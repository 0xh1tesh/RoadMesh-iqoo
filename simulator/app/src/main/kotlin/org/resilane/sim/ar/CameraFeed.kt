package org.resilane.sim.ar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import org.resilane.sim.RLog
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

/**
 * Plain rear-camera preview for phones where ARCore can't run (not on Google's supported list).
 * Camera2 streams into a SurfaceTexture bound to the background renderer's OES texture, so the
 * rest of the pipeline (cars drawn over the camera, PixelCopy for YOLO) is unchanged.
 *
 * Threading: [start]/[stop] on the UI thread (stop only after the GL view is paused), everything
 * else on the GL thread.
 */
class CameraFeed(private val context: Context) {
    @Volatile var wanted = false; private set
    @Volatile var error: String? = null; private set
    /** Field of view across the image's long side (portrait: vertical), radians. */
    @Volatile var longFovRad = Math.toRadians(70.0).toFloat(); private set
    /** Buffer size as delivered (landscape) and whether the sensor is mounted sideways. */
    @Volatile var size = Size(1920, 1080); private set
    @Volatile var sideways = true; private set

    private val lock = Any()
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var boundTex = 0
    private var device: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var hasFrame = false

    /** Width / height of the image as it appears upright on the portrait screen. */
    val imageAspect: Float
        get() = if (sideways) size.height.toFloat() / size.width else size.width.toFloat() / size.height

    /** Vertical field of view of the screen (degrees) after the background's centre-crop. */
    fun screenFovYDeg(screenAspect: Float): Float {
        val longPx = max(size.width, size.height).toFloat()
        val uprightH = if (sideways) size.width else size.height
        var tanHalf = tan(longFovRad / 2f) * (uprightH / longPx)
        if (screenAspect > imageAspect) tanHalf *= imageAspect / screenAspect
        return Math.toDegrees(2.0 * atan(tanHalf.toDouble())).toFloat()
    }

    fun start() {
        wanted = true
        if (thread == null) thread = HandlerThread("rl-camera").also { it.start(); handler = Handler(it.looper) }
    }

    fun stop() {
        wanted = false
        synchronized(lock) {
            try { capture?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            capture = null; device = null
            surface?.release(); texture?.release()
            surface = null; texture = null; boundTex = 0; hasFrame = false
        }
    }

    /**
     * GL thread, once per frame: (re)binds the stream to [texId] if needed and latches the newest
     * image. Returns false until the first camera frame has arrived.
     */
    fun update(texId: Int, texMatrix: FloatArray): Boolean {
        if (!wanted) return false
        synchronized(lock) {
            if (texture == null || boundTex != texId) {
                surface?.release(); texture?.release()
                try { capture?.close(); device?.close() } catch (_: Exception) {}
                capture = null; device = null; hasFrame = false
                val st = SurfaceTexture(texId)
                st.setOnFrameAvailableListener { hasFrame = true }
                texture = st; boundTex = texId
                open(st)
            }
            val st = texture ?: return false
            if (!hasFrame) return false
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            return true
        }
    }

    @SuppressLint("MissingPermission") // checked by the activity before start()
    private fun open(st: SurfaceTexture) {
        try {
            val mgr = context.getSystemService(CameraManager::class.java)
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: run { error = "no rear camera"; return }
            val ch = mgr.getCameraCharacteristics(id)
            val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(SurfaceTexture::class.java)
            // Widest 16:9-ish stream up to 1080p: least cropping on a tall portrait screen, cheap to sample.
            val pick = sizes.filter { it.width <= 1920 && it.height <= 1080 }
                .sortedWith(compareBy<Size>({ abs(it.width.toFloat() / it.height - 16f / 9f) > 0.05f }, { -it.width * it.height }))
                .firstOrNull() ?: sizes.first()
            size = pick
            sideways = (ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) % 180 != 0
            st.setDefaultBufferSize(pick.width, pick.height)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal != null && phys != null && focal > 0f) {
                // A wider-than-sensor stream (16:9 from 4:3) keeps the full long side.
                longFovRad = 2f * atan(max(phys.width, phys.height) / 2f / focal)
            }
            RLog.i("Track", "3-DoF camera $id: ${pick.width}x${pick.height}, long-side FOV %.1f°".format(Math.toDegrees(longFovRad.toDouble())))
            val surf = Surface(st)
            surface = surf
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    synchronized(lock) {
                        if (!wanted || surface !== surf) { d.close(); return }
                        device = d
                    }
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            synchronized(lock) { if (device !== d) { s.close(); return }; capture = s }
                            try {
                                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surf)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }.build()
                                s.setRepeatingRequest(req, null, handler)
                                error = null
                            } catch (e: Exception) { fail("preview failed: $e") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = fail("camera session configuration failed")
                    }, handler)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); fail("camera disconnected") }
                override fun onError(d: CameraDevice, code: Int) { d.close(); fail("camera error $code") }
            }, handler)
        } catch (e: Exception) {
            fail("camera open failed: $e")
        }
    }

    private fun fail(msg: String) {
        error = msg
        RLog.w("Track", msg)
    }
}
