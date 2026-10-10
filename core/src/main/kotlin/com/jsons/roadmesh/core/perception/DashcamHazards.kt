package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.HazardKind
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class ObjectClass(val widthM: Double?) {
    PERSON(null), BICYCLE(0.6), CAR(1.75), MOTORCYCLE(0.8), BUS(2.5), TRUCK(2.5), TRAFFIC_LIGHT(null),
    /** From the RESILANE road model, not COCO. */
    POTHOLE(null), HELMET(null), NO_HELMET(null), OTHER(null);

    val vehicle get() = this == CAR || this == BUS || this == TRUCK || this == MOTORCYCLE
    val rideable get() = this == BICYCLE || this == MOTORCYCLE

    companion object {
        /** COCO ids as the YOLO head numbers them. */
        fun coco(id: Int) = when (id) { 0 -> PERSON; 1 -> BICYCLE; 2 -> CAR; 3 -> MOTORCYCLE; 5 -> BUS; 7 -> TRUCK; 9 -> TRAFFIC_LIGHT; else -> OTHER }
        /** Class ids of the RESILANE road model (tools/kaggle/road_yolo): pothole, helmet, no_helmet. */
        fun road(id: Int) = when (id) { 0 -> POTHOLE; 1 -> HELMET; 2 -> NO_HELMET; else -> OTHER }
    }
}

/** A traffic light's lit colour, read from the pixels inside its box. */
enum class LightColour { RED, AMBER, GREEN }

/** One detection in normalized frame coordinates: (0,0) top-left, (1,1) bottom-right. */
data class SeenObject(val cls: ObjectClass, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val light: LightColour? = null) {
    val w get() = x2 - x1
    val h get() = y2 - y1
    val cx get() = (x1 + x2) / 2
    fun iou(o: SeenObject): Float {
        val iw = min(x2, o.x2) - max(x1, o.x1); val ih = min(y2, o.y2) - max(y1, o.y1)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        return inter / (w * h + o.w * o.h - inter)
    }
}

/**
 * Starting values for a forward-facing phone, checked against two CC0 Bangalore dashcam clips. They are not verified
 * road thresholds. Geometry uses a pinhole camera looking straight ahead: an object's size gives its distance, and its
 * offset from the image centre in its own widths gives its lateral position. This does not need the horizon, so it
 * tolerates the camera pitch of a phone mount. [focalW] is the focal length in frame widths (0.65 is about 75° HFOV).
 */
data class HazardRules(
    val aspect: Double = 16.0 / 9.0,
    val focalW: Double = 0.65,
    val personHeightM: Double = 1.65,
    val minScore: Float = 0.35f,
    val riderScore: Float = 0.2f,
    val minGroundY: Float = 0.35f,
    val laneHalfWidthM: Double = 1.6,
    val pedestrianHalfWidthM: Double = 2.0,
    val pedestrianRangeM: Double = 18.0,
    val pedestrianFrames: Int = 3,
    val pedestrianWindow: Int = 5,
    val closeVehicleM: Double = 6.0,
    val closingRangeM: Double = 25.0,
    val ttcS: Double = 2.0,
    val trackWindowS: Double = 0.8,
    val minTrackSamples: Int = 4,
    val holdOffS: Double = 4.0,
) {
    fun validate() = apply {
        require(aspect in 0.4..3.0 && focalW in 0.2..3.0 && personHeightM in 1.0..2.2) { "camera" }
        require(laneHalfWidthM in 0.5..4.0 && pedestrianHalfWidthM in 0.5..5.0 && pedestrianRangeM in 2.0..100.0) { "lane" }
        require(pedestrianFrames in 1..pedestrianWindow && pedestrianWindow <= 30) { "pedestrian frames" }
        require(closeVehicleM in 1.0..30.0 && closingRangeM >= closeVehicleM && ttcS in 0.3..10.0) { "vehicle" }
        require(trackWindowS in 0.2..3.0 && minTrackSamples >= 3 && holdOffS >= 0) { "tracking" }
    }
}

/** Why a hazard was raised, for logs, the screen and the co-pilot prompt. Never sent over the radio. */
data class HazardCall(val kind: HazardKind, val atS: Double, val reason: String, val subject: SeenObject, val distanceM: Double)

/** The vehicle the camera is following, if any: distance, lateral offset and time to contact from box growth. */
data class Lead(val subject: SeenObject, val distanceM: Double, val lateralM: Double, val ttcS: Double?)

/**
 * Turns per-frame YOLO detections from a forward camera into at most one hazard per frame. Deterministic and
 * Android-free. Rules, in priority order:
 * - Pedestrian: a person on foot within [HazardRules.pedestrianHalfWidthM] of the lane centre and
 *   [HazardRules.pedestrianRangeM], in [HazardRules.pedestrianFrames] of the last [HazardRules.pedestrianWindow] frames.
 *   A person on a two-wheeler is its rider, not a pedestrian.
 * - Vehicle close: a vehicle in the lane nearer than [HazardRules.closeVehicleM] (a cut-in or a tailgate).
 * - Braking ahead: the lead vehicle's box grows fast enough that time to contact is under [HazardRules.ttcS].
 * Each kind is raised once, then held off for [HazardRules.holdOffS].
 */
