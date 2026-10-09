package com.jsons.roadmesh.core.mesh

import kotlin.math.*

data class NodeId(val value: Long) { override fun toString() = java.lang.Long.toUnsignedString(value, 16) }
data class MessageId(val origin: NodeId, val session: Long, val sequence: Int)
enum class Provenance(val wire: Int) { DEMO(1), LIVE(2), REPLAY(3) }
enum class Role(val title: String, val stageId: Long) { DETECTOR("Detector", 1), RELAY("Relay", 2), OBSERVER("Observer", 3) }
enum class Mode { STAGE, ROAD }
enum class HazardKind(val cause: Int, val subCause: Int, val speech: String) {
    BRAKING(27, 1, "Caution. Braking ahead.");
    companion object { fun from(cause: Int, sub: Int) = entries.find { it.cause == cause && it.subCause == sub } }
}
data class GeoFix(val lat: Double, val lon: Double, val heading: Double?, val speedMps: Double?, val synthetic: Boolean) {
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
        (heading == null || heading.isFinite() && heading in 0.0..360.0) &&
        (speedMps == null || speedMps.isFinite() && speedMps in 0.0..100.0)
}
data class LocalHazard(val kind: HazardKind, val provenance: Provenance, val sourceEvent: String)

/** Implementations can be GNSS, explicit demo tracks, or recorded input. No radio dependency. */
fun interface PositionSource { fun current(): GeoFix? }
fun interface HazardSource { fun setSink(sink: (LocalHazard) -> Unit) }
interface MeshClock { fun monotonicMs(): Long; fun utcMs(): Long }

/** A starting operating envelope. Tune it from measured neighborhood density, not total fleet size. */
data class MeshProfile(
    val mode: Mode = Mode.STAGE,
    val initialTtl: Int = if (mode == Mode.STAGE) 1 else 2,
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
        require(initialTtl in 0..2) { "initialTtl must be 0..2" }
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

fun stageFix(role: Role) = GeoFix(12.0 - (role.stageId - 1) * 100.0 / 111_195.0, 77.0, 0.0, 10.0, true)
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

fun interface LinkPolicy { fun accepts(local: NodeId, forwarder: NodeId): Boolean }
object OpenLinks : LinkPolicy { override fun accepts(local: NodeId, forwarder: NodeId) = local != forwarder }
/** Disclosed staging filter. Radio reception can overlap physically; this controls acceptance. */
object StageChain : LinkPolicy {
    override fun accepts(local: NodeId, forwarder: NodeId) = abs(local.value - forwarder.value) == 1L &&
        local.value in 1L..3L && forwarder.value in 1L..3L
}
