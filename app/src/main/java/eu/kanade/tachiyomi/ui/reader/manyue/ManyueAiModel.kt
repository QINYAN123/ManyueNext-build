package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * Lite reconstructs pixels directly at the requested size using a shared low-resolution
 * encoder. Quality offers the original4x->2x graph and a separate native2x
 * output-head candidate. The old Real-CUGAN
 * identity remains only for compatibility with in-flight requests and historical cache keys;
 * it is not a selectable product model and old saved selections migrate to Real-ESRGAN.
 */
enum class ManyueAiModel(
    val id: String,
    val label: String,
    val runnerName: String,
    val assetDir: String,
    val runtimeDir: String,
    val modelFiles: List<String>,
    val scale: Int = 2,
    val continuousScale: Boolean = false,
) {
    MOBILE_LITE(
        id = "manyue_lite_v1",
        label = "轻量 · Manyue Lite 1–2×",
        runnerName = "libmanyue_lite.so",
        assetDir = "ai/models-Manyue-Lite",
        runtimeDir = "models-Manyue-Lite-v1",
        modelFiles = listOf("trunk.param", "trunk.bin", "head.param", "head.bin", "head.f32"),
        continuousScale = true,
    ),
    FAST_REAL_CUGAN(
        id = "realcugan_fast",
        label = "快速 · Real-CUGAN x2",
        runnerName = "libmanyue_realcugan.so",
        assetDir = "ai/models-Real-CUGAN-se",
        runtimeDir = "models-se",
        modelFiles = listOf("up2x-no-denoise.bin", "up2x-no-denoise.param"),
    ),
    QUALITY_REAL_ESRGAN(
        id = "realesrgan_quality",
        label = "质量 · 原模型（4×→2×）",
        runnerName = "libmanyue_realesr.so",
        assetDir = "ai/models-Real-ESRGANv3-anime",
        runtimeDir = "models-Real-ESRGANv3-anime",
        modelFiles = listOf("x2.bin", "x2.param"),
    ),
    QUALITY_NATIVE2(
        id = "realesrgan_native2_head3",
        label = "质量 · 原生2×（实验）",
        runnerName = "libmanyue_realesr.so",
        assetDir = "ai/models-Real-ESRGANv3-anime-native2",
        runtimeDir = "models-Real-ESRGANv3-anime-native2-head3-a3a99b7b",
        modelFiles = listOf("x2.bin", "x2.param"),
    ),
    ;

    companion object {
        val DEFAULT = QUALITY_REAL_ESRGAN

        /** Models offered to readers. FAST_REAL_CUGAN remains an internal legacy identity. */
        val selectableModels = listOf(QUALITY_REAL_ESRGAN, QUALITY_NATIVE2, MOBILE_LITE)

        fun canonicalPreferenceId(id: String?): String? =
            if (id == FAST_REAL_CUGAN.id) QUALITY_REAL_ESRGAN.id else id

        fun fromId(id: String?): ManyueAiModel {
            val canonicalId = canonicalPreferenceId(id)
            return entries.firstOrNull { it.id == canonicalId } ?: DEFAULT
        }
    }

    val cacheIdentity: String
        get() = when {
            continuousScale -> "$id:${ManyueLiteAssets.MODEL_REVISION}"
            this == QUALITY_REAL_ESRGAN -> "$id:neutral-source-v1"
            this == QUALITY_NATIVE2 -> "$id:native2-head3-a3a99b7b-neutral-source-v1"
            else -> id
        }

    /** Both fixed2x quality graphs share verified submission and cancellation behavior. */
    val usesQualityRuntime: Boolean
        get() = this == QUALITY_REAL_ESRGAN || this == QUALITY_NATIVE2

    /** Fractional reconstruction is a Lite feature; legacy quality work always outputs native 2x. */
    fun outputScalePercent(savedPercent: Int): Int =
        if (continuousScale) savedPercent.coerceIn(100, 200) else 200

    internal fun displayedState(detailStrength: Int, classicApplied: Boolean = false): ManyueEnhancementState =
        if (continuousScale && detailStrength <= 0) {
            if (classicApplied) ManyueEnhancementState.CLASSIC_READY else ManyueEnhancementState.INTERPOLATED_READY
        } else if (classicApplied) {
            ManyueEnhancementState.AI_CLASSIC_READY
        } else {
            ManyueEnhancementState.AI_READY
        }
}
