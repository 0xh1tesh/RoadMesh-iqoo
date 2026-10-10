package com.jsons.roadmesh.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF

/** What one camera frame shows of the driver face, in the units [com.jsons.roadmesh.core.perception.FaceSample] uses. */
data class FaceFrame(
    val face: Boolean,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
    /** Degrees from the camera view: pitch positive with the chin up, roll the sideways tilt. */
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val jawOpen: Float? = null,
    /** The face as a share of the upright frame (0..1), not mirrored. */
    val box: RectF? = null,
)

/** A face model that reads one frame at a time on the analysis thread. */
interface FaceSource : AutoCloseable {
    /** Shown to the driver and the jury, so they know which model is running. */
    val name: String
    /** [frame] is as the camera delivered it; [rotationDegrees] turns it upright. [timestampMs] only ever increases. */
    fun detect(frame: Bitmap, rotationDegrees: Int, timestampMs: Long): FaceFrame
}

/** Width and height of [this] once turned upright by [rotationDegrees]. */
internal fun Bitmap.uprightSize(rotationDegrees: Int) =
    if (rotationDegrees % 180 == 0) width to height else height to width

/** Opens the open-source Face Landmarker, and the ML Kit detector only if the Landmarker cannot start. */
object FaceSources {
    fun open(context: Context, log: (String) -> Unit): FaceSource =
        runCatching { FaceLandmarkerSource(context, log) }
            .onFailure { log("Face Landmarker unavailable (${it.message}); using ML Kit instead") }
            .getOrElse { MlKitFaceSource() }
}
