package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Central image enhancement pipeline.
 *
 * OFF              -> return null (use original, fast path)
 * animated (GIF/WebP VP8X) -> return null
 * CLASSIC          -> decode -> classic enhance -> WebP -> BufferedSource
 * AI_2X            -> async: kick off AI queue, return null (show original first)
 * AI_2X_CLASSIC    -> AI result then classic
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
     * Classic-only path: synchronous. Returns WebP bytes or null on failure/off/animated.
     */
    suspend fun classicEnhance(
        bytes: ByteArray,
        strength: Int,
        isAiCombined: Boolean,
    ): ByteArray? = withContext(Dispatchers.Default) {
        // A zero-strength pass must not decode and lossily re-encode an unchanged image.
        if (strength <= 0 || isAnimated(bytes)) return@withContext null
        var bmp: Bitmap? = null
        var enhanced: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (!ManyueAiSafetyPolicy.isPixelBudgetSafe(
                    bounds.outWidth,
                    bounds.outHeight,
                    ManyueAiSafetyPolicy.MAX_CLASSIC_PIXELS,
                )
            ) {
                return@withContext null
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext null
            bmp = decoded
            val outputBitmap = ManyueClassicEnhancer.enhance(decoded, strength, isAiCombined)
            enhanced = outputBitmap
            val out = ByteArrayOutputStream()
            check(outputBitmap.compress(ManyueBitmapEncoding.lossyWebpFormat(), 92, out))
            out.toByteArray()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            null
        } finally {
            enhanced?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
            bmp?.takeIf { !it.isRecycled }?.recycle()
        }
    }

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
                anime4kOverlay = ManyueRuntimeState.anime4kOverlay,
            )
            ManyueAiUpscaler.queue(token)
            return token
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * Convenience entry used by readers. Returns non-null bytes only when a
     * synchronous classic enhancement was applied; AI is always async.
     */
    suspend fun process(
        bytes: ByteArray,
        pageInfo: PageInfo,
        mode: ManyueEnhancementMode,
        preferences: ManyueEnhancementPreferences,
        context: Context,
    ): ByteArray? {
        if (mode == ManyueEnhancementMode.OFF) return null
        if (isAnimated(bytes)) return null
        return when (mode) {
            ManyueEnhancementMode.CLASSIC ->
                classicEnhance(bytes, preferences.classicStrength.get(), isAiCombined = false)
            ManyueEnhancementMode.AI_2X -> {
                // async: caller should poll state and swap image when ready.
                null
            }
            ManyueEnhancementMode.AI_2X_CLASSIC -> {
                // classic on top of whatever AI produced; synchronous classic pass on the original
                // is acceptable as a first frame. AI overlay happens via token polling.
                classicEnhance(bytes, preferences.classicStrength.get(), isAiCombined = true)
            }
            ManyueEnhancementMode.OFF -> null
            ManyueEnhancementMode.AUTO -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (ManyueAutoEnhancementPolicy.decide(
                        bounds.outWidth,
                        ManyueRuntimeState.displayWidthPx,
                        preferences.aiScalePercent.get(),
                        ManyueAiModel.fromId(preferences.aiModel.get()).continuousScale,
                    ).path == ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE
                ) {
                    classicEnhance(bytes, preferences.classicStrength.get(), isAiCombined = false)
                } else {
                    null
                }
            }
        }
    }
}
