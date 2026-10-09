package org.resilane.sim.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thumb stick that sets which way this node's vehicle faces (see HeadingControl). Push toward a
 * direction on screen and the car turns to face it; let go and the heading stays. It never moves
 * the car: position is the phone's own tracking.
 *
 * The ring carries a chevron at the car's current heading relative to the view, so turning the
 * phone shows where the car points even with the stick released.
 *
 * A plain View on top of the GL surface, like the rest of the HUD, so YOLO and the dataset never
 * see it.
 */
@SuppressLint("ViewConstructor")
class Joystick(
    context: Context,
    private val onStick: (x: Float, y: Float) -> Unit,
    /** Car heading relative to the view, radians clockwise from screen-up; null before tracking. */
    private val relativeHeading: () -> Float?,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private var knobX = 0f
    private var knobY = 0f
    private var active = false
    private var engaged = false

    init {
        contentDescription = "Vehicle heading joystick: drag toward the direction the car should face"
        isClickable = true
    }

    private val radius get() = min(width, height) / 2f - 4 * dp

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var x = (e.x - width / 2f) / radius
                var y = (e.y - height / 2f) / radius
                val l = sqrt(x * x + y * y)
                if (l > 1f) { x /= l; y /= l }
                knobX = x; knobY = y; active = true
                val now = l >= DEADZONE
                if (now && !engaged) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                engaged = now
                onStick(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f; knobY = 0f; active = false; engaged = false
                onStick(0f, 0f)
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = radius
        fill.color = Color.argb(if (active) 200 else 160, 12, 15, 20)
        c.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = 1.5f * dp
        stroke.color = if (engaged) Ui.ACCENT else Color.argb(90, 255, 255, 255)
        c.drawCircle(cx, cy, r, stroke)
        stroke.color = Color.argb(45, 255, 255, 255)
        c.drawCircle(cx, cy, r * DEADZONE, stroke)
        // "Ahead" tick at the top: the stick is relative to the view.
        stroke.color = Color.argb(120, 255, 255, 255); stroke.strokeWidth = 2 * dp
        c.drawLine(cx, cy - r + 3 * dp, cx, cy - r + 11 * dp, stroke)

        relativeHeading()?.let { a ->
            // Chevron on the ring where the car faces.
            val ux = sin(a); val uy = -cos(a)
            val tipR = r - 3 * dp; val baseR = r - 17 * dp; val half = 8 * dp
            path.reset()
            path.moveTo(cx + ux * tipR, cy + uy * tipR)
            path.lineTo(cx + ux * baseR - uy * half, cy + uy * baseR + ux * half)
            path.lineTo(cx + ux * baseR + uy * half, cy + uy * baseR - ux * half)
            path.close()
            fill.color = Ui.ACCENT; c.drawPath(path, fill)
        }

        val kx = cx + knobX * r * 0.72f; val ky = cy + knobY * r * 0.72f
        fill.color = if (active) Ui.ACCENT else Color.argb(220, 60, 68, 78)
        c.drawCircle(kx, ky, r * 0.30f, fill)
        text.textSize = 10 * dp
        text.color = if (active) Color.BLACK else Ui.TEXT
        c.drawText("HDG", kx, ky + 3.5f * dp, text)
    }

    companion object { const val DEADZONE = 0.25f }
}
