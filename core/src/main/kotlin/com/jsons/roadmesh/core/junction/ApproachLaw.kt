package com.jsons.roadmesh.core.junction

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Starting values for an urban approach. Tune from field data; they are not verified road thresholds. */
data class ApproachConfig(
    val speedLimitMps: Double = 13.9,
    val standstillGapM: Double = 7.0,
    val headwayS: Double = 1.2,
    val comfortDecelMps2: Double = 2.5,
    val maxAccelMps2: Double = 1.5,
    val lookaheadS: Double = 1.0,
    val reactionS: Double = 1.0,
    val crossingSpeedMps: Double = 5.0,
) {
    fun validate() = apply {
        require(speedLimitMps in 2.0..40.0 && standstillGapM in 3.0..20.0 && headwayS in 0.5..4.0) { "speed/gap/headway" }
        require(comfortDecelMps2 in 0.5..6.0 && maxAccelMps2 in 0.3..4.0 && lookaheadS in 0.0..3.0 && reactionS in 0.0..3.0) { "accel limits" }
        require(crossingSpeedMps in 1.0..speedLimitMps) { "crossing speed" }
    }
}

/**
 * What one node knows about itself on an inbound edge. [predecessorPlanMps] is the speed the vehicle ahead has
 * broadcast as its own target: using the plan instead of its observed braking is what stops disturbances from
 * growing along the queue.
 */
data class ApproachView(
    val now: Double,
    val distanceToLineM: Double,
    val speedMps: Double,
    val slot: Double?,
    val gapM: Double? = null,
    val predecessorPlanMps: Double? = null,
    val predecessorSpeedMps: Double? = null,
    val windowS: Double = 0.0,
)

object ApproachLaw {
    /**
     * Target speed: arrive at the stop line at crossing speed in the middle of the slot's entry window, keep a safe
     * following speed behind the predecessor, never exceed the limit. Without a slot, be able to stop at the line.
     */
    fun target(v: ApproachView, c: ApproachConfig): Double {
        val toLine = max(0.0, v.distanceToLineM)
        val lineSpeed = when (val slot = v.slot) {
            null -> sqrt(2 * c.comfortDecelMps2 * toLine)
            else -> {
                val left = slot + v.windowS / 2 - v.now
                minOf(if (left <= 1e-3) c.speedLimitMps else toLine / left, sqrt(c.crossingSpeedMps * c.crossingSpeedMps + 2 * c.comfortDecelMps2 * toLine))
            }
        }
        val gapSpeed = v.gapM?.let { followSpeed(it, v.speedMps, v.predecessorPlanMps, v.predecessorSpeedMps, c) } ?: Double.POSITIVE_INFINITY
        return minOf(c.speedLimitMps, lineSpeed, gapSpeed).coerceAtLeast(0.0)
    }

    /**
     * Highest safe speed behind a predecessor: the constant time headway against its broadcast plan (feed-forward),
     * and the Gipps bound, so that after [ApproachConfig.reactionS] both can brake at the comfort rate without contact.
     */
    fun followSpeed(gapM: Double, speedMps: Double, predecessorPlanMps: Double?, predecessorSpeedMps: Double?, c: ApproachConfig): Double {
        val ahead = predecessorPlanMps ?: predecessorSpeedMps ?: speedMps
        val headway = (gapM + (ahead - speedMps) * c.lookaheadS - c.standstillGapM) / c.headwayS
        val worstAhead = listOfNotNull(predecessorPlanMps, predecessorSpeedMps).minOrNull() ?: ahead
        val room = gapM - c.standstillGapM
        val bt = c.comfortDecelMps2 * c.reactionS
        val stoppable = if (room <= 0) 0.0 else -bt + sqrt(bt * bt + worstAhead * worstAhead + 2 * c.comfortDecelMps2 * room)
        return max(0.0, minOf(headway, stoppable))
    }

    /** Fastest arrival at the line: accelerate to the limit, cruise, and brake to crossing speed just before the line. */
    fun minimumTimeToLine(distanceM: Double, speedMps: Double, c: ApproachConfig): Double {
        val d = max(0.0, distanceM); val v = speedMps.coerceIn(0.0, c.speedLimitMps); val a = c.maxAccelMps2
        val vl = c.speedLimitMps; val vx = c.crossingSpeedMps; val b = c.comfortDecelMps2
        val tAcc = (vl - v) / a
        val dAcc = (v + vl) / 2 * tAcc
        val dDec = (vl * vl - vx * vx) / (2 * b)
        return when {
            dAcc + dDec <= d -> tAcc + (d - dAcc - dDec) / vl + (vl - vx) / b
            dAcc >= d -> (-v + sqrt(v * v + 2 * a * d)) / a
            else -> tAcc + (d - dAcc) / vl
        }
    }
}

enum class AdviceKind(val speech: String) {
    HOLD("Hold %d."), EASE_OFF("Ease off to %d."), STOP_AT_LINE("Stop at the line."), PROCEED("Proceed.")
}

data class Advice(val kind: AdviceKind, val kmh: Int, val urgent: Boolean) {
    val speech get() = if ('%' in kind.speech) kind.speech.format(kmh) else kind.speech
}

/**
 * Turns the continuous target into few, stable spoken instructions: quantized to [stepKmh], at most one every
 * [minIntervalS] unless the change is urgent (stop at line, or a cut bigger than [urgentCutMps]).
 */
class Advisor(private val stepKmh: Int = 5, private val minIntervalS: Double = 3.0, private val urgentCutMps: Double = 3.0) {
    private var last: Advice? = null
    private var lastAt = Double.NEGATIVE_INFINITY

    fun update(now: Double, targetMps: Double, speedMps: Double, cleared: Boolean): Advice? {
        val kmh = ((targetMps * 3.6 / stepKmh).roundToInt() * stepKmh)
        val kind = when {
            cleared -> AdviceKind.PROCEED
            targetMps < 0.5 -> AdviceKind.STOP_AT_LINE
            targetMps < speedMps - 1.0 -> AdviceKind.EASE_OFF
            else -> AdviceKind.HOLD
        }
        val urgent = kind == AdviceKind.STOP_AT_LINE || speedMps - targetMps > urgentCutMps
        val previous = last
        val changed = previous == null || previous.kind != kind || abs(previous.kmh - kmh) >= stepKmh
        if (!changed || !urgent && now - lastAt < minIntervalS) return null
        return Advice(kind, kmh, urgent).also { last = it; lastAt = now }
    }
}
