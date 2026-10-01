package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManyueAutoEnhancementPolicyTest {
    @Test fun `a small display width difference avoids an unnecessary fixed two times network`() {
        assertEquals(ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE, ManyueAutoEnhancementPolicy.decide(1000, 1050).path)
        assertEquals(ManyueAutoEnhancementPolicy.Path.AI, ManyueAutoEnhancementPolicy.decide(1000, 1051).path)
    }
    @Test fun `source wide enough stays at original size regardless of its height`() {
        assertEquals(ManyueAutoEnhancementPolicy.Decision(ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE, 1440), ManyueAutoEnhancementPolicy.decide(1440, 1080))
        assertEquals(ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE, ManyueAutoEnhancementPolicy.decide(690, 690).path)
    }
    @Test fun `low resolution uses the actual reading width with a two times ceiling`() {
        assertEquals(1035, ManyueAutoEnhancementPolicy.decide(690, 1035).targetWidth)
        assertEquals(1380, ManyueAutoEnhancementPolicy.decide(690, 2000).targetWidth)
        assertEquals(ManyueAutoEnhancementPolicy.Path.AI, ManyueAutoEnhancementPolicy.decide(690, 1035).path)
    }
    @Test fun `manual maximum is respected and one times avoids useless upscale work`() {
        assertEquals(863, ManyueAutoEnhancementPolicy.decide(690, 1200, 125).targetWidth)
        assertEquals(ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE, ManyueAutoEnhancementPolicy.decide(690, 1200, 100).path)
    }
    @Test fun `unmeasured width defers the decision instead of treating an image as clear`() {
        assertEquals(ManyueAutoEnhancementPolicy.Path.WAIT_FOR_WIDTH, ManyueAutoEnhancementPolicy.decide(690, 0).path)
        assertEquals(ManyueAutoEnhancementPolicy.Path.WAIT_FOR_WIDTH, ManyueAutoEnhancementPolicy.decide(0, 1080).path)
    }
}
