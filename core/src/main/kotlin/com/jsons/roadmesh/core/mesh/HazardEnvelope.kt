package com.jsons.roadmesh.core.mesh

import com.jsons.roadmesh.core.protocol.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One warning on air. [ttl] and [hop] are the authoritative relay budget (0..[MAX_TTL]); the 27-byte core packet keeps
 * its two-bit fields saturated at 3 for older readers. [rank] is the forwarder's chain position, [NO_RANK] if unknown.
 */
data class HazardEnvelope(
    val packet: Packet, val id: MessageId, val forwarder: NodeId, val provenance: Provenance,
    val ttl: Int = packet.ttl, val hop: Int = packet.hop, val rank: Int = NO_RANK,
) {
    /** The copy this node sends on: one relay used, this node as forwarder. Null when the budget is spent. */
    fun relayedBy(node: NodeId, alias: Int, rank: Int = NO_RANK): HazardEnvelope? {
        if (ttl <= 0 || hop >= MAX_TTL) return null
        val t = ttl - 1; val h = hop + 1
        return copy(packet = packet.copy(ttl = minOf(t, 3), hop = minOf(h, 3), lastHop = alias and 0xFF), forwarder = node, ttl = t, hop = h, rank = rank)
    }
    val radioLinks get() = hop + 1
    /** The origin's inference (range to the hazard, vehicle type, confidence), if it sent one. */
    val detail get() = HazardDetail.from(packet.p1, packet.p2)
    val kind get() = HazardKind.from(packet.cause, packet.subCause)
}

/** Extended-advertising envelope v2. The original 27-byte core remains unchanged.
 * Full identities, a new boot/session nonce, and a 32-bit sequence form the actual dedup key.
 * The 24-bit sender field is an explicitly assigned diagnostic alias, never a truncated identity.
 * No authentication is claimed by this unsigned experimental envelope.
 */
object HazardEnvelopeCodec {
    const val VERSION = 2
    const val SIZE = Packet.SIZE + 34
    fun encode(e: HazardEnvelope): ByteArray {
        require(e.ttl in 0..MAX_TTL && e.hop in 0..MAX_TTL && e.rank in 0..255) { "ttl/hop/rank" }
        return ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(PacketCodec.encode(e.packet)); put(0x45); put(VERSION.toByte())
            putLong(e.id.origin.value); putLong(e.id.session); putInt(e.id.sequence)
            putLong(e.forwarder.value); put(e.provenance.wire.toByte())
            put(e.ttl.toByte()); put(e.hop.toByte()); put(e.rank.toByte())
        }.array()
    }

    fun decode(raw: ByteArray): HazardEnvelope? {
        if (raw.size != SIZE) return null
        val packet = (PacketCodec.decode(raw.copyOfRange(0, Packet.SIZE)) as? DecodeResult.Ok)?.packet ?: return null
        val b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).apply { position(Packet.SIZE) }
        if (b.get().toInt() != 0x45 || b.get().toInt() != VERSION) return null
        val id = MessageId(NodeId(b.long), b.long, b.int)
        val forwarder = NodeId(b.long)
        val provenanceCode = b.get().toInt()
        val provenance = Provenance.entries.find { it.wire == provenanceCode } ?: return null
        val ttl = b.get().toInt() and 255; val hop = b.get().toInt() and 255; val rank = b.get().toInt() and 255
        if (ttl > MAX_TTL || hop > MAX_TTL) return null
        if (packet.seq != (id.sequence and 0xffff) || id.origin.value == 0L || forwarder.value == 0L) return null
        if (packet.ttl != minOf(ttl, 3) || packet.hop != minOf(hop, 3)) return null
        if (hop == 0 && forwarder != id.origin || hop > 0 && forwarder == id.origin) return null
        return HazardEnvelope(packet, id, forwarder, provenance, ttl, hop, rank)
    }
}
