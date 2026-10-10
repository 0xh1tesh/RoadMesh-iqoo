package com.jsons.roadmesh.ui

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jsons.roadmesh.*
import com.jsons.roadmesh.copilot.CopilotLine
import com.jsons.roadmesh.copilot.CopilotState
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.perception.ObjectClass
import com.jsons.roadmesh.perception.ObserverState
import com.jsons.roadmesh.perception.Raised
import com.jsons.roadmesh.service.EventTrace
import com.jsons.roadmesh.service.MeshSession
import com.jsons.roadmesh.service.SessionState
import com.jsons.roadmesh.service.letter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Role.letter get() = ('A'.code + ordinal).toChar()
private fun SessionState.me() = node?.letter() ?: role.letter.toString()
private fun SessionState.next() = if (role.ordinal < 2) ('A' + role.ordinal + 1).toString() else "the next phone"

@Composable fun RoadMeshScreen(app: RoadMeshApplication, start: () -> Unit, stop: () -> Unit, scene: () -> Unit) {
    RoadMeshTheme {
        Surface(Modifier.fillMaxSize(), color = Tokens.Ink) { Root(app, start, stop, scene) }
    }
}

internal class Actions(val start: () -> Unit, val stop: () -> Unit, val logs: () -> Unit, val settings: () -> Unit, val check: () -> Unit,
                       val importModel: () -> Unit, val pickVideo: () -> Unit, val scene: () -> Unit, val setup: () -> Unit)

@Composable private fun Root(app: RoadMeshApplication, start: () -> Unit, stop: () -> Unit, scene: () -> Unit) {
    val mesh = app.session
    val state by mesh.state.collectAsState()
    val copilot by app.copilot.flow.collectAsState()
    val observer by app.observer.flow.collectAsState()
    val questions by app.questions.collectAsState()
    var onboarded by remember { mutableStateOf(app.preferences.onboarded) }
    var page by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(app.copilot::import) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.observer.use(uri, displayName(context, uri) ?: "Your video")
    }
    val actions = Actions(start = start, stop = stop, logs = { page = "logs" }, settings = { page = "settings" }, check = { page = "check" },
        importModel = { pickModel.launch(arrayOf("*/*")) }, pickVideo = { pickVideo.launch(arrayOf("video/*")) }, scene = scene,
        setup = { app.observer.simulate(false); stop(); onboarded = false })

    when {
        !onboarded -> Onboarding(app.preferences.vehicle, state.role) { vehicle, role ->
            app.preferences.vehicle = vehicle
            runCatching { mesh.select(role) }
            app.preferences.onboarded = true; onboarded = true
        }
        page == "check" -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            SignalEdge(if (state.ready) Tokens.Green else Tokens.Red)
            TextButton(onClick = { page = null }, modifier = Modifier.padding(8.dp)) { Text("Back", color = Tokens.Yellow) }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { SetupCheckScreen(canProbeRadio = !state.running) }
        }
        else -> MainTabs(app, state, copilot, observer, questions, actions)
    }
    if (page == "logs") LogsDialog(state) { page = null }
    if (page == "settings") SettingsDialog(mesh) { page = null }
}

private fun displayName(context: android.content.Context, uri: Uri) = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()

enum class Tab(val title: String, val icon: TabIcon) { OBSERVE("Observe", TabIcon.OBSERVE), RELAY("Relay", TabIcon.RELAY), RECEIVED("Received", TabIcon.RECEIVED) }

/* ---------- The three tabs ---------- */

