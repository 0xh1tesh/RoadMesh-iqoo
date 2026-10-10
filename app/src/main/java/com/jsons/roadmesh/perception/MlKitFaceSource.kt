package com.jsons.roadmesh.perception

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** ML Kit face detection, bundled model, offline. Free to use but not open source: only the fallback for [FaceLandmarkerSource]. */
class MlKitFaceSource : FaceSource {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.25f).build())
    override val name = "ML Kit face detection"

    override fun detect(frame: Bitmap, rotationDegrees: Int, timestampMs: Long): FaceFrame {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, rotationDegrees)))
        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return FaceFrame(false)
        val (w, h) = frame.uprightSize(rotationDegrees)
        val b = f.boundingBox
        return FaceFrame(true, f.leftEyeOpenProbability, f.rightEyeOpenProbability, f.headEulerAngleX, f.headEulerAngleZ, null,
            RectF(b.left / w.toFloat(), b.top / h.toFloat(), b.right / w.toFloat(), b.bottom / h.toFloat()))
    }

    override fun close() { detector.close() }
}
