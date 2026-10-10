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
import com.jsons.roadmesh.service.rankLetter
import com.jsons.roadmesh.core.road.RoadPicture
import com.jsons.roadmesh.core.road.aheadWords
import com.jsons.roadmesh.reports.RoadReport
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Role.letter get() = ('A'.code + ordinal).toChar()
/** This phone's chain letter (A is the front), or its setup role's letter before the chain forms. */
private fun SessionState.me() = if (rank != NO_RANK) rankLetter(rank) else role.letter.toString()
private fun SessionState.next() = if (rank != NO_RANK && chain.size > rank + 1) rankLetter(rank + 1) else "the phone behind"

@Composable fun RoadMeshScreen(app: RoadMeshApplication, start: () -> Unit, stop: () -> Unit, scene: () -> Unit) {
    RoadMeshTheme {
        Surface(Modifier.fillMaxSize(), color = Tokens.Ink) { Root(app, start, stop, scene) }
    }
}

internal class Actions(val start: () -> Unit, val stop: () -> Unit, val logs: () -> Unit, val settings: () -> Unit, val check: () -> Unit,
                       val importModel: () -> Unit, val pickVideo: () -> Unit, val scene: () -> Unit, val setup: () -> Unit, val npu: () -> Unit = {})

@Composable private fun Root(app: RoadMeshApplication, start: () -> Unit, stop: () -> Unit, scene: () -> Unit) {
    val mesh = app.session
    val state by mesh.state.collectAsState()
    val copilot by app.copilot.flow.collectAsState()
    val observer by app.observer.flow.collectAsState()
    val questions by app.questions.collectAsState()
    var onboarded by remember { mutableStateOf(app.preferences.onboarded) }
    var page by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { app.copilot.import(it, displayName(context, it)) } }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.observer.use(uri, displayName(context, uri) ?: "Your video")
    }
    val actions = Actions(start = start, stop = stop, logs = { page = "logs" }, settings = { page = "settings" }, check = { page = "check" },
        importModel = { pickModel.launch(arrayOf("*/*")) }, pickVideo = { pickVideo.launch(arrayOf("video/*")) }, scene = scene,
        setup = { app.observer.simulate(false); stop(); onboarded = false },
        npu = {
            com.jsons.roadmesh.perception.NpuProbeService.start(context)
            android.widget.Toast.makeText(context, "Testing the NPU in the background. Restart the dashcam simulation in a minute to use it.", android.widget.Toast.LENGTH_LONG).show()
        })

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

enum class Tab(val title: String, val icon: TabIcon) {
    OBSERVE("Observe", TabIcon.OBSERVE), MESH("Mesh", TabIcon.MESH), RECEIVED("Road", TabIcon.RECEIVED), SWARM("Swarm", TabIcon.RELAY), REPORTS("Reports", TabIcon.REPORTS)
}

/* ---------- The tabs ---------- */

