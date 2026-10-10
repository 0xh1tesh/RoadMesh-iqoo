package com.jsons.roadmesh.core.swarm

import com.jsons.roadmesh.core.mesh.GeoFix
import kotlin.math.*

/** All times use the receiving phone's monotonic clock. Positive infinity means accuracy was not supplied. */
data class SwarmParticipant(
    val id: String,
    val fix: GeoFix?,
    val observedAtMs: Long,
    val positionAccuracyM: Double = Double.POSITIVE_INFINITY,
    val headingAccuracyDeg: Double = Double.POSITIVE_INFINITY,
)

enum class ObservationKind { OBSTRUCTION, QUEUE_TAIL, PEDESTRIAN, WRONG_WAY, ROAD_HAZARD }

/**
 * [eventId] and [sourceId] identify the ORIGINAL observation, not its relays. Re-forwarding must preserve
 * [observedAtMs]. Confidence is a detector score, not a calibrated probability that an action is safe.
 */
data class SwarmObservation(
    val eventId: String,
    val sourceId: String,
    val kind: ObservationKind,
    val fix: GeoFix,
    val observedAtMs: Long,
    val ttlMs: Long = 15_000,
    val confidence: Double? = null,
    val positionAccuracyM: Double = Double.POSITIVE_INFINITY,
)

enum class DirectionRelation { SAME_DIRECTION, OPPOSING, CROSSING, UNCERTAIN, UNLOCATED, STALE, OUT_OF_RANGE }
enum class SwarmStatus { CAUTION, UNKNOWN }

data class SwarmNeighbour(
    val id: String,
    val relation: DirectionRelation,
    val distanceM: Double?,
    val aheadM: Double?,
    val reason: String,
)

data class SwarmAssessment(
    val status: SwarmStatus,
    val title: String,
    val advice: String,
    val evidence: List<SwarmObservation>,
    val independentSources: Int,
    val oldestAgeMs: Long?,
    val nominatedObserverId: String?,
    val neighbours: List<SwarmNeighbour>,
)

data class SwarmConfig(
    val participantLifetimeMs: Long = 8_000,
    val maximumEvidenceLifetimeMs: Long = 30_000,
    val neighbourhoodM: Double = 250.0,
    val corridorHalfWidthM: Double = 35.0,
    val maximumPositionAccuracyM: Double = 40.0,
    val minimumConfidence: Double = 0.45,
    val maximumParticipants: Int = 128,
    val maximumObservations: Int = 512,
) {
    init {
        require(participantLifetimeMs in 1..60_000 && maximumEvidenceLifetimeMs in 1..60_000)
        require(neighbourhoodM.isFinite() && neighbourhoodM in 1.0..2_000.0)
        require(corridorHalfWidthM.isFinite() && corridorHalfWidthM in 1.0..200.0)
        require(maximumPositionAccuracyM.isFinite() && maximumPositionAccuracyM in 1.0..200.0)
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0)
        require(maximumParticipants in 1..1024 && maximumObservations in 1..4096)
    }
}

/**
 * A bounded cache for adapters that receive individual messages. Accepted identities remain reserved for the
 * maximum local lifetime, including after their shorter TTL expires. Capacity rejects NEW records, preserving
 * live originals and their age. Identity replay with a forged capture time beyond retention cannot be detected
 * here: the wire adapter must preserve original capture age. This is not an authentication layer.
 */
class SwarmEvidenceStore(private val capacity: Int = 128, private val maxLifetimeMs: Long = 30_000) {
    init { require(capacity in 1..4096 && maxLifetimeMs in 1..60_000) }
    private val entries = linkedMapOf<Pair<String, String>, SwarmObservation>()

    fun ingest(observation: SwarmObservation, nowMs: Long): Boolean {
        prune(nowMs)
        if (!validObservation(observation, nowMs, maxLifetimeMs)) return false
        val key = observation.sourceId to observation.eventId
        if (key in entries) return false
        if (entries.size == capacity) return false
        entries[key] = observation
        return true
    }

    fun current(nowMs: Long): List<SwarmObservation> {
        prune(nowMs)
        return entries.values.filter { validObservation(it, nowMs, maxLifetimeMs) }
    }

    private fun prune(nowMs: Long) {
        entries.entries.removeAll { !fresh(it.value.observedAtMs, nowMs, maxLifetimeMs) }
    }
}

/**
 * Small local evidence graph shared by the phone UI and deterministic comparison harness.
 * Heading groups are NOT lane assignments or map matching. Parallel roads and junction geometry need a map.
 * The nominated observer is a preference computed from this phone's current view, not distributed consensus.
 * No state permits overtaking, entering a junction, or assuming the absence of unconnected road users.
 */
