package com.jsons.roadmesh.core.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.ceil

/**
 * Control frames share the BLE manufacturer data with hazard envelopes but start with magic 0x53, so a reader tells them
 * apart from the first byte. Byte 1 is version (high nibble) and type (low nibble). All unsigned, unauthenticated.
 */
sealed interface ControlFrame {
    /** A heartbeat every second: who is in the mesh, how long it has been in it, where, and whether its camera runs. */
    data class Presence(
        val node: NodeId, val ageMs: Long, val observer: Boolean, val synthetic: Boolean, val rank: Int,
        val vehicle: VehicleType, val fix: GeoFix?, val known: Int,
    ) : ControlFrame

    /** "What is the road like?" Flooded both ways along the chain with its own relay budget. */
    data class Query(val id: MessageId, val forwarder: NodeId, val ttl: Int, val hop: Int, val radiusM: Int, val fix: GeoFix) : ControlFrame {
        fun relayedBy(node: NodeId) = if (ttl <= 0) null else copy(forwarder = node, ttl = ttl - 1, hop = hop + 1)
    }

    /** One node's answer: where it is, how fast it moves, and the hazards it holds, as offsets in metres from itself. */
    data class Reply(
        val query: MessageId, val responder: NodeId, val forwarder: NodeId, val ttl: Int, val hop: Int, val rank: Int,
        val fix: GeoFix, val records: List<Record>,
        /** Local receive time, never serialized; used to age evidence while a query is collected. */
        val receivedAtMs: Long? = null,
    ) : ControlFrame {
        fun relayedBy(node: NodeId) = if (ttl <= 0) null else copy(forwarder = node, ttl = ttl - 1, hop = hop + 1)
    }

    /** One original observation. Legacy [reporters] is retained for decoding, never trusted as independent support. */
    data class Record(
        val kind: HazardKind, val northM: Int, val eastM: Int, val ageS: Int, val reporters: Int, val confidence: Double?,
        val observationId: MessageId? = null, val capturedUtcMs: Long? = null, val provenance: Provenance? = null,
        val accuracyM: Double? = null,
    )
}

object ControlCodec {
    const val MAGIC = 0x53
    const val VERSION = 2
    const val MAX_RECORDS = 4
    const val MAX_BYTES = 256
    private const val PRESENCE = 1
    private const val QUERY = 2
    private const val REPLY = 3
    private const val NO_POS = Int.MIN_VALUE

    fun encode(f: ControlFrame): ByteArray {
        val b = ByteBuffer.allocate(MAX_BYTES).order(ByteOrder.BIG_ENDIAN)
        b.put(MAGIC.toByte())
        when (f) {
            is ControlFrame.Presence -> {
                b.put(((VERSION shl 4) or PRESENCE).toByte())
                b.putLong(f.node.value); b.putLong(f.ageMs)
                b.put(((if (f.observer) 1 else 0) or (if (f.synthetic) 2 else 0)).toByte())
                b.put(f.rank.coerceIn(0, 255).toByte()); b.put(f.vehicle.code.toByte())
                putFix(b, f.fix, uncertainty = true); b.put(f.known.coerceIn(0, 255).toByte())
            }
            is ControlFrame.Query -> {
                require(f.ttl in 0..MAX_TTL && f.hop in 0..MAX_TTL) { "ttl/hop" }
                b.put(((1 shl 4) or QUERY).toByte())
                putId(b, f.id); b.putLong(f.forwarder.value); b.put(f.ttl.toByte()); b.put(f.hop.toByte())
                b.putShort(f.radiusM.coerceIn(0, 65_535).toShort()); putFix(b, f.fix)
            }
            is ControlFrame.Reply -> {
                require(f.ttl in 0..MAX_TTL && f.hop in 0..MAX_TTL && f.records.size <= MAX_RECORDS) { "ttl/hop/records" }
                b.put(((VERSION shl 4) or REPLY).toByte())
                putId(b, f.query); b.putLong(f.responder.value); b.putLong(f.forwarder.value)
                b.put(f.ttl.toByte()); b.put(f.hop.toByte()); b.put(f.rank.coerceIn(0, 255).toByte()); putFix(b, f.fix, uncertainty = true)
                b.put(f.records.size.toByte())
                for (r in f.records) {
                    require(r.ageS in 0..65_535 && (r.confidence == null || r.confidence.isFinite() && r.confidence in 0.0..1.0)) { "record age/confidence" }
                    require(r.observationId == null || r.observationId.origin.value != 0L) { "record origin" }
                    b.put(r.kind.cause.toByte()); b.put(r.kind.subCause.toByte())
                    b.putShort(r.northM.coerceIn(-32_767, 32_767).toShort()); b.putShort(r.eastM.coerceIn(-32_767, 32_767).toShort())
                    b.putShort(r.ageS.coerceIn(0, 65_535).toShort()); b.put(r.reporters.coerceIn(1, 255).toByte())
                    b.put((r.confidence?.let { (it * 100).roundToInt().coerceIn(1, 100) } ?: 0).toByte())
                    putId(b, r.observationId ?: MessageId(NodeId(0), 0, 0))
                    b.putLong(r.capturedUtcMs ?: Long.MIN_VALUE)
                    b.put((r.provenance?.wire ?: 0).toByte())
                    b.put((r.accuracyM?.takeIf { it.isFinite() && it in 0.0..254.0 }?.let { ceil(it).toInt() } ?: 255).toByte())
                }
            }
        }
        return b.array().copyOf(b.position())
    }