@Composable internal fun MainTabs(app: RoadMeshApplication, state: SessionState, copilot: CopilotState, o: ObserverState, questions: List<Question>, actions: Actions) {
    var tab by rememberSaveable { mutableStateOf(when (state.role) { Role.OBSERVER -> Tab.OBSERVE; Role.RELAY -> Tab.MESH; Role.RECEIVER -> Tab.RECEIVED }) }
    val reports by app.reports.flow.collectAsState()
    LaunchedEffect(Unit) { if (!state.running) actions.start() }
    val now by produceState(SystemClock.elapsedRealtime()) { while (true) { value = SystemClock.elapsedRealtime(); delay(500) } }
    val warned = state.events.any { !it.originated && now - it.atMs < 10_000 }
    val received = state.events.count { !it.originated }
    val passed = state.events.count { it.relayedAtMs != null || it.sentAtMs != null }
    // The tab follows the role the chain gives this phone: A observes, the middle relays, the last receives.
    LaunchedEffect(state.liveRole, state.chain.size > 1) {
        if (state.mode == Mode.STAGE && state.chain.isNotEmpty() && tab != Tab.SWARM && tab != Tab.REPORTS)
            tab = when (state.liveRole) { Role.OBSERVER -> Tab.OBSERVE; Role.RELAY -> Tab.MESH; Role.RECEIVER -> Tab.RECEIVED }
    }
    // A new warning brings the Received tab forward unless this phone is busy observing.
    LaunchedEffect(state.events.firstOrNull { !it.originated }?.id) { if (received > 0 && tab == Tab.MESH && state.liveRole == Role.RECEIVER) tab = Tab.RECEIVED }
    Column(Modifier.fillMaxSize()) {
        SignalEdge(when { warned -> Tokens.Yellow; state.ready -> Tokens.Green; else -> Tokens.Red })
        Column(Modifier.weight(1f).safeDrawingPadding()) {
            TopBar(state, app.preferences.vehicle, actions)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.OBSERVE -> ObserveTab(app, state, copilot, o, questions, actions)
                    Tab.MESH -> MeshTab(state, copilot, now, actions)
                    Tab.RECEIVED -> ReceivedTab(app, state, copilot, questions, now, actions)
                    Tab.SWARM -> SwarmScreen(state, onAskRoad = { app.askRoad() }, onReportNarrowing = app::reportNarrowing)
                    Tab.REPORTS -> ReportsTab(app, reports)
                }
            }
            NavigationBar(containerColor = Tokens.Panel, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    val count = when (t) { Tab.OBSERVE -> o.raised.size; Tab.MESH -> state.chain.size; Tab.RECEIVED -> received; Tab.SWARM -> 0; Tab.REPORTS -> reports.size }
                    NavigationBarItem(selected, { tab = t }, icon = {
                        BadgedBox(badge = { if (count > 0) Badge(containerColor = Tokens.Yellow, contentColor = Tokens.Ink) { Text("$count") } }) {
                            TabGlyph(t.icon, if (selected) Tokens.Ink else Tokens.Muted)
                        }
                    }, label = { Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Tokens.Yellow, selectedTextColor = Tokens.Yellow, unselectedTextColor = Tokens.Muted))
                }
            }
        }
    }
}

