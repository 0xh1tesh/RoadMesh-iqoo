package com.jsons.roadmesh.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import com.jsons.roadmesh.AppPreferences
import com.jsons.roadmesh.copilot.Copilot
import com.jsons.roadmesh.copilot.Priority
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.perception.*
import com.jsons.roadmesh.reports.ReportLog
import com.jsons.roadmesh.service.MeshSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.resilane.sim.vision.YoloDetector
import java.util.concurrent.Executors

/** Short-lived local visual association, not a number plate or cross-phone vehicle identity. */
data class Overlay(val subject: SeenObject, val inLane: Boolean, val distanceM: Double?, val lateralM: Double?, val hazard: Boolean,
                   val id: Int = 0, val vx: Float = 0f, val vy: Float = 0f)
/** [sent] means the candidate entered the local mesh command queue; it may still be rejected before transmission. */
data class Raised(val call: HazardCall, val sent: Boolean, val atMs: Long, val evidence: List<String> = emptyList(),
                  val explanationTag: String? = null)
data class ObserverState(
    val simulating: Boolean = false,
    val source: String = DashcamObserver.DEFAULT_LABEL,
    val playing: Boolean = false,
    val detector: String = "YOLO11n not loaded",
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
    val live: Boolean = false,
    val cameraStatus: String = "Camera paused",
    val analysisStatus: String = "Waiting for camera frames",
    val capturedAtMs: Long? = null,
    val trackedVehicles: Int = 0,
    val automaticEvents: Long = 0,
)

/**
 * Visible dashboard rear camera or explicit replay -> local YOLO -> temporal evidence -> automatic mesh event.
 * Each phone can observe while relaying. Frames stay local. The optional LLM explains after alert/publication.
 */
