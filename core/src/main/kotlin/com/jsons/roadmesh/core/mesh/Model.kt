package com.jsons.roadmesh.core.mesh

import kotlin.math.*

data class NodeId(val value: Long) { override fun toString() = java.lang.Long.toUnsignedString(value, 16) }
data class MessageId(val origin: NodeId, val session: Long, val sequence: Int)
enum class Provenance(val wire: Int) { DEMO(1), LIVE(2), REPLAY(3) }
enum class Role(val title: String, val stageId: Long) { OBSERVER("Observer", 1), RELAY("Relay", 2), RECEIVER("Receiver", 3) }
enum class Mode { STAGE, ROAD }
/** What a report is for. CAUTION is spoken to drivers behind; REPORT is logged and shared for the authorities. */
enum class HazardUse { CAUTION, REPORT }

/**
 * Hazard kinds on air. Cause codes follow ETSI DENM where one exists (2 accident, 9 surface condition, 1 traffic
 * condition, 14 wrong-way driving, 12 people on the road, 27 end of queue, 97 collision risk). Violations have no DENM
 * cause; they use the private cause 200. [lifetimeS] is how long a node keeps the report on its road map.
 */
enum class HazardKind(val cause: Int, val subCause: Int, val speech: String, val use: HazardUse, val lifetimeS: Int) {
    BRAKING(27, 1, "Caution. Braking ahead.", HazardUse.CAUTION, 90),
    PEDESTRIAN(12, 0, "Caution. Pedestrian ahead.", HazardUse.CAUTION, 30),
    VEHICLE_CLOSE(97, 0, "Caution. Vehicle close ahead.", HazardUse.CAUTION, 20),
    POTHOLE(9, 0, "Caution. Pothole ahead.", HazardUse.CAUTION, 1_800),
    ACCIDENT(2, 0, "Caution. Accident ahead.", HazardUse.CAUTION, 1_200),
    SLOWDOWN(1, 0, "Caution. Traffic slowing ahead.", HazardUse.CAUTION, 120),
    WRONG_WAY(14, 0, "Caution. Vehicle on the wrong side ahead.", HazardUse.CAUTION, 120),
    TRIPLE_RIDING(200, 1, "Violation logged. Triple riding.", HazardUse.REPORT, 600),
    NO_HELMET(200, 2, "Violation logged. Rider without a helmet.", HazardUse.REPORT, 600),
    RED_LIGHT(200, 3, "Violation logged. Red light jumped.", HazardUse.REPORT, 600),
    // App-specific manual observation. Append to preserve existing control-record ordinal codes.
    ROAD_NARROWED(201, 0, "Caution. Road narrowed ahead. Watch for opposing traffic.", HazardUse.CAUTION, 120);

    val violation get() = cause == 200
    companion object { fun from(cause: Int, sub: Int) = entries.find { it.cause == cause && it.subCause == sub } }
}
data class GeoFix(val lat: Double, val lon: Double, val heading: Double?, val speedMps: Double?, val synthetic: Boolean,
                  val accuracyM: Double? = null, val headingAccuracyDeg: Double? = null,
                  /** Local GNSS sample metadata; not encoded in the presence wire format. */
                  val sampledAtMs: Long? = null, val speedAccuracyMps: Double? = null) {
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
        (heading == null || heading.isFinite() && heading in 0.0..360.0) &&
        (speedMps == null || speedMps.isFinite() && speedMps in 0.0..100.0) &&
        (accuracyM == null || accuracyM.isFinite() && accuracyM >= 0.0) &&
        (headingAccuracyDeg == null || headingAccuracyDeg.isFinite() && headingAccuracyDeg in 0.0..180.0) &&
        (sampledAtMs == null || sampledAtMs >= 0) &&
        (speedAccuracyMps == null || speedAccuracyMps.isFinite() && speedAccuracyMps >= 0.0)
}
data class LocalHazard(val kind: HazardKind, val provenance: Provenance, val sourceEvent: String, val detail: HazardDetail? = null,
                       /** Original sensor capture on the local monotonic clock, before inference or queueing. */
                       val capturedAtMs: Long? = null)

/** What the originating phone is mounted in. Four bits on air. */
enum class VehicleType(val code: Int, val title: String) {
    UNKNOWN(0, "vehicle"), CAR(1, "car"), TWO_WHEELER(2, "two-wheeler"), AUTO(3, "auto-rickshaw"), BUS_TRUCK(4, "bus or truck");
    companion object { fun from(code: Int) = entries.find { it.code == code } ?: UNKNOWN }
}

/**
 * The origin's own inference, carried in the two spare hazard bytes of the core packet: p1 is the distance from the
 * origin's camera to the hazard in metres (1..255, 0 unknown); p2 holds the origin's vehicle type (high nibble) and
 * the detector's confidence in tenths (low nibble, 0 unknown). Relays copy it unchanged.
 */
