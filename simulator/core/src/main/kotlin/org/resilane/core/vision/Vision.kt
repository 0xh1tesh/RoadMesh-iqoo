package org.resilane.core.vision

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in pixels of some image, (x1,y1) top-left. */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val w get() = x2 - x1
    val h get() = y2 - y1
    val area get() = max(0f, w) * max(0f, h)
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2

    fun iou(o: Box): Float {
        val iw = min(x2, o.x2) - max(x1, o.x1)
        val ih = min(y2, o.y2) - max(y1, o.y1)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        return inter / (area + o.area - inter)
    }

    fun clip(w: Float, h: Float) = Box(x1.coerceIn(0f, w), y1.coerceIn(0f, h), x2.coerceIn(0f, w), y2.coerceIn(0f, h))
    fun scaled(sx: Float, sy: Float) = Box(x1 * sx, y1 * sy, x2 * sx, y2 * sy)
}

data class Detection(val box: Box, val cls: Int, val score: Float)

object Coco {
    const val CAR = 2
    const val MOTORCYCLE = 3
    const val BUS = 5
    const val TRUCK = 7
    /** What counts as "a vehicle was detected" when scoring against simulator ground truth. */
    val VEHICLE = setOf(CAR, BUS, TRUCK)
    val NAMES = mapOf(0 to "person", CAR to "car", MOTORCYCLE to "motorcycle", BUS to "bus", TRUCK to "truck")
}

/** How a source image was resized and padded into the network input. */
data class Letterbox(val scale: Float, val padX: Float, val padY: Float, val inW: Int, val inH: Int) {
    companion object {
        fun fit(srcW: Int, srcH: Int, inW: Int, inH: Int): Letterbox {
            val s = min(inW.toFloat() / srcW, inH.toFloat() / srcH)
            return Letterbox(s, (inW - srcW * s) / 2f, (inH - srcH * s) / 2f, inW, inH)
        }
    }
    fun toSource(b: Box) = Box((b.x1 - padX) / scale, (b.y1 - padY) / scale, (b.x2 - padX) / scale, (b.y2 - padY) / scale)
}

/**
 * Decoder for Ultralytics YOLOv8/YOLO11 detection heads exported to ONNX without NMS:
 * output [1, 4 + numClasses, N], rows = cx, cy, w, h (input pixels) then per-class scores.
 */
object YoloDecoder {
    fun decode(
        out: FloatArray, numClasses: Int, numAnchors: Int, lb: Letterbox,
        confThreshold: Float = 0.25f, iouThreshold: Float = 0.5f,
        keep: Set<Int>? = null, maxDet: Int = 100,
    ): List<Detection> {
        val cands = ArrayList<Detection>()
        for (i in 0 until numAnchors) {
            var best = -1
            var bestS = confThreshold
            for (c in 0 until numClasses) {
                if (keep != null && c !in keep) continue
                val s = out[(4 + c) * numAnchors + i]
                if (s > bestS) { bestS = s; best = c }
            }
            if (best < 0) continue
            val cx = out[i]; val cy = out[numAnchors + i]
            val w = out[2 * numAnchors + i]; val h = out[3 * numAnchors + i]
            cands += Detection(lb.toSource(Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)), best, bestS)
        }
        return nms(cands, iouThreshold, maxDet)
    }

    /** Class-aware greedy NMS. */
    fun nms(dets: List<Detection>, iouThreshold: Float, maxDet: Int = 100): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in sorted) {
            if (kept.size >= maxDet) break
            if (kept.none { it.cls == d.cls && it.box.iou(d.box) > iouThreshold }) kept += d
        }
        return kept
    }
}

/**
 * Projects a 3D point set (a vehicle's silhouette points, model space) through model and
 * view-projection matrices (column-major, OpenGL) to a tight screen-space box. This is the
 * simulator's ground truth: where the car really is in the frame, which YOLO is scored against.
 *
 * Points behind the near plane are dropped; if the set straddles the camera, the box is built from
 * the in-front points only, which is exact enough for a car partly beside/behind the viewer.
 */
