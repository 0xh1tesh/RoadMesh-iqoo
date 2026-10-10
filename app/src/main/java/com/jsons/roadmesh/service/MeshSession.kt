package com.jsons.roadmesh.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.jsons.roadmesh.*
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.insight.NodeInference
import com.jsons.roadmesh.core.road.*
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * One warning's path through this phone: where it came from, what this node concluded, and whether it was passed on.
 * [inference] is deterministic (origin range + distance to origin); the co-pilot only phrases it.
 */
data class EventTrace(
    val id: MessageId, val kind: HazardKind?, val detail: HazardDetail?, val originated: Boolean,
    val via: NodeId, val atMs: Long, val toOriginM: Double? = null, val inference: NodeInference? = null,
    val sentAtMs: Long? = null, val relayedAtMs: Long? = null, val announced: Boolean = false, val hops: Int = 0, val canRelay: Boolean = false,
    val viaRank: Int = NO_RANK,
)

/** Stage phones 1, 2, 3 are A, B, C; anything else shows the last four hex digits of its id. */
fun NodeId.letter(): String = if (value in 1L..3L) ('A' + (value - 1).toInt()).toString() else toString().takeLast(4).uppercase()
/** A chain position as a letter: 0 is A (the observer), 1 is B, and so on. */
fun rankLetter(rank: Int): String = if (rank in 0..25) ('A' + rank).toString() else "?"

data class LogEntry(val utcMs: Long, val monotonicMs: Long, val code: String, val detail: String, val frame: HazardEnvelope? = null)
data class SessionState(
    val role: Role, val mode: Mode, val running: Boolean = false, val ready: Boolean = false,
    val status: String = "Start drive or a replay demo", val lastReceived: HazardEnvelope? = null,
    val lastTx: HazardEnvelope? = null, val alert: MeshEffect.Announce? = null,
    val counters: Map<String, Int> = emptyMap(), val logs: List<LogEntry> = emptyList(), val bounds: MeshBounds = MeshBounds(0, 0, 0),
    val events: List<EventTrace> = emptyList(), val node: NodeId? = null,
    /** The mesh as this phone sees it right now, in chain order. */
    val chain: List<Member> = emptyList(),
    /** Hazards this phone holds on its road map. */
    val known: List<KnownHazard> = emptyList(),
    val picture: RoadPicture? = null, val pictureAtMs: Long? = null, val asking: Boolean = false,
    val recoveries: Int = 0,
) {
    val me get() = chain.firstOrNull { it.self }
    /** The role the chain gave this phone; before anyone is heard, the role picked at setup. */
    val liveRole get() = me?.role ?: role
    val rank get() = me?.rank ?: NO_RANK
}

