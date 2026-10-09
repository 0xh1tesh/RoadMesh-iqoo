package com.jsons.roadmesh.core.protocol

/** Result of decoding raw advertising bytes. Decoding never throws. */
sealed interface DecodeResult {
    data class Ok(val packet: Packet) : DecodeResult
    data class Error(val reason: String) : DecodeResult
}

object PacketCodec {

    fun encode(p: Packet): ByteArray {
        require(p.senderId in 0..0xFFFFFF) { "senderId" }
        require(p.seq in 0..0xFFFF) { "seq" }
        require(p.ttl in 0..3 && p.hop in 0..3) { "ttl/hop" }
        require(p.flags in 0..15) { "flags" }
        require(p.tGenDeci in 0..Packet.T_GEN_MAX) { "tGen" }
        require(p.speedCms in 0..0xFFFF) { "speed" }
        require(p.headingQ in 0..255) { "heading" }
        require(p.cause in 0..255 && p.subCause in 0..255) { "cause" }
        require(p.p1 in 0..255 && p.p2 in 0..255) { "p1/p2" }
        require(p.lastHop in 0..255 && p.validityS in 0..255) { "lastHop/validity" }

        val b = ByteArray(Packet.SIZE)
        b[0] = Packet.MAGIC.toByte()
        b[1] = ((Packet.VERSION shl 4) or p.cls.code).toByte()
        putU24(b, 2, p.senderId)
        putU16(b, 5, p.seq)
        b[7] = ((p.ttl shl 6) or (p.hop shl 4) or p.flags).toByte()
        putU16(b, 8, p.tGenDeci)
        putI32(b, 10, p.latE7)
        putI32(b, 14, p.lonE7)
        putU16(b, 18, p.speedCms)
        b[20] = p.headingQ.toByte()
        b[21] = p.cause.toByte()
        b[22] = p.subCause.toByte()
        b[23] = p.p1.toByte()
        b[24] = p.p2.toByte()
        b[25] = p.lastHop.toByte()
        b[26] = p.validityS.toByte()
        return b
    }

    fun decode(raw: ByteArray?): DecodeResult {
        if (raw == null) return DecodeResult.Error("null")
        if (raw.size != Packet.SIZE) return DecodeResult.Error("size ${raw.size}")
        if (u8(raw, 0) != Packet.MAGIC) return DecodeResult.Error("magic")
        val version = u8(raw, 1) ushr 4
        if (version != Packet.VERSION) return DecodeResult.Error("version $version")
        val cls = PacketClass.fromCode(u8(raw, 1) and 0x0F)
            ?: return DecodeResult.Error("class ${u8(raw, 1) and 0x0F}")
        val tGen = u16(raw, 8)
        if (tGen > Packet.T_GEN_MAX) return DecodeResult.Error("tGen $tGen")
        val b7 = u8(raw, 7)
        return DecodeResult.Ok(
            Packet(
                cls = cls,
                senderId = u24(raw, 2),
                seq = u16(raw, 5),
                ttl = b7 ushr 6,
                hop = (b7 ushr 4) and 3,
                flags = b7 and 15,
                tGenDeci = tGen,
                latE7 = i32(raw, 10),
                lonE7 = i32(raw, 14),
                speedCms = u16(raw, 18),
                headingQ = u8(raw, 20),
                cause = u8(raw, 21),
                subCause = u8(raw, 22),
                p1 = u8(raw, 23),
                p2 = u8(raw, 24),
                lastHop = u8(raw, 25),
                validityS = u8(raw, 26),
            )
        )
    }

    /**
     * What a relay changes: ttl down by one, hop up by one, lastHop set to the relayer.
     * Returns null when the packet cannot be relayed (ttl is zero or three hops are used).
     */
    fun forRelay(p: Packet, relayId: Int): Packet? =
        if (p.ttl <= 0 || p.hop >= 3) null
        else p.copy(ttl = p.ttl - 1, hop = p.hop + 1, lastHop = relayId and 0xFF)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u24(b: ByteArray, i: Int) = (u8(b, i) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i + 2)
    private fun i32(b: ByteArray, i: Int) =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)

    private fun putU16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte()
    }

    private fun putU24(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 16).toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = v.toByte()
    }

    private fun putI32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
    }
}
