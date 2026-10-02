package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * Lite reconstructs pixels directly at the requested size using a shared low-resolution
 * encoder. The legacy networks still infer at 2x before native target resizing.
 * Keeping the model identity in the request/cache key prevents a result rendered by
 * Real-CUGAN from being mistaken for a Real-ESRGAN result after the user switches modes.
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
        label = "质量 · Real-ESRGAN x2",
        runnerName = "libmanyue_realesr.so",
        assetDir = "ai/models-Real-ESRGANv3-anime",
        runtimeDir = "models-Real-ESRGANv3-anime",
        modelFiles = listOf("x2.bin", "x2.param"),
    ),
    ;

    companion object {
        val DEFAULT = MOBILE_LITE

        fun fromId(id: String?): ManyueAiModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }

    val cacheIdentity: String
        get() = if (continuousScale) "$id:${ManyueLiteAssets.MODEL_REVISION}" else id

    internal fun displayedState(detailStrength: Int, classicApplied: Boolean = false): ManyueEnhancementState =
        if (continuousScale && detailStrength <= 0) {
            if (classicApplied) ManyueEnhancementState.CLASSIC_READY else ManyueEnhancementState.INTERPOLATED_READY
        } else if (classicApplied) {
            ManyueEnhancementState.AI_CLASSIC_READY
        } else {
            ManyueEnhancementState.AI_READY
        }
}
