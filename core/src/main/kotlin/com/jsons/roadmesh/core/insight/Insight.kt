package com.jsons.roadmesh.core.insight

import com.jsons.roadmesh.core.mesh.HazardDetail
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.core.mesh.VehicleType
import com.jsons.roadmesh.core.perception.DashcamHazards
import com.jsons.roadmesh.core.perception.Lead
import com.jsons.roadmesh.core.perception.ObjectClass
import com.jsons.roadmesh.core.perception.SeenObject
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
                HazardKind.BRAKING -> if (soon) "brake gently and open the gap to the car ahead" else "ease off, traffic is braking ahead"
                HazardKind.VEHICLE_CLOSE -> if (soon) "hold your lane and leave room, a vehicle cut in ahead" else "expect a sudden lane change ahead"
            }
            return NodeInference(kind, ahead, eta, action, detail)
        }
    }
}

fun HazardKind.title() = when (this) {
    HazardKind.BRAKING -> "Braking ahead"
    HazardKind.PEDESTRIAN -> "Pedestrian ahead"
    HazardKind.VEHICLE_CLOSE -> "Vehicle close ahead"
}

/** A mesh warning this phone currently holds, with its distance from here. */
data class ActiveWarning(val kind: HazardKind, val aheadM: Int)

data class OvertakeAnswer(val notNow: Boolean, val reasons: List<String>, val caveat: String) {
    /** The deterministic verdict in words. The co-pilot may rephrase it but must not change it. */
    val verdict get() = if (notNow) "Not now: ${reasons.joinToString("; ")}." else "Nothing the camera sees argues against it. $caveat"
}

/**
 * Answers "is it safe to overtake now?" from what this phone's front camera sees. Left-hand traffic: overtaking is on
 * the right, into the lane that may carry oncoming traffic. It never says an overtake is safe; at best it reports that
 * nothing it can see argues against one, and names what it cannot see.
 */
class OvertakeAdvisor(private val geometry: DashcamHazards = DashcamHazards(), private val rightLane: ClosedFloatingPointRange<Double> = 1.6..6.5,
                      private val lookM: Double = 80.0, private val minTtcS: Double = 3.0, private val warningM: Int = 400) {
    fun assess(cameraLive: Boolean, objects: List<SeenObject>, lead: Lead?, warnings: List<ActiveWarning>, vehicle: VehicleType): OvertakeAnswer {
        val reasons = mutableListOf<String>()
        if (!cameraLive) reasons += "this phone's front camera is not running, so I cannot see the road ahead"
        warnings.filter { it.aheadM <= warningM }.minByOrNull { it.aheadM }?.let { reasons += "the mesh reports ${it.kind.title().lowercase()} about ${it.aheadM} m ahead" }
        val seen = objects.filter { it.score >= 0.35f }
        seen.filter { it.cls == ObjectClass.PERSON && geometry.inLane(it) && geometry.distanceM(it)!! < 40 }.minByOrNull { geometry.distanceM(it)!! }
            ?.let { reasons += "a person is on the road about ${geometry.distanceM(it)!!.roundToInt()} m ahead" }
        seen.filter { it.cls.vehicle }.mapNotNull { o -> geometry.lateralM(o)?.let { lat -> Triple(o, lat, geometry.distanceM(o)!!) } }
            .filter { (_, lat, d) -> lat in rightLane && d < lookM }.minByOrNull { it.third }
            ?.let { (o, _, d) -> reasons += "a ${o.cls.name.lowercase()} is in the right lane about ${d.roundToInt()} m ahead" }
        lead?.ttcS?.takeIf { it < minTtcS }?.let { reasons += "you are closing fast on the ${lead.subject.cls.name.lowercase()} ahead" }
        val target = lead?.let { " The ${it.subject.cls.name.lowercase()} ahead is about ${it.distanceM.roundToInt()} m away." } ?: " There is no vehicle close ahead to overtake."
        val margin = if (vehicle == VehicleType.BUS_TRUCK) " A long vehicle needs a much longer clear stretch." else ""
        return OvertakeAnswer(reasons.isNotEmpty(), reasons,
            "The camera sees no vehicle in the right lane for about ${lookM.roundToInt()} m.$target It cannot see behind you, your blind spot or past a bend, so check your mirrors and the road yourself before you pull out.$margin")
    }
}
