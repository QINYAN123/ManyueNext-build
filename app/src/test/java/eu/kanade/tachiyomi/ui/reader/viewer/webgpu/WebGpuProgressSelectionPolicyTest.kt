package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebGpuProgressSelectionPolicyTest {
    private val transition = WebGpuTransitionKey(previousChapterId = 31L, nextChapterId = 32L)

    @Test
    fun rejectsPreviousTransitionAfterToolbarJumpAndReverseCrossing() {
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset() // setChapters / moveToPage(0) establish the new anchor

        policy.recordPageStep(transition, direction = -1, eventEpoch = epoch)

        assertFalse(policy.claimTransitionProgress(transition, epoch, anchorChapterId = 32L))
    }

    @Test
    fun acceptsPreviousChapterCompletionAfterAForwardTransitionCrossingOnce() {
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()

        policy.recordPageStep(transition, direction = 1, eventEpoch = epoch)

        assertTrue(policy.claimTransitionProgress(transition, epoch, anchorChapterId = 32L))
        assertFalse(policy.claimTransitionProgress(transition, epoch, anchorChapterId = 32L))
    }

    @Test
    fun eachPageStepMustActuallyTouchTheTransition() {
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        val neverVisited = WebGpuTransitionKey(previousChapterId = 32L, nextChapterId = 33L)

        // A three-page positive walk reports the one transition encountered by its intermediate
        // steps. Its final delta sign alone must not make an unvisited transition eligible.
        policy.recordPageStep(transition, direction = 1, eventEpoch = epoch)
        policy.recordPageStep(key = null, direction = 1, eventEpoch = epoch)
        policy.recordPageStep(key = null, direction = 1, eventEpoch = epoch)

        assertTrue(policy.claimTransitionProgress(transition, epoch, anchorChapterId = 32L))
        // A real chapter-32 image anchor would independently authorize its own end geometry.
        // Use chapter 33 here to isolate missing crossing evidence from that valid fallback.
        assertFalse(policy.claimTransitionProgress(neverVisited, epoch, anchorChapterId = 33L))
    }

    @Test
    fun queuedSelectionIsRejectedAfterNavigationResetOrPageReplacement() {
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        val eventPage = Any()

        assertTrue(policy.isCurrentDelivery(epoch, eventPage, eventPage))
        assertFalse(policy.isCurrentDelivery(epoch, eventPage, Any()))

        policy.reset()

        assertFalse(policy.isCurrentDelivery(epoch, eventPage, eventPage))
    }

    @Test
    fun ordinaryImagePageProgressDoesNotNeedTransitionCrossingEvidence() {
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()

        assertTrue(policy.canSelectImagePage(epoch))
        assertFalse(policy.claimTransitionProgress(transition, epoch, anchorChapterId = 32L))
    }

    @Test
    fun previousChapterAnchorAllowsTransitionReadThroughAfterRealReverseReturn() {
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()

        policy.recordPageStep(transition, direction = -1, eventEpoch = epoch)

        // The actual page-zero image is back in chapter 31, so the old last page has reached
        // read-through geometry even though the most recent transition crossing was backward.
        assertTrue(policy.claimTransitionProgress(transition, epoch, anchorChapterId = 31L))
    }

    @Test
    fun sameChapterTableRefreshPreservesNaturalForwardClaim() {
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        policy.recordPageStep(transition, direction = 1, eventEpoch = epoch)

        assertFalse(policy.shouldResetForChapterTable(retainedAnchorChapterId = 32L, incomingChapterId = 32L))
        assertTrue(policy.claimTransitionProgress(transition, epoch, anchorChapterId = 32L))
    }

    @Test
    fun changedChapterTableAndExplicitMoveInvalidateOldCrossing() {
        val policy = WebGpuProgressSelectionPolicy()
        val oldEpoch = policy.reset()
        policy.recordPageStep(transition, direction = 1, eventEpoch = oldEpoch)

        assertTrue(policy.shouldResetForChapterTable(retainedAnchorChapterId = 31L, incomingChapterId = 32L))
        val newEpoch = policy.reset() // setChapters for a toolbar jump or explicit moveToPage

        assertFalse(policy.claimTransitionProgress(transition, oldEpoch, anchorChapterId = 32L))
        assertFalse(policy.claimTransitionProgress(transition, newEpoch, anchorChapterId = 32L))
    }

    @Test
    fun terminalTransitionCanCompleteFinalChapter() {
        val terminal = WebGpuTransitionKey(previousChapterId = 42L, nextChapterId = null)
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()

        policy.recordPageStep(terminal, direction = 1, eventEpoch = epoch)

        assertTrue(policy.claimTransitionProgress(terminal, epoch, anchorChapterId = null))
    }
}
