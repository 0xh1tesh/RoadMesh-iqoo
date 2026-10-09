package org.resilane.core.proto

import org.resilane.core.math.Quat
import org.resilane.core.math.Vec3
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ResiLane wire protocol v1. Little-endian binary, one message per datagram/payload.
 *
 * Every message is an [Envelope]: who originated it, a per-origin message sequence number and a
 * hop budget (ttl). That is the minimum an event-relay mesh (MotorMesh) needs to forward and
 * de-duplicate traffic, so this format can be carried over a multi-hop relay later without change;
 * today every node is one hop away and ttl is 1.
 *
 * Header (18 bytes): magic "RL" | version u8 | type u8 | ttl u8 | flags u8 | origin i64 | msgSeq i32
 */
object Protocol {
    const val MAGIC: Short = 0x4C52 // "RL" little-endian
    const val VERSION: Byte = 1
    const val HEADER_BYTES = 18
    const val MAX_BYTES = 1024
}

enum class MsgType(val code: Byte) {
    HELLO(1), POSE(2), SCALE_SYNC(3), PING(4), PONG(5), BYE(6);
    companion object { fun of(c: Byte) = entries.firstOrNull { it.code == c } }
}

/** Sender's own tracking state, as ARCore reports it. */
enum class Tracking(val code: Byte) {
    TRACKING(0), PAUSED(1), STOPPED(2);
    companion object { fun of(c: Byte) = entries.firstOrNull { it.code == c } ?: STOPPED }
}

data class Envelope(
    val origin: Long,
    val msgSeq: Int,
    val ttl: Int,
    val body: Message,
)

sealed interface Message {
    val type: MsgType
}

/** Node identity and appearance. Sent on connect, on change, and every few seconds. */
data class Hello(
    val name: String,
    val modelId: Int,
    val colorArgb: Int,
    /** The scale this node asks others to draw its vehicle at. */
    val modelScale: Float,
    /** Physical marker width this node calibrated with, mm. Mismatches are flagged in the HUD. */
    val markerWidthMm: Float,
    val appVersion: String,
) : Message { override val type get() = MsgType.HELLO }

/** One pose sample in the SHARED WORLD frame (never raw session coordinates). */
data class PoseMsg(
    val poseSeq: Int,
    /** Sender wall clock (ms since epoch) when the camera frame was captured. */
    val timeMs: Long,
    val tracking: Tracking,
    val calibrated: Boolean,
    val calibEpoch: Int,
    /** Device (camera) position in the shared world, metres. y = height above shared ground. */
    val position: Vec3,
    /** Device (display-oriented camera) rotation in the shared world. */
    val rotation: Quat,
    /**
     * Vehicle heading as a yaw about +Y, radians (see math conventions). Set with the joystick
     * (HeadingControl), or the camera's ground direction when the joystick is off; independent of
     * [rotation], which stays the camera's. Receivers face the car this way in ground-locked mode.
     */
    val yaw: Float,
) : Message { override val type get() = MsgType.POSE }

/** Fleet-wide vehicle scale, last-writer-wins on [issuedMs]. */
data class ScaleSync(val scale: Float, val issuedMs: Long) : Message { override val type get() = MsgType.SCALE_SYNC }

data class Ping(val t0: Long) : Message { override val type get() = MsgType.PING }

/** Reply to [target]'s ping: echoes t0 and adds this node's clock reading t1. */
data class Pong(val target: Long, val t0: Long, val t1: Long) : Message { override val type get() = MsgType.PONG }

object Bye : Message { override val type get() = MsgType.BYE }

