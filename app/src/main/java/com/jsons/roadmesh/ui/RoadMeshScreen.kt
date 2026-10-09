package com.jsons.roadmesh.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jsons.roadmesh.*
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.service.MeshSession
import com.jsons.roadmesh.service.SessionState
import kotlinx.coroutines.launch

private val Ink = Color(0xff080e16)
private val Panel = Color(0xff111d2b)
private val Muted = Color(0xff94a8bc)
private val Cyan = Color(0xff51dfdc)
private val Amber = Color(0xffffc15b)

@Composable fun RoadMeshScreen(mesh: MeshSession, start: () -> Unit, stop: () -> Unit, scene: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Ink, surface = Panel, onSurfaceVariant = Muted)) {
        Dashboard(mesh, start, stop, scene)
    }
}
@Composable private fun Dashboard(mesh: MeshSession, start: () -> Unit, stop: () -> Unit, scene: () -> Unit) {
    val state by mesh.state.collectAsState()
    var setup by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val coroutine = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = Ink) {
        if (setup) Column(Modifier.fillMaxSize().padding(12.dp)) {
            TextButton(onClick = { setup = false }) { Text("← Back to RoadMesh") }
            Box(Modifier.weight(1f)) { SetupCheckScreen(canProbeRadio = !state.running) }
        } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text("ROAD", fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("MESH", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Cyan)
                }
                Role.entries.forEach { role ->
                    FilterChip(selected = state.role == role, enabled = !state.running, onClick = { mesh.select(role) },
                        label = { Text("${('A'.code + role.ordinal).toChar()}  ${role.title}") })
                }
                Spacer(Modifier.weight(1f))
                Text(if (state.ready) "● Ready" else if (state.running) "● Starting" else "○ Stopped", color = if (state.ready) Cyan else Muted, fontSize = 12.sp)
                if (state.running) OutlinedButton(onClick = stop) { Text("Stop") } else Button(onClick = start) { Text("Start mesh") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.weight(1.8f).fillMaxHeight(), color = if (state.alert != null && state.role == Role.OBSERVER) Color(0xff382813) else Panel,
                    shape = MaterialTheme.shapes.large) {
                    BoxWithConstraints {
                        val compact = maxHeight < 240.dp
                        Column(Modifier.padding(if (compact) 16.dp else 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                            RoleContent(state, scene, mesh::clearAlert, compact)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (state.mode == Mode.STAGE) "STAGE / SYNTHETIC POSITIONS" else "ROAD / LIVE GNSS", color = Muted, fontSize = 10.sp)
                    Text(if (state.mode == Mode.STAGE) "A → B → C" else "LOCAL NEIGHBORHOOD", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cyan)
                    Text(if (state.mode == Mode.STAGE) "Stage filter enforces the chain despite radio overlap.\n2 radio links · 1 relay" else "Bounded relaying across nearby devices. Live inputs can be added through source adapters.", color = Muted, fontSize = 13.sp)
                    HorizontalDivider(color = Muted.copy(alpha = .2f))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Metric("RECEIVED", state.counters["received"] ?: 0)
                        Metric("RELAY BURSTS", state.counters["relay_burst"] ?: 0)
                    }
                    Text(state.status, color = if (state.ready) Cyan else Amber, fontSize = 12.sp)
                    state.lastReceived?.let { Text("Last RX: ${it.radioLinks} radio link(s), ${it.packet.hop} relay(s)\nOrigin ${it.id.origin} · via ${it.forwarder}", color = Muted, fontSize = 12.sp) }
                    Text("Broadcast completion is local. Delivery is visible on the receiving phone.", fontSize = 11.sp, color = Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.ifEmpty { if (state.mode == Mode.STAGE) "Virtual brake / Real BLE / Unsigned caution" else "Experimental base / GNSS required / Unsigned caution" },
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = if (message.isEmpty()) Muted else Cyan)
                TextButton(onClick = { setup = true }) { Text("Device check") }
                TextButton(onClick = { settings = true }) { Text("Settings") }
                TextButton(onClick = { diagnostics = true }) { Text("Logs") }
                TextButton(onClick = {
                    coroutine.launch { message = runCatching { mesh.export() }.getOrElse { "Export failed: ${it.message}" } }
                }) { Text("Export JSON") }
            }
        }
    }
    if (settings) SettingsDialog(mesh, { settings = false })
    if (diagnostics) Dialog(onDismissRequest = { diagnostics = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.9f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Local diagnostics", fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
                    TextButton(onClick = { diagnostics = false }) { Text("Close") }
                }
                Text("Tables: ${state.bounds.dedup} events · ${state.bounds.neighbors} neighbors · ${state.bounds.pending} pending", color = Cyan)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(state.counters.entries.joinToString("  ") { "${it.key}=${it.value}" }, fontSize = 11.sp, color = Muted)
                    state.logs.takeLast(80).asReversed().forEach {
                        Text("${it.monotonicMs}  ${it.code}  ${it.detail}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun RoleContent(state: SessionState, scene: () -> Unit, clear: () -> Unit, compact: Boolean) {
    val role = state.role
    val titleSize = if (compact) 28.sp else 34.sp
    val lineSize = if (compact) 32.sp else 39.sp
    Text("${('A'.code + role.ordinal).toChar()} / ${role.title.uppercase()}", color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
    when (role) {
        Role.DETECTOR -> {
            Text(if (state.mode == Mode.STAGE) "See the brake.\nStart the warning." else "Live input\nstarts here.", fontSize = titleSize, fontWeight = FontWeight.Bold, lineHeight = lineSize)
            Spacer(Modifier.height(8.dp))
            Text(if (state.mode == Mode.STAGE) "A virtual car brakes in the camera scene. Its event enters the mesh directly." else
                "Road mode uses live GNSS. Camera and IMU hazard producers are future source adapters.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            Button(onClick = scene, enabled = state.ready && state.mode == Mode.STAGE) { Text("Open car scene") }
            state.lastTx?.let { Text("Local brake burst completed · C delivery unknown", fontSize = 12.sp, color = Cyan) }
        }
        Role.RELAY -> {
            Text(when { state.lastTx != null -> "Warning forwarded."; state.lastReceived != null -> "Warning received."; else -> "Keep the warning\nmoving." },
                fontSize = titleSize, fontWeight = FontWeight.Bold, lineHeight = lineSize)
            Spacer(Modifier.height(12.dp))
            Text(if (state.lastReceived == null) "Listening for A. This phone relays the same event over BLE." else
                "One origin. Same event identity. ${if (state.lastTx != null) "Local relay burst complete." else "Waiting for local relay burst."}", color = Muted)
            Text("Relay copies do not add trust or create a new warning.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Role.OBSERVER -> {
            val alert = state.alert
            Text(if (alert != null) "CAUTION\nBraking ahead" else "A warning before\nyou can see it.", fontSize = if (compact) 30.sp else 38.sp,
                fontWeight = FontWeight.Bold, lineHeight = if (compact) 34.sp else 43.sp, color = if (alert != null) Amber else Color.White)
            Spacer(Modifier.height(12.dp))
            Text(if (alert == null) "Listening for B. A received, relevant event triggers offline speech and haptics once." else
                "${alert.frame.radioLinks} radio links · ${alert.frame.packet.hop} relay · ${alert.distanceM} m ${if (state.mode == Mode.STAGE) "on synthetic track" else "ahead"}", color = Muted)
            if (alert != null) TextButton(onClick = clear) { Text("Dismiss visual alert") }
        }
    }
}
@Composable private fun Metric(label: String, value: Int) = Column {
    Text(value.toString(), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    Text(label, fontSize = 10.sp, color = Muted, letterSpacing = 1.sp)
}

@Composable private fun SettingsDialog(mesh: MeshSession, close: () -> Unit) {
    val state by mesh.state.collectAsState()
    var json by remember { mutableStateOf(profileJson(mesh.preferences.profile).toString(2)) }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.85f).fillMaxHeight(.95f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Expandable base settings", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f)); TextButton(onClick = close) { Text("Close") }
                }
                Text("Stop before editing. Invalid input keeps the last saved profile.", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mode.entries.forEach { mode -> TextButton(enabled = !state.running, onClick = {
                        mesh.mode(mode); json = profileJson(mesh.preferences.profile).toString(2); error = ""
                    }) { Text(mode.name) } }
                    TextButton(enabled = !state.running, onClick = { json = profileJson(MeshProfile()).toString(2) }) { Text("Stage defaults") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = !state.running, onClick = {
                        runCatching { mesh.saveProfile(json) }.onSuccess { error = "Saved" }.onFailure { error = it.message.orEmpty() }
                    }) { Text("Validate & save") }
                }
                Text(error, color = Amber, fontSize = 12.sp)
                OutlinedTextField(value = json, onValueChange = { json = it }, enabled = !state.running,
                    modifier = Modifier.fillMaxWidth().weight(1f), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            }
        }
    }
}