object SwarmAdvisor {
    fun evaluate(
        ego: SwarmParticipant,
        participants: List<SwarmParticipant>,
        observations: List<SwarmObservation>,
        nowMs: Long,
        config: SwarmConfig = SwarmConfig(),
    ): SwarmAssessment {
        if (participants.size > config.maximumParticipants || observations.size > config.maximumObservations)
            return unknown(emptyList(), "Assessment capacity reached", "Local input exceeds the bounded assessment capacity. Use direct observation while reports are reconciled.")
        val neighbours = participants.filter { it.id != ego.id }.distinctBy { it.id }.sortedBy { it.id }
            .map { classify(ego, it, nowMs, config) }
        val egoLocated = validParticipant(ego, nowMs, config)
        if (!egoLocated) return unknown(neighbours, "Position uncertain", "Current position is unavailable or stale. Shared reports cannot be assigned to this approach.")

        // Choose the oldest copy before freshness filtering, so a relay copy with a later receipt time cannot
        // turn an expired observation into a new observation or inflate source counts.
        val evidence = observations.groupBy { it.sourceId to it.eventId }.values
            .map { copies -> copies.minBy { it.observedAtMs } }
            .filter { validObservation(it, nowMs, config.maximumEvidenceLifetimeMs) }
            .filter { it.confidence == null || it.confidence >= config.minimumConfidence }
            .filter { relevant(ego, it, config) }
            .sortedWith(compareBy<SwarmObservation> { priority(it.kind) }.thenBy { it.sourceId }.thenBy { it.eventId })
        if (evidence.isEmpty()) return unknown(neighbours, "No fresh local evidence", "Coverage is unknown. No report does not mean a clear road; use direct observation.")

        val kind = evidence.first().kind
        val spatiallyReliable = validAccuracy(ego.positionAccuracyM, config.maximumPositionAccuracyM) &&
            validAccuracy(evidence.first().positionAccuracyM, config.maximumPositionAccuracyM)
        val unscored = evidence.first().confidence == null
        val subject = when (kind) {
            ObservationKind.OBSTRUCTION -> "Temporary obstruction reported"
            ObservationKind.QUEUE_TAIL -> "Queue reported nearby"
            ObservationKind.PEDESTRIAN -> "Pedestrian reported nearby"
            ObservationKind.WRONG_WAY -> "Opposing movement reported"
            ObservationKind.ROAD_HAZARD -> "Road hazard reported nearby"
        }
        val title = subject + if (!spatiallyReliable || unscored) " (unverified)" else ""
        val advice = if (!spatiallyReliable) "A recent report was received, but location uncertainty is unavailable or too large to assign it to this approach. Prepare to slow and check surroundings; no passage clearance is given." else if (unscored) "A recent road-condition report was received without a detector confidence score. Prepare to slow and verify the reported area directly; no passage clearance is given." else when (kind) {
            ObservationKind.OBSTRUCTION -> "Prepare to slow and inspect the narrowed passage. Opposing or hidden traffic may be present; no crossing priority is assigned."
            ObservationKind.QUEUE_TAIL -> "Prepare for slowing traffic and check that the exit has space before entering. Queue length and clearance are not established."
            ObservationKind.PEDESTRIAN -> "Prepare to slow near the reported area. A person may be hidden behind another vehicle."
            ObservationKind.WRONG_WAY -> "Prepare to slow and inspect approaching traffic. This report does not establish a free escape path."
            ObservationKind.ROAD_HAZARD -> "Prepare to slow and inspect the reported area. The exact lane and usable width are not established."
        }
        val sameDirection = neighbours.filter { it.relation == DirectionRelation.SAME_DIRECTION }.map { it.id }.toSet() + ego.id
        val originalSources = evidence.map { it.sourceId }.toSet()
        val nominee = (participants + ego).distinctBy { it.id }
            .filter { spatiallyReliable && it.id in originalSources && it.id in sameDirection && validParticipant(it, nowMs, config) && validAccuracy(it.positionAccuracyM, config.maximumPositionAccuracyM) }
            .sortedWith(compareBy<SwarmParticipant> { it.positionAccuracyM }.thenBy { it.id }).firstOrNull()?.id
        return SwarmAssessment(
            SwarmStatus.CAUTION, title, advice, evidence, originalSources.size,
            evidence.maxOf { nowMs - it.observedAtMs }, nominee, neighbours,
        )
    }

