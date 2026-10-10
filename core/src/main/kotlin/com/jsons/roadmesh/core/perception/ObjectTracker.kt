package com.jsons.roadmesh.core.perception

/** A detection with a stable id and the image velocity of its ground point (bottom centre), in frames per second. */
data class Track(val id: Int, val subject: SeenObject, val vx: Float, val vy: Float, val ageS: Double)

/**
 * Greedy IoU tracker for drawing motion vectors. Same-class boxes overlapping the previous frame's box by at least
 * [minIou] keep their id; velocity is an exponential average of the ground point's displacement. Visual aid only:
 * the hazard rules do their own tracking.
 */
class ObjectTracker(private val minIou: Float = 0.3f, private val smoothing: Float = 0.5f, private val maxGapS: Double = 0.5) {
    private class Live(val id: Int, var obj: SeenObject, var vx: Float, var vy: Float, val born: Double, var seen: Double)
    private val live = ArrayList<Live>()
    private var nextId = 1
    private var lastT: Double? = null

    fun reset() { live.clear(); lastT = null }

    fun update(tS: Double, objects: List<SeenObject>): List<Track> {
        val dt = lastT?.let { tS - it }?.takeIf { it > 1e-3 && it < 1.0 }
        if (lastT != null && tS < lastT!!) reset()
        lastT = tS
        live.removeAll { tS - it.seen > maxGapS }
        val free = live.toMutableList()
        val out = ArrayList<Track>(objects.size)
        for (o in objects.sortedByDescending { it.w * it.h }) {
            val match = free.filter { it.obj.cls == o.cls }.maxByOrNull { it.obj.iou(o) }?.takeIf { it.obj.iou(o) >= minIou }
            val t = if (match != null) {
                free.remove(match)
                if (dt != null) {
                    val vx = (o.cx - match.obj.cx) / dt.toFloat(); val vy = (o.y2 - match.obj.y2) / dt.toFloat()
                    match.vx += smoothing * (vx - match.vx); match.vy += smoothing * (vy - match.vy)
                }
                match.obj = o; match.seen = tS; match
            } else Live(nextId++, o, 0f, 0f, tS, tS).also { live += it }
            out += Track(t.id, o, t.vx, t.vy, tS - t.born)
        }
        return out
    }
}
