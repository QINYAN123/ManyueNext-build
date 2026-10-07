package eu.kanade.tachiyomi.data.download

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Release the network listener when the downloader finishes, pauses, or the worker is cancelled. */
internal suspend fun awaitDownloadCompletion(
    networkAllowed: Flow<Boolean>,
    isDownloading: () -> Boolean,
) = coroutineScope {
    val networkBlocked = async { networkAllowed.first { !it } }
    try {
        while (isDownloading() && !networkBlocked.isCompleted) {
            delay(250)
        }
        if (networkBlocked.isCompleted) networkBlocked.await()
    } finally {
        networkBlocked.cancelAndJoin()
    }
    Unit
}
