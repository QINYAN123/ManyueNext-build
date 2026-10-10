package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebtoonComfortScalePolicyTest {

    @Test fun `remember only deliberate shrink scales`() {
        assertEquals(0.8f, WebtoonComfortScalePolicy.rememberedScale(0.8f) ?: -1f, 0f)
        assertNull(WebtoonComfortScalePolicy.rememberedScale(1f))
        assertNull(WebtoonComfortScalePolicy.rememberedScale(1.8f))
        assertNull(WebtoonComfortScalePolicy.rememberedScale(0.4f))
        assertNull(WebtoonComfortScalePolicy.rememberedScale(Float.NaN))
    }

    @Test fun `shrunk recycler keeps complete viewport height centered`() {
        val viewportHeight = 1001
        val contentHeight = WebtoonComfortScalePolicy.contentHeight(viewportHeight, 0.8f)

        assertEquals(1252, contentHeight)
        assertEquals(-125.5f, WebtoonComfortScalePolicy.verticalOffset(viewportHeight, contentHeight), 0f)
    }

    @Test fun `viewport resize recomputes expanded height from current viewport`() {
        val oldHeight = WebtoonComfortScalePolicy.contentHeight(1000, 0.8f)
        val newHeight = WebtoonComfortScalePolicy.contentHeight(700, 0.8f)

        assertEquals(1250, oldHeight)
        assertEquals(875, newHeight)
    }
}
