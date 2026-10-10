package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.HazardKind

/** Something on the road worth reporting: a hazard for drivers behind or a violation for the authorities. */
data class RoadFinding(val kind: HazardKind, val atS: Double, val reason: String, val subject: SeenObject, val distanceM: Double?, val confidence: Double)

/**
 * Starting values for a phone on the windscreen. Like [HazardRules] they are first guesses checked on recorded clips,
 * not verified thresholds. [horizonY] is where the road meets the sky in the frame (fraction of height) and [cameraHeightM]
 * the phone's height above the road; together they give a road-surface object's distance from where it touches the road.
 */
data class WatchRules(
    val aspect: Double = 16.0 / 9.0,
    val focalW: Double = 0.65,
    val horizonY: Double = 0.45,
    val cameraHeightM: Double = 1.3,
    val potholeScore: Float = 0.4f,
    val potholeFrames: Int = 2,
    val potholeWindow: Int = 4,
    val helmetScore: Float = 0.4f,
    val helmetFrames: Int = 3,
    val tripleFrames: Int = 3,
    val violationWindow: Int = 5,
    val wrongWayMarginMps: Double = 4.0,
    val wrongWayRangeM: Double = 40.0,
    val redLightRangeM: Double = 50.0,
    val lightHousingM: Double = 0.9,
    val movingMps: Double = 3.0,
    val lyingRatio: Double = 1.3,
    val accidentFrames: Int = 4,
    val accidentWindow: Int = 6,
    val trackWindowS: Double = 0.8,
    val holdOffS: Map<HazardKind, Double> = mapOf(
        HazardKind.POTHOLE to 3.0, HazardKind.NO_HELMET to 6.0, HazardKind.TRIPLE_RIDING to 8.0,
        HazardKind.WRONG_WAY to 6.0, HazardKind.RED_LIGHT to 10.0, HazardKind.ACCIDENT to 15.0),
)

/**
 * Road-condition and violation rules over one forward camera, alongside [DashcamHazards]:
 * - Pothole: the road model's pothole box on the road surface ahead in [WatchRules.potholeFrames] of the last frames.
 * - No helmet: the road model's bare-head box on a two-wheeler's rider, or a confident one on its own.
 * - Triple riding: three or more people riding one two-wheeler.
 * - Wrong side: a vehicle in our lane closing faster than our own speed allows, so it is moving toward us.
 *   Needs our speed (GNSS, or the clip's known speed).
 * - Red light: a red light within range while a vehicle in our lane ahead keeps moving through.
 * - Accident: a person lying on the road, or a two-wheeler on its side with a person by it, held over several frames.
 * Each kind is raised once, then held off. Deterministic and Android-free.
 */
class RoadWatch(private val rules: WatchRules = WatchRules()) {
    private val geometry = DashcamHazards()
    private val tracker = ObjectTracker()
    private val history = HashMap<Int, ArrayDeque<Pair<Double, Double>>>()
    private val hits = HashMap<HazardKind, ArrayDeque<Boolean>>()
    private val lastRaised = HashMap<HazardKind, Double>()
    private val focalH get() = rules.focalW * rules.aspect

    fun reset() { tracker.reset(); history.clear(); hits.clear(); lastRaised.clear() }

    /** Metres to a point on the road surface at image height [y] (fraction of frame height). Null above the horizon. */
    fun groundM(y: Float): Double? = (y - rules.horizonY).takeIf { it > 0.02 }?.let { focalH * rules.cameraHeightM / it }

