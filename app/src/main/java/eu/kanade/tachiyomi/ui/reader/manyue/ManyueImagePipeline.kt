package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File

/**
 * Central image enhancement pipeline.
 *
 * OFF              -> return null (use original, fast path)
 * animated (GIF/WebP VP8X) -> return null
 * CLASSIC          -> retired; return null and keep the original
 * AI_2X            -> async: kick off AI queue, return null (show original first)
 * AI_2X_CLASSIC    -> legacy mode canonicalized to AI_2X
 *
 * Any exception => null (caller falls back to original).
 */
object ManyueImagePipeline {

    data class PageInfo(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
    )

    /**
     * Returns true if the bytes look like an animated image (bypass enhancement).
     */
    fun isAnimated(source: ByteArray): Boolean {
        if (source.size >= 6) {
            val hdr = String(source, 0, 6)
            if (hdr == "GIF87a" || hdr == "GIF89a") return true
        }
        // RIFF....WEBP + VP8X animation flag at byte 20
        if (source.size >= 25) {
            val riff = String(source, 0, 4)
            val webp = String(source, 8, 4)
            if (riff == "RIFF" && webp == "WEBP") {
                val chunk = String(source, 12, 4)
                if (chunk == "VP8X") {
                    val flags = source[20].toInt() and 0xff
                    if ((flags and 0x02) != 0) return true // animation flag
                }
            }
        }
        return false
    }

    /**
     * Legacy source-compatible entry point. Classic processing is retired.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun classicEnhance(
        bytes: ByteArray,
        strength: Int,
        isAiCombined: Boolean,
    ): ByteArray? = null

    /**
     * Kick off an AI upscale request. Returns a token the caller can poll via state().
     * Returns null if AI should not run (OFF / unsupported / animated / too large).
     */
    fun startAi(
        context: Context,
        pageInfo: PageInfo,
        inputFile: File,
        targetMode: Int,
        targetWidth: Int,
        priority: Int,
        targetScaleTenths: Int = 20,
    ): String? {
        try {
            if (ManyueAiRuntime.probe(context) != ManyueAiRuntime.Capability.READY) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            val token = ManyueAiUpscaler.register(
                pageInfo.mangaId, pageInfo.chapterId, pageInfo.pageIndex,
                inputFile, w, h, targetMode, targetWidth, priority,
                targetScaleTenths = targetScaleTenths,
                model = ManyueRuntimeState.aiModel,
                anime4kOverlay = false,
            )
            ManyueAiUpscaler.queue(token)
            return token
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * Legacy convenience entry. Async AI is scheduled by the reader bridge; this path preserves
     * the original bytes and never applies a retired classic filter.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun process(
        bytes: ByteArray,
        pageInfo: PageInfo,
        mode: ManyueEnhancementMode,
        preferences: ManyueEnhancementPreferences,
        context: Context,
    ): ByteArray? = null
}
