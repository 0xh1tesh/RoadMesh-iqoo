package com.jsons.roadmesh.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsons.roadmesh.core.mesh.Role
import com.jsons.roadmesh.core.mesh.VehicleType
import kotlin.math.pow

/** Respect the system "remove animations" setting: draw one still frame instead. */
@Composable fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/**
 * First-run onboarding: an animated road where a warning travels from the front phone (A) to B and on to C, then the
 * vehicle type and this phone's place in the convoy. [finish] saves both and starts the mesh.
 */
@Composable fun Onboarding(initialVehicle: VehicleType, initialRole: Role, finish: (VehicleType, Role) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var vehicle by rememberSaveable { mutableStateOf(initialVehicle) }
    var role by rememberSaveable { mutableStateOf(initialRole) }
    Column(Modifier.fillMaxSize().background(Tokens.Ink)) {
        SignalEdge(Tokens.Yellow)
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Wordmark(30.dp, 14)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "Step ${step + 1} of 3" }) {
                    repeat(3) { i -> Box(Modifier.size(if (i == step) 22.dp else 8.dp, 8.dp).background(if (i <= step) Tokens.Yellow else Tokens.Raised, RoundedCornerShape(4.dp))) }
                }
            }
            AnimatedContent(step, Modifier.weight(1f).widthIn(max = 640.dp), transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(320)) { it / 6 * dir } + fadeIn(tween(320))) togetherWith (slideOutHorizontally(tween(220)) { -it / 6 * dir } + fadeOut(tween(220)))
            }, label = "step") { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (s) {
                        0 -> Intro()
                        1 -> VehicleStep(vehicle) { vehicle = it }
                        else -> ConvoyStep(role) { role = it }
                    }
                }
            }
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) GhostButton("Back", { step-- }, Modifier.weight(1f), height = 56.dp)
                PrimaryButton(when (step) { 0 -> "Get started"; 1 -> "Continue"; else -> "Start the mesh" },
                    { if (step < 2) step++ else finish(vehicle, Role.RELAY) }, Modifier.weight(2f))
            }
        }
    }
}

@Composable private fun Intro() {
    RoadScene(Modifier.fillMaxWidth().height(300.dp), highlight = null)
    Text("Know the road before you reach it", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 36.sp))
    Text("Phones on the dash spot potholes, accidents and traffic violations with an on-device detector, pass warnings back along the road over Bluetooth, and help ease off early so slowdowns don't become jams. No internet, no new hardware.",
        color = Tokens.Muted, fontSize = 17.sp, lineHeight = 24.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Observe", "Relay", "Receive").forEachIndexed { i, label ->
            Row(Modifier.background(Tokens.Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoleBadge('A' + i, roleColor(i), 22.dp)
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

fun roleColor(i: Int) = when (i) { 0 -> Color(0xFF1F4FB8); 1 -> Color(0xFF6B2FB3); else -> Color(0xFF1C6B45) }

@Composable private fun VehicleStep(selected: VehicleType, pick: (VehicleType) -> Unit) {
    Text("What do you drive?", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp))
    Text("The co-pilot tailors its advice to your vehicle, and the phones behind you hear what kind of vehicle saw the hazard.",
        color = Tokens.Muted, fontSize = 16.sp, lineHeight = 23.sp)
    val types = listOf(VehicleType.CAR, VehicleType.TWO_WHEELER, VehicleType.AUTO, VehicleType.BUS_TRUCK)
    val motion = !reducedMotion()
    types.chunked(2).forEachIndexed { row, pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEachIndexed { col, type ->
                val appear = remember { Animatable(if (motion) 0f else 1f) }
                LaunchedEffect(Unit) { if (motion) { kotlinx.coroutines.delay(90L * (row * 2 + col)); appear.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) } }
                val on = type == selected
                Surface(onClick = { pick(type) }, shape = RoundedCornerShape(20.dp), color = Tokens.Panel,
                    border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Tokens.Yellow else Tokens.Line),
                    modifier = Modifier.weight(1f).height(150.dp).graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 40f }
                        .semantics { contentDescription = "${type.title}${if (on) ", selected" else ""}" }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        VehicleGlyph(type, if (on) Tokens.Yellow else Tokens.Paper, 76.dp)
                        Text(type.title.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (on) Tokens.Yellow else Tokens.Paper)
                    }
                }
            }
        }
    }
}