    fun update(tS: Double, objects: List<SeenObject>, egoSpeedMps: Double?): List<RoadFinding> {
        val out = mutableListOf<RoadFinding>()
        val bikes = objects.filter { it.cls == ObjectClass.MOTORCYCLE && it.score >= 0.3f }
        val people = objects.filter { it.cls == ObjectClass.PERSON && it.score >= 0.3f }

        // Pothole on the road surface ahead.
        val pothole = objects.filter { it.cls == ObjectClass.POTHOLE && it.score >= rules.potholeScore && it.cx in 0.1f..0.9f }
            .mapNotNull { p -> groundM(p.y2)?.let { p to it } }.minByOrNull { it.second }
        if (confirmed(HazardKind.POTHOLE, pothole != null, rules.potholeFrames, rules.potholeWindow) && pothole != null)
            raise(out, RoadFinding(HazardKind.POTHOLE, tS, "pothole on the road about ${pothole.second.toInt()} m ahead", pothole.first, pothole.second, pothole.first.score.toDouble()), tS)

        // Rider without a helmet.
        val bare = objects.filter { it.cls == ObjectClass.NO_HELMET && it.score >= rules.helmetScore }
            .firstOrNull { h -> bikes.any { onBike(h, it) } || h.score >= 0.55f }
        if (confirmed(HazardKind.NO_HELMET, bare != null, rules.helmetFrames, rules.violationWindow) && bare != null) {
            val bike = bikes.firstOrNull { onBike(bare, it) }
            raise(out, RoadFinding(HazardKind.NO_HELMET, tS, "two-wheeler rider without a helmet", bike ?: bare, bike?.let { geometry.distanceM(it) }, bare.score.toDouble()), tS)
        }

        // Three or more on one two-wheeler.
        val triple = bikes.map { b -> b to people.count { riding(it, b) } }.filter { it.second >= 3 }.maxByOrNull { it.second }
        if (confirmed(HazardKind.TRIPLE_RIDING, triple != null, rules.tripleFrames, rules.violationWindow) && triple != null)
            raise(out, RoadFinding(HazardKind.TRIPLE_RIDING, tS, "${triple.second} people riding one two-wheeler", triple.first, geometry.distanceM(triple.first), triple.first.score.toDouble()), tS)

        // Moving vehicles in our lane: closing speed from each track's distance history.
        val tracks = tracker.update(tS, objects.filter { it.cls.vehicle && it.score >= 0.35f })
        history.keys.retainAll(tracks.map { it.id }.toSet())
        val closing = tracks.mapNotNull { tr ->
            val d = geometry.distanceM(tr.subject) ?: return@mapNotNull null
            val h = history.getOrPut(tr.id) { ArrayDeque() }
            h.addLast(tS to d); while (h.isNotEmpty() && tS - h.first().first > rules.trackWindowS) h.removeFirst()
            if (!geometry.inLane(tr.subject) || h.size < 4) null else Triple(tr.subject, d, -slope(h))
        }
        val wrong = egoSpeedMps?.let { v -> closing.filter { (_, d, c) -> d < rules.wrongWayRangeM && c > v + rules.wrongWayMarginMps }.minByOrNull { it.second } }
        if (wrong != null) raise(out, RoadFinding(HazardKind.WRONG_WAY, tS,
            "${wrong.first.cls.name.lowercase()} in our lane coming toward us about ${wrong.second.toInt()} m ahead", wrong.first, wrong.second, wrong.first.score.toDouble()), tS)

        // A vehicle ahead moving on through a red light.
        val red = objects.filter { it.cls == ObjectClass.TRAFFIC_LIGHT && it.light == LightColour.RED && it.score >= 0.3f }
            .map { it to focalH * rules.lightHousingM / it.h.coerceAtLeast(1e-3f) }.filter { it.second <= rules.redLightRangeM }.minByOrNull { it.second }
        if (red != null) {
            val runner = closing.filter { (_, d, c) -> d <= red.second + 5 && (egoSpeedMps ?: 0.0) - c > rules.movingMps }.minByOrNull { it.second }
            if (runner != null) raise(out, RoadFinding(HazardKind.RED_LIGHT, tS,
                "${runner.first.cls.name.lowercase()} ahead moving through a red light about ${red.second.toInt()} m away", runner.first, runner.second, red.first.score.toDouble()), tS)
        }

        // Someone down on the road.
        val lying = people.filter { it.score >= 0.4f && it.y2 > 0.5f && it.cx in 0.15f..0.85f && it.w * rules.aspect > it.h * rules.lyingRatio }.maxByOrNull { it.score }
        val fallen = bikes.firstOrNull { b -> b.y2 > 0.55f && b.w * rules.aspect > b.h * 2.6 && people.any { p -> p.iou(b) > 0.05f } }
        val down = lying ?: fallen
        if (confirmed(HazardKind.ACCIDENT, down != null, rules.accidentFrames, rules.accidentWindow) && down != null)
            raise(out, RoadFinding(HazardKind.ACCIDENT, tS, if (down == lying) "a person lying on the road" else "a two-wheeler on its side with a person by it",
                down, groundM(down.y2), down.score.toDouble()), tS)
        return out
    }

    /** A bare-head box above the middle of a two-wheeler, where its rider's head would be. */
    private fun onBike(head: SeenObject, bike: SeenObject) =
        head.cx in (bike.x1 - 0.3f * bike.w)..(bike.x2 + 0.3f * bike.w) && head.y2 in (bike.y1 - 1.5f * bike.h)..(bike.y1 + 0.5f * bike.h)

    private fun riding(p: SeenObject, r: SeenObject) =
        p.iou(r) > 0.15f || (p.cx in r.x1..r.x2 && p.y2 in (r.y1 - 0.02f)..(r.y2 + 0.02f))

    private fun confirmed(kind: HazardKind, hit: Boolean, need: Int, window: Int): Boolean {
        val q = hits.getOrPut(kind) { ArrayDeque() }
        q.addLast(hit); while (q.size > window) q.removeFirst()
        return q.count { it } >= need
    }

    private fun raise(out: MutableList<RoadFinding>, f: RoadFinding, tS: Double) {
        val hold = rules.holdOffS[f.kind] ?: 5.0
        if (lastRaised[f.kind]?.let { tS - it < hold } == true) return
        lastRaised[f.kind] = tS; out += f
    }

    /** Least-squares slope of distance over time: negative when the object is getting closer. */
    private fun slope(h: ArrayDeque<Pair<Double, Double>>): Double {
        val n = h.size.toDouble(); val mt = h.sumOf { it.first } / n; val md = h.sumOf { it.second } / n
        val den = h.sumOf { (it.first - mt) * (it.first - mt) }
        return if (den < 1e-9) 0.0 else h.sumOf { (it.first - mt) * (it.second - md) } / den
    }
}

/**
 * The lit colour of a traffic light from the ARGB pixels inside its box, or null when no lamp is clearly lit.
 * Counts bright, saturated pixels by hue: red wraps around 0°, amber is near 40°, green near 140°.
 */
fun lightColour(argb: IntArray): LightColour? {
    var red = 0; var amber = 0; var green = 0
    for (p in argb) {
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        if (max < 150 || max - min < 70) continue
        val d = (max - min).toFloat()
        val hue = when (max) { r -> 60 * (((g - b) / d) % 6); g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }.let { if (it < 0) it + 360 else it }
        when { hue < 20 || hue > 330 -> red++; hue < 65 -> amber++; hue in 90.0..200.0 -> green++ }
    }
    val lit = maxOf(red, amber, green)
    if (lit < maxOf(4, argb.size / 60)) return null
    return when (lit) { red -> LightColour.RED; amber -> LightColour.AMBER; else -> LightColour.GREEN }
}
