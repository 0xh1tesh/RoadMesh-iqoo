package org.resilane.sim.ar

import org.resilane.core.math.Angles
import org.resilane.core.math.Pose
import org.resilane.core.math.Quat
import org.resilane.core.math.Vec3
import org.resilane.core.proto.Hello
import org.resilane.core.proto.PoseMsg
import org.resilane.core.proto.Tracking
import org.resilane.core.sync.NodeRegistry
import org.resilane.sim.RLog
import org.resilane.sim.Settings
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simulated test vehicles for one-phone testing (brief priority 1). They are fed through exactly
 * the same path as real peers (registry, interpolation, placement, rendering, YOLO scoring) at the
 * configured pose rate; only the source differs. They are never sent on the network and are
 * labelled SIM on screen.
 */
class SimPeers(private val registry: NodeRegistry, private val settings: Settings) {
    enum class Motion { PARKED, CIRCLE, SHUTTLE, BRAKE }

    private class Car(val id: Long, val anchor: Vec3, val facing: Float, var motion: Motion, val t0: Long) {
        var seq = 0
        var lastSent = 0L
    }

    private val cars = ArrayList<Car>()
    private var nextId = 0x5100_0000_0000_0001L

    val count get() = synchronized(cars) { cars.size }

    /** Places a test car 4 m ahead of the viewer, facing them. */
    fun spawn(viewer: Pose, motion: Motion, facingAway: Boolean = false) {
        if (count >= 16) return
        val f = viewer.cameraForward().let { Vec3(it.x, 0f, it.z).normalized() }.let { if (it.length() < 0.5f) Vec3(0f, 0f, -1f) else it }
        val pos = Vec3(viewer.t.x, 1.4f, viewer.t.z) + f * 4f
        val facing = Angles.yawOf(if (facingAway) f else -f)
        val id = nextId++
        synchronized(cars) { cars += Car(id, pos, facing, motion, android.os.SystemClock.elapsedRealtime()) }
        val i = synchronized(cars) { cars.size }
        registry.getOrCreate(id, simulated = true)?.hello =
            Hello("SIM-$i ${motion.name.lowercase()}", 0, Settings.PALETTE[(i + 1) % Settings.PALETTE.size], settings.myVehicleScale, settings.markerWidthM * 1000, "sim")
        RLog.i("Sim", "spawned SIM-$i ($motion) at (%.2f, %.2f) world".format(pos.x, pos.z))
    }

    fun clear() {
        synchronized(cars) { cars.clear() }
        registry.removeSimulated()
    }

    fun brake(viewer: Pose) {
        clear()
        spawn(viewer, Motion.BRAKE)
    }

    fun isBraking(id: Long): Boolean = synchronized(cars) {
        cars.any { it.id == id && it.motion == Motion.BRAKE && android.os.SystemClock.elapsedRealtime() - it.t0 < 5000 }
    }

    fun tick(now: Long) {
        val motionNow = android.os.SystemClock.elapsedRealtime()
        val periodMs = 1000L / settings.poseRateHz.coerceIn(1, 60)
        val list = synchronized(cars) { cars.toList() }
        for (c in list) {
            if (motionNow - c.lastSent < periodMs) continue
            c.lastSent = motionNow
            val t = (motionNow - c.t0) / 1000f
            val (pos, yaw) = when (c.motion) {
                Motion.BRAKE -> {
                    val direction = Quat.rotY(c.facing).rotate(Vec3.Z)
                    (c.anchor - direction * org.resilane.core.control.Braking.sample(t).distanceM) to Angles.wrapPi(c.facing + Angles.PI)
                }
                Motion.PARKED -> c.anchor to c.facing
                Motion.CIRCLE -> {
                    // 1.5 m radius at 0.6 m/s, heading along the tangent.
                    val w = 0.6f / 1.5f
                    val a = t * w
                    Vec3(c.anchor.x + 1.5f * sin(a), c.anchor.y, c.anchor.z + 1.5f * cos(a)) to Angles.wrapPi(a + Angles.PI / 2)
                }
                Motion.SHUTTLE -> {
                    // 3 m back and forth along its facing, turning around at each end.
                    val phase = (t * 0.5f / 3f) % 2f
                    val s = if (phase < 1f) phase else 2f - phase
                    val dir = Quat.rotY(c.facing).rotate(Vec3.Z)
                    (c.anchor + dir * (s * 3f - 1.5f)) to (if (phase < 1f) c.facing else Angles.wrapPi(c.facing + Angles.PI))
                }
            }
            // Camera looks down -Z, so a phone with heading `yaw` is rotY(yaw + pi).
            val q = Quat.rotY(yaw + Angles.PI)
            registry.getOrCreate(c.id, simulated = true)?.onPose(
                PoseMsg(c.seq++, now, Tracking.TRACKING, true, 1, pos, q, yaw), now, "sim")
        }
    }

    /** Simulated cars follow the fleet scale like real nodes do. */
    fun setScale(scale: Float) {
        val ids = synchronized(cars) { cars.map { it.id } }
        for (id in ids) registry.get(id)?.let { n -> n.hello = n.hello?.copy(modelScale = scale) }
    }

    fun cycleMotion() {
        synchronized(cars) { cars.forEach { it.motion = Motion.entries[(it.motion.ordinal + 1) % Motion.entries.size] } }
    }
}
