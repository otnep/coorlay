package com.coorlay.coolray

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import com.coorlay.UiText.tr

/** Shared native controls for API 18, using the home screen's wallpaper and palette. */
internal object CoorlayStyle {
    const val TEXT = 0xfff1f4f8.toInt()
    const val MUTED = 0xffbac4d3.toInt()
    const val BLUE = 0xff43b8ff.toInt()
    fun wallpaper(context: Context): Bitmap? = CoorlayWallpaper.bitmap(context)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(context: Context, active: Boolean = false, danger: Boolean = false): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (active) intArrayOf(0xee152f48.toInt(), 0xf00c192a.toInt())
            else intArrayOf(0xef252b35.toInt(), 0xf013171f.toInt())).apply {
            cornerRadius = dp(context, 14).toFloat()
            setStroke(dp(context, 1).coerceAtLeast(1),
                if (danger) 0xff925260.toInt() else if (active) BLUE else 0xff556171.toInt())
        }
    fun clickable(context: Context, primary: Boolean = false, danger: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), surface(context, true, danger))
        addState(intArrayOf(android.R.attr.state_focused), surface(context, true, danger))
        addState(intArrayOf(android.R.attr.state_selected), surface(context, true, danger))
        addState(intArrayOf(), surface(context, primary, danger))
    }
}