class DashcamHazards(private val rules: HazardRules = HazardRules()) {
    private val pedestrianHits = ArrayDeque<Boolean>()
    private val track = ArrayDeque<Pair<Double, SeenObject>>()
    private val lastRaised = HashMap<HazardKind, Double>()
    var lead: Lead? = null; private set

    init { rules.validate() }

    fun reset() { pedestrianHits.clear(); track.clear(); lastRaised.clear(); lead = null }

    /** Metres from the camera, from the object's known size. Null for classes without one. */
    fun distanceM(o: SeenObject): Double? = when {
        o.cls == ObjectClass.PERSON -> rules.focalW * rules.aspect * rules.personHeightM / o.h.coerceAtLeast(1e-3f)
        o.cls.widthM != null -> rules.focalW * o.cls.widthM / o.w.coerceAtLeast(1e-3f)
        else -> null
    }

    /** Metres right (+) or left (-) of the camera axis. */
    fun lateralM(o: SeenObject): Double? = when {
        o.cls == ObjectClass.PERSON -> (o.cx - 0.5) * rules.aspect * rules.personHeightM / o.h.coerceAtLeast(1e-3f)
        o.cls.widthM != null -> (o.cx - 0.5) * o.cls.widthM / o.w.coerceAtLeast(1e-3f)
        else -> null
    }

    /** True when [o] stands in or next to the camera's lane closely enough to matter to the rules. */
    fun inLane(o: SeenObject): Boolean {
        val lateral = lateralM(o) ?: return false
        return o.y2 > rules.minGroundY && abs(lateral) <= if (o.cls == ObjectClass.PERSON) rules.pedestrianHalfWidthM else rules.laneHalfWidthM
    }

    fun update(tS: Double, detections: List<SeenObject>): HazardCall? {
        val objects = detections.filter { it.score >= rules.minScore }
        val rideables = detections.filter { it.cls.rideable && it.score >= rules.riderScore }
        val pedestrian = objects.filter { it.cls == ObjectClass.PERSON && inLane(it) && distanceM(it)!! <= rules.pedestrianRangeM }
            .filter { p -> rideables.none { r -> riding(p, r) } }
            .minByOrNull { distanceM(it)!! }
        pedestrianHits.addLast(pedestrian != null)
        while (pedestrianHits.size > rules.pedestrianWindow) pedestrianHits.removeFirst()

        val nearest = objects.filter { it.cls.vehicle && inLane(it) }.minByOrNull { distanceM(it)!! }
        follow(tS, nearest)
        val ttc = timeToContact()
        val nearestM = nearest?.let { distanceM(it)!! }
        lead = nearest?.let { Lead(it, nearestM!!, lateralM(it)!!, ttc) }

        if (pedestrian != null && pedestrianHits.count { it } >= rules.pedestrianFrames && ready(HazardKind.PEDESTRIAN, tS)) {
            val d = distanceM(pedestrian)!!
            return raise(HazardCall(HazardKind.PEDESTRIAN, tS, "person on foot in the lane about ${d.toInt()} m ahead", pedestrian, d))
        }
        if (nearest != null && nearestM!! < rules.closeVehicleM && ready(HazardKind.VEHICLE_CLOSE, tS))
            return raise(HazardCall(HazardKind.VEHICLE_CLOSE, tS, "${name(nearest)} in the lane only ${"%.1f".format(nearestM)} m ahead", nearest, nearestM))
        if (nearest != null && ttc != null && ttc < rules.ttcS && nearestM!! <= rules.closingRangeM && ready(HazardKind.BRAKING, tS))
            return raise(HazardCall(HazardKind.BRAKING, tS, "closing on the ${name(nearest)} ahead, contact in ${"%.1f".format(ttc)} s", nearest, nearestM))
        return null
    }

    /** A person whose feet are inside a two-wheeler's box, or who overlaps it heavily, is its rider. */
    private fun riding(p: SeenObject, r: SeenObject) =
        p.iou(r) > 0.15f || (p.cx in r.x1..r.x2 && p.y2 in (r.y1 - 0.02f)..(r.y2 + 0.02f))

    private fun follow(tS: Double, nearest: SeenObject?) {
        val last = track.lastOrNull()?.second
        if (nearest == null || last != null && (last.cls != nearest.cls || last.iou(nearest) < 0.3f)) track.clear()
        if (nearest != null) track.addLast(tS to nearest)
        while (track.isNotEmpty() && tS - track.first().first > rules.trackWindowS) track.removeFirst()
    }

    /** Least-squares slope of ln(height) over time is the expansion rate; its inverse is time to contact. */
    private fun timeToContact(): Double? {
        if (track.size < rules.minTrackSamples) return null
        val n = track.size.toDouble()
        val mt = track.sumOf { it.first } / n
        val ml = track.sumOf { ln(it.second.h.toDouble()) } / n
        val num = track.sumOf { (it.first - mt) * (ln(it.second.h.toDouble()) - ml) }
        val den = track.sumOf { (it.first - mt) * (it.first - mt) }
        if (den <= 1e-9) return null
        val rate = num / den
        return if (rate > 1e-3) 1 / rate else null
    }

    private fun name(o: SeenObject) = o.cls.name.lowercase()
    private fun ready(kind: HazardKind, tS: Double) = lastRaised[kind]?.let { tS - it >= rules.holdOffS } ?: true
    private fun raise(call: HazardCall) = call.also { lastRaised[it.kind] = it.atS }
}
