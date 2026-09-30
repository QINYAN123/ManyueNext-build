package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * The two supported AI paths have fixed 2x model inference. The worker can resize that
 * result to a requested 1x–2x target before encoding, without modifying the source file.
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
) {
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
        val DEFAULT = FAST_REAL_CUGAN

        fun fromId(id: String?): ManyueAiModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