@Composable private fun TopBar(state: SessionState, vehicle: VehicleType, actions: Actions) {
    var menu by remember { mutableStateOf(false) }
    val deskContext = LocalContext.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Wordmark(28.dp, 12)
        Row(Modifier.background(Tokens.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoleBadge(state.me().first(), roleColor(state.liveRole.ordinal), 20.dp)
            Text("${state.liveRole.title} · ${vehicle.title}", fontSize = 13.sp, color = Tokens.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        if (state.running) StatusDot(if (state.ready) Tokens.Green else Tokens.Yellow, if (state.ready) "Mesh live" else "Starting")
        else TextButton(onClick = actions.start) { Text("Start mesh", color = Tokens.Yellow, fontWeight = FontWeight.Bold) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "More options" }) { Text("•••", color = Tokens.Paper, fontWeight = FontWeight.Bold) }
            DropdownMenu(menu, { menu = false }, containerColor = Tokens.Raised) {
                if (state.running) DropdownMenuItem({ Text("Stop mesh") }, { menu = false; actions.stop() })
                DropdownMenuItem({ Text("Logs and export") }, { menu = false; actions.logs() })
                DropdownMenuItem({ Text("Change vehicle") }, { menu = false; actions.setup() })
                DropdownMenuItem({ Text("Try NPU for detection") }, { menu = false; actions.npu() })
                DropdownMenuItem({ Text("Import co-pilot model") }, { menu = false; actions.importModel() })
                DropdownMenuItem({ Text("Device check") }, { menu = false; actions.check() })
                DropdownMenuItem({ Text("Mesh settings") }, { menu = false; actions.settings() })
                DropdownMenuItem({ Text("Mesh Desk (desktop)") }, { menu = false; deskContext.startActivity(android.content.Intent(deskContext, com.jsons.roadmesh.telemetry.MeshDeskActivity::class.java)) })
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
            HowTo(state)
            if (state.ready && state.liveRole == Role.OBSERVER) TestWarnings(app, state)
            Heading("Observe the road", "This phone becomes the dashcam and leads the chain. YOLO finds potholes, accidents, people on the road and traffic violations; hazards go to every phone behind, violations go to the report log.")
            Surface(shape = RoundedCornerShape(24.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoadScene(Modifier.fillMaxWidth().height(170.dp), highlight = state.liveRole.ordinal)
                    Text("Simulate dashcam", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    com.jsons.roadmesh.perception.DashcamObserver.CLIPS.forEach { (asset, label) ->
                        val on = o.source == label
                        Surface(onClick = { app.observer.use(null, label, asset) }, shape = RoundedCornerShape(12.dp), color = if (on) Tokens.Raised else Tokens.Ink,
                            border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Tokens.Yellow else Tokens.Line), modifier = Modifier.fillMaxWidth()) {
                            Text(label, fontSize = 14.sp, color = if (on) Tokens.Yellow else Tokens.Secondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
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
                ObservePanel(app, o, copilot, questions, actions)
            }
        } else Column(Modifier.fillMaxSize()) {
            video(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ObservePanel(app, o, copilot, questions, actions)
            }
        }
    }
}

@Composable internal fun ObservePanel(app: RoadMeshApplication, o: ObserverState, copilot: CopilotState, questions: List<Question>, actions: Actions) {
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
    BirdsEye3D(o.overlays, Modifier.background(Tokens.Panel, RoundedCornerShape(16.dp)))
    Text("Top view from size-based distance estimates. Yellow: in your lane. Red: the hazard just sent.", color = Tokens.Faint, fontSize = 12.sp)
    AskPanel(questions, copilot, app::ask)
    Text("Sent to the mesh", style = MaterialTheme.typography.titleMedium)
    if (o.raised.isEmpty()) Text("Nothing yet. Potholes, accidents, people in the lane, wrong-side vehicles and hard braking go to the phones behind; triple riding, riders without helmets and red-light jumping are logged as reports.",
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
            Text(r.call.kind.title() + if (r.call.kind.violation) " · logged" else "", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                Text(if (!copilot.ready) "Co-pilot off" else if (copilot.tuned) "RESILANE Gemma 2B · on device" else "Gemma 2B · on device", fontSize = 12.sp, color = Tokens.Faint)
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
    Text(listOfNotNull(o.detector, o.roadModel, o.inferMs?.let { "$it ms" }, o.fps?.let { "%.1f fps".format(it) }).joinToString(" · "),
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

/* ---------- Mesh ---------- */

@Composable internal fun MeshTab(state: SessionState, copilot: CopilotState, now: Long, actions: Actions) {
    val me = state.me()
    TabColumn {
        when {
            state.mode == Mode.ROAD -> Heading("Your local road mesh", "Phones keep their own view of nearby traffic. Camera observers and relays can overlap; joining or leaving does not appoint a road controller. Open Swarm to inspect directions and shared evidence.")
            !state.running -> Heading("Mesh is off", "Tap Start mesh. The first phone to start is A, the observer; every phone started after it relays in that order, and the newest one receives.")
            state.liveRole == Role.OBSERVER -> Heading("You are A, the observer", "You started the mesh first. What your camera finds, or a test warning from the Observe tab, goes to ${state.next()} and on down the chain.")
            state.liveRole == Role.RELAY -> Heading("$me relays to ${state.next()}", "Warnings from ${rankLetter(state.rank - 1)} arrive here. This phone works out what they mean from where it is, then passes them to ${state.next()}. Nothing to tap: it is automatic.")
            else -> Heading("$me receives", "You started last, so warnings end here: they appear on the Received tab and are spoken. When another phone starts after you, you begin relaying to it.")
        }
        HowTo(state)
        val (nodes, hazards) = remember(state.chain, state.known) { layoutMesh(state.chain, state.known) }
        val active = buildSet {
            for (e in state.events) {
                if (now - e.atMs > 4_000 && (e.relayedAtMs == null || now - e.relayedAtMs > 4_000) && (e.sentAtMs == null || now - e.sentAtMs > 4_000)) continue
                if (!e.originated && state.rank != NO_RANK) add(state.rank)
                if ((e.relayedAtMs != null || e.sentAtMs != null) && state.rank != NO_RANK) add(state.rank + 1)
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MESH MAP · 3D", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                if (nodes.isEmpty()) Text(if (state.chain.isEmpty()) "No phones heard yet." else "${state.chain.size} phones heard; waiting for usable positions.", color = Tokens.Faint, fontSize = 14.sp, modifier = Modifier.padding(6.dp))
                else MeshMap3D(nodes, hazards, active, unlocatedCount = state.chain.size - nodes.size)
                Text("Drag to rotate, pinch to zoom. Blocks show reported positions; arcs show inferred ordering, not confirmed radio links. Activity is local receipt or transmission. A missing position stays unlocated.",
                    color = Tokens.Faint, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
        Text(if (state.mode == Mode.STAGE) "Stage chain" else "Nearby participants", style = MaterialTheme.typography.titleMedium)
        if (state.chain.isEmpty()) Text("Waiting for presence beacons…", color = Tokens.Faint, fontSize = 14.sp)
        state.chain.forEach { m -> ChainRow(m, now) }
        Text("Each phone sends a presence beacon every second. This is one phone's local view; other phones may hear different peers. A phone silent for 4 s drops out. Road ordering uses this phone's heading, not a fleet-wide agreement.",
            color = Tokens.Faint, fontSize = 12.sp, lineHeight = 17.sp)
        if (state.events.isNotEmpty()) Text("Through this phone", style = MaterialTheme.typography.titleMedium)
        state.events.forEach { e -> EventStepper(e, me, state.next(), copilot.lines["event:${e.id}"], now) }
        Text(state.status + if (state.recoveries > 0) " · radio recovered ${state.recoveries}×" else "", color = if (state.ready) Tokens.Faint else Tokens.Red, fontSize = 13.sp)
        GhostButton("Logs", actions.logs, Modifier.fillMaxWidth())
    }
}

@Composable private fun ChainRow(m: Member, now: Long) {
    Row(Modifier.fillMaxWidth().background(if (m.self) Tokens.Raised else Tokens.Panel, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoleBadge(rankLetter(m.rank).first(), roleColor(m.role.ordinal), 34.dp)
        Column(Modifier.weight(1f)) {
            Text(m.role.title + if (m.self) " · this phone" else "", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (m.self) Tokens.Yellow else Tokens.Paper)
            Text("${m.vehicle.title} · node ${m.node.toString().takeLast(4).uppercase()}" + if (m.observer) " · camera on" else "", fontSize = 13.sp, color = Tokens.Faint)
        }
        Text(if (m.self) "${m.known} known" else "${((now - m.lastHeardMs) / 1000).coerceAtLeast(0)} s", style = Tabular, fontSize = 13.sp, color = Tokens.Faint)
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
                Step(true, "Received from ${if (e.viaRank != NO_RANK) rankLetter(e.viaRank) else via}" + if (e.hops > 0) " · ${e.hops + 1} hops from the camera" else "", "The camera's inference: ${detailText(e.detail)}")
                Step(true, "Inference on $me", e.inference?.let { "Hazard about ${it.hazardAheadM} m ahead of this phone" + (it.etaS?.let { s -> ", ${s.toInt()} s away" } ?: "") + ". ${it.action.replaceFirstChar { c -> c.uppercase() }}." } ?: "Position unknown",
                    copilot = line)
                if (e.canRelay) Step(e.relayedAtMs != null, if (e.relayedAtMs != null) "Relayed to $next" else "Relaying to $next…", "Same event, same origin. The relay adds no trust of its own.", last = true)
                else Step(null, "Not relayed", "Nobody is behind this phone in the chain, so this is the last hop.", last = true)
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

/* ---------- Received ---------- */

@Composable internal fun ReceivedTab(app: RoadMeshApplication, state: SessionState, copilot: CopilotState, questions: List<Question>, now: Long, actions: Actions) {
    val received = state.events.filter { !it.originated && it.kind?.use != HazardUse.REPORT }
    val latest = received.firstOrNull()
    TabColumn {
        if (latest != null && now - latest.atMs < 20_000) {
            val via = if (latest.viaRank != NO_RANK) rankLetter(latest.viaRank) else latest.via.letter()
            Surface(shape = RoundedCornerShape(24.dp), color = Tokens.Panel, border = BorderStroke(2.dp, Tokens.Yellow), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WARNING FROM $via · ${latest.hops + 1} HOP${if (latest.hops > 0) "S" else ""}", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
                    Text(latest.kind?.title() ?: "Warning", fontFamily = Saira, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 44.sp, color = Tokens.Yellow)
                    latest.inference?.let { Text("About ${it.hazardAheadM} m ahead of you" + (it.etaS?.let { s -> " · ${s.toInt()} s away" } ?: ""), style = Tabular, fontSize = 18.sp, color = Tokens.Paper) }
                    Text("Reported by a nearby phone (${detailText(latest.detail)})" + if (latest.hops > 0) ", relayed by $via." else ".", fontSize = 14.sp, color = Tokens.Muted, lineHeight = 19.sp)
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
        } else Heading("The road ahead", "Ask the phones in front of and behind you what they see. Their answers come back over Bluetooth with no internet and are combined into one picture, with a forecast made on this phone.")
        HowTo(state)
        RoadAhead(app, state, questions.firstOrNull(), copilot, now)
        ManualNarrowingReport(app, state)
        AskPanel(questions, copilot, app::ask)
        if (state.known.isNotEmpty()) {
            Text("On this phone's road map", style = MaterialTheme.typography.titleMedium)
            state.known.sortedByDescending { it.lastSeenMs }.take(8).forEach { h ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.kind.title(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(if (h.own) "seen by this phone" else "${h.reporters.size} phone${if (h.reporters.size == 1) "" else "s"} reported it", fontSize = 13.sp, color = Tokens.Faint)
                    }
                    Text("${h.ageS(now)} s ago", style = Tabular, fontSize = 14.sp, color = Tokens.Faint)
                }
                HorizontalDivider(color = Tokens.Line)
            }
        }
        Text(state.status, color = if (state.ready) Tokens.Faint else Tokens.Red, fontSize = 13.sp)
    }
}

/** The combined picture from the last road request: hazards ahead and behind, traffic, and this phone's forecast. */
@Composable private fun RoadAhead(app: RoadMeshApplication, state: SessionState, q: Question?, copilot: CopilotState, now: Long) {
    val picture = state.picture
    Surface(shape = RoundedCornerShape(24.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ROAD CONDITION", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow, modifier = Modifier.weight(1f))
                state.pictureAtMs?.let { Text("${(now - it) / 1000} s ago", style = Tabular, fontSize = 12.sp, color = Tokens.Faint) }
            }
            PrimaryButton(if (state.asking) "Asking the chain…" else "Ask the road ahead", { app.askRoad() }, Modifier.fillMaxWidth(), enabled = state.running && !state.asking)
            if (picture == null) Text(if (state.running) "One request goes forward and back along the chain; every phone that hears it answers with what it holds." else "Start the mesh to ask the road.",
                color = Tokens.Faint, fontSize = 14.sp, lineHeight = 20.sp)
            else {
                Text("${picture.peers.size} phone${if (picture.peers.size == 1) "" else "s"} answered" +
                    picture.peers.takeIf { it.isNotEmpty() }?.let { ps -> ": " + ps.joinToString { "${rankLetter(it.rank)} ${aheadWords(it.aheadM)}" + (it.speedMps?.let { s -> " at ${RoadPicture.kmh(s)} km/h" } ?: "") } }.orEmpty(),
                    color = Tokens.Muted, fontSize = 14.sp, lineHeight = 20.sp)
                picture.forecast.recommendedKmh?.let { k ->
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
                        Metric("$k km/h", "ease off to", Tokens.Yellow)
                        picture.forecast.meetS?.let { Metric("${it.toInt()} s", "until the slowdown") }
                    }
                }
                if (picture.ahead.isEmpty()) Text("Nothing reported ahead.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                picture.ahead.take(5).forEach { HazardLine(it.kind.title(), "${it.aheadM} m ahead", it.reporters, it.ageS) }
                picture.behind.take(3).forEach { HazardLine(it.kind.title(), "${-it.aheadM} m behind", it.reporters, it.ageS, Tokens.Faint) }
                picture.forecast.advice.forEach { Text(it, fontSize = 15.sp, lineHeight = 21.sp) }
                q?.let { copilot.lines[it.tag] }?.takeIf { it.text.isNotEmpty() }?.let { line ->
                    HorizontalDivider(color = Tokens.Line)
                    Text("CO-PILOT", fontFamily = Michroma, fontSize = 10.sp, letterSpacing = 1.sp, color = Tokens.Faint)
                    Text(line.text + if (line.done) "" else " ▍", fontSize = 17.sp, lineHeight = 24.sp)
                }
            }
        }
    }
}

@Composable private fun HazardLine(title: String, where: String, reporters: Int, ageS: Int, color: Color = Tokens.Paper) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
            Text("$reporters phone${if (reporters == 1) "" else "s"} · ${ageS} s old", fontSize = 12.sp, color = Tokens.Faint)
        }
        Text(where, style = Tabular, fontSize = 16.sp, color = color)
    }
}

/* ---------- Guidance ---------- */

/** What this phone is and what to do next, in one card. Shown on every tab while the chain is short or idle. */
@Composable private fun HowTo(state: SessionState) {
    val me = state.me()
    val (title, body) = when {
        !state.running -> "Start the mesh" to "Tap Start mesh at the top. The first phone to start becomes A, the observer. Start the other phones after it, one at a time: they become B, C, D… in that order, and the newest one receives."
        state.chain.size < 2 -> "Waiting for other phones" to "You are ${me}. Start the mesh on another phone nearby (Bluetooth on). It will appear here within two seconds and the chain forms by itself."
        state.liveRole == Role.OBSERVER -> "You are A · observer" to "Play the dashcam simulation below, or tap a test warning. It goes to B, which relays it down the chain; open Received on the last phone (${rankLetter(state.chain.size - 1)}) to see it arrive."
        state.liveRole == Role.RELAY -> "You are $me · relay" to "Keep this phone on. Warnings from ${rankLetter(state.rank - 1)} pass through here to ${state.next()} automatically; each one shows below with its hop."
        else -> "You are $me · receiver" to "Warnings from A arrive here after ${state.rank - 1} relay${if (state.rank - 1 == 1) "" else "s"} and are spoken. Tap Ask the road ahead to hear what every phone in front of you knows."
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Tokens.Raised, border = BorderStroke(1.dp, Tokens.Yellow.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            RoleBadge(if (state.running && state.rank != NO_RANK) me.first() else '?', roleColor(state.liveRole.ordinal), 40.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                // A start that failed says why here instead of silently going back to "Start mesh".
                if (!state.ready && state.status !in setOf("Choose a role, then start", "Stopped", "Starting BLE…", "BLE ready"))
                    Text(state.status, color = Tokens.Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                Text(body, color = Tokens.Secondary, fontSize = 14.sp, lineHeight = 20.sp)
                if (state.chain.size > 1) Text(state.chain.joinToString("  →  ") { rankLetter(it.rank) + if (it.self) " (you)" else "" }, style = Tabular, fontSize = 13.sp, color = Tokens.Yellow)
            }
        }
    }
}

@Composable private fun ManualNarrowingReport(app: RoadMeshApplication, state: SessionState) {
    var result by remember { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(20.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TEMPORARY ROAD CHANGE", fontFamily = Michroma, fontSize = 11.sp, color = Tokens.Yellow)
            Text("Stopped bus, parked truck or roadworks reducing the usable road? Report the narrowing at your current position.",
                color = Tokens.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            PrimaryButton(if (state.mode == Mode.STAGE) "Stage a road narrowing" else "Report road narrowed here",
                { result = app.reportNarrowing() }, Modifier.fillMaxWidth(), enabled = state.ready)
            Text(if (state.mode == Mode.STAGE) "DEMO position · test report over the phone mesh" else "For a stopped vehicle or passenger. This is a manual observation, not a camera detection.",
                color = Tokens.Faint, fontSize = 12.sp)
            if (result.isNotEmpty()) Text(result, color = Tokens.Muted, fontSize = 13.sp)
        }
    }
}

/** Test warnings for A, so the relay can be shown without the dashcam video. */
@Composable private fun TestWarnings(app: RoadMeshApplication, state: SessionState) {
    var last by remember { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(20.dp), color = Tokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SEND A TEST WARNING", fontFamily = Michroma, fontSize = 11.sp, letterSpacing = 1.sp, color = Tokens.Yellow)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HazardKind.POTHOLE, HazardKind.ACCIDENT, HazardKind.BRAKING).forEach { k ->
                    GhostButton(when (k) { HazardKind.ACCIDENT -> "Crash"; HazardKind.BRAKING -> "Braking"; else -> k.title() }, { last = if (app.sendTest(k)) "${k.title()} queued. Remote delivery is not confirmed." else "Not queued: the mesh is not ready." }, Modifier.weight(1f))
                }
            }
            if (last.isNotEmpty()) Text(last, color = Tokens.Muted, fontSize = 13.sp)
        }
    }
}

/* ---------- Reports ---------- */

@Composable internal fun ReportsTab(app: RoadMeshApplication, reports: List<RoadReport>) {
    val coroutine = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    TabColumn {
        Heading("Road reports", "Potholes, accidents and traffic violations, each with a snapshot, time and place. Export them for the municipality or the traffic police.")
        PrimaryButton("Report an accident here", { message = if (app.crash.report("accident reported by the driver")) "Accident sent to the mesh and logged." else "Not sent: the mesh is not running, or an accident was reported in the last minute." },
            Modifier.fillMaxWidth())
        GhostButton("Export for authorities", { coroutine.launch { message = runCatching { app.reports.export() }.getOrElse { "Export failed: ${it.message}" } } }, Modifier.fillMaxWidth())
        if (message.isNotEmpty()) Text(message, color = Tokens.Muted, fontSize = 13.sp)
        val counts = reports.groupingBy { it.kind }.eachCount()
        if (counts.isNotEmpty()) Text(counts.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.value} ${it.key.title().lowercase()}" }, color = Tokens.Secondary, fontSize = 14.sp)
        if (reports.isEmpty()) Text("No reports yet. Play the dashcam simulation on the Observe tab, or tap Report accident.", color = Tokens.Faint, fontSize = 14.sp, lineHeight = 20.sp)
        reports.take(60).forEach { r -> ReportRow(app, r) }
        if (reports.isNotEmpty()) GhostButton("Clear reports", { app.reports.clear() }, Modifier.fillMaxWidth())
    }
}

@Composable private fun ReportRow(app: RoadMeshApplication, r: RoadReport) {
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, r.id) {
        value = withContext(Dispatchers.IO) { app.reports.snapshot(r)?.let { f -> BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() } }
    }
    Row(Modifier.fillMaxWidth().background(Tokens.Panel, RoundedCornerShape(16.dp)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp, 54.dp).background(Tokens.Raised, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, contentDescription = "Snapshot of the ${r.kind.title().lowercase()}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Text(if (r.fromMesh) "mesh" else "no image", fontSize = 11.sp, color = Tokens.Faint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.kind.title(), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (r.kind.violation) Tokens.Purple else Tokens.Yellow)
            Text(r.reason, fontSize = 13.sp, color = Tokens.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(r.utcMs)) +
                (r.lat?.let { " · %.5f, %.5f".format(it, r.lon) } ?: "") + (if (r.synthetic) " (stage)" else "") +
                (if (r.fromMesh) " · from mesh" else if (r.sentToMesh) " · shared" else ""), style = Tabular, fontSize = 11.sp, color = Tokens.Faint)
        }
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