@Composable internal fun MainTabs(app: RoadMeshApplication, state: SessionState, copilot: CopilotState, o: ObserverState, questions: List<Question>, actions: Actions) {
    var tab by rememberSaveable { mutableStateOf(Tab.entries[state.role.ordinal]) }
    LaunchedEffect(Unit) { if (!state.running) actions.start() }
    val now by produceState(SystemClock.elapsedRealtime()) { while (true) { value = SystemClock.elapsedRealtime(); delay(500) } }
    val warned = state.events.any { !it.originated && now - it.atMs < 10_000 }
    val received = state.events.count { !it.originated }
    val passed = state.events.count { it.relayedAtMs != null || it.sentAtMs != null }
    // A new warning brings the Received tab forward unless this phone is busy observing.
    LaunchedEffect(state.events.firstOrNull { !it.originated }?.id) { if (received > 0 && tab != Tab.OBSERVE && state.role == Role.RECEIVER) tab = Tab.RECEIVED }
    Column(Modifier.fillMaxSize()) {
        SignalEdge(when { warned -> Tokens.Yellow; state.ready -> Tokens.Green; else -> Tokens.Red })
        Column(Modifier.weight(1f).safeDrawingPadding()) {
            TopBar(state, app.preferences.vehicle, actions)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.OBSERVE -> ObserveTab(app, state, copilot, o, questions, actions)
                    Tab.RELAY -> RelayTab(state, copilot, now, actions)
                    Tab.RECEIVED -> ReceivedTab(app, state, copilot, questions, now, actions)
                }
            }
            NavigationBar(containerColor = Tokens.Panel, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    val count = when (t) { Tab.OBSERVE -> o.raised.size; Tab.RELAY -> passed; Tab.RECEIVED -> received }
                    NavigationBarItem(selected, { tab = t }, icon = {
                        BadgedBox(badge = { if (count > 0) Badge(containerColor = Tokens.Yellow, contentColor = Tokens.Ink) { Text("$count") } }) {
                            TabGlyph(t.icon, if (selected) Tokens.Ink else Tokens.Muted)
                        }
                    }, label = { Text(t.title, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Tokens.Yellow, selectedTextColor = Tokens.Yellow, unselectedTextColor = Tokens.Muted))
                }
            }
        }
    }
}

@Composable private fun TopBar(state: SessionState, vehicle: VehicleType, actions: Actions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Wordmark(28.dp, 12)
        Row(Modifier.background(Tokens.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoleBadge(state.role.letter, roleColor(state.role.ordinal), 20.dp)
            Text(vehicle.title, fontSize = 13.sp, color = Tokens.Secondary, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (state.running) StatusDot(if (state.ready) Tokens.Green else Tokens.Yellow, if (state.ready) "Mesh live" else "Starting")
        else TextButton(onClick = actions.start) { Text("Start mesh", color = Tokens.Yellow, fontWeight = FontWeight.Bold) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "More options" }) { Text("•••", color = Tokens.Paper, fontWeight = FontWeight.Bold) }
            DropdownMenu(menu, { menu = false }, containerColor = Tokens.Raised) {
                if (state.running) DropdownMenuItem({ Text("Stop mesh") }, { menu = false; actions.stop() })
                DropdownMenuItem({ Text("Logs and export") }, { menu = false; actions.logs() })
                DropdownMenuItem({ Text("Change vehicle or position") }, { menu = false; actions.setup() })
                DropdownMenuItem({ Text("Import co-pilot model") }, { menu = false; actions.importModel() })
                DropdownMenuItem({ Text("Device check") }, { menu = false; actions.check() })
                DropdownMenuItem({ Text("Mesh settings") }, { menu = false; actions.settings() })
                DropdownMenuItem({ Text("Virtual brake scene") }, { menu = false; actions.scene() })
            }
        }
    }
}

@Composable private fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable private fun Heading(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 33.sp))
    Text(body, color = Tokens.Muted, fontSize = 16.sp, lineHeight = 23.sp)
}

/* ---------- Observe ---------- */

