package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `quality graph switch keeps saved IDs isolated and invalidates in-flight results`() {
        val oldState = RuntimeSnapshot.capture()
        try {
            val original = ManyueAiModel.QUALITY_REAL_ESRGAN
            val native2 = ManyueAiModel.QUALITY_NATIVE2
            assertEquals(2, original.ordinal)
            assertEquals("realesrgan_quality", original.id)
            assertNotEquals(original.runtimeDir, native2.runtimeDir)
            assertNotEquals(original.cacheIdentity, native2.cacheIdentity)
            assertEquals(native2, ManyueAiModel.fromId(native2.id))
            assertTrue(original.usesQualityRuntime)
            assertTrue(native2.usesQualityRuntime)
            assertFalse(ManyueAiModel.MOBILE_LITE.usesQualityRuntime)
            assertEquals(
                ManyueAiRuntime.requiredLegacyRuntimeHashes(original),
                ManyueAiRuntime.requiredLegacyRuntimeHashes(native2),
            )
            ManyueRuntimeState.updateAiModel(original)
            val generation = ManyueRuntimeState.generation
            ManyueRuntimeState.updateAiModel(native2)
            assertEquals(generation + 1, ManyueRuntimeState.generation)
            assertEquals(200, ManyueRuntimeState.aiScalePercent)
            ManyueRuntimeState.updateAiModel(native2)
            assertEquals(generation + 1, ManyueRuntimeState.generation)
        } finally {
            oldState.restore()
        }
    }

    @Test
    fun `viewport detail retirement preserves AI and comfort settings`() {
        val context = RuntimeEnvironment.getApplication()
        val shared = context.getSharedPreferences("viewport-retirement-${System.nanoTime()}", Context.MODE_PRIVATE)
        val prefs = ReaderPreferences(AndroidPreferenceStore(shared))
        val old = RuntimeSnapshot.capture()
        try {
            prefs.manyueAiModel.set(ManyueAiModel.MOBILE_LITE.id)
            prefs.manyueEnhancementMode.set(2)
            prefs.manyueAiScalePercent.set(137)
            prefs.manyueAiDetailStrength.set(60)
            prefs.manyueComfortScales.set(setOf("retained-comfort-value"))
            prefs.manyueGpuDisplayFilter.set(true)
            prefs.manyueGpuDisplayStrength.set(100)
            ManyueRuntimeState.syncFrom(prefs)
            assertFalse(prefs.manyueGpuDisplayFilter.get())
            assertTrue(prefs.manyueViewportDetailRetired.get())
            assertEquals(100, prefs.manyueGpuDisplayStrength.get())
            assertEquals(ManyueAiModel.MOBILE_LITE.id, prefs.manyueAiModel.get())
            assertEquals(137, prefs.manyueAiScalePercent.get())
            assertEquals(2, prefs.manyueEnhancementMode.get())
            assertEquals(60, prefs.manyueAiDetailStrength.get())
            assertEquals(setOf("retained-comfort-value"), prefs.manyueComfortScales.get())
        } finally {
            old.restore()
            shared.edit().clear().apply()
        }
    }

    @Test
    fun `completed retirement does not disable benchmark comparison on every page`() {
        val context = RuntimeEnvironment.getApplication()
        val shared = context.getSharedPreferences("viewport-retirement-repeat-${System.nanoTime()}", Context.MODE_PRIVATE)
        val prefs = ReaderPreferences(AndroidPreferenceStore(shared))
        val old = RuntimeSnapshot.capture()
        try {
            ManyueRuntimeState.syncFrom(prefs)
            prefs.manyueGpuDisplayFilter.set(true)
            ManyueRuntimeState.syncFrom(prefs)
            assertTrue(prefs.manyueGpuDisplayFilter.get())
        } finally {
            old.restore()
            shared.edit().clear().apply()
        }
    }

    @Test
    fun `original and native2 quality plus Lite are offered while CUGAN stays retired`() {
        assertEquals(
            listOf(ManyueAiModel.QUALITY_REAL_ESRGAN, ManyueAiModel.QUALITY_NATIVE2, ManyueAiModel.MOBILE_LITE),
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
        assertEquals(ManyueAiModel.QUALITY_REAL_ESRGAN, ManyueAiModel.DEFAULT)
        assertEquals(ManyueAiModel.DEFAULT, ManyueAiModel.fromId("removed-or-unknown-model"))
        assertEquals(ManyueAiModel.MOBILE_LITE, ManyueAiModel.fromId(ManyueAiModel.MOBILE_LITE.id))
    }

    @Test
    fun `legacy enhancement modes and Anime overlay migrate while saved Lite remains selected`() {
        val context = RuntimeEnvironment.getApplication()
        val shared = context.getSharedPreferences("mode-retirement-${System.nanoTime()}", Context.MODE_PRIVATE)
        val prefs = ReaderPreferences(AndroidPreferenceStore(shared))
        val old = RuntimeSnapshot.capture()
        try {
            prefs.manyueAiModel.set(ManyueAiModel.MOBILE_LITE.id)
            for ((savedMode, expectedMode) in listOf(1 to 0, 3 to 2, 4 to 4)) {
                prefs.manyueEnhancementMode.set(savedMode)
                prefs.manyueAnime4kOverlay.set(true)

                ManyueRuntimeState.syncFrom(prefs)

                assertEquals(expectedMode, prefs.manyueEnhancementMode.get())
                assertEquals(expectedMode, ManyueRuntimeState.modeInt)
                assertFalse(prefs.manyueAnime4kOverlay.get())
                assertFalse(ManyueRuntimeState.anime4kOverlay)
                assertEquals(ManyueAiModel.MOBILE_LITE.id, prefs.manyueAiModel.get())
                assertEquals(ManyueAiModel.MOBILE_LITE, ManyueRuntimeState.aiModel)
            }
        } finally {
            old.restore()
            shared.edit().clear().apply()
        }
    }

    @Test
    fun `neutral color policy isolates previous quality results without invalidating Lite`() {
        fun key(modelIdentity: String) = ManyueEnhancementCache.cacheKey(
            mangaId = 1, chapterId = 2, pageIndex = 3, mode = 2,
            targetMode = 2, targetWidth = 2560, classicStrength = 0,
            sourceFingerprint = "same-input", modelId = modelIdentity, benchmarkNamespace = 0,
        )
        val quality = ManyueAiModel.QUALITY_REAL_ESRGAN
        assertNotEquals(key(quality.id), key(quality.cacheIdentity))
        val lite = ManyueAiModel.MOBILE_LITE
        assertEquals(key("${lite.id}:${ManyueLiteAssets.MODEL_REVISION}"), key(lite.cacheIdentity))
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
