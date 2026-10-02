package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueSchedulerLifecycleTest {
    @Test fun newlyAttachedListenersSeeResourceWaitRatherThanInference() = runBlocking {
        val token = register(87)
        val req = request(token)
        try {
            req.processing = true
            req.progressState = ManyueAiUpscaler.STATE_WAITING_RESOURCES
            val states = mutableListOf<Int>()
            ManyueAiUpscaler.addListener(token) { _, state, _ -> states += state }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(ManyueAiUpscaler.STATE_WAITING_RESOURCES), states)
            assertFalse(req.terminal.isCompleted)
            ManyueAiUpscaler.cancel(token)
            assertEquals(ManyueAiUpscaler.STATE_CANCELLED, req.terminal.await())
        } finally {
            ManyueAiUpscaler.removeListener(token)
            ManyueAiUpscaler.cancel(token)
        }
    }

    @Test fun completionWaitersObserveCancellationWithoutDrainingMainLooper() = runBlocking {
        val token = register(81)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            ManyueAiUpscaler.awaitCompletion(token)
        }
        assertFalse(waiting.isCompleted)
        ManyueAiUpscaler.cancel(token)
        withTimeout(1_000L) {
            assertEquals(ManyueAiUpscaler.STATE_CANCELLED, waiting.await())
            assertEquals(ManyueAiUpscaler.STATE_CANCELLED, ManyueAiUpscaler.awaitCompletion(token))
            assertEquals(ManyueAiUpscaler.STATE_FAILED, ManyueAiUpscaler.awaitCompletion("missing-token"))
        }
    }

    @Test fun cancellingACompletionWaiterDoesNotCancelTheSharedVisibleRequest() = runBlocking {
        val token = register(82)
        try {
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                ManyueAiUpscaler.awaitCompletion(token)
            }
            waiting.cancelAndJoin()
            assertFalse(request(token).cancelled)
            assertFalse(ManyueAiUpscaler.isFinished(token))
        } finally {
            ManyueAiUpscaler.cancel(token)
        }
        assertTrue(ManyueAiUpscaler.isFinished(token))
    }

    private fun register(index: Int, chapterId: Long = 92): String = ManyueAiUpscaler.register(
        91, chapterId, index, Files.createTempFile("manyue-lifecycle", ".bin").toFile(),
        100, 200, 2, 200, 10, expectedMode = 2, generation = 991,
        sourceFingerprint = "lifecycle-$index",
    )

    @Suppress("UNCHECKED_CAST")
    private fun request(token: String): ManyueAiUpscaler.Request {
        val field = ManyueAiUpscaler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
        return (field.get(ManyueAiUpscaler) as ConcurrentHashMap<String, ManyueAiUpscaler.Request>).getValue(token)
    }

    @Test fun cancellationBeforeListenerRegistrationStillDeliversTerminalState() {
        val token = register(1)
        ManyueAiUpscaler.cancel(token)
        val states = mutableListOf<Int>()
        ManyueAiUpscaler.addListener(token) { _, state, _ -> states += state }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(ManyueAiUpscaler.STATE_CANCELLED), states)
    }

    @Test fun missingApplicationContextCannotLeaveAClaimedRequestProcessingForever() {
        val token = register(2)
        val req = request(token)
        val states = mutableListOf<Int>()
        ManyueAiUpscaler.addListener(token) { _, state, _ -> states += state }
        val saved = ManyueAiUpscaler.appContext
        try {
            ManyueAiUpscaler.appContext = null
            ManyueAiUpscaler.javaClass.getDeclaredMethod("process", ManyueAiUpscaler.Request::class.java)
                .apply { isAccessible = true }.invoke(ManyueAiUpscaler, req)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(req.completed)
            assertEquals(listOf(ManyueAiUpscaler.STATE_FAILED), states)
            assertFalse(req.inputFile.exists())
        } finally {
            ManyueAiUpscaler.appContext = saved
        }
    }

    @Test fun selectingBottomVisibleStripRetainsAllVisiblePageRequests() {
        ManyuePrefetchManager.reset()
        val tokens = (1..4).associateWith(::register)
        try {
            tokens.forEach { (index, token) -> ManyuePrefetchManager.register(92, index, token) }
            ManyuePrefetchManager.onPageChanged(92, 4, setOf(92L to 2, 92L to 3, 92L to 4))
            assertTrue(request(tokens.getValue(1)).cancelled)
            (2..4).forEach { index ->
                val req = request(tokens.getValue(index))
                assertFalse(req.cancelled)
                assertEquals(if (index == 4) 120 else 100, req.priority)
            }
            ManyuePrefetchManager.updateVisiblePages(setOf(92L to 3, 92L to 4))
            assertFalse(request(tokens.getValue(2)).cancelled)
            assertFalse(request(tokens.getValue(3)).cancelled)
            ManyuePrefetchManager.onPageChanged(92, 6, setOf(92L to 6))
            assertTrue(request(tokens.getValue(2)).cancelled)
        } finally {
            ManyuePrefetchManager.reset()
        }
    }

    @Test fun chapterChangeKeepsRetainedAndVisibleRequestsUntilTheyLeaveTheWindow() {
        ManyuePrefetchManager.reset()
        ManyuePrefetchManager.onPageChanged(92, 10, setOf(92L to 9, 93L to 0))
        val retainedNextPage = register(index = 0, chapterId = 93)
        val visiblePreviousPage = register(index = 9, chapterId = 92)
        ManyuePrefetchManager.register(93, 0, retainedNextPage)
        ManyuePrefetchManager.register(92, 9, visiblePreviousPage)
        val retainedRequest = request(retainedNextPage)
        val previousRequest = request(visiblePreviousPage)
        try {
            // ViewerChapters now makes the retained page the current page. Both the new current
            // request and the previous chapter page still on screen must survive this handoff.
            ManyuePrefetchManager.onChapterChanged(
                chapterId = 93,
                current = 0,
                visible = setOf(93L to 0, 92L to 9),
            )
            assertFalse(retainedRequest.cancelled)
            assertFalse(previousRequest.cancelled)

            // A request that was visible in the previous chapter remains able to finish.
            previousRequest.completionState = ManyueAiUpscaler.STATE_READY
            previousRequest.completed = true
            val completionStates = mutableListOf<Int>()
            ManyueAiUpscaler.addListener(visiblePreviousPage) { _, state, _ -> completionStates += state }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(ManyueAiUpscaler.STATE_READY), completionStates)

            val fifthAhead = register(index = 5, chapterId = 93)
            ManyuePrefetchManager.register(93, 5, fifthAhead)
            val fifthRequest = request(fifthAhead)
            assertFalse(fifthRequest.cancelled)

            // Far-behind work is cancelled, while the near previous page and completed
            // visible-page result remain reusable.
            ManyuePrefetchManager.onPageChanged(93, 6, visible = setOf(93L to 6))
            assertTrue(retainedRequest.cancelled)
            assertFalse(fifthRequest.cancelled)
            ManyuePrefetchManager.onPageChanged(93, 8, visible = setOf(93L to 8))
            assertTrue(fifthRequest.cancelled)
            assertFalse(previousRequest.cancelled)
        } finally {
            ManyuePrefetchManager.reset()
        }
    }

    @Test fun chapterChangeRetainsSelectedTokenWhenRecyclerTemporarilyReportsNoVisiblePages() {
        ManyuePrefetchManager.reset()
        ManyuePrefetchManager.onPageChanged(92, 10, visible = setOf(93L to 0))
        val selectedPage = register(index = 0, chapterId = 93)
        ManyuePrefetchManager.register(93, 0, selectedPage)
        val selectedRequest = request(selectedPage)
        try {
            ManyuePrefetchManager.onChapterChanged(93, 0, visible = emptySet())

            assertFalse(selectedRequest.cancelled)
            assertEquals(120, selectedRequest.priority)
            runBlocking {
                withTimeout(1_000L) {
                    ManyuePrefetchManager.awaitCurrentDecision(93, 0)
                }
            }
        } finally {
            ManyuePrefetchManager.reset()
        }
    }

    @Test fun completedCacheTokenRemainsUsableAfterItLeavesThePrefetchWindow() {
        val token = register(5)
        val req = request(token)
        req.completionState = ManyueAiUpscaler.STATE_READY
        req.completed = true
        ManyueAiUpscaler.cancel(token)
        assertFalse(req.cancelled)
        val states = mutableListOf<Int>()
        ManyueAiUpscaler.addListener(token) { _, state, _ -> states += state }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(ManyueAiUpscaler.STATE_READY), states)
        req.inputFile.delete()
    }
}
