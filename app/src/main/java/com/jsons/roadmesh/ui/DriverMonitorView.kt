package com.jsons.roadmesh.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jsons.roadmesh.RoadMeshApplication
import com.jsons.roadmesh.core.perception.Alertness
import com.jsons.roadmesh.perception.DriverState
import com.jsons.roadmesh.perception.SosSent
import kotlin.math.roundToInt

private fun Alertness.title() = when (this) {
    Alertness.NO_FACE -> "No face in view"
    Alertness.CALIBRATING -> "Learning your normal pose"
    Alertness.AWAKE -> "Awake"
    Alertness.DROWSY -> "Drowsy"
    Alertness.UNRESPONSIVE -> "Not responding"
}
private fun Alertness.color() = when (this) {
    Alertness.NO_FACE, Alertness.CALIBRATING -> Tokens.Muted
    Alertness.AWAKE -> Tokens.Green
    Alertness.DROWSY -> Tokens.Yellow
    Alertness.UNRESPONSIVE -> Tokens.Red
}

/** The Observe tab while the driver monitor runs: front camera, how awake the driver looks, and every SOS sent. */
@Composable internal fun DriverMonitorView(app: RoadMeshApplication) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val d by app.driver.flow.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val camera = @Composable { m: Modifier ->
            if (granted) DriverCamera(app, d, m) else Box(m.background(Tokens.Panel), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("The driver monitor needs the front camera. Frames stay on this phone; nothing is recorded.", color = Tokens.Muted, fontSize = 15.sp, lineHeight = 21.sp)
                    PrimaryButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                }
            }
        }
        val panel = @Composable { DriverPanel(app, d) }
        if (landscape) Row(Modifier.fillMaxSize()) {
            camera(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { panel() }
        } else Column(Modifier.fillMaxSize()) {
            camera(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { panel() }
        }
    }
    d.alarm?.let { WakeUp(d, app.driver::acknowledge) }
}

