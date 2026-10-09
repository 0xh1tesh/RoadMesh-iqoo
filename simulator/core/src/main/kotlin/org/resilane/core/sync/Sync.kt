package org.resilane.core.sync

import org.resilane.core.math.Angles
import org.resilane.core.math.Pose
import org.resilane.core.math.Quat
import org.resilane.core.math.Vec3
import org.resilane.core.proto.Hello
import org.resilane.core.proto.PoseMsg
import org.resilane.core.proto.Tracking

/**
 * NTP-style offset between a peer's wall clock and ours, from PING/PONG round trips. Keeps the
 * sample with the smallest round trip seen recently, since that one has the least queueing error.
 * `peerTime - offsetMs == local time`.
 */
class ClockSync(private val windowMs: Long = 30_000) {
    private data class S(val offset: Long, val rtt: Long, val at: Long)
    private val samples = ArrayDeque<S>()

    @Synchronized
    fun onPong(t0: Long, t1Peer: Long, t3: Long) {
        val rtt = t3 - t0
        if (rtt < 0 || rtt > 5_000) return
        samples.addLast(S(t1Peer - (t0 + rtt / 2), rtt, t3))
        while (samples.size > 64 || (samples.isNotEmpty() && t3 - samples.first().at > windowMs)) samples.removeFirst()
    }

    @get:Synchronized val offsetMs: Long? get() = samples.minByOrNull { it.rtt }?.offset
    @get:Synchronized val rttMs: Long? get() = samples.minByOrNull { it.rtt }?.rtt
}

/** A received pose placed on OUR clock. */
data class TimedPose(val tLocal: Long, val seq: Int, val pose: Pose, val yaw: Float, val tracking: Tracking)

data class InterpolatedPose(
    val pose: Pose,
    val yaw: Float,
    val tracking: Tracking,
    /** True when renderTime is past the newest sample: we hold it rather than extrapolate. */
    val holding: Boolean,
    val newestLocalT: Long,
)

/**
 * Snapshot interpolation. Rendering runs [delayMs] behind real time so there is almost always a
 * received sample on each side of the render time; position is lerped and rotation slerped between
 * them. It never extrapolates: past the newest sample it holds that pose, so a dropped stream
 * freezes the car where it was last confirmed instead of letting it drift.
 */
class PoseInterpolator(private val capacity: Int = 48) {
    private val buf = ArrayList<TimedPose>(capacity)
    private var lastArrival = 0L
    private var meanGap = 80f

    @Synchronized
    fun add(p: TimedPose, arrivalLocal: Long) {
        if (buf.any { it.seq == p.seq }) return
        if (lastArrival != 0L) {
            val gap = (arrivalLocal - lastArrival).coerceIn(1, 2_000).toFloat()
            meanGap = meanGap * 0.9f + gap * 0.1f
        }
        lastArrival = arrivalLocal
        val i = buf.indexOfLast { it.tLocal <= p.tLocal } + 1
        buf.add(i, p)
        while (buf.size > capacity) buf.removeAt(0)
    }

    @Synchronized
    fun clear() { buf.clear(); lastArrival = 0L }

    /** Playout delay adapted to the observed packet interval (1.8 gaps, clamped). */
    @get:Synchronized val delayMs: Long get() = (meanGap * 1.8f).toLong().coerceIn(60, 350)

    @get:Synchronized val newest: TimedPose? get() = buf.lastOrNull()

    @Synchronized
    fun sample(renderTime: Long): InterpolatedPose? {
        if (buf.isEmpty()) return null
        val last = buf.last()
        if (renderTime >= last.tLocal) return InterpolatedPose(last.pose, last.yaw, last.tracking, true, last.tLocal)
        val first = buf.first()
        if (renderTime <= first.tLocal) return InterpolatedPose(first.pose, first.yaw, first.tracking, false, last.tLocal)
        val j = buf.indexOfFirst { it.tLocal > renderTime }
        val a = buf[j - 1]
        val b = buf[j]
        val s = ((renderTime - a.tLocal).toFloat() / (b.tLocal - a.tLocal).coerceAtLeast(1)).coerceIn(0f, 1f)
        // Only interpolate between confirmed poses; a degraded endpoint is held, not blended into.
        if (b.tracking != Tracking.TRACKING || a.tracking != Tracking.TRACKING) {
            val pick = if (s < 0.5f) a else b
            return InterpolatedPose(pick.pose, pick.yaw, pick.tracking, false, last.tLocal)
        }
        return InterpolatedPose(Pose.lerp(a.pose, b.pose, s), Angles.lerpAngle(a.yaw, b.yaw, s), Tracking.TRACKING, false, last.tLocal)
    }
}

enum class NodeState { LIVE, DEGRADED, UNCALIBRATED, LOST }

data class StateThresholds(val degradedAfterMs: Long = 700, val lostAfterMs: Long = 5_000)

