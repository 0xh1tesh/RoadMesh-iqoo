package com.jsons.roadmesh

import android.app.Application
import android.os.SystemClock
import com.jsons.roadmesh.copilot.Copilot
import com.jsons.roadmesh.copilot.Priority
import com.jsons.roadmesh.core.insight.ActiveWarning
import com.jsons.roadmesh.core.insight.OvertakeAdvisor
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.perception.DashcamObserver
import com.jsons.roadmesh.service.MeshSession
import com.jsons.roadmesh.service.letter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A question asked on this phone. [verdict] is the deterministic part; the co-pilot phrases it under [tag]. */
data class Question(val tag: String, val text: String, val verdict: String?, val notNow: Boolean?, val facts: String, val atMs: Long)

/** Small manual composition root; replace adapters without changing domain or scene code. */
class RoadMeshApplication : Application() {
    val preferences by lazy { AppPreferences(this) }
    val session by lazy { MeshSession(this, preferences) }
    val copilot by lazy { Copilot(this) }
    val observer by lazy { DashcamObserver(this, session, copilot, preferences) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val asked = MutableStateFlow<List<Question>>(emptyList())
    val questions = asked.asStateFlow()
    private val advisor = OvertakeAdvisor()

    override fun onCreate() {
        super.onCreate()
        copilot.load()
        // Every node that receives a warning makes its own inference and has the co-pilot phrase it after the caution.
        scope.launch {
            session.state.map { s -> s.events.filter { !it.originated && it.inference != null } }.collect { events ->
                for (e in events) {
                    val tag = "event:${e.id}"
                    if (tag in explained) continue
                    explained += tag
                    val inference = e.inference!!
                    val facts = inference.facts(e.id.origin.letter().first(), e.via.letter().first()) + " This phone is in a ${preferences.vehicle.title}."
                    copilot.ask(tag, facts, Priority.EVENT) { line -> if (session.state.value.running) session.say(line) }
                }
            }
        }
    }
    private val explained = HashSet<String>()

    /**
     * Answers a question from what this phone knows: its own camera (if the dashcam simulation is running) and the
     * warnings it holds from the mesh. Overtaking questions get a deterministic verdict first; the co-pilot only
     * rephrases it. The answer is spoken when ready.
     */
    fun ask(text: String) {
        val q = text.trim().ifEmpty { return }
        val o = observer.flow.value
        val s = session.state.value
        val now = SystemClock.elapsedRealtime()
        val warnings = s.events.filter { !it.originated && now - it.atMs < 60_000 && it.inference != null }
            .map { ActiveWarning(it.inference!!.kind, it.inference.hazardAheadM) }
        val cameraLive = o.simulating && o.playing
        val scene = if (!cameraLive) "The front camera is not running on this phone."
            else "Front camera sees: " + (o.counts.entries.joinToString { (c, n) -> "$n ${c.name.lowercase()}" }.ifEmpty { "an empty road" }) +
                (o.lead?.let { ". Nearest vehicle in our lane: ${it.subject.cls.name.lowercase()} about ${it.distanceM.toInt()} m ahead" + (it.ttcS?.let { t -> ", contact in ${"%.1f".format(t)} s" } ?: "") } ?: ". No vehicle directly ahead in our lane") + "."
        val mesh = if (warnings.isEmpty()) "No warnings from the mesh in the last minute."
            else "Mesh warnings: " + warnings.joinToString { "${it.kind.title().lowercase()} about ${it.aheadM} m ahead" } + "."
        val overtake = Regex("overtak|pass (the|this|that)|go past", RegexOption.IGNORE_CASE).containsMatchIn(q)
        val answer = if (overtake) advisor.assess(cameraLive, o.objects, o.lead, warnings, preferences.vehicle) else null
        val facts = "This phone is in a ${preferences.vehicle.title}. $scene $mesh" + (answer?.let { " Overtaking verdict (keep its meaning exactly): ${it.verdict}" } ?: "")
        val tag = "q:$now"
        asked.update { (listOf(Question(tag, q, answer?.verdict, answer?.notNow, facts, now)) + it).take(10) }
        val task = "The driver asks: \"$q\". Answer in at most two short sentences." + if (overtake) " Start with the verdict." else " If the facts do not answer it, say you cannot tell from this phone."
        val queued = copilot.ask(tag, facts, Priority.QUESTION, task) { line -> session.say(line) }
        if (!queued) session.say(answer?.verdict ?: "The co-pilot model is not installed on this phone. $scene $mesh")
    }
}
