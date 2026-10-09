package org.resilane.core.frame

import org.resilane.core.math.Angles
import org.resilane.core.math.Pose
import org.resilane.core.math.Quat
import org.resilane.core.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The shared world frame every phone agrees on, derived from one stationary printed marker.
 *
 * Each phone's ARCore session has its own arbitrary origin and yaw (only gravity is common), so
 * raw session poses are meaningless to another phone. Every phone instead observes the same marker
 * and defines:
 *
 *   origin  = marker centre, dropped by `markerHeightM` so world y = 0 is the ground
 *   +Y      = up (gravity; ARCore session frames are already gravity-aligned)
 *   +X      = the marker's +X edge, projected onto the horizontal plane
 *   +Z      = X cross Y (toward the marker's bottom edge when it lies flat)
 *
 * Using only the marker's yaw, never its tilt, means a slightly warped or tilted print cannot tip
 * the world over: up stays gravity on every phone.
 *
 * Everything sent over the network is in this frame. `sessionFromWorld` converts back for drawing.
 */
object SharedFrame {

    /** One observation of the marker centre pose in this phone's session frame. */
    data class MarkerSample(val sessionFromMarker: Pose, val timeMs: Long)

    data class Estimate(
        val sessionFromWorld: Pose,
        val samples: Int,
        /** RMS distance of the sample centres from their mean, metres. */
        val positionSpreadM: Float,
        /** Largest deviation of any sample's yaw from the circular mean, degrees. */
        val yawSpreadDeg: Float,
        /** Angle between the marker normal and up, degrees. 0 = lying flat. */
        val tiltDeg: Float,
        val spanMs: Long,
    )

    data class Gate(
        val minSamples: Int = 20,
        val minSpanMs: Long = 700,
        val maxPositionSpreadM: Float = 0.02f,
        val maxYawSpreadDeg: Float = 2.0f,
    ) {
        fun accepts(e: Estimate) = e.samples >= minSamples && e.spanMs >= minSpanMs &&
            e.positionSpreadM <= maxPositionSpreadM && e.yawSpreadDeg <= maxYawSpreadDeg

        fun whyNot(e: Estimate?): String = when {
            e == null -> "marker not seen yet"
            e.samples < minSamples -> "collecting ${e.samples}/$minSamples"
            e.spanMs < minSpanMs -> "collecting (${e.spanMs} ms)"
            e.positionSpreadM > maxPositionSpreadM -> "hold steady: spread %.1f cm".format(e.positionSpreadM * 100)
            e.yawSpreadDeg > maxYawSpreadDeg -> "hold steady: yaw spread %.1f°".format(e.yawSpreadDeg)
            else -> "ok"
        }
    }

    /** Yaw of the world frame implied by one marker observation, or null if its X axis is ~vertical. */
    fun worldYawOf(sessionFromMarker: Pose): Float? {
        val xAxis = sessionFromMarker.rotateVector(Vec3.X)
        if (xAxis.horizontalLength() < 0.6f) return null
        // rotY(phi) maps +X to (cos phi, 0, -sin phi)
        return atan2(-xAxis.z, xAxis.x)
    }

    fun estimate(samples: List<MarkerSample>, markerHeightM: Float = 0f): Estimate? {
        val usable = samples.mapNotNull { s -> worldYawOf(s.sessionFromMarker)?.let { s to it } }
        if (usable.isEmpty()) return null

        var sx = 0f; var sy = 0f; var sz = 0f
        var cs = 0f; var sn = 0f
        for ((s, yaw) in usable) {
            sx += s.sessionFromMarker.t.x; sy += s.sessionFromMarker.t.y; sz += s.sessionFromMarker.t.z
            cs += kotlin.math.cos(yaw); sn += kotlin.math.sin(yaw)
        }
        val n = usable.size.toFloat()
        val mean = Vec3(sx / n, sy / n, sz / n)
        val yaw = atan2(sn, cs)

        var sq = 0f
        var maxYawDev = 0f
        var tiltSum = 0f
        for ((s, y) in usable) {
            val d = s.sessionFromMarker.t - mean
            sq += d.dot(d)
            maxYawDev = maxOf(maxYawDev, abs(Angles.wrapPi(y - yaw)))
            val normal = s.sessionFromMarker.rotateVector(Vec3.Y)
            tiltSum += Angles.deg(acos(normal.y.coerceIn(-1f, 1f)))
        }
        val origin = mean - Vec3(0f, markerHeightM, 0f)
        val times = usable.map { it.first.timeMs }
        return Estimate(
            sessionFromWorld = Pose(origin, Quat.rotY(yaw)),
            samples = usable.size,
            positionSpreadM = sqrt(sq / n),
            yawSpreadDeg = Angles.deg(maxYawDev),
            tiltDeg = tiltSum / n,
            spanMs = (times.max() - times.min()),
        )
    }

    /**
     * How far one fresh marker observation disagrees with the calibrated frame: the visible measure
     * of drift. Returns (position error m, yaw error deg).
     */
    fun residual(sessionFromWorld: Pose, observation: Pose, markerHeightM: Float = 0f): Pair<Float, Float>? {
        val yaw = worldYawOf(observation) ?: return null
        val obsOrigin = observation.t - Vec3(0f, markerHeightM, 0f)
        val worldYaw = Angles.yawOf(sessionFromWorld.rotateVector(Vec3.Z))
        // rotY(phi) maps +Z to (sin phi, 0, cos phi), so yawOf(+Z image) == phi.
        return obsOrigin.distanceTo(sessionFromWorld.t) to Angles.deg(abs(Angles.wrapPi(yaw - worldYaw)))
    }
}
