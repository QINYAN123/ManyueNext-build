package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Creates validated native requests for visible holders and the bounded chapter prefetch cursor. */
object ManyueAiRequestFactory {

    fun enqueue(
        context: Context,
        identity: ManyuePageBridge.Identity,
        originalBytes: ByteArray,
        priority: Int,
        expectedMode: Int = ManyueRuntimeState.modeInt,
        generation: Long = ManyueRuntimeState.generation,
    ): String? {
        if (expectedMode != ManyueEnhancementMode.AI_2X.value &&
            expectedMode != ManyueEnhancementMode.AI_2X_CLASSIC.value
        ) return null
        if (ManyueRuntimeState.modeInt != expectedMode || ManyueRuntimeState.generation != generation) return null
        if (ManyueAiRuntime.probe(context) != ManyueAiRuntime.Capability.READY || originalBytes.isEmpty() ||
            originalBytes.size > ManyueAiRuntime.MAX_INPUT_BYTES || ManyueImagePipeline.isAnimated(originalBytes)
        ) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (!ManyueAiSafetyPolicy.isPredictedNativeOutputSafe(
                width,
                height,
                minOf(ManyueAiRuntime.MAX_MODEL_OUTPUT_PIXELS, ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS),
            )
        ) return null

        val model = ManyueRuntimeState.aiModel
        val targetMode = model.scale
        val targetWidth = ManyueAiUpscaler.customTargetWidth(width, ManyueRuntimeState.aiScalePercent)
        if (targetWidth <= width) return null
        // The resolved width is in both cache and request identity; legacy tenths stays native x2.
        val targetScaleTenths = 20
        val resolved = targetWidth
        val resolvedHeight = Math.round(height * (resolved.toDouble() / width)).toInt()
        if (!ManyueAiSafetyPolicy.isPixelBudgetSafe(
                resolved,
                resolvedHeight,
                ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS,
            )
        ) return null

        val sourceFingerprint = ManyueAiUpscaler.fingerprint(originalBytes)
        val reusable = ManyueAiUpscaler.findReusableToken(
            context = context,
            mangaId = identity.mangaId,
            chapterId = identity.chapterId,
            pageIndex = identity.pageIndex,
            targetMode = targetMode,
            targetWidth = targetWidth,
            expectedMode = expectedMode,
            generation = generation,
            sourceFingerprint = sourceFingerprint,
            targetScaleTenths = targetScaleTenths,
            model = model,
            anime4kOverlay = ManyueRuntimeState.anime4kOverlay,
        )
        if (reusable != null) {
            ManyueAiUpscaler.promotePriority(reusable, priority)
            ManyuePrefetchManager.register(identity.chapterId, identity.pageIndex, reusable)
            ManyueAiUpscaler.queue(reusable)
            return reusable
        }

        var input: File? = null
        return try {
            input = File(context.cacheDir, "manyue_in_${UUID.randomUUID()}.bin")
            input.writeBytes(originalBytes)
            val token = ManyueAiUpscaler.register(
                mangaId = identity.mangaId,
                chapterId = identity.chapterId,
                pageIndex = identity.pageIndex,
                inputFile = input,
                sourceWidth = width,
                sourceHeight = height,
                targetMode = targetMode,
                targetWidth = targetWidth,
                targetScaleTenths = targetScaleTenths,
                priority = priority,
                expectedMode = expectedMode,
                generation = generation,
                sourceFingerprint = sourceFingerprint,
                model = model,
                anime4kOverlay = ManyueRuntimeState.anime4kOverlay,
            )
            ManyuePrefetchManager.register(identity.chapterId, identity.pageIndex, token)
            ManyueAiUpscaler.queue(token)
            logcat {
                "Manyue AI queued chapter=${identity.chapterId} page=${identity.pageIndex} priority=$priority token=$token"
            }
            ManyueDiagnostics.record("第 ${identity.pageIndex + 1} 页已排队，优先级 $priority")
            token
        } catch (t: Throwable) {
            input?.delete()
            logcat(LogPriority.WARN, t) { "Manyue AI request creation failed; original retained" }
            ManyueDiagnostics.record("第 ${identity.pageIndex + 1} 页创建任务失败")
            null
        }
    }
}
