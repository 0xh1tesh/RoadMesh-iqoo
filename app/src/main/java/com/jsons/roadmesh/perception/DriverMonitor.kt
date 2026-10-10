package com.jsons.roadmesh.perception

import android.content.Context
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.jsons.roadmesh.AppPreferences
import com.jsons.roadmesh.core.mesh.HazardDetail
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.core.mesh.LocalHazard
import com.jsons.roadmesh.core.mesh.Mode
import com.jsons.roadmesh.core.mesh.Provenance
import com.jsons.roadmesh.core.perception.Alertness
import com.jsons.roadmesh.core.perception.DriverAlarm
import com.jsons.roadmesh.core.perception.DriverEscalation
import com.jsons.roadmesh.core.perception.DriverReading
import com.jsons.roadmesh.core.perception.DriverWatch
import com.jsons.roadmesh.core.perception.EscalationAction
import com.jsons.roadmesh.core.perception.EscalationState
import com.jsons.roadmesh.core.perception.FaceSample
import com.jsons.roadmesh.reports.ReportLog
import com.jsons.roadmesh.service.MeshSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import kotlin.math.ceil

/** An SOS this phone sent for its own driver: one row per episode, [repeats] counting the beacons sent since. */
data class SosSent(val alarm: DriverAlarm, val sent: Boolean, val atMs: Long, val repeats: Int = 0, val cleared: Boolean = false)

data class DriverState(
    val running: Boolean = false,
    val reading: DriverReading = DriverReading(),
    /** The face as a share of the upright camera frame (0..1, not mirrored), with the frame size for placing it on the preview. */
    val face: RectF? = null,
    val frameW: Int = 0,
    val frameH: Int = 0,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
    val fps: Double? = null,
    /** The face model in use, for example the MediaPipe Face Landmarker on the GPU. */
    val model: String? = null,
    /** The alarm sounding now, until the driver answers or is awake again. */
    val alarm: DriverAlarm? = null,
    /** Whole seconds left before the SOS goes out if the driver does not answer; null when no countdown is running. */
    val sosInS: Int? = null,
    val sosActive: Boolean = false,
    val sos: List<SosSent> = emptyList(),
    val error: String? = null,
)

/**
 * Driver monitor: the front camera (CameraX) gives frames to an open-source face model ([FaceSource], the MediaPipe Face
 * Landmarker, bundled and offline) which reads eye closure, jaw opening and head pose, and [DriverWatch] judges them.
 * A drowsy driver gets a loud local alarm first. Only if the driver does not answer within the window does [DriverEscalation]
 * send an SOS ([HazardKind.DRIVER_SOS]) over the same mesh the dashcam warnings use, repeated while it lasts, and a
 * [HazardKind.DRIVER_OK] when the driver is awake again. No image or face data leaves the phone.
 */
class DriverMonitor(private val context: Context, private val mesh: MeshSession, private val reports: ReportLog, private val prefs: AppPreferences) {
    private val state = MutableStateFlow(DriverState())
    val flow = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val analysis = Executors.newSingleThreadExecutor { Thread(it, "driver-face") }
    private var source: FaceSource? = null
    private var lastFrameMs = 0L
    private val watch = DriverWatch()
    private val escalation = DriverEscalation()
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var controller: LifecycleCameraController? = null
    private var siren: Job? = null
    private var tts: TextToSpeech? = null
    private var speech = false
    private var window = SystemClock.elapsedRealtime()
    private var frames = 0

    /** Starts the front camera into [view] for as long as [owner] is started. */
    fun bind(view: PreviewView, owner: LifecycleOwner) {
        if (controller != null) return
        watch.reset(); escalation.reset()
        if (tts == null) tts = TextToSpeech(context) { status -> speech = status == TextToSpeech.SUCCESS }
        runCatching {
            LifecycleCameraController(context).apply {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                setImageAnalysisAnalyzer(analysis) { frame -> analyse(frame) }
                bindToLifecycle(owner)
            }
        }.onSuccess { controller = it; view.controller = it; state.update { s -> s.copy(running = true, error = null) } }
            .onFailure { e -> state.update { it.copy(error = "Front camera unavailable: ${e.message}") } }
    }

    /** Stops the camera. An SOS in progress is not cleared: the phones around let it lapse after 10 s, which is the honest answer. */
    fun unbind() {
        controller?.unbind(); controller = null
        silence(); escalation.reset()
        state.update { it.copy(running = false, face = null, alarm = null, sosInS = null, sosActive = false, reading = DriverReading()) }
    }

    /** The driver answered the alarm (the button, a tap or a volume key). Before the window ends no SOS goes out; after, a DRIVER_OK does. */
    fun acknowledge() {
        val now = SystemClock.elapsedRealtime()
        escalation.acknowledge().forEach { perform(it, now) }
        watch.acknowledge()
        silence()
        state.update { it.copy(alarm = null, sosInS = null, sosActive = false) }
    }

    fun close() {
        unbind(); scope.cancel(); analysis.execute { source?.close(); source = null }; analysis.shutdown(); tts?.shutdown(); tts = null
    }

