package eu.kanade.tachiyomi.ui.reader.manyue

import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/**
 * Passive, bounded measurement of visible-region exposures. A swap is not a draw.
 * Source fingerprints/real database IDs stay in memory and are never exported.
 */
object ManyueReadingCoverage {
    data class Source(
        val mangaId: Long,
        val chapterId: Long,
        val page: Int,
        val fingerprint: String? = null,
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0,
    )

    data class Result(
        val source: Source,
        val generation: Long,
        val model: Int,
        val mode: Int,
        val scalePercent: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val token: String,
    )

    internal data class Configuration(val generation: Long, val model: Int, val mode: Int, val scale: Int)
    internal class Exposure(
        val id: Int,
        var source: Source,
        val chapter: Int,
        val configuration: Configuration,
        val enteredNs: Long,
        var exitedNs: Long? = null,
        var firstDrawNs: Long? = null,
        var firstAiDrawNs: Long? = null,
        var result: Int? = null,
        var outputWidth: Int = 0,
        var outputHeight: Int = 0,
        var sourceId: Int = 0,
        var failureCount: Int = 0,
        var invalidDrawCount: Int = 0,
        var dimensionMismatch: Boolean = false,
        var maxViewportPermille: Int = 0,
        var maxPagePermille: Int = 0,
        var shortPage: Boolean = false,
        var reason: String = "active",
    )

    private const val MAX_EXPOSURES = 1024
    private const val MAX_RESULTS = 2048
    private const val MIN_DWELL_NS = 100_000_000L
    private const val TIMELY_NS = 2_000_000_000L
    private val lock = Any()
    private val active = IdentityHashMap<Any, Exposure>()
    private val exposures = ArrayList<Exposure>()
    private val chapters = LinkedHashMap<Pair<Long, Long>, Int>()
    private val sources = LinkedHashMap<String, Int>()
    private val results = LinkedHashMap<String, Int>()
    private val ready = LinkedHashMap<Int, Pair<Long, Boolean>>()
    private val stamps = LinkedHashMap<Int, Result>()
    private var droppedExposures = 0
    private var droppedResults = 0
    @Volatile private var resetEpoch = 0L

    val enabled: Boolean get() = ManyuePerformanceDiagnostics.enabled
    internal val epoch: Long get() = resetEpoch

    internal var clockOverride: (() -> Long)? = null
    private fun now(): Long = clockOverride?.invoke() ?: ManyuePerformanceDiagnostics.recorder.elapsedNs()
    private fun configuration() = Configuration(
        ManyueRuntimeState.generation,
        ManyueRuntimeState.aiModel.ordinal,
        ManyueRuntimeState.modeInt,
        ManyueRuntimeState.aiScalePercent,
    )

    fun clear() = synchronized(lock) {
        active.clear()
        exposures.clear()
        chapters.clear()
        sources.clear()
        results.clear()
        ready.clear()
        stamps.clear()
        droppedExposures = 0
        droppedResults = 0
        resetEpoch++
    }

    private fun samePage(a: Source, b: Source): Boolean =
        a.mangaId == b.mangaId && a.chapterId == b.chapterId && a.page == b.page

    private fun sourceId(source: Source): Int {
        val fingerprint = source.fingerprint ?: return 0
        if (fingerprint !in sources && sources.size >= MAX_RESULTS) return 0
        return sources.getOrPut(fingerprint) { sources.size + 1 }
    }

    private fun end(owner: Any, time: Long, reason: String) {
        active.remove(owner)?.let {
            it.exitedNs = time
            it.reason = reason
        }
    }

    fun exposure(
        owner: Any,
        source: Source,
        viewportFractionPermille: Int,
        pageFractionPermille: Int,
        isShortPage: Boolean = false,
    ) {
        if (!enabled) return
        synchronized(lock) {
            val time = now()
            val config = configuration()
            if (!ManyueEnhancementMode.fromInt(config.mode).usesAi() ||
                (viewportFractionPermille < 200 && !(isShortPage && pageFractionPermille >= 500))
            ) {
                end(owner, time, "left_visible_region")
                return
            }
            var current = active[owner]
            if (current != null &&
                (!samePage(current.source, source) || current.configuration != config ||
                    (current.source.fingerprint != null && source.fingerprint != null &&
                        current.source.fingerprint != source.fingerprint))
            ) {
                end(owner, time, "identity_or_configuration_changed")
                current = null
            }
            if (current == null) {
                if (exposures.size >= MAX_EXPOSURES) {
                    droppedExposures++
                    return
                }
                current = Exposure(
                    exposures.size + 1,
                    source,
                    chapters.getOrPut(source.mangaId to source.chapterId) { chapters.size + 1 },
                    config,
                    time,
                )
                exposures.add(current)
                active[owner] = current
            }
            // Loading placeholders retain their original entry time when the source becomes known.
            if (source.fingerprint != null) current.source = source
            current.sourceId = sourceId(current.source)
            current.maxViewportPermille = maxOf(current.maxViewportPermille, viewportFractionPermille.coerceIn(0, 1000))
            current.maxPagePermille = maxOf(current.maxPagePermille, pageFractionPermille.coerceIn(0, 1000))
            current.shortPage = isShortPage
        }
    }

