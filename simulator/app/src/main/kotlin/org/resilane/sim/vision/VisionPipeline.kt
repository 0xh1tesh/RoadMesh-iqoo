package org.resilane.sim.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import org.json.JSONArray
import org.json.JSONObject
import org.resilane.core.math.Angles
import org.resilane.core.math.Pose
import org.resilane.core.vision.Box
import org.resilane.core.vision.Coco
import org.resilane.core.vision.Detection
import org.resilane.core.vision.DetectionStats
import org.resilane.core.vision.GroundTruth
import org.resilane.core.vision.MatchResult
import org.resilane.core.vision.Matcher
import org.resilane.core.vision.yoloLabelLine
import org.resilane.sim.RLog
import org.resilane.sim.Settings
import org.resilane.sim.ar.VehicleView
import java.io.File
import java.util.Locale

/**
 * One composited AR frame with everything the simulator knows about it: the "simulator feed".
 * [gt] are exact boxes of the rendered vehicles (from their 3D poses), [detections] are what YOLO
 * found in the same pixels. This is the unit a decentralised learning layer consumes.
 */
class FeedSample(
    val frameId: Long,
    val timeMs: Long,
    val image: Bitmap,
    val gt: List<GroundTruth>,
    val detections: List<Detection>,
    val match: MatchResult,
    val myWorld: Pose?,
    val vehicles: List<VehicleView>,
)

/** Consumers of the simulator feed (dataset export today; on-device/federated learners later). */
fun interface FeedSink { fun onSample(s: FeedSample) }

/**
 * Grabs the composited AR frame (camera + rendered cars; the HUD and labels are separate views
 * and are NOT in it), runs YOLO on it, and scores the detections against simulator ground truth.
 */
class VisionPipeline(private val context: Context, private val settings: Settings) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("rl-yolo").apply { start() }
    private val workerHandler = Handler(worker.looper)
    @Volatile private var detector: YoloDetector? = null
    @Volatile private var busy = false
    @Volatile private var lastRequest = 0L
    var surface: SurfaceView? = null
    val stats = DetectionStats()
    private val sinks = ArrayList<FeedSink>()
    private var dataset: DatasetWriter? = null

    @Volatile var lastDetections: List<Detection> = emptyList(); private set
    @Volatile var lastGt: List<GroundTruth> = emptyList(); private set
    @Volatile var lastFrameSize = 1 to 1; private set
    @Volatile var lastInferMs = 0L; private set
    @Volatile var lastResultMs = 0L; private set
    @Volatile var status = "loading"; private set

    init {
        workerHandler.post {
            try {
                detector = YoloDetector(context)
                status = "ready (${detector?.provider})"
            } catch (e: Throwable) {
                status = "failed: ${e.message}"
                RLog.e("Yolo", "could not load detector", e)
            }
        }
        sinks += FeedSink { s -> if (settings.datasetCapture) datasetWriter().write(s) }
    }

    fun addSink(s: FeedSink) { sinks += s }

    private fun datasetWriter(): DatasetWriter = dataset ?: DatasetWriter(File(RLog.sessionDir ?: context.filesDir, "dataset")).also { dataset = it }

    /** Called on the GL thread each frame; cheap. */
    fun wantsFrame(now: Long) = settings.yoloEnabled && detector != null && !busy &&
        now - lastRequest >= 1000L / settings.yoloHz.coerceIn(1, 15)

    /** Called on the GL thread after drawing frame [frameId]; captures it right after it is shown. */
    fun onFrameRendered(frameId: Long, gt: List<GroundTruth>, w: Int, h: Int, myWorld: Pose?, vehicles: List<VehicleView>) {
        val view = surface ?: return
        busy = true
        lastRequest = System.currentTimeMillis()
        main.post {
            // A third of the view resolution is plenty for a 640x320 network input.
            val bw = (w / 3).coerceAtLeast(64); val bh = (h / 3).coerceAtLeast(64)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(view, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) workerHandler.post { process(frameId, bmp, gt, w, h, myWorld, vehicles) }
                    else { busy = false; bmp.recycle() }
                }, workerHandler)
            } catch (e: Exception) {
                busy = false
                RLog.w("Yolo", "PixelCopy failed: $e")
            }
        }
    }

    private fun process(frameId: Long, bmp: Bitmap, gtView: List<GroundTruth>, w: Int, h: Int, myWorld: Pose?, vehicles: List<VehicleView>) {
        try {
            val det = detector ?: return
            val t0 = System.nanoTime()
            val dets = det.detect(bmp, settings.yoloConf)
            lastInferMs = (System.nanoTime() - t0) / 1_000_000
            // Everything in view pixels from here on.
            val sx = w.toFloat() / bmp.width; val sy = h.toFloat() / bmp.height
            val detsView = dets.map { Detection(it.box.scaled(sx, sy), it.cls, it.score) }
            val match = Matcher.match(gtView, detsView)
            stats.add(match)
            lastDetections = detsView
            lastGt = gtView
            lastFrameSize = w to h
            lastResultMs = System.currentTimeMillis()
            if (sinks.isNotEmpty()) {
                // Sinks get boxes in the captured image's own pixels.
                val gtImg = gtView.map { GroundTruth(it.nodeId, it.box.scaled(1 / sx, 1 / sy), it.distanceM) }
                val detImg = dets
                val sample = FeedSample(frameId, lastResultMs, bmp, gtImg, detImg, Matcher.match(gtImg, detImg), myWorld, vehicles)
                for (s in sinks) runCatching { s.onSample(sample) }.onFailure { RLog.w("Yolo", "sink: $it") }
            }
        } catch (e: Throwable) {
            RLog.e("Yolo", "inference failed", e)
        } finally {
            bmp.recycle()
            busy = false
        }
    }

    fun close() { workerHandler.post { detector?.close(); worker.quitSafely() } }
}

