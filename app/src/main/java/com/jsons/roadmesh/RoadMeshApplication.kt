package com.jsons.roadmesh

import android.app.Application
import android.os.SystemClock
import com.jsons.roadmesh.copilot.Copilot
import com.jsons.roadmesh.copilot.Priority
import com.jsons.roadmesh.copilot.withArticle
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.HazardUse
import com.jsons.roadmesh.core.road.RoadPicture
import com.jsons.roadmesh.perception.DashcamObserver
import com.jsons.roadmesh.reports.ReportLog
import com.jsons.roadmesh.sensing.CrashDetector
import com.jsons.roadmesh.service.MeshSession
import com.jsons.roadmesh.service.letter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A question asked on this phone. [answer] is the deterministic text; the co-pilot phrases it under [tag]. */
data class Question(val tag: String, val text: String, val answer: String?, val facts: String, val atMs: Long, val picture: RoadPicture? = null)

/** Small manual composition root; replace adapters without changing domain or scene code. */
class RoadMeshApplication : Application() {
    val preferences by lazy { AppPreferences(this) }
    val session by lazy { MeshSession(this, preferences) }
    val meshDesk by lazy { com.jsons.roadmesh.telemetry.MeshDeskPublisher(this) }
    val copilot by lazy { Copilot(this) }
    val reports by lazy { ReportLog(this) }
    val observer by lazy { DashcamObserver(this, session, copilot, preferences, reports) }
    val crash by lazy { CrashDetector(this, session, reports, preferences) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val asked = MutableStateFlow<List<Question>>(emptyList())
    val questions = asked.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return
        copilot.load()
        // Phone motion is relevant only during a visible mounted live session, never a pitch clip.
        scope.launch { combine(session.state, observer.flow) { s, o ->
            s.running && s.mode == com.jsons.roadmesh.core.mesh.Mode.ROAD && o.live && o.playing
        }.distinctUntilChanged().collect { if (it) crash.start() else crash.stop() } }
        // Every node that receives a warning makes its own inference and has the co-pilot phrase it after the caution.
        scope.launch {
            session.state.map { s -> s.events.filter { !it.originated } }.distinctUntilChanged().collect { events ->
                for (e in events) {
                    val tag = "event:${e.id}"
                    if (tag in explained) continue
                    explained += tag
                    while (explained.size > 256) explained.remove(explained.first())
                    val kind = e.kind ?: continue
                    if (kind.use == HazardUse.REPORT) {
                        reports.add(kind, "reported over the mesh by phone ${e.id.origin.letter()}", e.detail?.confidence, null, null, true, null,
                            sent = false, source = "mesh", fromMesh = true)
                        continue
                    }
                    val inference = e.inference ?: continue
                    val mode = session.state.value.mode
                    val facts = inference.facts(e.id.origin.letter().first(), e.via.letter().first()) +
                        " Distances are rough estimates, not a calibrated measurement. Mode: $mode. This phone is in ${withArticle(preferences.vehicle.title)}."
                    val plain = "${kind.title()}: ${inference.action}. Distance is approximate."
                    copilot.ask(tag, facts, Priority.EVENT, fallback = plain) { line ->
                        val current = session.state.value
                        if (current.running && current.mode == mode && current.alert?.frame?.id == e.id &&
                            SystemClock.elapsedRealtime() - e.atMs in 0..5_000) session.say(line)
                    }
                }
            }
        }
    }
    private val explained = LinkedHashSet<String>()
    private var lastNarrowingReportMs: Long? = null

