package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManyueModelAssessmentTest {
    @Test fun `missing measurements are not converted into zero or optimistic ratings`() {
        for (model in ManyueAiModel.selectableModels) {
            val assessment = ManyueModelAssessment.forModel(model)
            assertNull(assessment.speed.value)
            assertNull(assessment.energy.value)
            assertNull(assessment.sustained.value)
            assertEquals("待测", assessment.energy.display())
        }
    }

    @Test fun `out of range subjective ratings fail instead of showing misleading scores`() {
        assertThrows(IllegalArgumentException::class.java) { ManyueModelAssessment.Rating(0, "invalid") }
        assertThrows(IllegalArgumentException::class.java) { ManyueModelAssessment.Rating(6, "invalid") }
    }
}
