package com.jsons.roadmesh.core.insight

import com.jsons.roadmesh.core.mesh.HazardDetail
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.core.mesh.VehicleType
import kotlin.math.roundToInt

/**
 * What one node concludes from a warning it received: the origin's inference (range from its camera to the hazard)
 * plus this node's own distance to the origin gives the hazard's distance from here, and with this node's speed, the
 * time until it gets there. Deterministic; the co-pilot only phrases it.
 */
data class NodeInference(val kind: HazardKind, val hazardAheadM: Int, val etaS: Double?, val action: String, val origin: HazardDetail?) {
    fun facts(fromLetter: Char, viaLetter: Char?) = buildString {
        append("${kind.title()} reported by phone $fromLetter")
        origin?.vehicle?.takeIf { it != VehicleType.UNKNOWN }?.let { append(" (a ${it.title})") }
        origin?.rangeM?.let { append(", whose camera saw it about $it m in front of it") }
        origin?.confidence?.let { append(" with ${(it * 100).roundToInt()}% detector confidence") }
        if (viaLetter != null && viaLetter != fromLetter) append(", relayed by phone $viaLetter")
        append(". From here the hazard is about $hazardAheadM m ahead")
        etaS?.let { append(", about ${it.roundToInt()} s away at the current speed") }
        append(". Recommended: $action.")
    }

    companion object {
        fun at(kind: HazardKind, detail: HazardDetail?, toOriginM: Double, ownSpeedMps: Double?): NodeInference {
            val ahead = (toOriginM + (detail?.rangeM ?: 0)).roundToInt()
            val eta = ownSpeedMps?.takeIf { it > 1.0 }?.let { ahead / it }
            val soon = ahead < 80 || eta != null && eta < 8
            val action = when (kind) {
                HazardKind.PEDESTRIAN -> if (soon) "slow down now and be ready to stop" else "ease off and watch for people on the road"
                HazardKind.BRAKING -> if (soon) "brake gently and open the gap to the car ahead" else "ease off early so you don't have to brake hard"
                HazardKind.VEHICLE_CLOSE -> if (soon) "hold your lane and leave room, a vehicle cut in ahead" else "expect a sudden lane change ahead"
                HazardKind.POTHOLE -> if (soon) "slow to about 20 km/h and watch the road surface" else "expect a pothole; slow down before you reach it"
                HazardKind.ACCIDENT -> if (soon) "slow down now, a lane may be blocked and people may be on the road" else "slow down early and be ready to change lanes"
                HazardKind.SLOWDOWN -> if (soon) "ease off now so you reach the queue after it moves" else "ease off early; a slowdown is moving back toward you"
                HazardKind.WRONG_WAY -> if (soon) "keep left and be ready to stop, a vehicle is coming the wrong way" else "keep left; a vehicle is on the wrong side ahead"
                HazardKind.TRIPLE_RIDING, HazardKind.NO_HELMET, HazardKind.RED_LIGHT -> "no action needed; it was logged for the traffic police"
            }
            return NodeInference(kind, ahead, eta, action, detail)
        }
    }
}

fun HazardKind.title() = when (this) {
    HazardKind.BRAKING -> "Braking ahead"
    HazardKind.PEDESTRIAN -> "Pedestrian ahead"
    HazardKind.VEHICLE_CLOSE -> "Vehicle close ahead"
    HazardKind.POTHOLE -> "Pothole"
    HazardKind.ACCIDENT -> "Accident"
    HazardKind.SLOWDOWN -> "Traffic slowdown"
    HazardKind.WRONG_WAY -> "Wrong-side vehicle"
    HazardKind.TRIPLE_RIDING -> "Triple riding"
    HazardKind.NO_HELMET -> "Rider without helmet"
    HazardKind.RED_LIGHT -> "Red light jumped"
}
