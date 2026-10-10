package com.jsons.roadmesh.core.perception

import com.jsons.roadmesh.core.mesh.HazardKind
import kotlin.math.abs
import kotlin.math.ln

/** A sensor measurement, not a speed repeated with each camera frame. [atS] uses the frame's monotonic clock. */
data class EgoMotion(
    val atS: Double,
    val speedMps: Double,
    val speedAccuracyMps: Double? = null,
    val headingDeg: Double? = null,
    val headingAccuracyDeg: Double? = null,
    val cameraStable: Boolean = true,
)

/** Local track ids are short-lived visual associations, never licence plates or cross-phone identities. */
data class AutomaticTrafficEvent(
    val kind: HazardKind,
    val atS: Double,
    val reason: String,
    val trackIds: List<Int>,
    /** Minimum YOLO score among supporting detections; not a calibrated probability of an incident. */
    val detectorConfidence: Double,
    val estimatedRangeM: Double?,
    /** Optical expansion estimate under a constant-size/heading assumption, not a collision prediction. */
    val estimatedTtcS: Double?,
    val evidence: List<String>,
    val subject: SeenObject? = null,
)

data class AutomatedTrafficFrame(
    val tracks: List<Track>,
    val events: List<AutomaticTrafficEvent>,
    val status: String,
    val monitoredVehicles: Int,
)

data class AutomatedTrafficConfig(
    val maxTracks: Int = 48,
    val maxInputDetections: Int = 256,
    val maxSamplesPerTrack: Int = 32,
    val trackGapS: Double = 1.0,
    val historyS: Double = 2.5,
    val minimumObservationS: Double = 0.6,
    val minimumSamples: Int = 4,
    val cooldownS: Double = 8.0,
    val maximumEgoAgeS: Double = 1.5,
    val maximumSpeedAccuracyMps: Double = 1.0,
    val hardDecelerationMps2: Double = 3.5,
    val slowdownDecelerationMps2: Double = 1.5,
    val minimumSpeedDropMps: Double = 2.5,
    val maximumHeadingChangeDeg: Double = 12.0,
    val maximumLateralRate: Double = 0.12,
) {
    init {
        require(maxTracks in 2..128 && maxInputDetections in maxTracks..1_024)
        require(maxSamplesPerTrack in 8..128 && minimumSamples in 3..maxSamplesPerTrack)
        require(trackGapS in 0.1..2.0 && historyS in 1.0..5.0)
        require(minimumObservationS in 0.3..historyS && cooldownS in 1.0..60.0)
        require(maximumEgoAgeS in 0.1..3.0 && maximumSpeedAccuracyMps in 0.1..2.0)
        require(slowdownDecelerationMps2 in 0.5..5.0 && hardDecelerationMps2 in slowdownDecelerationMps2..10.0)
        require(minimumSpeedDropMps in 1.0..10.0 && maximumHeadingChangeDeg in 2.0..45.0)
        require(maximumLateralRate in 0.01..0.5)
    }
}

/**
 * Bounded on-device event extraction from successive YOLO boxes and optional GNSS speed measurements.
 *
 * A growing box supports relative approach, not another driver's braking or absolute velocity. BRAKING therefore
 * requires this phone's measured deceleration, with speed uncertainty subtracted from the drop. SLOWDOWN requires
 * that speed evidence plus at least two persistent vehicles whose relative gaps are not opening. All camera ranges
 * remain size-prior estimates. This model neither identifies a safe passage nor commands a vehicle.
 *
 * Run on one analysis executor. No network, Android API, language model or driver interaction is required.
 */
