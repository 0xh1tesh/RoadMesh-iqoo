package com.jsons.roadmesh.perception

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.max

/** Timestamp of the displayed camera buffer, never the time inference happened to finish. */
internal data class CameraFrameStamp(val sensorNs: Long, val capturedAtMs: Long, val clockEstimated: Boolean)

/** Bounds camera latency and prevents processing a frozen preview twice. Unknown clock domains use capture arrival. */
internal class CameraFrameClock(private val maxAgeMs: Long = 500) {
    private var lastSensorNs = 0L
    fun reset() { lastSensorNs = 0 }
    fun accept(sensorNs: Long, completedSensorNs: Long, completedAtMs: Long, nowMs: Long,
               realtimeClock: Boolean): CameraFrameStamp? {
        if (sensorNs <= lastSensorNs || sensorNs <= 0 || completedSensorNs <= 0) return null
        if (nowMs - completedAtMs !in 0..maxAgeMs) return null
        // SENSOR_TIMESTAMP shares elapsedRealtime only when the camera explicitly declares REALTIME.
        val captureMs = if (realtimeClock) sensorNs / 1_000_000L
            else completedAtMs + (sensorNs - completedSensorNs) / 1_000_000L
        if (nowMs - captureMs !in 0..maxAgeMs) return null
        lastSensorNs = sensorNs
        return CameraFrameStamp(sensorNs, captureMs, !realtimeClock)
    }
}

/** Camera2 rear preview. Only the visible resumed activity owns it; no background camera service is started. */
internal class LiveCameraSource(private val context: Context, private val status: (String, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var view: TextureView? = null
    private var texture: SurfaceTexture? = null
    private var sensorRotation = 90
    private var bufferSize = Size(1280, 720)
    private var realtimeClock = false
    private var completedSensorNs = 0L
    private var completedAtMs = 0L
    private val clock = CameraFrameClock()
    private var lastTransform = ""
    var geometryEpoch: Int = 0
        private set
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var lastMovementMs = 0L
    private val mountListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.take(3).any { abs(it) > 0.3f }) lastMovementMs = SystemClock.elapsedRealtime()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    val cameraStable: Boolean get() = SystemClock.elapsedRealtime() - lastMovementMs > 800

    @SuppressLint("MissingPermission")
    fun start(target: TextureView, targetTexture: SurfaceTexture) {
        close()
        val token = generation
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status("Camera permission required", false); return
        }
        view = target; texture = targetTexture
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(mountListener, it, SensorManager.SENSOR_DELAY_UI, main) }
        val manager = context.getSystemService(CameraManager::class.java)
        runCatching {
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: error("No rear camera available")
            val info = manager.getCameraCharacteristics(cameraId)
            sensorRotation = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            realtimeClock = info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            bufferSize = sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .minByOrNull { abs(it.width.toDouble() / it.height - 16.0 / 9.0) * 10000 + abs(it.width - 1280) }
                ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: error("No preview stream available")
            targetTexture.setDefaultBufferSize(bufferSize.width, bufferSize.height)
            updateTransform()
            val output = Surface(targetTexture).also { surface = it }
            status("Opening rear camera", false)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (token != generation) { camera.close(); return }
                    device = camera
                    runCatching {
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (token != generation) { configured.close(); return }
                            session = configured
                            runCatching {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(output)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }.build()
                                configured.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                        if (token != generation) return
                                        completedSensorNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0
                                        completedAtMs = SystemClock.elapsedRealtime()
                                    }
                                }, main)
                                status(if (realtimeClock) "Rear camera live · sensor clock" else "Rear camera live · arrival clock estimate", true)
                            }.onFailure { fail(token, "Camera stream failed: ${it.message}") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); fail(token, "Camera preview unavailable") }
                    }, main)
                    }.onFailure { fail(token, "Camera session unavailable: ${it.message}") }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); fail(token, "Rear camera disconnected") }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail(token, "Rear camera error $error") }
            }, main)
        }.onFailure { fail(token, "Rear camera unavailable: ${it.message}") }
    }

    /** Main-thread snapshot of the latest displayed frame. There is no frame queue to accumulate latency. */
    fun readLatest(target: Bitmap): CameraFrameStamp? {
        val v = view?.takeIf { it.isAvailable } ?: return null
        updateTransform()
        // getBitmap first applies any pending SurfaceTexture update. Read its timestamp afterwards so pixels and
        // evidence time refer to the same displayed buffer. Clearing avoids reusing pixels after a failed copy.
        target.eraseColor(Color.BLACK)
        if (runCatching { v.getBitmap(target) }.getOrNull() == null) return null
        val sensorNs = runCatching { texture?.timestamp ?: 0L }.getOrDefault(0L)
        val stamp = clock.accept(sensorNs, completedSensorNs, completedAtMs, SystemClock.elapsedRealtime(), realtimeClock) ?: return null
        return stamp
    }

    private fun fail(token: Int, message: String) {
        if (token != generation) return
        close(); status(message, false)
    }

    fun close() {
        generation++
        runCatching { session?.stopRepeating() }
        session?.close(); session = null
        device?.close(); device = null
        surface?.release(); surface = null
        view = null; texture = null
        sensors.unregisterListener(mountListener)
        completedSensorNs = 0; completedAtMs = 0; lastTransform = ""; clock.reset()
    }

    /**
     * TextureView already applies sensor orientation. Undo its aspect stretch, compensate only DISPLAY rotation,
     * then centre-crop. YOLO copies this transformed content, matching the overlay coordinate system.
     * See https://developer.android.com/media/camera/camera2/camera-preview#textureview
     */
    private fun updateTransform() {
        val v = view ?: return
        if (v.width <= 0 || v.height <= 0) return
        val displayDegrees = when (v.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val rotation = (360 - displayDegrees) % 360
        val key = "${v.width}:${v.height}:$rotation"
        if (key == lastTransform) return
        lastTransform = key
        geometryEpoch++
        val bw = (if (sensorRotation % 180 == 0) bufferSize.width else bufferSize.height).toFloat()
        val bh = (if (sensorRotation % 180 == 0) bufferSize.height else bufferSize.width).toFloat()
        val rw = if (rotation % 180 == 0) bw else bh
        val rh = if (rotation % 180 == 0) bh else bw
        val scale = max(v.width / rw, v.height / rh)
        val ox = (v.width - rw * scale) / 2f; val oy = (v.height - rh * scale) / 2f
        val raw = arrayOf(0f to 0f, bw to 0f, bw to bh, 0f to bh)
        val dst = raw.flatMap { (x, y) ->
            val p = when (rotation) { 90 -> bh - y to x; 180 -> bw - x to bh - y; 270 -> y to bw - x; else -> x to y }
            listOf(p.first * scale + ox, p.second * scale + oy)
        }.toFloatArray()
        val src = floatArrayOf(0f, 0f, v.width.toFloat(), 0f, v.width.toFloat(), v.height.toFloat(), 0f, v.height.toFloat())
        v.setTransform(Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) })
        // TextureView size changes can reset its buffer size; restore the camera-supported stream dimensions.
        texture?.setDefaultBufferSize(bufferSize.width, bufferSize.height)
    }
}
