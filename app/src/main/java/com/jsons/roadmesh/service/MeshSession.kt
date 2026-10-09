package com.jsons.roadmesh.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.jsons.roadmesh.*
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.output.HazardOutput
import com.jsons.roadmesh.radio.*
import com.jsons.roadmesh.sensing.AndroidPositionSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class LogEntry(val utcMs: Long, val monotonicMs: Long, val code: String, val detail: String, val frame: HazardEnvelope? = null)
data class SessionState(
    val role: Role, val mode: Mode, val running: Boolean = false, val ready: Boolean = false,
    val status: String = "Choose a role, then start", val lastReceived: HazardEnvelope? = null,
    val lastTx: HazardEnvelope? = null, val alert: MeshEffect.Announce? = null,
    val counters: Map<String, Int> = emptyMap(), val logs: List<LogEntry> = emptyList(), val bounds: MeshBounds = MeshBounds(0, 0, 0),
)

class MeshSession(private val context: Context, val preferences: AppPreferences) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(SessionState(preferences.role, preferences.profile.mode))
    val state = mutableState.asStateFlow()
    private var scope: CoroutineScope? = null
    private var commands: Channel<Command>? = null
    private var tx: Channel<MeshEffect.Transmit>? = null
    private var transport: HazardTransport? = null
    private var position: AndroidPositionSource? = null
    private var output: HazardOutput? = null
    private var activeProfile: MeshProfile? = null
    private var activeNode: NodeId? = null
    private var activeRole: Role? = null
    private var sessionNonce = 0L
    @Volatile private var accepting = false
    private sealed interface Command {
        data class Receive(val bytes: ByteArray) : Command
        data class Publish(val hazard: LocalHazard) : Command
        data object Tick : Command
    }

    fun select(role: Role) {
        check(!state.value.running) { "Stop the session before changing roles" }
        preferences.role = role; update { it.copy(role = role, lastReceived = null, lastTx = null, alert = null) }
    }
    fun mode(mode: Mode) {
        check(!state.value.running) { "Stop the session before changing mode" }
        preferences.setMode(mode); update { it.copy(mode = mode) }
    }
    fun saveProfile(text: String) {
        check(!state.value.running) { "Stop the session before changing settings" }
        val p = preferences.saveProfile(text); update { it.copy(mode = p.mode) }
    }

    /** Called only from the foreground service after Android's foreground notification is active. */
    fun begin(onFailure: () -> Unit) {
        if (scope != null) return
        val profile = preferences.profile
        val role = preferences.role
        val node = if (profile.mode == Mode.STAGE) NodeId(role.stageId) else preferences.nodeId
        val alias = if (profile.mode == Mode.STAGE) role.stageId.toInt() else preferences.alias
        sessionNonce = SecureRandom().nextLong()
        activeProfile = profile; activeNode = node; activeRole = role
        update { SessionState(role, profile.mode, running = true, status = "Starting BLE…") }
        record("session_start", "node=$node session=${java.lang.Long.toUnsignedString(sessionNonce, 16)}; ${profile.mode}; ${role.name}")
        val clock = object : MeshClock { override fun monotonicMs() = SystemClock.elapsedRealtime(); override fun utcMs() = System.currentTimeMillis() }
        val source: PositionSource = if (profile.mode == Mode.STAGE) PositionSource { stageFix(role) }
        else AndroidPositionSource(context).also { position = it; it.start() }
        val engine = MeshEngine(node, alias, sessionNonce, profile, clock, source,
            if (profile.mode == Mode.STAGE) StageChain else OpenLinks, announce = profile.mode == Mode.ROAD || role == Role.OBSERVER)
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val incoming = Channel<Command>(256).also { commands = it }
        val outgoing = Channel<MeshEffect.Transmit>(64).also { tx = it }
        output = HazardOutput(context) { record("output", it) }
        val ble = BleHazardTransport(context, { message -> record("radio", message) }, { ready, status ->
            update { it.copy(ready = ready, status = status) }
        }).also { transport = it }
        jobScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            try {
                ble.start { bytes ->
                    if (accepting && !incoming.trySend(Command.Receive(bytes)).isSuccess) count("rx_queue_full")
                }
                accepting = true
                launch {
                    while (isActive) { delay(25); incoming.trySend(Command.Tick) }
                }
                launch(Dispatchers.Default) {
                    for (request in outgoing) {
                        try {
                            // Discard a queued burst once its originating hazard has expired.
                            val age = generationAgeMs(request.frame.packet.tGenDeci, clock.utcMs())
                            if (age > minOf(profile.maxAgeMs, request.frame.packet.validityS * 1_000L) || age < -profile.futureToleranceMs) {
                                count("tx_expired"); continue
                            }
                            val events = ble.transmit(HazardEnvelopeCodec.encode(request.frame), request.relay)
                            count(if (request.relay) "relay_burst" else "origin_burst")
                            record("tx_complete", "${frameDetail(request.frame)} requestedEvents=$events; remote delivery unknown", request.frame)
                            update { it.copy(lastTx = request.frame) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            count("tx_error"); record("tx_error", e.message.orEmpty())
                            update { it.copy(ready = false, status = "BLE transmit failed · stop and restart") }
                            onFailure()
                            break
                        }
                    }
                }
                for (command in incoming) {
                    val effects = when (command) {
                        is Command.Receive -> engine.receive(command.bytes)
                        is Command.Publish -> engine.publish(command.hazard)
                        Command.Tick -> { ble.maintain(clock.monotonicMs()); engine.tick() }
                    }
                    for (effect in effects) when (effect) {
                        is MeshEffect.Originated -> { count("originated"); record("originated", "sourceEvent=${effect.sourceEvent}", effect.frame) }
                        is MeshEffect.Transmit -> {
                            record("tx_queued", frameDetail(effect.frame), effect.frame)
                            if (!outgoing.trySend(effect).isSuccess) count("tx_queue_full")
                        }
                        is MeshEffect.Received -> {
                            count("received"); record("received", frameDetail(effect.frame), effect.frame)
                            update { it.copy(lastReceived = effect.frame) }
                        }
                        is MeshEffect.Announce -> {
                            count("caution"); record("caution", frameDetail(effect.frame), effect.frame)
                            update { it.copy(alert = effect) }
                            withContext(Dispatchers.Main.immediate) { output?.announce(effect) }
                        }
                        is MeshEffect.Notice -> { count(effect.code); if (effect.code != "duplicate" && effect.code != "link_filtered") record(effect.code, effect.detail) }
                    }
                    update { it.copy(bounds = engine.bounds) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                record("session_error", e.message.orEmpty())
                update { it.copy(status = e.message ?: "Session failed", ready = false) }
                onFailure()
            }
        }
    }

    fun publish(hazard: LocalHazard): Boolean {
        if (!state.value.ready) { record("source_rejected", "Start a ready mesh session first"); return false }
        if (state.value.mode == Mode.STAGE && state.value.role != Role.DETECTOR) return false
        return commands?.trySend(Command.Publish(hazard))?.isSuccess == true
    }
    fun clearAlert() = update { it.copy(alert = null) }
    fun fail(message: String) { record("service_error", message); update { it.copy(status = message, ready = false) } }
    fun stop() {
        accepting = false
        commands?.close(); tx?.close(); scope?.cancel(); scope = null; commands = null; tx = null
        transport?.close(); transport = null; position?.close(); position = null; output?.close(); output = null
        record("session_stop", "resources released")
        update { it.copy(running = false, ready = false, status = if (it.ready || it.status == "Starting BLE…") "Stopped" else it.status) }
    }
    fun record(code: String, detail: String, frame: HazardEnvelope? = null) = update {
        it.copy(logs = (it.logs + LogEntry(System.currentTimeMillis(), SystemClock.elapsedRealtime(), code, detail.take(1_000), frame)).takeLast(512))
    }
    private fun count(code: String) = update { it.copy(counters = it.counters + (code to ((it.counters[code] ?: 0) + 1))) }
    private fun update(block: (SessionState) -> SessionState) { synchronized(lock) { mutableState.value = block(mutableState.value) } }

    suspend fun export(): String = withContext(Dispatchers.IO) {
        val snapshot = state.value
        val exportedRole = activeRole ?: snapshot.role
        val exportedProfile = activeProfile ?: preferences.profile
        val doc = JSONObject().apply {
            put("schema", 1); put("exportedUtcMs", System.currentTimeMillis()); put("role", exportedRole.name)
            put("nodeId", activeNode?.toString() ?: preferences.nodeId.toString())
            put("session", java.lang.Long.toUnsignedString(sessionNonce, 16)); put("profile", profileJson(exportedProfile))
            put("disclosure", (if (exportedProfile.mode == Mode.STAGE) "Virtual brake input; synthetic stage positions; enforced A-B-C acceptance filter. " else "Live GNSS; no synthetic fallback; live hazard source adapters are not connected in this slice. ") + "Unsigned caution. TX completion is local only. No delivery receipt or clock-derived latency.")
            put("counters", JSONObject(snapshot.counters)); put("retainedLogLimit", 512)
            put("bounds", JSONObject().put("dedup", snapshot.bounds.dedup).put("neighbors", snapshot.bounds.neighbors).put("pending", snapshot.bounds.pending))
            put("events", JSONArray(snapshot.logs.map { JSONObject().put("utcMs", it.utcMs).put("monotonicMs", it.monotonicMs).put("code", it.code).put("detail", it.detail).apply {
                it.frame?.let { frame -> put("frame", frameJson(frame)) }
            } }))
        }
        val name = "roadmesh-${exportedRole.name.lowercase()}-${System.currentTimeMillis()}.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RoadMesh")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create export")
        try {
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(doc.toString(2)) } ?: error("Cannot write export")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        "Downloads/RoadMesh/$name"
    }
}

fun frameDetail(frame: HazardEnvelope) = "origin=${frame.id.origin} session=${java.lang.Long.toUnsignedString(frame.id.session, 16)} seq=${Integer.toUnsignedString(frame.id.sequence)} forwarder=${frame.forwarder} ttl=${frame.packet.ttl} relays=${frame.packet.hop} radioLinks=${frame.radioLinks} ${frame.provenance}"
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
fun frameJson(frame: HazardEnvelope) = JSONObject().apply {
    put("origin", frame.id.origin.toString()); put("session", java.lang.Long.toUnsignedString(frame.id.session, 16))
    put("sequence", Integer.toUnsignedString(frame.id.sequence)); put("forwarder", frame.forwarder.toString())
    put("ttl", frame.packet.ttl); put("relayCount", frame.packet.hop); put("radioLinks", frame.radioLinks)
    put("provenance", frame.provenance.name); put("hex", HazardEnvelopeCodec.encode(frame).toHex())
}
