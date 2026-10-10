package com.jsons.roadmesh.core.junction

import kotlin.math.*

/** Arms in counter-clockwise order. Arm k is the south arm rotated k quarter turns. */
enum class Arm {
    S, E, N, W;
    fun after(turn: Turn) = entries[(ordinal + turn.quarterTurns) % 4]
}

/** Left-hand traffic (India): LEFT is the short turn, RIGHT crosses opposing traffic. No U-turns. */
enum class Turn(val quarterTurns: Int) { RIGHT(1), STRAIGHT(2), LEFT(3) }

data class Movement(val from: Arm, val turn: Turn) {
    val to: Arm get() = from.after(turn)
    override fun toString() = "$from→$to"
    companion object { val ALL = Arm.entries.flatMap { arm -> Turn.entries.map { Movement(arm, it) } } }
}

data class Point(val x: Double, val y: Double) {
    fun distance(o: Point) = hypot(x - o.x, y - o.y)
    fun rotate(quarterTurns: Int): Point { var p = this; repeat(quarterTurns) { p = Point(-p.y, p.x) }; return p }
    operator fun plus(o: Point) = Point(x + o.x, y + o.y)
    operator fun times(k: Double) = Point(x * k, y * k)
}

/** A tile of the junction box that a movement occupies from [from] to [to] seconds after its slot. */
data class TileUse(val tile: Int, val from: Double, val to: Double)

/**
 * One lane in and one lane out per arm. The box is the square where the four roads overlap.
 * A slot is the moment a vehicle's centre is on its stop line, one footprint radius before the box,
 * so the footprint touches the box exactly at the slot and is clear of it at [clearTime].
 */
data class JunctionConfig(
    val laneWidthM: Double = 3.5,
    val footprintRadiusM: Double = 2.5,
    val tileM: Double = 0.5,
    val crossingSpeedMps: Double = 5.0,
) {
    fun validate() = apply {
        require(laneWidthM in 2.5..5.0 && footprintRadiusM in 1.0..5.0) { "lane/footprint" }
        require(tileM in 0.1..1.0 && crossingSpeedMps in 1.0..15.0) { "tile/speed" }
    }
}

class JunctionGeometry(val config: JunctionConfig = JunctionConfig()) {
    init { config.validate() }
    val half = config.laneWidthM
    private val lane = config.laneWidthM / 2
    private val r = config.footprintRadiusM
    val tilesPerSide = ceil(2 * half / config.tileM).toInt()
    val tileCount = tilesPerSide * tilesPerSide

    private class Path(val points: List<Point>, val distance: DoubleArray)
    private val paths: Map<Movement, Path> = Movement.ALL.associateWith { m ->
        val points = canonical(m.turn).map { it.rotate(m.from.ordinal) }
        val d = DoubleArray(points.size)
        for (i in 1 until points.size) d[i] = d[i - 1] + points[i].distance(points[i - 1])
        Path(points, d)
    }

    val uses: Map<Movement, List<TileUse>> = paths.mapValues { (_, path) -> tileUses(path) }

    fun pathLength(m: Movement) = paths.getValue(m).distance.last()
    fun clearTime(m: Movement) = pathLength(m) / config.crossingSpeedMps

    fun positionAt(m: Movement, offsetS: Double): Point {
        val path = paths.getValue(m)
        val d = (offsetS * config.crossingSpeedMps).coerceIn(0.0, path.distance.last())
        var i = path.distance.binarySearch(d).let { if (it >= 0) it else -it - 2 }.coerceIn(0, path.points.size - 2)
        val span = path.distance[i + 1] - path.distance[i]
        val f = if (span > 0) (d - path.distance[i]) / span else 0.0
        val a = path.points[i]; val b = path.points[++i]
        return Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    }

    /** Centre of a vehicle [distanceM] before the stop line of inbound [arm]. */
    fun approachPoint(arm: Arm, distanceM: Double) = Point(-lane, -half - r - distanceM).rotate(arm.ordinal)
    /** Centre of a vehicle [distanceM] along outbound [arm], measured from where it clears the box. */
    fun exitPoint(arm: Arm, distanceM: Double) = Point(lane, -half - r - distanceM).rotate(arm.ordinal)

    /** Centreline inside the box only, for counting geometric conflict points. */
    fun boxCentreline(m: Movement) = paths.getValue(m).points.filter { abs(it.x) <= half + 1e-9 && abs(it.y) <= half + 1e-9 }

    private fun canonical(turn: Turn): List<Point> {
        val out = ArrayList<Point>()
        line(out, Point(-lane, -half - r), Point(-lane, -half))
        when (turn) {
            Turn.STRAIGHT -> line(out, Point(-lane, -half), Point(-lane, half + r))
            Turn.LEFT -> { arc(out, Point(-half, -half), half - lane, 0.0, PI / 2); line(out, Point(-half, -lane), Point(-half - r, -lane)) }
            Turn.RIGHT -> { arc(out, Point(half, -half), half + lane, PI, PI / 2); line(out, Point(half, lane), Point(half + r, lane)) }
        }
        return out
    }

    private fun line(out: MutableList<Point>, a: Point, b: Point) {
        val n = max(1, ceil(a.distance(b) / STEP).toInt())
        for (i in (if (out.isEmpty()) 0 else 1)..n) out += Point(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
    }

    private fun arc(out: MutableList<Point>, c: Point, radius: Double, from: Double, to: Double) {
        val n = max(1, ceil(radius * abs(to - from) / STEP).toInt())
        for (i in 1..n) { val t = from + (to - from) * i / n; out += Point(c.x + radius * cos(t), c.y + radius * sin(t)) }
    }

    private fun tileUses(path: Path): List<TileUse> {
        val first = DoubleArray(tileCount) { Double.POSITIVE_INFINITY }
        val last = DoubleArray(tileCount) { Double.NEGATIVE_INFINITY }
        val tile = config.tileM
        path.points.forEachIndexed { i, p ->
            val t = path.distance[i] / config.crossingSpeedMps
            val x0 = floor((p.x - r + half) / tile).toInt().coerceAtLeast(0)
            val x1 = floor((p.x + r + half) / tile).toInt().coerceAtMost(tilesPerSide - 1)
            val y0 = floor((p.y - r + half) / tile).toInt().coerceAtLeast(0)
            val y1 = floor((p.y + r + half) / tile).toInt().coerceAtMost(tilesPerSide - 1)
            for (ix in x0..x1) for (iy in y0..y1) {
                val centre = Point(-half + (ix + .5) * tile, -half + (iy + .5) * tile)
                if (centre.distance(p) > r) continue
                val k = iy * tilesPerSide + ix
                first[k] = min(first[k], t); last[k] = max(last[k], t)
            }
        }
        return (0 until tileCount).filter { first[it].isFinite() }.map { TileUse(it, first[it], last[it]) }
    }

    companion object { private const val STEP = 0.05 }
}
