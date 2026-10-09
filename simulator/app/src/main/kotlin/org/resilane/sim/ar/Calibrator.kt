package org.resilane.sim.ar

import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import org.resilane.core.frame.SharedFrame
import org.resilane.core.math.Pose
import org.resilane.core.math.Quat
import org.resilane.core.math.Vec3
import org.resilane.sim.RLog
import org.resilane.sim.Settings

fun com.google.ar.core.Pose.toCore() = Pose(Vec3(tx(), ty(), tz()), Quat(qx(), qy(), qz(), qw()))
fun Pose.toAr() = com.google.ar.core.Pose(floatArrayOf(t.x, t.y, t.z), floatArrayOf(q.x, q.y, q.z, q.w))

/**
 * Establishes the shared world frame from the printed marker (see [SharedFrame]).
 *
 * Flow: the first time the marker is fully tracked, scanning starts automatically. Marker centre
 * poses from the last 3 s are pooled; once they pass the stability gate (enough samples, < 2 cm
 * and < 2 deg spread) the frame is committed as an ARCore Anchor. Anchors are re-optimised by
 * ARCore as its map improves, so using the anchor's live pose absorbs much of the session drift.
 * Afterwards, whenever the marker is in view, the disagreement between it and the committed frame
 * is shown as drift; Recalibrate re-runs the scan while the old frame stays in use.
 * All runs on the GL thread except [requestCalibration].
 */
class Calibrator(private val settings: Settings) {
    enum class State { UNCALIBRATED, CALIBRATED }

    @Volatile var state = State.UNCALIBRATED; private set
    @Volatile var scanning = false; private set
    @Volatile var message = "Point the camera at the printed marker"; private set
    @Volatile var epoch = 0; private set
    @Volatile var lastEstimate: SharedFrame.Estimate? = null; private set
    /** Drift of the committed frame vs a fresh marker sighting: metres, degrees. */
    @Volatile var residual: Pair<Float, Float>? = null; private set
    @Volatile var markerVisible = false; private set
    @Volatile var commitTimeMs = 0L; private set

    private val gate = SharedFrame.Gate()
    private val samples = ArrayDeque<SharedFrame.MarkerSample>()
    private var anchor: Anchor? = null
    private var lastSeenMs = 0L
    private var scanStartMs = 0L
    @Volatile private var pendingScan = false

    fun requestCalibration() { pendingScan = true }

    /** Called when the marker image database changes (new width): forget everything. */
    fun reset() {
        anchor?.detach(); anchor = null
        state = State.UNCALIBRATED; scanning = false; samples.clear(); residual = null
        message = "Point the camera at the printed marker"
    }

    fun onFrame(session: Session, frame: Frame, now: Long) {
        if (pendingScan) {
            pendingScan = false
            startScan(now, "manual")
        }
        var seen: Pose? = null
        for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
            if (img.trackingState == TrackingState.TRACKING && img.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING) {
                seen = img.centerPose.toCore()
            }
        }
        markerVisible = seen != null
        if (seen != null) {
            lastSeenMs = now
            if (state == State.UNCALIBRATED && !scanning) startScan(now, "marker sighted")
            if (scanning) samples.addLast(SharedFrame.MarkerSample(seen, now))
            sessionFromWorld()?.let { residual = SharedFrame.residual(it, seen, settings.markerHeightM) }
        }
        if (!scanning) return
        while (samples.isNotEmpty() && now - samples.first().timeMs > 3_000) samples.removeFirst()
        val est = SharedFrame.estimate(samples.toList(), settings.markerHeightM)
        lastEstimate = est
        if (est != null && gate.accepts(est)) {
            commit(session, est, now)
        } else {
            message = when {
                samples.isEmpty() && now - lastSeenMs > 1_500 -> "Scanning: point the camera at the marker"
                else -> "Scanning: " + gate.whyNot(est)
            }
            if (now - scanStartMs > 45_000 && state == State.CALIBRATED) {
                scanning = false
                message = "Recalibration timed out; keeping epoch $epoch"
                RLog.w("Calib", message)
            }
        }
    }

    private fun startScan(now: Long, why: String) {
        samples.clear(); scanning = true; scanStartMs = now
        message = "Scanning: hold the marker in view"
        RLog.i("Calib", "scan started ($why)")
    }

    private fun commit(session: Session, est: SharedFrame.Estimate, now: Long) {
        val newAnchor = try {
            session.createAnchor(est.sessionFromWorld.toAr())
        } catch (e: Exception) {
            RLog.e("Calib", "anchor creation failed", e); return
        }
        anchor?.detach()
        anchor = newAnchor
        epoch++
        state = State.CALIBRATED
        scanning = false
        commitTimeMs = now
        residual = null
        message = "Calibrated (epoch $epoch)"
        RLog.i("Calib", "COMMITTED epoch $epoch: n=${est.samples} spread=%.1f cm yaw±%.2f° tilt=%.1f° origin(session)=(%.2f, %.2f, %.2f)"
            .format(est.positionSpreadM * 100, est.yawSpreadDeg, est.tiltDeg, est.sessionFromWorld.t.x, est.sessionFromWorld.t.y, est.sessionFromWorld.t.z))
        if (est.tiltDeg in 20f..70f) RLog.w("Calib", "marker tilted %.0f°: lay it flat, or vertical with Settings > marker height".format(est.tiltDeg))
    }

    /** The shared world expressed in this session, or null if not calibrated. */
    fun sessionFromWorld(): Pose? = anchor?.takeIf { state == State.CALIBRATED }?.pose?.toCore()

    val anchorTracking get() = anchor?.trackingState
}
