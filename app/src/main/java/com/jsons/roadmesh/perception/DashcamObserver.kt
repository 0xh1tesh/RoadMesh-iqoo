package com.jsons.roadmesh.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import com.jsons.roadmesh.AppPreferences
import com.jsons.roadmesh.copilot.Copilot
import com.jsons.roadmesh.copilot.Priority
import com.jsons.roadmesh.copilot.withArticle
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.HazardDetail
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.core.mesh.LocalHazard
import com.jsons.roadmesh.core.mesh.Provenance
import com.jsons.roadmesh.core.perception.*
import com.jsons.roadmesh.reports.ReportLog
import com.jsons.roadmesh.service.MeshSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.resilane.sim.vision.YoloDetector
import java.util.concurrent.Executors

/** One box on screen, already judged by the rules. */
data class Overlay(val subject: SeenObject, val inLane: Boolean, val distanceM: Double?, val lateralM: Double?, val hazard: Boolean,
                   val id: Int = 0, val vx: Float = 0f, val vy: Float = 0f)

/** A hazard the rules raised and what the mesh did with it. */
data class Raised(val call: HazardCall, val sent: Boolean, val atMs: Long)

data class ObserverState(
    val simulating: Boolean = false,
    val source: String = DashcamObserver.DEFAULT_LABEL,
    val playing: Boolean = false,
    val detector: String = "YOLO11n not loaded",
    /** The RESILANE road model (potholes, helmets), when bundled. */
    val roadModel: String? = null,
    val inferMs: Long? = null,
    val fps: Double? = null,
    val clipS: Double = 0.0,
    val overlays: List<Overlay> = emptyList(),
    val lead: Lead? = null,
    val raised: List<Raised> = emptyList(),
    val counts: Map<ObjectClass, Int> = emptyMap(),
    val objects: List<SeenObject> = emptyList(),
    val frames: Long = 0,
    val error: String? = null,
)

/**
 * Observer pipeline: dashcam video → frames at about 10 Hz → YOLO11n (ONNX Runtime, XNNPACK) → [DashcamHazards] →
 * [MeshSession.publish] with REPLAY provenance, and a co-pilot line for each hazard. Video plays in a [TextureView] the
 * screen owns; frames are read back from it so what YOLO sees is exactly what is shown.
 */
