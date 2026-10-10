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
        // Impact detection runs only while the mesh is up, so a crash is shared the moment it is felt.
        scope.launch { session.state.map { it.running }.distinctUntilChanged().collect { if (it) crash.start() else crash.stop() } }
        // Every node that receives a warning makes its own inference and has the co-pilot phrase it after the caution.
        scope.launch {
            session.state.map { s -> s.events.filter { !it.originated } }.collect { events ->
                for (e in events) {
                    val tag = "event:${e.id}"
                    if (tag in explained) continue
                    explained += tag
                    val kind = e.kind ?: continue
                    if (kind.use == HazardUse.REPORT) {
                        reports.add(kind, "reported over the mesh by phone ${e.id.origin.letter()}", e.detail?.confidence, null, null, true, null,
                            sent = false, source = "mesh", fromMesh = true)
                        continue
                    }
                    val inference = e.inference ?: continue
                    val facts = inference.facts(e.id.origin.letter().first(), e.via.letter().first()) + " This phone is in ${withArticle(preferences.vehicle.title)}."
                    val plain = "${kind.title()} about ${inference.hazardAheadM} m ahead: ${inference.action}."
                    copilot.ask(tag, facts, Priority.EVENT, fallback = plain) { line -> if (session.state.value.running) session.say(line) }
                }
            }
        }
    }
    private val explained = HashSet<String>()

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
            if (!(o.simulating && o.playing)) "" else " Front camera sees: " + (o.counts.entries.joinToString { (c, n) -> "$n ${c.name.lowercase()}" }.ifEmpty { "an empty road" }) + "."
        }
        val facts = "This phone is in ${withArticle(preferences.vehicle.title)}. " + picture.facts() + camera
        val plain = picture.forecast.advice.take(2).joinToString(" ")
        asked.update { qs -> qs.map { if (it.tag == tag) it.copy(answer = plain, facts = facts, picture = picture) else it } }
        val generic = question.startsWith("What's the road like")
        val queued = copilot.ask(tag, if (generic) picture.facts() else facts, Priority.QUESTION,
            if (generic) Copilot.TASK_ROAD else Copilot.taskQuestion(question), fallback = plain) { line -> session.say(line) }
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
