package org.resilane.sim.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
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

/** Synchronous consumers. The bitmap is borrowed only during this callback; copy it to retain it. */
fun interface FeedSink { fun onSample(s: FeedSample) }

/**
 * Grabs the composited AR frame (camera + rendered cars; the HUD and labels are separate views
 * and are NOT in it), runs YOLO on it, and scores the detections against simulator ground truth.
 */
class VisionPipeline(private val context: Context, private val settings: Settings) {
    private val worker = HandlerThread("rl-yolo").apply { start() }
    private val workerHandler = Handler(worker.looper)
    @Volatile private var detector: YoloDetector? = null
    @Volatile private var busy = false
    @Volatile private var lastRequest = 0L
    @Volatile private var closed = false
    @Volatile private var loadRequested = false
    private var captureFbo = 0
    private var captureTexture = 0
    private var captureSize = 0 to 0
    val stats = DetectionStats()
    private val sinks = CopyOnWriteArrayList<FeedSink>()
    private var dataset: DatasetWriter? = null

    @Volatile var lastDetections: List<Detection> = emptyList(); private set
    @Volatile var lastGt: List<GroundTruth> = emptyList(); private set
    @Volatile var lastFrameSize = 1 to 1; private set
    @Volatile var lastInferMs = 0L; private set
    @Volatile var lastResultMs = 0L; private set
    @Volatile var status = "disabled (optional detector)"; private set

    init {
        sinks += FeedSink { s -> if (settings.datasetCapture) datasetWriter().write(s) }
    }

    fun addSink(s: FeedSink) { sinks += s }

    private fun datasetWriter(): DatasetWriter = dataset ?: DatasetWriter(File(RLog.sessionDir ?: context.filesDir, "dataset")).also { dataset = it }

    /** Called on the GL thread each frame; cheap. */
    fun wantsFrame(now: Long): Boolean {
        if (closed || !settings.yoloEnabled) return false
        if (!loadRequested) {
            loadRequested = true
            status = "loading optional detector"
            workerHandler.post {
                if (closed) return@post
                if (context.assets.list("models")?.contains("yolo11n_640x320.onnx") != true) {
                    status = "model unavailable (car demo still works)"
                    return@post
                }
                try {
                    detector = YoloDetector(context)
                    status = "ready (${detector?.provider})"
                } catch (e: Exception) {
                    status = "detector unavailable: ${e.message}"
                    RLog.e("Yolo", "could not load optional detector", e)
                }
            }
        }
        return detector != null && !busy && now - lastRequest >= 1000L / settings.yoloHz.coerceIn(1, 15)
    }

    /** GL names belong to a context. CPU-side model and detector can survive recreation. */
    fun onGlContextCreated() { captureFbo = 0; captureTexture = 0; captureSize = 0 to 0 }

    /** GL thread before buffer swap: resolve THIS frame, preserving its exact metadata. */
    fun onFrameRendered(frameId: Long, capturedMs: Long, gt: List<GroundTruth>, w: Int, h: Int, myWorld: Pose?, vehicles: List<VehicleView>) {
        if (closed || busy || w <= 0 || h <= 0) return
        busy = true
        lastRequest = capturedMs
        try {
            if (captureSize != w to h) {
                if (captureFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(captureFbo), 0)
                if (captureTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(captureTexture), 0)
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0); captureTexture = ids[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureTexture)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glGenFramebuffers(1, ids, 0); captureFbo = ids[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, captureTexture, 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
                captureSize = w to h
            }
            // Same-size blit resolves MSAA without mixing a later displayed frame with this pose.
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, captureFbo)
            GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, captureFbo)
            val rgba = ByteBuffer.allocateDirect(w * h * 4)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GL frame capture failed" }
            if (!workerHandler.post {
                if (closed) { busy = false; return@post }
                try {
                    val pixels = IntArray(w * h)
                    for (y in 0 until h) for (x in 0 until w) {
                        val i = (y * w + x) * 4
                        pixels[(h - y - 1) * w + x] = (255 shl 24) or ((rgba[i].toInt() and 255) shl 16) or
                            ((rgba[i + 1].toInt() and 255) shl 8) or (rgba[i + 2].toInt() and 255)
                    }
                    val full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                    val small = Bitmap.createScaledBitmap(full, (w / 3).coerceAtLeast(1), (h / 3).coerceAtLeast(1), true)
                    if (small !== full) full.recycle()
                    process(frameId, capturedMs, small, gt, w, h, myWorld, vehicles)
                } catch (e: Exception) { busy = false; RLog.e("Yolo", "frame conversion failed", e) }
            }) busy = false
        } catch (e: Exception) {
            busy = false
            status = "capture unavailable: ${e.message}"
            RLog.w("Yolo", status)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun process(frameId: Long, capturedMs: Long, bmp: Bitmap, gtView: List<GroundTruth>, w: Int, h: Int, myWorld: Pose?, vehicles: List<VehicleView>) {
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
                val sample = FeedSample(frameId, capturedMs, bmp, gtImg, detImg, Matcher.match(gtImg, detImg), myWorld, vehicles)
                for (s in sinks) runCatching { s.onSample(sample) }.onFailure { RLog.w("Yolo", "sink: $it") }
            }
        } catch (e: Throwable) {
            RLog.e("Yolo", "inference failed", e)
        } finally {
            bmp.recycle()
            busy = false
        }
    }

    fun close() { closed = true; workerHandler.post { detector?.close(); detector = null; worker.quitSafely() } }
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