    /** Runs on the analysis thread: one frame in, one reading out. */
    private fun analyse(frame: ImageProxy) {
        frame.use { proxy ->
            val model = source ?: FaceSources.open(context) { note -> state.update { it.copy(error = note) } }.also { src ->
                source = src; state.update { it.copy(model = src.name) }
            }
            val bitmap = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            // The face model needs strictly rising timestamps.
            val at = maxOf(SystemClock.elapsedRealtime(), lastFrameMs + 1).also { lastFrameMs = it }
            val face = try { model.detect(bitmap, rotation, at) } catch (e: Exception) {
                state.update { it.copy(error = "Face model: ${e.message}") }; return
            }
            val (w, h) = bitmap.uprightSize(rotation)
            scope.launch { onFrame(face, at, w, h) }
        }
    }

    private fun onFrame(f: FaceFrame, nowMs: Long, w: Int, h: Int) {
        if (controller == null) return
        val tS = nowMs / 1000.0
        val alarm = watch.update(FaceSample(tS, f.face, f.leftEyeOpen, f.rightEyeOpen, f.pitchDeg, f.rollDeg, f.jawOpen))
        val actions = buildList {
            alarm?.let { addAll(escalation.onAlarm(it, tS)) }
            addAll(escalation.tick(tS, watch.alarmed))
        }
        var shown = state.value.alarm
        if (alarm != null) shown = alarm
        if (actions.any { it is EscalationAction.StopAlarm }) shown = null
        // A worse alarm while one already sounds: say so, louder.
        if (alarm != null && actions.none { it is EscalationAction.StartAlarm }) sound(alarm.level, escalation.state == EscalationState.SOS_ACTIVE)
        actions.forEach { perform(it, nowMs) }
        frames++
        val fps = if (nowMs - window >= 1_000) frames * 1000.0 / (nowMs - window) else null
        if (fps != null) { window = nowMs; frames = 0 }
        val left = escalation.remainingS(tS)
        state.update { s ->
            s.copy(reading = watch.reading, face = f.box, frameW = w, frameH = h, leftEyeOpen = f.leftEyeOpen, rightEyeOpen = f.rightEyeOpen,
                fps = fps ?: s.fps, alarm = shown, sosInS = left?.let { ceil(it).toInt() }, sosActive = escalation.state == EscalationState.SOS_ACTIVE)
        }
    }

    private fun perform(action: EscalationAction, nowMs: Long) {
        when (action) {
            is EscalationAction.StartAlarm -> sound(action.level, false)
            EscalationAction.StopAlarm -> silence()
            is EscalationAction.SendSos -> sendSos(action, nowMs)
            is EscalationAction.SendClear -> {
                val ok = mesh.publish(LocalHazard(HazardKind.DRIVER_OK, provenance(), "driver awake again", HazardDetail(null, prefs.vehicle, null)))
                state.update { s -> s.copy(sos = s.sos.mapIndexed { i, row -> if (i == 0) row.copy(cleared = ok) else row }) }
            }
        }
    }

    private fun sendSos(a: EscalationAction.SendSos, nowMs: Long) {
        val reason = "driver ${if (a.level == Alertness.UNRESPONSIVE) "unresponsive" else "drowsy"}: ${a.reason}"
        val ok = mesh.publish(LocalHazard(HazardKind.DRIVER_SOS, provenance(), reason, HazardDetail(null, prefs.vehicle, a.confidence)))
        if (a.sequence == 0) {
            val here = mesh.here()
            reports.add(HazardKind.DRIVER_SOS, reason, a.confidence, here?.lat, here?.lon, here?.synthetic ?: true, null, ok, "driver camera")
            val alarm = DriverAlarm(a.level, nowMs / 1000.0, a.reason, a.confidence)
            state.update { s -> s.copy(sos = (listOf(SosSent(alarm, ok, nowMs)) + s.sos).take(6)) }
            sound(a.level, true)
        } else state.update { s -> s.copy(sos = s.sos.mapIndexed { i, row -> if (i == 0) row.copy(sent = ok, repeats = row.repeats + 1) else row }) }
    }

    /** Stage mode only carries demo or recorded sources, so the driver SOS goes out as a recorded one there. */
    private fun provenance() = if (mesh.state.value.mode == Mode.STAGE) Provenance.REPLAY else Provenance.LIVE

    /** A siren on the alarm stream with a strong vibration, then a spoken line, repeating until [silence]. */
    private fun sound(level: Alertness, sosOut: Boolean) {
        siren?.cancel()
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200), 0))
        val line = when {
            sosOut -> "Wake up! A warning has gone to the cars around you."
            level == Alertness.UNRESPONSIVE -> "Wake up! Tap the screen now, or a warning goes to the cars around you."
            else -> "Wake up. You are falling asleep. Tap the screen, then pull over when it is safe."
        }
        siren = scope.launch {
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            try {
                while (isActive) {
                    repeat(3) { tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 600); delay(800) }
                    if (speech) tts?.run {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        speak(line, TextToSpeech.QUEUE_FLUSH, null, "driver-alarm")
                    }
                    delay(3_500)
                }
            } finally { tone?.release() }
        }
    }

    private fun silence() { siren?.cancel(); siren = null; vibrator.cancel(); tts?.stop() }

    companion object {
        /** The Observe tab lists the driver monitor beside the dashcam clips under this pseudo-asset. */
        const val ASSET = "camera:front"
        const val LABEL = "Driver monitor · front camera · drowsiness and SOS"
    }
}