internal class CoorlayPanel(private val activity: Activity, title: String, subtitle: String = "") {
    private companion object {
        /** Room below the content for the OEM bar (it covers the screen's bottom edge). */
        const val BOTTOM_ROOM = 96
        /** A field moved up for the keyboard keeps its title (and a little air) visible above it. */
        const val FIELD_TITLE_ROOM = 48
    }
    private var backAction: (() -> Unit)? = null
    val dialog = object : Dialog(activity) {
        override fun onBackPressed() { goBack() }
    }
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        // The OEM climate/app bar covers the bottom of the screen: the end of every list must be
        // able to scroll above it.
        setPadding(0, 0, dp(14), dp(BOTTOM_ROOM))
    }
    private val footer = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    private val scroll = ScrollView(activity).apply {
        isFillViewport = false
        // An always visible scrollbar and a fading edge show that more settings follow below.
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(32))
        addView(body)
    }
    private fun dp(value: Int) = CoorlayStyle.dp(activity, value)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(9, 16, 26)) }
        root.addView(Wallpaper(activity), FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(16))
        }
        val metrics = activity.resources.displayMetrics
        root.addView(column, FrameLayout.LayoutParams(minOf(metrics.widthPixels, dp(1496)), -1, Gravity.CENTER))
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val brand = SpannableString("coorlay  /  $title").apply {
            setSpan(ForegroundColorSpan(0xffff3c4e.toInt()), 4, 7, 0)
        }
        heading.addView(label(brand, 28f, true))
        if (subtitle.isNotEmpty()) heading.addView(label(subtitle, 15f).apply { setPadding(0, dp(4), 0, 0) })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        // Actions sit in the header: on the head unit the OEM climate/app bar covers the screen's
        // bottom edge, where a footer row was hidden.
        header.addView(footer, LinearLayout.LayoutParams(-2, -2))
        header.addView(button(tr("‹  Назад", "‹  Back")) { goBack() }.apply { setSingleLine(true) },
            LinearLayout.LayoutParams(-2, dp(54)).apply { leftMargin = dp(10) })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            // A FLAG_FULLSCREEN window ignores adjustResize and is panned instead: the keyboard pushed the
            // header with «Start hotspot»/«Back» off the screen (2026-10-09 video). The window stays put and
            // the focused field is scrolled up under the header, above the keyboard ([reveal]).
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }
        // Takes the focus back from a field when its keyboard is done, and first when the panel opens,
        // so no field grabs the focus (and the keyboard) by itself.
        body.isFocusableInTouchMode = true
        scroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused is EditText) reveal(focused) else setBottomRoom(dp(BOTTOM_ROOM))
        }
    }

    /** Moves [field] (with its title) to the top of the list, the part the keyboard leaves visible. */
    private fun reveal(field: View) {
        // Room below the last field so it, too, can scroll up to the top.
        val resized = setBottomRoom(maxOf(dp(BOTTOM_ROOM), scroll.height))
        val move = Runnable {
            var top = 0
            var view: View = field
            while (view !== body) { top += view.top; view = view.parent as? View ?: return@Runnable }
            scroll.smoothScrollTo(0, (top - dp(FIELD_TITLE_ROOM)).coerceAtLeast(0))
        }
        if (resized) afterLayout { move.run() } else move.run()
    }
    /** Returns whether the room changed (the body is laid out again before it can scroll that far). */
    private fun setBottomRoom(room: Int): Boolean {
        if (body.paddingBottom == room) return false
        body.setPadding(0, 0, dp(14), room)
        return true
    }
    private fun afterLayout(action: () -> Unit) {
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                @Suppress("DEPRECATION") scroll.viewTreeObserver.removeGlobalOnLayoutListener(this)
                action()
            }
        })
    }
    /** «Done» on the keyboard hides it and gives the list its usual end back. */
    private fun finishEditing(field: EditText) {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(field.windowToken, 0)
        body.requestFocus()
    }

    fun show() {
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    /** Shows the end of the content, e.g. the newest journal lines. */
    fun scrollToEnd() {
        // After the dialog's first layout; before it the body has no height to scroll to.
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (body.height == 0) return
                @Suppress("DEPRECATION") scroll.viewTreeObserver.removeGlobalOnLayoutListener(this)
                scroll.scrollTo(0, body.height)
            }
        })
    }
    fun close() = dialog.dismiss()
    fun backTo(action: () -> Unit) { backAction = action }
    private fun goBack() { close(); backAction?.invoke() }
    fun label(text: CharSequence, size: Float = 16f, strong: Boolean = false) = TextView(activity).apply {
        this.text = text; textSize = size
        setTextColor(if (strong) CoorlayStyle.TEXT else CoorlayStyle.MUTED)
        if (strong) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    fun button(text: String, primary: Boolean = false, danger: Boolean = false, action: () -> Unit) = Button(activity).apply {
        this.text = text; textSize = 18f; isAllCaps = false
        setTextColor(if (danger) 0xffffaab6.toInt() else CoorlayStyle.TEXT)
        background = CoorlayStyle.clickable(activity, primary, danger)
        setPadding(dp(20), dp(8), dp(20), dp(8)); minimumHeight = dp(52)
        setOnClickListener { action() }
    }
    fun action(text: String, primary: Boolean = false, danger: Boolean = false, close: Boolean = true, action: () -> Unit) {
        footer.addView(button(text, primary, danger) { if (close) this.close(); action() }.apply { setSingleLine(true) },
            LinearLayout.LayoutParams(-2, dp(54)).apply { leftMargin = dp(10) })
    }
    fun section(title: String, parent: LinearLayout = body): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; background = CoorlayStyle.surface(activity)
            setPadding(dp(24), dp(18), dp(24), dp(20))
        }
        card.addView(label(title, 22f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return card
    }
    fun field(parent: LinearLayout, title: String, value: String, password: Boolean = false): EditText {
        parent.addView(label(title, 15f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(6) })
        return EditText(activity).apply {
            setSingleLine(true); textSize = 19f; setTextColor(CoorlayStyle.TEXT); setHintTextColor(CoorlayStyle.MUTED)
            background = CoorlayStyle.clickable(activity)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
            minimumHeight = dp(56)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { finishEditing(this); true } else false
            }
            // A tap on the focused field after the keyboard was closed with Back brings it up again.
            setOnClickListener { reveal(this) }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun toggle(parent: LinearLayout, title: String, description: String, checked: Boolean): Switch {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12)); isClickable = true; isFocusable = true
        }
        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(20), 0) }
        labels.addView(label(title, 20f, true))
        labels.addView(label(description, 15f).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(activity).apply {
            isChecked = checked; contentDescription = title; switchMinWidth = dp(64)
            textOn = ""; textOff = ""
            thumbDrawable = GradientDrawable().apply {
                setColor(CoorlayStyle.TEXT); cornerRadius = dp(14).toFloat(); setSize(dp(26), dp(26))
            }
            trackDrawable = StateListDrawable().apply {
                fun track(color: Int) = GradientDrawable().apply {
                    setColor(color); cornerRadius = dp(14).toFloat(); setSize(dp(64), dp(28))
                }
                addState(intArrayOf(android.R.attr.state_checked), track(0xff167eb7.toInt()))
                addState(intArrayOf(), track(0xff465163.toInt()))
            }
        }
        row.addView(control, LinearLayout.LayoutParams(-2, dp(48)))
        row.setOnClickListener { control.toggle() }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return control
    }
    fun card(title: String, description: String, kind: Int? = null, action: () -> Unit): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20)); background = CoorlayStyle.clickable(activity)
        minimumHeight = dp(112)
        isClickable = true; isFocusable = true; contentDescription = "$title. $description"
        if (kind != null) addView(Symbol(activity, kind), LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(18) })
        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label("$title  ›", 23f, true))
        if (description.isNotEmpty()) labels.addView(label(description, 15f).apply { setPadding(0, dp(8), 0, 0) })
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { close(); action() }
    }
    fun item(title: String, description: String = "", action: () -> Unit) {
        body.addView(card(title, description, action = action), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    fun message(text: String) { body.addView(label(text, 18f).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }) }
    /** An explanatory line inside a section. */
    fun note(parent: LinearLayout, text: String) {
        parent.addView(label(text, 16f).apply { setLineSpacing(0f, 1.1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
    }
    /** Buttons side by side across a section; each keeps its panel open unless the action closes it. */
    fun buttons(parent: LinearLayout, vararg entries: Pair<String, () -> Unit>, primary: Int = -1) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        entries.forEachIndexed { index, (text, action) ->
            row.addView(button(text, primary = index == primary) { action() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(12) })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    /** One option of a single choice (e.g. which iPhone); [selected] marks the current one. */
    fun choice(parent: LinearLayout, title: String, description: String, selected: Boolean,
        markText: String = tr("✓  Выбран", "✓  Selected"), action: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14)); minimumHeight = dp(64)
            background = CoorlayStyle.clickable(activity); isClickable = true; isFocusable = true
            isSelected = selected
            setOnClickListener { action() }
        }
        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(title, 19f, true))
        if (description.isNotEmpty()) labels.addView(label(description, 15f).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val mark = label(markText, 17f, true).apply { setTextColor(CoorlayStyle.BLUE) }
        row.addView(mark)
        row.tag = mark
        chosen(row, selected)
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return row
    }
    /** Marks a [choice] row as the current option (or not) without rebuilding the panel. */
    fun chosen(row: View, selected: Boolean) {
        row.isSelected = selected
        (row.tag as? View)?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private class Symbol(context: Context, private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CoorlayStyle.BLUE; style = Paint.Style.STROKE; strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.scale(width / 48f, height / 48f)
            path.reset()
            when (kind) {
                0 -> {
                    canvas.drawRoundRect(RectF(4f, 9f, 44f, 37f), 5f, 5f, paint)
                    canvas.drawLine(17f, 43f, 31f, 43f, paint); canvas.drawLine(24f, 37f, 24f, 43f, paint)
                    path.moveTo(15f, 29f); path.lineTo(23f, 17f); path.lineTo(32f, 29f); canvas.drawPath(path, paint)
                }
                1 -> {
                    path.moveTo(24f, 40f); path.lineTo(24f, 6f); path.moveTo(19f, 12f); path.lineTo(24f, 6f); path.lineTo(29f, 12f)
                    path.moveTo(24f, 29f); path.lineTo(11f, 21f); path.lineTo(11f, 14f)
                    path.moveTo(24f, 23f); path.lineTo(37f, 16f); path.lineTo(37f, 10f)
                    canvas.drawPath(path, paint); canvas.drawCircle(24f, 41f, 4f, paint); canvas.drawCircle(11f, 12f, 3f, paint)
                    canvas.drawRect(34f, 5f, 40f, 11f, paint)
                }
                2 -> {
                    canvas.drawArc(RectF(3f, 9f, 45f, 48f), 222f, 96f, false, paint)
                    canvas.drawArc(RectF(11f, 19f, 37f, 43f), 222f, 96f, false, paint)
                    canvas.drawCircle(24f, 36f, 2f, paint)
                }
                3 -> {
                    path.moveTo(24f, 4f); path.lineTo(36f, 15f); path.lineTo(12f, 35f)
                    path.moveTo(12f, 13f); path.lineTo(36f, 33f); path.lineTo(24f, 44f); path.lineTo(24f, 4f)
                    canvas.drawPath(path, paint)
                }
                4 -> {
                    canvas.drawRoundRect(RectF(8f, 4f, 40f, 44f), 4f, 4f, paint)
                    for (y in listOf(15f, 24f, 33f)) canvas.drawLine(16f, y, 32f, y, paint)
                }
                6 -> {
                    canvas.drawCircle(24f, 24f, 19f, paint); canvas.drawCircle(24f, 27f, 5f, paint)
                    path.moveTo(6f, 22f); path.quadTo(24f, 15f, 42f, 22f)
                    path.moveTo(24f, 32f); path.lineTo(24f, 43f)
                    canvas.drawPath(path, paint)
                }
                else -> {
                    path.moveTo(24f, 4f); path.lineTo(24f, 29f); path.moveTo(15f, 21f); path.lineTo(24f, 30f); path.lineTo(33f, 21f)
                    path.moveTo(7f, 31f); path.lineTo(7f, 42f); path.lineTo(41f, 42f); path.lineTo(41f, 31f)
                    canvas.drawPath(path, paint)
                }
            }
            canvas.restore()
        }
    }

    private class Wallpaper(context: Context) : View(context) {
        private val bitmap = CoorlayStyle.wallpaper(context)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            bitmap?.let {
                val ratio = maxOf(width.toFloat() / it.width, height.toFloat() / it.height)
                val w = it.width * ratio; val h = it.height * ratio
                canvas.drawBitmap(it, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
            }
            canvas.drawColor(if (CoorlayWallpaper.showingCustom) 0xe608101c.toInt() else 0xc908101c.toInt())
        }
    }
}
