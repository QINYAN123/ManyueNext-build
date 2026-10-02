package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.metadata.ReaderMetadataLabels
import eu.kanade.tachiyomi.ui.reader.metadata.ReaderMetadataPresentation
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageInfo
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueLiteReaderIntegrationTest {

    @Test
    fun `lite auto keeps original first frame and sends an already fitting image to ai`() {
        val state = RuntimeSnapshot().apply { installLite(displayWidth = 6) }
        try {
            assertFalse(ManyueEnhancementMode.AUTO.usesClassic(ManyueAiModel.MOBILE_LITE))
            assertTrue(ManyueEnhancementMode.AUTO.usesClassic(ManyueAiModel.FAST_REAL_CUGAN))
            assertTrue(ManyueEnhancementMode.AI_2X_CLASSIC.usesClassic(ManyueAiModel.MOBILE_LITE))

            val original = png(width = 8, height = 17, color = Color.RED)
            assertTrue(ManyueReaderHook.needsAi(Buffer().write(original.clone()), ManyueEnhancementMode.AUTO.value))

            val source = Buffer().write(original.clone())
            val states = mutableListOf<ManyueEnhancementState>()
            val result = runBlocking {
                ManyueReaderHook.applyClassic(
                    source = source,
                    modeInt = ManyueEnhancementMode.AUTO.value,
                    strength = 75,
                    onState = { enhancementState, _ -> states += enhancementState },
                    displayWidthPx = 6,
                )
            }

            assertSame(source, result)
            assertArrayEquals(original, result.peek().readByteArray())
            assertTrue("Lite AUTO should not apply a classic first-frame pass", states.isEmpty())
        } finally {
            state.restore()
        }
    }

    @Test
    fun `lite auto queues one times requests and keys reuse by source generation and strength`() {
        val context: Application = RuntimeEnvironment.getApplication()
        withHarness(context, displayWidth = 6) { harness ->
            val identity = ManyuePageBridge.Identity(mangaId = 8101, chapterId = 8102, pageIndex = 3)
            val original = png(width = 8, height = 17, color = Color.RED)
            val originalCopy = original.clone()
            val inputsBefore = inputFiles(context.cacheDir)

            val firstToken = requireNotNull(
                harness.enqueue(identity, original),
            ) { "Lite AUTO should queue AI at the source's one-times width" }
            val first = harness.request(firstToken)
            assertEquals(8, first.sourceWidth)
            assertEquals(17, first.sourceHeight)
            assertEquals(8, first.targetWidth)
            assertEquals(ManyueAiModel.MOBILE_LITE, first.model)
            assertEquals(60, first.aiDetailStrength)
            assertArrayEquals(originalCopy, original)
            assertArrayEquals(originalCopy, first.inputFile.readBytes())

            val inputsAfterFirst = inputFiles(context.cacheDir)
            val reusedToken = requireNotNull(harness.enqueue(identity, original.clone()))
            assertEquals(firstToken, reusedToken)
            assertEquals(inputsAfterFirst, inputFiles(context.cacheDir))

            val differentSource = png(width = 8, height = 17, color = Color.BLUE)
            val differentSourceToken = requireNotNull(harness.enqueue(identity, differentSource))
            assertNotEquals(firstToken, differentSourceToken)
            val differentSourceRequest = harness.request(differentSourceToken)
            assertEquals(first.targetWidth, differentSourceRequest.targetWidth)
            assertEquals(first.sourceWidth, differentSourceRequest.sourceWidth)
            assertEquals(first.sourceHeight, differentSourceRequest.sourceHeight)
            assertNotEquals(first.sourceFingerprint, differentSourceRequest.sourceFingerprint)

            val generationBeforeStrengthChange = ManyueRuntimeState.generation
            ManyueRuntimeState.updateAiDetailStrength(61)
            assertEquals(generationBeforeStrengthChange + 1, ManyueRuntimeState.generation)

            val changedStrengthToken = requireNotNull(harness.enqueue(identity, original.clone()))
            val changedStrengthRequest = harness.request(changedStrengthToken)
            assertNotEquals(firstToken, changedStrengthToken)
            assertEquals(61, changedStrengthRequest.aiDetailStrength)
            assertEquals(first.targetWidth, changedStrengthRequest.targetWidth)
            assertNotEquals(first.requestKey, changedStrengthRequest.requestKey)
        }
    }

    @Test
    fun `zero lite repair strength is presented as interpolation with actual dimensions`() {
        val model = ManyueAiModel.MOBILE_LITE
        val page = ReaderPage(index = 0)
        page.updateSourceImageInfo(SourceImageInfo.Available(width = 8, height = 17))
        page.updateEnhancedImageInfo(width = 8, height = 17)

        val state = model.displayedState(detailStrength = 0)
        assertEquals(ManyueEnhancementState.INTERPOLATED_READY, state)
        page.updateEnhancementState(state)
        val info = page.sourceImageInfo.value as SourceImageInfo.Available
        assertEquals(8, info.enhancedWidth)
        assertEquals(17, info.enhancedHeight)

        val labels = ReaderMetadataLabels(
            availablePrefix = "Source",
            loading = "Loading",
            unavailable = "Unavailable",
            enhancedPrefix = "Output",
            aiReady = "AI ready",
            interpolatedReady = "Interpolation",
        )
        val text = ReaderMetadataPresentation.text(1, 1, info, labels)
        assertEquals("1 / 1 · Source 8 × 17 · Output 8 × 17 · Interpolation", text)
        assertFalse(text.contains(labels.aiReady))

        val classicState = model.displayedState(detailStrength = 0, classicApplied = true)
        assertEquals(ManyueEnhancementState.CLASSIC_READY, classicState)
        page.updateEnhancementState(classicState)
        val classicInfo = page.sourceImageInfo.value as SourceImageInfo.Available
        assertEquals(8, classicInfo.enhancedWidth)
        assertEquals(17, classicInfo.enhancedHeight)
    }

    @Test
    fun `visible lite queue request cancels active prefetch through native callback`() {
        val context: Application = RuntimeEnvironment.getApplication()
        withHarness(context, displayWidth = 6) { harness ->
            val oldInput = harness.newInputFile()
            val activeToken = harness.register(
                input = oldInput,
                priority = 40,
                pageIndex = 4,
                sourceFingerprint = "active-lite-prefetch",
            )
            val active = harness.request(activeToken)
            val cancellationReceived = CountDownLatch(1)
            active.processing = true
            active.nativeCancellation = { cancellationReceived.countDown() }
            harness.setActiveRequest(active)

            val visibleInput = harness.newInputFile()
            val visibleToken = harness.register(
                input = visibleInput,
                priority = 120,
                pageIndex = 0,
                sourceFingerprint = "visible-lite-page",
            )
            ManyueAiUpscaler.queue(visibleToken)

            assertTrue("visible Lite work should cancel active prefetch", active.cancelled)
            assertTrue("native cancellation callback should run", cancellationReceived.await(2, TimeUnit.SECONDS))
            assertTrue(harness.request(visibleToken).queued)
        }
    }

    @Test
    fun `demoting old visible work releases it even when the new page was promoted first`() {
        val context: Application = RuntimeEnvironment.getApplication()
        withHarness(context, displayWidth = 6) { harness ->
            val activeToken = harness.register(harness.newInputFile(), 120, 4, "old-selected-page")
            val active = harness.request(activeToken)
            val cancellationReceived = CountDownLatch(1)
            active.processing = true
            active.nativeCancellation = { cancellationReceived.countDown() }
            harness.setActiveRequest(active)

            val nextToken = harness.register(harness.newInputFile(), 40, 0, "new-selected-page")
            ManyueAiUpscaler.queue(nextToken)
            ManyueAiUpscaler.updatePriority(nextToken, 120)
            assertFalse(active.cancelled)
            ManyueAiUpscaler.updatePriority(activeToken, 40)

            assertTrue(active.cancelled)
            assertTrue(cancellationReceived.await(2, TimeUnit.SECONDS))
            assertTrue(harness.request(nextToken).queued)
        }
    }

    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun inputFiles(cacheDir: File): Set<String> =
        cacheDir.listFiles()?.filter { it.name.startsWith("manyue_in_") }?.map { it.name }?.toSet().orEmpty()

    private inline fun withHarness(
        context: Application,
        displayWidth: Int,
        block: (LiteHarness) -> Unit,
    ) {
        val harness = LiteHarness(context, displayWidth)
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    private class RuntimeSnapshot {
        private val fields = StaticFields(
            ManyueRuntimeState::class.java,
            ManyueRuntimeState,
            listOf(
                "modeInt", "classicStrength", "aiModel", "aiScalePercent", "aiDetailStrength",
                "anime4kOverlay", "displayWidthPx", "foldableMode", "foldableTargetWidth", "generation",
            ),
        )

        fun installLite(displayWidth: Int) {
            fields.set("modeInt", ManyueEnhancementMode.AUTO.value)
            fields.set("aiModel", ManyueAiModel.MOBILE_LITE)
            fields.set("aiScalePercent", 200)
            fields.set("aiDetailStrength", 60)
            fields.set("anime4kOverlay", false)
            fields.set("displayWidthPx", displayWidth)
            fields.set("generation", (fields.value("generation") as Long) + 100L)
        }

        fun restore() = fields.restore()
    }

    private class LiteHarness(
        private val context: Application,
        displayWidth: Int,
    ) {
        private val runtime = RuntimeSnapshot().apply { installLite(displayWidth) }
        private val scheduler = SchedulerSnapshot()
        private val prefetch = PrefetchSnapshot()
        private val cachedLiteProbeField = field(ManyueAiRuntime::class.java, "cachedLiteProbe")
        private val oldCachedLiteProbe = cachedLiteProbeField.get(ManyueAiRuntime)
        private val oldAbis = Build.SUPPORTED_64_BIT_ABIS
        private val oldNativeLibraryDir = context.applicationInfo.nativeLibraryDir
        private val diagnostics = field(ManyueDiagnostics::class.java, "mutableLatest")
            .get(ManyueDiagnostics) as MutableStateFlow<String>
        private val oldDiagnostic = diagnostics.value
        private val tokens = linkedSetOf<String>()
        private val inputFiles = linkedSetOf<File>()
        private var closed = false

        init {
            scheduler.isolate()
            prefetch.isolate()
            val fakeNativeDir = File(context.cacheDir, "lite-integration-native").absolutePath
            context.applicationInfo.nativeLibraryDir = fakeNativeDir
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
            cachedLiteProbeField.set(ManyueAiRuntime, fakeNativeDir to ManyueAiRuntime.Capability.READY)
        }

        fun enqueue(identity: ManyuePageBridge.Identity, bytes: ByteArray): String? {
            val token = ManyueAiRequestFactory.enqueue(
                context = context,
                identity = identity,
                originalBytes = bytes,
                priority = 100,
                displayWidthPx = 6,
            )
            token?.let(tokens::add)
            return token
        }

        fun register(
            input: File,
            priority: Int,
            pageIndex: Int,
            sourceFingerprint: String,
        ): String {
            inputFiles += input
            val token = ManyueAiUpscaler.register(
                mangaId = 8101,
                chapterId = 8102,
                pageIndex = pageIndex,
                inputFile = input,
                sourceWidth = 8,
                sourceHeight = 17,
                targetMode = ManyueAiModel.MOBILE_LITE.scale,
                targetWidth = 8,
                priority = priority,
                expectedMode = ManyueEnhancementMode.AUTO.value,
                generation = ManyueRuntimeState.generation,
                sourceFingerprint = sourceFingerprint,
                model = ManyueAiModel.MOBILE_LITE,
                aiDetailStrength = ManyueRuntimeState.aiDetailStrength,
            )
            tokens += token
            return token
        }

        fun request(token: String): ManyueAiUpscaler.Request = scheduler.request(token)

        fun newInputFile(): File = File(context.cacheDir, "manyue_lite_${UUID.randomUUID()}.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            inputFiles += this
        }

        fun setActiveRequest(request: ManyueAiUpscaler.Request) = scheduler.setActiveRequest(request)

        fun close() {
            if (closed) return
            closed = true
            scheduler.cancelAndRemove(tokens)
            inputFiles.forEach { it.delete() }
            prefetch.restore()
            runtime.restore()
            cachedLiteProbeField.set(ManyueAiRuntime, oldCachedLiteProbe)
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", oldAbis)
            context.applicationInfo.nativeLibraryDir = oldNativeLibraryDir
            diagnostics.value = oldDiagnostic
        }
    }

    private class StaticFields(owner: Class<*>, private val receiver: Any, names: List<String>) {
        private val fields = names.associateWith { field(owner, it) }
        private val values = fields.mapValues { (_, field) -> field.get(receiver) }

        fun value(name: String): Any? = values[name]

        fun set(name: String, value: Any?) {
            fields.getValue(name).set(receiver, value)
        }

        fun restore() {
            values.forEach { (name, value) -> fields.getValue(name).set(receiver, value) }
        }
    }

    private class SchedulerSnapshot {
        @Suppress("UNCHECKED_CAST")
        private val requests = field(
            ManyueAiUpscaler::class.java,
            "requests",
        ).get(ManyueAiUpscaler) as MutableMap<String, ManyueAiUpscaler.Request>
        private val savedRequests = requests.toMap()

        @Suppress("UNCHECKED_CAST")
        private val tokensByRequestKey = field(
            ManyueAiUpscaler::class.java,
            "tokensByRequestKey",
        ).get(ManyueAiUpscaler) as MutableMap<String, String>
        private val savedTokensByRequestKey = tokensByRequestKey.toMap()

        @Suppress("UNCHECKED_CAST")
        private val queue = field(
            ManyueAiUpscaler::class.java,
            "queue",
        ).get(ManyueAiUpscaler) as MutableCollection<ManyueAiUpscaler.Request>
        private val savedQueue = queue.toList()

        private val startedField = field(ManyueAiUpscaler::class.java, "started")
        private val wasStarted = startedField.getBoolean(ManyueAiUpscaler)
        private val activeRequestField = field(ManyueAiUpscaler::class.java, "activeRequest")
        private val savedActiveRequest = activeRequestField.get(ManyueAiUpscaler)
        private val sequence = field(ManyueAiUpscaler::class.java, "sequence").get(ManyueAiUpscaler) as AtomicLong
        private val savedSequence = sequence.get()
        private var isolated = false

        fun isolate() {
            requests.clear()
            tokensByRequestKey.clear()
            queue.clear()
            startedField.setBoolean(ManyueAiUpscaler, true)
            activeRequestField.set(ManyueAiUpscaler, null)
            isolated = true
        }

        fun request(token: String): ManyueAiUpscaler.Request = requireNotNull(requests[token])

        fun setActiveRequest(request: ManyueAiUpscaler.Request) {
            activeRequestField.set(ManyueAiUpscaler, request)
        }

        fun cancelAndRemove(tokens: Set<String>) {
            tokens.forEach { token ->
                val request = requests[token] ?: return@forEach
                if (!request.cancelled && !request.completed) ManyueAiUpscaler.cancel(token)
                queue.remove(request)
                request.requestKey.takeIf(String::isNotEmpty)?.let { tokensByRequestKey.remove(it, token) }
                requests.remove(token)
            }
            if (isolated) {
                requests.clear()
                requests.putAll(savedRequests)
                tokensByRequestKey.clear()
                tokensByRequestKey.putAll(savedTokensByRequestKey)
                queue.clear()
                queue.addAll(savedQueue)
                sequence.set(savedSequence)
                activeRequestField.set(ManyueAiUpscaler, savedActiveRequest)
                startedField.setBoolean(ManyueAiUpscaler, wasStarted)
            }
        }
    }

    private class PrefetchSnapshot {
        @Suppress("UNCHECKED_CAST")
        private val tokens = field(
            ManyuePrefetchManager::class.java,
            "tokens",
        ).get(ManyuePrefetchManager) as MutableMap<Any, MutableSet<String>>
        private val savedTokens = tokens.mapValues { (_, value) -> value.toSet() }

        @Suppress("UNCHECKED_CAST")
        private val currentDecisions = field(
            ManyuePrefetchManager::class.java,
            "currentDecisions",
        ).get(ManyuePrefetchManager) as MutableMap<Any, Any>
        private val savedCurrentDecisions = currentDecisions.toMap()

        @Suppress("UNCHECKED_CAST")
        private val remembered = field(
            ManyuePrefetchManager::class.java,
            "rememberedCurrentDecisions",
        ).get(ManyuePrefetchManager) as MutableCollection<Any>
        private val savedRemembered = remembered.toList()

        private val visiblePagesField = field(ManyuePrefetchManager::class.java, "visiblePages")
        private val savedVisiblePages = (visiblePagesField.get(ManyuePrefetchManager) as Set<*>).toSet()
        private val currentChapterField = field(ManyuePrefetchManager::class.java, "currentChapterId")
        private val savedCurrentChapter = currentChapterField.getLong(ManyuePrefetchManager)
        private val currentPageField = field(ManyuePrefetchManager::class.java, "currentPage")
        private val savedCurrentPage = currentPageField.getInt(ManyuePrefetchManager)
        private var isolated = false

        fun isolate() {
            tokens.clear()
            currentDecisions.clear()
            remembered.clear()
            visiblePagesField.set(ManyuePrefetchManager, emptySet<Any>())
            currentChapterField.setLong(ManyuePrefetchManager, Long.MIN_VALUE)
            currentPageField.setInt(ManyuePrefetchManager, -1)
            isolated = true
        }

        fun restore() {
            if (!isolated) return
            tokens.clear()
            savedTokens.forEach { (key, value) -> tokens[key] = value.toMutableSet() }
            currentDecisions.clear()
            currentDecisions.putAll(savedCurrentDecisions)
            remembered.clear()
            remembered.addAll(savedRemembered)
            visiblePagesField.set(ManyuePrefetchManager, savedVisiblePages)
            currentChapterField.setLong(ManyuePrefetchManager, savedCurrentChapter)
            currentPageField.setInt(ManyuePrefetchManager, savedCurrentPage)
        }
    }

    companion object {
        private fun field(owner: Class<*>, name: String): Field = owner.getDeclaredField(name).apply {
            isAccessible =
                true
        }
    }
}