class DashcamObserver(private val context: Context, private val mesh: MeshSession, private val copilot: Copilot, private val prefs: AppPreferences,
                      private val reports: ReportLog) {
    private val state = MutableStateFlow(ObserverState())
    val flow = state.asStateFlow()
    private val yoloThread = Executors.newSingleThreadExecutor { Thread(it, "yolo").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detector: YoloDetector? = null
    private var roadDetector: YoloDetector? = null
    private var roadFailed = false
    private var roadSeen: List<SeenObject> = emptyList()
    private val watch = RoadWatch()
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var loop: Job? = null
    private var source: Uri? = null
    private var asset: String = DEFAULT_ASSET
    private val rules = DashcamHazards()
    private val tracker = ObjectTracker()
    private val frame = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
    private var lastClipS = 0.0
    private var lastNarration = 0L

    /** Called by the screen when its video surface is ready. Starts playback and analysis. */
    fun attach(textureView: TextureView, texture: SurfaceTexture) {
        view = textureView
        surface?.release(); surface = Surface(texture)
        if (player == null) open() else player?.setSurface(surface)
        if (loop == null) loop = scope.launch { analyse() }
    }

    /** The screen went away: stop reading frames and release the surface. The mesh keeps running. */
    fun detach() {
        loop?.cancel(); loop = null
        player?.release(); player = null
        surface?.release(); surface = null; view = null
        state.update { it.copy(playing = false, overlays = emptyList()) }
    }

    /** The Observe tab's Simulate button: show the video pane, which then attaches its surface. */
    fun simulate(on: Boolean) {
        if (!on) detach()
        mesh.setObserving(on)
        state.update { it.copy(simulating = on) }
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        state.update { it.copy(playing = p.isPlaying) }
    }

    /** Plays a video the user picked instead of the bundled clip. */
    fun use(uri: Uri?, label: String, bundled: String = DEFAULT_ASSET) {
        source = uri; asset = bundled
        state.update { it.copy(source = label, raised = emptyList()) }
        rules.reset(); tracker.reset(); watch.reset()
        if (surface != null) { player?.release(); player = null; open() }
    }

    fun close() { detach(); scope.cancel(); yoloThread.close(); detector?.close(); detector = null; roadDetector?.close(); roadDetector = null }

    private fun open() {
        val s = surface ?: return
        runCatching {
            MediaPlayer().apply {
                val uri = source
                if (uri != null) setDataSource(context, uri)
                else context.assets.openFd(asset).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setSurface(s); isLooping = true; setVolume(0f, 0f)
                setOnPreparedListener { it.start(); state.update { st -> st.copy(playing = true, error = null) } }
                prepareAsync()
            }
        }.onSuccess { player = it }.onFailure { e -> state.update { it.copy(error = "Video failed: ${e.message}") } }
    }

    private suspend fun analyse() {
        val yolo = detector ?: withContext(yoloThread) {
            // The NPU is used only after the isolated probe proved it on this phone and app version; until then the CPU.
            val probe = NpuProbeService.result(context)
            runCatching { YoloDetector(context, MODEL_ASSET, npu = probe?.startsWith("ok") == true) }
        }.getOrElse { e ->
            state.update { it.copy(detector = "YOLO11n missing", error = "Detector unavailable: ${e.message}. Run tools/fetch_dashcam.py") }
            return
        }.also { detector = it; state.update { s -> s.copy(detector = "YOLO11n · ${it.provider}") } }
        val road = roadDetector ?: if (roadFailed) null else withContext(yoloThread) {
            runCatching { YoloDetector(context, ROAD_ASSET, npu = NpuProbeService.result(context)?.startsWith("ok") == true) }.onFailure { roadFailed = true }.getOrNull()
        }?.also { roadDetector = it; state.update { s -> s.copy(roadModel = "road model · ${it.provider}") } }

        var window = SystemClock.elapsedRealtime(); var frames = 0
        while (currentCoroutineContext().isActive) {
            val started = SystemClock.elapsedRealtime()
            val p = player; val v = view
            if (p != null && v != null && v.isAvailable && runCatching { p.isPlaying }.getOrDefault(false)) {
                val clipS = p.currentPosition / 1000.0
                v.getBitmap(frame)
                val t0 = SystemClock.elapsedRealtime()
                val found = withContext(yoloThread) { yolo.detect(frame, 0.25f, KEEP) }
                // The road model runs on every other frame; its boxes are reused for the frame between.
                if (road != null && state.value.frames % 2 == 0L) roadSeen = withContext(yoloThread) { road.detect(frame, 0.3f, null) }.map {
                    SeenObject(ObjectClass.road(it.cls), it.score, it.box.x1 / FRAME_W, it.box.y1 / FRAME_H, it.box.x2 / FRAME_W, it.box.y2 / FRAME_H)
                }
                val inferMs = SystemClock.elapsedRealtime() - t0
                if (clipS + 0.5 < lastClipS) { rules.reset(); tracker.reset(); watch.reset() } // the clip looped
                lastClipS = clipS
                val objects = found.map {
                    val o = SeenObject(ObjectClass.coco(it.cls), it.score, it.box.x1 / FRAME_W, it.box.y1 / FRAME_H, it.box.x2 / FRAME_W, it.box.y2 / FRAME_H)
                    if (o.cls == ObjectClass.TRAFFIC_LIGHT) o.copy(light = colourOf(it.box.x1, it.box.y1, it.box.x2, it.box.y2)) else o
                } + roadSeen
                val call = rules.update(clipS, objects)
                // Replay has no speed of its own; on the road the GNSS speed feeds the wrong-side rule.
                val here = mesh.here()
                watch.update(clipS, objects, here?.takeIf { !it.synthetic }?.speedMps).forEach { finding(it, objects) }
                val tracks = tracker.update(clipS, objects.filter { it.score >= 0.35f })
                if (call != null) raise(call, objects)
                frames++
                val now = SystemClock.elapsedRealtime()
                val fps = if (now - window >= 1_000) frames * 1000.0 / (now - window) else null
                if (fps != null) { window = now; frames = 0 }
                state.update { s ->
                    s.copy(inferMs = inferMs, fps = fps ?: s.fps, clipS = clipS, lead = rules.lead,
                        counts = objects.filter { it.score >= 0.35f && it.cls in SCENE }.groupingBy { it.cls }.eachCount(), objects = objects, frames = s.frames + 1,
                        overlays = tracks.map { tr ->
                            val o = tr.subject
                            Overlay(o, rules.inLane(o), rules.distanceM(o), rules.lateralM(o),
                                call?.subject == o || s.raised.firstOrNull()?.let { r -> now - r.atMs < 1_500 && r.call.subject.iou(o) > 0.5f } == true,
                                tr.id, tr.vx, tr.vy)
                        })
                }
                if (call == null && now - lastNarration > NARRATE_MS && copilot.idle) narrate(objects, clipS)
            }
            delay((PERIOD_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(5))
        }
    }

    /** Reads a traffic light's lit lamp from the current frame. */
    private fun colourOf(x1: Float, y1: Float, x2: Float, y2: Float): LightColour? {
        val l = x1.toInt().coerceIn(0, FRAME_W - 1); val t = y1.toInt().coerceIn(0, FRAME_H - 1)
        val w = (x2.toInt() - l).coerceIn(1, FRAME_W - l); val h = (y2.toInt() - t).coerceIn(1, FRAME_H - t)
        val px = IntArray(w * h); frame.getPixels(px, 0, w, l, t, w, h)
        return lightColour(px)
    }

    /** A pothole, accident or violation: sent to the mesh, logged with a snapshot, and phrased by the co-pilot. */
    private fun finding(f: RoadFinding, objects: List<SeenObject>) {
        val detail = HazardDetail(f.distanceM?.toInt()?.coerceAtLeast(1), prefs.vehicle, f.confidence)
        val sent = mesh.publish(LocalHazard(f.kind, Provenance.REPLAY, "dashcam ${"%.1f".format(f.atS)}s: ${f.reason}", detail))
        val here = mesh.here()
        reports.add(f.kind, f.reason, f.confidence, here?.lat, here?.lon, here?.synthetic ?: true, frame, sent, state.value.source)
        val call = HazardCall(f.kind, f.atS, f.reason, f.subject, f.distanceM ?: 0.0)
        state.update { it.copy(raised = (listOf(Raised(call, sent, SystemClock.elapsedRealtime())) + it.raised).take(6)) }
        lastNarration = SystemClock.elapsedRealtime()
        val veh = prefs.vehicle.title
        if (f.kind.violation) copilot.ask("hazard:${f.atS}", "This $veh's front camera saw: ${f.reason}. Violation: ${f.kind.title()}. " +
            "It was logged with a snapshot and location for the traffic police" + (if (sent) " and shared over the mesh." else "."), Priority.EVENT, Copilot.TASK_REPORT)
        else raiseCopilot(call, objects, sent)
    }

    private fun raise(call: HazardCall, objects: List<SeenObject>) {
        val detail = HazardDetail(call.distanceM.toInt(), prefs.vehicle, call.subject.score.toDouble())
        val sent = mesh.publish(LocalHazard(call.kind, Provenance.REPLAY, "dashcam ${"%.1f".format(call.atS)}s: ${call.reason}", detail))
        state.update { it.copy(raised = (listOf(Raised(call, sent, SystemClock.elapsedRealtime())) + it.raised).take(6)) }
        raiseCopilot(call, objects, sent)
    }

    private fun raiseCopilot(call: HazardCall, objects: List<SeenObject>, sent: Boolean) {
        lastNarration = SystemClock.elapsedRealtime()
        copilot.ask("hazard:${call.atS}", "This ${prefs.vehicle.title}'s front camera saw: ${call.reason}. Hazard: ${call.kind.title()}. Also in view: ${scene(objects)}. " +
            if (sent) "A warning was broadcast to the vehicles behind over the Bluetooth mesh." else "The mesh is not running, so nobody behind was warned.", Priority.EVENT)
    }

    private fun narrate(objects: List<SeenObject>, clipS: Double) {
        lastNarration = SystemClock.elapsedRealtime()
        val lead = rules.lead?.let { "The nearest vehicle in our lane is ${withArticle(it.subject.cls.name.lowercase())} about ${it.distanceM.toInt()} m ahead. " } ?: "No vehicle directly ahead in our lane. "
        copilot.ask("scene:$clipS", "Front camera of ${withArticle(prefs.vehicle.title)} on an Indian city road. In view: ${scene(objects)}. $lead No hazard right now.", Priority.SCENE,
            Copilot.TASK_SCENE)
    }

    private fun scene(objects: List<SeenObject>) = objects.filter { it.score >= 0.35f && it.cls in SCENE }
        .groupingBy { it.cls }.eachCount().entries.joinToString { (c, n) -> val name = c.name.lowercase(); "$n $name${if (n > 1) (if (name.endsWith("s")) "es" else "s") else ""}" }.ifEmpty { "an empty road" }

    companion object {
        const val DEFAULT_ASSET = "dashcam/bangalore_cv_raman_road.mp4"
        /** Clips bundled with the app: asset path to the label shown. The pothole clips are roadside views, not dashcams. */
        val CLIPS = listOf(
            DEFAULT_ASSET to "C V Raman Road, Bengaluru · dashcam · CC0",
            "dashcam/pothole_traffic_cc-by-sa.mp4" to "Traffic through potholes · roadside · Amuzujoe, CC BY-SA 4.0",
            "dashcam/pothole_road_cc-by-sa.mp4" to "Rain-filled potholes · roadside · Knowledge and philosophy, CC BY-SA 4.0",
        )
        const val DEFAULT_LABEL = "C V Raman Road, Bengaluru · dashcam · CC0"
        const val MODEL_ASSET = "models/yolo11n_384x640.onnx"
        /** Trained on Kaggle by tools/kaggle/road_yolo/train.py: 0 pothole, 1 helmet, 2 no_helmet. Optional. */
        const val ROAD_ASSET = "models/road_yolo11n_384x640.onnx"
        private val SCENE = setOf(ObjectClass.PERSON, ObjectClass.BICYCLE, ObjectClass.CAR, ObjectClass.MOTORCYCLE, ObjectClass.BUS, ObjectClass.TRUCK)
        const val FRAME_W = 640
        const val FRAME_H = 360
        const val PERIOD_MS = 100L
        const val NARRATE_MS = 6_000L
        private val KEEP = setOf(0, 1, 2, 3, 5, 7, 9)
        fun title(kind: HazardKind) = kind.title()
    }
}
