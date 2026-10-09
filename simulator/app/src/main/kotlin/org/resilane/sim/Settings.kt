package org.resilane.sim

import android.content.Context
import android.os.Build
import org.resilane.core.sync.RenderMode
import org.resilane.core.sync.VehicleOffset
import kotlin.random.Random

/**
 * Every tunable in the simulator, persisted per phone. Read from any thread (fields are volatile);
 * written from the UI thread, which then calls [save].
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("resilane", Context.MODE_PRIVATE)

    /** Stable random 64-bit node id, created on first launch. */
    val nodeId: Long = prefs.getLong("nodeId", 0L).takeIf { it != 0L }
        ?: Random.nextLong().let { if (it == 0L) 1L else it }.also { prefs.edit().putLong("nodeId", it).apply() }

    @Volatile var nodeName = prefs.getString("nodeName", null) ?: defaultName()
    @Volatile var vehicleColor = prefs.getInt("vehicleColor", PALETTE[(nodeId and 0x7FFFFFFF).toInt() % PALETTE.size])
    @Volatile var modelId = prefs.getInt("modelId", 0)

    /** Real-world length of the vehicle model at scale 1.0, metres. */
    @Volatile var defaultLengthM = prefs.getFloat("defaultLengthM", 4.3f)
    /** Scale this node asks peers to draw its vehicle at; synced fleet-wide when [scaleSyncGlobal]. */
    @Volatile var myVehicleScale = prefs.getFloat("myVehicleScale", 0.5f)
    /** Visualisation-only multiplier applied on this phone, on top of each node's own scale. */
    @Volatile var localScale = prefs.getFloat("localScale", 1.0f)
    @Volatile var scaleSyncGlobal = prefs.getBoolean("scaleSyncGlobal", true)

    /** How other vehicles are drawn: grid-line hologram, or the realistic PBR car (best YOLO recall). */
    @Volatile var hologram = prefs.getBoolean("hologram", true)
    /** Camera height above the floor assumed when there is no floor to measure (3-DoF / no plane yet). */
    @Volatile var eyeHeightM = prefs.getFloat("eyeHeightM", 1.4f)

    /** Vehicle heading from the on-screen joystick (true) or from where the camera looks (false). */
    @Volatile var joystickHeading = prefs.getBoolean("joystickHeading", true)

    @Volatile var renderMode = RenderMode.valueOf(prefs.getString("renderMode", RenderMode.GROUND_LOCKED.name)!!)
    @Volatile var offset = VehicleOffset(prefs.getFloat("offR", 0f), prefs.getFloat("offU", 0f), prefs.getFloat("offF", 0f))

    @Volatile var poseRateHz = prefs.getInt("poseRateHz", 12)
    @Volatile var useNearby = prefs.getBoolean("useNearby", true)
    @Volatile var useUdp = prefs.getBoolean("useUdp", true)

    /** Printed marker width, metres. Must match the paper or every distance is scaled wrong. */
    @Volatile var markerWidthM = prefs.getFloat("markerWidthM", 0.18f)
    /** Height of the marker centre above the ground, metres (0 when it lies on the floor). */
    @Volatile var markerHeightM = prefs.getFloat("markerHeightM", 0f)

    @Volatile var showLabels = prefs.getBoolean("showLabels", true)
    @Volatile var showDistance = prefs.getBoolean("showDistance", true)
    @Volatile var showDebug = prefs.getBoolean("showDebug", true)
    @Volatile var showWindshield = prefs.getBoolean("showWindshield", false)
    @Volatile var showPlanes = prefs.getBoolean("showPlanes", true)
    @Volatile var showRadar = prefs.getBoolean("showRadar", true)
    @Volatile var showOffscreen = prefs.getBoolean("showOffscreen", true)

    @Volatile var yoloEnabled = prefs.getBoolean("yoloEnabled", true)
    @Volatile var yoloHz = prefs.getInt("yoloHz", 4)
    @Volatile var yoloConf = prefs.getFloat("yoloConf", 0.30f)
    @Volatile var datasetCapture = prefs.getBoolean("datasetCapture", false)

    fun save() {
        prefs.edit()
            .putString("nodeName", nodeName).putInt("vehicleColor", vehicleColor).putInt("modelId", modelId)
            .putFloat("defaultLengthM", defaultLengthM).putFloat("myVehicleScale", myVehicleScale)
            .putFloat("localScale", localScale).putBoolean("scaleSyncGlobal", scaleSyncGlobal)
            .putString("renderMode", renderMode.name).putBoolean("joystickHeading", joystickHeading)
            .putBoolean("hologram", hologram).putFloat("eyeHeightM", eyeHeightM)
            .putFloat("offR", offset.right).putFloat("offU", offset.up).putFloat("offF", offset.forward)
            .putInt("poseRateHz", poseRateHz).putBoolean("useNearby", useNearby).putBoolean("useUdp", useUdp)
            .putFloat("markerWidthM", markerWidthM).putFloat("markerHeightM", markerHeightM)
            .putBoolean("showLabels", showLabels).putBoolean("showDistance", showDistance)
            .putBoolean("showDebug", showDebug).putBoolean("showWindshield", showWindshield)
            .putBoolean("showPlanes", showPlanes).putBoolean("showRadar", showRadar)
            .putBoolean("showOffscreen", showOffscreen)
            .putBoolean("yoloEnabled", yoloEnabled).putInt("yoloHz", yoloHz).putFloat("yoloConf", yoloConf)
            .putBoolean("datasetCapture", datasetCapture)
            .apply()
    }

    private fun defaultName(): String {
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "").takeLast(6)
        return "%s-%03X".format(model.ifEmpty { "node" }, (nodeId ushr 52).toInt() and 0xFFF)
    }

    companion object {
        /** Saturated, distinct paints (YOLO detects these better than near-black or near-white). */
        val PALETTE = intArrayOf(
            0xFFB3122A.toInt(), // carmine red
            0xFF1F4FB8.toInt(), // blue
            0xFFF2B705.toInt(), // yellow
            0xFF1E8C4E.toInt(), // green
            0xFFE86A1A.toInt(), // orange
            0xFFE8E8E8.toInt(), // white
            0xFF6B2FB3.toInt(), // purple
        )
    }
}