object Projection {
    fun projectPoints(points: FloatArray, model: FloatArray, viewProj: FloatArray, width: Int, height: Int): Box? {
        val mvp = mul(viewProj, model)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var inFront = 0
        var i = 0
        while (i + 2 < points.size) {
            val x = points[i]; val y = points[i + 1]; val z = points[i + 2]
            i += 3
            val cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
            if (cw <= 0.05f) continue
            val cx = (mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]) / cw
            val cy = (mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]) / cw
            val sx = (cx + 1f) * 0.5f * width
            val sy = (1f - cy) * 0.5f * height
            if (sx < minX) minX = sx; if (sx > maxX) maxX = sx
            if (sy < minY) minY = sy; if (sy > maxY) maxY = sy
            inFront++
        }
        if (inFront == 0) return null
        val b = Box(minX, minY, maxX, maxY).clip(width.toFloat(), height.toFloat())
        return if (b.w < 2f || b.h < 2f) null else b
    }

    /** Clip-space projection of one world point; null if behind the camera. Returns screen px. */
    fun projectPoint(x: Float, y: Float, z: Float, viewProj: FloatArray, width: Int, height: Int): FloatArray? {
        val cw = viewProj[3] * x + viewProj[7] * y + viewProj[11] * z + viewProj[15]
        if (cw <= 0.05f) return null
        val cx = (viewProj[0] * x + viewProj[4] * y + viewProj[8] * z + viewProj[12]) / cw
        val cy = (viewProj[1] * x + viewProj[5] * y + viewProj[9] * z + viewProj[13]) / cw
        return floatArrayOf((cx + 1f) * 0.5f * width, (1f - cy) * 0.5f * height, cx, cy)
    }

    /** Column-major a * b. */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }
}

/** A simulator-known vehicle in one captured frame. */
data class GroundTruth(val nodeId: Long, val box: Box, val distanceM: Float)

data class MatchResult(
    val pairs: List<Pair<GroundTruth, Detection>>,
    val missed: List<GroundTruth>,
    val falsePositives: List<Detection>,
)

object Matcher {
    /** Greedy one-to-one matching by IoU, best pairs first. Only vehicle classes can match. */
    fun match(gt: List<GroundTruth>, dets: List<Detection>, iouThreshold: Float = 0.4f): MatchResult {
        val vehicles = dets.filter { it.cls in Coco.VEHICLE }
        val cand = ArrayList<Triple<Int, Int, Float>>()
        for ((gi, g) in gt.withIndex()) for ((di, d) in vehicles.withIndex()) {
            val iou = g.box.iou(d.box)
            if (iou >= iouThreshold) cand += Triple(gi, di, iou)
        }
        cand.sortByDescending { it.third }
        val usedG = HashSet<Int>(); val usedD = HashSet<Int>()
        val pairs = ArrayList<Pair<GroundTruth, Detection>>()
        for ((gi, di, _) in cand) {
            if (gi in usedG || di in usedD) continue
            usedG += gi; usedD += di
            pairs += gt[gi] to vehicles[di]
        }
        return MatchResult(
            pairs,
            gt.filterIndexed { i, _ -> i !in usedG },
            vehicles.filterIndexed { i, _ -> i !in usedD },
        )
    }
}

/** Running YOLO-vs-simulator scores, overall and per node. */
class DetectionStats {
    var frames = 0L; private set
    var tp = 0L; private set
    var fn = 0L; private set
    var fp = 0L; private set
    var iouSum = 0.0; private set
    private val perNode = HashMap<Long, LongArray>() // [tp, fn]

    @Synchronized
    fun add(r: MatchResult) {
        frames++
        tp += r.pairs.size; fn += r.missed.size; fp += r.falsePositives.size
        for ((g, d) in r.pairs) { iouSum += g.box.iou(d.box); perNode.getOrPut(g.nodeId) { LongArray(2) }[0]++ }
        for (g in r.missed) perNode.getOrPut(g.nodeId) { LongArray(2) }[1]++
    }

    @get:Synchronized val recall get() = if (tp + fn == 0L) Float.NaN else tp.toFloat() / (tp + fn)
    @get:Synchronized val precision get() = if (tp + fp == 0L) Float.NaN else tp.toFloat() / (tp + fp)
    @get:Synchronized val meanIou get() = if (tp == 0L) Float.NaN else (iouSum / tp).toFloat()

    @Synchronized
    fun recallOf(node: Long): Float = perNode[node]?.let { (t, f) -> if (t + f == 0L) Float.NaN else t.toFloat() / (t + f) } ?: Float.NaN

    @Synchronized
    fun reset() { frames = 0; tp = 0; fn = 0; fp = 0; iouSum = 0.0; perNode.clear() }
}

/** One label line in Ultralytics YOLO txt format (normalised cx cy w h). */
fun yoloLabelLine(cls: Int, b: Box, imgW: Int, imgH: Int): String =
    "%d %.6f %.6f %.6f %.6f".format(java.util.Locale.US, cls, b.cx / imgW, b.cy / imgH, b.w / imgW, b.h / imgH)