data class HazardDetail(val rangeM: Int?, val vehicle: VehicleType, val confidence: Double?) {
    val p1 get() = rangeM?.coerceIn(1, 255) ?: 0
    val p2 get() = (vehicle.code shl 4) or (confidence?.let { (it * 10).roundToInt().coerceIn(1, 10) } ?: 0)
    companion object {
        fun from(p1: Int, p2: Int): HazardDetail? = if (p1 == 0 && p2 == 0) null
            else HazardDetail(p1.takeIf { it > 0 }, VehicleType.from(p2 shr 4), (p2 and 15).takeIf { it in 1..10 }?.let { it / 10.0 })
    }
}

/** Implementations can be GNSS, explicit demo tracks, or recorded input. No radio dependency. */
fun interface PositionSource { fun current(): GeoFix? }
fun interface HazardSource { fun setSink(sink: (LocalHazard) -> Unit) }
interface MeshClock { fun monotonicMs(): Long; fun utcMs(): Long }

/** A starting operating envelope. Tune it from measured neighborhood density, not total fleet size. */
data class MeshProfile(
    val mode: Mode = Mode.STAGE,
    /** Relays a warning may still take. A chain of n phones needs n - 1. */
    val initialTtl: Int = if (mode == Mode.STAGE) 8 else 4,
    val maxAgeMs: Long = 5_000,
    val futureToleranceMs: Long = 2_000,
    val dedupCapacity: Int = 4_096,
    val dedupRetentionMs: Long = 30_000,
    val neighborCapacity: Int = 1_024,
    val neighborRetentionMs: Long = 30_000,
    val pendingCapacity: Int = 64,
    val maxRelayDelayMs: Int = 200,
    val jitterMs: Int = 20,
    val suppressionCopies: Int = 2,
    val rangeM: Double = 150.0,
    val relevanceM: Double = 400.0,
    val relayPerSecond: Int = 3,
    val originPerSecond: Int = 2,
) {
    fun validate(): MeshProfile = apply {
        require(initialTtl in 0..MAX_TTL) { "initialTtl must be 0..$MAX_TTL" }
        require(maxAgeMs in 500..30_000 && futureToleranceMs in 0..5_000) { "freshness limits" }
        require(dedupCapacity in 64..16_384 && dedupRetentionMs in maxAgeMs..120_000) { "dedup limits" }
        require(neighborCapacity in 3..4_096 && neighborRetentionMs in 5_000..120_000) { "neighbor limits" }
        require(pendingCapacity in 1..256) { "pendingCapacity must be 1..256" }
        require(maxRelayDelayMs in 0..1_000 && jitterMs in 0..200) { "relay delay" }
        require(suppressionCopies in 1..8) { "suppressionCopies must be 1..8" }
        require(rangeM.isFinite() && rangeM in 10.0..1_000.0 && relevanceM.isFinite() && relevanceM in 10.0..1_000.0) { "range limits" }
        require(relayPerSecond in 1..20 && originPerSecond in 1..10) { "rate limits" }
    }
}

const val MAX_TTL = 15
/** Stage phones stand in a line heading north, [STAGE_GAP_M] apart; chain position 0 (the observer) is in front. */
const val STAGE_GAP_M = 40.0
fun stageFix(rank: Int) = GeoFix(12.0 - rank * STAGE_GAP_M / 111_195.0, 77.0, 0.0, 10.0, true)
fun stageFix(role: Role) = stageFix((role.stageId - 1).toInt())
fun distanceM(a: GeoFix, b: GeoFix): Double {
    val dy = Math.toRadians(b.lat - a.lat); val dx = Math.toRadians(b.lon - a.lon)
    val h = sin(dy / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dx / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
fun bearing(a: GeoFix, b: GeoFix): Double {
    val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
    return (Math.toDegrees(atan2(sin(dl) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dl))) + 360) % 360
}
fun angleDifference(a: Double, b: Double) = abs((a - b + 540) % 360 - 180)

/** Chain position carried on air when the sender does not know its own. */
const val NO_RANK = 255

/** Decides whether a frame from [forwarder] (at chain position [forwarderRank]) counts as a radio link to [local]. */
fun interface LinkPolicy { fun accepts(local: NodeId, forwarder: NodeId, forwarderRank: Int): Boolean }
object OpenLinks : LinkPolicy { override fun accepts(local: NodeId, forwarder: NodeId, forwarderRank: Int) = local != forwarder }

/**
 * Disclosed staging filter for phones lying on one table: radio reception overlaps physically, so a node accepts a frame
 * only from the position directly in front of it. [rank] is this node's current chain position.
 */
class ChainLinks(private val rank: () -> Int) : LinkPolicy {
    override fun accepts(local: NodeId, forwarder: NodeId, forwarderRank: Int): Boolean {
        val mine = rank()
        return local != forwarder && mine != NO_RANK && forwarderRank != NO_RANK && forwarderRank == mine - 1
    }
}
/** The fixed three-phone stage: node ids 1, 2, 3 are positions 0, 1, 2. */
val StageChain: LinkPolicy = LinkPolicy { local, forwarder, forwarderRank ->
    local.value in 1L..3L && forwarder.value in 1L..3L && abs(local.value - forwarder.value) == 1L &&
        (forwarderRank == NO_RANK || forwarderRank == (forwarder.value - 1).toInt())
}