/**
 * Writes the simulator feed as an Ultralytics-format dataset:
 *   images/NNNNNN.jpg   the composited AR frame
 *   labels/NNNNNN.txt   simulator ground truth, class 2 (COCO "car"), normalised boxes
 *   preds/NNNNNN.txt    what YOLO detected (cls cx cy w h conf)
 *   meta.jsonl          poses, node ids, distances and the match per frame
 *   data.yaml           so `yolo val data=.../data.yaml` works directly
 */
class DatasetWriter(private val dir: File) {
    private var n = 0
    private val meta: java.io.BufferedWriter

    init {
        File(dir, "images").mkdirs(); File(dir, "labels").mkdirs(); File(dir, "preds").mkdirs()
        File(dir, "data.yaml").writeText(
            "path: .\ntrain: images\nval: images\nnames:\n" + (0..79).joinToString("") { "  $it: ${Coco.NAMES[it] ?: "c$it"}\n" }
        )
        meta = File(dir, "meta.jsonl").bufferedWriter()
        RLog.i("Data", "dataset capture -> $dir")
    }

    fun write(s: FeedSample) {
        val id = "%06d".format(n++)
        File(dir, "images/$id.jpg").outputStream().use { s.image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val w = s.image.width; val h = s.image.height
        File(dir, "labels/$id.txt").writeText(s.gt.joinToString("") { yoloLabelLine(Coco.CAR, it.box, w, h) + "\n" })
        File(dir, "preds/$id.txt").writeText(s.detections.joinToString("") {
            yoloLabelLine(it.cls, it.box, w, h) + " %.4f\n".format(Locale.US, it.score)
        })
        val j = JSONObject()
            .put("id", id).put("frame", s.frameId).put("t_ms", s.timeMs).put("w", w).put("h", h)
            .put("me", s.myWorld?.let { poseJson(it) })
            .put("gt", JSONArray(s.gt.map { g ->
                JSONObject().put("node", "%016x".format(g.nodeId)).put("box", boxJson(g.box)).put("dist_m", g.distanceM.toDouble())
                    .put("detected", s.match.pairs.any { it.first.nodeId == g.nodeId })
            }))
            .put("vehicles", JSONArray(s.vehicles.map { v ->
                JSONObject().put("node", "%016x".format(v.nodeId)).put("name", v.name).put("sim", v.simulated)
                    .put("x", v.phoneWorld.x.toDouble()).put("y", v.phoneWorld.y.toDouble()).put("z", v.phoneWorld.z.toDouble())
                    .put("heading_deg", v.headingDeg.toDouble()).put("scale", v.scale.toDouble()).put("state", v.state.name)
            }))
            .put("false_positives", s.match.falsePositives.size)
        meta.write(j.toString()); meta.write("\n"); meta.flush()
    }

    private fun boxJson(b: Box) = JSONArray(listOf(b.x1, b.y1, b.x2, b.y2).map { it.toDouble() })
    private fun poseJson(p: Pose) = JSONObject().put("x", p.t.x.toDouble()).put("y", p.t.y.toDouble()).put("z", p.t.z.toDouble())
        .put("heading_deg", Angles.headingDegOfYaw(Angles.yawOf(p.cameraForward())).toDouble())
}