    /** Manual observation at the phone's current position. The return text describes queueing, not delivery. */
    fun reportNarrowing(): String {
        if (!session.state.value.ready) return "Start a ready mesh session before reporting."
        val at = session.here() ?: return "Waiting for a fresh position. The report was not queued."
        val now = SystemClock.elapsedRealtime()
        if (lastNarrowingReportMs?.let { now - it in 0 until 15_000 } == true) return "Report already queued. Wait 15 seconds before reporting again."
        val replay = session.state.value.mode == com.jsons.roadmesh.core.mesh.Mode.STAGE
        val queued = session.publish(com.jsons.roadmesh.core.mesh.LocalHazard(
            com.jsons.roadmesh.core.mesh.HazardKind.ROAD_NARROWED,
            if (replay) com.jsons.roadmesh.core.mesh.Provenance.REPLAY else com.jsons.roadmesh.core.mesh.Provenance.LIVE,
            "manual road narrowing at this phone",
            com.jsons.roadmesh.core.mesh.HazardDetail(0, preferences.vehicle, null)), at)
        if (!queued) return "The session could not queue the report."
        lastNarrowingReportMs = now
        return if (replay) "Stage report queued at a synthetic position. Remote delivery is not confirmed."
            else "Road narrowing queued at your position. Remote delivery is not confirmed."
    }

    /**
     * "What is the road like?": asks the phones ahead and behind over the mesh, combines their answers with this phone's
     * own road map into one picture, and has the co-pilot phrase it. Works with the mesh alone; the camera is optional.
     */
    fun askRoad(question: String = "What's the road like ahead?") {
        val now = SystemClock.elapsedRealtime()
        val tag = "q:$now"
        asked.update { (listOf(Question(tag, question, null, "", now)) + it).take(10) }
        val sent = session.askRoad { picture -> answer(tag, question, picture) }
        if (!sent) {
            val text = "The mesh is not running, so I can't ask the phones around you. Start the mesh first."
            asked.update { qs -> qs.map { if (it.tag == tag) it.copy(answer = text) else it } }
            session.say(text)
        }
    }

    private fun answer(tag: String, question: String, picture: RoadPicture) {
        val camera = observer.flow.value.let { o ->
            val fresh = o.capturedAtMs?.let { SystemClock.elapsedRealtime() - it in 0..1_500 } == true
            if (!o.playing || !fresh) " Camera evidence unavailable or stale." else
                " ${if (o.live) "Live camera" else "Replay clip"} detections: " +
                    o.counts.entries.joinToString { (c, n) -> "$n ${c.name.lowercase()}" }.ifEmpty { "no current detections; visibility is unknown" } +
                    ". ${if (o.live) "Object detections do not prove the road is clear." else "These are recorded observations."}"
        }
        val facts = "This phone is in ${withArticle(preferences.vehicle.title)}. " + picture.facts() + camera
        val plain = picture.forecast.advice.take(2).joinToString(" ")
        asked.update { qs -> qs.map { if (it.tag == tag) it.copy(answer = plain, facts = facts, picture = picture) else it } }
        val generic = question.startsWith("What's the road like")
        val mode = session.state.value.mode
        val queued = copilot.ask(tag, if (generic) picture.facts() else facts, Priority.QUESTION,
            if (generic) Copilot.TASK_ROAD else Copilot.taskQuestion(question), fallback = plain) { line ->
                if (session.state.value.running && session.state.value.mode == mode) session.say(line)
            }
        if (!queued) session.say(plain)
    }

    /** Sends a test warning from this phone, as if its camera had seen [kind] about 25 m ahead. For showing the relay. */
    fun sendTest(kind: com.jsons.roadmesh.core.mesh.HazardKind): Boolean {
        val reason = "test ${kind.title().lowercase()} sent from the Observe tab"
        val sent = session.publish(com.jsons.roadmesh.core.mesh.LocalHazard(kind, com.jsons.roadmesh.core.mesh.Provenance.REPLAY, reason,
            com.jsons.roadmesh.core.mesh.HazardDetail(25, preferences.vehicle, 0.9)))
        if (sent && (kind == com.jsons.roadmesh.core.mesh.HazardKind.POTHOLE || kind == com.jsons.roadmesh.core.mesh.HazardKind.ACCIDENT)) {
            val here = session.here()
            reports.add(kind, reason, 0.9, here?.lat, here?.lon, true, null, true, "test")
        }
        return sent
    }

    /** Any question goes to the road: the chain is asked, and the co-pilot answers from the combined picture. */
    fun ask(text: String) {
        val q = text.trim().ifEmpty { return }
        askRoad(q)
    }
}
