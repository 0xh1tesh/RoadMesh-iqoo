package com.jsons.roadmesh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsons.roadmesh.core.mesh.VehicleType
import com.jsons.roadmesh.core.perception.ObjectClass
import com.jsons.roadmesh.perception.Overlay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private fun ObjectClass.lengthM() = when (this) {
    ObjectClass.CAR -> 4.4; ObjectClass.BUS -> 10.0; ObjectClass.TRUCK -> 8.0; ObjectClass.MOTORCYCLE -> 2.0
    ObjectClass.BICYCLE -> 1.7; ObjectClass.PERSON -> 0.6; else -> 1.0
}
private fun ObjectClass.heightM() = when (this) {
    ObjectClass.BUS, ObjectClass.TRUCK -> 3.0; ObjectClass.PERSON -> 1.65; ObjectClass.MOTORCYCLE, ObjectClass.BICYCLE -> 1.4; else -> 1.5
}
private fun Overlay.color() = when { hazard -> Tokens.Red; inLane -> Tokens.Yellow; else -> Tokens.Paper.copy(alpha = 0.7f) }

/**
 * YOLO over the video as 3D frames: each box is the near face of a cuboid whose far face recedes toward the vanishing
 * point by the object's estimated length at its estimated distance. Arrows are the tracked ground point's motion over
 * the next second. Red is the hazard just raised, yellow is in the lane, white is everything else.
 */
@Composable fun DetectionOverlay3D(overlays: List<Overlay>, vanishing: Offset = Offset(0.5f, 0.52f)) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Detections: ${overlays.size} objects, ${overlays.count { it.inLane }} in the lane" }) {
        val vp = Offset(vanishing.x * size.width, vanishing.y * size.height)
        for (ov in overlays.sortedByDescending { it.distanceM ?: 0.0 }) {
            val s = ov.subject; val c = ov.color()
            val near = listOf(Offset(s.x1, s.y1), Offset(s.x2, s.y1), Offset(s.x2, s.y2), Offset(s.x1, s.y2)).map { Offset(it.x * size.width, it.y * size.height) }
            val d = ov.distanceM ?: 20.0
            val f = (d / (d + s.cls.lengthM())).toFloat()
            val far = near.map { vp + (it - vp) * f }
            val face = Path().apply { moveTo(near[0].x, near[0].y); near.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(face, c.copy(alpha = if (ov.hazard) 0.22f else 0.08f))
            val back = Path().apply { moveTo(far[0].x, far[0].y); far.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            val w = if (ov.hazard) 6f else if (ov.inLane) 4f else 2f
            drawPath(back, c.copy(alpha = 0.55f), style = Stroke(w * 0.6f))
            for (i in 0..3) drawLine(c.copy(alpha = 0.6f), near[i], far[i], strokeWidth = w * 0.6f)
            drawPath(face, c, style = Stroke(w))
            // Motion vector of the ground point, one second ahead.
            val from = Offset(s.cx * size.width, s.y2 * size.height)
            val to = from + Offset(ov.vx * size.width, ov.vy * size.height)
            if (hypot(to.x - from.x, to.y - from.y) > 6f) arrow(from, to, if (ov.inLane || ov.hazard) c else Tokens.Blue, 4f)
            if (ov.inLane || ov.hazard) {
                val label = "#${ov.id} ${s.cls.name.lowercase()}${ov.distanceM?.let { " ${it.toInt()} m" } ?: ""}"
                val layout = measurer.measure(label, TextStyle(fontFamily = Saira, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Tokens.Ink))
                val y = (near[0].y - layout.size.height - 6f).coerceAtLeast(0f)
                drawRect(c, Offset(near[0].x, y), Size(layout.size.width + 12f, layout.size.height + 4f))
                drawText(layout, topLeft = Offset(near[0].x + 6f, y + 2f))
            }
        }
    }
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color, width: Float) {
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)
    val a = atan2(to.y - from.y, to.x - from.x); val head = 16f
    val p = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - head * cos(a - 0.45f), to.y - head * sin(a - 0.45f))
        lineTo(to.x - head * cos(a + 0.45f), to.y - head * sin(a + 0.45f)); close()
    }
    drawPath(p, color)
}

/**
 * Bird's-eye 3D frame of the road ahead: a perspective ground plane with lanes (left-hand traffic, so the overtaking
 * lane is on the right), range lines, this vehicle at the bottom and every detection extruded at its estimated lateral
 * offset and distance.
 */
