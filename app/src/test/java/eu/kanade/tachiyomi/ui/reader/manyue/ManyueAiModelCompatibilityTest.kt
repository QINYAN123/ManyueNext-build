package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueAiModelCompatibilityTest {

    @Test
    fun `only quality ESRGAN and Lite are offered as model choices`() {
        assertEquals(
            listOf(ManyueAiModel.QUALITY_REAL_ESRGAN, ManyueAiModel.MOBILE_LITE),
            ManyueAiModel.selectableModels,
        )
        assertFalse(ManyueAiModel.selectableModels.contains(ManyueAiModel.FAST_REAL_CUGAN))
    }

    @Test
    fun `legacy CUGAN id resolves to quality ESRGAN and is saved during preference sync`() {
        val context = RuntimeEnvironment.getApplication()
        val preferenceName = "manyue-ai-model-compat-${System.nanoTime()}"
        val sharedPreferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val readerPreferences = ReaderPreferences(AndroidPreferenceStore(sharedPreferences))
        val oldState = RuntimeSnapshot.capture()

        try {
            assertEquals(
                ManyueAiModel.QUALITY_REAL_ESRGAN.id,
                ManyueAiModel.canonicalPreferenceId(ManyueAiModel.FAST_REAL_CUGAN.id),
            )
            assertEquals(
                ManyueAiModel.QUALITY_REAL_ESRGAN,
                ManyueAiModel.fromId(ManyueAiModel.FAST_REAL_CUGAN.id),
            )
            assertEquals(ManyueAiModel.MOBILE_LITE, ManyueAiModel.fromId(ManyueAiModel.MOBILE_LITE.id))

            // Also migrate when the older Lite migration marker is already set.
            for (migrationAlreadyMarked in listOf(false, true)) {
                readerPreferences.manyueAiModel.set(ManyueAiModel.FAST_REAL_CUGAN.id)
                readerPreferences.manyueLiteModelMigrationDone.set(migrationAlreadyMarked)

                ManyueRuntimeState.syncFrom(readerPreferences)

                assertEquals(ManyueAiModel.QUALITY_REAL_ESRGAN.id, readerPreferences.manyueAiModel.get())
                assertEquals(ManyueAiModel.QUALITY_REAL_ESRGAN, ManyueRuntimeState.aiModel)
                assertTrue(readerPreferences.manyueLiteModelMigrationDone.get())
            }
        } finally {
            oldState.restore()
            sharedPreferences.edit().clear().apply()
        }
    }

    @Test
    fun `unknown saved model still falls back to the configured default`() {
        assertEquals(ManyueAiModel.DEFAULT, ManyueAiModel.fromId("removed-or-unknown-model"))
    }

    @Test
    fun `quality ignores saved fractional output without erasing the Lite choice`() {
        val context = RuntimeEnvironment.getApplication()
        val shared = context.getSharedPreferences("quality-fixed-scale-${System.nanoTime()}", Context.MODE_PRIVATE)
        val prefs = ReaderPreferences(AndroidPreferenceStore(shared))
        val old = RuntimeSnapshot.capture()
        try {
            prefs.manyueAiScalePercent.set(125)
            prefs.manyueAiModel.set(ManyueAiModel.QUALITY_REAL_ESRGAN.id)
            ManyueRuntimeState.syncFrom(prefs)
            assertEquals(200, ManyueRuntimeState.aiScalePercent)
            assertEquals(125, prefs.manyueAiScalePercent.get())
            prefs.manyueAiModel.set(ManyueAiModel.MOBILE_LITE.id)
            ManyueRuntimeState.syncFrom(prefs)
            assertEquals(125, ManyueRuntimeState.aiScalePercent)
        } finally {
            old.restore()
            shared.edit().clear().apply()
        }
    }

    private data class RuntimeSnapshot(
        val mode: Int,
        val classicStrength: Int,
        val model: ManyueAiModel,
        val scale: Int,
        val detailStrength: Int,
        val anime4kOverlay: Boolean,
        val diagnosticsEnabled: Boolean,
        val foldableMode: Int,
        val foldableTargetWidth: Int,
    ) {
        fun restore() {
            ManyueRuntimeState.updateMode(mode)
            ManyueRuntimeState.updateClassicStrength(classicStrength)
            ManyueRuntimeState.updateAiModel(model)
            ManyueRuntimeState.updateAiScale(scale)
            ManyueRuntimeState.updateAiDetailStrength(detailStrength)
            ManyueRuntimeState.updateAnime4kOverlay(anime4kOverlay)
            ManyueRuntimeState.foldableMode = foldableMode
            ManyueRuntimeState.foldableTargetWidth = foldableTargetWidth
            ManyuePerformanceDiagnostics.setEnabled(diagnosticsEnabled)
            ManyuePrefetchManager.reset()
        }

        companion object {
            fun capture() = RuntimeSnapshot(
                mode = ManyueRuntimeState.modeInt,
                classicStrength = ManyueRuntimeState.classicStrength,
                model = ManyueRuntimeState.aiModel,
                scale = ManyueRuntimeState.aiScalePercent,
                detailStrength = ManyueRuntimeState.aiDetailStrength,
                anime4kOverlay = ManyueRuntimeState.anime4kOverlay,
                diagnosticsEnabled = ManyuePerformanceDiagnostics.enabled,
                foldableMode = ManyueRuntimeState.foldableMode,
                foldableTargetWidth = ManyueRuntimeState.foldableTargetWidth,
            )
        }
    }
}
