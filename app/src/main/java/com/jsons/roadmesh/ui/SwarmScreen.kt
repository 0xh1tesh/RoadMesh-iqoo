package com.jsons.roadmesh.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsons.roadmesh.core.mesh.HazardKind
import com.jsons.roadmesh.core.mesh.Mode
import com.jsons.roadmesh.core.mesh.Provenance
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.swarm.*
import com.jsons.roadmesh.service.SessionState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** The lab runs the same core evaluator as the phone view, with explicitly synthetic inputs. */
@Composable fun SwarmScreen(
    s: SessionState,
    modifier: Modifier = Modifier,
    onAskRoad: (() -> Unit)? = null,
    initialConfig: SwarmScenarioConfig = SwarmScenarioConfig(),
    initialLive: Boolean = false,
) {
    var phoneView by rememberSaveable { mutableStateOf(initialLive) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("AUTOMATIC COOPERATIVE PERCEPTION", fontFamily = Michroma, fontSize = 10.sp, color = Tokens.Yellow, letterSpacing = 1.sp)
        Text("Beyond one\nvehicle’s view.", fontFamily = Saira, fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = Tokens.Paper)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !phoneView, onClick = { phoneView = false }, label = { Text("Pipeline lab") })
            FilterChip(selected = phoneView, onClick = { phoneView = true }, label = { Text("This phone") })
        }
        if (phoneView) LiveSwarmContent(s, onAskRoad) else {
            AutomationLab()
            SwarmLabContent(initialConfig)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun SwarmLabContent(initialConfig: SwarmScenarioConfig) {
    var relay by rememberSaveable { mutableStateOf(initialConfig.relayEnabled) }
    var alternate by rememberSaveable { mutableStateOf(initialConfig.alternateEnabled) }
    var v2i by rememberSaveable { mutableStateOf(initialConfig.v2iCovered) }
    var navigationReport by rememberSaveable { mutableStateOf(initialConfig.navigationDelayMs != null) }
    var elapsed by rememberSaveable { mutableLongStateOf(initialConfig.elapsedMs) }
    val config = SwarmScenarioConfig(relayEnabled = relay, alternateEnabled = alternate, v2iCovered = v2i,
        elapsedMs = elapsed, navigationDelayMs = if (navigationReport) 8_000L else null)
    val result = remember(config) { SwarmScenario.run(config) }
    val learning = remember { ClearanceLearningDemo.run() }
    val mesh = result.comparisons.firstOrNull { it.label == "RoadMesh" }

    SwarmPanel {
        SwarmEyebrow("SYNTHETIC SCENARIO", Tokens.Yellow)
        Text("Stopped bus. Narrow two-way road.", style = MaterialTheme.typography.titleLarge, color = Tokens.Paper)
        Text("A sees the obstruction. C is still beyond its camera’s view. Test what C can know when neighbours cooperate.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        ScenarioDiagram(result, config, mesh?.takeIf { it.assessment.evidence.isNotEmpty() }?.path.orEmpty())
        Text("Schematic • fixed vehicle positions • no live radio", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
        HorizontalDivider(color = Tokens.Line)
        Text("KNOWLEDGE AT VEHICLE C", fontFamily = Michroma, fontSize = 10.sp, color = Tokens.Muted)
        if (mesh != null) {
            Text(mesh.assessment.title, style = MaterialTheme.typography.titleLarge,
                color = if (mesh.assessment.status == SwarmStatus.CAUTION) Tokens.Yellow else Tokens.Paper)
            Text(mesh.assessment.advice, style = MaterialTheme.typography.bodyMedium, color = Tokens.Paper)
            val route = mesh.path.takeIf { it.isNotEmpty() }?.joinToString(" → ")?.let {
                if (mesh.assessment.evidence.isEmpty()) "Previous delivery: $it" else it
            } ?: "No delivered observation"
            Text(route, fontFamily = Saira, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Tokens.Yellow)
            Text(if (mesh.assessment.evidence.isNotEmpty()) "Received evidence from A • C did not observe the bus itself"
                else "Missing or expired evidence means unknown, never road clear.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
            val opposing = mesh.assessment.neighbours.filter { it.relation == DirectionRelation.OPPOSING }.map { it.id }
            if (opposing.isNotEmpty()) Text("Opposing movement: ${opposing.joinToString()} • kept separate from same-direction peers", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
            mesh.assessment.nominatedObserverId?.let { Text("Local sensing nominee: $it • a preference, not a controller", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint) }
        }
    }

    SwarmPanel {
        SwarmEyebrow("CHANGE THE CONDITIONS")
        SwarmToggle("Relay B", "Remove the original relay from the local graph.", relay) { relay = it }
        SwarmToggle("Alternative neighbour D", "A second in-range path; recovery requires a real route in this model.", alternate) { alternate = it }
        SwarmToggle("Roadside sensor coverage", "Give fixed V2I a view of this obstruction.", v2i) { v2i = it }
        SwarmToggle("Navigation incident feed", "In this experiment, a report reaches C after 8 seconds.", navigationReport) { navigationReport = it }
        HorizontalDivider(color = Tokens.Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Observation age", style = MaterialTheme.typography.bodyMedium, color = Tokens.Paper)
            Text("${elapsed / 1_000}s / 15s lifetime", style = Tabular, color = Tokens.Yellow)
        }
        Slider(value = elapsed.toFloat(), onValueChange = { elapsed = (it / 1_000f).roundToInt() * 1_000L }, valueRange = 0f..20_000f, steps = 19,
            modifier = Modifier.semantics { contentDescription = "Synthetic observation age in milliseconds" })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { elapsed = 2_000 }, modifier = Modifier.weight(1f)) { Text("Fresh · 2s") }
            OutlinedButton(onClick = { elapsed = 16_000 }, modifier = Modifier.weight(1f)) { Text("Expired · 16s") }
        }
        Text("One observation, no refresh. Relaying preserves its original age.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
    }

    SwarmPanel {
        SwarmEyebrow("SAME EVENT · SAME RECIPIENT")
        Text("What reaches C?", style = MaterialTheme.typography.titleLarge, color = Tokens.Paper)
        result.comparisons.forEachIndexed { index, comparison ->
            if (index > 0) HorizontalDivider(color = Tokens.Line)
            ComparisonRow(comparison)
        }
        Text("Navigation is a configurable information baseline, not a test of Google Maps. V2I can win when its sensor has coverage.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
    }

    SwarmPanel {
        SwarmEyebrow("LEARNING REPLAY · SYNTHETIC EPISODES")
        Text("Learn from outcomes. Test the errors.", style = MaterialTheme.typography.titleLarge, color = Tokens.Paper)
        Text("A local rolling median estimates how long a narrowing lasts. It learns only from completed, directly observed episodes; missing messages never count as clearance.",
            style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        learning.forEachIndexed { index, experiment ->
            if (index > 0) HorizontalDivider(color = Tokens.Line)
            Text(experiment.label, style = MaterialTheme.typography.titleMedium, color = Tokens.Paper)
            Text("${experiment.trainingSamples} retained training samples · ${experiment.heldOutSeconds.size} separate evaluation episodes", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
            Text(String.format(Locale.US, "Learned estimate %.1f s · fixed estimate %.1f s", experiment.estimateSeconds, experiment.baselineSeconds),
                style = MaterialTheme.typography.bodySmall, color = Tokens.Paper)
            val learnedError = String.format(Locale.US, "%.1f s", experiment.adaptiveMaeSeconds)
            val fixedError = String.format(Locale.US, "%.1f s", experiment.baselineMaeSeconds)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SwarmMetric(learnedError, "learned mean error", Modifier.weight(1f))
                SwarmMetric(fixedError, "fixed mean error", Modifier.weight(1f))
            }
            Text(experiment.detail, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        }
        Text("Replay only. No live training, driving-policy learning or passage permission. These are prediction errors, not travel-time savings.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Yellow)
    }

    SwarmPanel {
        SwarmEyebrow("WHAT THIS DEMONSTRATES")
        Text("Local knowledge can survive a missing relay—when another path exists. Without a path, the result becomes unknown.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Paper)
        Text("This demonstrates message routing and conservative advice. It does not measure accident reduction, travel-time savings or real BLE reliability.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        result.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint) }
    }
}

@Composable private fun ComparisonRow(comparison: SwarmComparison) {
    val caution = comparison.assessment.status == SwarmStatus.CAUTION
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(comparison.label, style = MaterialTheme.typography.titleMedium, color = Tokens.Paper, modifier = Modifier.weight(1f))
            Text(if (caution) "CAUTION" else "UNKNOWN", fontFamily = Saira, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = if (caution) Tokens.Yellow else Tokens.Faint)
        }
        Text(comparison.detail, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        if (comparison.path.isNotEmpty()) Text((if (caution) "" else "Previous delivery: ") + comparison.path.joinToString(" → "), style = MaterialTheme.typography.labelMedium, color = Tokens.Yellow)
        if (caution) {
            val age = comparison.assessment.oldestAgeMs?.let { "${it / 1_000}s old" } ?: "age unknown"
            Text("${comparison.assessment.independentSources} source · $age", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
        }
    }
}

/** Only the core result determines delivery. Diagram coordinates communicate topology, not measured geometry. */
@Composable private fun ScenarioDiagram(result: SwarmScenarioResult, config: SwarmScenarioConfig, path: List<String>) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(235.dp).semantics {
        contentDescription = "Synthetic stopped bus scenario. Observer A, relay B, recipient C, optional alternative D and opposing vehicle E. " +
            if (path.isEmpty()) "No observation delivered." else "Computed delivery path ${path.joinToString(" to ")}."
    }) {
        val w = size.width
        val h = size.height
        val roadLeft = w * .28f
        val roadWidth = w * .44f
        drawRoundRect(Tokens.Raised, Offset(roadLeft, 0f), Size(roadWidth, h), CornerRadius(16f))
        drawLine(Tokens.Outline, Offset(w * .5f, 0f), Offset(w * .5f, h), strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 13f)))
        val positions = mapOf("A" to Offset(w * .39f, h * .29f), "B" to Offset(w * .39f, h * .56f),
            "C" to Offset(w * .39f, h * .84f), "D" to Offset(w * .65f, h * .56f), "E" to Offset(w * .65f, h * .15f))
        result.configuredLinks.forEach { (a, b) ->
            val start = positions[a]; val end = positions[b]
            if (start != null && end != null) drawLine(Tokens.Outline, start, end, strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 8f)))
        }
        path.zipWithNext().forEach { (a, b) ->
            val start = positions[a]; val end = positions[b]
            if (start != null && end != null) drawLine(Tokens.Yellow, start, end, strokeWidth = 5f)
        }
        fun label(text: String, at: Offset, color: Color = Tokens.Muted, centered: Boolean = false) {
            val measured = measurer.measure(text, TextStyle(fontFamily = Saira, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold))
            drawText(measured, topLeft = if (centered) at - Offset(measured.size.width / 2f, measured.size.height / 2f) else at)
        }
        val bus = Offset(w * .345f, h * .03f)
        drawRoundRect(Tokens.Yellow, bus, Size(w * .09f, h * .13f), CornerRadius(5f))
        label("Stopped bus", Offset(w * .01f, h * .05f), Tokens.Yellow)
        positions.forEach { (id, at) ->
            val enabled = id != "B" && id != "D" || id == "B" && config.relayEnabled || id == "D" && config.alternateEnabled
            val selected = id == "C"
            drawCircle(if (selected) Tokens.Yellow else if (enabled) Tokens.Panel else Tokens.Ink, 17.dp.toPx(), at)
            if (!selected) drawCircle(if (enabled) Tokens.Paper else Tokens.Outline, 17.dp.toPx(), at, style = Stroke(2f))
            label(id, at, if (selected) Tokens.Ink else if (enabled) Tokens.Paper else Tokens.Faint, centered = true)
            val caption = when (id) { "A" -> "observes"; "B" -> if (enabled) "relay" else "offline"; "C" -> "recipient"; "E" -> "opposing"; else -> if (enabled) "alternate" else "absent" }
            label(caption, Offset(if (id == "D" || id == "E") w * .77f else w * .01f, at.y - 8.dp.toPx()))
        }
    }
}

@Composable private fun LiveSwarmContent(s: SessionState, onAskRoad: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    val stage = s.mode == Mode.STAGE
    val assessment = sessionSwarmAssessment(s, now)
    val retained = s.known.flatMap { it.evidence }.distinctBy { it.id }
    val own = retained.count { it.own }
    val shared = retained.count { !it.own }

    SwarmPanel {
        SwarmEyebrow(if (stage) "THIS PHONE · STAGE POSITIONS" else "THIS PHONE · ROAD MODE")
        Text(if (s.running) "Local knowledge" else "Session not running", style = MaterialTheme.typography.titleLarge, color = Tokens.Paper)
        Text(if (stage) "Position data is synthetic. Received records come from this session; they are separate from the lab scenario."
            else "Only records held by this phone are shown. Missing information remains unknown.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SwarmMetric("${s.chain.count { !it.self }}", "peers heard", Modifier.weight(1f))
            SwarmMetric("$own", "own observations", Modifier.weight(1f))
            SwarmMetric("$shared", "shared observations", Modifier.weight(1f))
        }
        Text("Evidence counts include retained records; only fresh, attributed records can support the advisory below.", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
        if (onAskRoad != null) OutlinedButton(onClick = onAskRoad, enabled = s.ready && !s.asking, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.asking) "Asking nearby phones…" else "Ask nearby phones for their road picture")
        }
    }
    s.picture?.let { picture ->
        SwarmPanel {
            SwarmEyebrow("LAST ROAD QUERY · RESPONSE SNAPSHOT")
            val sinceQueryMs = s.pictureAtMs?.let { (now - it).coerceAtLeast(0) }
            Text("${picture.peers.size} responding phones", style = MaterialTheme.typography.titleMedium, color = Tokens.Paper)
            if (picture.items.isEmpty()) Text("No conditions returned. Coverage is unknown.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
            picture.items.filter { !it.kind.violation }.forEach { item ->
                val age = sinceQueryMs?.let { item.ageS + (it / 1_000).toInt() }
                Text(item.kind.title(), style = MaterialTheme.typography.titleMedium, color = Tokens.Paper)
                val identity = if (item.lineageKnown) "${item.evidenceIds.map { it.origin }.distinct().size} claimed sources" else "unknown source lineage"
                val freshness = when { age == null -> "age unavailable"; age >= 15 -> "$age seconds old · outside swarm freshness window"; else -> "$age seconds old" }
                Text("$identity · $freshness", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                if (item.provenances.any { it != Provenance.LIVE }) Text("Contains replay or demo evidence", style = MaterialTheme.typography.bodySmall, color = Tokens.Yellow)
            }
            Text("Query responses retain their original sources and age. This snapshot does not establish lane, direction or a clear passage.", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
        }
    }
    SwarmPanel {
        SwarmEyebrow("CONSERVATIVE ADVISORY")
        Text(assessment.title, style = MaterialTheme.typography.titleLarge,
            color = if (assessment.status == SwarmStatus.CAUTION) Tokens.Yellow else Tokens.Paper)
        Text(assessment.advice, style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        if (!stage) Text("Grouping uses reported GPS and heading uncertainty. Missing measurements remain uncertain; a heading group is not a verified road or lane.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Yellow)
        Text("${assessment.independentSources} claimed original sources in the current local evidence. Reports may describe different conditions; copies of one event do not add sources. IDs are not proof of independent vehicles.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
        assessment.nominatedObserverId?.let { id ->
            Text("Local sensing nominee: ${id.takeLast(6)}", style = MaterialTheme.typography.titleMedium, color = Tokens.Paper)
            Text("A recommendation from this phone’s view; no task was sent and no vehicle has been given control.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        }
    }
    SwarmPanel {
        SwarmEyebrow("DIRECTION-AWARE NEIGHBOURS")
        if (assessment.neighbours.isEmpty()) Text("No peer evidence yet. Start a mesh session with another phone.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
        assessment.neighbours.forEach { neighbour ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(neighbour.id.takeLast(6), style = MaterialTheme.typography.titleMedium, color = Tokens.Paper)
                Text(neighbour.relation.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = Tokens.Yellow)
            }
            Text(neighbour.reason, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        }
    }
    Text("Advisory only. No automatic steering, braking or crossing permission. The prototype does not yet learn or deploy driving policies.",
        style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
}

/** Keep source identity, capture time and confidence attached to each observation, never to its latest copy. */
internal fun sessionSwarmAssessment(s: SessionState, nowMs: Long): SwarmAssessment {
    val stage = s.mode == Mode.STAGE
    val participants = s.chain.map { member ->
        SwarmParticipant(id = member.node.toString(), fix = member.fix, observedAtMs = member.lastHeardMs,
            positionAccuracyM = if (stage) 0.0 else member.fix?.accuracyM ?: Double.POSITIVE_INFINITY,
            headingAccuracyDeg = if (stage) 0.0 else member.fix?.headingAccuracyDeg ?: Double.POSITIVE_INFINITY)
    }
    val ego = participants.firstOrNull { it.id == s.me?.node?.toString() }
        ?: SwarmParticipant(s.node?.toString() ?: "this phone", null, nowMs,
            positionAccuracyM = Double.POSITIVE_INFINITY, headingAccuracyDeg = Double.POSITIVE_INFINITY)
    val observations = s.known.filter { !it.kind.violation }.flatMap { hazard ->
        hazard.evidence.filter { evidence ->
            if (stage) evidence.provenance == Provenance.DEMO || evidence.provenance == Provenance.REPLAY
            else evidence.provenance == Provenance.LIVE
        }.map { evidence ->
            SwarmObservation(eventId = "${evidence.id.origin}/${evidence.id.session}/${evidence.id.sequence}",
                sourceId = evidence.id.origin.toString(), kind = hazard.kind.toSwarmKind(), fix = (evidence.fix ?: hazard.fix).copy(synthetic = stage),
                observedAtMs = evidence.capturedMs, ttlMs = minOf(hazard.kind.lifetimeS * 1_000L, 15_000L),
                confidence = evidence.confidence, positionAccuracyM = if (stage) 0.0 else evidence.fix?.accuracyM ?: Double.POSITIVE_INFINITY)
        }
    }
    return SwarmAdvisor.evaluate(ego, participants, observations, nowMs)
}

private fun HazardKind.toSwarmKind() = when (this) {
    HazardKind.ACCIDENT, HazardKind.ROAD_NARROWED -> ObservationKind.OBSTRUCTION
    HazardKind.BRAKING, HazardKind.SLOWDOWN -> ObservationKind.QUEUE_TAIL
    HazardKind.PEDESTRIAN -> ObservationKind.PEDESTRIAN
    HazardKind.WRONG_WAY -> ObservationKind.WRONG_WAY
    else -> ObservationKind.ROAD_HAZARD
}

@Composable private fun SwarmPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Tokens.Panel, RoundedCornerShape(18.dp))
        .border(1.dp, Tokens.Line, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable private fun SwarmEyebrow(text: String, color: Color = Tokens.Faint) {
    Text(text, fontFamily = Michroma, fontSize = 9.sp, lineHeight = 15.sp, letterSpacing = .7.sp, color = color)
}

@Composable private fun SwarmToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Tokens.Paper)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable private fun SwarmMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, fontFamily = Saira, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Tokens.Yellow)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
    }
}
