package com.jsons.roadmesh.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.jsons.roadmesh.AppPreferences
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.reports.ReportLog
import com.jsons.roadmesh.service.MeshSession
import kotlin.math.sqrt

/**
 * Accident reporting from this phone: an impact the accelerometer feels (over [thresholdG] for [samples] readings in a
 * row, the usual crash-detection floor for phones in a mount), or the driver tapping "Report accident" when they see one.
 * Either way the accident goes to the mesh so cars behind slow down early, and into the report log.
 * A dropped phone also exceeds the threshold; the mesh carries it as an unconfirmed caution, never as a verified crash.
 */
class CrashDetector(private val context: Context, private val mesh: MeshSession, private val reports: ReportLog, private val prefs: AppPreferences,
                    private val thresholdG: Double = 4.0, private val samples: Int = 3) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var over = 0
    private var lastMs = -60_000L
    var listening = false; private set

    fun start() {
        if (listening) return
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        listening = sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }
    fun stop() { sensors?.unregisterListener(this); listening = false; over = 0 }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        over = if (g > thresholdG) over + 1 else 0
        if (over >= samples) { over = 0; report("impact of ${"%.1f".format(g)} g felt by this phone", g.toDouble() / 10) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Sends and logs an accident at this phone's position. Repeats within a minute are dropped. */
    fun report(reason: String, confidence: Double? = null): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMs < 60_000) return false
        lastMs = now
        val provenance = if (mesh.state.value.mode == Mode.STAGE) Provenance.REPLAY else Provenance.LIVE
        val sent = mesh.publish(LocalHazard(HazardKind.ACCIDENT, provenance, reason, HazardDetail(null, prefs.vehicle, confidence?.coerceIn(0.1, 1.0))))
        val here = mesh.here()
        reports.add(HazardKind.ACCIDENT, reason, confidence, here?.lat, here?.lon, here?.synthetic ?: true, null, sent, "this phone")
        return sent
    }
}
