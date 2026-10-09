package org.resilane.core.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Conventions used everywhere in ResiLane:
 *  - Right-handed, Y up, metres, radians internally.
 *  - A camera looks down its local -Z.
 *  - A vehicle model faces its local +Z, with +Y up (glTF convention), so its right side is -X.
 *  - "yaw" is a rotation about +Y; yaw 0 maps +Z onto +Z. rotY(yaw) maps +Z to (sin yaw, 0, cos yaw).
 *  - "heading" is what people read: degrees clockwise (seen from above) from the shared world's -Z,
 *    which is the "TOP" edge of the printed marker. Heading 90 = toward the marker's +X edge.
 */

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun horizontalLength() = sqrt(x * x + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) ZERO else this * (1f / l)
    }
    fun distanceTo(o: Vec3) = (this - o).length()
    fun horizontalDistanceTo(o: Vec3) = (this - o).horizontalLength()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val X = Vec3(1f, 0f, 0f)
        val Y = Vec3(0f, 1f, 0f)
        val Z = Vec3(0f, 0f, 1f)
        fun lerp(a: Vec3, b: Vec3, t: Float) = Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    }
}

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    /** Hamilton product: (this * o) applies o first, then this. */
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun conjugate() = Quat(-x, -y, -z, w)

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-9f) IDENTITY else Quat(x / l, y / l, z / l, w / l)
    }

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q x v) + 2 q x (q x v)
        val q = Vec3(x, y, z)
        val t = q.cross(v) * 2f
        return v + t * w + q.cross(t)
    }

    fun dot(o: Quat) = x * o.x + y * o.y + z * o.z + w * o.w

    /** Angle of the rotation taking this to [o], in radians, in [0, pi]. */
    fun angleTo(o: Quat): Float = (2f * acos(abs(dot(o)).coerceAtMost(1f)))

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun axisAngle(axis: Vec3, angle: Float): Quat {
            val a = axis.normalized()
            val s = sin(angle / 2f)
            return Quat(a.x * s, a.y * s, a.z * s, cos(angle / 2f))
        }

        fun rotY(yaw: Float) = Quat(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))

        fun slerp(a: Quat, bIn: Quat, t: Float): Quat {
            var b = bIn
            var d = a.dot(b)
            if (d < 0f) { b = Quat(-b.x, -b.y, -b.z, -b.w); d = -d }
            if (d > 0.9995f) {
                return Quat(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t).normalized()
            }
            val th0 = acos(d)
            val th = th0 * t
            val s0 = cos(th) - d * sin(th) / sin(th0)
            val s1 = sin(th) / sin(th0)
            return Quat(a.x * s0 + b.x * s1, a.y * s0 + b.y * s1, a.z * s0 + b.z * s1, a.w * s0 + b.w * s1)
        }
    }
}

/**
 * A rigid transform. Named by what it maps: `aFromB` takes coordinates expressed in frame B and
 * returns them in frame A, so `aFromB.compose(bFromC) == aFromC`.
 */
data class Pose(val t: Vec3, val q: Quat) {
    fun compose(o: Pose) = Pose(t + q.rotate(o.t), (q * o.q).normalized())
    fun inverse(): Pose {
        val qi = q.conjugate()
        return Pose(-qi.rotate(t), qi)
    }
    fun transformPoint(p: Vec3) = t + q.rotate(p)
    fun rotateVector(v: Vec3) = q.rotate(v)

    /** Camera forward (local -Z) in the parent frame. */
    fun cameraForward() = q.rotate(Vec3(0f, 0f, -1f))

    /** Column-major 4x4 for OpenGL. */
    fun toMatrix(out: FloatArray = FloatArray(16), scale: Float = 1f): FloatArray {
        val (x, y, z, w) = q
        out[0] = (1 - 2 * (y * y + z * z)) * scale; out[1] = (2 * (x * y + z * w)) * scale; out[2] = (2 * (x * z - y * w)) * scale; out[3] = 0f
        out[4] = (2 * (x * y - z * w)) * scale; out[5] = (1 - 2 * (x * x + z * z)) * scale; out[6] = (2 * (y * z + x * w)) * scale; out[7] = 0f
        out[8] = (2 * (x * z + y * w)) * scale; out[9] = (2 * (y * z - x * w)) * scale; out[10] = (1 - 2 * (x * x + y * y)) * scale; out[11] = 0f
        out[12] = t.x; out[13] = t.y; out[14] = t.z; out[15] = 1f
        return out
    }

    companion object {
        val IDENTITY = Pose(Vec3.ZERO, Quat.IDENTITY)
        fun lerp(a: Pose, b: Pose, s: Float) = Pose(Vec3.lerp(a.t, b.t, s), Quat.slerp(a.q, b.q, s))
    }
}

object Angles {
    const val PI = Math.PI.toFloat()
    const val TWO_PI = (2 * Math.PI).toFloat()

    fun wrapPi(a: Float): Float {
        var r = a % TWO_PI
        if (r > PI) r -= TWO_PI
        if (r < -PI) r += TWO_PI
        return r
    }

    fun lerpAngle(a: Float, b: Float, t: Float) = wrapPi(a + wrapPi(b - a) * t)

    /** Yaw (about +Y) that turns +Z toward the horizontal part of [forward]. NaN-safe: 0 if vertical. */
    fun yawOf(forward: Vec3): Float =
        if (forward.horizontalLength() < 1e-6f) 0f else atan2(forward.x, forward.z)

    /** Human heading in degrees [0, 360): 0 = world -Z (marker TOP), 90 = world +X. */
    fun headingDegOfYaw(yaw: Float): Float {
        // forward = (sin yaw, 0, cos yaw); heading = atan2(fx, -fz)
        val d = Math.toDegrees(atan2(sin(yaw), -cos(yaw)).toDouble()).toFloat()
        return (d + 360f) % 360f
    }

    fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()
}
