package com.jsons.roadmesh.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsons.roadmesh.core.insight.title
import com.jsons.roadmesh.core.mesh.*
import com.jsons.roadmesh.core.road.KnownHazard
import com.jsons.roadmesh.service.rankLetter
import kotlin.math.*

/** A phone placed on the 3D road: [z] metres from the first located phone (negative is behind the display heading). */
data class MapNode(val rank: Int, val role: Role, val self: Boolean, val z: Double, val x: Double, val vehicle: VehicleType)
/** A hazard placed on the road, same frame as [MapNode]. */
data class MapHazard(val kind: HazardKind, val z: Double, val x: Double, val reporters: Int)

/**
 * Lays the chain and the road map out along the road. With real positions (road mode) everything is placed by its
 * distance along the mean heading from the first located phone. Missing positions are never invented from rank.
 * Stage positions, when present, come explicitly from the synthetic stage position source.
 */
fun layoutMesh(chain: List<Member>, known: List<KnownHazard>): Pair<List<MapNode>, List<MapHazard>> {
    val lead = chain.firstNotNullOfOrNull { it.fix?.takeIf(GeoFix::valid) }
    // Opposing traffic must not rotate the reference frame by averaging its heading with the anchor.
    val heading = lead?.heading ?: 0.0
    fun place(f: GeoFix?): Pair<Double, Double>? {
        if (lead == null || f == null || !f.valid()) return null
        val along = alongM(lead, f, heading)
        val d = distanceM(lead, f)
        val side = if (d < 0.5) 0.0 else d * sin(Math.toRadians(bearing(lead, f) - heading))
        return along to side.coerceIn(-6.0, 6.0)
    }
    val nodes = chain.mapNotNull { m ->
        val (z, x) = place(m.fix) ?: return@mapNotNull null
        MapNode(m.rank, m.role, m.self, z, x.coerceIn(-3.5, 3.5), m.vehicle)
    }
    val hazards = known.mapNotNull { h -> place(h.fix)?.let { (z, x) -> MapHazard(h.kind, z, x.coerceIn(-3.5, 3.5), h.reporters.size) } }
    return nodes to hazards
}

private data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
}

/** Orbit camera: [yaw] around the vertical axis, [pitch] above the road, looking at [target] from [distance]. */
private class Camera(val yaw: Double, val pitch: Double, val distance: Double, val target: V3, val w: Float, val h: Float) {
    private val cy = cos(yaw); private val sy = sin(yaw); private val cp = cos(pitch); private val sp = sin(pitch)
    private val eye = target + V3(sy * cp, sp, -cy * cp) * distance
    private val focal = h * 1.25
    // Orthonormal basis: forward points at the target; right is +x when seen from behind the chain; up completes it.
    private val f = V3(-sy * cp, -sp, cy * cp)
    private val r = V3(cy, 0.0, sy)
    private val u = V3(-sy * sp, cp, cy * sp)
    private fun dot(a: V3, b: V3) = a.x * b.x + a.y * b.y + a.z * b.z
    /** Screen point and depth, or null behind the camera. */
    fun project(p: V3): Pair<Offset, Double>? {
        val d = p - eye
        val zc = dot(d, f)
        if (zc < 1.0) return null
        return Offset((w / 2 + focal * dot(d, r) / zc).toFloat(), (h / 2 - focal * dot(d, u) / zc).toFloat()) to zc
    }
}

private fun roleColour(role: Role) = when (role) { Role.OBSERVER -> roleColor(0); Role.RELAY -> roleColor(1); Role.RECEIVER -> roleColor(2) }
private fun hazardColour(kind: HazardKind) = when {
    kind.violation -> Tokens.Purple
    kind == HazardKind.ACCIDENT || kind == HazardKind.WRONG_WAY || kind == HazardKind.DRIVER_SOS -> Tokens.Red
    else -> Tokens.Yellow
}

/**
 * The mesh in 3D: a two-lane road (traffic keeps left), every phone as a vehicle block with its chain letter and role,
 * inferred chain adjacency as arcs (local activity is highlighted; remote delivery is not established), and the
 * hazards this phone knows sitting on the road. Drag to orbit, pinch to zoom.
 */
