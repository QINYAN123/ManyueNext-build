package eu.kanade.tachiyomi.ui.reader.manyue

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat

/**
 * Thin, defensive hook used by PagerPageHolder / WebtoonPageHolder.
 * OFF / animated / any exception -> returns the original source untouched.
 * Classic-only enhancement is applied synchronously; AI is async (caller polls).
 */
object ManyueReaderHook {
    // Several attached strips can enter the classic-only hook at once. Serialize its
    // non-suspending decode/enhance/encode work so full-size temporary bitmaps do not fan out.
    private val classicDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun applyClassic(
        source: BufferedSource,
        modeInt: Int,
        strength: Int,
        onState: (ManyueEnhancementState, String?) -> Unit = { _, _ -> },
    ): BufferedSource {
        // Original/AI first frames need no classic worker or dispatcher hop.
        if (modeInt != ManyueEnhancementMode.CLASSIC.value) return source
        return applyClassicOnWorker(source, strength, onState)
    }

    private suspend fun applyClassicOnWorker(
        source: BufferedSource,
        strength: Int,
        onState: (ManyueEnhancementState, String?) -> Unit,
    ): BufferedSource = withContext(classicDispatcher) {
        try {
            if (strength <= 0) {
                onState(ManyueEnhancementState.SKIPPED, "强度为 0")
                return@withContext source
            }
            val bytes = source.peek().readByteArray()
            if (bytes.isEmpty()) {
                onState(ManyueEnhancementState.FAILED, "图片为空")
                return@withContext source
            }
            if (ManyueImagePipeline.isAnimated(bytes)) {
                onState(ManyueEnhancementState.SKIPPED, "动态图")
                return@withContext source
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (!ManyueAiSafetyPolicy.isPixelBudgetSafe(
                    bounds.outWidth,
                    bounds.outHeight,
                    ManyueAiSafetyPolicy.MAX_CLASSIC_PIXELS,
                )
            ) {
                onState(ManyueEnhancementState.SKIPPED, "超过经典增强内存限制")
                return@withContext source
            }
            val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: run {
                    onState(ManyueEnhancementState.FAILED, "原图解码失败，已保留原图")
                    return@withContext source
                }
            var enhanced: Bitmap? = null
            try {
                enhanced = ManyueClassicEnhancer.enhance(bmp, strength, isAiCombined = false)
                val out = java.io.ByteArrayOutputStream()
                check(enhanced.compress(ManyueBitmapEncoding.lossyWebpFormat(), 92, out))
                Buffer().write(out.toByteArray()).also {
                    onState(ManyueEnhancementState.CLASSIC_READY, null)
                }
            } finally {
                enhanced?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "Manyue classic enhancement failed; original retained" }
            onState(ManyueEnhancementState.FAILED, "经典增强处理错误")
            source
        }
    }
}
