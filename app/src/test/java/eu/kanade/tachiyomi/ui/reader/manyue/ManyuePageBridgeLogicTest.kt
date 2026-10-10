package eu.kanade.tachiyomi.ui.reader.manyue

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyuePageBridgeLogicTest {

    @Test fun `identity equality distinguishes pages`() {
        val a = ManyuePageBridge.Identity(1, 2, 3)
        val b = ManyuePageBridge.Identity(1, 2, 3)
        val c = ManyuePageBridge.Identity(1, 2, 4)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test fun `runtime state defaults to OFF`() {
        assertEquals(0, ManyueRuntimeState.modeInt)
    }

    @Test fun `OFF mode maps correctly`() {
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(0))
        assertEquals(ManyueEnhancementMode.AI_2X, ManyueEnhancementMode.fromInt(2))
        assertEquals(ManyueEnhancementMode.AI_2X_CLASSIC, ManyueEnhancementMode.fromInt(3))
    }

    @Test fun `classic combined strength scales and floors at 1`() {
        // AI+classic: level 1 -> round(0.45)=0 -> max(1,0)=1
        assertEquals(1, ManyueClassicEnhancer.effectiveLevel(1, true))
        assertEquals(11, ManyueClassicEnhancer.effectiveLevel(25, true))
        assertEquals(25, ManyueClassicEnhancer.effectiveLevel(25, false))
        assertEquals(0, ManyueClassicEnhancer.effectiveLevel(0, true))
        assertEquals(0, ManyueClassicEnhancer.effectiveLevel(0, false))
    }

    @Test fun `segment heights manifest build helper`() {
        val m = ManyueEnhancementCache.buildJsonObject(
            "parts" to 3,
            "w" to 2344,
            "h" to 5000,
        )
        assertTrue(m.contains("\"parts\":3"))
        assertTrue(m.contains("\"w\":2344"))
    }

    @Test fun `display details merge without losing partial result warnings`() {
        assertEquals(
            "Anime4KCPP 未生效；部分高清分块读取失败",
            ManyuePageBridge.mergeDisplayDetails(
                " Anime4KCPP 未生效 ",
                "部分高清分块读取失败",
                "Anime4KCPP 未生效",
            ),
        )
        assertNull(ManyuePageBridge.mergeDisplayDetails(null, " "))
    }

    @Test fun `offscreen holder waits before request enqueue and stale binding does not submit`() = runBlocking {
        val chapterId = 9_101L
        val pageIndex = 3
        val generation = ManyueRuntimeState.generation
        ManyuePrefetchManager.reset()
        ManyuePrefetchManager.onPageChanged(chapterId, pageIndex)
        val currentDecision = async(start = CoroutineStart.UNDISPATCHED) {
            ManyuePrefetchManager.awaitCurrentDecision(chapterId, pageIndex, generation)
        }
        val releaseGuard = PageDecisionReleaseGuard(chapterId, pageIndex, generation)
        var current = true
        var visible = false
        var enqueuedRequests = 0
        try {
            val reboundWaiter = async {
                awaitVisiblePageAction(
                    isCurrent = { current },
                    isVisible = { visible },
                ) {
                    try {
                        enqueuedRequests++
                        "stale-token"
                    } finally {
                        releaseGuard.markDecisionReached()
                    }
                }
            }
            yield()
            assertFalse(reboundWaiter.isCompleted)
            current = false
            assertEquals(VisibleActionResult.Stale, withTimeout(1_000L) { reboundWaiter.await() })
            releaseGuard.releaseIfReached()
            assertFalse(currentDecision.isCompleted, "rebind before submission must not release the page gate")
            assertEquals(0, enqueuedRequests)

            current = true
            val cancelledWaiter = async {
                awaitVisiblePageAction(
                    isCurrent = { current },
                    isVisible = { visible },
                ) {
                    try {
                        enqueuedRequests++
                        "cancelled-token"
                    } finally {
                        releaseGuard.markDecisionReached()
                    }
                }
            }
            yield()
            assertFalse(cancelledWaiter.isCompleted)
            cancelledWaiter.cancelAndJoin()
            releaseGuard.releaseIfReached()
            assertFalse(currentDecision.isCompleted, "cancelling before submission must not release the page gate")
            assertEquals(0, enqueuedRequests)

            val visibleWaiter = async {
                awaitVisiblePageAction(
                    isCurrent = { current },
                    isVisible = { visible },
                ) {
                    try {
                        enqueuedRequests++
                        "token"
                    } finally {
                        releaseGuard.markDecisionReached()
                    }
                }
            }
            yield()
            assertFalse(visibleWaiter.isCompleted, "an offscreen holder must not submit direct AI work")
            assertEquals(0, enqueuedRequests)
            visible = true
            assertEquals(
                VisibleActionResult.Completed("token"),
                withTimeout(1_000L) { visibleWaiter.await() },
                "a holder can submit after entering view",
            )
            assertEquals(1, enqueuedRequests)
            releaseGuard.releaseIfReached()
            withTimeout(1_000L) { currentDecision.await() }

            val nullChapterId = chapterId + 1
            val nullPageIndex = pageIndex + 1
            ManyuePrefetchManager.onPageChanged(nullChapterId, nullPageIndex)
            val nullResultDecision = async(start = CoroutineStart.UNDISPATCHED) {
                ManyuePrefetchManager.awaitCurrentDecision(nullChapterId, nullPageIndex, generation)
            }
            val nullResultGuard = PageDecisionReleaseGuard(nullChapterId, nullPageIndex, generation)
            val nullFactoryResult: VisibleActionResult<String?> = awaitVisiblePageAction(
                isCurrent = { current },
                isVisible = { true },
            ) {
                try {
                    enqueuedRequests++
                    null
                } finally {
                    nullResultGuard.markDecisionReached()
                }
            }
            assertEquals(VisibleActionResult.Completed<String?>(null), nullFactoryResult)
            nullResultGuard.releaseIfReached()
            withTimeout(1_000L) { nullResultDecision.await() }
            assertEquals(2, enqueuedRequests, "a completed null/skip result still counts as a decision")
        } finally {
            currentDecision.cancelAndJoin()
            ManyuePrefetchManager.reset()
        }
    }

    @Test fun `classic render rechecks visibility after waiting for the shared lock`() = runBlocking {
        val mutex = Mutex(locked = true)
        var offscreenPageVisible = true
        var offscreenPrepared = false
        val offscreenPreparation = async(start = CoroutineStart.UNDISPATCHED) {
            withVisibleRenderMutex(
                mutex = mutex,
                isCurrent = { true },
                isVisible = { offscreenPageVisible },
            ) {
                offscreenPrepared = true
                "offscreen"
            }
        }

        // The page was visible when it queued, but scrolled away before the lock became free.
        offscreenPageVisible = false
        mutex.unlock()
        val visibleResult = withTimeout(1_000L) {
            withVisibleRenderMutex(
                mutex = mutex,
                isCurrent = { true },
                isVisible = { true },
            ) { "visible" }
        }

        assertEquals("visible", (visibleResult as VisibleRenderResult.Prepared).value)
        assertFalse(offscreenPrepared, "an offscreen page must release the lock without classic work")

        offscreenPageVisible = true
        val offscreenResult = withTimeout(1_000L) { offscreenPreparation.await() }
        assertEquals("offscreen", (offscreenResult as VisibleRenderResult.Prepared).value)
        assertTrue(offscreenPrepared)
    }
}
