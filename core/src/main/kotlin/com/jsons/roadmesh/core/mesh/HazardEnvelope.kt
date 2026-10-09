package com.jsons.roadmesh.core.mesh

import com.jsons.roadmesh.core.protocol.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HazardEnvelope(val packet: Packet, val id: MessageId, val forwarder: NodeId, val provenance: Provenance) {
    fun relayedBy(node: NodeId, alias: Int) = PacketCodec.forRelay(packet, alias)?.let { copy(packet = it, forwarder = node) }
    val radioLinks get() = packet.hop + 1
}

/** Extended-advertising envelope v1. The original 27-byte core remains unchanged.
 * Full identities, a new boot/session nonce, and a 32-bit sequence form the actual dedup key.
 * The 24-bit sender field is an explicitly assigned diagnostic alias, never a truncated identity.
 * No authentication is claimed by this unsigned experimental envelope.
 */
object HazardEnvelopeCodec {
    const val SIZE = Packet.SIZE + 31
    fun encode(e: HazardEnvelope): ByteArray = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN).apply {
        put(PacketCodec.encode(e.packet)); put(0x45); put(1)
        putLong(e.id.origin.value); putLong(e.id.session); putInt(e.id.sequence)
        putLong(e.forwarder.value); put(e.provenance.wire.toByte())
    }.array()

    fun decode(raw: ByteArray): HazardEnvelope? {
        if (raw.size != SIZE) return null
        val packet = (PacketCodec.decode(raw.copyOfRange(0, Packet.SIZE)) as? DecodeResult.Ok)?.packet ?: return null
        val b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).apply { position(Packet.SIZE) }
        if (b.get().toInt() != 0x45 || b.get().toInt() != 1) return null
        val id = MessageId(NodeId(b.long), b.long, b.int)
        val forwarder = NodeId(b.long)
        val provenanceCode = b.get().toInt()
        val provenance = Provenance.entries.find { it.wire == provenanceCode } ?: return null
        if (packet.seq != (id.sequence and 0xffff) || id.origin.value == 0L || forwarder.value == 0L) return null
        if (packet.hop == 0 && forwarder != id.origin || packet.hop > 0 && forwarder == id.origin) return null
        return HazardEnvelope(packet, id, forwarder, provenance)
    }
}