@Composable fun BirdsEye3D(overlays: List<Overlay>, modifier: Modifier = Modifier, highlightOvertake: Boolean = false) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(230.dp).semantics { contentDescription = "Top view of the road ahead with ${overlays.size} objects" }) {
        val horizon = size.height * 0.08f; val bottom = size.height - 8f; val cx = size.width / 2
        val pxPerM = size.width / 15f
        fun s(z: Double) = (1.0 / (1.0 + z / 18.0)).toFloat()
        fun at(x: Double, z: Double) = Offset(cx + (x * pxPerM * s(z)).toFloat(), horizon + (bottom - horizon) * s(z))
        drawRect(Tokens.Panel, size = size)
        // Lanes: ours is centred on 0; the right lane (overtaking, possibly oncoming) is 1.75..5.25 m.
        if (highlightOvertake) {
            val band = Path().apply { val a = at(1.75, 0.0); moveTo(a.x, a.y); at(5.25, 0.0).let { lineTo(it.x, it.y) }; at(5.25, 80.0).let { lineTo(it.x, it.y) }; at(1.75, 80.0).let { lineTo(it.x, it.y) }; close() }
            drawPath(band, Tokens.Yellow.copy(alpha = 0.12f))
        }
        for (x in listOf(-5.25, -1.75, 1.75, 5.25)) drawLine(Tokens.Paper.copy(alpha = if (x == 1.75 || x == -1.75) 0.5f else 0.25f), at(x, 0.0), at(x, 80.0),
            strokeWidth = 2f, pathEffect = if (x == 1.75) PathEffect.dashPathEffect(floatArrayOf(14f, 12f)) else null)
        for (z in listOf(10.0, 20.0, 40.0)) {
            drawLine(Tokens.Paper.copy(alpha = 0.10f), at(-7.0, z), at(7.0, z), strokeWidth = 1.5f)
            val t = measurer.measure("$z".removeSuffix(".0") + " m", TextStyle(fontFamily = Saira, fontSize = 10.sp, color = Tokens.Faint))
            drawText(t, topLeft = at(-7.0, z) + Offset(2f, -t.size.height.toFloat()))
        }
        // Detections, far first so near ones draw on top.
        for (ov in overlays.filter { it.distanceM != null && it.lateralM != null && it.distanceM!! < 80 }.sortedByDescending { it.distanceM }) {
            val c = ov.color(); val cls = ov.subject.cls
            val half = (cls.widthM ?: 0.5) / 2; val z0 = ov.distanceM!!; val z1 = z0 + cls.lengthM(); val x = ov.lateralM!!
            val base = listOf(at(x - half, z0), at(x + half, z0), at(x + half, z1), at(x - half, z1))
            val lift = (cls.heightM() * pxPerM * 0.55).toFloat()
            val top = base.mapIndexed { i, p -> p - Offset(0f, lift * s(if (i < 2) z0 else z1)) }
            fun quad(a: Offset, b: Offset, cc: Offset, d: Offset) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(cc.x, cc.y); lineTo(d.x, d.y); close() }
            drawPath(quad(base[0], base[1], top[1], top[0]), c.copy(alpha = 0.55f))
            drawPath(quad(top[0], top[1], top[2], top[3]), c.copy(alpha = 0.9f))
            drawPath(quad(base[1], base[2], top[2], top[1]), c.copy(alpha = 0.35f))
        }
        // This vehicle.
        val me = listOf(at(-0.9, -0.5), at(0.9, -0.5), at(0.9, 3.0), at(-0.9, 3.0))
        drawPath(Path().apply { moveTo(me[0].x, me[0].y); me.drop(1).forEach { lineTo(it.x, it.y) }; close() }, Tokens.Yellow)
        val label = measurer.measure("You", TextStyle(fontFamily = Saira, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Tokens.Ink))
        drawText(label, topLeft = Offset(cx - label.size.width / 2f, me[2].y + 2f))
    }
}

