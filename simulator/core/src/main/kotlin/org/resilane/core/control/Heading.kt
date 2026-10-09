package org.resilane.core.control

import org.resilane.core.math.Angles
import org.resilane.core.math.Pose
import org.resilane.core.math.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The direction this node's vehicle faces, set with the on-screen joystick instead of being read
 * off the camera. Position still comes only from tracking: the joystick never moves the car.
 *
 * The stick is relative to what the user is looking at: push up = straight ahead along the ground
 * as seen on screen, push right = to its right. While the stick is held the heading follows it (so
 * turning the phone with a thumb on the stick steers too); on release it is latched and stays put
 * however the phone is turned. Until the stick is first used, the car faces where the camera looks.
 *
 * The latched direction is kept in the TRACKING frame (ARCore session, or the 3-DoF sensor frame),
 * which never changes while the app runs, so recalibrating the shared frame does not swing the car
 * round. [toWorld] converts it into whatever shared frame is current.
 */
class HeadingControl(private val deadzone: Float = 0.25f) {
    @Volatile private var stickX = 0f
    @Volatile private var stickY = 0f
    private var trackingYaw: Float? = null

    /** From the UI thread. Screen axes (x right, y down), each in [-1, 1]; (0, 0) = released. */
    fun setStick(x: Float, y: Float) { stickX = x; stickY = y }

    val engaged get() = stickX * stickX + stickY * stickY >= deadzone * deadzone

    /** Call once per tracked frame with the display-oriented camera pose in the tracking frame. */
    @Synchronized
    fun update(trackingFromCamera: Pose): Float {
        val view = groundForwardYaw(trackingFromCamera)
        val x = stickX; val y = stickY
        // Stick angle clockwise from "up"; a clockwise turn is a negative yaw (see Geometry.kt).
        if (x * x + y * y >= deadzone * deadzone) trackingYaw = Angles.wrapPi(view - atan2(x, -y))
        else if (trackingYaw == null) trackingYaw = view
        return trackingYaw!!
    }

    /** Forget the latched heading: the next [update] faces the camera direction again. */
    @Synchronized
    fun reset() { trackingYaw = null }

    companion object {
        /**
         * Yaw of "straight ahead on the ground" as the user sees the screen. Held upright that is
         * the camera's forward; pointed at the floor the forward is vertical, so the screen's top
         * edge (camera up) takes over, blended in by how far the camera looks down.
         */
        fun groundForwardYaw(cam: Pose): Float {
            val f = cam.cameraForward()
            val up = cam.rotateVector(Vec3.Y)
            return Angles.yawOf(f + up * maxOf(0f, -f.y))
        }

        /** A tracking-frame yaw expressed in the frame [worldFromTracking] maps into. */
        fun toWorld(trackingYaw: Float, worldFromTracking: Pose): Float =
            Angles.yawOf(worldFromTracking.rotateVector(Vec3(sin(trackingYaw), 0f, cos(trackingYaw))))
    }
}
