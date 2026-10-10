package com.jsons.roadmesh.core.swarm

/** One COMPLETED, directly observed episode; silence, a missing packet or relayed prediction is not a clearance. */
data class ClearanceEpisode(
    val id: String,
    val region: String,
    val sourceId: String,
    val onsetMs: Long,
    val clearedMs: Long,
    val directlyObserved: Boolean = false,
)

data class ClearanceEstimate(
    val sampleCount: Int,
    val estimatedSeconds: Double,
    val minSeconds: Double,
    val maxSeconds: Double,
)

/**
 * Local rolling-median estimator in SHADOW mode. Never used to release vehicles or declare passage safe.
 * The caller must provide directly observed onset AND clearance, in this phone's monotonic time base.
 * Episodes must be ingested chronologically and must not overlap within a region. A global watermark rejects
 * late/replayed training even after bounded context eviction. It intentionally sacrifices out-of-order training.
 * Observations prove only duration in one context; the sample range is not a prediction-confidence interval.
 */
class ClearanceEstimator(
    private val windowSize: Int = 5,
    private val maxContexts: Int = 8,
    private val minimumSamples: Int = 3,
) {
    init { require(windowSize in 3..64 && maxContexts in 1..64 && minimumSamples in 1..windowSize) }
    private data class Context(val durations: ArrayDeque<Double> = ArrayDeque(), var clearedMs: Long = -1L)
    private val contexts = linkedMapOf<String, Context>()
    private var lastAcceptedClearedMs = -1L
    // A recent identity guard also rejects a duplicate whose sender incorrectly supplies a later timestamp.
    private val recentIds = linkedSetOf<Pair<String, String>>()

    fun observe(episode: ClearanceEpisode, nowMs: Long): Boolean {
        if (!episode.directlyObserved || episode.id.isBlank() || episode.sourceId.isBlank() || episode.region.isBlank() ||
            episode.id.length > 160 || episode.sourceId.length > 160 || episode.region.length > 160) return false
        if (episode.onsetMs < 0 || episode.clearedMs <= episode.onsetMs || episode.clearedMs > nowMs ||
            episode.clearedMs <= lastAcceptedClearedMs || nowMs - episode.clearedMs > 300_000) return false
        val durationMs = episode.clearedMs - episode.onsetMs
        if (durationMs !in 1_000..300_000) return false
        val identity = episode.sourceId to episode.id
        if (identity in recentIds) return false
        val existing = contexts[episode.region]
        if (existing != null && episode.onsetMs < existing.clearedMs) return false
        val context = contexts.remove(episode.region) ?: Context()
        if (contexts.size == maxContexts) contexts.remove(contexts.keys.first())
        if (context.durations.size == windowSize) context.durations.removeFirst()
        context.durations.add(durationMs / 1_000.0)
        context.clearedMs = episode.clearedMs
        contexts[episode.region] = context
        lastAcceptedClearedMs = episode.clearedMs
        if (recentIds.size == maxContexts * windowSize * 2) recentIds.remove(recentIds.first())
        recentIds.add(identity)
        return true
    }

    fun estimate(region: String): ClearanceEstimate? {
        val durations = contexts[region]?.durations?.sorted() ?: return null
        if (durations.size < minimumSamples) return null
        val middle = durations.size / 2
        val median = if (durations.size % 2 == 1) durations[middle] else (durations[middle - 1] + durations[middle]) / 2.0
        return ClearanceEstimate(durations.size, median, durations.first(), durations.last())
    }
}

data class ClearanceLearningResult(
    val label: String,
    val trainingSamples: Int,
    val estimateSeconds: Double,
    val baselineSeconds: Double,
    val heldOutSeconds: List<Double>,
    val adaptiveMaeSeconds: Double,
    val baselineMaeSeconds: Double,
    val detail: String,
)

/** Deliberately synthetic training and disjoint evaluation episodes. Includes a distribution-shift failure. */
object ClearanceLearningDemo {
    fun run(): List<ClearanceLearningResult> {
        val estimator = ClearanceEstimator()
        var clockMs = 1_000L
        var sequence = 0
        fun train(seconds: List<Double>) {
            seconds.forEach { duration ->
                val start = clockMs
                clockMs += (duration * 1_000).toLong()
                check(estimator.observe(ClearanceEpisode("episode-${sequence++}", "market-passage", "fixture-observer", start, clockMs, true), clockMs))
                clockMs += 1_000
            }
        }
        fun evaluate(label: String, heldOut: List<Double>, detail: String): ClearanceLearningResult {
            val estimate = checkNotNull(estimator.estimate("market-passage"))
            val baseline = 20.0
            return ClearanceLearningResult(label, estimate.sampleCount, estimate.estimatedSeconds, baseline, heldOut,
                heldOut.map { kotlin.math.abs(it - estimate.estimatedSeconds) }.average(),
                heldOut.map { kotlin.math.abs(it - baseline) }.average(), detail)
        }
        train(listOf(9.0, 10.0, 11.0, 12.0, 10.0))
        val stable = evaluate("Stable context", listOf(10.0, 12.0, 9.0),
            "Synthetic held-out episodes were not used in training. Compare a rolling median with a configured fixed 20 s estimate.")
        val shift = evaluate("Unseen congestion shift", listOf(40.0, 48.0, 45.0),
            "The unchanged model underestimates a new traffic regime and performs worse than the fixed estimate. Past clearance is not permission to proceed.")
        // These are new completed feedback episodes, not any of the held-out episode objects above.
        train(listOf(40.0, 48.0, 45.0))
        val recovery = evaluate("After new observed outcomes", listOf(44.0, 46.0, 42.0),
            "Three new direct-observation training episodes update the five-sample window. Evaluation uses further unseen synthetic episodes.")
        return listOf(stable, shift, recovery)
    }
}