object Codec {
    fun encode(e: Envelope): ByteArray {
        val b = ByteBuffer.allocate(Protocol.MAX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(Protocol.MAGIC).put(Protocol.VERSION).put(e.body.type.code)
        b.put(e.ttl.coerceIn(0, 255).toByte()).put(0).putLong(e.origin).putInt(e.msgSeq)
        when (val m = e.body) {
            is Hello -> {
                putStr(b, m.name, 32); b.put(m.modelId.toByte()); b.putInt(m.colorArgb)
                b.putFloat(m.modelScale); b.putFloat(m.markerWidthMm); putStr(b, m.appVersion, 24)
            }
            is PoseMsg -> {
                b.putInt(m.poseSeq).putLong(m.timeMs).put(m.tracking.code)
                b.put(if (m.calibrated) 1 else 0).putShort(m.calibEpoch.toShort())
                b.putFloat(m.position.x).putFloat(m.position.y).putFloat(m.position.z)
                b.putFloat(m.rotation.x).putFloat(m.rotation.y).putFloat(m.rotation.z).putFloat(m.rotation.w)
                b.putFloat(m.yaw)
            }
            is ScaleSync -> b.putFloat(m.scale).putLong(m.issuedMs)
            is Ping -> b.putLong(m.t0)
            is Pong -> b.putLong(m.target).putLong(m.t0).putLong(m.t1)
            Bye -> {}
        }
        return b.array().copyOf(b.position())
    }

    /** Null for anything that is not a well-formed v1 ResiLane message; never throws. */
    fun decode(bytes: ByteArray, len: Int = bytes.size): Envelope? = try {
        val b = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        if (len < Protocol.HEADER_BYTES || b.short != Protocol.MAGIC || b.get() != Protocol.VERSION) null
        else {
            val type = MsgType.of(b.get())
            val ttl = b.get().toInt() and 0xFF
            b.get() // flags, reserved
            val origin = b.long
            val seq = b.int
            val body: Message? = when (type) {
                MsgType.HELLO -> Hello(getStr(b), b.get().toInt() and 0xFF, b.int, b.float, b.float, getStr(b))
                MsgType.POSE -> PoseMsg(
                    poseSeq = b.int, timeMs = b.long, tracking = Tracking.of(b.get()),
                    calibrated = b.get().toInt() != 0, calibEpoch = b.short.toInt() and 0xFFFF,
                    position = Vec3(b.float, b.float, b.float),
                    rotation = Quat(b.float, b.float, b.float, b.float), yaw = b.float,
                )
                MsgType.SCALE_SYNC -> ScaleSync(b.float, b.long)
                MsgType.PING -> Ping(b.long)
                MsgType.PONG -> Pong(b.long, b.long, b.long)
                MsgType.BYE -> Bye
                null -> null
            }
            body?.let { Envelope(origin, seq, ttl, it) }
        }
    } catch (_: BufferUnderflowException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun putStr(b: ByteBuffer, s: String, max: Int) {
        var bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size > max) bytes = bytes.copyOf(max)
        b.put(bytes.size.toByte()).put(bytes)
    }

    private fun getStr(b: ByteBuffer): String {
        val n = b.get().toInt() and 0xFF
        val a = ByteArray(n)
        b.get(a)
        return String(a, Charsets.UTF_8)
    }
}

/**
 * Per-origin replay window (like IPsec anti-replay): accepts each (origin, msgSeq) once even when
 * the same message arrives over two transports or via a relay, and tolerates reordering within 64.
 */
class DedupeWindow {
    private class W(var top: Int, var bits: Long)
    private val windows = HashMap<Long, W>()

    @Synchronized
    fun firstSeen(origin: Long, seq: Int): Boolean {
        val w = windows[origin] ?: run { windows[origin] = W(seq, 1L); return true }
        val d = seq - w.top
        return when {
            d > 0 -> { w.bits = if (d >= 64) 1L else (w.bits shl d) or 1L; w.top = seq; true }
            d > -64 -> {
                val mask = 1L shl (-d)
                if (w.bits and mask != 0L) false else { w.bits = w.bits or mask; true }
            }
            // Far behind: most likely the peer restarted and its counter reset. Start over.
            else -> { w.top = seq; w.bits = 1L; true }
        }
    }

    @Synchronized
    fun forget(origin: Long) { windows.remove(origin) }
}
