package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueAiRequestReuseTest {

    private lateinit var isolatedCache: File
    private lateinit var context: Context
    private val registeredTokens = mutableListOf<String>()

    @Before
    fun setUp() {
        ManyueReaderForegroundGate.resetForTest()
        isolatedCache = File(RuntimeEnvironment.getApplication().cacheDir, "request-reuse-${UUID.randomUUID()}")
            .apply { mkdirs() }
        context = IsolatedCacheContext(RuntimeEnvironment.getApplication(), isolatedCache)
        registeredTokens.clear()
    }

    @After
    fun tearDown() {
        val requests = requestMap()
        val requestKeys = requestKeyMap()
        registeredTokens.forEach { token ->
            requests.remove(token)?.let { request ->
                request.requestKey.takeIf(String::isNotEmpty)?.let { requestKeys.remove(it, token) }
                request.inputFile.delete()
            }
        }
        isolatedCache.deleteRecursively()
        ManyueReaderForegroundGate.resetForTest()
    }

    @Test
    fun `reuse matches the current reader request and rejects stale or different identities`() {
        val identity = RequestIdentity()
        ManyueReaderForegroundGate.onResume(Any())

        val firstOwnerToken = register(identity)
        assertEquals(firstOwnerToken, find(identity))

        ManyueReaderForegroundGate.onResume(Any())
        assertNull("a replacement reader cannot reuse a live request from its prior epoch", find(identity))

        val currentOwnerToken = register(identity)
        assertNotEquals(firstOwnerToken, currentOwnerToken)
        assertEquals(currentOwnerToken, find(identity))

        assertNull(find(identity.copy(expectedMode = identity.expectedMode + 1)))
        assertNull(find(identity.copy(generation = identity.generation + 1)))
        assertNull(find(identity.copy(targetMode = identity.targetMode + 1)))
        assertNull(find(identity.copy(targetWidth = identity.targetWidth + 1)))
        assertNull(find(identity.copy(targetScaleTenths = identity.targetScaleTenths - 1)))
        assertNull(find(identity.copy(model = ManyueAiModel.QUALITY_REAL_ESRGAN)))
        assertNull(find(identity.copy(sourceFingerprint = "different-source")))
        assertNull(find(identity.copy(aiDetailStrength = identity.aiDetailStrength + 1)))

        val currentRequest = request(currentOwnerToken)
        val completedImage = File(isolatedCache, "completed-${UUID.randomUUID()}.png")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(
            ManyueEnhancementCache.putImage(
                context,
                requireNotNull(ManyueAiUpscaler.cacheKeyForToken(currentOwnerToken)),
                completedImage,
                200,
                320,
            ),
        )
        currentRequest.completed = true
        currentRequest.completionState = ManyueAiUpscaler.STATE_READY
        assertEquals("an exact recent completed result is reused when its cache bundle is valid", currentOwnerToken, find(identity))

        // The old Anime overlay flag is intentionally retired and normalized to the plain-AI key.
        assertEquals(currentOwnerToken, find(identity, anime4kOverlay = true))
        assertFalse(currentRequest.anime4kOverlay)

        val cancelledIdentity = identity.copy(pageIndex = identity.pageIndex + 1)
        val cancelledToken = register(cancelledIdentity)
        ManyueAiUpscaler.cancel(cancelledToken)
        val cancelledRequest = request(cancelledToken)
        requestKeyMap()[cancelledRequest.requestKey] = cancelledToken
        assertNull("a stale key mapping cannot revive a cancelled request", find(cancelledIdentity))
        assertFalse(requestKeyMap().containsKey(cancelledRequest.requestKey))
    }

    private fun register(identity: RequestIdentity): String {
        val input = File(isolatedCache, "input-${UUID.randomUUID()}.bin")
            .apply { writeBytes(byteArrayOf(7, 8, 9)) }
        return ManyueAiUpscaler.register(
            mangaId = identity.mangaId,
            chapterId = identity.chapterId,
            pageIndex = identity.pageIndex,
            inputFile = input,
            sourceWidth = 100,
            sourceHeight = 160,
            targetMode = identity.targetMode,
            targetWidth = identity.targetWidth,
            targetScaleTenths = identity.targetScaleTenths,
            priority = 100,
            expectedMode = identity.expectedMode,
            generation = identity.generation,
            sourceFingerprint = identity.sourceFingerprint,
            model = identity.model,
            anime4kOverlay = false,
            aiDetailStrength = identity.aiDetailStrength,
        ).also(registeredTokens::add)
    }

    private fun find(identity: RequestIdentity, anime4kOverlay: Boolean = false): String? =
        ManyueAiUpscaler.findReusableToken(
            context = context,
            mangaId = identity.mangaId,
            chapterId = identity.chapterId,
            pageIndex = identity.pageIndex,
            targetMode = identity.targetMode,
            targetWidth = identity.targetWidth,
            expectedMode = identity.expectedMode,
            generation = identity.generation,
            sourceFingerprint = identity.sourceFingerprint,
            targetScaleTenths = identity.targetScaleTenths,
            model = identity.model,
            anime4kOverlay = anime4kOverlay,
            aiDetailStrength = identity.aiDetailStrength,
        )

    private fun request(token: String): ManyueAiUpscaler.Request = requireNotNull(requestMap()[token])

    private fun requestMap(): MutableMap<String, ManyueAiUpscaler.Request> {
        val field = ManyueAiUpscaler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(ManyueAiUpscaler) as MutableMap<String, ManyueAiUpscaler.Request>
    }

    private fun requestKeyMap(): MutableMap<String, String> {
        val field = ManyueAiUpscaler.javaClass.getDeclaredField("tokensByRequestKey").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(ManyueAiUpscaler) as MutableMap<String, String>
    }

    private data class RequestIdentity(
        val mangaId: Long = 95_101L,
        val chapterId: Long = 95_102L,
        val pageIndex: Int = 7,
        val targetMode: Int = ManyueEnhancementMode.AI_2X.value,
        val targetWidth: Int = 200,
        val targetScaleTenths: Int = 20,
        val expectedMode: Int = ManyueEnhancementMode.AI_2X.value,
        val generation: Long = 95_103L,
        val sourceFingerprint: String = "same-source",
        val model: ManyueAiModel = ManyueAiModel.QUALITY_NATIVE2,
        val aiDetailStrength: Int = 0,
    )

    private class IsolatedCacheContext(base: Context, private val isolatedCache: File) : ContextWrapper(base) {
        override fun getCacheDir(): File = isolatedCache
    }
}
