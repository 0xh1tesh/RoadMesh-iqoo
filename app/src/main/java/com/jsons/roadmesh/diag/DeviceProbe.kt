package com.jsons.roadmesh.diag

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import com.jsons.roadmesh.radio.BleProbe
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One line of the device report. ok = null means "information only". */
data class Probe(val name: String, val value: String, val ok: Boolean? = null)

val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.NEARBY_WIFI_DEVICES,
)

object DeviceProbe {

    /** Everything that can be read at once with no user action. */
    fun readStatic(context: Context): List<Probe> {
        val out = mutableListOf<Probe>()
        out += buildInfo()
        out += bluetooth(context)
        out += wifiAware(context)
        out += features(context)
        out += sensors(context)
        out += battery(context)
        out += permissions(context)
        return out
    }

    private fun buildInfo(): List<Probe> = listOf(
        Probe("Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"),
        Probe("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}"),
        Probe("Build display", Build.DISPLAY),
        Probe("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"),
        Probe("OriginOS / vivo property", systemProperty("ro.vivo.os.version") ?: systemProperty("ro.vivo.os.name") ?: "not readable"),
    )

    private fun bluetooth(context: Context): List<Probe> {
        val c = BleProbe.readCaps(context)
        if (!c.hasAdapter) return listOf(Probe("Bluetooth adapter", "none", false))
        return listOf(
            Probe("Bluetooth enabled", c.enabled.toString(), c.enabled),
            Probe("BLE extended advertising", c.extendedAdv.toString(), c.extendedAdv),
            Probe("BLE 2M PHY", c.le2mPhy.toString(), c.le2mPhy),
            Probe("BLE Coded PHY", c.codedPhy.toString(), c.codedPhy),
            Probe("BLE periodic advertising", c.periodicAdv.toString()),
            Probe("BLE max advertising data length", c.maxAdvDataLength.toString(), c.maxAdvDataLength >= 251),
            Probe("BLE multiple advertisement", c.multipleAdv.toString(), c.multipleAdv),
            Probe("BLE offloaded filtering", c.offloadedFiltering.toString()),
        )
    }

    private fun wifiAware(context: Context): List<Probe> {
        val feature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val out = mutableListOf(Probe("Wi-Fi Aware feature", feature.toString(), feature))
        val manager = context.getSystemService(WifiAwareManager::class.java)
        if (manager == null) {
            out += Probe("Wi-Fi Aware manager", "none", false)
            return out
        }
        out += Probe("Wi-Fi Aware available now", manager.isAvailable.toString(), manager.isAvailable)
        val ch = runCatching { manager.characteristics }.getOrNull()
        if (ch != null) {
            out += Probe("Aware max service info", ch.maxServiceSpecificInfoLength.toString())
            out += Probe("Aware max match filter", ch.maxMatchFilterLength.toString())
            out += Probe("Aware instant mode", ch.isInstantCommunicationModeSupported.toString())
        } else {
            out += Probe("Aware characteristics", "not available")
        }
        return out
    }

    private fun features(context: Context): List<Probe> {
        val pm = context.packageManager
        val wanted = listOf(
            "Bluetooth LE" to PackageManager.FEATURE_BLUETOOTH_LE,
            "Wi-Fi RTT" to PackageManager.FEATURE_WIFI_RTT,
            "UWB" to "android.hardware.uwb",
            "GPS" to PackageManager.FEATURE_LOCATION_GPS,
            "Camera (any)" to PackageManager.FEATURE_CAMERA_ANY,
            "Accelerometer" to PackageManager.FEATURE_SENSOR_ACCELEROMETER,
            "Gyroscope" to PackageManager.FEATURE_SENSOR_GYROSCOPE,
            "Barometer" to PackageManager.FEATURE_SENSOR_BAROMETER,
        )
        return wanted.map { (label, feature) -> pm.hasSystemFeature(feature).let { Probe("Feature $label", it.toString(), it) } }
    }

    private fun sensors(context: Context): List<Probe> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return emptyList()
        val wanted = listOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "linear acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
            "gravity" to Sensor.TYPE_GRAVITY,
            "rotation vector" to Sensor.TYPE_ROTATION_VECTOR,
        )
        return wanted.map { (label, type) ->
            val s = sm.getDefaultSensor(type)
            if (s == null) Probe("Sensor $label", "missing", false)
            else {
                val hz = if (s.minDelay > 0) 1_000_000 / s.minDelay else 0
                Probe("Sensor $label", "max ${hz} Hz, range ${s.maximumRange}, ${s.vendor}")
            }
        }
    }

    private fun battery(context: Context): List<Probe> {
        val pm = context.getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        return listOf(Probe("Battery optimisation exempt (our app)", exempt.toString(), exempt))
    }

    private fun permissions(context: Context): List<Probe> =
        REQUIRED_PERMISSIONS.map {
            val granted = context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            Probe("Permission ${it.substringAfterLast('.')}", granted.toString(), granted)
        }

    /** Text-to-speech engines and voices. Asynchronous: the engine initialises on a worker. */
    fun readTts(context: Context, onDone: (List<Probe>) -> Unit) {
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TextToSpeech(context) { status ->
            val tts = holder[0]
            if (status != TextToSpeech.SUCCESS || tts == null) {
                onDone(listOf(Probe("TTS init", "failed (status $status)", false)))
                return@TextToSpeech
            }
            val out = mutableListOf<Probe>()
            out += Probe("TTS engine", tts.defaultEngine ?: "none")
            val locales = listOf(
                "en-IN" to Locale.Builder().setLanguage("en").setRegion("IN").build(),
                "en-US" to Locale.US,
                "hi-IN" to Locale.Builder().setLanguage("hi").setRegion("IN").build(),
                "kn-IN" to Locale.Builder().setLanguage("kn").setRegion("IN").build(),
            )
            for ((label, locale) in locales) {
                val r = tts.isLanguageAvailable(locale)
                out += Probe("TTS $label", availabilityName(r), r >= TextToSpeech.LANG_AVAILABLE)
            }
            val offline = tts.voices
                ?.filter { !it.isNetworkConnectionRequired }
                ?.map { it.locale.toLanguageTag() }
                ?.distinct()?.sorted()
            out += Probe("TTS offline voice languages", offline?.joinToString(", ").orEmpty().ifEmpty { "none" })
            tts.shutdown()
            onDone(out)
        }
    }

    private fun availabilityName(code: Int): String = when (code) {
        TextToSpeech.LANG_AVAILABLE -> "available"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "available (country)"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "available (variant)"
        TextToSpeech.LANG_MISSING_DATA -> "missing data"
        TextToSpeech.LANG_NOT_SUPPORTED -> "not supported"
        else -> "code $code"
    }

    /** Writes the report to Downloads as JSON. Returns the file name, or an error message. */
    fun exportJson(context: Context, probes: List<Probe>): String {
        val arr = JSONArray()
        probes.forEach {
            arr.put(JSONObject().put("name", it.name).put("value", it.value).put("ok", it.ok ?: JSONObject.NULL))
        }
        val json = JSONObject().put("device", Build.MODEL).put("time", System.currentTimeMillis()).put("probes", arr)
        val name = "roadmesh-probe-${Build.MODEL.replace(' ', '_')}-${System.currentTimeMillis()}.json"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return try {
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "export failed: no uri"
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
            "saved Downloads/$name"
        } catch (e: Exception) {
            "export failed: ${e.javaClass.simpleName} ${e.message}"
        }
    }

    private fun systemProperty(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val value = cls.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
        value.ifEmpty { null }
    }.getOrNull()
}