    fun classify(ego: SwarmParticipant, other: SwarmParticipant, nowMs: Long, config: SwarmConfig = SwarmConfig()): SwarmNeighbour {
        fun result(relation: DirectionRelation, reason: String, distance: Double? = null, ahead: Double? = null) =
            SwarmNeighbour(other.id, relation, distance, ahead, reason)
        if (!fresh(ego.observedAtMs, nowMs, config.participantLifetimeMs) || !fresh(other.observedAtMs, nowMs, config.participantLifetimeMs))
            return result(DirectionRelation.STALE, "Position evidence is stale or has an invalid timestamp")
        val a = ego.fix
        val b = other.fix
        if (a == null || b == null || !a.valid() || !b.valid()) return result(DirectionRelation.UNLOCATED, "A valid position is unavailable")
        if (a.synthetic != b.synthetic) return result(DirectionRelation.UNLOCATED, "Synthetic and physical positions cannot be combined")
        val offset = offset(a, b)
        val distance = hypot(offset.first, offset.second)
        val ahead = a.heading?.let { offset.first * sin(Math.toRadians(it)) + offset.second * cos(Math.toRadians(it)) }
        if (distance > config.neighbourhoodM) return result(DirectionRelation.OUT_OF_RANGE, "Outside the local assessment region", distance, ahead)
        if (!validAccuracy(ego.positionAccuracyM, config.maximumPositionAccuracyM) || !validAccuracy(other.positionAccuracyM, config.maximumPositionAccuracyM))
            return result(DirectionRelation.UNCERTAIN, "Position uncertainty is too large for grouping", distance, ahead)
        val heading = a.heading
        val otherHeading = b.heading
        if (heading == null || otherHeading == null || !validAccuracy(ego.headingAccuracyDeg, 45.0) || !validAccuracy(other.headingAccuracyDeg, 45.0))
            return result(DirectionRelation.UNCERTAIN, "Reliable movement headings are unavailable", distance, ahead)
        val delta = abs((heading - otherHeading + 540.0) % 360.0 - 180.0)
        val uncertainty = ego.headingAccuracyDeg + other.headingAccuracyDeg
        val relation = when {
            delta + uncertainty <= 60.0 -> DirectionRelation.SAME_DIRECTION
            delta - uncertainty >= 120.0 -> DirectionRelation.OPPOSING
            delta - uncertainty > 45.0 && delta + uncertainty < 135.0 -> DirectionRelation.CROSSING
            else -> DirectionRelation.UNCERTAIN
        }
        return result(relation, "Heading relationship only; road, lane and crossing priority are unverified", distance, ahead)
    }

    private fun unknown(neighbours: List<SwarmNeighbour>, title: String, advice: String) =
        SwarmAssessment(SwarmStatus.UNKNOWN, title, advice, emptyList(), 0, null, null, neighbours)

    private fun priority(kind: ObservationKind): Int = when (kind) {
        ObservationKind.PEDESTRIAN -> 0
        ObservationKind.WRONG_WAY -> 1
        ObservationKind.OBSTRUCTION -> 2
        ObservationKind.QUEUE_TAIL -> 3
        ObservationKind.ROAD_HAZARD -> 4
    }

    private fun validParticipant(p: SwarmParticipant, nowMs: Long, config: SwarmConfig): Boolean =
        p.id.isNotBlank() && p.fix?.valid() == true && fresh(p.observedAtMs, nowMs, config.participantLifetimeMs)

    private fun relevant(ego: SwarmParticipant, event: SwarmObservation, config: SwarmConfig): Boolean {
        val fix = ego.fix ?: return false
        if (fix.synthetic != event.fix.synthetic) return false
        val (east, north) = offset(fix, event.fix)
        if (hypot(east, north) > config.neighbourhoodM) return false
        if (!validAccuracy(ego.positionAccuracyM, config.maximumPositionAccuracyM) || !validAccuracy(event.positionAccuracyM, config.maximumPositionAccuracyM)) return true
        // Without a reliable heading, report only general vicinity. Never infer that a direction is clear.
        val heading = fix.heading ?: return true
        if (!validAccuracy(ego.headingAccuracyDeg, 30.0)) return true
        val rad = Math.toRadians(heading)
        val forward = east * sin(rad) + north * cos(rad)
        val sideways = east * cos(rad) - north * sin(rad)
        val uncertainty = ego.positionAccuracyM + event.positionAccuracyM
        return forward + uncertainty >= -20.0 && abs(sideways) <= config.corridorHalfWidthM + uncertainty
    }
}

internal fun fresh(observedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean =
    observedAtMs >= 0 && nowMs >= observedAtMs && nowMs - observedAtMs < ttlMs

private fun validAccuracy(value: Double, maximum: Double) = value.isFinite() && value in 0.0..maximum

private fun validObservation(o: SwarmObservation, nowMs: Long, maxLifetimeMs: Long): Boolean =
    o.eventId.isNotBlank() && o.sourceId.isNotBlank() && o.fix.valid() && o.ttlMs > 0 &&
        fresh(o.observedAtMs, nowMs, minOf(o.ttlMs, maxLifetimeMs)) &&
        (o.confidence == null || o.confidence.isFinite() && o.confidence in 0.0..1.0) && !o.positionAccuracyM.isNaN() && o.positionAccuracyM >= 0

/** Local tangent approximation, bounded to a small neighbourhood by the caller. Dateline is wrapped. */
internal fun offset(origin: GeoFix, target: GeoFix): Pair<Double, Double> {
    val latitude = Math.toRadians((origin.lat + target.lat) / 2.0)
    val deltaLon = (target.lon - origin.lon + 540.0) % 360.0 - 180.0
    return Math.toRadians(deltaLon) * 6_371_000.0 * cos(latitude) to
        Math.toRadians(target.lat - origin.lat) * 6_371_000.0
}
