package org.resilane.sim.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.resilane.core.math.Quat
import org.resilane.core.math.Vec3

/**
 * 3-DoF camera orientation from the phone's fused rotation sensor, for the no-ARCore fallback.
 * Rotation only: walking does not move the viewpoint.
 *
 * The sensor frame is X east, Y north, Z up (yaw arbitrary for the game vector); ours is Y up with
 * -Z "north", so world = rotX(-90°) * sensor. In portrait the device axes already match an OpenGL
 * camera (X right, Y up, rear camera looking down -Z), so the result is the camera orientation.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val q4 = FloatArray(4)
    private val toWorld = Quat.axisAngle(Vec3.X, -Math.PI.toFloat() / 2)

    val available get() = sensor != null
    @Volatile var orientation: Quat? = null; private set
    @Volatile var displayRotationDegrees = 0

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm.unregisterListener(this); orientation = null }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getQuaternionFromVector(q4, e.values) // w, x, y, z
        val display = Quat.axisAngle(Vec3.Z, Math.toRadians(displayRotationDegrees.toDouble()).toFloat())
        orientation = (toWorld * Quat(q4[1], q4[2], q4[3], q4[0]) * display).normalized()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
