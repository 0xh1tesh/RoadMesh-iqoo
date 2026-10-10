package com.jsons.roadmesh.core.junction

import kotlin.math.*

/** Position on an edge: [s] along it from its first vertex, [d] lateral offset (left of travel positive). */
data class EdgePosition(val s: Double, val d: Double)

/** An edge as a polyline in local metres (x east, y north). Projects points into edge (Frenet) coordinates. */
class EdgeFrame(private val vertices: List<Point>) {
    private val cumulative = DoubleArray(vertices.size)
    init {
        require(vertices.size >= 2) { "edge needs two vertices" }
        for (i in 1 until vertices.size) cumulative[i] = cumulative[i - 1] + vertices[i].distance(vertices[i - 1])
        require(cumulative.last() > 0) { "degenerate edge" }
    }
    val length get() = cumulative.last()

    fun project(p: Point): EdgePosition {
        var best = EdgePosition(0.0, Double.POSITIVE_INFINITY)
        for (i in 0 until vertices.size - 1) {
            val a = vertices[i]; val b = vertices[i + 1]
            val dx = b.x - a.x; val dy = b.y - a.y; val len2 = dx * dx + dy * dy
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            val qx = a.x + t * dx; val qy = a.y + t * dy
            val dist = hypot(p.x - qx, p.y - qy)
            if (dist < abs(best.d)) best = EdgePosition(cumulative[i] + t * sqrt(len2), if (dx * (p.y - a.y) - dy * (p.x - a.x) >= 0) dist else -dist)
        }
        return best
    }

    companion object {
        /** Local east/north metres of [lat]/[lon] around an origin; adequate over a junction cluster's few hundred metres. */
        fun local(originLat: Double, originLon: Double, lat: Double, lon: Double) =
            Point(Math.toRadians(lon - originLon) * 6_371_000 * cos(Math.toRadians(originLat)), Math.toRadians(lat - originLat) * 6_371_000)
    }
}

/**
 * Along-edge Kalman filter, state [s, v]. The vehicle-frame longitudinal acceleration from the calibrated IMU drives
 * the prediction; GNSS position (projected on the edge), GNSS speed and optional OBD wheel speed correct it.
 */
class EgoTracker(s0: Double, v0: Double, private val accelNoise: Double = 0.5) {
    var s = s0; private set
    var v = v0; private set
    private var pss = 25.0; private var psv = 0.0; private var pvv = 4.0

    val positionVariance get() = pss
    val speedVariance get() = pvv

    fun predict(dt: Double, accel: Double) {
        require(dt in 0.0..1.0 && accel.isFinite())
        s += v * dt + 0.5 * accel * dt * dt
        v = max(0.0, v + accel * dt)
        val q = accelNoise * accelNoise
        val nss = pss + 2 * dt * psv + dt * dt * pvv + q * dt.pow(4) / 4
        val nsv = psv + dt * pvv + q * dt.pow(3) / 2
        val nvv = pvv + q * dt * dt
        pss = nss; psv = nsv; pvv = nvv
    }

    fun observePosition(sMeasured: Double, sigma: Double) {
        val r = sigma * sigma; val k = pss + r
        val ks = pss / k; val kv = psv / k; val e = sMeasured - s
        s += ks * e; v = max(0.0, v + kv * e)
        val nss = (1 - ks) * pss; val nsv = (1 - ks) * psv; val nvv = pvv - kv * psv
        pss = nss; psv = nsv; pvv = nvv
    }

    fun observeSpeed(vMeasured: Double, sigma: Double) {
        val r = sigma * sigma; val k = pvv + r
        val ks = psv / k; val kv = pvv / k; val e = vMeasured - v
        s += ks * e; v = max(0.0, v + kv * e)
        val nss = pss - ks * psv; val nsv = (1 - kv) * psv; val nvv = (1 - kv) * pvv
        pss = nss; psv = nsv; pvv = nvv
    }
}