@Composable private fun ConvoyStep(selected: Role, pick: (Role) -> Unit) {
    Text("Your place comes from when you start", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 33.sp))
    RoadScene(Modifier.fillMaxWidth().height(200.dp), highlight = null)
    listOf(Triple('A', 0, "First phone to start the mesh · Observer" to "Watches the road (dashcam or test warnings). Its findings go to every phone behind."),
        Triple('B', 1, "Phones started after it · Relays" to "Each passes warnings to the phone that started after it. Nothing to tap."),
        Triple('C', 2, "Newest phone · Receiver" to "Warnings end here and are spoken. When another phone starts, it becomes a relay.")).forEach { (letter, i, text) ->
        Row(Modifier.fillMaxWidth().background(Tokens.Panel, RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoleBadge(letter, roleColor(i), 40.dp)
            Column(Modifier.weight(1f)) {
                Text(text.first, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text(text.second, color = Tokens.Muted, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }
    Text("Start the phones one at a time, a couple of seconds apart, with Bluetooth on.", color = Tokens.Faint, fontSize = 13.sp, lineHeight = 18.sp)
}

/**
 * Motion graphic: a perspective road scrolling toward the viewer with three cars at different depths. A warning
 * leaves A as rings, travels down the road to B, which re-emits it to C. [highlight] rings one car.
 */
@Composable fun RoadScene(modifier: Modifier, highlight: Int?) {
    val still = reducedMotion()
    val t by if (still) remember { mutableFloatStateOf(0.55f) } else rememberInfiniteTransition(label = "road").animateFloat(0f, 1f,
        infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "t")
    val measurer = rememberTextMeasurer()
    Canvas(modifier.semantics { contentDescription = "Animation: a warning travels from car A to car B to car C" }) {
        val w = size.width; val h = size.height
        val hz = h * 0.06f
        fun y(d: Float) = hz + (h - hz) * (1 - d).pow(2.2f)
        fun half(d: Float) = w * (0.04f + 0.62f * (1 - d).pow(2.2f))
        val road = Path().apply { moveTo(w / 2 - half(1f), y(1f)); lineTo(w / 2 + half(1f), y(1f)); lineTo(w / 2 + half(0f), y(0f)); lineTo(w / 2 - half(0f), y(0f)); close() }
        drawPath(road, Tokens.Panel)
        drawLine(Tokens.Paper.copy(alpha = 0.3f), Offset(w / 2 - half(1f), y(1f)), Offset(w / 2 - half(0f), y(0f)), strokeWidth = 3f)
        drawLine(Tokens.Paper.copy(alpha = 0.3f), Offset(w / 2 + half(1f), y(1f)), Offset(w / 2 + half(0f), y(0f)), strokeWidth = 3f)
        // Lane dashes move toward the viewer.
        val phase = (t * 6) % 1f
        for (i in 0 until 10) {
            val d0 = 1f - (i + phase) / 10f; val d1 = (d0 - 0.04f).coerceAtLeast(0f)
            if (d0 <= 0f) continue
            drawLine(Tokens.Paper.copy(alpha = 0.55f), Offset(w / 2, y(d0)), Offset(w / 2, y(d1)), strokeWidth = 2f + 10f * (1 - d1).pow(2f))
        }
        val depths = listOf(0.78f, 0.5f, 0.2f)
        val lane = -0.42f // keep left
        val cars = depths.map { d -> Offset(w / 2 + lane * half(d), y(d)) }
        val scale = depths.map { d -> 0.18f + 1.2f * (1 - d).pow(2.2f) }
        // The packet: A rings at 0-0.25, travels A→B 0.2-0.45, B rings 0.45-0.6, travels B→C 0.55-0.8, C glows 0.8-1.
        fun rings(c: Offset, s: Float, p: Float) { if (p in 0f..1f) for (k in 0..2) { val q = (p - k * 0.18f).coerceIn(0f, 1f); if (q > 0f && q < 1f) drawCircle(Tokens.Yellow.copy(alpha = 1 - q), 18f * s + q * 70f * s, c - Offset(0f, 40f * s), style = Stroke(4f * s.coerceAtLeast(0.6f))) } }
        rings(cars[0], scale[0], t / 0.3f)
        rings(cars[1], scale[1], (t - 0.45f) / 0.3f)
        fun travel(a: Int, b: Int, p: Float) { if (p in 0f..1f) { val e = FastOutSlowInEasing.transform(p); val pos = cars[a] + (cars[b] - cars[a]) * e - Offset(0f, 46f * (scale[a] + (scale[b] - scale[a]) * e)); drawCircle(Tokens.Yellow, 7f + 6f * e, pos); drawCircle(Tokens.Yellow.copy(alpha = 0.25f), 18f + 10f * e, pos) } }
        travel(0, 1, (t - 0.2f) / 0.25f)
        travel(1, 2, (t - 0.55f) / 0.25f)
        cars.forEachIndexed { i, c ->
            val s = scale[i]
            val glow = i == 2 && t > 0.8f || i == 1 && t in 0.45f..0.6f
            if (highlight == i) drawCircle(Tokens.Yellow, 62f * s, c - Offset(0f, 22f * s), style = Stroke(4f))
            carRear(c, s, roleColor(i), glow)
            val label = measurer.measure(('A' + i).toString(), TextStyle(fontFamily = Saira, fontWeight = FontWeight.ExtraBold, fontSize = (12 + 10 * s).coerceAtMost(26f).sp, color = Tokens.Paper))
            drawText(label, topLeft = c - Offset(label.size.width / 2f, 74f * s + label.size.height))
        }
    }
}

/** A car seen from behind, like the brand mark: body, dark rear window, tail lights that flash when warned. */
private fun DrawScope.carRear(base: Offset, s: Float, color: Color, warned: Boolean) {
    val w = 96f * s; val h = 56f * s
    val tl = base - Offset(w / 2, h)
    drawRoundRect(color, tl + Offset(w * 0.12f, -h * 0.42f), Size(w * 0.76f, h * 0.55f), CornerRadius(h * 0.2f))
    drawRoundRect(Tokens.Ink, tl + Offset(w * 0.2f, -h * 0.32f), Size(w * 0.6f, h * 0.3f), CornerRadius(h * 0.1f))
    drawRoundRect(color, tl, Size(w, h), CornerRadius(h * 0.25f))
    val light = if (warned) Tokens.Yellow else Tokens.Red.copy(alpha = 0.85f)
    drawRoundRect(light, tl + Offset(w * 0.06f, h * 0.2f), Size(w * 0.2f, h * 0.18f), CornerRadius(h * 0.06f))
    drawRoundRect(light, tl + Offset(w * 0.74f, h * 0.2f), Size(w * 0.2f, h * 0.18f), CornerRadius(h * 0.06f))
    drawRoundRect(Tokens.Ink, tl + Offset(w * 0.08f, h * 0.92f), Size(w * 0.18f, h * 0.2f), CornerRadius(h * 0.05f))
    drawRoundRect(Tokens.Ink, tl + Offset(w * 0.74f, h * 0.92f), Size(w * 0.18f, h * 0.2f), CornerRadius(h * 0.05f))
}