    fun decode(raw: ByteArray): ControlFrame? = runCatching {
        val b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        if (raw.size !in 2..MAX_BYTES || (b.get().toInt() and 255) != MAGIC) return null
        val head = b.get().toInt() and 255
        val version = head ushr 4
        if (version != 1 && version != VERSION) return null
        val frame = when (head and 15) {
            PRESENCE -> {
                val node = NodeId(b.long); val age = b.long; val flags = b.get().toInt()
                val rank = b.get().toInt() and 255; val vehicle = VehicleType.from(b.get().toInt() and 15)
                val fix = getFix(b, flags and 2 != 0, version == VERSION); val known = b.get().toInt() and 255
                if (node.value == 0L || age < 0) return null
                ControlFrame.Presence(node, age, flags and 1 != 0, flags and 2 != 0, rank, vehicle, fix, known)
            }
            QUERY -> {
                val id = getId(b); val fwd = NodeId(b.long); val ttl = b.get().toInt() and 255; val hop = b.get().toInt() and 255
                val radius = b.short.toInt() and 0xffff; val fix = getFix(b, false) ?: return null
                if (ttl > MAX_TTL || hop > MAX_TTL || id.origin.value == 0L || fwd.value == 0L) return null
                ControlFrame.Query(id, fwd, ttl, hop, radius, fix)
            }
            REPLY -> {
                val id = getId(b); val responder = NodeId(b.long); val fwd = NodeId(b.long)
                val ttl = b.get().toInt() and 255; val hop = b.get().toInt() and 255; val rank = b.get().toInt() and 255
                val fix = getFix(b, false, version == VERSION) ?: return null
                val n = b.get().toInt() and 255
                if (n > (if (version == 1) 8 else MAX_RECORDS) || ttl > MAX_TTL || hop > MAX_TTL || id.origin.value == 0L || responder.value == 0L || fwd.value == 0L) return null
                val records = List(n) {
                    val cause = b.get().toInt() and 255; val sub = b.get().toInt() and 255
                    val north = b.short.toInt(); val east = b.short.toInt(); val age = b.short.toInt() and 0xffff
                    val reporters = b.get().toInt() and 255; val conf = b.get().toInt() and 255
                    val kind = HazardKind.from(cause, sub) ?: return null
                    if (conf > 100) return null
                    val origin = if (version == VERSION) getId(b) else null
                    val captured = if (version == VERSION) b.long.takeUnless { it == Long.MIN_VALUE } else null
                    val provenanceCode = if (version == VERSION) b.get().toInt() and 255 else 0
                    val accuracy = if (version == VERSION) (b.get().toInt() and 255).takeUnless { it == 255 }?.toDouble() else null
                    val provenance = Provenance.entries.firstOrNull { it.wire == provenanceCode }
                    if (provenanceCode != 0 && provenance == null || captured != null && captured < 0) return null
                    ControlFrame.Record(kind, north, east, age, reporters.coerceAtLeast(1), if (conf == 0) null else conf / 100.0,
                        origin?.takeUnless { it.origin.value == 0L }, captured, provenance, accuracy)
                }
                ControlFrame.Reply(id, responder, fwd, ttl, hop, rank, fix, records)
            }
            else -> return null
        }
        if (b.hasRemaining()) null else frame
    }.getOrNull()

    private fun putId(b: ByteBuffer, id: MessageId) { b.putLong(id.origin.value); b.putLong(id.session); b.putInt(id.sequence) }
    private fun getId(b: ByteBuffer) = MessageId(NodeId(b.long), b.long, b.int)
    private fun putFix(b: ByteBuffer, f: GeoFix?, uncertainty: Boolean = false) {
        if (f == null || !f.valid()) {
            b.putInt(NO_POS); b.putInt(NO_POS); b.putShort(-1); b.put(0); b.put(0)
            if (uncertainty) { b.putShort(-1); b.put(-1) }
            return
        }
        b.putInt((f.lat * 1e7).roundToInt()); b.putInt((f.lon * 1e7).roundToInt())
        b.putShort((f.speedMps?.let { (it * 100).roundToInt().coerceIn(0, 65_534) } ?: 65_535).toShort())
        b.put((f.heading?.let { ((it % 360) * 256 / 360).roundToInt() and 255 } ?: 0).toByte())
        b.put((if (f.heading == null) 1 else 0).toByte())
        if (uncertainty) {
            b.putShort((f.accuracyM?.takeIf { it.isFinite() && it in 0.0..655.34 }?.let { ceil(it * 100).toInt() } ?: 65_535).toShort())
            b.put((f.headingAccuracyDeg?.takeIf { it.isFinite() && it in 0.0..180.0 }?.let { ceil(it).toInt() } ?: 255).toByte())
        }
    }
    private fun getFix(b: ByteBuffer, synthetic: Boolean, uncertainty: Boolean = false): GeoFix? {
        val lat = b.int; val lon = b.int; val speed = b.short.toInt() and 0xffff; val heading = b.get().toInt() and 255; val noHeading = b.get().toInt() != 0
        val accuracy = if (uncertainty) (b.short.toInt() and 0xffff).takeUnless { it == 65_535 }?.let { it / 100.0 } else null
        val headingAccuracy = if (uncertainty) (b.get().toInt() and 255).takeUnless { it == 255 }?.toDouble() else null
        if (lat == NO_POS || lon == NO_POS) return null
        return GeoFix(lat / 1e7, lon / 1e7, if (noHeading) null else heading * 360.0 / 256, if (speed == 65_535) null else speed / 100.0, synthetic, accuracy, headingAccuracy)
            .takeIf { it.valid() }
    }
}
