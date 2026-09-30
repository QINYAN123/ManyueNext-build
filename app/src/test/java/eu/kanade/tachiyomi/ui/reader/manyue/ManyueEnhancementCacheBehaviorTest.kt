package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueEnhancementCacheBehaviorTest {

    @Test fun `clear removes unleased current pages but keeps a live displayed image`() = runBlocking {
        val root = Files.createTempDirectory("manyue-cache-clear-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(701L, 801L, 6)
        ManyueEnhancementCache.updateReadingPosition(identity.mangaId, identity.chapterId, identity.pageIndex)
        try {
            val displayedKey = putImage(context, identity, "displayed")
            val otherKey = putImage(context, identity.copy(pageIndex = 7), "other")
            val displayed = ManyueEnhancementCache.pinCachedImage(context, displayedKey)
            val otherBundle = File(ManyueEnhancementCache.cacheDir(context), otherKey)
            val displayedBundle = File(ManyueEnhancementCache.cacheDir(context), displayedKey)
            assertNotNull(displayed)
            try {
                ManyueEnhancementCache.clearCache(context)
                awaitCondition { !otherBundle.exists() }
                assertNotNull(ManyueEnhancementCache.getImage(context, displayedKey))
            } finally {
                displayed?.lease?.close()
            }

            ManyueEnhancementCache.clearCache(context)
            awaitCondition { !displayedBundle.exists() }
        } finally {
            ManyueEnhancementCache.clearReadingPosition(identity.mangaId, identity.chapterId)
            root.deleteRecursively()
        }
    }

    @Test fun `prefetch reservation gates writes and a cancelled waiter releases its slot`() = runBlocking {
        val root = Files.createTempDirectory("manyue-cache-budget-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(702L, 802L, 3)
        val reservation = ManyueEnhancementCache.awaitPrefetchBudget(
            context,
            identity.mangaId,
            identity.chapterId,
            identity.pageIndex,
            estimatedBytes = MAX_CACHE_BYTES,
        )
        try {
            val other = identity.copy(pageIndex = 4)
            val blockedSource = File(root, "blocked.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val blockedKey = cacheKey(other)
            assertFalse(
                ManyueEnhancementCache.putImage(
                    context,
                    blockedKey,
                    blockedSource,
                    2,
                    2,
                    readingIdentity = other,
                ),
            )
            assertNull(ManyueEnhancementCache.getImage(context, blockedKey))

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                ManyueEnhancementCache.awaitPrefetchBudget(
                    context,
                    other.mangaId,
                    other.chapterId,
                    other.pageIndex,
                    estimatedBytes = 1L,
                )
            }
            assertFalse(waiter.isCompleted)
            awaitCondition { ManyueEnhancementCache.pendingBudgetWaiterCountForTest() > 0 }
            waiter.cancelAndJoin()
        } finally {
            reservation.close()
        }

        val afterRelease = ManyueEnhancementCache.awaitPrefetchBudget(
            context,
            identity.mangaId,
            identity.chapterId,
            identity.pageIndex,
            estimatedBytes = MAX_CACHE_BYTES,
        )
        afterRelease.close()
        root.deleteRecursively()
        Unit
    }

    @Test fun `reading progress releases a waiter blocked by a full protected cache`() = runBlocking {
        val root = Files.createTempDirectory("manyue-cache-protected-budget-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val unread = ManyueEnhancementCache.ReadingIdentity(704L, 804L, 5)
        ManyueEnhancementCache.updateReadingPosition(unread.mangaId, unread.chapterId, unread.pageIndex)
        try {
            val protectedKey = putImage(context, unread, "unread")
            val bundle = ManyueEnhancementCache.cacheDir(context).resolve(protectedKey)
            val padding = File(bundle, "budget-padding.bin")
            val currentBytes = ManyueEnhancementCache.cacheSizeBytes(context)
            RandomAccessFile(padding, "rw").use { it.setLength(MAX_CACHE_BYTES - currentBytes) }
            assertTrue(ManyueEnhancementCache.cacheSizeBytes(context) >= MAX_CACHE_BYTES)

            val next = unread.copy(pageIndex = 10)
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                ManyueEnhancementCache.awaitPrefetchBudget(
                    context,
                    next.mangaId,
                    next.chapterId,
                    next.pageIndex,
                    estimatedBytes = 1L,
                )
            }
            assertFalse(waiter.isCompleted)
            awaitCondition { ManyueEnhancementCache.pendingBudgetWaiterCountForTest() == 1 }
            assertNotNull(ManyueEnhancementCache.getImage(context, protectedKey))

            ManyueEnhancementCache.updateReadingPosition(unread.mangaId, unread.chapterId, 8)
            val acquired = withTimeout(5_000L) { waiter.await() }
            acquired.close()
            assertNull(ManyueEnhancementCache.getImage(context, protectedKey))
        } finally {
            ManyueEnhancementCache.clearReadingPosition(unread.mangaId, unread.chapterId)
            root.deleteRecursively()
        }
    }

    @Test fun `a damaged active cache bundle can be rebuilt for its current page`() {
        val root = Files.createTempDirectory("manyue-cache-repair-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(705L, 805L, 4)
        ManyueEnhancementCache.updateReadingPosition(identity.mangaId, identity.chapterId, identity.pageIndex)
        try {
            val key = putImage(context, identity, "first")
            val damaged = ManyueEnhancementCache.getImage(context, key) ?: error("cache image not created")
            assertTrue(damaged.file.delete())

            val repairedSource = File(root, "repair.webp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            assertTrue(
                ManyueEnhancementCache.putImage(
                    context,
                    key,
                    repairedSource,
                    2,
                    2,
                    readingIdentity = identity,
                ),
            )
            assertNotNull(ManyueEnhancementCache.getImage(context, key))
        } finally {
            ManyueEnhancementCache.clearReadingPosition(identity.mangaId, identity.chapterId)
            root.deleteRecursively()
        }
    }

    @Test fun `trim counts legacy disk bytes and preserves the active unread cache bundle`() = runBlocking {
        val root = Files.createTempDirectory("manyue-cache-trim-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(703L, 803L, 9)
        ManyueEnhancementCache.updateReadingPosition(identity.mangaId, identity.chapterId, identity.pageIndex)
        try {
            val key = putImage(context, identity, "protected")
            val legacy = File(root, "manyue_ai_anime_fixed2_v6").apply { mkdirs() }
            val oldEntry = File(legacy, "old-output.bin")
            RandomAccessFile(oldEntry, "rw").use { it.setLength(MAX_CACHE_BYTES + 1L) }
            assertTrue(ManyueEnhancementCache.cacheSizeBytes(context) > MAX_CACHE_BYTES)

            ManyueEnhancementCache.trimCache(context)
            awaitCondition { !oldEntry.exists() }
            assertNotNull(ManyueEnhancementCache.getImage(context, key))
        } finally {
            ManyueEnhancementCache.clearReadingPosition(identity.mangaId, identity.chapterId)
            root.deleteRecursively()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }

    private fun putImage(
        context: Context,
        identity: ManyueEnhancementCache.ReadingIdentity,
        suffix: String,
    ): String {
        val source = File(context.cacheDir, "$suffix.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val key = cacheKey(identity)
        assertTrue(
            ManyueEnhancementCache.putImage(
                context,
                key,
                source,
                2,
                2,
                readingIdentity = identity,
            ),
        )
        return key
    }

    private fun cacheKey(identity: ManyueEnhancementCache.ReadingIdentity): String =
        ManyueEnhancementCache.cacheKey(
            identity.mangaId,
            identity.chapterId,
            identity.pageIndex,
            0,
            0,
            2,
            0,
        )

    private class IsolatedCacheContext(base: Context, private val isolatedCacheDir: File) : ContextWrapper(base) {
        override fun getCacheDir(): File = isolatedCacheDir.apply { mkdirs() }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 805_306_368L
    }
}
