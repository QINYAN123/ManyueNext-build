package eu.kanade.tachiyomi.ui.reader.manyue

/** Compare image pixels with the stable reader width in pixels, never density-independent dp. */
object ManyueAutoEnhancementPolicy {
    // A few extra display pixels should not launch a full fixed-x2 network.
    private const val WIDTH_TOLERANCE_PERCENT = 5
    enum class Path { WAIT_FOR_WIDTH, ORIGINAL_SIZE, AI }
    data class Decision(val path: Path, val targetWidth: Int)

    fun decide(
        sourceWidth: Int,
        displayWidth: Int,
        maximumScalePercent: Int = 200,
        continuousScale: Boolean = false,
    ): Decision {
        if (sourceWidth <= 0 || displayWidth <= 0) return Decision(Path.WAIT_FOR_WIDTH, 0)
        val maximumWidth = ManyueAiUpscaler.customTargetWidth(sourceWidth, maximumScalePercent)
        val target = minOf(displayWidth, maximumWidth)
        return if (target.toLong() * 100L >
            sourceWidth.toLong() * (100L + WIDTH_TOLERANCE_PERCENT)
        ) {
            Decision(Path.AI, target)
        } else {
            Decision(if (continuousScale) Path.AI else Path.ORIGINAL_SIZE, sourceWidth)
        }
    }
}
