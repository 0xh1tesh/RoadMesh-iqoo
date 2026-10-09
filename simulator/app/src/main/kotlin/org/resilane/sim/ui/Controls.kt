package org.resilane.sim.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Small programmatic view kit for the HUD: dark translucent surfaces, amber accent. */
class Ui(private val ctx: Context) {
    val dp = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).roundToInt()

    companion object {
        val ACCENT = Color.rgb(242, 183, 5)
        val SURFACE = Color.argb(225, 14, 17, 22)
        val TEXT = Color.rgb(232, 237, 242)
        val MUTED = Color.rgb(150, 160, 172)
    }

    fun bg(color: Int, radius: Float = 12f, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * dp
        strokeColor?.let { setStroke(px(1), it) }
    }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.BLACK else TEXT)
        background = bg(if (primary) ACCENT else Color.argb(200, 32, 38, 46), 22f)
        minHeight = px(44); minimumHeight = px(44)
        minWidth = 0; minimumWidth = 0
        setPadding(px(16), 0, px(16), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44)).apply { marginEnd = px(8) }
    }

    fun label(t: String, size: Float = 13f, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
}

/**
 * Settings panel. Every control writes [org.resilane.sim.Settings] immediately and calls [onChange]
 * with a key so the activity can apply side effects (transports, marker DB, scale sync).
 */
class SettingsPanel(ctx: Context, private val ui: Ui) : FrameLayout(ctx) {
    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(18), ui.px(14), ui.px(18), ui.px(40)) }

    init {
        background = ui.bg(Ui.SURFACE, 18f)
        addView(ScrollView(ctx).apply { addView(list); isVerticalScrollBarEnabled = false })
        isClickable = true // swallow touches so the AR view underneath doesn't get them
    }

    fun header(title: String, onClose: () -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ui.label(title, 20f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ui.button("Done", primary = true, onClick = onClose))
        list.addView(row)
    }

    fun section(title: String) {
        list.addView(ui.label(title.uppercase(), 11f, Ui.ACCENT, bold = true).apply {
            letterSpacing = 0.12f; setPadding(0, ui.px(18), 0, ui.px(6))
        })
    }

    fun note(t: String) { list.addView(ui.label(t, 12f, Ui.MUTED).apply { setPadding(0, 0, 0, ui.px(6)) }) }

    fun slider(name: String, min: Float, max: Float, step: Float, get: () -> Float, fmt: (Float) -> String, set: (Float) -> Unit) {
        val steps = ((max - min) / step).roundToInt()
        val value = ui.label("", 13f, Ui.ACCENT).apply { typeface = Typeface.MONOSPACE }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.label(name, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        val bar = SeekBar(context).apply {
            this.max = steps
            progress = ((get() - min) / step).roundToInt().coerceIn(0, steps)
            progressTintList = ColorStateList.valueOf(Ui.ACCENT); thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            minimumHeight = ui.px(40)
        }
        value.text = fmt(get())
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = min + p * step
                value.text = fmt(v)
                if (fromUser) set(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        list.addView(row.apply { setPadding(0, ui.px(8), 0, 0) })
        list.addView(bar)
    }

    fun toggle(name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        list.addView(Switch(context).apply {
            text = name; textSize = 14f; setTextColor(Ui.TEXT)
            isChecked = get()
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ACCENT, Color.LTGRAY))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.argb(140, 242, 183, 5), Color.DKGRAY))
            minimumHeight = ui.px(46)
            setOnCheckedChangeListener { _, v -> set(v) }
        })
    }

    fun choice(name: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
        list.addView(ui.label(name, 14f).apply { setPadding(0, ui.px(8), 0, ui.px(6)) })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<Button>()
        fun refresh() = buttons.forEachIndexed { i, b ->
            val on = i == get()
            b.background = ui.bg(if (on) Ui.ACCENT else Color.argb(200, 32, 38, 46), 22f)
            b.setTextColor(if (on) Color.BLACK else Ui.TEXT)
        }
        options.forEachIndexed { i, o -> buttons += ui.button(o) { set(i); refresh() }.also { row.addView(it) } }
        refresh()
        list.addView(row)
    }

    fun swatches(colors: IntArray, get: () -> Int, set: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.px(6), 0, ui.px(6)) }
        val views = ArrayList<View>()
        fun refresh() = views.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setStroke(ui.px(if (colors[i] == get()) 3 else 0), Color.WHITE)
        }
        for (c in colors) {
            val v = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
                setOnClickListener { set(c); refresh() }
            }
            views += v
            row.addView(v, LinearLayout.LayoutParams(ui.px(36), ui.px(36)).apply { marginEnd = ui.px(10) })
        }
        refresh()
        list.addView(row)
    }

    fun textField(name: String, get: () -> String, set: (String) -> Unit) {
        list.addView(ui.label(name, 14f).apply { setPadding(0, ui.px(8), 0, ui.px(4)) })
        list.addView(EditText(context).apply {
            setText(get()); setTextColor(Ui.TEXT); textSize = 15f; isSingleLine = true
            background = ui.bg(Color.argb(200, 32, 38, 46), 10f)
            setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { s?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(set) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }
}

/** Horizontally scrollable row of buttons. */
fun Ui.bar(ctx: Context, vararg views: View) = HorizontalScrollView(ctx).apply {
    isHorizontalScrollBarEnabled = false
    addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(12), 0, px(12), 0)
        views.forEach { addView(it) }
    })
}