@Composable private fun ObserveTab(app: RoadMeshApplication, state: SessionState, copilot: CopilotState, o: ObserverState, questions: List<Question>, actions: Actions) {
    if (!o.simulating) {
        TabColumn {
            Heading("Observe the road", "This phone becomes the dashcam. Simulate it with a real dashcam clip: YOLO runs on every frame, and hazards go to the phones behind.")
            Surface(shape = RoundedCornerShape(24.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoadScene(Modifier.fillMaxWidth().height(170.dp), highlight = state.role.ordinal)
                    Text("Simulate dashcam", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Clip: ${o.source}", color = Tokens.Faint, fontSize = 13.sp)
                    PrimaryButton("Play simulation", { app.observer.simulate(true) }, Modifier.fillMaxWidth())
                    GhostButton("Choose another video", actions.pickVideo, Modifier.fillMaxWidth())
                }
            }
            CopilotStatus(copilot, actions)
            AskPanel(questions, copilot, app::ask)
        }
        return
    }
    val recent = o.raised.firstOrNull()?.takeIf { SystemClock.elapsedRealtime() - it.atMs < 3_500 }
    val overtakeAsked = questions.firstOrNull()?.let { it.notNow != null && SystemClock.elapsedRealtime() - it.atMs < 30_000 } == true
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val video = @Composable { m: Modifier ->
            Box(m.background(Color.Black)) {
                VideoPane(app, o, Modifier.align(Alignment.Center))
                HazardFlash(recent, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                PerfChip(o, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
        if (landscape) Row(Modifier.fillMaxSize()) {
            video(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ObservePanel(app, o, copilot, questions, overtakeAsked, actions)
            }
        } else Column(Modifier.fillMaxSize()) {
            video(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ObservePanel(app, o, copilot, questions, overtakeAsked, actions)
            }
        }
    }
}

@Composable internal fun ObservePanel(app: RoadMeshApplication, o: ObserverState, copilot: CopilotState, questions: List<Question>, overtakeAsked: Boolean, actions: Actions) {
    o.error?.let { Text(it, color = Tokens.Red, fontSize = 14.sp) }
    val hazardLine = o.raised.firstOrNull()?.let { copilot.lines["hazard:${it.call.atS}"] }
    val live = listOfNotNull(hazardLine?.takeIf { !it.done || o.raised.first().let { r -> SystemClock.elapsedRealtime() - r.atMs < 12_000 } }, copilot.latestScene).firstOrNull()
    LiveAnalysis(live, copilot, actions)
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
        Metric(o.lead?.let { "${it.distanceM.toInt()} m" } ?: "—", o.lead?.let { "${it.subject.cls.name.lowercase()} ahead" } ?: "lane clear", Tokens.Yellow)
        Metric(o.lead?.ttcS?.let { "%.1f s".format(it) } ?: "steady", "to contact")
        Metric("${o.overlays.size}", "tracked")
    }
    Text(counts(o.counts), color = Tokens.Muted, fontSize = 14.sp)
    Text("3D FRAME", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
    BirdsEye3D(o.overlays, Modifier.background(Tokens.Panel, RoundedCornerShape(16.dp)), highlightOvertake = overtakeAsked)
    Text(if (overtakeAsked) "Shaded: the overtaking lane the co-pilot checked." else "Top view from size-based distance estimates. Yellow: in your lane. Red: the hazard just sent.",
        color = Tokens.Faint, fontSize = 12.sp)
    AskPanel(questions, copilot, app::ask)
    Text("Sent to the mesh", style = MaterialTheme.typography.titleMedium)
    if (o.raised.isEmpty()) Text("Nothing yet. A pedestrian in the lane, a vehicle cutting in close, or fast closing on the car ahead is sent to the next phone.",
        color = Tokens.Faint, fontSize = 14.sp, lineHeight = 20.sp)
    o.raised.forEach { r -> RaisedRow(r) }
    Text("Clip: ${o.source}", color = Tokens.Faint, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(if (o.playing) "Pause" else "Play", app.observer::toggle, Modifier.weight(1f), height = 48.dp)
        GhostButton("Other video", actions.pickVideo, Modifier.weight(1f))
    }
    GhostButton("Stop simulation", { app.observer.simulate(false) }, Modifier.fillMaxWidth())
}

@Composable private fun RaisedRow(r: Raised) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.call.kind.title(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("at ${"%.1f".format(r.call.atS)} s · ${r.call.reason}", color = Tokens.Faint, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(if (r.sent) "Sent" else "Not sent", color = if (r.sent) Tokens.Green else Tokens.Red, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
    HorizontalDivider(color = Tokens.Line)
}

@Composable private fun LiveAnalysis(line: CopilotLine?, copilot: CopilotState, actions: Actions) {
    Surface(shape = RoundedCornerShape(20.dp), color = Tokens.Panel, border = BorderStroke(1.dp, if (line?.done == false) Tokens.Yellow else Tokens.Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LIVE ANALYSIS", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
                Text(if (copilot.ready) "Gemma 2B · on device" else "Co-pilot off", fontSize = 12.sp, color = Tokens.Faint)
            }
            when {
                !copilot.ready -> { Text(copilot.detail, color = Tokens.Muted, fontSize = 15.sp); ImportButton(copilot, actions) }
                line == null || line.text.isEmpty() -> Text("Reading the scene…", color = Tokens.Faint, fontSize = 16.sp)
                else -> Text(line.text + if (line.done) "" else " ▍", fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
            }
            line?.ms?.let { Text("${"%.1f".format(it / 1000.0)} s on this phone's CPU, offline", style = Tabular, fontSize = 12.sp, color = Tokens.Faint) }
        }
    }
}

@Composable private fun ImportButton(copilot: CopilotState, actions: Actions) {
    if (copilot.importProgress != null) LinearProgressIndicator(progress = { copilot.importProgress }, color = Tokens.Yellow, trackColor = Tokens.Raised, modifier = Modifier.fillMaxWidth())
    else if (copilot.status == CopilotState.Status.MISSING || copilot.status == CopilotState.Status.FAILED)
        TextButton(onClick = actions.importModel, contentPadding = PaddingValues(0.dp)) { Text("Import gemma-2b-it-cpu-int4.bin", color = Tokens.Yellow) }
}

@Composable private fun CopilotStatus(copilot: CopilotState, actions: Actions) {
    if (copilot.ready) return
    Surface(shape = RoundedCornerShape(16.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Co-pilot", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(copilot.detail, color = Tokens.Muted, fontSize = 14.sp)
            ImportButton(copilot, actions)
        }
    }
}

@Composable private fun VideoPane(app: RoadMeshApplication, o: ObserverState, modifier: Modifier) {
    DisposableEffect(Unit) { onDispose { app.observer.detach() } }
    Box(modifier.aspectRatio(16f / 9f)) {
        AndroidView(factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = app.observer.attach(this@apply, st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { app.observer.detach(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        }, modifier = Modifier.fillMaxSize())
        DetectionOverlay3D(o.overlays)
    }
}

@Composable private fun HazardFlash(recent: Raised?, modifier: Modifier) {
    if (recent == null) return
    Row(modifier.background(Tokens.Yellow, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(recent.call.kind.title().uppercase(), color = Tokens.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Text(if (recent.sent) "Sent to mesh" else "Not sent", color = Tokens.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun PerfChip(o: ObserverState, modifier: Modifier) {
    Text(listOfNotNull(o.detector, o.inferMs?.let { "$it ms" }, o.fps?.let { "%.1f fps".format(it) }).joinToString(" · "),
        style = Tabular, fontSize = 12.sp, color = Tokens.Secondary,
        modifier = modifier.background(Tokens.Scrim, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable private fun Metric(value: String, label: String, color: Color = Tokens.Paper) = Column {
    Text(value, style = Tabular, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = color, lineHeight = 34.sp)
    Text(label, color = Tokens.Faint, fontSize = 13.sp)
}

private fun counts(c: Map<ObjectClass, Int>): String {
    if (c.isEmpty()) return "Nothing detected in this frame"
    return c.entries.sortedByDescending { it.value }.joinToString(" · ") { (k, n) ->
        val name = when (k) { ObjectClass.PERSON -> if (n == 1) "person" else "people"; else -> k.name.lowercase() + if (n > 1) "s" else "" }
        "$n $name"
    }
}

/* ---------- Relay ---------- */

@Composable internal fun RelayTab(state: SessionState, copilot: CopilotState, now: Long, actions: Actions) {
    val me = state.me()
    TabColumn {
        when (state.role) {
            Role.OBSERVER -> Heading("Your warnings go to B", "This phone is at the front. Hazards its camera finds are broadcast to B, which re-infers and passes them to C.")
            Role.RELAY -> Heading("A → you → C", "Warnings from A arrive here. This phone works out what they mean from where it is, then relays them to C.")
            Role.RECEIVER -> Heading("End of the chain", "C is the last phone. It receives from B and does not relay further in the stage chain.")
        }
        val fromA = state.events.any { it.originated && it.sentAtMs != null } || state.events.any { !it.originated }
        val toC = state.events.any { it.relayedAtMs != null } || state.role == Role.RECEIVER && state.events.any { !it.originated && it.via.value == 2L }
        Chain(state.role, fromA, toC)
        if (state.events.isEmpty()) Text("No warnings have passed through this phone yet.", color = Tokens.Faint, fontSize = 15.sp)
        state.events.forEach { e -> EventStepper(e, me, state.next(), copilot.lines["event:${e.id}"], now) }
        Text(state.status, color = if (state.ready) Tokens.Faint else Tokens.Red, fontSize = 13.sp)
        GhostButton("Logs", actions.logs, Modifier.fillMaxWidth())
    }
}

private fun detailText(d: HazardDetail?) = d?.let {
    listOfNotNull(it.rangeM?.let { r -> "camera saw it $r m ahead" }, it.vehicle.takeIf { v -> v != VehicleType.UNKNOWN }?.let { v -> "from a ${v.title}" },
        it.confidence?.let { c -> "${(c * 100).toInt()}% confidence" }).joinToString(" · ")
} ?: "no detail sent"

@Composable private fun EventStepper(e: EventTrace, me: String, next: String, line: CopilotLine?, now: Long) {
    Surface(shape = RoundedCornerShape(20.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.kind?.title() ?: "Unknown hazard", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text("${(now - e.atMs) / 1000} s ago", style = Tabular, fontSize = 13.sp, color = Tokens.Faint)
            }
            if (e.originated) {
                Step(true, "Detected by this phone's camera", detailText(e.detail))
                Step(e.sentAtMs != null, if (e.sentAtMs != null) "Sent to $next" else "Sending to $next…", "Broadcast over Bluetooth. Delivery shows on the next phone.", last = true)
            } else {
                val origin = e.id.origin.letter(); val via = e.via.letter()
                Step(true, "Received from $via" + if (via != origin) " · started by $origin" else "", "$origin's inference: ${detailText(e.detail)}")
                Step(true, "Inference on $me", e.inference?.let { "Hazard about ${it.hazardAheadM} m ahead of this phone" + (it.etaS?.let { s -> ", ${s.toInt()} s away" } ?: "") + ". ${it.action.replaceFirstChar { c -> c.uppercase() }}." } ?: "Position unknown",
                    copilot = line)
                if (e.canRelay) Step(e.relayedAtMs != null, if (e.relayedAtMs != null) "Relayed to $next" else "Relaying to $next…", "Same event, same origin. The relay adds no trust of its own.", last = true)
                else Step(null, "Not relayed", "The warning's relay budget is used up, so this is the last hop.", last = true)
            }
        }
    }
}

@Composable private fun Step(done: Boolean?, title: String, body: String, copilot: CopilotLine? = null, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Box(Modifier.size(18.dp).background(when (done) { true -> Tokens.Green; false -> Tokens.Yellow; null -> Tokens.Raised }, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                if (done == true) Canvas(Modifier.size(10.dp)) { drawPath(Path().apply { moveTo(0f, size.height * 0.55f); lineTo(size.width * 0.38f, size.height * 0.9f); lineTo(size.width, size.height * 0.1f) }, Tokens.Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(4f)) }
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(Tokens.Outline))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, bottom = if (last) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (done == false) Tokens.Yellow else Tokens.Paper)
            Text(body, color = Tokens.Muted, fontSize = 14.sp, lineHeight = 19.sp)
            copilot?.takeIf { it.text.isNotEmpty() }?.let { Text("Co-pilot: " + it.text + if (it.done) "" else " ▍", fontSize = 15.sp, lineHeight = 21.sp, color = Tokens.Paper) }
        }
    }
}

/** A → B → C with this phone ringed in yellow; a link is lit once a warning has crossed it. */
@Composable private fun Chain(me: Role, firstLink: Boolean, secondLink: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Role.entries.forEachIndexed { i, role ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                Box(Modifier.size(52.dp).border(2.dp, if (role == me) Tokens.Yellow else Color.Transparent, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                    RoleBadge(role.letter, roleColor(i), 42.dp)
                }
                Text(role.title, fontSize = 13.sp, color = if (role == me) Tokens.Yellow else Tokens.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
            if (i < 2) {
                val lit = if (i == 0) firstLink else secondLink
                Canvas(Modifier.weight(1f).height(52.dp)) {
                    val y = size.height / 2; val c = if (lit) Tokens.Green else Tokens.Outline
                    drawLine(c, Offset(4f, y), Offset(size.width - 14f, y), strokeWidth = if (lit) 6f else 3f, pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    drawPath(Path().apply { moveTo(size.width - 4f, y); lineTo(size.width - 18f, y - 9f); lineTo(size.width - 18f, y + 9f); close() }, c)
                }
            }
        }
    }
}

/* ---------- Received ---------- */

@Composable internal fun ReceivedTab(app: RoadMeshApplication, state: SessionState, copilot: CopilotState, questions: List<Question>, now: Long, actions: Actions) {
    val received = state.events.filter { !it.originated }
    val latest = received.firstOrNull()
    TabColumn {
        if (latest != null && now - latest.atMs < 20_000) {
            val origin = latest.id.origin.letter(); val via = latest.via.letter()
            Surface(shape = RoundedCornerShape(24.dp), color = Tokens.Panel, border = BorderStroke(2.dp, Tokens.Yellow), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("INFERENCE RECEIVED FROM $via", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
                    Text(latest.kind?.title() ?: "Warning", fontFamily = Saira, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 44.sp, color = Tokens.Yellow)
                    latest.inference?.let { Text("About ${it.hazardAheadM} m ahead of you" + (it.etaS?.let { s -> " · ${s.toInt()} s away" } ?: ""), style = Tabular, fontSize = 18.sp, color = Tokens.Paper) }
                    Text("Started by $origin (${detailText(latest.detail)})" + if (via != origin) ", relayed by $via." else ".", fontSize = 14.sp, color = Tokens.Muted, lineHeight = 19.sp)
                    latest.inference?.let { Text("Your move: ${it.action}.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    HorizontalDivider(color = Tokens.Line)
                    val line = copilot.lines["event:${latest.id}"]
                    Text("CO-PILOT", fontFamily = Michroma, fontSize = 10.sp, letterSpacing = 1.sp, color = Tokens.Faint)
                    Text(when {
                        line != null && line.text.isNotEmpty() -> line.text + if (line.done) "" else " ▍"
                        copilot.ready -> "Thinking…"
                        else -> "Co-pilot not installed on this phone."
                    }, fontSize = 17.sp, lineHeight = 24.sp)
                }
            }
        } else Heading("Listening for warnings", "Warnings reach this phone over Bluetooth with no internet. Each one shows who sent it, what they saw, and what it means from where you are.")
        Chain(state.role, received.isNotEmpty() || state.role == Role.RELAY && received.isNotEmpty(), state.role == Role.RECEIVER && received.isNotEmpty())
        AskPanel(questions, copilot, app::ask)
        if (received.size > (if (latest != null && now - latest.atMs < 20_000) 1 else 0)) {
            Text("Earlier", style = MaterialTheme.typography.titleMedium)
            received.forEach { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.kind?.title() ?: "Warning", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("from ${e.via.letter()} · started by ${e.id.origin.letter()}", fontSize = 13.sp, color = Tokens.Faint)
                    }
                    Text("${e.inference?.hazardAheadM?.let { "$it m · " } ?: ""}${(now - e.atMs) / 1000} s ago", style = Tabular, fontSize = 14.sp, color = Tokens.Faint)
                }
                HorizontalDivider(color = Tokens.Line)
            }
        }
        Text(state.status, color = if (state.ready) Tokens.Faint else Tokens.Red, fontSize = 13.sp)
    }
}

/* ---------- Logs and settings ---------- */

@Composable private fun LogsDialog(state: SessionState, close: () -> Unit) {
    val mesh = (LocalContext.current.applicationContext as RoadMeshApplication).session
    val coroutine = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = RoundedCornerShape(24.dp), color = Tokens.Panel) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Local diagnostics", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f))
                    TextButton(onClick = close) { Text("Close", color = Tokens.Yellow) }
                }
                Text("${state.bounds.dedup} events · ${state.bounds.neighbors} neighbours · ${state.bounds.pending} pending", style = Tabular, color = Tokens.Secondary)
                Text(state.counters.entries.joinToString("  ") { "${it.key}=${it.value}" }, fontSize = 12.sp, color = Tokens.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { coroutine.launch { message = runCatching { mesh.export() }.getOrElse { "Export failed: ${it.message}" } } }) { Text("Export JSON", color = Tokens.Yellow) }
                    Text(message, fontSize = 12.sp, color = Tokens.Muted)
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.logs.takeLast(120).asReversed().forEach {
                        Text("${it.monotonicMs}  ${it.code}  ${it.detail}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Tokens.Secondary,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun SettingsDialog(mesh: MeshSession, close: () -> Unit) {
    val state by mesh.state.collectAsState()
    var json by remember { mutableStateOf(profileJson(mesh.preferences.profile).toString(2)) }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f), shape = RoundedCornerShape(24.dp), color = Tokens.Panel) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mesh settings", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f)); TextButton(onClick = close) { Text("Close", color = Tokens.Yellow) }
                }
                Text("Stop before editing. Invalid input keeps the last saved profile.", color = Tokens.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mode.entries.forEach { mode -> TextButton(enabled = !state.running, onClick = {
                        mesh.mode(mode); json = profileJson(mesh.preferences.profile).toString(2); error = ""
                    }) { Text(mode.name, color = if (state.mode == mode) Tokens.Yellow else Tokens.Muted) } }
                    TextButton(enabled = !state.running, onClick = { json = profileJson(MeshProfile()).toString(2) }) { Text("Defaults", color = Tokens.Muted) }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Save", { runCatching { mesh.saveProfile(json) }.onSuccess { error = "Saved" }.onFailure { error = it.message.orEmpty() } },
                        enabled = !state.running, height = 44.dp)
                }
                Text(error, color = if (error == "Saved") Tokens.Green else Tokens.Red, fontSize = 13.sp)
                OutlinedTextField(value = json, onValueChange = { json = it }, enabled = !state.running,
                    modifier = Modifier.fillMaxWidth().weight(1f), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            }
        }
    }
}