class DashcamObserver(private val context: Context, private val mesh: MeshSession, private val copilot: Copilot,
                      private val prefs: AppPreferences, private val reports: ReportLog) {
    private val state = MutableStateFlow(ObserverState())
    val flow = state.asStateFlow()
    private val yoloThread = Executors.newSingleThreadExecutor { Thread(it, "roadmesh-perception") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detector: YoloDetector? = null
    private var roadDetector: YoloDetector? = null
    private var roadAttempted = false
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var texture: SurfaceTexture? = null
    private var loop: Job? = null
    private var source: Uri? = null
    private var asset: String = DEFAULT_ASSET
    private var replayLabel = DEFAULT_LABEL
    private var foreground = false
    private var generation = 0
    private var lastNarration = 0L
    private val camera = LiveCameraSource(context) { message, active ->
        state.update { it.copy(cameraStatus = message, playing = active) }
        mesh.setObserving(active)
    }

    /** Drive session setup only; subsequent sensing, inference and event exchange require no reporting taps. */
    fun startLive() {
        halt()
        state.update { it.copy(live = true, simulating = false, source = "Rear camera · live", raised = emptyList(),
            error = null, analysisStatus = "Waiting for camera frames", cameraStatus = "Waiting for visible dashboard") }
        startAttached()
    }
    fun stopLive() {
        halt()
        state.update { it.copy(live = false, cameraStatus = "Camera paused", analysisStatus = "Observation paused") }
    }
    /** Backgrounding closes the camera and invalidates every in-flight result. */
    fun onForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (active) startAttached() else {
            halt()
            state.update { it.copy(cameraStatus = "Paused while app is in background", analysisStatus = "Observation paused") }
        }
    }
    fun attach(textureView: TextureView, surfaceTexture: SurfaceTexture) {
        halt(); view = textureView; texture = surfaceTexture; startAttached()
    }
    fun detach() { halt(); view = null; texture = null }
    fun simulate(on: Boolean) {
        halt()
        state.update { it.copy(simulating = on, live = false, source = replayLabel, error = null,
            cameraStatus = if (on) "Replay clip · not a live camera" else "Camera paused", raised = emptyList()) }
        if (on) startAttached()
    }
    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        state.update { it.copy(playing = p.isPlaying, analysisStatus = if (p.isPlaying) "Analysing replay frames" else "Replay paused") }
    }
    /** Bundled and picked pitch clips always remain REPLAY observations. */
    fun use(uri: Uri?, label: String, bundled: String = DEFAULT_ASSET) {
        source = uri; asset = bundled; replayLabel = label
        if (!state.value.live) state.update { it.copy(source = label, raised = emptyList()) }
        if (state.value.simulating) { halt(); startAttached() }
    }
    private fun halt() {
        generation++; loop?.cancel(); loop = null; camera.close()
        player?.release(); player = null; surface?.release(); surface = null
        mesh.setObserving(false)
        state.update { it.copy(playing = false, overlays = emptyList(), objects = emptyList(), counts = emptyMap(),
            lead = null, trackedVehicles = 0, capturedAtMs = null) }
    }
    private fun startAttached() {
        if (!foreground || loop != null) return
        val v = view ?: return; val t = texture ?: return
        val live = state.value.live
        if (!live && !state.value.simulating) return
        val token = generation
        if (live) camera.start(v, t) else { v.setTransform(Matrix()); surface = Surface(t); openReplay(token) }
        loop = scope.launch { analyse(token, live) }
    }
    private fun openReplay(token: Int) {
        val output = surface ?: return
        runCatching {
            MediaPlayer().also { p ->
                player = p
                source?.let { p.setDataSource(context, it) }
                    ?: context.assets.openFd(asset).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                p.setSurface(output); p.isLooping = true; p.setVolume(0f, 0f)
                p.setOnPreparedListener {
                    if (token == generation && foreground) { it.start(); mesh.setObserving(true); state.update { s -> s.copy(playing = true, error = null) } }
                }
                p.setOnErrorListener { _, _, _ ->
                    if (token == generation) state.update { it.copy(playing = false, error = "Replay video could not be decoded") }
                    true
                }
                p.prepareAsync()
            }
        }.onFailure { failure -> state.update { it.copy(error = "Replay unavailable: ${failure.message}") } }
    }
    fun close() {
        halt(); scope.cancel()
        // Cleanup queues behind a cancelled-but-still-running native ONNX call.
        CoroutineScope(yoloThread).launch {
            detector?.close(); detector = null; roadDetector?.close(); roadDetector = null; yoloThread.close()
        }
    }
    private suspend fun loadDetectors(): YoloDetector? {
        withContext(yoloThread + NonCancellable) {
            if (detector == null) runCatching {
                // NPU only after the isolated probe passed; detector otherwise uses XNNPACK/CPU.
                YoloDetector(context, MODEL_ASSET, npu = NpuProbeService.result(context)?.startsWith("ok") == true)
            }.onSuccess { detector = it }.onFailure { e ->
                state.update { it.copy(detector = "YOLO11n unavailable", error = "Local detector could not load: ${e.message}",
                    analysisStatus = "Detection unavailable; no automatic warnings") }
            }
            if (detector != null && !roadAttempted) {
                roadAttempted = true
                roadDetector = runCatching { YoloDetector(context, ROAD_ASSET, npu = false) }.getOrNull()
            }
        }
        currentCoroutineContext().ensureActive()
        detector?.let { d -> state.update { it.copy(detector = "YOLO11n · ${d.provider}",
            roadModel = roadDetector?.let { r -> "road model · ${r.provider}" } ?: "Road model unavailable") } }
        return detector
    }
    private suspend fun analyse(token: Int, live: Boolean) {
        val yolo = loadDetectors() ?: return
        val analyzer = AutomatedTrafficAnalyzer()
        val geometry = DashcamHazards()
        // Session-local bitmap prevents native inference reading pixels from a newly attached source.
        val frame = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        var lastClipS = -1.0
        var lastReplayBufferNs = 0L
        var lastCapturedFix: GeoFix? = null
        var geometryEpoch = camera.geometryEpoch
        var lastFrameMs = SystemClock.elapsedRealtime()
        var window = lastFrameMs; var frames = 0
        try {
            while (currentCoroutineContext().isActive && token == generation) {
                val started = SystemClock.elapsedRealtime()
                val v = view
                var captureMs: Long? = null; var tS = 0.0
                if (live) {
                    camera.readLatest(frame)?.let { stamp -> captureMs = stamp.capturedAtMs; tS = stamp.capturedAtMs / 1000.0 }
                } else {
                    val p = player
                    if (v != null && v.isAvailable && p != null && runCatching { p.isPlaying }.getOrDefault(false)) {
                        tS = p.currentPosition / 1000.0
                        frame.eraseColor(Color.BLACK)
                        if (tS != lastClipS && runCatching { v.getBitmap(frame) }.getOrNull() != null) {
                            val bufferNs = runCatching { texture?.timestamp ?: 0L }.getOrDefault(0L)
                            if (bufferNs > 0 && bufferNs != lastReplayBufferNs) {
                                if (tS < lastClipS || tS - lastClipS > 1.0) analyzer.reset()
                                lastReplayBufferNs = bufferNs; lastClipS = tS; captureMs = started
                            }
                        }
                    }
                }
                val captured = captureMs
                if (captured != null) {
                    lastFrameMs = started
                    if (live && camera.geometryEpoch != geometryEpoch) {
                        analyzer.reset()
                        geometryEpoch = camera.geometryEpoch
                    }
                    // Snapshot sensors at capture, before ONNX can take hundreds of milliseconds. A later GNSS
                    // update must not be attributed to an older camera frame or discard all speed evidence.
                    if (live) mesh.here()?.takeIf { !it.synthetic && it.valid() &&
                        it.sampledAtMs?.let { sample -> sample <= captured } == true }?.let { lastCapturedFix = it }
                    val capturedFix = lastCapturedFix.takeIf { live }
                    val stableAtCapture = !live || camera.cameraStable
                    val objects = withContext(yoloThread) {
                        val general = yolo.detect(frame, 0.25f, KEEP).map {
                            SeenObject(ObjectClass.coco(it.cls), it.score, it.box.x1 / FRAME_W, it.box.y1 / FRAME_H, it.box.x2 / FRAME_W, it.box.y2 / FRAME_H)
                        }
                        // No cached road boxes: a repeated inference must not earn independent temporal support.
                        general + roadDetector?.detect(frame, 0.35f, null).orEmpty().map {
                            SeenObject(ObjectClass.road(it.cls), it.score, it.box.x1 / FRAME_W, it.box.y1 / FRAME_H, it.box.x2 / FRAME_W, it.box.y2 / FRAME_H)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (token != generation) break
                    val now = SystemClock.elapsedRealtime(); val inferMs = now - started
                    if (now - captured > MAX_FRAME_AGE_MS) {
                        analyzer.reset()
                        state.update { it.copy(inferMs = inferMs, analysisStatus = "Unknown: inference frame too old", overlays = emptyList(), objects = emptyList()) }
                        continue
                    }
                    val stable = stableAtCapture && (!live || camera.cameraStable)
                    val ego = capturedFix?.let { fix ->
                        val sample = fix.sampledAtMs; val speed = fix.speedMps
                        if (sample == null || speed == null) null else EgoMotion(sample / 1000.0, speed,
                            fix.speedAccuracyMps, fix.heading, fix.headingAccuracyDeg, stable)
                    } ?: if (live && !stable) EgoMotion(tS, Double.NaN, cameraStable = false) else null
                    val result = analyzer.update(tS, objects, ego)
                    result.events.forEach { automatic(it, captured, live, objects, frame) }
                    frames++
                    val fps = if (now - window >= 1000) frames * 1000.0 / (now - window) else null
                    if (fps != null) { window = now; frames = 0 }
                    state.update { s -> s.copy(inferMs = inferMs, fps = fps ?: s.fps, clipS = if (live) s.clipS else tS,
                        capturedAtMs = captured, analysisStatus = result.status, trackedVehicles = result.monitoredVehicles,
                        counts = objects.filter { it.score >= 0.35f && it.cls in SCENE }.groupingBy { it.cls }.eachCount(),
                        objects = objects, frames = s.frames + 1, automaticEvents = s.automaticEvents + result.events.size,
                        overlays = result.tracks.map { tr -> Overlay(tr.subject, geometry.inLane(tr.subject), geometry.distanceM(tr.subject),
                            geometry.lateralM(tr.subject), result.events.any { tr.id in it.trackIds }, tr.id, tr.vx, tr.vy) }) }
                    if (result.events.isEmpty() && now - lastNarration > NARRATE_MS && copilot.idle) narrate(objects, live, tS)
                } else if (live && started - lastFrameMs > MAX_FRAME_AGE_MS) {
                    analyzer.reset()
                    state.update { it.copy(analysisStatus = "Unknown: waiting for fresh camera frames", overlays = emptyList(), objects = emptyList()) }
                }
                delay((PERIOD_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(10))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            if (token == generation) state.update { it.copy(error = "Analysis paused: ${e.message}", analysisStatus = "Detection unavailable", overlays = emptyList()) }
        } finally { frame.recycle() }
    }
    private fun automatic(event: AutomaticTrafficEvent, capturedMs: Long, live: Boolean, objects: List<SeenObject>, frame: Bitmap) {
        val provenance = if (live) Provenance.LIVE else Provenance.REPLAY
        val modeMatches = if (live) mesh.state.value.mode == Mode.ROAD else mesh.state.value.mode == Mode.STAGE
        val detail = HazardDetail(event.estimatedRangeM?.toInt()?.coerceAtLeast(1), prefs.vehicle, event.detectorConfidence)
        val reason = event.reason + ". Evidence: " + event.evidence.joinToString("; ")
        if (live) mesh.localCaution(when (event.kind) {
            HazardKind.BRAKING -> "Sharp slowing detected at this phone. Stay attentive."
            HazardKind.SLOWDOWN -> "Possible traffic slowdown. Stay attentive."
            HazardKind.POTHOLE -> "Possible pothole ahead."
            else -> event.kind.speech
        })
        val sent = modeMatches && mesh.publish(LocalHazard(event.kind, provenance, reason, detail, capturedAtMs = capturedMs))
        val here = mesh.here()
        reports.add(event.kind, reason, event.detectorConfidence, here?.lat, here?.lon, !live || here?.synthetic != false,
            frame, sent, if (live) "Automatic rear camera" else "Replay: $replayLabel")
        val subject = event.subject ?: objects.firstOrNull() ?: SeenObject(ObjectClass.OTHER, 0f, 0f, 0f, 0f, 0f)
        val call = HazardCall(event.kind, event.atS, event.reason, subject, event.estimatedRangeM ?: 0.0)
        val tag = "automatic:$provenance:$capturedMs:${event.kind}"
        state.update { it.copy(raised = (listOf(Raised(call, sent, SystemClock.elapsedRealtime(), event.evidence, tag)) + it.raised).take(6)) }
        lastNarration = SystemClock.elapsedRealtime()
        copilot.ask(tag,
            "${if (live) "Live mounted rear camera" else "Recorded clip replay, not a live road event"}: $reason. " +
                "Detector score ${event.detectorConfidence}; this is not an incident probability. " +
                (if (sent) "An event candidate was queued for mesh processing; remote delivery is unknown."
                else "This caution is local; no network warning was queued."), Priority.EVENT, fallback = event.reason)
    }
    private fun narrate(objects: List<SeenObject>, live: Boolean, tS: Double) {
        lastNarration = SystemClock.elapsedRealtime()
        val scene = objects.filter { it.score >= 0.35f && it.cls in SCENE }.groupingBy { it.cls }.eachCount()
            .entries.joinToString { "${it.value} ${it.key.name.lowercase()}" }.ifEmpty { "no current detections; visibility is unknown" }
        copilot.ask("scene:$live:$tS", "${if (live) "Live mounted camera" else "Recorded clip"}: $scene. " +
            "These are local object detections, not proof the road is clear. Explain briefly without inventing a hazard or manoeuvre.", Priority.SCENE, Copilot.TASK_SCENE)
    }
    companion object {
        const val DEFAULT_ASSET = "dashcam/bangalore_cv_raman_road.mp4"
        val CLIPS = listOf(
            DEFAULT_ASSET to "C V Raman Road, Bengaluru · dashcam · CC0",
            "dashcam/pothole_traffic_cc-by-sa.mp4" to "Traffic through potholes · roadside · Amuzujoe, CC BY-SA 4.0",
            "dashcam/pothole_road_cc-by-sa.mp4" to "Rain-filled potholes · roadside · Knowledge and philosophy, CC BY-SA 4.0",
        )
        const val DEFAULT_LABEL = "C V Raman Road, Bengaluru · dashcam · CC0"
        const val MODEL_ASSET = "models/yolo11n_384x640.onnx"
        const val ROAD_ASSET = "models/road_yolo11n_384x640.onnx"
        private val SCENE = setOf(ObjectClass.PERSON, ObjectClass.BICYCLE, ObjectClass.CAR, ObjectClass.MOTORCYCLE, ObjectClass.BUS, ObjectClass.TRUCK)
        const val FRAME_W = 640
        const val FRAME_H = 360
        const val PERIOD_MS = 200L
        const val NARRATE_MS = 8_000L
        const val MAX_FRAME_AGE_MS = 1_500L
        private val KEEP = setOf(0, 1, 2, 3, 5, 7, 9)
        fun title(kind: HazardKind) = kind.title()
    }
}
