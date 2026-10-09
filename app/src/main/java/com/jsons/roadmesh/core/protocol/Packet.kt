package com.jsons.roadmesh.core.protocol

/** Packet class carried in the low nibble of byte 1. PING and PONG use a different body and are added later. */
enum class PacketClass(val code: Int) {
    ALERT(1),
    PRESENCE(2);

    companion object {
        fun fromCode(code: Int): PacketClass? = entries.firstOrNull { it.code == code }
    }
}

/** Flag bits (low 4 bits of byte 7). */
object Flags {
    const val PROXY = 1
    const val SYNTH_POS = 2
    const val NO_HEADING = 4
    const val SIGNED = 8
}

/**
 * Protocol v2 core packet, 27 bytes on air. See docs/architecture.md section 3.
 * Positions are degrees times 1e7. Speed is in 0.01 m/s (0xFFFF = unknown). Heading is 360/256 degree steps.
 */
data class Packet(
    val cls: PacketClass,
    val senderId: Int,
    val seq: Int,
    val ttl: Int,
    val hop: Int,
    val flags: Int,
    val tGenDeci: Int,
    val latE7: Int,
    val lonE7: Int,
    val speedCms: Int,
    val headingQ: Int,
    val cause: Int,
    val subCause: Int,
    val p1: Int,
    val p2: Int,
    val lastHop: Int,
    val validityS: Int,
) {
    /** The dedup key: sender and sequence number together. */
    val msgKey: Long get() = (senderId.toLong() shl 16) or seq.toLong()

    /** The same packet with positions rounded to about 1 m (1e-5 degrees), as sent on air. */
    fun quantised(): Packet = copy(latE7 = roundTo(latE7, POS_STEP), lonE7 = roundTo(lonE7, POS_STEP))

    companion object {
        const val SIZE = 27
        const val MAGIC = 0x52
        const val VERSION = 2
        const val SPEED_UNKNOWN = 0xFFFF
        const val T_GEN_MAX = 35_999
        private const val POS_STEP = 100

        private fun roundTo(value: Int, step: Int): Int =
            Math.floorDiv(value + step / 2, step) * step
    }
}