    fun hidden(owner: Any) {
        if (!enabled) return
        synchronized(lock) { end(owner, now(), "left_visible_region") }
    }

    /**
     * Validates a late AI source stamp and upgrades a matching active exposure when present.
     * This never creates/restarts an exposure, so its entry time and dwell stay intact. The caller
     * must still report an actual active-child draw through [drawn].
     */
    internal fun upgradeActiveSource(
        owner: Any,
        currentSource: Source,
        result: Result,
    ): Boolean {
        if (!enabled) return false
        synchronized(lock) {
            val config = configuration()
            if (result.generation != config.generation ||
                result.model != config.model ||
                result.mode != config.mode ||
                result.scalePercent != config.scale ||
                currentSource.fingerprint != null ||
                !samePage(currentSource, result.source) ||
                currentSource.sourceWidth <= 0 || currentSource.sourceHeight <= 0 ||
                currentSource.sourceWidth != result.source.sourceWidth ||
                currentSource.sourceHeight != result.source.sourceHeight ||
                result.source.fingerprint.isNullOrBlank() ||
                result.outputWidth <= 0 || result.outputHeight <= 0
            ) {
                return false
            }
            val current = active[owner] ?: return true
            if (current.configuration != config) return false
            if (current.source.fingerprint != null) return current.source == result.source
            if (!samePage(current.source, currentSource) ||
                current.source.sourceWidth != currentSource.sourceWidth ||
                current.source.sourceHeight != currentSource.sourceHeight
            ) {
                return false
            }
            current.source = result.source
            current.sourceId = sourceId(result.source)
            return true
        }
    }

    /** First submitted reader frame includes placeholders and rejected stale active images. */
    fun frameSubmitted(owner: Any) {
        if (!enabled) return
        synchronized(lock) {
            active[owner]?.let { if (it.firstDrawNs == null) it.firstDrawNs = now() }
        }
    }

    fun endReader() {
        synchronized(lock) {
            val time = now()
            active.keys.toList().forEach { end(it, time, "reader_paused_or_finished") }
        }
    }

    private fun resultId(token: String): Int? {
        results[token]?.let { return it }
        if (results.size >= MAX_RESULTS) {
            droppedResults++
            return null
        }
        return (results.size + 1).also { results[token] = it }
    }

    /** Called for an actual READY result, including cache reuse, independently of visibility. */
    fun ready(result: Result, prefetch: Boolean) {
        if (!enabled) return
        synchronized(lock) {
            val id = resultId(result.token) ?: return
            stamps[id] = result
            ready.putIfAbsent(id, now() to prefetch)
        }
    }

    /** Joins numeric queue/native events to chapter, source and frozen request configuration. */
    fun requested(result: Result) {
        if (!enabled) return
        synchronized(lock) {
            val id = resultId(result.token) ?: return
            stamps.putIfAbsent(id, result)
        }
    }

    /** Called synchronously after the current, loaded child submits its draw pass. */
    fun drawn(owner: Any, source: Source, result: Result?) {
        if (!enabled) return
        synchronized(lock) {
            val current = active[owner] ?: return
            if (!samePage(current.source, source)) return
            val time = now()
            if (current.firstDrawNs == null) current.firstDrawNs = time
            if (result == null) return
            val config = current.configuration
            val expected = current.source
            if (config != configuration() || result.generation != config.generation ||
                result.model != config.model || result.mode != config.mode || result.scalePercent != config.scale ||
                !samePage(result.source, expected) || expected.fingerprint == null ||
                expected.fingerprint != result.source.fingerprint ||
                result.source.sourceWidth != expected.sourceWidth || result.source.sourceHeight != expected.sourceHeight ||
                result.outputWidth <= 0 || result.outputHeight <= 0
            ) {
                current.invalidDrawCount++
                return
            }
            if (current.firstAiDrawNs != null) return
            current.result = resultId(result.token)
            if (current.result == null) return
            current.firstAiDrawNs = time
            current.outputWidth = result.outputWidth
            current.outputHeight = result.outputHeight
        }
    }

    fun drawFailure(owner: Any) {
        if (!enabled) return
        synchronized(lock) { active[owner]?.let { it.failureCount++ } }
    }

