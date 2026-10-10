package com.jsons.roadmesh.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.jsons.roadmesh.RoadMeshApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Opt-in local telemetry. No camera upload, no commands into the mesh, no saved token. */
class MeshDeskPublisher(private val app: RoadMeshApplication) : LocationListener, SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = app.getSystemService(LocationManager::class.java)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private var job: Job? = null
    private var listening = false
    private var last: Location? = null
    private var phoneHeading: Double? = null
    private var deceleration: Double? = null
    private var slowingUntil = 0L
    private val mutableStatus = MutableStateFlow("Disconnected")
    val status = mutableStatus.asStateFlow()
    var host = ""; private set
    var port = "8787"; private set
    var enabled = false; private set
    private var token = ""
    private var stage = false

    fun connect(hostInput: String, portInput: String, tokenInput: String, stagePositions: Boolean) {
        val parts = hostInput.trim().split('.').map { it.toIntOrNull() ?: -1 }
        require(parts.size == 4 && parts.all { it in 0..255 } &&
            (parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31)) {
            "Use the laptop's private IPv4 address (10.x, 192.168.x or 172.16–31.x)."
        }
        val number = portInput.toIntOrNull()
        require(number != null && number in 1024..65535) { "Use a port from 1024 to 65535." }
        require(tokenInput.matches(Regex("[A-Za-z0-9_-]{16,100}"))) { "Paste the pairing token printed by Mesh Desk." }
        disconnect()
        host = parts.joinToString("."); port = number.toString(); token = tokenInput; stage = stagePositions; enabled = true
        val sessionId = UUID.randomUUID().toString()
        job = scope.launch {
            var seq = 0L
            while (isActive && enabled) {
                try {
                    val s = app.session.state.value
                    if (s.running && !stage) startSensors() else stopSensors()
                    val packet = snapshot(sessionId, seq++)
                    val target = URL("http://$host:$port/api/telemetry")
                    val auth = token
                    withContext(Dispatchers.IO) {
                        val c = target.openConnection() as HttpURLConnection
                        try {
                            c.instanceFollowRedirects = false; c.requestMethod = "POST"; c.connectTimeout = 1800; c.readTimeout = 1800
                            c.setRequestProperty("Authorization", "Bearer $auth"); c.setRequestProperty("Content-Type", "application/json")
                            c.doOutput = true
                            c.outputStream.use { it.write(packet.toString().toByteArray(Charsets.UTF_8)) }
                            check(c.responseCode == 200) { "Desk returned ${c.responseCode}; check pairing token." }
                            c.inputStream.use { it.readBytes() }
                        } finally { c.disconnect() }
                    }
                    mutableStatus.value = if (!s.running) "Connected · start the mesh to share position" else if (packet.isNull("pose")) "Connected · waiting for a fresh GPS fix" else "Sharing with $host · ${packet.getString("source") }"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutableStatus.value = "Reconnecting · ${e.message?.take(100)}"; delay(1250) }
                delay(250)
            }
        }
    }
    fun disconnect() { enabled = false; job?.cancel(); job = null; stopSensors(); token = ""; mutableStatus.value = "Disconnected" }
    @SuppressLint("MissingPermission")
    private fun startSensors() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this, Looper.getMainLooper())
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        listening = true
    }
    private fun stopSensors() { if (listening) { manager.removeUpdates(this); sensors.unregisterListener(this) }; listening = false; last = null; phoneHeading = null; deceleration = null; slowingUntil = 0 }
    override fun onLocationChanged(location: Location) {
        if (location.isMock) return
        val previous = last
        deceleration = null
        if (previous != null && previous.hasSpeed() && location.hasSpeed() &&
            previous.hasSpeedAccuracy() && location.hasSpeedAccuracy() &&
            previous.speedAccuracyMetersPerSecond <= .7f && location.speedAccuracyMetersPerSecond <= .7f) {
            val dt = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1e9
            if (dt in .2..3.0) {
                val dv = location.speed - previous.speed
                deceleration = dv / dt
                val uncertainty = kotlin.math.hypot(previous.speedAccuracyMetersPerSecond.toDouble(), location.speedAccuracyMetersPerSecond.toDouble())
                if (previous.speed >= 1.2f && dv < -kotlin.math.max(.8, 1.5 * uncertainty) && deceleration!! < -1.2)
                    slowingUntil = SystemClock.elapsedRealtime() + 1500
            }
        }
        last = Location(location)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) phoneHeading = null }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val rotation = FloatArray(9); val angles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values); SensorManager.getOrientation(rotation, angles)
        phoneHeading = (Math.toDegrees(angles[0].toDouble()) + 360) % 360
    }
    private fun snapshot(sessionId: String, seq: Long): JSONObject {
        val s = app.session.state.value; val now = SystemClock.elapsedRealtime()
        var source = "LIVE"
        val pose: JSONObject? = if (stage && s.running) {
            source = "DEMO"
            app.session.here()?.takeIf { it.synthetic }?.let { p -> JSONObject().put("lat",p.lat).put("lon",p.lon)
                .put("headingDeg",p.heading ?: JSONObject.NULL).put("speedMps",p.speedMps ?: JSONObject.NULL)
                .put("accuracyM",JSONObject.NULL).put("ageMs",0).put("headingSource","synthetic") }
        } else last?.takeIf { now - it.elapsedRealtimeNanos / 1_000_000 < 5000 && it.hasAccuracy() && it.accuracy <= 50 }?.let { p ->
            val bearing = if (p.hasBearing() && p.hasSpeed() && p.speed >= 1f) p.bearing.toDouble() else phoneHeading
            JSONObject().put("lat",p.latitude).put("lon",p.longitude).put("headingDeg",bearing ?: JSONObject.NULL)
                .put("speedMps",if(p.hasSpeed()) p.speed.toDouble() else JSONObject.NULL).put("accuracyM",p.accuracy.toDouble())
                .put("ageMs",(now-p.elapsedRealtimeNanos/1_000_000).coerceAtLeast(0))
                .put("headingSource",if(p.hasBearing() && p.hasSpeed() && p.speed>=1f) "GNSS course" else "phone magnetic")
                .put("decelerationMps2",deceleration ?: JSONObject.NULL).put("slowing",now < slowingUntil)
        }
        return JSONObject().put("schema",1).put("nodeId",(s.node ?: app.preferences.nodeId).toString())
            .put("sessionId",sessionId).put("seq",seq).put("sentAt",System.currentTimeMillis()).put("source",source)
            .put("role",s.liveRole.name).put("rank",s.rank).put("meshRunning",s.running).put("pose",pose ?: JSONObject.NULL)
            .put("chain",JSONArray(s.chain.take(100).map { JSONObject().put("id",it.node.toString()).put("rank",it.rank).put("role",it.role.name) }))
            .put("events",JSONArray(s.events.take(32).filter { now-it.atMs in 0..86_400_000 }.map { e ->
                val provenance = s.logs.firstOrNull { it.frame?.id == e.id }?.frame?.provenance?.name ?: "UNKNOWN"
                JSONObject().put("id","${e.id.origin}:${java.lang.Long.toUnsignedString(e.id.session,16)}:${Integer.toUnsignedString(e.id.sequence)}")
                    .put("origin",e.id.origin.toString()).put("via",e.via.toString()).put("kind",e.kind?.name ?: "UNKNOWN")
                    .put("ageMs",now-e.atMs).put("provenance",provenance)
            }))
    }
}
