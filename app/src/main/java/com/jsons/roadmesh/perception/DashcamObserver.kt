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
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.HazardDetail
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.core.mesh.LocalHazard
import com.jsons.roadmesh.core.mesh.Provenance
import com.jsons.roadmesh.core.perception.*
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
class DashcamObserver(private val context: Context, private val mesh: MeshSession, private val copilot: Copilot, private val prefs: AppPreferences) {
    private val state = MutableStateFlow(ObserverState())
    val flow = state.asStateFlow()
    private val yoloThread = Executors.newSingleThreadExecutor { Thread(it, "yolo").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detector: YoloDetector? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var loop: Job? = null
    private var source: Uri? = null
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
        state.update { it.copy(simulating = on) }
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        state.update { it.copy(playing = p.isPlaying) }
    }

    /** Plays a video the user picked instead of the bundled clip. */
    fun use(uri: Uri?, label: String) {
        source = uri
        state.update { it.copy(source = label, raised = emptyList()) }
        rules.reset(); tracker.reset()
        if (surface != null) { player?.release(); player = null; open() }
    }

    fun close() { detach(); scope.cancel(); yoloThread.close(); detector?.close(); detector = null }

    private fun open() {
        val s = surface ?: return
        runCatching {
            MediaPlayer().apply {
                val uri = source
                if (uri != null) setDataSource(context, uri)
                else context.assets.openFd(DEFAULT_ASSET).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setSurface(s); isLooping = true; setVolume(0f, 0f)
                setOnPreparedListener { it.start(); state.update { st -> st.copy(playing = true, error = null) } }
                prepareAsync()
            }
        }.onSuccess { player = it }.onFailure { e -> state.update { it.copy(error = "Video failed: ${e.message}") } }
    }

    private suspend fun analyse() {
        val yolo = detector ?: withContext(yoloThread) {
            runCatching { YoloDetector(context, MODEL_ASSET) }
        }.getOrElse { e ->
            state.update { it.copy(detector = "YOLO11n missing", error = "Detector unavailable: ${e.message}. Run tools/fetch_dashcam.py") }
            return
        }.also { detector = it; state.update { s -> s.copy(detector = "YOLO11n · ${it.provider}") } }

        var window = SystemClock.elapsedRealtime(); var frames = 0
        while (currentCoroutineContext().isActive) {
            val started = SystemClock.elapsedRealtime()
            val p = player; val v = view
            if (p != null && v != null && v.isAvailable && runCatching { p.isPlaying }.getOrDefault(false)) {
                val clipS = p.currentPosition / 1000.0
                v.getBitmap(frame)
                val t0 = SystemClock.elapsedRealtime()
                val found = withContext(yoloThread) { yolo.detect(frame, 0.25f, KEEP) }
                val inferMs = SystemClock.elapsedRealtime() - t0
                if (clipS + 0.5 < lastClipS) { rules.reset(); tracker.reset() } // the clip looped
                lastClipS = clipS
                val objects = found.map {
                    SeenObject(ObjectClass.coco(it.cls), it.score, it.box.x1 / FRAME_W, it.box.y1 / FRAME_H, it.box.x2 / FRAME_W, it.box.y2 / FRAME_H)
                }
                val call = rules.update(clipS, objects)
                val tracks = tracker.update(clipS, objects.filter { it.score >= 0.35f })
                if (call != null) raise(call, objects)
                frames++
                val now = SystemClock.elapsedRealtime()
                val fps = if (now - window >= 1_000) frames * 1000.0 / (now - window) else null
                if (fps != null) { window = now; frames = 0 }
                state.update { s ->
                    s.copy(inferMs = inferMs, fps = fps ?: s.fps, clipS = clipS, lead = rules.lead,
                        counts = objects.filter { it.score >= 0.35f }.groupingBy { it.cls }.eachCount(), objects = objects, frames = s.frames + 1,
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

    private fun raise(call: HazardCall, objects: List<SeenObject>) {
        val detail = HazardDetail(call.distanceM.toInt(), prefs.vehicle, call.subject.score.toDouble())
        val sent = mesh.publish(LocalHazard(call.kind, Provenance.REPLAY, "dashcam ${"%.1f".format(call.atS)}s: ${call.reason}", detail))
        state.update { it.copy(raised = (listOf(Raised(call, sent, SystemClock.elapsedRealtime())) + it.raised).take(6)) }
        lastNarration = SystemClock.elapsedRealtime()
        copilot.ask("hazard:${call.atS}", "This ${prefs.vehicle.title}'s front camera saw: ${call.reason}. Hazard: ${call.kind.title()}. Also in view: ${scene(objects)}. " +
            if (sent) "A warning was broadcast to the vehicles behind over the Bluetooth mesh." else "The mesh is not running, so nobody behind was warned.", Priority.EVENT)
    }

    private fun narrate(objects: List<SeenObject>, clipS: Double) {
        lastNarration = SystemClock.elapsedRealtime()
        val lead = rules.lead?.let { "The nearest vehicle in our lane is a ${it.subject.cls.name.lowercase()} about ${it.distanceM.toInt()} m ahead. " } ?: "No vehicle directly ahead in our lane. "
        copilot.ask("scene:$clipS", "Front camera of a ${prefs.vehicle.title} on an Indian city road. In view: ${scene(objects)}. $lead No hazard right now.", Priority.SCENE,
            "Describe what the camera sees and what to watch, in one sentence of at most 22 words.")
    }

    private fun scene(objects: List<SeenObject>) = objects.filter { it.score >= 0.35f && it.cls != ObjectClass.OTHER }
        .groupingBy { it.cls }.eachCount().entries.joinToString { (c, n) -> "$n ${c.name.lowercase()}${if (n > 1) "s" else ""}" }.ifEmpty { "an empty road" }

    companion object {
        const val DEFAULT_ASSET = "dashcam/bangalore_cv_raman_road.mp4"
        const val DEFAULT_LABEL = "C V Raman Road, Bangalore · CC0"
        const val MODEL_ASSET = "models/yolo11n_384x640.onnx"
        const val FRAME_W = 640
        const val FRAME_H = 360
        const val PERIOD_MS = 100L
        const val NARRATE_MS = 6_000L
        private val KEEP = setOf(0, 1, 2, 3, 5, 7)
        fun title(kind: HazardKind) = kind.title()
    }
}
