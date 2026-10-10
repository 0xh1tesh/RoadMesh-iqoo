package com.jsons.roadmesh.core.junction

import kotlin.math.abs
import kotlin.math.sqrt

data class Gauss(val mean: Double, val variance: Double) {
    init { require(mean.isFinite() && variance.isFinite() && variance > 0) { "invalid Gaussian" } }
    val sigma get() = sqrt(variance)
}

/**
 * What one equipped node on a lane reports, in edge coordinates. [s] is distance from the entry leaf toward the stop
 * line. [leadGap] is its camera's distance to the vehicle directly ahead (centre to centre), if it sees one.
 */
data class LaneReport(val id: String, val s: Gauss, val leadGap: Gauss? = null)

data class LaneActor(val id: String, val equipped: Boolean, val s: Gauss)

/**
 * Exact shared estimate of one lane. Nodes ordered front to back form a chain: each factor links a vehicle to the one
 * ahead of it. Gaussian belief propagation on a chain is exact after one forward and one backward pass, and every
 * message is two numbers that can hop vehicle to vehicle.
 */
object LaneBelief {
    /**
     * [priors] front to back (null = no absolute fix). [gaps][k] observes x[k] − x[k+1] (null = no observation).
     * Returns null for a node whose position nothing anchors.
     */
    fun fuse(priors: List<Gauss?>, gaps: List<Gauss?>): List<Gauss?> {
        val n = priors.size
        require(gaps.size == maxOf(0, n - 1)) { "need n-1 gaps" }
        val ue = DoubleArray(n) { i -> priors[i]?.let { it.mean / it.variance } ?: 0.0 }
        val ul = DoubleArray(n) { i -> priors[i]?.let { 1 / it.variance } ?: 0.0 }
        // Forward messages into node k from k-1, backward messages into node k from k+1.
        val fe = DoubleArray(n); val fl = DoubleArray(n); val be = DoubleArray(n); val bl = DoubleArray(n)
        for (k in 1 until n) {
            val g = gaps[k - 1] ?: continue
            val w = 1 / g.variance; val z = g.mean
            val le = ue[k - 1] + fe[k - 1]; val ll = ul[k - 1] + fl[k - 1]
            fl[k] = w - w * w / (ll + w)
            fe[k] = -w * z + w * (le + w * z) / (ll + w)
        }
        for (k in n - 2 downTo 0) {
            val g = gaps[k] ?: continue
            val w = 1 / g.variance; val z = g.mean
            val re = ue[k + 1] + be[k + 1]; val rl = ul[k + 1] + bl[k + 1]
            bl[k] = w - w * w / (rl + w)
            be[k] = w * z + w * (re - w * z) / (rl + w)
        }
        return List(n) { k ->
            val l = ul[k] + fl[k] + bl[k]
            if (l <= 1e-12) null else Gauss((ue[k] + fe[k] + be[k]) / l, 1 / l)
        }
    }

    /**
     * Builds the lane from reports of equipped nodes. A camera gap that matches the equipped node ahead within
     * [gateSigma] links them; otherwise the observer has seen an unequipped vehicle and a proxy node is inserted.
     */
    fun build(reports: List<LaneReport>, gateSigma: Double = 3.0): List<LaneActor> {
        val ordered = reports.sortedByDescending { it.s.mean }
        val ids = ArrayList<String>(); val equipped = ArrayList<Boolean>(); val priors = ArrayList<Gauss?>(); val gaps = ArrayList<Gauss?>()
        ordered.forEachIndexed { i, r ->
            val ahead = ordered.getOrNull(i - 1)
            val gap = r.leadGap
            if (i > 0) {
                val matches = gap != null && ahead != null &&
                    abs(ahead.s.mean - (r.s.mean + gap.mean)) <= gateSigma * sqrt(ahead.s.variance + r.s.variance + gap.variance)
                if (gap != null && !matches) {
                    // Unequipped vehicle between the node ahead and this observer; only the observer anchors it.
                    gaps += null; ids += "proxy:${r.id}"; equipped += false; priors += null
                    gaps += gap
                } else gaps += if (matches) gap else null
            } else if (gap != null) {
                ids += "proxy:${r.id}"; equipped += false; priors += null; gaps += gap
            }
            ids += r.id; equipped += true; priors += r.s
        }
        val fused = fuse(priors, gaps)
        return ids.indices.mapNotNull { i -> fused[i]?.let { LaneActor(ids[i], equipped[i], it) } }
    }
}
