package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.BitmapFactory
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.UUID

/** Creates validated native requests for visible holders and the bounded chapter prefetch cursor. */
object ManyueAiRequestFactory {

    enum class RejectionReason {
        MODE_NOT_AI,
        SESSION_EXPIRED,
        MODEL_UNAVAILABLE,
        EMPTY_INPUT,
        INPUT_BYTES_LIMIT,
        ANIMATED_IMAGE,
        INVALID_DECODE_BOUNDS,
        AUTO_ORIGINAL_SIZE,
        AUTO_WAIT_FOR_WIDTH,
        INVALID_TARGET_BOUNDS,
        SOURCE_PIXEL_BUDGET,
        DISPLAY_PIXEL_BUDGET,
        NATIVE_PIXEL_BUDGET,
        UNCLASSIFIED_PIXEL_BUDGET,
        REQUEST_CREATION_FAILED,
    }

    /** Finite, per-enqueue rejection details; intentionally contains no paths or source bytes. */
    data class Rejection(
        val reason: RejectionReason,
        val capability: ManyueAiRuntime.Capability? = null,
        val sourceWidth: Int? = null,
        val sourceHeight: Int? = null,
        val targetWidth: Int? = null,
        val targetHeight: Int? = null,
        val inputBytes: Int? = null,
        val limit: Long? = null,
    )

