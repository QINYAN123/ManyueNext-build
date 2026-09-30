package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class ManyueEncodingCompatibilityTest {
    @Test fun sdk29UsesAvailableLossyWebpFormat() {
        assertSame(Bitmap.CompressFormat.WEBP, ManyueBitmapEncoding.lossyWebpFormat())
    }
}
