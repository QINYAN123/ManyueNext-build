package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.ContextWrapper
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueAiCachePreflightTest {

    @Test
    fun `cache hit bypasses pre-inference gates and the probe is memoized`() {
        val preflight = ManyueAiCachePreflight<Any>()
        val image = Any()
        var probes = 0

        assertFalse(preflight.bypassesPreInferenceGates)
        val first = preflight.probeOnce { probes++; image }
        val repeated = preflight.probeOnce { probes++; null }

        assertTrue(first is ManyueAiCacheProbe.Hit<*>)
        assertSame(first, repeated)
        assertTrue(preflight.bypassesPreInferenceGates)
        assertEquals(1, probes)
    }

    @Test
    fun `cache miss and probe failure remain gated without repeated reads`() {
        val missRequest = makeRequest(ManyueAiModel.QUALITY_REAL_ESRGAN)
        val failedRequest = makeRequest(ManyueAiModel.QUALITY_REAL_ESRGAN)
        var probes = 0
        val error = IllegalStateException("cache read failed")

        assertTrue(ManyueAiUpscaler.shouldApplyPreInferenceGates(missRequest))
        assertSame(ManyueAiCacheProbe.Miss, missRequest.cachePreflight.probeOnce { probes++; null })
        assertSame(ManyueAiCacheProbe.Miss, missRequest.cachePreflight.probeOnce { probes++; null })
        assertTrue(ManyueAiUpscaler.shouldApplyPreInferenceGates(missRequest))

        val failed = failedRequest.cachePreflight.probeOnce { probes++; throw error }
        assertTrue(failed is ManyueAiCacheProbe.Failed)
        assertSame(error, (failed as ManyueAiCacheProbe.Failed).error)
        assertSame(failed, failedRequest.cachePreflight.probeOnce { probes++; null })
        assertFalse(failedRequest.cachePreflight.bypassesPreInferenceGates)
        assertTrue(ManyueAiUpscaler.shouldApplyPreInferenceGates(failedRequest))
        assertEquals(2, probes)
    }

    @Test
    fun `invalidated hit becomes a gated miss without another preflight`() {
        val request = makeRequest(ManyueAiModel.QUALITY_REAL_ESRGAN)
        var preflightReads = 0
        var revalidationReads = 0

        request.cachePreflight.probeOnce {
            preflightReads++
            ManyueEnhancementCache.CachedImage(File("preflight.webp"), 200, 400)
        }
        assertFalse(ManyueAiUpscaler.shouldApplyPreInferenceGates(request))

        assertSame(
            ManyueAiCacheProbe.Miss,
            request.cachePreflight.revalidateHit { revalidationReads++; null },
        )
        assertTrue(ManyueAiUpscaler.shouldApplyPreInferenceGates(request))
        assertSame(ManyueAiCacheProbe.Miss, request.cachePreflight.probeOnce { preflightReads++; null })
        assertEquals(1, preflightReads)
        assertEquals(1, revalidationReads)
    }

    @Test
    fun `hybrid cache preflight waits for a physical model identity`() {
        val fixed = makeRequest(ManyueAiModel.QUALITY_REAL_ESRGAN)
        val hybrid = makeRequest(ManyueAiModel.QUALITY_HYBRID)

        assertFalse(ManyueAiUpscaler.shouldPreflightCacheBeforeGate(fixed, hasContext = false))
        assertTrue(ManyueAiUpscaler.shouldPreflightCacheBeforeGate(fixed, hasContext = true))
        assertFalse(ManyueAiUpscaler.shouldPreflightCacheBeforeGate(hybrid, hasContext = true))

        hybrid.bindPhysicalModel(ManyueAiModel.QUALITY_NATIVE2)
        assertTrue(ManyueAiUpscaler.shouldPreflightCacheBeforeGate(hybrid, hasContext = true))
    }

    @Test
    fun `preflight hit completes through the existing ready listener path`() {
        val isolatedDir = Files.createTempDirectory("manyue-ai-preflight").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = isolatedDir.apply { mkdirs() }
        }
        val previousContext = ManyueAiUpscaler.appContext
        val previousMode = ManyueRuntimeState.modeInt
        val previousModel = ManyueRuntimeState.aiModel
        var token: String? = null
        val states = mutableListOf<Int>()
        val gateOwner = Any()

        try {
            ManyueRuntimeState.updateAiModel(ManyueAiModel.QUALITY_REAL_ESRGAN)
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
            ManyueReaderWorkGate.update(gateOwner, active = true)
            ManyueReaderWorkGate.reportThermalStatus(gateOwner, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
            assertTrue(ManyueReaderWorkGate.isAiWorkBlocked(priority = 100))
            ManyueAiUpscaler.appContext = context
            val generation = ManyueRuntimeState.generation
            val id = UUID.randomUUID().toString()
            val input = File(isolatedDir, "$id.input").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            token = ManyueAiUpscaler.register(
                mangaId = 91,
                chapterId = 92,
                pageIndex = 3,
                inputFile = input,
                sourceWidth = 100,
                sourceHeight = 200,
                targetMode = ManyueEnhancementMode.AI_2X.value,
                targetWidth = 200,
                priority = 100,
                expectedMode = ManyueEnhancementMode.AI_2X.value,
                generation = generation,
                sourceFingerprint = id,
                model = ManyueAiModel.QUALITY_REAL_ESRGAN,
            )
            val request = makeRequest(requireNotNull(token))
            val key = requireNotNull(ManyueAiUpscaler.cacheKeyForToken(token!!))
            val cachedFile = File(isolatedDir, "$id.webp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            assertTrue(ManyueEnhancementCache.putImage(context, key, cachedFile, 200, 400, detail = "cached"))
            val probe = request.cachePreflight.probeOnce {
                ManyueEnhancementCache.getImage(context, key)
            }
            assertTrue(probe is ManyueAiCacheProbe.Hit<*>)
            var pinned: ManyueEnhancementCache.PinnedCachedImage? = null
            val validated = request.cachePreflight.revalidateHit {
                pinned = ManyueEnhancementCache.pinCachedImage(context, key)
                request.cachedImageLease = pinned?.lease
                pinned?.image
            }
            assertTrue(validated is ManyueAiCacheProbe.Hit<*>)
            assertNotNull(pinned)
            assertFalse(ManyueAiUpscaler.shouldApplyPreInferenceGates(request))
            ManyueAiUpscaler.addListener(token!!) { _, state, _ -> states += state }

            ManyueAiUpscaler.javaClass.getDeclaredMethod("process", ManyueAiUpscaler.Request::class.java)
                .apply { isAccessible = true }
                .invoke(ManyueAiUpscaler, request)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(request.completed)
            assertEquals(ManyueAiUpscaler.STATE_READY, request.completionState)
            assertEquals(200, request.outputWidth)
            assertEquals(400, request.outputHeight)
            assertEquals("cached", request.completionDetail)
            assertEquals(listOf(ManyueAiUpscaler.STATE_READY), states)
            assertNotNull(ManyueAiUpscaler.coverageResultForToken(token!!))
            assertTrue(requireNotNull(pinned).image.file.isFile)
        } finally {
            token?.let {
                ManyueAiUpscaler.removeListener(it)
                ManyueAiUpscaler.cancel(it)
                removeRegisteredRequest(it)
            }
            ManyueAiUpscaler.appContext = previousContext
            ManyueRuntimeState.updateAiModel(previousModel)
            ManyueRuntimeState.updateMode(previousMode)
            ManyueReaderWorkGate.release(gateOwner)
            isolatedDir.deleteRecursively()
        }
    }

    @Test
    fun `cancelled cache hit cannot complete READY`() {
        val previousContext = ManyueAiUpscaler.appContext
        val previousMode = ManyueRuntimeState.modeInt
        val previousModel = ManyueRuntimeState.aiModel
        val states = mutableListOf<Int>()
        val isolatedDir = Files.createTempDirectory("manyue-ai-cancelled-hit").toFile()
        val input = File(isolatedDir, "input.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var token: String? = null

        try {
            ManyueRuntimeState.updateAiModel(ManyueAiModel.QUALITY_REAL_ESRGAN)
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
            ManyueAiUpscaler.appContext = RuntimeEnvironment.getApplication()
            token = ManyueAiUpscaler.register(
                mangaId = 991,
                chapterId = 992,
                pageIndex = 3,
                inputFile = input,
                sourceWidth = 100,
                sourceHeight = 200,
                targetMode = ManyueEnhancementMode.AI_2X.value,
                targetWidth = 200,
                priority = 100,
                expectedMode = ManyueEnhancementMode.AI_2X.value,
                generation = ManyueRuntimeState.generation,
                sourceFingerprint = UUID.randomUUID().toString(),
                model = ManyueAiModel.QUALITY_REAL_ESRGAN,
            )
            val request = makeRequest(requireNotNull(token))
            request.cachePreflight.probeOnce {
                ManyueEnhancementCache.CachedImage(File("cancelled.webp"), 200, 400)
            }
            ManyueAiUpscaler.addListener(requireNotNull(token)) { _, state, _ -> states += state }
            ManyueAiUpscaler.cancel(requireNotNull(token))

            ManyueAiUpscaler.javaClass.getDeclaredMethod("process", ManyueAiUpscaler.Request::class.java)
                .apply { isAccessible = true }
                .invoke(ManyueAiUpscaler, request)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(request.completed)
            assertEquals(ManyueAiUpscaler.STATE_CANCELLED, request.completionState)
            assertEquals(listOf(ManyueAiUpscaler.STATE_CANCELLED), states)
        } finally {
            token?.let {
                ManyueAiUpscaler.removeListener(it)
                ManyueAiUpscaler.cancel(it)
                removeRegisteredRequest(it)
            }
            ManyueAiUpscaler.appContext = previousContext
            ManyueRuntimeState.updateAiModel(previousModel)
            ManyueRuntimeState.updateMode(previousMode)
            isolatedDir.deleteRecursively()
        }
    }

    @Test
    fun `releasing cancelled claims completes worker cleanup without requeue`() {
        val previousMode = ManyueRuntimeState.modeInt
        val previousModel = ManyueRuntimeState.aiModel
        val isolatedDir = Files.createTempDirectory("manyue-cancelled-claim").toFile()
        val unrelatedInput = File(isolatedDir, "unrelated.bin").apply { writeBytes(byteArrayOf(9)) }
        val activeField = ManyueAiUpscaler.javaClass.getDeclaredField("activeRequest").apply { isAccessible = true }
        val queueField = ManyueAiUpscaler.javaClass.getDeclaredField("queue").apply { isAccessible = true }
        val previousActive = activeField.get(ManyueAiUpscaler)
        val releaseClaim = ManyueAiUpscaler.javaClass.getDeclaredMethod(
            "releaseClaim",
            ManyueAiUpscaler.Request::class.java,
            Boolean::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        val queue = queueField.get(ManyueAiUpscaler) as java.util.concurrent.BlockingQueue<*>
        val statesByToken = mutableMapOf<String, MutableList<Int>>()
        val tokens = mutableListOf<String>()

        try {
            ManyueRuntimeState.updateAiModel(ManyueAiModel.QUALITY_REAL_ESRGAN)
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
            listOf(false, true).forEachIndexed { index, requeue ->
                val input = File(isolatedDir, "claim-$index.bin").apply { writeBytes(byteArrayOf(index.toByte())) }
                val token = ManyueAiUpscaler.register(
                    mangaId = 1100L + index,
                    chapterId = 1200L + index,
                    pageIndex = index,
                    inputFile = input,
                    sourceWidth = 100,
                    sourceHeight = 200,
                    targetMode = ManyueEnhancementMode.AI_2X.value,
                    targetWidth = 200,
                    priority = 100,
                    expectedMode = ManyueEnhancementMode.AI_2X.value,
                    generation = ManyueRuntimeState.generation,
                    sourceFingerprint = "cancelled-claim-$index",
                    model = ManyueAiModel.QUALITY_REAL_ESRGAN,
                )
                tokens += token
                val request = makeRequest(token)
                val states = mutableListOf<Int>()
                statesByToken[token] = states
                val lease = ManyueEnhancementCache.CacheLease(null)
                request.cachedImageLease = lease
                request.processing = true
                activeField.set(ManyueAiUpscaler, request)
                ManyueAiUpscaler.addListener(token) { _, state, _ -> states += state }

                ManyueAiUpscaler.cancel(token)
                assertTrue(request.cancelled)
                assertFalse(request.completed)
                assertTrue("cooperative cancellation leaves file cleanup to the worker", input.exists())
                releaseClaim.invoke(ManyueAiUpscaler, request, requeue)

                assertFalse(request.processing)
                assertFalse(request.queued)
                assertFalse(queue.contains(request))
                assertTrue(request.completed)
                assertEquals(ManyueAiUpscaler.STATE_CANCELLED, request.completionState)
                assertTrue(request.terminal.isCompleted)
                assertEquals(ManyueAiUpscaler.STATE_CANCELLED, runBlocking { request.terminal.await() })
                assertNull(request.cachedImageLease)
                val closedField = lease.javaClass.getDeclaredField("closed").apply { isAccessible = true }
                assertTrue((closedField.get(lease) as java.util.concurrent.atomic.AtomicBoolean).get())
                assertFalse(input.exists())
                assertNull(activeField.get(ManyueAiUpscaler))
            }
            shadowOf(Looper.getMainLooper()).idle()

            statesByToken.values.forEach { assertEquals(listOf(ManyueAiUpscaler.STATE_CANCELLED), it) }
            assertTrue("cleanup must leave unrelated input untouched", unrelatedInput.exists())
        } finally {
            tokens.forEach {
                ManyueAiUpscaler.removeListener(it)
                ManyueAiUpscaler.cancel(it)
                removeRegisteredRequest(it)
            }
            activeField.set(ManyueAiUpscaler, previousActive)
            ManyueRuntimeState.updateAiModel(previousModel)
            ManyueRuntimeState.updateMode(previousMode)
            isolatedDir.deleteRecursively()
        }
    }

    private fun makeRequest(model: ManyueAiModel, inputFile: File = File("unused")) = ManyueAiUpscaler.Request(
        token = UUID.randomUUID().toString(),
        mangaId = 1,
        chapterId = 2,
        pageIndex = 3,
        inputFile = inputFile,
        sourceWidth = 100,
        sourceHeight = 200,
        targetMode = ManyueEnhancementMode.AI_2X.value,
        targetWidth = 200,
        generation = ManyueRuntimeState.generation,
        model = model,
    )

    @Suppress("UNCHECKED_CAST")
    private fun makeRequest(token: String): ManyueAiUpscaler.Request {
        val field = ManyueAiUpscaler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
        return (field.get(ManyueAiUpscaler) as ConcurrentHashMap<String, ManyueAiUpscaler.Request>).getValue(token)
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeRegisteredRequest(token: String) {
        val field = ManyueAiUpscaler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
        (field.get(ManyueAiUpscaler) as ConcurrentHashMap<String, ManyueAiUpscaler.Request>).remove(token)
    }
}