@Composable fun MeshMap3D(allNodes: List<MapNode>, allHazards: List<MapHazard>, activeLinks: Set<Int>, modifier: Modifier = Modifier, height: Dp = 340.dp, unlocatedCount: Int = 0) {
    val measurer = rememberTextMeasurer()
    // The road is drawn shortened along its length (ALONG display units per metre) so a 400 m chain still fits on a
    // phone with a readable road width; distance labels stay in real metres.
    val nodes = allNodes.map { it.copy(z = it.z * ALONG) }
    val hazards = allHazards.map { it.copy(z = it.z * ALONG) }
    var yaw by remember { mutableDoubleStateOf(-0.42) }
    var pitch by remember { mutableDoubleStateOf(0.55) }
    var zoom by remember { mutableDoubleStateOf(1.0) }
    val still = reducedMotion()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "p")
    val zs = nodes.map { it.z } + hazards.map { it.z }
    val front = (zs.maxOrNull() ?: 0.0) + 30; val back = (zs.minOrNull() ?: 0.0) - 30
    val span = (front - back).coerceAtLeast(80.0)
    val target = V3(0.0, 0.0, (front + back) / 2)
    Column(modifier) {
    Text("Relative positions · arcs show inferred neighbours", color = Tokens.Muted, fontFamily = Saira, fontSize = 12.sp)
    Text("Local activity is not remote delivery confirmation. Lateral spacing is illustrative.", color = Tokens.Faint, fontFamily = Saira, fontSize = 11.sp)
    if (unlocatedCount > 0) Text("$unlocatedCount phone${if (unlocatedCount == 1) "" else "s"} unlocated · no position drawn", color = Tokens.Yellow, fontFamily = Saira, fontSize = 12.sp)
    Canvas(Modifier.fillMaxWidth().height(height)
        .pointerInput(Unit) { detectTransformGestures { _, pan, z, _ ->
            yaw += pan.x / 300.0; pitch = (pitch + pan.y / 400.0).coerceIn(0.12, 1.45); zoom = (zoom / z).coerceIn(0.35, 2.5)
        } }
        .semantics { contentDescription = "Relative map of ${nodes.size} located phones and ${hazards.size} hazards. $unlocatedCount unlocated phones are omitted. Arcs show inferred neighbours, not confirmed delivery. Drag to rotate." }) {
        val cam = Camera(yaw, pitch, span * 0.95 * zoom, target, size.width, size.height)
        fun p(x: Double, y: Double, z: Double) = cam.project(V3(x, y, z))?.first
        fun poly(pts: List<Offset?>, color: Color, stroke: Float? = null) {
            if (pts.any { it == null }) return
            val path = Path().apply { moveTo(pts[0]!!.x, pts[0]!!.y); pts.drop(1).forEach { lineTo(it!!.x, it.y) }; close() }
            if (stroke == null) drawPath(path, color) else drawPath(path, color, style = Stroke(stroke))
        }
        // Ground grid and road, in slices so the perspective stays correct far from the camera.
        val step = 8.0
        var z = back
        while (z < front) {
            val z2 = min(z + step, front)
            poly(listOf(p(-16.0, 0.0, z), p(16.0, 0.0, z), p(16.0, 0.0, z2), p(-16.0, 0.0, z2)), Color(0xFF0E120F))
            poly(listOf(p(-ROAD, 0.0, z), p(ROAD, 0.0, z), p(ROAD, 0.0, z2), p(-ROAD, 0.0, z2)), Color(0xFF2A2A2A))
            p(0.0, 0.0, z)?.let { a -> p(0.0, 0.0, z + step / 2)?.let { b -> drawLine(Tokens.Paper.copy(alpha = 0.55f), a, b, strokeWidth = 2f) } }
            for (edge in listOf(-ROAD, ROAD)) p(edge, 0.0, z)?.let { a -> p(edge, 0.0, z2)?.let { b -> drawLine(Tokens.Paper.copy(alpha = 0.8f), a, b, strokeWidth = 2.5f) } }
            z = z2
        }
        // Distance ticks every 100 m from the first located phone.
        val label = TextStyle(fontFamily = Saira, fontSize = 10.sp, color = Tokens.Faint)
        var tick = 0.0
        while (tick * ALONG >= back) {
            p(ROAD + 1.5, 0.0, tick * ALONG)?.let { drawText(measurer.measure(if (tick == 0.0) "anchor" else "${(-tick).toInt()} m", label), topLeft = it) }
            tick -= 100.0
        }
        // Hazards on the road.
        hazards.forEach { h ->
            val c = hazardColour(h.kind)
            val hx = h.x * ROAD / 3.6
            val ring = (0 until 12).map { i -> val a = i * PI / 6; p(hx + 1.6 * cos(a), 0.05, h.z + 1.6 * sin(a)) }
            poly(ring, c.copy(alpha = 0.85f))
            val top = p(hx, 16.0, h.z); val base = p(hx, 0.0, h.z)
            if (top != null && base != null) {
                drawLine(c, base, top, strokeWidth = 3f)
                val t = measurer.measure(h.kind.title() + if (h.reporters > 1) " ×${h.reporters}" else "", TextStyle(fontFamily = Saira, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = c))
                drawText(t, topLeft = top - Offset(t.size.width / 2f, t.size.height.toFloat()))
            }
        }
        // Inferred adjacency only. A missing location must not invent a shortcut across an unknown neighbour.
        val ordered = nodes.sortedBy { it.rank }
        ordered.zipWithNext().filter { (a, b) -> b.rank == a.rank + 1 }.forEach { (a, b) ->
            val lit = b.rank in activeLinks
            val pts = (0..24).map { i -> val t = i / 24.0; p((a.x + (b.x - a.x) * t) * ROAD / 3.6, 4.5 + sin(PI * t) * (abs(a.z - b.z) * 0.3).coerceIn(4.0, 14.0), a.z + (b.z - a.z) * t) }
            if (pts.none { it == null }) {
                val path = Path().apply { moveTo(pts[0]!!.x, pts[0]!!.y); pts.drop(1).forEach { lineTo(it!!.x, it.y) } }
                drawPath(path, if (lit) Tokens.Green else Tokens.Outline, style = Stroke(if (lit) 5f else 2.5f, pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
                if (lit) {
                    val i = if (still) 12 else (pulse * 24).toInt().coerceIn(0, 24)
                    pts[i]?.let { drawCircle(Tokens.Green, 9f, it); drawCircle(Tokens.Green.copy(alpha = 0.3f), 20f, it) }
                }
            }
        }
        // Vehicles, far first.
        nodes.sortedByDescending { n -> cam.project(V3(n.x, 0.0, n.z))?.second ?: 0.0 }.forEach { n -> vehicle(n, cam, measurer) }
    }
    }
}

private const val ALONG = 0.3
private const val ROAD = 6.0

private fun DrawScope.vehicle(n: MapNode, cam: Camera, measurer: androidx.compose.ui.text.TextMeasurer) {
    // Drawn about twice life size so a phone stands out on the shortened road.
    val (len, wid, hgt) = when (n.vehicle) { VehicleType.TWO_WHEELER -> Triple(4.0, 1.6, 3.0); VehicleType.BUS_TRUCK -> Triple(11.0, 4.6, 6.0); VehicleType.AUTO -> Triple(5.0, 2.8, 3.6); else -> Triple(7.5, 3.6, 3.0) }
    val c = roleColour(n.role)
    val x = n.x * ROAD / 3.6
    fun pt(dx: Double, dy: Double, dz: Double) = cam.project(V3(x + dx, dy, n.z + dz))?.first
    val hx = wid / 2; val hz = len / 2
    val b = listOf(pt(-hx, 0.0, -hz), pt(hx, 0.0, -hz), pt(hx, 0.0, hz), pt(-hx, 0.0, hz))
    val t = listOf(pt(-hx, hgt, -hz), pt(hx, hgt, -hz), pt(hx, hgt, hz), pt(-hx, hgt, hz))
    if ((b + t).any { it == null }) return
    fun face(q: List<Offset?>, shade: Float) = drawPath(Path().apply { moveTo(q[0]!!.x, q[0]!!.y); q.drop(1).forEach { lineTo(it!!.x, it.y) }; close() }, c.copy(alpha = shade))
    face(listOf(b[0], b[1], t[1], t[0]), 0.75f)
    face(listOf(b[1], b[2], t[2], t[1]), 0.6f)
    face(listOf(b[3], b[0], t[0], t[3]), 0.6f)
    face(listOf(b[2], b[3], t[3], t[2]), 0.75f)
    face(listOf(t[0], t[1], t[2], t[3]), 1f)
    if (n.self) drawPath(Path().apply { moveTo(t[0]!!.x, t[0]!!.y); listOf(t[1], t[2], t[3]).forEach { lineTo(it!!.x, it.y) }; close() }, Tokens.Yellow, style = Stroke(4f))
    val tag = pt(0.0, hgt + 2, 0.0) ?: return
    val text = measurer.measure("${rankLetter(n.rank)} · ${n.role.title}${if (n.self) " · you" else ""}",
        TextStyle(fontFamily = Saira, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (n.self) Tokens.Yellow else Tokens.Paper))
    drawRect(Tokens.Scrim, topLeft = tag - Offset(text.size.width / 2f + 6, text.size.height + 4f), size = androidx.compose.ui.geometry.Size(text.size.width + 12f, text.size.height + 6f))
    drawText(text, topLeft = tag - Offset(text.size.width / 2f, text.size.height + 1f))
}
