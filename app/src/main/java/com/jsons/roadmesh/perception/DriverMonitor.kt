package com.jsons.roadmesh.perception

import android.content.Context
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.jsons.roadmesh.AppPreferences
import com.jsons.roadmesh.core.mesh.HazardDetail
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.core.mesh.LocalHazard
import com.jsons.roadmesh.core.mesh.Mode
import com.jsons.roadmesh.core.mesh.Provenance
import com.jsons.roadmesh.core.perception.Alertness
import com.jsons.roadmesh.core.perception.DriverAlarm
import com.jsons.roadmesh.core.perception.DriverReading
import com.jsons.roadmesh.core.perception.DriverWatch
import com.jsons.roadmesh.core.perception.FaceSample
import com.jsons.roadmesh.reports.ReportLog
import com.jsons.roadmesh.service.MeshSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** An SOS this phone sent for its own driver. */
data class SosSent(val alarm: DriverAlarm, val sent: Boolean, val atMs: Long)

data class DriverState(
    val running: Boolean = false,
    val reading: DriverReading = DriverReading(),
    /** The face in the preview's own pixels, mirrored like the preview. */
    val face: Rect? = null,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
    val fps: Double? = null,
    /** The alarm sounding now, until the driver is awake again or taps "I'm awake". */
    val alarm: DriverAlarm? = null,
    val sos: List<SosSent> = emptyList(),
    val error: String? = null,
)

/**
 * Driver monitor: the front camera (CameraX) → on-device face detection with eye-open and head-pose estimates (ML Kit,
 * bundled model, offline) → [DriverWatch]. A drowsy or unresponsive driver sets off a loud local alarm and an SOS
 * ([HazardKind.DRIVER_SOS]) over the same mesh the dashcam warnings use, so the phones around know at once.
 */
class DriverMonitor(private val context: Context, private val mesh: MeshSession, private val reports: ReportLog, private val prefs: AppPreferences) {
    private val state = MutableStateFlow(DriverState())
    val flow = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val analysis = Executors.newSingleThreadExecutor { Thread(it, "driver-face") }
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.25f).build())
    private val watch = DriverWatch()
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var controller: LifecycleCameraController? = null
    private var siren: Job? = null
    private var tts: TextToSpeech? = null
    private var speech = false
    private var window = SystemClock.elapsedRealtime()
    private var frames = 0
    private var lastSosMs = -SOS_FLOOR_MS

    /** Starts the front camera into [view] for as long as [owner] is started. */
    fun bind(view: PreviewView, owner: LifecycleOwner) {
        if (controller != null) return
        watch.reset()
        if (tts == null) tts = TextToSpeech(context) { status -> speech = status == TextToSpeech.SUCCESS }
        runCatching {
            LifecycleCameraController(context).apply {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                setImageAnalysisAnalyzer(analysis, MlKitAnalyzer(listOf(detector), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    ContextCompat.getMainExecutor(context)) { result ->
                    val faces = result.getValue(detector)
                    if (faces == null) result.getThrowable(detector)?.let { e -> state.update { it.copy(error = "Face detector: ${e.message}") } }
                    else onFaces(faces)
                })
                bindToLifecycle(owner)
            }
        }.onSuccess { controller = it; view.controller = it; state.update { s -> s.copy(running = true, error = null) } }
            .onFailure { e -> state.update { it.copy(error = "Front camera unavailable: ${e.message}") } }
    }

    fun unbind() {
        controller?.unbind(); controller = null
        silence()
        state.update { it.copy(running = false, face = null, alarm = null, reading = DriverReading()) }
    }

    /** The driver tapped "I'm awake": the alarm stops. The SOS already sent stands; the next episode raises a new one. */
    fun acknowledge() { silence(); state.update { it.copy(alarm = null) } }

    fun close() { unbind(); scope.cancel(); analysis.shutdown(); detector.close(); tts?.shutdown(); tts = null }

    private fun onFaces(faces: List<com.google.mlkit.vision.face.Face>) {
        if (controller == null) return
        val now = SystemClock.elapsedRealtime()
        // The driver is the biggest face; a passenger further back is smaller.
        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val sample = if (f == null) FaceSample(now / 1000.0, false)
            else FaceSample(now / 1000.0, true, f.leftEyeOpenProbability, f.rightEyeOpenProbability, f.headEulerAngleX, f.headEulerAngleZ)
        val alarm = watch.update(sample)
        frames++
        val fps = if (now - window >= 1_000) frames * 1000.0 / (now - window) else null
        if (fps != null) { window = now; frames = 0 }
        state.update { s ->
            s.copy(reading = watch.reading, face = f?.boundingBox, leftEyeOpen = f?.leftEyeOpenProbability, rightEyeOpen = f?.rightEyeOpenProbability,
                fps = fps ?: s.fps, alarm = if (s.alarm != null && !watch.alarmed) null else s.alarm)
        }
        if (!watch.alarmed && siren != null) silence()
        if (alarm != null) raise(alarm, now)
    }

    /** Warn the driver at once and send the SOS. */
    private fun raise(alarm: DriverAlarm, now: Long) {
        sound(alarm)
        // An unresponsive driver always goes out; repeated drowsy episodes at most once every [SOS_FLOOR_MS].
        val sent = if (alarm.level == Alertness.UNRESPONSIVE || now - lastSosMs >= SOS_FLOOR_MS) {
            lastSosMs = now
            val provenance = if (mesh.state.value.mode == Mode.STAGE) Provenance.REPLAY else Provenance.LIVE
            val reason = "driver ${if (alarm.level == Alertness.UNRESPONSIVE) "unresponsive" else "drowsy"}: ${alarm.reason}"
            val ok = mesh.publish(LocalHazard(HazardKind.DRIVER_SOS, provenance, reason, HazardDetail(null, prefs.vehicle, alarm.confidence)))
            val here = mesh.here()
            reports.add(HazardKind.DRIVER_SOS, reason, alarm.confidence, here?.lat, here?.lon, here?.synthetic ?: true, null, ok, "driver camera")
            ok
        } else null
        state.update { s -> s.copy(alarm = alarm, sos = if (sent == null) s.sos else (listOf(SosSent(alarm, sent, now)) + s.sos).take(6)) }
    }

    /** A siren on the alarm stream with a strong vibration, then a spoken line, repeating until [silence]. */
    private fun sound(alarm: DriverAlarm) {
        siren?.cancel()
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200), 0))
        val line = if (alarm.level == Alertness.UNRESPONSIVE) "Wake up! An SOS has gone to the cars around you." else "Wake up. You are falling asleep. Pull over when it is safe."
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
        const val SOS_FLOOR_MS = 60_000L
    }
}
