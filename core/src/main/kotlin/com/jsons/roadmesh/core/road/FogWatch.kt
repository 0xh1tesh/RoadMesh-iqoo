package com.jsons.roadmesh.core.road

import kotlin.math.roundToInt

/**
 * Starting values for fog. [visibilityM] is what the driver is assumed to see (set by the driver, not measured), and
 * [ttcS] is the time to contact that triggers a warning, longer than the usual 2 s because a fog driver sees the hazard late.
 */
data class FogRules(
    val visibilityM: Double = 50.0,
    val reactionS: Double = 1.5,
    val brakeMps2: Double = 4.5,
    val ttcS: Double = 4.0,
    /** Also warn once a vehicle is within this many stopping distances, so there is room left to stop after hearing it. */
    val stopFactor: Double = 2.0,
    val maxRangeM: Double = 600.0,
    /** A vehicle slower than this counts as stopped. */
    val stoppedMps: Double = 1.5,
    /** Closing speed below this is not a closing vehicle. */
    val minClosingMps: Double = 1.0,
)

enum class FogThreat { SLOW, STOPPED }

/**
 * A vehicle ahead in this direction that this phone is closing on. [beyondSight] is true when the warning comes from further
 * away than the driver can see: the whole point of the mesh in fog. Longitudinal only; GPS cannot tell lanes apart.
 */
data class FogWarning(val threat: FogThreat, val aheadM: Int, val closingMps: Double, val ttcS: Double, val stopDistM: Int, val beyondSight: Boolean) {
    val speech get() = "Caution. ${if (threat == FogThreat.STOPPED) "Stopped" else "Slow"} vehicle ahead, ${(aheadM / 10.0).roundToInt() * 10} metres."
}

/** Pure rules over the peers a phone already knows about (their speed and distance ahead). Android-free. */
object FogWatch {
    /** Distance this phone needs to stop at [speedMps]: reaction time at full speed, then braking. */
    fun stopDistanceM(speedMps: Double, rules: FogRules = FogRules()) = speedMps * rules.reactionS + speedMps * speedMps / (2 * rules.brakeMps2)

    /** The nearest threatening peer, or null. A peer with an unknown speed is never a threat: that is not evidence. */
    fun evaluate(ownSpeedMps: Double?, peers: List<PicturePeer>, rules: FogRules = FogRules()): FogWarning? {
        val v = ownSpeedMps?.takeIf { it.isFinite() && it > rules.minClosingMps && it <= 100.0 } ?: return null
        val stop = stopDistanceM(v, rules)
        return peers.asSequence()
            .filter { it.aheadM > 0 && it.aheadM <= rules.maxRangeM && it.speedMps != null }
            .mapNotNull { p ->
                val closing = v - p.speedMps!!
                if (closing < rules.minClosingMps) return@mapNotNull null
                val ttc = p.aheadM / closing
                // Warn once the vehicle is within the time to contact, or within [FogRules.stopFactor] stopping distances.
                if (ttc > rules.ttcS && p.aheadM > stop * rules.stopFactor) return@mapNotNull null
                val threat = if (p.speedMps < rules.stoppedMps) FogThreat.STOPPED else FogThreat.SLOW
                FogWarning(threat, p.aheadM, closing, ttc, stop.roundToInt(), p.aheadM > rules.visibilityM)
            }
            .minByOrNull { it.aheadM }
    }
}