    /** A cropped decoder has a different source coordinate extent; do not silently count it. */
    fun rejectedDimensions(owner: Any) {
        if (!enabled) return
        synchronized(lock) {
            active[owner]?.let {
                if (!it.dimensionMismatch) it.invalidDrawCount++
                it.dimensionMismatch = true
            }
        }
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        val time = now()
        val rows = exposures.map { item ->
            val end = item.exitedNs ?: time
            val eligible = end - item.enteredNs >= MIN_DWELL_NS
            val drew = item.firstAiDrawNs?.let { it <= end } == true && item.failureCount == 0
            val timely = drew && item.firstAiDrawNs!! - item.enteredNs <= TIMELY_NS
            val firstFrame = drew && item.firstAiDrawNs == item.firstDrawNs
            val readyTime = item.result?.let { ready[it]?.first }
            JSONObject().apply {
                put("exposure", item.id)
                put("chapter", item.chapter)
                put("page", item.source.page)
                put("source", item.sourceId)
                put("sourceWidth", item.source.sourceWidth)
                put("sourceHeight", item.source.sourceHeight)
                put("outputWidth", item.outputWidth)
                put("outputHeight", item.outputHeight)
                put("generation", item.configuration.generation)
                put("model", item.configuration.model)
                put("mode", item.configuration.mode)
                put("scalePercent", item.configuration.scale)
                put("enteredNs", item.enteredNs)
                put("exitedNs", end)
                put("closed", item.exitedNs != null)
                put("eligible", eligible)
                put("firstFrame", firstFrame)
                put("timely", timely)
                put("displayedDuringExposure", drew)
                put("late", drew && !timely)
                put("firstDrawNs", item.firstDrawNs ?: JSONObject.NULL)
                put("aiDrawNs", item.firstAiDrawNs ?: JSONObject.NULL)
                put("aiReadyNs", readyTime ?: JSONObject.NULL)
                put("result", item.result ?: JSONObject.NULL)
                put("drawFailureCount", item.failureCount)
                put("rejectedDrawCount", item.invalidDrawCount)
                put("maxViewportPermille", item.maxViewportPermille)
                put("maxPagePermille", item.maxPagePermille)
                put("shortPage", item.shortPage)
                put("reason", when {
                    !eligible -> "shorter_than_100ms"
                    item.failureCount > 0 -> "active_image_draw_error"
                    item.sourceId == 0 -> "source_unavailable"
                    !drew && item.dimensionMismatch -> "cropped_or_mismatched_output_dimensions"
                    !drew -> "no_correct_ai_draw_before_exit"
                    !timely -> "late_ai_draw"
                    else -> item.reason
                })
            }
        }
        val used = exposures.filter {
            (it.exitedNs ?: time) - it.enteredNs >= MIN_DWELL_NS &&
                it.firstAiDrawNs != null && it.failureCount == 0
        }.mapNotNull { it.result }.toSet()
        val prefetched = ready.filterValues { it.second }.keys
        JSONObject().apply {
            put("unit", "qualifying visible-region exposure of a whole-page AI output")
            put("drawEvidence", "Loaded active image child submitted an Android Canvas draw; not SurfaceFlinger scanout or all high-resolution region tiles.")
            put("sourceDimensionMeaning", "AI input bytes after any reader transformation; original source dimensions remain in reader metadata.")
            put("deltaNs", TIMELY_NS)
            put("minimumDwellNs", MIN_DWELL_NS)
            put("viewportThresholdPermille", 200)
            put("shortPageThresholdPermille", 500)
            put("exposures", JSONArray(rows))
            put("results", JSONArray(stamps.map { (id, stamp) ->
                JSONObject().apply {
                    put("result", id)
                    put("job", stamp.token.hashCode())
                    put("chapter", chapters.getOrPut(stamp.source.mangaId to stamp.source.chapterId) { chapters.size + 1 })
                    put("page", stamp.source.page)
                    put("source", sourceId(stamp.source))
                    put("sourceWidth", stamp.source.sourceWidth)
                    put("sourceHeight", stamp.source.sourceHeight)
                    put("outputWidth", stamp.outputWidth)
                    put("outputHeight", stamp.outputHeight)
                    put("generation", stamp.generation)
                    put("model", stamp.model)
                    put("mode", stamp.mode)
                    put("scalePercent", stamp.scalePercent)
                    put("readyNs", ready[id]?.first ?: JSONObject.NULL)
                    put("prefetch", ready[id]?.second ?: false)
                }
            }))
            put("completedPrefetchResults", prefetched.size)
            put("usedPrefetchResults", prefetched.count { it in used })
            put("completedResults", ready.size)
            put("droppedExposures", droppedExposures)
            put("droppedResults", droppedResults)
        }
    }
}
