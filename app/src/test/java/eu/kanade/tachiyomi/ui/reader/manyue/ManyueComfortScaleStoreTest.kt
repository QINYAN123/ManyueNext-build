package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueComfortScaleStoreTest {
    @Test
    fun `reopening preserves book and screen specific shrink while resetting only the current entry`() {
        val shared = RuntimeEnvironment.getApplication().getSharedPreferences("comfort-${System.nanoTime()}", Context.MODE_PRIVATE)
        val prefs = ReaderPreferences(AndroidPreferenceStore(shared))
        val store = ManyueComfortScaleStore(prefs.manyueComfortScales)
        try {
            store.save(11, true, 0.8f)
            store.save(11, false, 0.9f)
            store.save(12, true, 0.7f)
            val reopened = ManyueComfortScaleStore(ReaderPreferences(AndroidPreferenceStore(shared)).manyueComfortScales)
            assertEquals(0.8f, reopened.get(11, true)!!, 0.001f)
            assertEquals(0.9f, reopened.get(11, false)!!, 0.001f)
            assertEquals(0.7f, reopened.get(12, true)!!, 0.001f)
            reopened.save(11, true, null)
            assertNull(reopened.get(11, true))
            assertEquals(0.9f, reopened.get(11, false)!!, 0.001f)
            assertEquals(0.7f, reopened.get(12, true)!!, 0.001f)
            reopened.save(12, true, 1.5f)
            assertNull(reopened.get(12, true))
        } finally {
            shared.edit().clear().apply()
        }
    }

    @Test
    fun `corrupt entries and nonfinite scales never become a transform`() {
        val shared = RuntimeEnvironment.getApplication().getSharedPreferences("comfort-invalid-${System.nanoTime()}", Context.MODE_PRIVATE)
        val prefs = ReaderPreferences(AndroidPreferenceStore(shared))
        val store = ManyueComfortScaleStore(prefs.manyueComfortScales)
        try {
            prefs.manyueComfortScales.set(setOf("11:i:NaN", "11:o:1200", "12:i:500"))
            assertNull(store.get(11, true))
            assertNull(store.get(11, false))
            store.save(11, true, Float.NaN)
            store.save(11, false, Float.POSITIVE_INFINITY)
            store.save(0, true, 0.8f)
            assertNull(store.get(11, true))
            assertNull(store.get(11, false))
            assertNull(store.get(0, true))
            assertEquals(0.5f, store.get(12, true)!!, 0.001f)
        } finally {
            shared.edit().clear().apply()
        }
    }
}