class RemoteNode(val id: Long, val simulated: Boolean = false) {
    @Volatile var hello: Hello? = null
    val interp = PoseInterpolator()
    val clock = ClockSync()

    @Volatile var lastPose: PoseMsg? = null
    @Volatile var lastRecvLocal = 0L
    @Volatile var received = 0L
    @Volatile var seqGaps = 0L
    @Volatile var rateHz = 0f
    @Volatile var lastPacketAgeMs: Long? = null
    @Volatile var via: String = ""
    private var lastSeq = -1

    val name get() = hello?.name ?: "node-%04x".format(id and 0xFFFF)

    fun state(now: Long, th: StateThresholds = StateThresholds()): NodeState {
        val p = lastPose ?: return NodeState.LOST
        val age = now - lastRecvLocal
        return when {
            age > th.lostAfterMs -> NodeState.LOST
            !p.calibrated -> NodeState.UNCALIBRATED
            age > th.degradedAfterMs || p.tracking != Tracking.TRACKING -> NodeState.DEGRADED
            else -> NodeState.LIVE
        }
    }

    @Synchronized
    fun onPose(m: PoseMsg, nowLocal: Long, via: String) {
        if (lastSeq >= 0 && m.poseSeq > lastSeq + 1) seqGaps += (m.poseSeq - lastSeq - 1)
        if (m.poseSeq > lastSeq || m.poseSeq < lastSeq - 1000) lastSeq = m.poseSeq
        if (lastRecvLocal != 0L) {
            val dt = (nowLocal - lastRecvLocal).coerceAtLeast(1)
            rateHz = rateHz * 0.85f + (1000f / dt) * 0.15f
        }
        lastRecvLocal = nowLocal
        received++
        lastPose = m
        this.via = via
        val off = clock.offsetMs
        // With a measured clock offset use the sender's capture time (no network jitter);
        // without one, fall back to arrival time.
        val tLocal = if (off != null) m.timeMs - off else nowLocal
        lastPacketAgeMs = if (off != null) nowLocal - tLocal else null
        if (m.calibrated) interp.add(TimedPose(tLocal, m.poseSeq, Pose(m.position, m.rotation), m.yaw, m.tracking), nowLocal)
        else interp.clear()
    }
}

/** All peers this node has heard of. Thread-safe; network threads write, the render thread reads. */
class NodeRegistry(val selfId: Long) {
    private val nodes = LinkedHashMap<Long, RemoteNode>()

    @Synchronized
    fun getOrCreate(id: Long, simulated: Boolean = false): RemoteNode? =
        if (id == selfId) null else nodes.getOrPut(id) { RemoteNode(id, simulated) }

    @Synchronized fun get(id: Long) = nodes[id]
    @Synchronized fun remove(id: Long) = nodes.remove(id)
    @Synchronized fun all(): List<RemoteNode> = nodes.values.toList()
    @Synchronized fun removeSimulated() { nodes.values.removeAll { it.simulated } }

    /** Drops real peers not heard from in [forgetAfterMs]; simulated test nodes stay until cleared. */
    @Synchronized
    fun pruneLost(now: Long, forgetAfterMs: Long = 60_000) {
        nodes.values.removeAll { !it.simulated && it.lastRecvLocal != 0L && now - it.lastRecvLocal > forgetAfterMs }
    }
}

/** Vehicle placement modes from the brief. */
enum class RenderMode { GROUND_LOCKED, FREE_3D }

/** Offset from the participant's phone to the car's centre, in the car's own frame. Metres. */
data class VehicleOffset(val right: Float = 0f, val up: Float = 0f, val forward: Float = 0f)

object VehiclePlacement {
    /**
     * World pose of the car's ground-centre for a node whose phone is at [phone] with heading [yaw].
     * GROUND_LOCKED drops the car onto the shared ground plane (y = [groundY]) and keeps only the
     * horizontal heading; the phone's real height is still tracked and reported separately.
     * FREE_3D puts the car exactly at the phone pose (debug).
     */
    fun place(phone: Pose, yaw: Float, mode: RenderMode, offset: VehicleOffset, groundY: Float = 0f): Pose {
        // Car frame: +Z forward, +Y up, right = -X.
        val local = Vec3(-offset.right, offset.up, offset.forward)
        return when (mode) {
            RenderMode.GROUND_LOCKED -> {
                val q = Quat.rotY(yaw)
                val base = Vec3(phone.t.x, groundY, phone.t.z)
                Pose(base + q.rotate(local), q)
            }
            RenderMode.FREE_3D -> {
                // Camera looks down -Z; turning it half a turn about its Y makes the car face the same way.
                val q = (phone.q * Quat.rotY(Angles.PI)).normalized()
                Pose(phone.t + q.rotate(local), q)
            }
        }
    }
}