class AutomatedTrafficAnalyzer(
    private val config: AutomatedTrafficConfig = AutomatedTrafficConfig(),
    geometry: HazardRules = HazardRules(),
) {
    private data class Sample(val t: Double, val obj: SeenObject)
    private class Tracked(val id: Int, val born: Double, val samples: ArrayDeque<Sample>) {
        val last get() = samples.last()
    }
    private data class Trend(val growth: Double, val lateralRate: Double, val consistent: Boolean)
    private data class SpeedDrop(val measured: Double, val conservative: Double, val duration: Double) {
        val conservativeDeceleration get() = conservative / duration
    }
    private val camera = DashcamHazards(geometry)
    private val geometry = geometry.validate()
    private val live = ArrayList<Tracked>()
    private val egoHistory = ArrayDeque<EgoMotion>()
    private val raised = HashMap<HazardKind, Double>()
    private var lastFrameS: Double? = null
    private var nextTrackId = 1
    private var instabilityUntilS = Double.NEGATIVE_INFINITY

    /** Clears inference history. The next frame must earn persistence again; ids are not recycled by reset. */
    fun reset() {
        live.clear()
        egoHistory.clear()
        raised.clear()
        lastFrameS = null
        instabilityUntilS = Double.NEGATIVE_INFINITY
    }

    fun update(tS: Double, detections: List<SeenObject>, ego: EgoMotion? = null): AutomatedTrafficFrame {
        if (!tS.isFinite() || tS < 0.0) {
            reset()
            return empty("Unknown: invalid camera time")
        }
        val previous = lastFrameS
        if (previous != null && tS == previous) return empty("Waiting for a new camera frame")
        if (previous != null && (tS < previous || tS - previous > config.trackGapS)) reset()
        lastFrameS = tS
        if (detections.size > config.maxInputDetections) {
            live.clear()
            egoHistory.clear()
            return empty("Unknown: detection capacity exceeded")
        }
        val objects = cleanDetections(detections)
        live.removeAll { tS - it.last.t > config.trackGapS }
        val current = associate(tS, objects)
        val tracks = current.map { tracked ->
            val first = tracked.samples.first()
            val last = tracked.last
            val dt = last.t - first.t
            Track(tracked.id, last.obj,
                if (dt > 0) ((last.obj.cx - first.obj.cx) / dt).toFloat() else 0f,
                if (dt > 0) ((last.obj.y2 - first.obj.y2) / dt).toFloat() else 0f,
                tS - tracked.born)
        }
        recordEgo(tS, ego)
        val vehicles = current.filter { isVehicle(it.last.obj) && relevant(it.last.obj) }
        if (ego?.cameraStable == false) instabilityUntilS = tS + config.minimumObservationS
        val turning = turning(tS)
        if (turning) instabilityUntilS = tS + config.minimumObservationS
        if (tS <= instabilityUntilS) {
            // Do not let a turn or mount movement become persistence for the first stable frame afterwards.
            current.forEach { val last = it.last; it.samples.clear(); it.samples.addLast(last) }
            return AutomatedTrafficFrame(tracks, emptyList(), "Paused: camera movement or turning", vehicles.size)
        }
        if (objects.isEmpty()) return AutomatedTrafficFrame(tracks, emptyList(), "Unknown: no current detections", 0)

        val persistent = current.filter { persistent(it) }
        val vehicleEvidence = persistent.filter { isVehicle(it.last.obj) && relevant(it.last.obj) }
        val traffic = vehicleEvidence.filter {
            val trend = trend(it)
            trend != null && trend.consistent && trend.growth >= -0.05 && abs(trend.lateralRate) <= config.maximumLateralRate
        }
        val speed = speedDrop(tS, ego)
        val candidates = ArrayList<AutomaticTrafficEvent>()
        val pedestrians = persistent.filter { it.last.obj.cls == ObjectClass.PERSON && relevant(it.last.obj) }
            .filter { p -> objects.none { it.cls.rideable && riding(p.last.obj, it) } }
            .filter { camera.distanceM(it.last.obj)?.let { d -> d <= geometry.pedestrianRangeM } == true }
        pedestrians.minByOrNull { camera.distanceM(it.last.obj)!! }?.let { p ->
            candidates += event(HazardKind.PEDESTRIAN, tS, listOf(p),
                "Person on foot persists in the forward travel corridor; range is estimated",
                listOf("Same local track in ${p.samples.size} frames", "Person not associated with a visible two-wheeler"))
        }

        if (speed != null && speed.conservative >= config.minimumSpeedDropMps && traffic.isNotEmpty()) {
            if (speed.conservativeDeceleration >= config.hardDecelerationMps2) {
                candidates += event(HazardKind.BRAKING, tS, traffic,
                    "This vehicle is decelerating sharply with traffic visible ahead",
                    listOf("Measured host speed drop ${format(speed.measured)} m/s over ${format(speed.duration)} s",
                        "Conservative deceleration ${format(speed.conservativeDeceleration)} m/s² after speed uncertainty",
                        "${traffic.size} persistent vehicle tracks; target braking is not directly measured"), rangeAtHost = true)
            } else if (speed.conservativeDeceleration >= config.slowdownDecelerationMps2 && traffic.size >= 2) {
                candidates += event(HazardKind.SLOWDOWN, tS, traffic,
                    "Possible collective slowdown: host speed falls while multiple forward vehicle gaps do not open",
                    listOf("${traffic.size} distinct persistent local vehicle tracks",
                        "Measured host speed drop ${format(speed.measured)} m/s over ${format(speed.duration)} s",
                        "Relative box histories corroborate the slowdown; target speeds remain unknown"), rangeAtHost = true)
            }
        }

        val approaching = vehicleEvidence.mapNotNull { track ->
            val trend = trend(track) ?: return@mapNotNull null
            val range = camera.distanceM(track.last.obj) ?: return@mapNotNull null
            if (!trend.consistent || abs(trend.lateralRate) > config.maximumLateralRate) return@mapNotNull null
            val ttc = if (trend.growth > 0.01) 1.0 / trend.growth else null
            if (range <= geometry.closeVehicleM || (ttc != null && ttc < geometry.ttcS && range <= geometry.closingRangeM)) track to ttc else null
        }.minByOrNull { camera.distanceM(it.first.last.obj)!! }
        approaching?.let { (track, ttc) ->
            candidates += event(HazardKind.VEHICLE_CLOSE, tS, listOf(track),
                if (ttc != null && ttc < geometry.ttcS) "Rapid relative approach to a tracked vehicle; cause unknown"
                else "Tracked vehicle is close in the forward corridor; range is estimated",
                listOf("${track.samples.size} frames support one local track",
                    "Box expansion cannot distinguish target braking from host acceleration"), ttc = ttc)
        }

        // Road-model obstacles may be shared automatically after persistence, without inventing their size.
        persistent.firstOrNull { it.last.obj.cls == ObjectClass.POTHOLE && it.last.obj.y2 >= 0.55f && it.last.obj.cx in 0.25f..0.75f }?.let { p ->
            candidates += event(HazardKind.POTHOLE, tS, listOf(p), "Road model repeatedly detects a possible pothole ahead",
                listOf("Same road-model detection across ${p.samples.size} frames", "Obstacle dimensions and distance are unknown"))
        }
        val events = candidates.filter { c -> raised[c.kind]?.let { tS - it < config.cooldownS } != true }
            .take(2).onEach { raised[it.kind] = tS }
        val state = when {
            events.isNotEmpty() -> "Automatic event: ${events.first().kind.name.lowercase().replace('_', ' ')}"
            speed == null -> "Tracking automatically; reliable speed history unavailable"
            persistent.isEmpty() -> "Building temporal evidence"
            else -> "Tracking automatically; no new supported event"
        }
        return AutomatedTrafficFrame(tracks, events, state, vehicles.size)
    }

    private fun empty(status: String) = AutomatedTrafficFrame(emptyList(), emptyList(), status, 0)
    private fun isVehicle(o: SeenObject) = o.cls.vehicle || o.cls == ObjectClass.BICYCLE
    private fun relevant(o: SeenObject) = camera.inLane(o)
    private fun riding(p: SeenObject, r: SeenObject) = p.iou(r) > 0.15f ||
        (p.cx in r.x1..r.x2 && p.y2 in (r.y1 - 0.02f)..(r.y2 + 0.02f))

    private fun cleanDetections(input: List<SeenObject>): List<SeenObject> {
        val result = ArrayList<SeenObject>()
        for (o in input.filter { o ->
            o.score.isFinite() && o.score in geometry.minScore..1f &&
                listOf(o.x1, o.y1, o.x2, o.y2).all { it.isFinite() && it in 0f..1f } && o.w >= 0.005f && o.h >= 0.005f
        }.sortedByDescending { it.score }) {
            // Competing car/bus labels on the same box must not manufacture two traffic witnesses.
            if (result.none { (it.cls == o.cls || isVehicle(it) && isVehicle(o)) && it.iou(o) > 0.65f }) result += o
            if (result.size == config.maxTracks) break
        }
        return result
    }

    private fun associate(tS: Double, objects: List<SeenObject>): List<Tracked> {
        val free = live.toMutableList()
        val current = ArrayList<Tracked>()
        for (obj in objects) {
            val match = free.filter { it.last.obj.cls == obj.cls }.maxByOrNull { it.last.obj.iou(obj) }
                ?.takeIf { it.last.obj.iou(obj) >= 0.3f }
            val track = if (match != null) {
                free.remove(match)
                // Identity may survive a brief occlusion within trackGapS; longer gaps have expired above.
                match.samples.addLast(Sample(tS, obj))
                match
            } else {
                if (live.size >= config.maxTracks) {
                    val oldestMissing = free.minByOrNull { it.last.t } ?: continue
                    free.remove(oldestMissing)
                    live.remove(oldestMissing)
                }
                if (nextTrackId == Int.MAX_VALUE) nextTrackId = 1
                Tracked(nextTrackId++, tS, ArrayDeque<Sample>().apply { addLast(Sample(tS, obj)) }).also { live += it }
            }
            while (track.samples.size > config.maxSamplesPerTrack || tS - track.samples.first().t > config.historyS) track.samples.removeFirst()
            current += track
        }
        return current
    }

    private fun persistent(t: Tracked) = t.samples.size >= config.minimumSamples &&
        t.last.t - t.samples.first().t >= config.minimumObservationS

    private fun trend(track: Tracked): Trend? {
        if (!persistent(track)) return null
        val samples = track.samples.toList()
        val ts = samples.map { it.t }
        val logs = samples.map { ln(it.obj.h.toDouble()) }
        val growth = slope(ts, logs) ?: return null
        val lateral = slope(ts, samples.map { it.obj.cx.toDouble() }) ?: return null
        val meanT = ts.average()
        val meanH = logs.average()
        val rms = kotlin.math.sqrt(logs.indices.sumOf { i ->
            val residual = logs[i] - (meanH + growth * (ts[i] - meanT)); residual * residual
        } / logs.size)
        // Width and height should broadly scale together, not alternate because of truncation or detector jitter.
        val widthGrowth = slope(ts, samples.map { ln(it.obj.w.toDouble()) }) ?: return null
        return Trend(growth, lateral, rms <= 0.12 && abs(widthGrowth - growth) <= 0.35)
    }

    private fun recordEgo(tS: Double, ego: EgoMotion?) {
        while (egoHistory.isNotEmpty() && tS - egoHistory.first().atS > config.historyS) egoHistory.removeFirst()
        if (ego == null || !ego.atS.isFinite() || ego.atS < 0 || tS - ego.atS !in 0.0..config.maximumEgoAgeS ||
            !ego.speedMps.isFinite() || ego.speedMps !in 0.0..80.0) return
        if (egoHistory.lastOrNull()?.atS?.let { ego.atS <= it } == true) return
        egoHistory.addLast(ego)
        while (egoHistory.size > config.maxSamplesPerTrack) egoHistory.removeFirst()
    }

    private fun turning(tS: Double): Boolean {
        val bearings = egoHistory.filter { tS - it.atS <= config.historyS && it.headingDeg?.isFinite() == true &&
            it.headingAccuracyDeg?.let { acc -> acc.isFinite() && acc in 0.0..20.0 } == true }
        if (bearings.size < 2) return false
        val origin = bearings.first().headingDeg!!
        return bearings.any { abs(((it.headingDeg!! - origin + 540.0) % 360.0) - 180.0) > config.maximumHeadingChangeDeg }
    }

    private fun speedDrop(tS: Double, latest: EgoMotion?): SpeedDrop? {
        if (latest == null || tS - latest.atS !in 0.0..config.maximumEgoAgeS) return null
        val valid = egoHistory.filter { e -> e.speedAccuracyMps?.let { it.isFinite() && it in 0.0..config.maximumSpeedAccuracyMps } == true }
        if (valid.size < 3 || valid.last().atS != latest.atS) return null
        val first = valid.first()
        val last = valid.last()
        val duration = last.atS - first.atS
        if (duration < config.minimumObservationS) return null
        // Distinct GNSS samples, not copies of one sample stamped with each camera frame.
        if (valid.zipWithNext().any { (a, b) -> b.atS - a.atS > config.maximumEgoAgeS }) return null
        val measured = first.speedMps - last.speedMps
        val conservative = measured - first.speedAccuracyMps!! - last.speedAccuracyMps!!
        // A single GNSS speed outlier cannot become measured braking just because the camera has steady tracks.
        // Two separated drops must exceed their endpoint error budgets. This costs latency at a 1 Hz GNSS rate;
        // optical relative-approach events remain available independently while the speed evidence accumulates.
        val midpoint = (first.atS + last.atS) / 2.0
        val middle = valid.subList(1, valid.lastIndex).minByOrNull { abs(it.atS - midpoint) } ?: return null
        if (first.speedMps - middle.speedMps <= first.speedAccuracyMps!! + middle.speedAccuracyMps!! ||
            middle.speedMps - last.speedMps <= middle.speedAccuracyMps!! + last.speedAccuracyMps!!) return null
        return SpeedDrop(measured, conservative, duration)
    }

    private fun event(kind: HazardKind, tS: Double, supporting: List<Tracked>, reason: String,
        evidence: List<String>, ttc: Double? = null, rangeAtHost: Boolean = false): AutomaticTrafficEvent {
        val subject = supporting.minByOrNull { camera.distanceM(it.last.obj) ?: Double.POSITIVE_INFINITY }!!.last.obj
        return AutomaticTrafficEvent(kind, tS, reason, supporting.map { it.id },
            supporting.minOf { it.samples.minOf { sample -> sample.obj.score.toDouble() } },
            if (rangeAtHost) null else camera.distanceM(subject), ttc, evidence, subject)
    }

    private fun slope(times: List<Double>, values: List<Double>): Double? {
        val mt = times.average()
        val mv = values.average()
        val denominator = times.sumOf { (it - mt) * (it - mt) }
        if (denominator <= 1e-9) return null
        return times.indices.sumOf { (times[it] - mt) * (values[it] - mv) } / denominator
    }

    private fun format(n: Double) = java.lang.String.format(java.util.Locale.ROOT, "%.1f", n)
}
