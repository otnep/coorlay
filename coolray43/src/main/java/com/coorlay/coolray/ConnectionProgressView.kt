package com.coorlay.coolray

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.coorlay.UiText.tr

/**
 * A connection as the driver sees it (layout «A», agreed 2026-10-13): title and «Step N of M», the mode line, a bar
 * of one segment per step, and below it only the current step and what happens now. Buttons only when something is
 * asked of the driver. The same card tells that a running CarPlay went away ([message]). It covers the home screen
 * (not CarPlay) and blocks taps on the cards behind it.
 */
internal class ConnectionProgressView(context: Context) : FrameLayout(context) {
    private fun dp(value: Int) = CoorlayStyle.dp(context, value)
    private val title = label(26f, strong = true)
    private val counter = label(17f)
    /** How the iPhone joins: Wi-Fi Direct or the head-unit hotspot (the driver asked to see it, 2026-10-12). */
    private val modeLine = label(19f).apply { setTextColor(CoorlayStyle.BLUE) }
    private val segments = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val spinner = BusySpinner(context)
    private val failedMark = label(22f, strong = true).apply { text = "✕"; setTextColor(DANGER) }
    private val stepName = label(22f, strong = true)
    private val stepRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val detail = label(18f).apply { setLineSpacing(0f, 1.15f) }
    private val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    private val card = LinearLayout(context)
    private var steps = emptyList<String>()
    private var current = -1
    private var failedStep = -1
    /** What the head unit's Back key does now: the card's first button. */
    private var backAction: (() -> Unit)? = null

    init {
        isClickable = true // taps must not reach the home cards behind the card
        setBackgroundColor(0x99050a12.toInt())
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(32), dp(24), dp(32), dp(24))
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(counter, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        card.addView(header, LinearLayout.LayoutParams(-1, -2))
        card.addView(modeLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        card.addView(segments, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(18); bottomMargin = dp(18) })
        stepRow.addView(spinner, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
        stepRow.addView(failedMark, LinearLayout.LayoutParams(dp(26), -2).apply { rightMargin = dp(14) })
        stepRow.addView(stepName, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(stepRow, LinearLayout.LayoutParams(-1, -2))
        // Under the step name, past the spinner.
        card.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); leftMargin = dp(40) })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        // Upper part of the screen: the OEM climate bar covers the bottom edge.
        addView(card, LayoutParams(dp(820), -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(40) })
        visibility = View.GONE
    }

    val showing get() = visibility == View.VISIBLE

    /** A new connection: every step still ahead, «Отмена» only. [mode] is the line under the title (none for USB). */
    fun start(heading: String, steps: List<String>, onCancel: () -> Unit, mode: String? = null) {
        this.steps = steps
        current = -1; failedStep = -1
        title.text = heading
        mode(mode)
        card.background = CoorlayStyle.surface(context, active = true)
        segments.visibility = View.VISIBLE; stepRow.visibility = View.VISIBLE
        segments.removeAllViews()
        steps.indices.forEach { index ->
            segments.addView(View(context), LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        show(0, tr("Подготовка…", "Preparing…"))
        setButtons(tr("Отмена", "Cancel") to onCancel)
        visibility = View.VISIBLE
    }

    /** The wireless mode of this attempt, e.g. «Режим: Wi-Fi Direct»; null hides the line. */
    fun mode(text: String?) {
        modeLine.text = text.orEmpty()
        modeLine.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Step [index] runs now; the ones before it are done. Never goes back, so late status lines cannot undo progress. */
    fun step(index: Int, now: String) {
        if (!showing || failedStep >= 0 || index !in steps.indices) return
        if (index >= current) show(index, now) else detail.text = now
    }

    /** What happens within the current step (e.g. a quiet retry); the steps stay as they are. */
    fun note(now: String) { if (showing && failedStep < 0) detail.text = now }

    /** Only for problems the driver can act on: plain words, what to do, and the way on (the last button is the main one). */
    fun failed(message: String, vararg actions: Pair<String, () -> Unit>) {
        if (!showing) return
        title.text = tr("Не удалось подключиться", "Could not connect")
        card.background = CoorlayStyle.surface(context, active = true, danger = true)
        failedStep = current.coerceAtLeast(0)
        paintSegments()
        spinner.visibility = View.GONE; failedMark.visibility = View.VISIBLE
        stepName.setTextColor(DANGER)
        detail.text = message
        setButtons(*actions)
    }

    /** A message without steps, e.g. «CarPlay отключён» after the picture went away; stays until a button is pressed. */
    fun message(heading: String, text: String, vararg actions: Pair<String, () -> Unit>) {
        steps = emptyList(); current = -1; failedStep = -1
        title.text = heading
        mode(null)
        card.background = CoorlayStyle.surface(context, active = true)
        counter.text = ""
        segments.visibility = View.GONE; stepRow.visibility = View.GONE
        spinner.visibility = View.GONE
        detail.text = text
        setButtons(*actions)
        visibility = View.VISIBLE
    }

    /** The head unit's Back key: the same as the card's first button. */
    fun back() { backAction?.invoke() }

    fun hide() { visibility = View.GONE }

    /** Back after a question asked over it (e.g. which phone), with the steps as they were. */
    fun show() { visibility = View.VISIBLE }

    private fun show(index: Int, now: String) {
        current = index
        counter.text = tr("Шаг ${index + 1} из ${steps.size}", "Step ${index + 1} of ${steps.size}")
        stepName.text = steps[index]
        stepName.setTextColor(CoorlayStyle.TEXT)
        spinner.visibility = View.VISIBLE; failedMark.visibility = View.GONE
        detail.text = now
        paintSegments()
    }

    private fun paintSegments() {
        for (index in 0 until segments.childCount) {
            val color = when {
                index == failedStep -> DANGER
                index < current -> CoorlayStyle.TEXT
                index == current -> CoorlayStyle.BLUE
                else -> AHEAD
            }
            segments.getChildAt(index).background = GradientDrawable().apply { setColor(color); cornerRadius = dp(3).toFloat() }
        }
    }

    private fun setButtons(vararg entries: Pair<String, () -> Unit>) {
        buttons.removeAllViews()
        backAction = entries.firstOrNull()?.second
        entries.forEachIndexed { index, (text, action) ->
            buttons.addView(Button(context).apply {
                this.text = text; textSize = 18f; isAllCaps = false; setSingleLine(true)
                setTextColor(CoorlayStyle.TEXT)
                background = CoorlayStyle.clickable(context, primary = index == entries.lastIndex && entries.size > 1)
                setPadding(dp(26), dp(8), dp(26), dp(8)); minimumHeight = dp(52)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(54)).apply { leftMargin = dp(12) })
        }
    }

    private fun label(size: Float, strong: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(if (strong) CoorlayStyle.TEXT else CoorlayStyle.MUTED)
        if (strong) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private companion object {
        const val DANGER = 0xffffaab6.toInt()
        const val AHEAD = 0xff465163.toInt()
    }
}

/** A turning arc in the coorlay blue: API 18 cannot tint the platform ProgressBar. */
internal class BusySpinner(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = CoorlayStyle.BLUE
    }
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth = width / 8f
        val inset = paint.strokeWidth / 2
        bounds.set(inset, inset, width - inset, height - inset)
        val angle = (SystemClock.uptimeMillis() % 1000L) * 360f / 1000f
        canvas.drawArc(bounds, angle, 270f, false, paint)
        // Only while it is on the screen: a hidden card must not keep the UI thread busy.
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) invalidate()
    }
}
