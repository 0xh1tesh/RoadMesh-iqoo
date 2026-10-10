package com.jsons.roadmesh.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.atan2

/**
 * MediaPipe Face Landmarker (open source, Apache-2.0), model bundled at [MODEL]. Per frame it returns 478 face landmarks and
 * 52 blendshape scores. Eyes open = 1 - eyeBlink; the mouth is jawOpen. Head pitch comes from the depth of the forehead against
 * the chin and roll from the line between the outer eye corners, both from the landmarks, so the signs are fixed here
 * and do not depend on a matrix convention. Tries the GPU first, so it stays off the CPU the YOLO detector uses.
 * Created and used on the analysis thread only: a GPU delegate belongs to the thread that made it.
 */
class FaceLandmarkerSource(private val context: Context, private val log: (String) -> Unit = {}) : FaceSource {
    private var landmarker: FaceLandmarker
    private var delegate = Delegate.GPU
    override val name get() = "MediaPipe Face Landmarker · ${delegate.name.lowercase()}"

    init {
        landmarker = runCatching { create(Delegate.GPU) }.getOrElse { e ->
            log("Face Landmarker GPU unavailable (${e.message}); using CPU")
            delegate = Delegate.CPU
            create(Delegate.CPU)
        }
    }

    private fun create(delegate: Delegate): FaceLandmarker {
        val base = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(delegate).build()
        return FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base).setRunningMode(RunningMode.VIDEO).setNumFaces(2)
            .setMinFaceDetectionConfidence(0.5f).setMinFacePresenceConfidence(0.5f).setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true).build())
    }

    override fun detect(frame: Bitmap, rotationDegrees: Int, timestampMs: Long): FaceFrame {
        val image = BitmapImageBuilder(frame).build()
        val options = ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()
        val result = try { landmarker.detectForVideo(image, options, timestampMs) } catch (e: RuntimeException) {
            // A GPU delegate that loaded but cannot run: rebuild on the CPU once and carry on.
            if (delegate != Delegate.GPU) throw e
            log("Face Landmarker GPU failed (${e.message}); using CPU")
            landmarker.close(); delegate = Delegate.CPU; landmarker = create(Delegate.CPU)
            landmarker.detectForVideo(image, options, timestampMs)
        }
        val (w, h) = frame.uprightSize(rotationDegrees)
        return read(result, w, h)
    }

    private fun read(result: FaceLandmarkerResult, w: Int, h: Int): FaceFrame {
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return FaceFrame(false)
        // The driver is the biggest face; a passenger further back is smaller.
        val i = faces.indices.maxBy { k -> faces[k].let { f -> (f.maxOf { it.x() } - f.minOf { it.x() }) * (f.maxOf { it.y() } - f.minOf { it.y() }) } }
        val lm = faces[i]
        if (lm.size <= RIGHT_EYE_OUTER) return FaceFrame(false)
        val scores = result.faceBlendshapes().orElse(null)?.getOrNull(i)?.associate { it.categoryName() to it.score() }
        val forehead = lm[FOREHEAD]; val chin = lm[CHIN]
        // Chin up is positive. In the landmark depth a smaller z is nearer the camera, and z is on the same scale as x.
        val pitch = -Math.toDegrees(atan2(((chin.z() - forehead.z()) * w).toDouble(), ((chin.y() - forehead.y()) * h).toDouble())).toFloat()
        val roll = Math.toDegrees(atan2(((lm[RIGHT_EYE_OUTER].y() - lm[LEFT_EYE_OUTER].y()) * h).toDouble(),
            ((lm[RIGHT_EYE_OUTER].x() - lm[LEFT_EYE_OUTER].x()) * w).toDouble())).toFloat()
        val box = RectF(lm.minOf { it.x() }.coerceIn(0f, 1f), lm.minOf { it.y() }.coerceIn(0f, 1f),
            lm.maxOf { it.x() }.coerceIn(0f, 1f), lm.maxOf { it.y() }.coerceIn(0f, 1f))
        return FaceFrame(true, scores?.get("eyeBlinkLeft")?.let { 1f - it }, scores?.get("eyeBlinkRight")?.let { 1f - it },
            pitch, roll, scores?.get("jawOpen"), box)
    }

    override fun close() { landmarker.close() }

    companion object {
        const val MODEL = "models/face_landmarker.task"
        // Indices in the canonical 478 point face mesh.
        private const val FOREHEAD = 10
        private const val CHIN = 152
        private const val LEFT_EYE_OUTER = 33
        private const val RIGHT_EYE_OUTER = 263
    }
}
