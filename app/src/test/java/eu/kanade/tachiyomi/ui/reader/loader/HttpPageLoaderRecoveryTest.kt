package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import okhttp3.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
class HttpPageLoaderRecoveryTest {
    private fun fixture(test: (HttpPageLoader, ReaderPage, File, HttpSource, ChapterCache) -> Unit) {
        val directory = Files.createTempDirectory("source-recovery").toFile()
        val file = File(directory, "evicted.0")
        val chapter = ReaderChapter(mockk<eu.kanade.tachiyomi.data.database.models.Chapter>(relaxed = true))
        val page = ReaderPage(0, imageUrl = "https://example.invalid/page.png", stream = { file.inputStream() })
        page.chapter = chapter
        page.status = Page.State.Ready
        val source = mockk<HttpSource>()
        val cache = mockk<ChapterCache>()
        val response = mockk<Response>()
        every { cache.getImageFile(any()) } returns file
        every { cache.isImageInCache(any()) } answers { file.exists() }
        coEvery { source.getImage(page) } returns response
        every { cache.putImageToCache(any(), response) } answers { file.writeBytes(byteArrayOf(1, 2, 3)) }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            test(loader, page, file, source, cache)
        } finally {
            loader.recycle()
            directory.deleteRecursively()
        }
    }

    @Test fun missingReadySourceIsReloadedAndReopened() = fixture { loader, page, _, source, _ ->
        val bytes = runBlocking { withTimeout(5_000) { loader.openPageStream(page)!!.use { it.readBytes() } } }
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(Page.State.Ready, page.status)
        coVerify(exactly = 1) { source.getImage(page) }
    }

    @Test fun concurrentAiAndDecoderRecoveryDownloadsOnlyOnce() = fixture { loader, page, _, source, _ ->
        val outputs = runBlocking {
            withTimeout(5_000) {
                coroutineScope {
                    List(2) { async(Dispatchers.IO) { loader.openPageStream(page)!!.use { it.readBytes() } } }.awaitAll()
                }
            }
        }
        outputs.forEach { assertArrayEquals(byteArrayOf(1, 2, 3), it) }
        coVerify(exactly = 1) { source.getImage(page) }
    }

    @Test fun existingButDeniedFileDoesNotTriggerNetworkRetry() = fixture { loader, page, file, source, _ ->
        file.writeBytes(byteArrayOf(9))
        page.stream = { throw FileNotFoundException("denied") }
        assertThrows(FileNotFoundException::class.java) { runBlocking { loader.openPageStream(page) } }
        coVerify(exactly = 0) { source.getImage(page) }
    }

    @Test fun failedDownloadTerminatesInsteadOfRetryingForever() = fixture { loader, page, _, source, cache ->
        every { cache.putImageToCache(any(), any()) } throws IOException("offline")
        assertThrows(IOException::class.java) {
            runBlocking { withTimeout(5_000) { loader.openPageStream(page) } }
        }
        assertFalse(loader.recoverMissingSource(page, FileNotFoundException()))
        coVerify(exactly = 1) { source.getImage(page) }
    }

    @Test fun recycledLoaderCannotStartRecovery() = fixture { loader, page, _, source, _ ->
        loader.recycle()
        assertFalse(loader.recoverMissingSource(page, FileNotFoundException()))
        coVerify(exactly = 0) { source.getImage(page) }
    }

    @Test fun recycleDuringMissingFileCheckCannotEnqueueAfterShutdown() = fixture { loader, page, file, source, cache ->
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { cache.getImageFile(any()) } answers {
            checking.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            file
        }
        runBlocking {
            val recovering = async(Dispatchers.IO) { loader.recoverMissingSource(page, FileNotFoundException()) }
            check(checking.await(5, TimeUnit.SECONDS))
            loader.recycle()
            release.countDown()
            assertFalse(withTimeout(5_000) { recovering.await() })
        }
        assertEquals(Page.State.Ready, page.status)
        coVerify(exactly = 0) { source.getImage(page) }
    }

    @Test fun recyclingWakesRecoveryQueuedBehindAnotherDownload() = fixture { loader, page, _, source, _ ->
        val blockingPage = ReaderPage(1, imageUrl = "https://example.invalid/busy.png").apply { chapter = page.chapter }
        val started = CompletableDeferred<Unit>()
        coEvery { source.getImage(blockingPage) } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        runBlocking {
            withTimeout(5_000) {
                loader.retryPage(blockingPage)
                started.await()
                val opening = async(Dispatchers.IO) { loader.openPageStream(page) }
                while (page.status == Page.State.Ready) delay(5)
                assertEquals(Page.State.Queue, page.status)
                loader.recycle()
                try {
                    opening.await()
                    throw AssertionError("Recycled recovery unexpectedly succeeded")
                } catch (_: CancellationException) {
                    // The page remains Queue after the serial consumer is stopped: the
                    // loader lifecycle signal, not a fake Ready/Error, releases this waiter.
                }
            }
        }
        coVerify(exactly = 0) { source.getImage(page) }
    }
}
