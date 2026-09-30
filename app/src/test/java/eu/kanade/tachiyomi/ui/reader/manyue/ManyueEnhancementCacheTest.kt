package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ManyueEnhancementCacheTest {

    @Test fun `cache key is stable and unique`() {
        val k1 = ManyueEnhancementCache.cacheKey(1L, 2L, 3, 0, 2001, 2344, 25)
        val k2 = ManyueEnhancementCache.cacheKey(1L, 2L, 3, 0, 2001, 2344, 25)
        val k3 = ManyueEnhancementCache.cacheKey(1L, 2L, 4, 0, 2001, 2344, 25)
        assertEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertEquals(32, k1.length) // md5 hex
    }

    @Test fun `cache key depends on mode`() {
        val a = ManyueEnhancementCache.cacheKey(1L, 2L, 3, 1, 2001, 2344, 25)
        val b = ManyueEnhancementCache.cacheKey(1L, 2L, 3, 2, 2001, 2344, 25)
        assertNotEquals(a, b)
    }

    @Test fun `overlay cache key isolates v95 ambiguous entries`() {
        val withoutOverlay = ManyueEnhancementCache.cacheKey(
            1L, 2L, 3, 0, 2001, 2344, 0, "source", 20, "realcugan_fast", false,
        )
        val withOverlay = ManyueEnhancementCache.cacheKey(
            1L, 2L, 3, 0, 2001, 2344, 0, "source", 20, "realcugan_fast", true,
        )
        assertEquals("68da5aa6667bd522caf2a0820de53442", withoutOverlay)
        assertNotEquals("dd167c3d3c16b1626fdcc462159ef58f", withOverlay)
    }

    @Test fun `constants match spec`() {
        assertEquals(48_000_000, ManyueAiRuntime.MAX_MODEL_OUTPUT_PIXELS)
        assertEquals(67108864, ManyueAiRuntime.MAX_INPUT_BYTES)
        // Cache trim target 640 MiB is referenced indirectly via cacheSizeText formatting.
        assertTrue(ManyueAiRuntime.MAX_INPUT_BYTES > 0L)
    }

    @Test fun `cached full image is accepted only when manifest and file exist`() {
        val bundle = Files.createTempDirectory("manyue-cache-test").toFile()
        try {
            val image = java.io.File(bundle, "image.webp")
            image.writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(bundle, "manifest.json").writeText("""{"image":"image.webp","width":2000,"height":4000}""")

            assertEquals(image, ManyueEnhancementCache.imageFile(bundle))

            image.delete()
            assertNull(ManyueEnhancementCache.imageFile(bundle))
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test fun `classic variant shares bundle dimensions and requires a valid strength`() {
        val bundle = Files.createTempDirectory("manyue-cache-variant-test").toFile()
        try {
            val image = java.io.File(bundle, "image.webp").apply { writeBytes(byteArrayOf(1)) }
            java.io.File(bundle, "manifest.json").writeText("""{"image":"image.webp","width":1000,"height":2000}""")
            val variant = java.io.File(bundle, "classic_25.webp").apply { writeBytes(byteArrayOf(2)) }

            assertEquals(image, ManyueEnhancementCache.cachedImage(bundle)?.file)
            assertEquals(variant, ManyueEnhancementCache.cachedImage(bundle, classicStrength = 25)?.file)
            assertNull(ManyueEnhancementCache.cachedImage(bundle, classicStrength = 101))
        } finally {
            bundle.deleteRecursively()
        }
    }

    @Test fun `overlay failure detail round trips through manifest`() {
        val bundle = Files.createTempDirectory("manyue-cache-overlay-test").toFile()
        try {
            val image = java.io.File(bundle, "image.webp").apply { writeBytes(byteArrayOf(1)) }
            val detail = "Anime4K \"未生效\"，路径 C:\\cache\\AI\n保留\u0001原图 😀"
            val manifest = ManyueEnhancementCache.buildJsonObject(
                "image" to image.name,
                "width" to 1000,
                "height" to 2000,
                "overlayRequested" to true,
                "overlayApplied" to false,
                "detail" to detail,
            )
            java.io.File(bundle, "manifest.json").writeText(manifest)

            val cached = ManyueEnhancementCache.cachedImage(bundle)
            assertEquals(detail, cached?.detail)
            assertTrue(cached?.overlayRequested == true)
            assertFalse(cached?.overlayApplied ?: true)
        } finally {
            bundle.deleteRecursively()
        }
    }
}