    fun enqueue(
        context: Context,
        identity: ManyuePageBridge.Identity,
        originalBytes: ByteArray,
        priority: Int,
        expectedMode: Int = ManyueRuntimeState.modeInt,
        generation: Long = ManyueRuntimeState.generation,
        displayWidthPx: Int = ManyueRuntimeState.displayWidthPx,
        onRejected: (Rejection) -> Unit = {},
    ): String? {
        if (!ManyueEnhancementMode.fromInt(expectedMode).usesAi()) {
            return reject(onRejected, Rejection(RejectionReason.MODE_NOT_AI))
        }
        if (ManyueRuntimeState.modeInt != expectedMode || ManyueRuntimeState.generation != generation) {
            return reject(onRejected, Rejection(RejectionReason.SESSION_EXPIRED))
        }
        val model = ManyueRuntimeState.aiModel
        val capability = ManyueAiRuntime.probe(context, model)
        if (capability != ManyueAiRuntime.Capability.READY) {
            return reject(
                onRejected,
                Rejection(RejectionReason.MODEL_UNAVAILABLE, capability = capability),
            )
        }
        if (originalBytes.isEmpty()) {
            return reject(onRejected, Rejection(RejectionReason.EMPTY_INPUT))
        }
        if (originalBytes.size > ManyueAiRuntime.MAX_INPUT_BYTES) {
            return reject(
                onRejected,
                Rejection(
                    RejectionReason.INPUT_BYTES_LIMIT,
                    inputBytes = originalBytes.size,
                    limit = ManyueAiRuntime.MAX_INPUT_BYTES.toLong(),
                ),
            )
        }
        if (ManyueImagePipeline.isAnimated(originalBytes)) {
            return reject(onRejected, Rejection(RejectionReason.ANIMATED_IMAGE))
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            return reject(onRejected, Rejection(RejectionReason.INVALID_DECODE_BOUNDS))
        }
        val targetMode = model.scale
        val targetWidth = if (expectedMode == ManyueEnhancementMode.AUTO.value) {
            val decision = ManyueAutoEnhancementPolicy.decide(
                width,
                displayWidthPx,
                ManyueRuntimeState.aiScalePercent,
                model.continuousScale,
            )
            if (decision.path != ManyueAutoEnhancementPolicy.Path.AI) {
                val reason = when (decision.path) {
                    ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE -> RejectionReason.AUTO_ORIGINAL_SIZE
                    ManyueAutoEnhancementPolicy.Path.WAIT_FOR_WIDTH -> RejectionReason.AUTO_WAIT_FOR_WIDTH
                    ManyueAutoEnhancementPolicy.Path.AI -> RejectionReason.AUTO_WAIT_FOR_WIDTH
                }
                return reject(
                    onRejected,
                    Rejection(reason, sourceWidth = width, sourceHeight = height),
                )
            }
            decision.targetWidth
        } else {
            ManyueAiUpscaler.customTargetWidth(width, model.outputScalePercent(ManyueRuntimeState.aiScalePercent))
        }
        val resolvedHeight = ManyueAiUpscaler.targetHeight(width, height, targetWidth)
        if (targetWidth <= 0 || resolvedHeight <= 0) {
            return reject(
                onRejected,
                Rejection(
                    RejectionReason.INVALID_TARGET_BOUNDS,
                    sourceWidth = width,
                    sourceHeight = height,
                    targetWidth = targetWidth,
                    targetHeight = resolvedHeight,
                ),
            )
        }
        if (!ManyueAiSafetyPolicy.isNativeWorkSafe(model, width, height, targetWidth)) {
            val reason = when {
                !ManyueAiSafetyPolicy.isPixelBudgetSafe(
                    width,
                    height,
                    ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS,
                ) -> RejectionReason.SOURCE_PIXEL_BUDGET
                !ManyueAiSafetyPolicy.isPixelBudgetSafe(
                    targetWidth,
                    resolvedHeight,
                    ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS,
                ) -> RejectionReason.DISPLAY_PIXEL_BUDGET
                !model.continuousScale &&
                    !ManyueAiSafetyPolicy.isPredictedNativeOutputSafe(
                        width,
                        height,
                        ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS,
                    ) -> RejectionReason.NATIVE_PIXEL_BUDGET
                else -> RejectionReason.UNCLASSIFIED_PIXEL_BUDGET
            }
            val rejectedWidth = when (reason) {
                RejectionReason.NATIVE_PIXEL_BUDGET -> width.toLong().times(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                else -> targetWidth
            }
            val rejectedHeight = when (reason) {
                RejectionReason.SOURCE_PIXEL_BUDGET -> height
                RejectionReason.NATIVE_PIXEL_BUDGET -> height.toLong().times(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                else -> resolvedHeight
            }
            return reject(
                onRejected,
                Rejection(
                    reason,
                    sourceWidth = width,
                    sourceHeight = height,
                    targetWidth = rejectedWidth,
                    targetHeight = rejectedHeight,
                    limit = ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS.toLong(),
                ),
            )
        }
        // The resolved width is in both cache and request identity; legacy tenths stays native x2.
        val targetScaleTenths = 20
        val resolved = targetWidth
        if (!ManyueAiSafetyPolicy.isPixelBudgetSafe(
                resolved,
                resolvedHeight,
                ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS,
            )
        ) {
            return reject(
                onRejected,
                Rejection(
                    RejectionReason.DISPLAY_PIXEL_BUDGET,
                    sourceWidth = width,
                    sourceHeight = height,
                    targetWidth = resolved,
                    targetHeight = resolvedHeight,
                    limit = ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS.toLong(),
                ),
            )
        }

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
            anime4kOverlay = false,
            aiDetailStrength = if (model.continuousScale) ManyueRuntimeState.aiDetailStrength else 0,
        )
        if (reusable != null) {
            ManyueAiUpscaler.promotePriority(reusable, priority)
            ManyuePrefetchManager.register(
                identity.chapterId,
                identity.pageIndex,
                reusable,
                decisionReady = false,
                generation = generation,
            )
            ManyueAiUpscaler.queue(reusable)
            ManyuePrefetchManager.releaseCurrent(identity.chapterId, identity.pageIndex, generation)
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
                anime4kOverlay = false,
                aiDetailStrength = if (model.continuousScale) ManyueRuntimeState.aiDetailStrength else 0,
            )
            // Register priority first, but open read-ahead only after queue() has offered the
            // current request. A registration-only gate lets a future page win this small race.
            ManyuePrefetchManager.register(
                identity.chapterId,
                identity.pageIndex,
                token,
                decisionReady = false,
                generation = generation,
            )
            ManyueAiUpscaler.queue(token)
            ManyuePrefetchManager.releaseCurrent(identity.chapterId, identity.pageIndex, generation)
            logcat {
                "Manyue AI queued chapter=${identity.chapterId} page=${identity.pageIndex} priority=$priority token=$token"
            }
            ManyueDiagnostics.record("第 ${identity.pageIndex + 1} 页已排队，优先级 $priority")
            token
        } catch (t: Throwable) {
            input?.delete()
            logcat(LogPriority.WARN, t) { "Manyue AI request creation failed; original retained" }
            ManyueDiagnostics.record("第 ${identity.pageIndex + 1} 页创建任务失败")
            reject(
                onRejected,
                Rejection(
                    RejectionReason.REQUEST_CREATION_FAILED,
                    sourceWidth = width,
                    sourceHeight = height,
                    targetWidth = targetWidth,
                    targetHeight = resolvedHeight,
                ),
            )
        }
    }

    private fun reject(onRejected: (Rejection) -> Unit, rejection: Rejection): String? {
        runCatching { onRejected(rejection) }
        return null
    }
}
