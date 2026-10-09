package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReadingCoverage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueReadingCoverageViewHelpersTest {

    @Test
    fun `exposure fractions do not depend on page direction`() {
        val viewport = ManyueCoverageBounds(0, 0, 100, 100)
        val topPage = manyueCoverageFractions(
            page = ManyueCoverageBounds(0, 0, 100, 200),
            visible = ManyueCoverageBounds(0, 0, 100, 100),
            viewport = viewport,
        )
        val bottomPage = manyueCoverageFractions(
            page = ManyueCoverageBounds(0, -100, 100, 100),
            visible = ManyueCoverageBounds(0, 0, 100, 100),
            viewport = viewport,
        )

        assertEquals(topPage, bottomPage)
        assertEquals(1000, topPage.viewportFractionPermille)
        assertEquals(500, topPage.pageFractionPermille)
        assertTrue(isManyueCoverageExposureEligible(topPage, isShortPage = false))
    }

    @Test
    fun `short page uses half page threshold while ordinary pages use viewport threshold`() {
        val pageOnlyHalf = ManyueCoverageFractions(5_000L, 100, 500)
        val viewportOnlyEnough = ManyueCoverageFractions(5_000L, 200, 100)

        assertTrue(isManyueCoverageExposureEligible(pageOnlyHalf, isShortPage = true))
        assertFalse(isManyueCoverageExposureEligible(pageOnlyHalf, isShortPage = false))
        assertTrue(isManyueCoverageExposureEligible(viewportOnlyEnough, isShortPage = false))
        assertFalse(isManyueCoverageExposureEligible(viewportOnlyEnough.copy(viewportFractionPermille = 199), false))
    }

    @Test
    fun `only an active ready visible child can report a matching AI output`() {
        val source = source()
        val result = result(source)

        assertTrue(policy(source, result, active = true, ready = true, shown = true, eligible = true))
        assertFalse(policy(source, result, active = false, ready = true, shown = true, eligible = true))
        assertFalse(policy(source, result, active = true, ready = false, shown = true, eligible = true))
        assertFalse(policy(source, result, active = true, ready = true, shown = false, eligible = true))
        assertFalse(policy(source, result, active = true, ready = true, shown = true, eligible = false))
        assertFalse(policy(source, result, active = true, ready = true, shown = true, eligible = true, width = 401))
    }

    @Test
    fun `wrong generation source or output dimensions cannot pass`() {
        val source = source()
        val result = result(source)

        assertFalse(
            policy(
                source,
                result.copy(generation = 8),
                active = true,
                ready = true,
                shown = true,
                eligible = true,
                generation = 7,
            ),
        )
        assertFalse(policy(source.copy(fingerprint = "other"), result, active = true, ready = true, shown = true, eligible = true))
        assertFalse(policy(source.copy(fingerprint = null), result, active = true, ready = true, shown = true, eligible = true))
        assertFalse(policy(source.copy(sourceWidth = 101), result, active = true, ready = true, shown = true, eligible = true))
        assertFalse(policy(source, result, active = true, ready = true, shown = true, eligible = true, height = 801))
        assertFalse(policy(source, result.copy(outputWidth = 0), active = true, ready = true, shown = true, eligible = true))
    }

    @Test
    fun `a reused page slot cannot report the prior source stamp`() {
        val firstSource = source(page = 4)
        val nextSource = source(page = 5)

        assertFalse(
            policy(
                nextSource,
                result(firstSource),
                active = true,
                ready = true,
                shown = true,
                eligible = true,
            ),
        )
    }

    @Test
    fun `only an active AI draw error can invalidate its exposure`() {
        assertTrue(
            ManyueCoverageDrawPolicy.shouldRecordFailure(
                isActiveChild = true,
                isShown = true,
                exposureEligible = true,
                imageIsAi = true,
            ),
        )
        assertFalse(
            ManyueCoverageDrawPolicy.shouldRecordFailure(
                isActiveChild = true,
                isShown = true,
                exposureEligible = true,
                imageIsAi = false,
            ),
        )
        assertFalse(
            ManyueCoverageDrawPolicy.shouldRecordFailure(
                isActiveChild = false,
                isShown = true,
                exposureEligible = true,
                imageIsAi = true,
            ),
        )
    }

    @Test
    fun `failed canvas draw reports failure and never success`() {
        var successes = 0
        var failures = 0

        assertThrows(IllegalStateException::class.java) {
            drawReaderContentThenReport(
                draw = { throw IllegalStateException("canvas failed") },
                onDraw = { successes++ },
                onFailure = { failures++ },
            )
        }

        assertEquals(0, successes)
        assertEquals(1, failures)
    }

    @Test
    fun `successful canvas draw reports only after draw returns`() {
        val calls = mutableListOf<String>()

        drawReaderContentThenReport(
            draw = { calls += "draw" },
            onDraw = { calls += "reported" },
            onFailure = { calls += "failed" },
        )

        assertEquals(listOf("draw", "reported"), calls)
    }

    private fun policy(
        source: ManyueReadingCoverage.Source,
        result: ManyueReadingCoverage.Result?,
        active: Boolean,
        ready: Boolean,
        shown: Boolean,
        eligible: Boolean,
        width: Int = 400,
        height: Int = 800,
        generation: Long? = result?.generation,
    ) = ManyueCoverageDrawPolicy.shouldReport(
        isActiveChild = active,
        isImageReady = ready,
        isShown = shown,
        exposureEligible = eligible,
        source = source,
        result = result,
        imageIsAi = true,
        expectedGeneration = generation,
        actualOutputWidth = width,
        actualOutputHeight = height,
    )

    private fun source(page: Int = 4) = ManyueReadingCoverage.Source(
        mangaId = 2L,
        chapterId = 3L,
        page = page,
        fingerprint = "fp-$page",
        sourceWidth = 100,
        sourceHeight = 200,
    )

    private fun result(source: ManyueReadingCoverage.Source) = ManyueReadingCoverage.Result(
        source = source,
        generation = 7L,
        model = 1,
        mode = 2,
        scalePercent = 200,
        outputWidth = 400,
        outputHeight = 800,
        token = "token-${source.page}",
    )
}
