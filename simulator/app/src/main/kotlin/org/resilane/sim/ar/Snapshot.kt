package org.resilane.sim.ar

import org.resilane.core.math.Pose
import org.resilane.core.math.Vec3
import org.resilane.core.sync.NodeState
import org.resilane.core.vision.Box

/** One remote (or simulated) node as this phone currently sees it. Screen coordinates in view px. */
class VehicleView(
    val nodeId: Long,
    val name: String,
    val color: Int,
    val state: NodeState,
    val simulated: Boolean,
    /** Shared-world pose of the other phone (not the car): what distance/altitude are measured on. */
    val phoneWorld: Vec3,
    val headingDeg: Float,
    val yaw: Float,
    val distanceM: Float,
    val horizontalDistanceM: Float,
    val altitudeM: Float,
    val packetAgeMs: Long?,
    val rateHz: Float,
    val scale: Float,
    /** Point above the car roof for the label, or null if behind the camera. */
    val labelPt: FloatArray?,
    /** Tight screen box of the rendered car (simulator ground truth), or null if not in view. */
    val gtBox: Box?,
    /** Car centre on the ground and a point 1.5 m ahead of it, for the heading arrow. */
    val groundPt: FloatArray?,
    val aheadPt: FloatArray?,
    /** Direction toward the node in screen space (x right, y down), for off-screen pointers. */
    val screenDir: FloatArray,
)

class Snapshot(
    val frameId: Long,
    val timeMs: Long,
    val width: Int,
    val height: Int,
    val tracking: String,
    val trackingReason: String,
    val calibrated: Boolean,
    val calibMsg: String,
    val epoch: Int,
    val residual: Pair<Float, Float>?,
    val markerVisible: Boolean,
    val myWorld: Pose?,
    /** This node's vehicle heading (joystick, or camera when it is off), as relayed to peers. */
    val myHeadingDeg: Float,
    val myYaw: Float,
    /** Where the camera looks along the ground, world yaw: what the radar is oriented by. */
    val myViewYaw: Float,
    val mySpeedMps: Float,
    val vehicles: List<VehicleView>,
    /** Screen points of the shared origin and of 1 m along +X, +Z (debug axes), or null. */
    val axes: Array<FloatArray?>?,
    val fps: Float,
    val floorOffsetM: Float?,
    /** "ARCORE" (6-DoF) or "3DOF" (no ARCore: camera + rotation sensor only). */
    val mode: String,
    /** Why the phone is in a degraded mode, shown on screen; null when all is well. */
    val notice: String?,
)