/** Side-view glyph for each vehicle type, drawn in [color]. */
@Composable fun VehicleGlyph(type: VehicleType, color: Color, size: Dp = 56.dp) {
    Canvas(Modifier.size(size, size * 0.62f)) {
        val w = this.size.width; val h = this.size.height
        val wheel = h * 0.17f
        fun wheels(vararg xs: Float) = xs.forEach { drawCircle(color, wheel, Offset(it * w, h - wheel)); drawCircle(Tokens.Ink, wheel * 0.45f, Offset(it * w, h - wheel)) }
        when (type) {
            VehicleType.CAR, VehicleType.UNKNOWN -> {
                drawRoundRect(color, Offset(0.02f * w, 0.42f * h), Size(0.96f * w, 0.38f * h), CornerRadius(h * 0.12f))
                drawPath(Path().apply { moveTo(0.22f * w, 0.45f * h); lineTo(0.34f * w, 0.14f * h); lineTo(0.68f * w, 0.14f * h); lineTo(0.82f * w, 0.45f * h); close() }, color)
                drawPath(Path().apply { moveTo(0.31f * w, 0.42f * h); lineTo(0.38f * w, 0.22f * h); lineTo(0.49f * w, 0.22f * h); lineTo(0.49f * w, 0.42f * h); close() }, Tokens.Ink)
                drawPath(Path().apply { moveTo(0.53f * w, 0.42f * h); lineTo(0.53f * w, 0.22f * h); lineTo(0.65f * w, 0.22f * h); lineTo(0.73f * w, 0.42f * h); close() }, Tokens.Ink)
                wheels(0.22f, 0.78f)
            }
            VehicleType.TWO_WHEELER -> {
                drawPath(Path().apply { moveTo(0.18f * w, 0.72f * h); lineTo(0.42f * w, 0.5f * h); lineTo(0.66f * w, 0.5f * h); lineTo(0.8f * w, 0.72f * h); close() }, color)
                drawLine(color, Offset(0.72f * w, 0.62f * h), Offset(0.66f * w, 0.18f * h), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
                drawLine(color, Offset(0.6f * w, 0.18f * h), Offset(0.74f * w, 0.18f * h), strokeWidth = h * 0.07f, cap = StrokeCap.Round)
                drawRoundRect(color, Offset(0.3f * w, 0.4f * h), Size(0.24f * w, 0.1f * h), CornerRadius(h * 0.05f))
                wheels(0.18f, 0.8f)
            }
            VehicleType.AUTO -> {
                drawPath(Path().apply { moveTo(0.1f * w, 0.78f * h); lineTo(0.1f * w, 0.3f * h); quadraticTo(0.12f * w, 0.08f * h, 0.4f * w, 0.08f * h)
                    lineTo(0.78f * w, 0.08f * h); quadraticTo(0.92f * w, 0.1f * h, 0.92f * w, 0.4f * h); lineTo(0.92f * w, 0.78f * h); close() }, color)
                drawRect(Tokens.Ink, Offset(0.22f * w, 0.24f * h), Size(0.3f * w, 0.3f * h))
                drawRect(Tokens.Ink, Offset(0.6f * w, 0.24f * h), Size(0.22f * w, 0.24f * h))
                wheels(0.24f, 0.8f)
            }
            VehicleType.BUS_TRUCK -> {
                drawRoundRect(color, Offset(0.02f * w, 0.08f * h), Size(0.96f * w, 0.72f * h), CornerRadius(h * 0.08f))
                for (i in 0..3) drawRect(Tokens.Ink, Offset((0.08f + i * 0.21f) * w, 0.2f * h), Size(0.16f * w, 0.22f * h))
                wheels(0.2f, 0.8f)
            }
        }
    }
}

enum class TabIcon { OBSERVE, RELAY, RECEIVED, MESH, REPORTS }

/** Stroke icons for the three tabs, drawn so no icon font is needed. */
@Composable fun TabGlyph(icon: TabIcon, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val st = Stroke(w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (icon) {
            TabIcon.OBSERVE -> {
                drawPath(Path().apply { moveTo(0.06f * w, 0.5f * w); quadraticTo(0.5f * w, 0.08f * w, 0.94f * w, 0.5f * w); quadraticTo(0.5f * w, 0.92f * w, 0.06f * w, 0.5f * w); close() }, color, style = st)
                drawCircle(color, 0.14f * w, Offset(0.5f * w, 0.5f * w))
            }
            TabIcon.RELAY -> {
                drawCircle(color, 0.08f * w, Offset(0.5f * w, 0.62f * w))
                drawArc(color, 215f, 110f, false, Offset(0.26f * w, 0.38f * w), Size(0.48f * w, 0.48f * w), style = st)
                drawArc(color, 215f, 110f, false, Offset(0.08f * w, 0.2f * w), Size(0.84f * w, 0.84f * w), style = st)
            }
            TabIcon.MESH -> {
                val a = Offset(0.2f * w, 0.78f * w); val b = Offset(0.5f * w, 0.22f * w); val c = Offset(0.82f * w, 0.7f * w)
                drawLine(color, a, b, strokeWidth = w * 0.07f); drawLine(color, b, c, strokeWidth = w * 0.07f); drawLine(color, a, c, strokeWidth = w * 0.07f)
                listOf(a, b, c).forEach { drawCircle(color, 0.12f * w, it) }
            }
            TabIcon.REPORTS -> {
                drawRoundRect(color, Offset(0.18f * w, 0.12f * w), Size(0.64f * w, 0.8f * w), CornerRadius(0.08f * w), style = st)
                for (y in listOf(0.36f, 0.54f, 0.72f)) drawLine(color, Offset(0.32f * w, y * w), Offset(0.68f * w, y * w), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            }
            TabIcon.RECEIVED -> {
                drawPath(Path().apply { moveTo(0.1f * w, 0.55f * w); lineTo(0.1f * w, 0.88f * w); lineTo(0.9f * w, 0.88f * w); lineTo(0.9f * w, 0.55f * w) }, color, style = st)
                drawPath(Path().apply { moveTo(0.1f * w, 0.55f * w); lineTo(0.34f * w, 0.55f * w); lineTo(0.4f * w, 0.68f * w); lineTo(0.6f * w, 0.68f * w); lineTo(0.66f * w, 0.55f * w); lineTo(0.9f * w, 0.55f * w) }, color, style = st)
                drawLine(color, Offset(0.5f * w, 0.08f * w), Offset(0.5f * w, 0.44f * w), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(0.36f * w, 0.32f * w); lineTo(0.5f * w, 0.46f * w); lineTo(0.64f * w, 0.32f * w) }, color, style = st)
            }
        }
    }
}
