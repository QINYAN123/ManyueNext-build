package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadWorkerMonitorTest {
    @Test fun completedDownloadReleasesNeverEndingNetworkListener() = runTest {
        var downloading = true
        var listenerReleased = false
        val worker = launch {
            awaitDownloadCompletion(
                flow {
                    try {
                        emit(true)
                        awaitCancellation()
                    } finally {
                        listenerReleased = true
                    }
                },
                { downloading },
            )
        }
        runCurrent()
        assertFalse(worker.isCompleted)
        downloading = false
        advanceTimeBy(250)
        runCurrent()
        assertTrue(worker.isCompleted)
        assertTrue(listenerReleased)
    }

    @Test fun networkRestrictionEndsWorkerWhileDownloaderIsActive() = runTest {
        val allowed = MutableStateFlow(true)
        val worker = launch { awaitDownloadCompletion(allowed) { true } }
        runCurrent()
        assertFalse(worker.isCompleted)
        allowed.value = false
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertTrue(worker.isCompleted)
        assertTrue(allowed.subscriptionCount.value == 0)
    }

    @Test fun cancelledWorkerReleasesNetworkListener() = runTest {
        val allowed = MutableStateFlow(true)
        val worker = launch { awaitDownloadCompletion(allowed) { true } }
        runCurrent()
        assertTrue(allowed.subscriptionCount.value == 1)
        worker.cancelAndJoin()
        assertTrue(allowed.subscriptionCount.value == 0)
    }

    @Test fun activeDownloadSuspendsInsteadOfBusySpinning() = runTest {
        val allowed = MutableStateFlow(true)
        var checks = 0
        val worker = launch {
            awaitDownloadCompletion(allowed) {
                checks++
                true
            }
        }
        runCurrent()
        assertTrue(checks == 1)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(checks <= 5)
        assertFalse(worker.isCompleted)
        worker.cancelAndJoin()
    }
}
