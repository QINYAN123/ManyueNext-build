package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

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

            ManyueEnhancementCache.updateReadingPosition(unread.mangaId, unread.chapterId, 11)
            val acquired = withTimeout(5_000L) { waiter.await() }
            acquired.close()
            assertNull(ManyueEnhancementCache.getImage(context, protectedKey))
        } finally {
            ManyueEnhancementCache.clearReadingPosition(unread.mangaId, unread.chapterId)
            root.deleteRecursively()
        }
    }

    @Test fun `prefetch reservation waits instead of evicting unread forward result and resumes after head passes it`() = runBlocking {
        val root = Files.createTempDirectory("manyue-cache-forward-retention-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val future = ManyueEnhancementCache.ReadingIdentity(708L, 808L, 20)
        ManyueEnhancementCache.updateReadingPosition(future.mangaId, future.chapterId, 10)
        try {
            val oldChapterKey = putImage(context, future.copy(chapterId = 807L, pageIndex = 3), "old-chapter")
            val futureKey = putImage(context, future, "future-forward")
            val padding = File(ManyueEnhancementCache.cacheDir(context).resolve(futureKey), "budget-padding.bin")
            RandomAccessFile(padding, "rw").use { it.setLength(MAX_CACHE_BYTES - ManyueEnhancementCache.cacheSizeBytes(context)) }

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                ManyueEnhancementCache.awaitPrefetchBudget(context, future.mangaId, future.chapterId, 11, 1_000_000L)
            }
            awaitCondition { ManyueEnhancementCache.pendingBudgetWaiterCountForTest() == 1 }
            assertFalse(waiter.isCompleted)
            assertNull("old chapters remain eligible for eviction", ManyueEnhancementCache.getImage(context, oldChapterKey))
            assertNotNull("a fixed forward head keeps its unread future result", ManyueEnhancementCache.getImage(context, futureKey))

            ManyueEnhancementCache.updateReadingPosition(future.mangaId, future.chapterId, 26)
            withTimeout(5_000L) { waiter.await() }.close()
            assertNull("the passed result can be evicted after the head moves", ManyueEnhancementCache.getImage(context, futureKey))
        } finally {
            ManyueEnhancementCache.clearReadingPosition(future.mangaId, future.chapterId)
            root.deleteRecursively()
        }
    }

    @Test fun `visible page write can evict unread far future cache under pressure`() {
        val root = Files.createTempDirectory("manyue-cache-visible-write-eviction-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val visible = ManyueEnhancementCache.ReadingIdentity(709L, 809L, 10)
        val future = visible.copy(pageIndex = 20)
        ManyueEnhancementCache.updateReadingPosition(visible.mangaId, visible.chapterId, visible.pageIndex)
        try {
            val futureKey = putImage(context, future, "future-visible-write")
            val padding = File(ManyueEnhancementCache.cacheDir(context).resolve(futureKey), "budget-padding.bin")
            RandomAccessFile(padding, "rw").use { it.setLength(MAX_CACHE_BYTES - ManyueEnhancementCache.cacheSizeBytes(context)) }

            val visibleSource = File(root, "visible-page.webp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val visibleKey = cacheKey(visible)
            assertTrue(
                ManyueEnhancementCache.putImage(
                    context,
                    visibleKey,
                    visibleSource,
                    2,
                    2,
                    readingIdentity = visible,
                ),
            )
            assertNotNull(ManyueEnhancementCache.getImage(context, visibleKey))
            assertNull("visible output can reclaim speculative future cache", ManyueEnhancementCache.getImage(context, futureKey))
        } finally {
            ManyueEnhancementCache.clearReadingPosition(visible.mangaId, visible.chapterId)
            root.deleteRecursively()
        }
    }

    @Test fun `backward prefetch reservation retains unread pages in reverse direction`() = runBlocking {
        val root = Files.createTempDirectory("manyue-cache-backward-retention-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val upcoming = ManyueEnhancementCache.ReadingIdentity(710L, 810L, 10)
        ManyueEnhancementCache.updateReadingPosition(upcoming.mangaId, upcoming.chapterId, 20)
        ManyueEnhancementCache.updateReadingPosition(upcoming.mangaId, upcoming.chapterId, 19)
        try {
            val upcomingKey = putImage(context, upcoming, "future-backward")
            val padding = File(ManyueEnhancementCache.cacheDir(context).resolve(upcomingKey), "budget-padding.bin")
            RandomAccessFile(padding, "rw").use { it.setLength(MAX_CACHE_BYTES - ManyueEnhancementCache.cacheSizeBytes(context)) }

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                ManyueEnhancementCache.awaitPrefetchBudget(context, upcoming.mangaId, upcoming.chapterId, 18, 1L)
            }
            awaitCondition { ManyueEnhancementCache.pendingBudgetWaiterCountForTest() == 1 }
            assertFalse(waiter.isCompleted)
            assertNotNull(ManyueEnhancementCache.getImage(context, upcomingKey))

            ManyueEnhancementCache.updateReadingPosition(upcoming.mangaId, upcoming.chapterId, 4)
            withTimeout(5_000L) { waiter.await() }.close()
            assertNull(ManyueEnhancementCache.getImage(context, upcomingKey))
        } finally {
            ManyueEnhancementCache.clearReadingPosition(upcoming.mangaId, upcoming.chapterId)
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

    @Test fun `invalid classic variant waits for its readers but not the displayed base image`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("manyue-cache-classic-invalidate-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(706L, 806L, 4)
        val key = putImage(context, identity, "classic-base")
        val variant = File(ManyueEnhancementCache.cacheDir(context), "$key/classic_luma_v3_25.img")
            .apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val basePin = ManyueEnhancementCache.pinCachedImage(context, key)
        val variantPin = ManyueEnhancementCache.pinCachedImage(context, key, 25)
        val viewPin = ManyueEnhancementCache.pinFile(context, variant)
        try {
            assertNotNull(basePin)
            assertNotNull(variantPin)
            assertNotNull(viewPin)
            ManyueEnhancementCache.invalidateClassicImage(context, key, 25)

            assertNull(
                "new readers must stop taking a variant already marked corrupt",
                ManyueEnhancementCache.pinCachedImage(context, key, 25),
            )
            assertTrue("an existing decoder lease keeps its file until it closes", variant.isFile)
            assertNotNull("the valid base result stays usable", ManyueEnhancementCache.getImage(context, key))

            variantPin?.lease?.close()
            assertTrue("a staged view's file lease also delays deletion", variant.exists())
            viewPin?.close()
            awaitCondition { !variant.exists() }
            val baseStillAvailable = ManyueEnhancementCache.pinCachedImage(context, key)
            assertNotNull(
                "the displayed base-image lease must not block corrupt-variant cleanup",
                baseStillAvailable,
            )
            baseStillAvailable?.lease?.close()
        } finally {
            variantPin?.lease?.close()
            viewPin?.close()
            basePin?.lease?.close()
            root.deleteRecursively()
        }
    }

    @Test fun `ordinary classic removal does not leave a corrupt marker behind`() {
        val root = Files.createTempDirectory("manyue-cache-classic-remove-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(707L, 807L, 4)
        try {
            val key = putImage(context, identity, "classic-remove")
            val variant = File(ManyueEnhancementCache.cacheDir(context), "$key/classic_luma_v3_25.img")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }

            ManyueEnhancementCache.removeClassicImage(context, key, 25)
            assertFalse(variant.exists())

            variant.writeBytes(byteArrayOf(4, 5, 6))
            val lease = ManyueEnhancementCache.pinFile(context, variant)
            assertNotNull("generic removal must not mark a future replacement as corrupt", lease)
            lease?.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `stale deferred invalidation cannot delete a rebuilt classic variant`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("manyue-cache-classic-rebuild-race-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(708L, 808L, 4)
        val key = putImage(context, identity, "classic-rebuild-race")
        val variant = File(ManyueEnhancementCache.cacheDir(context), "$key/classic_luma_v3_25.img")
            .apply { writeBytes(byteArrayOf(7, 7, 7)) }
        val variantPin = ManyueEnhancementCache.pinCachedImage(context, key, 25)
            ?: error("classic variant was not pinnable")
        val diskLock = ManyueEnhancementCache::class.java.getDeclaredField("diskLock")
            .apply { isAccessible = true }
            .get(ManyueEnhancementCache)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        try {
            ManyueEnhancementCache.invalidateClassicImage(context, key, 25)
            synchronized(diskLock) {
                // Closing the final file lease queues deletion on Dispatchers.IO. Keep that worker
                // at the disk lock while rebuilding so its stale invalidation id runs afterward.
                variantPin.lease.close()
                awaitDeferredInvalidationWorker(waiting = true)
                assertNotNull(
                    "rebuild should replace the invalid variant while old cleanup is queued",
                    ManyueEnhancementCache.putClassicImage(context, key, 25, bitmap),
                )
            }
            awaitDeferredInvalidationWorker(waiting = false)

            assertTrue("stale cleanup must preserve the replacement", variant.isFile)
            val rebuiltPin = ManyueEnhancementCache.pinCachedImage(context, key, 25)
            assertNotNull("the rebuilt variant must be available to a fresh reader", rebuiltPin)
            rebuiltPin?.lease?.close()
        } finally {
            bitmap.recycle()
            variantPin.lease.close()
            root.deleteRecursively()
        }
    }

    @Test fun `captured cache key still pins and invalidates after request pruning`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("manyue-cache-pruned-request-test").toFile()
        val context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), root)
        val identity = ManyueEnhancementCache.ReadingIdentity(709L, 809L, 4)
        val requestsField = ManyueAiUpscaler::class.java.getDeclaredField("requests").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val requests = requestsField.get(ManyueAiUpscaler) as MutableMap<String, ManyueAiUpscaler.Request>
        val requestKeysField = ManyueAiUpscaler::class.java.getDeclaredField("tokensByRequestKey")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val tokensByRequestKey = requestKeysField.get(ManyueAiUpscaler) as MutableMap<String, String>
        val token = ManyueAiUpscaler.register(
            mangaId = identity.mangaId,
            chapterId = identity.chapterId,
            pageIndex = identity.pageIndex,
            inputFile = File(root, "request.input"),
            sourceWidth = 2,
            sourceHeight = 2,
            targetMode = ManyueAiModel.MOBILE_LITE.scale,
            targetWidth = 2,
            priority = 100,
            expectedMode = ManyueEnhancementMode.AI_2X.value,
            generation = 809L,
            sourceFingerprint = "pruned-token-cache-key-test",
            model = ManyueAiModel.MOBILE_LITE,
        )
        var requestKey: String? = null

        try {
            requestKey = requests[token]?.requestKey ?: error("request was not registered")
            val capturedKey = ManyueAiUpscaler.cacheKeyForToken(token)
                ?: error("request cache key was not available to the holder")
            requests.remove(token)
            tokensByRequestKey.remove(requestKey, token)
            val source = File(root, "pruned-request.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val variant = File(ManyueEnhancementCache.cacheDir(context), "$capturedKey/classic_luma_v3_25.img")

            assertNull(
                "token lookup is unavailable after request pruning",
                ManyueAiUpscaler.pinnedCachedImage(context, token),
            )
            assertTrue(ManyueEnhancementCache.putImage(context, capturedKey, source, 2, 2))
            variant.writeBytes(byteArrayOf(5, 4, 3))

            val basePin = ManyueAiUpscaler.pinnedCachedImageForKey(context, capturedKey)
            assertNotNull("the holder's captured key keeps base cache access independent of token retention", basePin)
            basePin?.lease?.close()

            ManyueAiUpscaler.invalidateClassicImageForKey(context, capturedKey, 25)
            awaitCondition { !variant.exists() }
            val legacyStrengthPin = ManyueAiUpscaler.pinnedCachedImageForKey(context, capturedKey, 25)
            assertNotNull("retired strength arguments must still select the base AI image", legacyStrengthPin)
            assertEquals("image.webp", legacyStrengthPin?.image?.file?.name)
            legacyStrengthPin?.lease?.close()
            val baseStillAvailable = ManyueAiUpscaler.pinnedCachedImageForKey(context, capturedKey)
            assertNotNull("invalidating a variant leaves the base cache entry usable", baseStillAvailable)
            baseStillAvailable?.lease?.close()
        } finally {
            requests.remove(token)
            requestKey?.let { tokensByRequestKey.remove(it, token) }
            root.deleteRecursively()
        }
    }

    private fun awaitDeferredInvalidationWorker(waiting: Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val active = Thread.getAllStackTraces().any { (thread, trace) ->
                thread !== Thread.currentThread() && trace.any {
                    it.className.contains("ManyueEnhancementCache") &&
                        it.methodName == "deleteInvalidClassicVariant"
                }
            }
            if (active == waiting) return
            Thread.sleep(5L)
        }
        assertTrue("deferred invalidation worker did not reach expected state: waiting=$waiting", false)
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
