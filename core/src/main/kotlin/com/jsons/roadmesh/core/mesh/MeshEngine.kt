package com.jsons.roadmesh.core.mesh

import com.jsons.roadmesh.core.protocol.*
import com.jsons.roadmesh.core.protocol.Flags as PacketFlags
import kotlin.math.roundToInt

sealed interface MeshEffect {
    data class Originated(val frame: HazardEnvelope, val sourceEvent: String) : MeshEffect
    data class Transmit(val frame: HazardEnvelope, val relay: Boolean) : MeshEffect
    data class Received(val frame: HazardEnvelope) : MeshEffect
    data class Announce(val frame: HazardEnvelope, val kind: HazardKind, val distanceM: Int) : MeshEffect
    data class Notice(val code: String, val detail: String = "") : MeshEffect
}
data class MeshBounds(val dedup: Int, val neighbors: Int, val pending: Int)

/** Pure, single-owner state machine. An Android service or a deterministic simulator drives it.
 * No callbacks, threads, graphics, Bluetooth or speech run inside the engine.
 */
class MeshEngine(
    val node: NodeId,
    private val alias: Int,
    private val session: Long,
    val profile: MeshProfile,
    private val clock: MeshClock,
    private val positions: PositionSource,
    private val links: LinkPolicy = OpenLinks,
    private val announce: Boolean = true,
    private val random: (Int) -> Int = { kotlin.random.Random.nextInt(it) },
) {
    private data class Seen(val until: Long)
    private data class Neighbor(var last: Long, var window: Long, var count: Int)
    private data class Pending(val frame: HazardEnvelope, val due: Long, val expires: Long, val heard: MutableSet<NodeId> = HashSet())
    private val seen = LinkedHashMap<MessageId, Seen>()
    private val neighbors = LinkedHashMap<NodeId, Neighbor>()
    private val originRates = LinkedHashMap<NodeId, Neighbor>()
    private val pending = LinkedHashMap<MessageId, Pending>()
    private var sequence = 0
    private var rateWindow = Long.MIN_VALUE
    private var relayCount = 0
    private var ownWindow = Long.MIN_VALUE
    private var ownCount = 0
    init { profile.validate(); require(node.value != 0L && alias in 1..0xffffff) }
    val bounds get() = MeshBounds(seen.size, neighbors.size, pending.size)

    fun publish(hazard: LocalHazard): List<MeshEffect> {
        val now = clock.monotonicMs(); expire(now)
        val fix = positions.current()?.takeIf { it.valid() } ?: return drop("position_unavailable")
        if (!permitted(hazard.provenance, fix.synthetic)) return drop("provenance_rejected")
        if (ownWindow == Long.MIN_VALUE || now - ownWindow >= 1_000) { ownWindow = now; ownCount = 0 }
        if (ownCount >= profile.originPerSecond) return drop("origin_rate")
        if (seen.size >= profile.dedupCapacity) return drop("dedup_full")
        ownCount++
        val id = MessageId(node, session, ++sequence)
        val packet = Packet(PacketClass.ALERT, alias, sequence and 0xffff, profile.initialTtl, 0,
            (if (fix.synthetic) PacketFlags.SYNTH_POS else 0) or (if (fix.heading == null) PacketFlags.NO_HEADING else 0),
            Math.floorMod(clock.utcMs(), 3_600_000L).toInt() / 100,
            (fix.lat * 1e7).roundToInt(), (fix.lon * 1e7).roundToInt(),
            fix.speedMps?.times(100)?.roundToInt() ?: Packet.SPEED_UNKNOWN,
            fix.heading?.let { ((it % 360) * 256 / 360).roundToInt() and 255 } ?: 0,
            hazard.kind.cause, hazard.kind.subCause, hazard.detail?.p1 ?: 0, hazard.detail?.p2 ?: 0, alias and 255, (profile.maxAgeMs / 1_000).toInt().coerceAtLeast(1))
        val frame = HazardEnvelope(packet, id, node, hazard.provenance)
        seen[id] = Seen(now + profile.dedupRetentionMs)
        return listOf(MeshEffect.Originated(frame, hazard.sourceEvent), MeshEffect.Transmit(frame, false))
    }

    fun receive(raw: ByteArray): List<MeshEffect> {
        val now = clock.monotonicMs(); expire(now)
        val e = HazardEnvelopeCodec.decode(raw) ?: return drop("decode_rejected")
        val p = e.packet
        if (!links.accepts(node, e.forwarder)) return drop("link_filtered")
        if (e.id.origin == node) return drop("own_copy")
        val kind = HazardKind.from(p.cause, p.subCause) ?: return drop("cause_rejected")
        if (p.cls != PacketClass.ALERT || p.flags and PacketFlags.SIGNED != 0 || p.flags and PacketFlags.PROXY != 0) return drop("unsupported_packet")
        if (p.hop + p.ttl != profile.initialTtl) return drop("hop_budget")
        if (!permitted(e.provenance, p.flags and PacketFlags.SYNTH_POS != 0)) return drop("provenance_rejected")
        val age = generationAgeMs(p.tGenDeci, clock.utcMs())
        val validity = minOf(profile.maxAgeMs, p.validityS * 1_000L)
        if (validity <= 0 || age > validity || age < -profile.futureToleranceMs) return drop("stale_or_future")
        val remote = fix(p)
        if (!remote.valid()) return drop("invalid_position")
        val own = positions.current()?.takeIf { it.valid() }
        val distance = own?.let { distanceM(it, remote) }
        // Origin location is retained across relays. Permit the full configured radio budget.
        if (distance != null && distance > (p.hop + 1) * profile.rangeM * 1.5) return drop("implausible_distance")
        val neighbor = neighbors[e.forwarder] ?: run {
            if (neighbors.size >= profile.neighborCapacity) return drop("neighbors_full")
            Neighbor(now, now, 0).also { neighbors[e.forwarder] = it }
        }
        neighbor.last = now
        // Valid overheard progress can cancel a pending relay even though the event is a duplicate.
        pending[e.id]?.let { q ->
            if (p.hop > q.frame.packet.hop && e.forwarder != node) q.heard += e.forwarder
            if (q.heard.size >= profile.suppressionCopies) {
                pending.remove(e.id)
                return listOf(MeshEffect.Notice("relay_suppressed"))
            }
        }
        if (e.id in seen) return drop("duplicate")
        val origin = originRates[e.id.origin] ?: run {
            if (originRates.size >= profile.neighborCapacity) return drop("origin_table_full")
            Neighbor(now, now, 0).also { originRates[e.id.origin] = it }
        }
        origin.last = now
        if (now - origin.window >= 1_000) { origin.window = now; origin.count = 0 }
        if (++origin.count > profile.originPerSecond) return drop("origin_rate")
        if (now - neighbor.window >= 1_000) { neighbor.window = now; neighbor.count = 0 }
        // Limit newly accepted events per immediate neighbor; repeated radio copies don't consume it.
        if (++neighbor.count > profile.originPerSecond * 4) return drop("neighbor_rate")
        if (seen.size >= profile.dedupCapacity) return drop("dedup_full")
        seen[e.id] = Seen(now + profile.dedupRetentionMs)
        val effects = mutableListOf<MeshEffect>(MeshEffect.Received(e))
        if (announce && own != null && distance != null && relevant(own, remote, distance)) {
            // This base has one trust tier: caution. Forwarders never create independent evidence.
            effects += MeshEffect.Announce(e, kind, distance.roundToInt())
        }
        if (p.ttl > 0) {
            if (pending.size >= profile.pendingCapacity) effects += MeshEffect.Notice("pending_full")
            else {
                val delay = (profile.maxRelayDelayMs * (1 - ((distance ?: 0.0) / profile.rangeM).coerceIn(0.0, 1.0))).toLong() + random(profile.jitterMs + 1)
                pending[e.id] = Pending(e, now + delay, now + (validity - age.coerceAtLeast(0)))
                effects += MeshEffect.Notice("relay_scheduled", "${delay}ms")
            }
        }
        return effects
    }

    fun tick(): List<MeshEffect> {
        val now = clock.monotonicMs(); expire(now)
        if (rateWindow == Long.MIN_VALUE || now - rateWindow >= 1_000) { rateWindow = now; relayCount = 0 }
        val effects = mutableListOf<MeshEffect>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (_, q) = iterator.next()
            if (now > q.expires) { iterator.remove(); effects += MeshEffect.Notice("relay_expired"); continue }
            if (q.due > now) continue
            iterator.remove()
            if (relayCount >= profile.relayPerSecond) { effects += MeshEffect.Notice("relay_rate"); continue }
            q.frame.relayedBy(node, alias)?.let { relayCount++; effects += MeshEffect.Transmit(it, true) }
        }
        return effects
    }

    private fun permitted(provenance: Provenance, synthetic: Boolean) = when (profile.mode) {
        // Stage accepts the virtual brake (DEMO) and recorded dashcam footage (REPLAY), both on synthetic positions.
        Mode.STAGE -> (provenance == Provenance.DEMO || provenance == Provenance.REPLAY) && synthetic
        Mode.ROAD -> provenance == Provenance.LIVE && !synthetic
    }
    private fun expire(now: Long) {
        seen.entries.removeAll { it.value.until <= now }
        neighbors.entries.removeAll { now - it.value.last > profile.neighborRetentionMs }
        originRates.entries.removeAll { now - it.value.last > profile.neighborRetentionMs }
    }
    private fun relevant(own: GeoFix, remote: GeoFix, distance: Double): Boolean {
        val heading = own.heading ?: return false
        val theirs = remote.heading ?: return false
        return distance <= profile.relevanceM && angleDifference(heading, theirs) <= 45 &&
            (distance < 5 || angleDifference(heading, bearing(own, remote)) <= 30)
    }
    private fun fix(p: Packet) = GeoFix(p.latE7 / 1e7, p.lonE7 / 1e7,
        if (p.flags and PacketFlags.NO_HEADING != 0) null else p.headingQ * 360.0 / 256,
        if (p.speedCms == Packet.SPEED_UNKNOWN) null else p.speedCms / 100.0, p.flags and PacketFlags.SYNTH_POS != 0)
    private fun drop(reason: String) = listOf(MeshEffect.Notice(reason))
}

/** Nearest-hour reconstruction is a freshness check with 100ms resolution, never a latency metric. */
fun generationAgeMs(tGenDeci: Int, utcMs: Long): Long {
    val hour = Math.floorDiv(utcMs, 3_600_000L) * 3_600_000L
    val generated = hour + tGenDeci * 100L
    return listOf(generated - 3_600_000, generated, generated + 3_600_000).minBy { kotlin.math.abs(utcMs - it) }.let { utcMs - it }
}
