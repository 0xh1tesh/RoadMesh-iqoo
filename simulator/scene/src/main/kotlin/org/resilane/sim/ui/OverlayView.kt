package org.resilane.sim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import org.resilane.core.math.Angles
import org.resilane.core.math.Vec3
import org.resilane.core.sync.NodeState
import org.resilane.core.vision.Coco
import org.resilane.sim.Settings
import org.resilane.sim.ar.Snapshot
import org.resilane.sim.ar.VehicleView
import org.resilane.sim.vision.VisionPipeline
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Everything drawn on top of the AR view. This is a separate View from the GL surface on purpose:
 * GL capture (what YOLO sees and what the dataset records) contains only the
 * camera and the rendered vehicles, never these labels, boxes or the cockpit overlay.
 */
class OverlayView(
    context: Context,
    private val settings: Settings,
    private val vision: VisionPipeline,
    private val snapshot: () -> Snapshot?,
    private val debugLines: () -> List<String>,
    private val logLines: () -> List<String>?,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val sans = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = sans }
    private val dash = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    private val path = Path()
    private val rect = RectF()

    var topInset = 0f

    override fun onDraw(c: Canvas) {
        val s = snapshot() ?: return drawCenterHint(c, "Starting AR…")
        val sx = width.toFloat() / s.width.coerceAtLeast(1)
        val sy = height.toFloat() / s.height.coerceAtLeast(1)

        if (settings.showWindshield) drawWindshield(c, s)
        if (settings.showDebug) s.axes?.let { drawAxes(c, it, sx, sy) }

        val yoloFresh = settings.yoloEnabled && System.currentTimeMillis() - vision.lastResultMs < 1200
        if (yoloFresh) drawYolo(c)

        for (v in s.vehicles) {
            if (settings.showDebug) drawVehicleDebug(c, v, sx, sy)
            if (settings.showLabels) v.labelPt?.let { if (onScreen(it, s)) drawLabel(c, v, it[0] * sx, it[1] * sy) }
        }
        if (settings.showOffscreen) for (v in s.vehicles) if (v.gtBox == null) drawPointer(c, v)
        if (settings.showRadar) drawRadar(c, s)

        if (settings.presentationMode) {
            text.typeface = sans; text.textSize = 16 * dp; text.color = Color.WHITE
            fill.color = Color.argb(210, 8, 14, 22)
            c.drawRoundRect(RectF(16 * dp, topInset + 10 * dp, min(width - 16 * dp, 540 * dp), topInset + 68 * dp), 12 * dp, 12 * dp, fill)
            c.drawText("ROAD MESH  /  A · DETECTOR", 30 * dp, topInset + 34 * dp, text)
            text.textSize = 11 * dp; text.color = Color.rgb(81, 223, 220)
            c.drawText("VIRTUAL CAR · ${if (s.mode == "3DOF") "3DOF rotation only" else s.mode} · Brake → BLE", 30 * dp, topInset + 55 * dp, text)
        } else drawStatus(c, s)
        if (s.notice != null && !settings.presentationMode) drawCenterHint(c, wrap(s.notice, 40), 0.24f, Color.argb(215, 120, 70, 0))
        else if (!s.calibrated && !settings.presentationMode) drawCalibrationHint(c, s)
        if (s.tracking != "TRACKING") drawCenterHint(c, "Tracking ${s.tracking.lowercase()}: ${s.trackingReason.lowercase().replace('_', ' ')}\nMove slowly, point at textured surfaces")
        if (settings.showDebug) drawDebugPanel(c)
        logLines()?.let { drawLog(c, it) }
    }

    private fun onScreen(p: FloatArray, s: Snapshot) = p[0] in -50f..(s.width + 50f) && p[1] in -50f..(s.height + 50f)

    // ---------------------------------------------------------------- labels and boxes

    private fun drawLabel(c: Canvas, v: VehicleView, x: Float, y: Float) {
        val degraded = v.state == NodeState.DEGRADED
        val title = (if (v.simulated) "SIM · " else "") + v.name
        val parts = ArrayList<String>()
        if (settings.showDistance) parts += "%.1f m".format(v.distanceM)
        parts += "alt %+.2f m".format(v.altitudeM)
        parts += "%03.0f°".format(v.headingDeg)
        val sub = parts.joinToString("  ·  ")
        text.typeface = sans; text.textSize = 15 * dp
        val tw = text.measureText(title)
        text.typeface = mono; text.textSize = 12 * dp
        val sw = text.measureText(sub)
        val w = maxOf(tw, sw) + 26 * dp
        val h = 46 * dp + if (degraded) 16 * dp else 0f
        rect.set(x - w / 2, y - h - 10 * dp, x + w / 2, y - 10 * dp)
        fill.color = Color.argb(200, 12, 16, 22)
        c.drawRoundRect(rect, 10 * dp, 10 * dp, fill)
        fill.color = v.color
        c.drawRoundRect(RectF(rect.left, rect.top, rect.left + 5 * dp, rect.bottom), 3 * dp, 3 * dp, fill)
        // Pointer down to the car.
        path.reset(); path.moveTo(x - 7 * dp, y - 10 * dp); path.lineTo(x + 7 * dp, y - 10 * dp); path.lineTo(x, y - 2 * dp); path.close()
        fill.color = Color.argb(200, 12, 16, 22); c.drawPath(path, fill)
        text.typeface = sans; text.textSize = 15 * dp; text.color = Color.WHITE
        c.drawText(title, rect.left + 14 * dp, rect.top + 20 * dp, text)
        text.typeface = mono; text.textSize = 12 * dp; text.color = Color.rgb(190, 200, 210)
        c.drawText(sub, rect.left + 14 * dp, rect.top + 38 * dp, text)
        if (degraded) {
            text.color = Color.rgb(255, 176, 32)
            c.drawText("⚠ tracking degraded · held at last pose", rect.left + 14 * dp, rect.top + 54 * dp, text)
        }
        text.color = Color.WHITE
    }

    private fun drawVehicleDebug(c: Canvas, v: VehicleView, sx: Float, sy: Float) {
        v.gtBox?.let { b ->
            stroke.color = Color.argb(220, 0, 229, 255); stroke.strokeWidth = 1.5f * dp; stroke.pathEffect = dash
            c.drawRect(b.x1 * sx, b.y1 * sy, b.x2 * sx, b.y2 * sy, stroke)
            stroke.pathEffect = null
            text.typeface = mono; text.textSize = 10 * dp; text.color = Color.argb(230, 0, 229, 255)
            c.drawText("GT", b.x1 * sx + 3 * dp, b.y2 * sy - 4 * dp, text)
        }
        val g = v.groundPt; val a = v.aheadPt
        if (g != null && a != null) {
            stroke.color = v.color; stroke.strokeWidth = 3 * dp
            c.drawLine(g[0] * sx, g[1] * sy, a[0] * sx, a[1] * sy, stroke)
            fill.color = v.color; c.drawCircle(a[0] * sx, a[1] * sy, 4 * dp, fill)
        }
    }

    private fun drawYolo(c: Canvas) {
        val (vw, vh) = vision.lastFrameSize
        val sx = width.toFloat() / vw; val sy = height.toFloat() / vh
        val gts = vision.lastGt
        for (d in vision.lastDetections) {
            val matched = d.cls in Coco.VEHICLE && gts.any { it.box.iou(d.box) >= 0.4f }
            stroke.pathEffect = null; stroke.strokeWidth = 2 * dp
            stroke.color = if (matched) Color.rgb(57, 255, 20) else if (d.cls in Coco.VEHICLE) Color.rgb(255, 140, 0) else Color.argb(150, 200, 200, 200)
            c.drawRect(d.box.x1 * sx, d.box.y1 * sy, d.box.x2 * sx, d.box.y2 * sy, stroke)
            val lbl = "${Coco.NAMES[d.cls] ?: "#${d.cls}"} %.2f".format(d.score)
            text.typeface = monoBold; text.textSize = 11 * dp
            val tw = text.measureText(lbl)
            fill.color = stroke.color
            c.drawRect(d.box.x1 * sx, d.box.y1 * sy - 16 * dp, d.box.x1 * sx + tw + 8 * dp, d.box.y1 * sy, fill)
            text.color = Color.BLACK
            c.drawText(lbl, d.box.x1 * sx + 4 * dp, d.box.y1 * sy - 4 * dp, text)
        }
        text.color = Color.WHITE
    }

    private fun drawAxes(c: Canvas, axes: Array<FloatArray?>, sx: Float, sy: Float) {
        val o = axes[0] ?: return
        val cols = intArrayOf(Color.rgb(255, 64, 64), Color.rgb(64, 128, 255), Color.rgb(64, 255, 96))
        val names = arrayOf("+X", "+Z", "+Y")
        for (i in 1..3) {
            val p = axes[i] ?: continue
            stroke.color = cols[i - 1]; stroke.strokeWidth = 3 * dp; stroke.pathEffect = null
            c.drawLine(o[0] * sx, o[1] * sy, p[0] * sx, p[1] * sy, stroke)
            text.typeface = monoBold; text.textSize = 11 * dp; text.color = cols[i - 1]
            c.drawText(names[i - 1], p[0] * sx + 4 * dp, p[1] * sy, text)
        }
        text.color = Color.WHITE
    }

    /** Arrow at the screen edge toward a node that is outside the camera's view. */
    private fun drawPointer(c: Canvas, v: VehicleView) {
        if (v.state == NodeState.LOST) return
        val dir = v.screenDir
        val cx = width / 2f; val cy = height / 2f
        val inset = 28 * dp
        val hw = cx - inset; val hh = cy - inset - 60 * dp
        val t = min(if (abs(dir[0]) > 1e-4f) hw / abs(dir[0]) else Float.MAX_VALUE, if (abs(dir[1]) > 1e-4f) hh / abs(dir[1]) else Float.MAX_VALUE)
        val x = cx + dir[0] * t; val y = cy + dir[1] * t
        val ang = kotlin.math.atan2(dir[1], dir[0])
        path.reset()
        path.moveTo(x + cos(ang) * 16 * dp, y + sin(ang) * 16 * dp)
        path.lineTo(x + cos(ang + 2.5f) * 12 * dp, y + sin(ang + 2.5f) * 12 * dp)
        path.lineTo(x + cos(ang - 2.5f) * 12 * dp, y + sin(ang - 2.5f) * 12 * dp)
        path.close()
        fill.color = v.color; c.drawPath(path, fill)
        stroke.color = Color.argb(200, 0, 0, 0); stroke.strokeWidth = 1.5f * dp; c.drawPath(path, stroke)
        text.typeface = mono; text.textSize = 11 * dp; text.color = Color.WHITE
        val lbl = "${v.name.take(10)} %.1fm".format(v.distanceM)
        val tw = text.measureText(lbl)
        val lx = (x - cos(ang) * 30 * dp - tw / 2).coerceIn(4 * dp, width - tw - 4 * dp)
        val ly = (y - sin(ang) * 30 * dp).coerceIn(30 * dp, height - 8 * dp)
        fill.color = Color.argb(170, 10, 12, 16)
        c.drawRoundRect(lx - 4 * dp, ly - 13 * dp, lx + tw + 4 * dp, ly + 5 * dp, 4 * dp, 4 * dp, fill)
        c.drawText(lbl, lx, ly, text)
    }

    // ---------------------------------------------------------------- radar

    private fun drawRadar(c: Canvas, s: Snapshot) {
        val me = s.myWorld ?: return
        val r = min(width, height) * 0.17f
        val cx = width - r - 14 * dp
        val cy = topInset + 52 * dp + r
        val range = 12f
        fill.color = Color.argb(170, 10, 14, 20); c.drawCircle(cx, cy, r, fill)
        stroke.color = Color.argb(70, 255, 255, 255); stroke.strokeWidth = 1 * dp; stroke.pathEffect = null
        c.drawCircle(cx, cy, r, stroke); c.drawCircle(cx, cy, r / 2, stroke)
        text.typeface = mono; text.textSize = 9 * dp; text.color = Color.argb(150, 255, 255, 255)
        c.drawText("${range.toInt()}m", cx + 3 * dp, cy - r + 11 * dp, text)
        c.drawText("${(range / 2).toInt()}", cx + 3 * dp, cy - r / 2 + 11 * dp, text)

        // View-up: the radar turns with the camera, so it reads like the screen.
        val yaw = s.myViewYaw
        val f = Vec3(sin(yaw), 0f, cos(yaw))
        val right = Vec3(-cos(yaw), 0f, sin(yaw))
        // Marker origin as a small square.
        if (s.calibrated) project(Vec3.ZERO - me.t, f, right, r, range)?.let { (x, y) ->
            fill.color = Color.rgb(242, 183, 5); c.drawRect(cx + x - 3 * dp, cy + y - 3 * dp, cx + x + 3 * dp, cy + y + 3 * dp, fill)
        }
        for (v in s.vehicles) {
            val (x, y) = project(v.phoneWorld - me.t, f, right, r, range) ?: continue
            val hf = Vec3(sin(v.yaw), 0f, cos(v.yaw))
            val hx = hf.dot(right); val hy = -hf.dot(f)
            fill.color = if (v.state == NodeState.DEGRADED) Color.GRAY else v.color
            c.drawCircle(cx + x, cy + y, 5 * dp, fill)
            stroke.color = fill.color; stroke.strokeWidth = 2 * dp
            c.drawLine(cx + x, cy + y, cx + x + hx * 12 * dp, cy + y + hy * 12 * dp, stroke)
        }
        // Me: a faint view tick (always up) and the triangle along my vehicle heading.
        stroke.color = Color.argb(90, 255, 255, 255); stroke.strokeWidth = 1.5f * dp
        c.drawLine(cx, cy, cx, cy - r + 6 * dp, stroke)
        val mf = Vec3(sin(s.myYaw), 0f, cos(s.myYaw))
        val ang = kotlin.math.atan2(mf.dot(right), mf.dot(f)) // clockwise from up, radians
        val ux = sin(ang); val uy = -cos(ang)
        path.reset()
        path.moveTo(cx + ux * 9 * dp, cy + uy * 9 * dp)
        path.lineTo(cx - ux * 6 * dp - uy * 6 * dp, cy - uy * 6 * dp + ux * 6 * dp)
        path.lineTo(cx - ux * 6 * dp + uy * 6 * dp, cy - uy * 6 * dp - ux * 6 * dp)
        path.close()
        fill.color = Color.WHITE; c.drawPath(path, fill)
        text.textSize = 10 * dp; text.color = Color.WHITE; text.typeface = monoBold
        val hd = "HDG %03.0f°".format(s.myHeadingDeg)
        c.drawText(hd, cx - text.measureText(hd) / 2, cy + r + 14 * dp, text)
    }

    private fun project(d: Vec3, f: Vec3, right: Vec3, r: Float, range: Float): Pair<Float, Float>? {
        val a = d.dot(f); val b = d.dot(right)
        val k = r / range
        val x = b * k; val y = -a * k
        val l = kotlin.math.sqrt(x * x + y * y)
        return if (l > r - 4 * dp) Pair(x / l * (r - 4 * dp), y / l * (r - 4 * dp)) else Pair(x, y)
    }

    // ---------------------------------------------------------------- cockpit

    private fun drawWindshield(c: Canvas, s: Snapshot) {
        val w = width.toFloat(); val h = height.toFloat()
        fill.shader = null
        fill.color = Color.argb(22, 120, 160, 200); c.drawRect(0f, 0f, w, h, fill)
        // Roof liner and A-pillars.
        fill.color = Color.argb(235, 14, 16, 19)
        path.reset(); path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w, h * 0.07f); path.quadTo(w / 2, h * 0.11f, 0f, h * 0.07f); path.close()
        c.drawPath(path, fill)
        path.reset(); path.moveTo(0f, 0f); path.lineTo(w * 0.13f, 0f); path.lineTo(w * 0.035f, h * 0.70f); path.lineTo(0f, h * 0.72f); path.close()
        c.drawPath(path, fill)
        path.reset(); path.moveTo(w, 0f); path.lineTo(w * 0.87f, 0f); path.lineTo(w * 0.965f, h * 0.70f); path.lineTo(w, h * 0.72f); path.close()
        c.drawPath(path, fill)
        // Dashboard.
        fill.shader = LinearGradient(0f, h * 0.74f, 0f, h, Color.argb(240, 22, 25, 30), Color.argb(255, 8, 9, 11), Shader.TileMode.CLAMP)
        path.reset(); path.moveTo(0f, h * 0.76f); path.quadTo(w / 2, h * 0.70f, w, h * 0.76f); path.lineTo(w, h); path.lineTo(0f, h); path.close()
        c.drawPath(path, fill)
        fill.shader = null
        val y0 = h * 0.80f
        text.typeface = monoBold; text.color = Color.rgb(242, 183, 5)
        text.textSize = 30 * dp
        val kmh = "%3.0f".format(s.mySpeedMps * 3.6f)
        c.drawText(kmh, w * 0.5f - text.measureText(kmh) / 2, y0 + 28 * dp, text)
        text.textSize = 10 * dp; text.color = Color.rgb(160, 168, 176)
        c.drawText("km/h", w * 0.5f - text.measureText("km/h") / 2, y0 + 42 * dp, text)
        text.textSize = 13 * dp; text.color = Color.WHITE
        c.drawText("HDG %03.0f°".format(s.myHeadingDeg), w * 0.12f, y0 + 22 * dp, text)
        c.drawText("ALT %+.2f m".format(s.myWorld?.t?.y ?: 0f), w * 0.12f, y0 + 40 * dp, text)
        val near = s.vehicles.minByOrNull { it.distanceM }
        val nl = near?.let { "NEAR %.1f m".format(it.distanceM) } ?: "NEAR —"
        c.drawText("PEERS ${s.vehicles.count { !it.simulated }}", w * 0.88f - text.measureText(nl), y0 + 22 * dp, text)
        c.drawText(nl, w * 0.88f - text.measureText(nl), y0 + 40 * dp, text)
    }

    // ---------------------------------------------------------------- status / text

    private fun drawStatus(c: Canvas, s: Snapshot) {
        val y = topInset + 8 * dp
        val items = listOf(
            when {
                s.mode == "3DOF" -> "3-DoF · NO ARCORE" to Color.rgb(255, 176, 32)
                s.calibrated -> "SHARED e${s.epoch}" to Color.rgb(57, 220, 120)
                else -> "UNCALIBRATED" to Color.rgb(255, 176, 32)
            },
            (if (s.tracking == "TRACKING") "TRACKING" else s.tracking) to (if (s.tracking == "TRACKING") Color.rgb(57, 220, 120) else Color.rgb(255, 80, 80)),
            "${s.vehicles.count { !it.simulated }} peers" to Color.WHITE,
            "%.0f fps".format(s.fps) to Color.WHITE,
        )
        text.typeface = monoBold; text.textSize = 11 * dp
        var x = 12 * dp
        for ((t, col) in items) {
            val tw = text.measureText(t)
            fill.color = Color.argb(185, 10, 12, 16)
            c.drawRoundRect(x, y, x + tw + 18 * dp, y + 24 * dp, 12 * dp, 12 * dp, fill)
            fill.color = col; c.drawCircle(x + 8 * dp, y + 12 * dp, 3 * dp, fill)
            text.color = Color.WHITE
            c.drawText(t, x + 14 * dp, y + 16 * dp, text)
            x += tw + 24 * dp
        }
    }

    private fun drawCalibrationHint(c: Canvas, s: Snapshot) {
        val msg = if (s.markerVisible) s.calibMsg else "Point the camera at the printed marker on the floor"
        text.typeface = sans; text.textSize = 15 * dp; text.color = Color.WHITE
        val tw = text.measureText(msg)
        val y = height * 0.30f
        fill.color = Color.argb(200, 242, 183, 5)
        c.drawRoundRect(width / 2f - tw / 2 - 16 * dp, y - 24 * dp, width / 2f + tw / 2 + 16 * dp, y + 12 * dp, 18 * dp, 18 * dp, fill)
        text.color = Color.BLACK
        c.drawText(msg, width / 2f - tw / 2, y, text)
        text.color = Color.WHITE
    }

    private fun drawCenterHint(c: Canvas, msg: String, yFrac: Float = 0.5f, bg: Int = Color.argb(200, 10, 12, 16)) {
        text.typeface = sans; text.textSize = 15 * dp; text.color = Color.WHITE
        val lines = msg.split("\n")
        val w = lines.maxOf { text.measureText(it) }
        val y = height * yFrac
        fill.color = bg
        c.drawRoundRect(width / 2f - w / 2 - 18 * dp, y - 26 * dp, width / 2f + w / 2 + 18 * dp, y + lines.size * 22 * dp - 10 * dp, 14 * dp, 14 * dp, fill)
        lines.forEachIndexed { i, l -> c.drawText(l, width / 2f - text.measureText(l) / 2, y + i * 22 * dp, text) }
    }

    /** Greedy word wrap to about [cols] characters per line. */
    private fun wrap(msg: String, cols: Int): String {
        val out = StringBuilder()
        var line = 0
        for (w in msg.split(' ')) {
            if (line > 0 && line + 1 + w.length > cols) { out.append('\n'); line = 0 }
            if (line > 0) { out.append(' '); line++ }
            out.append(w); line += w.length
        }
        return out.toString()
    }

    private fun drawDebugPanel(c: Canvas) {
        val lines = debugLines()
        text.typeface = mono; text.textSize = 10.5f * dp
        val lh = 14 * dp
        val w = min(width * 0.78f, lines.maxOfOrNull { text.measureText(it) }?.plus(16 * dp) ?: 0f)
        val top = topInset + 42 * dp
        fill.color = Color.argb(150, 6, 8, 12)
        c.drawRoundRect(8 * dp, top, 8 * dp + w, top + lines.size * lh + 10 * dp, 8 * dp, 8 * dp, fill)
        text.color = Color.rgb(220, 228, 236)
        lines.forEachIndexed { i, l -> c.drawText(l, 16 * dp, top + 16 * dp + i * lh, text) }
    }

    private fun drawLog(c: Canvas, lines: List<String>) {
        text.typeface = mono; text.textSize = 9.5f * dp
        val lh = 12.5f * dp
        val bottom = height - 150 * dp
        val top = bottom - lines.size * lh - 10 * dp
        fill.color = Color.argb(215, 0, 0, 0)
        c.drawRect(0f, top, width.toFloat(), bottom, fill)
        lines.forEachIndexed { i, l ->
            text.color = when { " E " in l -> Color.rgb(255, 100, 100); " W " in l -> Color.rgb(255, 196, 64); else -> Color.rgb(200, 210, 220) }
            c.drawText(l.take(90), 8 * dp, top + 14 * dp + i * lh, text)
        }
        text.color = Color.WHITE
    }
}