@Composable private fun DriverCamera(app: RoadMeshApplication, d: DriverState, modifier: Modifier) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(Unit) { onDispose { app.driver.unbind() } }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                app.driver.bind(this, owner)
            }
        }, modifier = Modifier.fillMaxSize())
        val tint = d.reading.alertness.color()
        Canvas(Modifier.fillMaxSize()) {
            val r = d.face ?: return@Canvas
            // Corner brackets around the face, in the preview's own pixels.
            val l = r.left.toFloat(); val t = r.top.toFloat(); val rr = r.right.toFloat(); val b = r.bottom.toFloat()
            val arm = (rr - l) * 0.22f; val w = 4.dp.toPx()
            for ((x, dx) in listOf(l to arm, rr to -arm)) for ((y, dy) in listOf(t to arm, b to -arm)) {
                drawLine(tint, Offset(x, y), Offset(x + dx, y), w); drawLine(tint, Offset(x, y), Offset(x, y + dy), w)
            }
            drawRect(tint.copy(alpha = 0.25f), Offset(l, t), androidx.compose.ui.geometry.Size(rr - l, b - t), style = Stroke(1.dp.toPx()))
        }
        Text(d.reading.alertness.title().uppercase(), color = if (d.reading.alertness >= Alertness.AWAKE) Tokens.Ink else Tokens.Paper,
            fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 0.6.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                .background(if (d.reading.alertness >= Alertness.AWAKE) tint else Tokens.Scrim, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite })
        Text(listOfNotNull("Front camera · ML Kit face, on device", d.fps?.let { "%.0f fps".format(it) }).joinToString(" · "),
            style = Tabular, fontSize = 12.sp, color = Tokens.Secondary,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Tokens.Scrim, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable internal fun DriverPanel(app: RoadMeshApplication, d: DriverState) {
    d.error?.let { Text(it, color = Tokens.Red, fontSize = 14.sp) }
    val r = d.reading
    Text(r.alertness.title(), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 33.sp), color = r.alertness.color())
    Text(r.reason ?: when (r.alertness) {
        Alertness.NO_FACE -> "Mount the phone so the front camera sees your face, about an arm’s length away."
        Alertness.CALIBRATING -> "Look at the road as you normally would for two seconds."
        else -> "Watching your eyes and head. Eyes shut over a second, or a dropping head, sets off the alarm and an SOS."
    }, color = Tokens.Muted, fontSize = 16.sp, lineHeight = 23.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
        DriverMetric("%.1f s".format(r.eyesClosedS), "eyes shut", if (r.eyesClosedS >= 1.0) Tokens.Yellow else Tokens.Paper)
        DriverMetric(r.perclos?.let { "${(it * 100).roundToInt()}%" } ?: "…", "shut, last 30 s")
        val open = listOfNotNull(d.leftEyeOpen, d.rightEyeOpen).maxOrNull()
        DriverMetric(open?.let { "${(it * 100).roundToInt()}%" } ?: "…", "eyes open")
    }
    Surface(shape = RoundedCornerShape(16.dp), color = Tokens.Panel, border = BorderStroke(1.dp, Tokens.Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What sets it off", color = Tokens.Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.6.sp)
            Text("Drowsy: eyes shut 1.2 s, or shut for 30% of the last 30 s.\nPassing out: eyes shut 3 s, the head dropping with eyes shut, or slumping out of view.\n" +
                "Either one sounds an alarm on this phone and sends a Driver SOS over the mesh to every phone around.", color = Tokens.Secondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
    Text("SOS sent", style = MaterialTheme.typography.titleMedium)
    if (d.sos.isEmpty()) Text("None yet. To try it, close your eyes for two seconds.", color = Tokens.Faint, fontSize = 14.sp)
    d.sos.forEach { SosRow(it) }
    GhostButton("Stop driver monitor", { app.observer.simulate(false) }, Modifier.fillMaxWidth())
}

@Composable private fun DriverMetric(value: String, label: String, color: Color = Tokens.Paper) = Column {
    Text(value, style = Tabular, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = color, lineHeight = 32.sp)
    Text(label, color = Tokens.Faint, fontSize = 13.sp)
}

@Composable private fun SosRow(s: SosSent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (s.alarm.level == Alertness.UNRESPONSIVE) "Driver not responding" else "Driver drowsy", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val ago = (SystemClock.elapsedRealtime() - s.atMs) / 1000
            Text("${s.alarm.reason} · ${if (ago < 60) "${ago} s" else "${ago / 60} min"} ago", color = Tokens.Faint, fontSize = 13.sp)
        }
        Text(if (s.sent) "Sent" else "Not sent", color = if (s.sent) Tokens.Green else Tokens.Red, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
    HorizontalDivider(color = Tokens.Line)
}

/** Full screen, impossible to miss: what happened, whether the SOS went out, and one big button to stop the alarm. */
@Composable private fun WakeUp(d: DriverState, acknowledge: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        WakeUpContent(d, acknowledge)
    }
}

@Composable internal fun WakeUpContent(d: DriverState, acknowledge: () -> Unit) {
    val alarm = d.alarm ?: return
    val severe = alarm.level == Alertness.UNRESPONSIVE
    val base = if (severe) Tokens.Red else Tokens.Yellow
    val pulse = if (reducedMotion()) 1f else rememberInfiniteTransition(label = "wake").animateFloat(1f, 0.72f,
        infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "pulse").value
    val sos = d.sos.firstOrNull()?.takeIf { it.alarm == alarm }
    Box(Modifier.fillMaxSize().background(base.copy(alpha = pulse)).semantics { liveRegion = LiveRegionMode.Assertive }) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Text("WAKE UP", color = Tokens.Ink, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 64.sp,
                style = MaterialTheme.typography.displayLarge)
            Text(if (severe) "You stopped responding: ${alarm.reason}." else "You are falling asleep: ${alarm.reason}. Pull over when it is safe.",
                color = Tokens.Ink, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
            Text(when {
                sos == null -> "SOS already sent in the last minute."
                sos.sent -> "SOS sent to the phones around you over the mesh."
                else -> "SOS not sent: the mesh is not running. Start the mesh so nearby phones are warned."
            }, color = Tokens.Ink, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.background(Tokens.Paper.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp))
            Spacer(Modifier.height(12.dp))
            Button(onClick = acknowledge, shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Tokens.Ink, contentColor = Tokens.Paper),
                modifier = Modifier.fillMaxWidth().height(88.dp)) {
                Text("I’m awake", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