class MeshSession(private val context: Context, val preferences: AppPreferences) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(SessionState(preferences.role, preferences.profile.mode))
    val state = mutableState.asStateFlow()
    private var scope: CoroutineScope? = null
    private var commands: Channel<Command>? = null
    private var tx: Outbox? = null
    private var transport: HazardTransport? = null
    private var position: AndroidPositionSource? = null
    private var source: PositionSource? = null
    private var output: HazardOutput? = null
    private var activeProfile: MeshProfile? = null
    private var activeNode: NodeId? = null
    private var activeRole: Role? = null
    private var sessionNonce = 0L
    @Volatile private var accepting = false
    @Volatile private var observing = false
    private var pictureDone: ((RoadPicture) -> Unit)? = null
    private sealed interface Command {
        data class Receive(val bytes: ByteArray) : Command
        data class Publish(val hazard: LocalHazard, val at: GeoFix?) : Command
        data class Ask(val done: (RoadPicture) -> Unit) : Command
        data object Tick : Command
    }
    private sealed interface Outgoing {
        data class Hazard(val effect: MeshEffect.Transmit) : Outgoing
        data class Control(val frame: ControlFrame, val label: String) : Outgoing
    }

    /** Pending radio work is bounded and ordered by hazard urgency rather than camera frame rate. */
    private class Outbox(private val profile: MeshProfile) {
        private val queue = PriorityOutbox<Outgoing>(64)
        private val wake = Channel<Unit>(Channel.CONFLATED)
        @Volatile private var closed = false
        @Synchronized fun offer(request: Outgoing): Boolean {
            if (closed) return false
            val now = SystemClock.elapsedRealtime()
            val priority: PriorityOutbox.Priority
            val expires: Long
            val key: String?
            when (request) {
                is Outgoing.Hazard -> {
                    val f = request.effect.frame
                    val age = generationAgeMs(f.packet.tGenDeci, System.currentTimeMillis()).coerceAtLeast(0)
                    expires = now + minOf(profile.maxAgeMs, f.packet.validityS * 1_000L) - age
                    priority = when (f.kind) {
                        HazardKind.BRAKING, HazardKind.VEHICLE_CLOSE, HazardKind.PEDESTRIAN, HazardKind.ACCIDENT -> PriorityOutbox.Priority.URGENT
                        else -> if (f.kind?.use == HazardUse.CAUTION || f.kind?.use == HazardUse.CANCEL) PriorityOutbox.Priority.CONDITION else PriorityOutbox.Priority.CONTEXT
                    }
                    key = "event:${f.id}"
                }
                is Outgoing.Control -> {
                    priority = PriorityOutbox.Priority.CONTEXT
                    expires = now + if (request.frame is ControlFrame.Presence) 1_500 else 2_500
                    key = if (request.frame is ControlFrame.Presence) "presence" else null
                }
            }
            val admitted = queue.offer(request, priority, now, expires, key)
            if (admitted) wake.trySend(Unit)
            return admitted
        }
        suspend fun next(): Outgoing? {
            while (!closed) {
                queue.poll(SystemClock.elapsedRealtime())?.let { return it }
                if (wake.receiveCatching().isClosed) return null
            }
            return null
        }
        @Synchronized fun close() { closed = true; queue.clear(); wake.close() }
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
    /** A capability flag. Every road phone can observe, relay and receive concurrently. */
    fun setObserving(on: Boolean) { observing = on }

    /** Called only from the foreground service after Android's foreground notification is active. */
    fun begin(onFailure: () -> Unit) {
        if (scope != null) return
        val profile = preferences.profile
        val role = preferences.role
        val node = preferences.nodeId
        val alias = preferences.alias
        sessionNonce = SecureRandom().nextLong()
        activeProfile = profile; activeNode = node; activeRole = role
        update { SessionState(role, profile.mode, running = true, status = "Starting BLE…", node = node) }
        record("session_start", "node=$node session=${java.lang.Long.toUnsignedString(sessionNonce, 16)}; ${profile.mode}; ${role.name}")
        val clock = object : MeshClock { override fun monotonicMs() = SystemClock.elapsedRealtime(); override fun utcMs() = System.currentTimeMillis() }
        val roster = ChainRoster(node, clock.monotonicMs(), byRoad = profile.mode == Mode.ROAD).also { it.vehicle = preferences.vehicle }
        val source: PositionSource = if (profile.mode == Mode.STAGE) PositionSource { stageFix(roster.rank.takeIf { it != NO_RANK } ?: 0) }
        else AndroidPositionSource(context).also { position = it; it.start() }
        this.source = source
        val engine = MeshEngine(node, alias, sessionNonce, profile, clock, source,
            if (profile.mode == Mode.STAGE) ChainLinks { roster.rank } else OpenLinks, announce = true,
            rank = { roster.rank.coerceAtMost(254) }, relayGate = { profile.mode == Mode.ROAD || roster.hasBehind })
        val map = RoadMap()
        val queries = RoadQueries(node, sessionNonce, ttl = profile.initialTtl.coerceAtLeast(1), records = { from, now, r -> map.records(from, now, r) },
            here = { source.current()?.takeIf { it.valid() } }, rank = { roster.rank })
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val incoming = Channel<Command>(256).also { commands = it }
        val outgoing = Outbox(profile).also { tx = it }
        if (output == null) output = HazardOutput(context) { record("output", it) }
        val ble = BleHazardTransport(context, { message -> record("radio", message) }, { ready, status ->
            update { it.copy(ready = ready, status = status) }
        }).also { transport = it }
        jobScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            try {
                ble.start { bytes ->
                    if (accepting && !incoming.trySend(Command.Receive(bytes)).isSuccess) count("rx_queue_full")
                }
                accepting = true
                launch { while (isActive) { delay(25); incoming.trySend(Command.Tick) } }
                launch(Dispatchers.Default) { transmitLoop(ble, outgoing, clock, profile) }
                var lastBeat = 0L
                var lastContextQuery = Long.MIN_VALUE
                var beaconWorks = true
                for (command in incoming) {
                    val now = clock.monotonicMs()
                    val effects = when (command) {
                        is Command.Receive -> {
                            if (command.bytes.firstOrNull()?.toInt()?.and(255) == ControlCodec.MAGIC) {
                                val frame = ControlCodec.decode(command.bytes)
                                if (frame == null) { count("control_rejected"); emptyList() }
                                else {
                                    if (frame is ControlFrame.Presence) { roster.hear(frame, now); count("presence_heard") }
                                    else {
                                        if (frame is ControlFrame.Reply) {
                                            val utc = clock.utcMs()
                                            for (r in frame.records) {
                                                val id = r.observationId ?: continue
                                                val capturedUtc = r.capturedUtcMs ?: continue
                                                val provenance = r.provenance ?: continue
                                                if (capturedUtc < 0 || capturedUtc > utc || profile.mode == Mode.ROAD && provenance != Provenance.LIVE) {
                                                    count("evidence_scope_rejected"); continue
                                                }
                                                val age = maxOf(utc - capturedUtc, r.ageS * 1000L)
                                                val at = moved(frame.fix, r.northM.toDouble(), r.eastM.toDouble()).copy(accuracyM = r.accuracyM)
                                                map.add(r.kind, at, id.origin, now, r.confidence, own = false, note = "Shared road-query observation",
                                                    observationId = id, capturedMs = now - age, capturedUtcMs = capturedUtc, provenance = provenance)
                                            }
                                            update { it.copy(known = map.all) }
                                        }
                                        queries.receive(frame, now).forEach { f ->
                                            if (!outgoing.offer(Outgoing.Control(f, if (f is ControlFrame.Reply && f.responder == node) "reply" else "query_relay"))) count("context_queue_full")
                                        }
                                    }
                                    emptyList()
                                }
                            } else engine.receive(command.bytes)
                        }
                        is Command.Publish -> engine.publish(command.hazard).also { fx ->
                            fx.filterIsInstance<MeshEffect.Originated>().firstOrNull()?.let { o ->
                                // Host-speed events are located at the emitting phone. Retain its measured
                                // uncertainty for context replies; camera-projected depth has unknown error.
                                val at = command.at ?: if (command.hazard.kind in setOf(HazardKind.BRAKING, HazardKind.SLOWDOWN) &&
                                    command.hazard.detail?.rangeM == null) o.originFix ?: hazardFix(o.frame) else hazardFix(o.frame)
                                val utc = clock.utcMs()
                                val age = generationAgeMs(o.frame.packet.tGenDeci, utc)
                                if (age >= 0) map.add(command.hazard.kind, at, node, now, command.hazard.detail?.confidence, own = true,
                                    note = command.hazard.sourceEvent, observationId = o.frame.id, capturedMs = now - age,
                                    capturedUtcMs = utc - age, provenance = o.frame.provenance)
                            }
                        }
                        is Command.Ask -> {
                            val here = source.current()?.takeIf { it.valid() }
                            if (here == null) {
                                val picture = RoadPicture.unknown()
                                update { it.copy(picture = picture, pictureAtMs = now, asking = false) }
                                withContext(Dispatchers.Main) { command.done(picture) }
                                record("road_query_unavailable", "Position unavailable; no synthetic road position substituted")
                                emptyList()
                            }
                            else {
                                pictureDone = command.done
                                outgoing.offer(Outgoing.Control(queries.ask(here, now), "query"))
                                update { it.copy(asking = true) }; record("road_query", "asked the chain both ways")
                                emptyList()
                            }
                        }
                        Command.Tick -> {
                            ble.maintain(now)
                            if (now - lastBeat >= 1_000) {
                                lastBeat = now
                                roster.observer = observing
                                roster.fix = source.current()?.takeIf { it.valid() }
                                map.expire(now); roster.known = map.all.size
                                val members = roster.update(now)
                                val beat = roster.presence(profile.mode == Mode.STAGE, now)
                                if (beaconWorks) beaconWorks = runCatching { ble.beacon(ControlCodec.encode(beat)) }.getOrDefault(false)
                                if (!beaconWorks) outgoing.offer(Outgoing.Control(beat, "presence"))
                                update { it.copy(chain = members, known = map.all) }
                                // Enrich a new short warning automatically; no driver needs to tap Ask Road.
                                // Only fresh unresolved remote evidence triggers this bounded background exchange.
                                val unresolved = map.all.any { h -> h.evidence.any { e ->
                                    !e.own && e.fix?.accuracyM == null && now - e.capturedMs in 0..5_000
                                } }
                                if (unresolved && !state.value.asking && roster.fix != null &&
                                    (lastContextQuery == Long.MIN_VALUE || now - lastContextQuery >= 8_000)) {
                                    lastContextQuery = now
                                    val q = queries.ask(roster.fix!!, now, radiusM = 500)
                                    if (outgoing.offer(Outgoing.Control(q, "auto_context"))) {
                                        update { it.copy(asking = true) }
                                        count("auto_context_query")
                                    }
                                }
                            }
                            queries.tick(now).forEach { q ->
                                val here = source.current()?.takeIf { it.valid() } ?: q.fix
                                val replies = if (profile.mode == Mode.ROAD) q.replies.map { r -> r.copy(records = r.records.filter { it.provenance == Provenance.LIVE }) } else q.replies
                                val picture = RoadPicture.combine(here, map.all, replies, now, clock.utcMs())
                                update { it.copy(picture = picture, pictureAtMs = now, asking = false) }
                                record("road_picture", "${q.replies.size} replies; ${picture.items.size} hazards")
                                pictureDone?.let { cb -> withContext(Dispatchers.Main) { cb(picture) } }; pictureDone = null
                            }
                            engine.tick()
                        }
                    }
                    handle(effects, engine, map, node, now, outgoing, source)
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

    private suspend fun handle(effects: List<MeshEffect>, engine: MeshEngine, map: RoadMap, node: NodeId, now: Long, outgoing: Outbox, source: PositionSource) {
        for (effect in effects) when (effect) {
            is MeshEffect.Originated -> {
                count("originated"); record("originated", "sourceEvent=${effect.sourceEvent}", effect.frame)
                trace(EventTrace(effect.frame.id, effect.frame.kind, effect.frame.detail, true, effect.frame.forwarder, SystemClock.elapsedRealtime(), canRelay = true))
            }
            is MeshEffect.Transmit -> {
                record("tx_queued", frameDetail(effect.frame), effect.frame)
                if (!outgoing.offer(Outgoing.Hazard(effect))) count("tx_queue_full")
            }
            is MeshEffect.Received -> {
                count("received"); record("received", frameDetail(effect.frame), effect.frame)
                val kind = effect.frame.kind
                val at = hazardFix(effect.frame)
                val repeat = kind != null && !map.fresh(kind, at, now, 60_000)
                val utc = System.currentTimeMillis()
                val age = generationAgeMs(effect.frame.packet.tGenDeci, utc)
                if (kind?.use == HazardUse.CANCEL) map.cancel(effect.frame.id.origin, now)
                else if (kind != null && age >= 0) map.add(kind, at, effect.frame.id.origin, now, effect.frame.detail?.confidence, own = false,
                    observationId = effect.frame.id, capturedMs = now - age, capturedUtcMs = utc - age, provenance = effect.frame.provenance)
                else if (age < 0) count("evidence_future_rejected")
                update { it.copy(lastReceived = effect.frame, known = map.all) }
                if (!repeat) trace(received(effect.frame))
                else count("repeat_report")
            }
            is MeshEffect.Announce -> {
                val kind = effect.kind
                val fresh = state.value.events.any { it.id == effect.frame.id }
                count("caution"); record("caution", frameDetail(effect.frame), effect.frame)
                update { s -> s.copy(alert = effect, events = s.events.map { if (it.id == effect.frame.id) it.copy(announced = true) else it }) }
                // Repeated nearby reports remain visible without repeatedly speaking the same long-lived condition.
                if (fresh || kind.lifetimeS < 600) withContext(Dispatchers.Main.immediate) { output?.announce(effect) }
            }
            is MeshEffect.Notice -> { count(effect.code); if (effect.code != "duplicate" && effect.code != "link_filtered") record(effect.code, effect.detail) }
        }
    }

    /** Where a hazard is: the origin's position moved forward by the range its camera measured, along its heading. */
    private fun hazardFix(f: HazardEnvelope): GeoFix {
        val origin = GeoFix(f.packet.latE7 / 1e7, f.packet.lonE7 / 1e7, null, null, f.provenance != Provenance.LIVE)
        val range = f.detail?.rangeM ?: return origin
        if (f.packet.flags and com.jsons.roadmesh.core.protocol.Flags.NO_HEADING != 0) return origin
        val heading = Math.toRadians(f.packet.headingQ * 360.0 / 256)
        return moved(origin, range * cos(heading), range * sin(heading))
    }

    private suspend fun transmitLoop(ble: HazardTransport, outgoing: Outbox, clock: MeshClock, profile: MeshProfile) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val request = outgoing.next() ?: break
            try {
                when (request) {
                    is Outgoing.Hazard -> {
                        val frame = request.effect.frame
                        // Discard a queued burst once its originating hazard has expired.
                        val age = generationAgeMs(frame.packet.tGenDeci, clock.utcMs())
                        if (age >= minOf(profile.maxAgeMs, frame.packet.validityS * 1_000L) || age < -profile.futureToleranceMs) { count("tx_expired"); continue }
                        val events = ble.transmit(HazardEnvelopeCodec.encode(frame), request.effect.relay)
                        count(if (request.effect.relay) "relay_burst" else "origin_burst")
                        record("tx_complete", "${frameDetail(frame)} requestedEvents=$events; remote delivery unknown", frame)
                        val sentAt = SystemClock.elapsedRealtime()
                        update { s -> s.copy(lastTx = frame, events = s.events.map { e ->
                            if (e.id != frame.id) e else if (request.effect.relay) e.copy(relayedAtMs = sentAt) else e.copy(sentAtMs = sentAt)
                        }) }
                    }
                    is Outgoing.Control -> { ble.transmit(ControlCodec.encode(request.frame), true); count("${request.label}_burst") }
                }
                if (failures > 0) { failures = 0; update { it.copy(ready = true, status = "BLE ready") } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // One failed burst must not end the session: rebuild the advertising set and keep the chain running.
                failures++; count("tx_error"); record("tx_error", e.message.orEmpty())
                update { it.copy(ready = false, status = "BLE transmit failed · recovering ($failures)") }
                val recovered = runCatching { ble.recover() }.isSuccess
                if (recovered) update { it.copy(ready = true, status = "BLE ready · recovered", recoveries = it.recoveries + 1) }
                else delay((500L * failures).coerceAtMost(5_000))
            }
        }
    }

    private fun trace(t: EventTrace) = update { s -> s.copy(events = (listOf(t) + s.events.filter { it.id != t.id }).take(24)) }

    /** This node's own inference: the origin's range to the hazard plus this node's distance to the origin. */
    private fun received(f: HazardEnvelope): EventTrace {
        val own = source?.current()?.takeIf { it.valid() }
        val origin = GeoFix(f.packet.latE7 / 1e7, f.packet.lonE7 / 1e7, null, null, f.provenance != Provenance.LIVE)
        val toOrigin = own?.let { distanceM(it, origin) }
        val inference = f.kind?.let { k -> toOrigin?.let { NodeInference.at(k, f.detail, it, own.speedMps) } }
        return EventTrace(f.id, f.kind, f.detail, false, f.forwarder, SystemClock.elapsedRealtime(), toOrigin, inference, hops = f.hop,
            canRelay = f.ttl > 0 && (state.value.mode == Mode.ROAD || state.value.chain.let { c -> c.size > (c.firstOrNull { it.self }?.rank ?: Int.MAX_VALUE) + 1 }), viaRank = f.rank)
    }

    /** Queues a candidate for engine admission, not a delivery acknowledgement. [at] is an optional hazard position. */
    fun publish(hazard: LocalHazard, at: GeoFix? = null): Boolean {
        if (!state.value.ready) { record("source_rejected", "Start a ready mesh session first"); return false }
        val fix = here()
        val mode = activeProfile?.mode ?: state.value.mode
        val scoped = fix != null && when (mode) {
            Mode.ROAD -> hazard.provenance == Provenance.LIVE && !fix.synthetic
            Mode.STAGE -> hazard.provenance in setOf(Provenance.REPLAY, Provenance.DEMO) && fix.synthetic
        }
        if (!scoped) { record("source_rejected", "Position unavailable or input does not match session mode"); return false }
        val now = SystemClock.elapsedRealtime()
        if (hazard.capturedAtMs?.let { it < 0 || it > now || now - it >= (activeProfile?.maxAgeMs ?: 5_000) } == true) {
            record("source_rejected", "Camera evidence is stale or from a future timestamp"); return false
        }
        return commands?.trySend(Command.Publish(hazard, at))?.isSuccess == true
    }
    /** Asks the phones ahead and behind what the road is like; [done] gets the combined picture after about 2.5 s. */
    fun askRoad(done: (RoadPicture) -> Unit): Boolean = commands?.trySend(Command.Ask(done))?.isSuccess == true
    /** Where this phone is now, if it knows. */
    fun here(): GeoFix? = source?.current()?.takeIf { it.valid() }
    fun clearAlert() = update { it.copy(alert = null) }
    /** Speaks a non-alert line through the session's offline voice, if the session is running. */
    fun say(text: String) { output?.say(text); record("copilot_spoken", text) }
    /** Local perception remains useful before GNSS or Bluetooth are ready. Called on the foreground UI thread. */
    fun localCaution(text: String) {
        if (output == null) output = HazardOutput(context) { record("output", it) }
        output?.localCaution(text)
        record("local_caution", text)
    }
    fun fail(message: String) { record("service_error", message); update { it.copy(status = message, ready = false) } }
    fun stop() {
        accepting = false
        commands?.close(); tx?.close(); scope?.cancel(); scope = null; commands = null; tx = null
        transport?.close(); transport = null; position?.close(); position = null; source = null; output?.close(); output = null
        record("session_stop", "resources released")
        update { it.copy(running = false, ready = false, chain = emptyList(), asking = false, status = if (it.ready || it.status == "Starting BLE…") "Stopped" else it.status) }
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
            put("schema", 2); put("exportedUtcMs", System.currentTimeMillis()); put("role", snapshot.liveRole.name)
            put("nodeId", activeNode?.toString() ?: preferences.nodeId.toString())
            put("session", java.lang.Long.toUnsignedString(sessionNonce, 16)); put("profile", profileJson(exportedProfile))
            put("chain", JSONArray(snapshot.chain.map { JSONObject().put("node", it.node.toString()).put("rank", it.rank).put("role", it.role.name).put("self", it.self) }))
            put("disclosure", (if (exportedProfile.mode == Mode.STAGE) "Dashcam replay and virtual brake; synthetic stage positions by chain position; adjacent-position acceptance filter. " else "Live GNSS; no synthetic fallback. ") + "Unsigned caution. TX completion is local only. No delivery receipt or clock-derived latency.")
            put("counters", JSONObject(snapshot.counters)); put("retainedLogLimit", 512)
            put("bounds", JSONObject().put("dedup", snapshot.bounds.dedup).put("neighbors", snapshot.bounds.neighbors).put("pending", snapshot.bounds.pending))
            put("events", JSONArray(snapshot.logs.map { JSONObject().put("utcMs", it.utcMs).put("monotonicMs", it.monotonicMs).put("code", it.code).put("detail", it.detail).apply {
                it.frame?.let { frame -> put("frame", frameJson(frame)) }
            } }))
        }
        val name = "resilane-${exportedRole.name.lowercase()}-${System.currentTimeMillis()}.json"
        saveDownload(context, name, "application/json", doc.toString(2))
    }
}

/** Writes a text file to Downloads/RESILANE and returns its path for display. */
fun saveDownload(context: Context, name: String, mime: String, text: String): String {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RESILANE")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create export")
    try {
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("Cannot write export")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    return "Downloads/RESILANE/$name"
}

fun frameDetail(frame: HazardEnvelope) = "origin=${frame.id.origin} session=${java.lang.Long.toUnsignedString(frame.id.session, 16)} seq=${Integer.toUnsignedString(frame.id.sequence)} forwarder=${frame.forwarder} rank=${frame.rank} ttl=${frame.ttl} relays=${frame.hop} radioLinks=${frame.radioLinks} ${frame.provenance}"
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
fun frameJson(frame: HazardEnvelope) = JSONObject().apply {
    put("origin", frame.id.origin.toString()); put("session", java.lang.Long.toUnsignedString(frame.id.session, 16))
    put("sequence", Integer.toUnsignedString(frame.id.sequence)); put("forwarder", frame.forwarder.toString())
    put("ttl", frame.ttl); put("relayCount", frame.hop); put("radioLinks", frame.radioLinks); put("rank", frame.rank)
    put("provenance", frame.provenance.name); put("hex", HazardEnvelopeCodec.encode(frame).toHex())
}
