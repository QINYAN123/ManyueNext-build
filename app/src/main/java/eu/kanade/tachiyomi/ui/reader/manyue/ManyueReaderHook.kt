package eu.kanade.tachiyomi.ui.reader.manyue

import android.graphics.BitmapFactory
import okio.BufferedSource

/** Reader hook for source-page admission. Synchronous classic processing has been retired. */
object ManyueReaderHook {
    /** Called on the holder's IO thread; AUTO can keep a fitting source out of the AI queue. */
    fun needsAi(source: BufferedSource, modeInt: Int): Boolean {
        if (!ManyueEnhancementMode.fromInt(modeInt).usesAi()) return false
        if (ManyueEnhancementMode.fromInt(modeInt) != ManyueEnhancementMode.AUTO) return true
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        return ManyueAutoEnhancementPolicy.decide(
            bounds.outWidth,
            ManyueRuntimeState.displayWidthPx,
            ManyueRuntimeState.aiScalePercent,
            ManyueRuntimeState.aiModel.continuousScale,
        ).path != ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE
    }

    /** Compatibility entry point for page holders; always keeps the exact original source. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun applyClassic(
        source: BufferedSource,
        modeInt: Int,
        strength: Int,
        onState: (ManyueEnhancementState, String?) -> Unit = { _, _ -> },
        displayWidthPx: Int = ManyueRuntimeState.displayWidthPx,
    ): BufferedSource = source
}
