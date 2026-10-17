package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.ContextWrapper
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueAiCacheFastlaneTest {
    @Test
    fun `pinned cache completes while another request owns Native without replacing or cancelling it`() = fixture {
        val active = register(1).also { it.processing = true }
        field("activeRequest").set(ManyueAiUpscaler, active)
        val cached = register(2)
        val states = mutableListOf<Int>()
        ManyueAiUpscaler.addListener(cached.token) { _, state, _ -> states += state }
        val pinned = pin(cached)
        assertTrue(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(cached.token) { pinned })
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(active, field("activeRequest").get(ManyueAiUpscaler))
        assertFalse(active.cancelled)
        assertTrue(active.processing)
        assertTrue(cached.completed)
        assertEquals(ManyueAiUpscaler.STATE_READY, cached.completionState)
        assertEquals(listOf(ManyueAiUpscaler.STATE_READY), states)
        assertEquals("exact cached output", cached.completionDetail)
        assertFalse(cached.inputFile.exists())
        assertFalse(cached.cacheOnlyClaim)
        assertNull(cached.cachedImageLease)
        assertTrue(closed(pinned.lease))
        assertFalse(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(cached.token) { error("completed probe") })
        // A missing runtime/model asset tree is intentional: READY can only be from cache.
    }

    @Test
    fun `production factory path pins the exact disk cache and never starts the inference queue`() = fixture {
        val req = register(20)
        val context = requireNotNull(ManyueAiUpscaler.appContext)
        val key = requireNotNull(ManyueAiUpscaler.cacheKeyForToken(req.token))
        val output = File(dir, "cached-source.webp").apply { writeText("exact output bytes") }
        assertTrue(ManyueEnhancementCache.putImage(context, key, output, 200, 400, detail = "disk hit"))
        ManyueAiUpscaler.queueWithCacheProbe(req.token)
        assertTrue(req.completed)
        assertEquals(ManyueAiUpscaler.STATE_READY, req.completionState)
        assertEquals("disk hit", req.completionDetail)
        assertEquals("exact output bytes", requireNotNull(ManyueAiUpscaler.cachedImage(context, req.token)).file.readText())
        assertFalse(req.processing)
        assertFalse(req.queued)
    }

    @Test
    fun `simultaneous factory callers and queue cannot steal the cache probe owner`() = fixture {
        val cached = register(3)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                assertTrue(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(cached.token) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    pin(cached)
                })
            } catch (failure: Throwable) { error.set(failure) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(cached.token) { error("second probe") })
            ManyueAiUpscaler.queue(cached.token)
            assertFalse(cached.queued)
            assertTrue(cached.cacheOnlyClaim)
        } finally { resume.countDown(); worker.join(6000) }
        assertFalse(worker.isAlive)
        error.get()?.let { throw it }
        assertTrue(cached.completed)
        assertEquals(ManyueAiUpscaler.STATE_READY, cached.completionState)
    }

    @Test
    fun `miss retains original input and gates with no repeated disk probe`() = fixture {
        val miss = register(4)
        assertFalse(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(miss.token) { null })
        assertTrue(miss.inputFile.isFile)
        assertFalse(miss.processing)
        assertFalse(miss.completed)
        assertFalse(miss.cacheOnlyClaim)
        assertTrue(ManyueAiUpscaler.shouldApplyPreInferenceGates(miss))
        assertFalse(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(miss.token) { error("repeat miss lookup") })
    }

    @Test
    fun `public cache miss enters the gated queue exactly once instead of disappearing`() = fixture {
        val gateOwner = Any()
        ManyueReaderWorkGate.update(gateOwner, active = true)
        ManyueReaderWorkGate.reportThermalStatus(gateOwner, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
        val req = register(21)
        // Severe gating is part of this test's host fixture, not device thermal injection.
        try {
            assertTrue(ManyueReaderWorkGate.isAiWorkBlocked(120))
            ManyueAiUpscaler.queueWithCacheProbe(req.token)
            ManyueAiUpscaler.queueWithCacheProbe(req.token)
            assertTrue(req.queued)
            assertFalse(req.processing)
            assertFalse(req.completed)
            assertSame(ManyueAiCacheProbe.Miss, req.cachePreflight.outcome)
            val queue = field("queue").get(ManyueAiUpscaler) as java.util.concurrent.BlockingQueue<*>
            assertTrue(queue.count { it == req } <= 1)
        } finally {
            ManyueAiUpscaler.cancel(req.token)
            ManyueReaderWorkGate.release(gateOwner)
        }
    }

    @Test
    fun `queued processing hybrid and stale requests never read disk on factory path`() = fixture {
        val queued = register(5).also { it.queued = true }
        val processing = register(6).also { it.processing = true }
        val hybrid = register(7, ManyueAiModel.QUALITY_HYBRID)
        listOf(queued, processing, hybrid).forEach { req ->
            assertFalse(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(req.token) { error("ineligible probe") })
        }
        val stale = register(8)
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.OFF.value)
        assertFalse(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(stale.token) { error("stale probe") })
    }

    @Test
    fun `cancellation or generation change during pin never publishes ready and releases lease`() = fixture {
        listOf(false, true).forEachIndexed { index, changeMode ->
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
            val req = register(10 + index)
            val states = mutableListOf<Int>()
            ManyueAiUpscaler.addListener(req.token) { _, state, _ -> states += state }
            val pinned = pin(req)
            assertTrue(ManyueAiUpscaler.tryCompleteUnqueuedFromCache(req.token) {
                if (changeMode) ManyueRuntimeState.updateMode(ManyueEnhancementMode.OFF.value)
                else ManyueAiUpscaler.cancel(req.token)
                pinned
            })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ManyueAiUpscaler.STATE_CANCELLED, req.completionState)
            assertFalse(states.contains(ManyueAiUpscaler.STATE_READY))
            assertTrue(req.terminal.isCompleted)
            assertTrue(closed(pinned.lease))
            assertFalse(req.inputFile.exists())
        }
    }

    private class Fixture(val dir: File) {
        val tokens = mutableListOf<String>()
        fun register(index: Int, model: ManyueAiModel = ManyueAiModel.QUALITY_NATIVE2): ManyueAiUpscaler.Request {
            val token = ManyueAiUpscaler.register(
                mangaId = 9100, chapterId = 9200, pageIndex = index,
                inputFile = File(dir, "$index.input").apply { writeText("input") },
                sourceWidth = 100, sourceHeight = 200, targetMode = ManyueEnhancementMode.AI_2X.value,
                targetWidth = 200, priority = 120, generation = ManyueRuntimeState.generation,
                sourceFingerprint = UUID.randomUUID().toString(), model = model,
            )
            tokens += token
            return requests().getValue(token)
        }
        fun pin(req: ManyueAiUpscaler.Request): ManyueEnhancementCache.PinnedCachedImage =
            ManyueEnhancementCache.PinnedCachedImage(
                ManyueEnhancementCache.CachedImage(
                    File(dir, "${req.token}.webp").apply { writeText("unchanged cached bytes") },
                    200, 400, detail = "exact cached output",
                ),
                ManyueEnhancementCache.CacheLease(null),
            )
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val previousContext = ManyueAiUpscaler.appContext
        val previousModel = ManyueRuntimeState.aiModel
        val previousMode = ManyueRuntimeState.modeInt
        val previousActive = field("activeRequest").get(ManyueAiUpscaler)
        val dir = Files.createTempDirectory("manyue-fastlane").toFile()
        val fixture = Fixture(dir)
        try {
            ManyueRuntimeState.updateAiModel(ManyueAiModel.QUALITY_NATIVE2)
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
            ManyueAiUpscaler.appContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getCacheDir() = dir
            }
            fixture.block()
        } finally {
            fixture.tokens.forEach {
                ManyueAiUpscaler.removeListener(it)
                requests().remove(it)
            }
            field("activeRequest").set(ManyueAiUpscaler, previousActive)
            ManyueAiUpscaler.appContext = previousContext
            ManyueRuntimeState.updateAiModel(previousModel)
            ManyueRuntimeState.updateMode(previousMode)
            dir.deleteRecursively()
        }
    }

    companion object {
        private fun field(name: String) = ManyueAiUpscaler.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun requests() = field("requests").get(ManyueAiUpscaler) as ConcurrentHashMap<String, ManyueAiUpscaler.Request>
        private fun closed(lease: ManyueEnhancementCache.CacheLease): Boolean =
            (lease.javaClass.getDeclaredField("closed").apply { isAccessible = true }.get(lease) as java.util.concurrent.atomic.AtomicBoolean).get()
    }
}
