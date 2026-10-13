package com.coorlay.coolray

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.coorlay.UiText.tr
import kotlin.math.cos
import kotlin.math.sin

/** Four real, focusable controls over a lightweight API-18-compatible wallpaper. */
class CoorlayHomeView(context: Context, actions: List<() -> Unit>) : ViewGroup(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cards: List<ConnectionCard>
    private val titles = listOf(tr("Беспроводное\nподключение", "Wireless\nconnection"), tr("Подключение\nпо USB", "USB\nconnection"),
        tr("Настройки", "Settings"), tr("Выход", "Exit"))
    /** Small and muted in the bottom-right corner: only testers need it (agreed 2026-10-13). */
    private val versionLabel = TextView(context).apply {
        text = context.getString(R.string.app_version, BuildConfig.VERSION_NAME)
        setTextColor(0xff8f9aab.toInt())
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setShadowLayer(TEXT_SHADOW_RADIUS, 0f, 2f, TEXT_SHADOW)
    }
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var subtitle = tr("Выберите способ подключения", "Choose how to connect")

    init {
        setWillNotDraw(false)
        cards = titles.mapIndexed { index, title ->
            ConnectionCard(context, index, title).apply {
                id = View.generateViewId()
                setOnClickListener { actions[index]() }
                addView(this)
            }
        }
        addView(versionLabel)
    }

    /**
     * The menu over a running CarPlay ([wireless] = its transport): that card is the only one lit and reads
     * «Вернуться в CarPlay». null: the plain menu, no card lit until it is pressed or focused.
     */
    fun carPlayRunning(wireless: Boolean?) {
        val running = when (wireless) { null -> -1; true -> 0; false -> 1 }
        cards.forEachIndexed { index, card ->
            card.highlighted = index == running
            card.title = if (index == running) tr("Вернуться\nв CarPlay", "Back to\nCarPlay") else titles[index]
        }
        subtitle = when (wireless) {
            null -> tr("Выберите способ подключения", "Choose how to connect")
            true -> tr("CarPlay работает · без провода", "CarPlay is running · wireless")
            false -> tr("CarPlay работает · по USB", "CarPlay is running · USB")
        }
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        scale = minOf(w / 1920f, h / 640f)
        offsetX = (w - 1920f * scale) / 2f
        offsetY = (h - 640f * scale) / 2f
        cards.forEach {
            it.measure(MeasureSpec.makeMeasureSpec((382 * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((218 * scale).toInt(), MeasureSpec.EXACTLY))
        }
        versionLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * scale)
        versionLabel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        cards.forEachIndexed { index, card ->
            val x = (offsetX + (163 + index * 404) * scale).toInt()
            val y = (offsetY + 350 * scale).toInt()
            card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
        }
        // The view's own corner, not the 1920 × 640 design area (that sits higher on a taller screen).
        val margin = (24 * scale).toInt()
        val versionRight = right - left - margin
        val versionBottom = bottom - top - margin
        versionLabel.layout(versionRight - versionLabel.measuredWidth, versionBottom - versionLabel.measuredHeight,
            versionRight, versionBottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        // Read on every draw: the driver can change the background in settings (cached there).
        val wallpaper = CoorlayStyle.wallpaper(context)
        if (wallpaper != null) {
            val ratio = maxOf(width.toFloat() / wallpaper.width, height.toFloat() / wallpaper.height)
            val w = wallpaper.width * ratio
            val h = wallpaper.height * ratio
            canvas.drawBitmap(wallpaper, null, RectF((width - w) / 2, (height - h) / 2,
                (width + w) / 2, (height + h) / 2), paint)
            // The built-in picture is dark by design; a chosen one may be bright, so dim it for the white text.
            if (CoorlayWallpaper.showingCustom) canvas.drawColor(CUSTOM_DIM)
        } else canvas.drawColor(Color.rgb(9, 16, 26))
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(0x1806101b, 0x0806101b, 0x8c040810.toInt()), floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        paint.shader = null
        // A chosen background may be light: a soft dark shadow keeps the title and hint readable.
        paint.setShadowLayer(TEXT_SHADOW_RADIUS, 0f, 3f, TEXT_SHADOW)
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 96f
        val first = paint.measureText("coor")
        val second = paint.measureText("lay")
        val start = 945f - (first + second) / 2
        paint.shader = LinearGradient(0f, 92f, 0f, 181f, Color.WHITE, 0xffbec8d6.toInt(), Shader.TileMode.CLAMP)
        canvas.drawText("coor", start, 176f, paint)
        paint.shader = LinearGradient(0f, 95f, 0f, 188f, 0xffff3c4e.toInt(), 0xffbd101d.toInt(), Shader.TileMode.CLAMP)
        canvas.drawText("lay", start + first, 176f, paint)
        paint.shader = null
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 30f
        paint.color = 0xffbac4d3.toInt()
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(subtitle, 945f, 229f, paint)
        paint.clearShadowLayer()
        canvas.restore()
    }

    private companion object {
        const val TEXT_SHADOW_RADIUS = 8f
        const val TEXT_SHADOW = 0xb3000000.toInt()
        const val CUSTOM_DIM = 0x4d000000
    }

    private class ConnectionCard(context: Context, private val kind: Int, title: String) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        var highlighted = false
            set(value) { field = value; invalidate() }
        var title = title
            set(value) { field = value; contentDescription = value.replace('\n', ' '); invalidate() }

        init {
            isClickable = true
            isFocusable = true
            contentDescription = title.replace('\n', ' ')
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }

        override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
            super.onInitializeAccessibilityEvent(event)
            event.className = "android.widget.Button"
        }

        override fun onDraw(canvas: Canvas) {
            val sx = width / 382f
            val sy = height / 218f
            canvas.save()
            canvas.scale(sx, sy)
            val active = highlighted || isPressed || isFocused
            val rect = RectF(5f, 5f, 377f, 208f)
            paint.reset(); paint.isAntiAlias = true
            paint.shader = LinearGradient(0f, 0f, 382f, 218f,
                if (active) intArrayOf(0xee152335.toInt(), 0xef0c1420.toInt(), 0xef162c50.toInt())
                else intArrayOf(0xef252b35.toInt(), 0xf013171f.toInt(), 0xee30262a.toInt()),
                null, Shader.TileMode.CLAMP)
            paint.setShadowLayer(if (active) 13f else 7f, 0f, 5f,
                if (active) 0x9a078aff.toInt() else 0x66000000)
            canvas.drawRoundRect(rect, 20f, 20f, paint)
            paint.clearShadowLayer()
            paint.shader = LinearGradient(0f, 5f, 0f, 210f,
                if (active) 0xff43b8ff.toInt() else 0xff737b8b.toInt(),
                if (active) 0xff4faeff.toInt() else 0xffbeaaa0.toInt(), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = if (active) 1.8f else 1.1f
            canvas.drawRoundRect(rect, 20f, 20f, paint)
            paint.shader = null
            paint.color = if (active) 0xff9ae2ff.toInt() else 0xfff1f4f8.toInt()
            if (active) paint.setShadowLayer(14f, 0f, 0f, 0xff219cff.toInt())
            canvas.save(); canvas.translate(191f, 75f)
            drawIcon(canvas)
            canvas.restore()
            paint.clearShadowLayer(); paint.shader = null
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 27f; paint.textAlign = Paint.Align.CENTER
            val lines = title.split('\n')
            if (lines.size == 2) {
                canvas.drawText(lines[0], 191f, 155f, paint)
                canvas.drawText(lines[1], 191f, 187f, paint)
            } else canvas.drawText(title, 191f, 174f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            path.reset(); path.moveTo(346f, 157f); path.lineTo(357f, 169f); path.lineTo(346f, 181f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        private fun drawIcon(canvas: Canvas) {
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            when (kind) {
                0 -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 10f
                    canvas.drawArc(RectF(-49f, -27f, 49f, 62f), 222f, 96f, false, paint)
                    canvas.drawArc(RectF(-30f, -7f, 30f, 47f), 220f, 100f, false, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(0f, 28f, 9f, paint)
                }
                1 -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f
                    path.reset(); path.moveTo(0f, 29f); path.lineTo(0f, -34f)
                    path.moveTo(0f, 9f); path.cubicTo(-26f, 2f, -23f, 1f, -23f, -14f)
                    path.moveTo(0f, -2f); path.cubicTo(24f, -9f, 23f, -12f, 23f, -25f)
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                    path.reset(); path.moveTo(0f, -46f); path.lineTo(-10f, -28f); path.lineTo(10f, -28f); path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawCircle(0f, 32f, 9f, paint); canvas.drawCircle(-23f, -15f, 7f, paint)
                    canvas.drawRect(17f, -31f, 29f, -19f, paint)
                }
                2 -> {
                    paint.style = Paint.Style.FILL
                    path.reset(); path.fillType = Path.FillType.EVEN_ODD
                    for (i in 0 until 48) {
                        val angle = i * Math.PI * 2 / 48
                        val radius = if (i % 6 in 1..4) 39f else 30f
                        val x = (cos(angle) * radius).toFloat(); val y = (sin(angle) * radius).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); path.addCircle(0f, 0f, 15f, Path.Direction.CCW)
                    canvas.drawPath(path, paint)
                }
                else -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f
                    path.reset(); path.moveTo(6f, -17f); path.lineTo(6f, -31f)
                    path.quadTo(6f, -36f, 0f, -36f); path.lineTo(-32f, -36f)
                    path.lineTo(-32f, 35f); path.lineTo(0f, 35f); path.quadTo(6f, 35f, 6f, 29f); path.lineTo(6f, 18f)
                    path.moveTo(-4f, 0f); path.lineTo(39f, 0f)
                    path.moveTo(25f, -14f); path.lineTo(39f, 0f); path.lineTo(25f, 14f)
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                    path.reset(); path.moveTo(-31f, -36f); path.lineTo(-13f, -27f)
                    path.lineTo(-13f, 27f); path.lineTo(-31f, 35f); path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}
