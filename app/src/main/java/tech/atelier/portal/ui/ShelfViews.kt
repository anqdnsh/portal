package tech.atelier.portal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import tech.atelier.portal.core.Action
import tech.atelier.portal.core.ShelfItem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun mix(c1: Int, c2: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * k).toInt(),
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * k).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * k).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * k).toInt(),
    )
}

private fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

/**
 * The window's root. Draws the breathing blue notch glow and the soft halo around the panel
 * (no border: the panel's edge is only light), then the panel itself on top.
 */
class ShelfRoot(context: Context, val pal: Palette) : FrameLayout(context), Choreographer.FrameCallback {

    var panel: View? = null
    /** 0 = hidden, 1 = fully lit. Driven by a spring from the window controller. */
    var glow = 0f
        set(v) { field = v; invalidate() }
    /** Extra brightness while something is dragged over the shelf. */
    var hot = 0f
        set(v) { field = v; invalidate() }
    /** The small handle at the top centre while the shelf is put away (0 hidden, 1 shown). */
    var pill = 0f
        set(v) { field = v; invalidate() }
    var pillHover = 0f
        set(v) { field = v; invalidate() }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var panelRadius = context.dp(22f)

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dp(3f)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.glow }
    private val edgeSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dp(5f)
    }
    private val rect = RectF()
    private val path = Path()
    private var pulsing = false
    private var pulse = 0.5f
    private var startNs = 0L
    private var shaderFor = -1

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    fun setPulsing(on: Boolean) {
        if (on == pulsing) return
        pulsing = on
        if (on) {
            startNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!pulsing) return
        if (startNs == 0L) startNs = frameTimeNanos
        val t = (frameTimeNanos - startNs) / 1e9f
        // One slow breath every 2.4 s.
        pulse = 0.5f + 0.5f * sin((2.0 * PI * t / 2.4).toFloat())
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        setPulsing(false)
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawPill(canvas)
        drawCorner(canvas)
        drawHalo(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawPill(c: Canvas) {
        if (pill <= 0.01f) return
        val cx = width / 2f
        val w = (context.dp(38f) + context.dp(18f) * pillHover) * (0.4f + 0.6f * pill)
        val h = context.dp(5f) + context.dp(1f) * pillHover
        val top = context.dp(7f)
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h)
        val idle = withAlpha(pal.sub, 0.55f)
        pillPaint.color = withAlpha(mix(idle, pal.accent, pillHover), pill * (0.75f + 0.25f * pillHover))
        pillPaint.setShadowLayer(context.dp(4f) + context.dp(8f) * pillHover, 0f, 0f, withAlpha(pal.glow, (0.35f + 0.55f * pillHover) * pill))
        c.drawRoundRect(rect, h / 2f, h / 2f, pillPaint)
    }

    /** The breathing blue light along the top edge, like light leaking from a notch. Just the edge, no haze. */
    private fun drawCorner(c: Canvas) {
        if (glow <= 0.001f) return
        val w = width.toFloat()
        val cx = w / 2f
        val half = min(w / 2f - context.dp(4f), context.dp(120f))
        val key = (half * 10).toInt()
        if (shaderFor != key) {
            shaderFor = key
            val g = LinearGradient(cx - half, 0f, cx + half, 0f,
                intArrayOf(withAlpha(pal.accent, 0f), withAlpha(pal.accent, 1f), withAlpha(pal.accent, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            edgePaint.shader = g
            edgeSoft.shader = g
        }
        val level = (glow * (0.6f + 0.4f * pulse) + hot * 0.3f).coerceIn(0f, 1f)
        val spread = half * (0.7f + 0.3f * glow)
        val y = context.dp(2f)
        edgeSoft.alpha = (level * 200).toInt()
        edgeSoft.setShadowLayer(context.dp(10f) + context.dp(6f) * pulse, 0f, 0f, withAlpha(pal.glow, 0.9f * level))
        c.drawLine(cx - spread, y, cx + spread, y, edgeSoft)
        edgePaint.alpha = (level * 255).toInt()
        c.drawLine(cx - spread, y, cx + spread, y, edgePaint)
    }

    private fun drawHalo(c: Canvas) {
        val p = panel ?: return
        if (p.visibility != VISIBLE || p.alpha <= 0.01f || p.width == 0) return
        c.save()
        c.translate(p.left + p.translationX, p.top + p.translationY)
        c.scale(p.scaleX, p.scaleY, p.pivotX, p.pivotY)
        rect.set(0f, 0f, p.width.toFloat(), p.height.toFloat())
        path.reset()
        path.addRoundRect(rect, panelRadius, panelRadius, Path.Direction.CW)
        c.clipOutPath(path)
        val a = p.alpha * (0.42f + 0.10f * pulse + 0.35f * hot)
        haloPaint.setShadowLayer(context.dp(18f), 0f, 0f, withAlpha(pal.glow, a.coerceIn(0f, 0.95f)))
        c.drawPath(path, haloPaint)
        haloPaint.setShadowLayer(context.dp(6f), 0f, 0f, withAlpha(pal.glow, (a * 0.8f).coerceIn(0f, 0.9f)))
        c.drawPath(path, haloPaint)
        c.restore()
    }
}

/** The glass panel: a rounded, borderless charcoal (or white) surface. */
class PanelLayout(context: Context, private val pal: Palette) : LinearLayout(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.panel }
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = if (pal.dark) 9 else 12
    }
    private val rect = RectF()
    val radius = context.dp(22f)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, grain)
    }

    companion object {
        /** Fine grey noise, like real frosted glass. Made once. */
        private val noise: Bitmap by lazy {
            val n = 96
            val px = IntArray(n * n)
            val r = java.util.Random(11)
            for (i in px.indices) {
                val v = r.nextInt(256)
                px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }
    }
}

/** Dot + "Portal" + item count. Tapping it opens Portal's settings. */
class TitleView(context: Context, private val pal: Palette) : View(context) {
    var count = 0
        set(v) { field = v; contentDescription = "Portal, $v items. Open settings"; invalidate() }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.text
        textSize = context.dp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.sub
        textSize = context.dp(11f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.accent
        setShadowLayer(context.dp(5f), 0f, 0f, pal.glow)
    }

    init { isClickable = true; isFocusable = true }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val r = context.dp(4.5f)
        c.drawCircle(r + context.dp(2f), cy, r, dot)
        val x = context.dp(16f)
        val base = cy - (title.descent() + title.ascent()) / 2f
        c.drawText("Portal", x, base, title)
        c.drawText(count.toString(), x + title.measureText("Portal") + context.dp(7f), base, sub)
    }
}

/** A small round icon button (pin, clear). */
class IconButtonView(context: Context, private val pal: Palette, var glyph: Glyph, label: String) : View(context) {
    var active = false
        set(v) { field = v; invalidate() }
    private var hover = 0f
    private val hoverSpring = Spring(Motion.POP_K, 0.9f) { hover = it; invalidate() }
    /** 0..1 while an item is dragged over this button (the bin): it swells and turns red. */
    private var danger = 0f
    private val dangerSpring = Spring(Motion.POP_K, Motion.POP_Z) {
        danger = it
        val s = 1f + 0.28f * it
        scaleX = s; scaleY = s
        invalidate()
    }
    fun setDragHover(on: Boolean) = dangerSpring.animateTo(if (on) 1f else 0f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Glyphs.paint(pal.text, 2f)
    private val rect = RectF()

    init {
        contentDescription = label
        isClickable = true
        isFocusable = true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> hoverSpring.animateTo(1f)
            MotionEvent.ACTION_HOVER_EXIT -> hoverSpring.animateTo(0f)
        }
        return super.onHoverEvent(event)
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = context.dp(10f)
        val base = if (active) pal.accentSoft else withAlpha(pal.tileHover, Color.alpha(pal.tileHover) / 255f * hover)
        bg.color = mix(base, 0xE6FF4D57.toInt(), danger)
        if (danger > 0.02f) bg.setShadowLayer(context.dp(12f) * danger, 0f, 0f, withAlpha(0xFFFF4D57.toInt(), 0.8f * danger.coerceAtMost(1f)))
        else bg.clearShadowLayer()
        c.drawRoundRect(rect, r, r, bg)
        icon.color = mix(if (active) pal.accent else pal.text, Color.WHITE, danger)
        Glyphs.draw(c, glyph, width / 2f, height / 2f, context.dp(15f), icon)
    }
}

/**
 * One quick-action target. Lights up and swells (on a spring) when something is dragged over it,
 * shows a spinning ring while its work runs.
 */
class ActionTile(context: Context, private val pal: Palette, action: Action) : View(context), Choreographer.FrameCallback {
    var action: Action = action
        set(v) { field = v; contentDescription = v.label; invalidate() }
    private var hover = 0f
    private var press = 0f
    private val hoverSpring = Spring(Motion.POP_K, Motion.POP_Z) { hover = it; applyScale(); invalidate() }
    private val pressSpring = Spring(Motion.POP_K, 0.8f) { press = it; applyScale() }
    var busy = false
        set(v) {
            field = v
            if (v) { spinStart = 0L; Choreographer.getInstance().postFrameCallback(this) } else Choreographer.getInstance().removeFrameCallback(this)
            invalidate()
        }
    private var spinStart = 0L
    private var spin = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Glyphs.paint(pal.text, 1.8f)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = pal.accent
    }
    private val rect = RectF()

    init {
        contentDescription = action.label
        isClickable = true
        isFocusable = true
    }

    private fun applyScale() {
        val s = 1f + 0.07f * hover - 0.05f * press
        scaleX = s; scaleY = s
    }

    fun setDragHover(on: Boolean) = hoverSpring.animateTo(if (on) 1f else 0f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressSpring.animateTo(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressSpring.animateTo(0f)
        }
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> hoverSpring.animateTo(0.45f)
            MotionEvent.ACTION_HOVER_EXIT -> hoverSpring.animateTo(0f)
        }
        return super.onHoverEvent(event)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!busy) return
        if (spinStart == 0L) spinStart = frameTimeNanos
        spin = ((frameTimeNanos - spinStart) / 1e9f * 360f) % 360f
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        busy = false
        hoverSpring.cancel(); pressSpring.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val inset = context.dp(2f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = context.dp(18f)
        val h = hover.coerceIn(0f, 1.2f)
        bg.color = mix(pal.tile, withAlpha(pal.glow, if (pal.dark) 0.34f else 0.18f), h)
        if (h > 0.02f) bg.setShadowLayer(context.dp(14f) * h, 0f, 0f, withAlpha(pal.glow, 0.75f * h.coerceAtMost(1f)))
        else bg.clearShadowLayer()
        c.drawRoundRect(rect, r, r, bg)
        icon.color = mix(pal.text, if (pal.dark) Color.WHITE else pal.accent, h)
        if (busy) {
            val rr = context.dp(11f)
            rect.set(width / 2f - rr, height / 2f - rr, width / 2f + rr, height / 2f + rr)
            c.drawArc(rect, spin, 260f, false, ring)
        } else {
            Glyphs.draw(c, glyphFor(action), width / 2f, height / 2f, context.dp(23f), icon)
        }
    }

    companion object {
        fun glyphFor(a: Action) = when (a) {
            Action.QUICK_SHARE -> Glyph.QUICK_SHARE
            Action.COMPRESS -> Glyph.COMPRESS
            Action.SHARE -> Glyph.SHARE
            Action.CONVERT -> Glyph.CONVERT
            Action.MERGE -> Glyph.MERGE
            Action.COPY -> Glyph.COPY
        }
    }
}

/** What an item tile reports back to the shelf. */
interface TileHost {
    fun isSelected(id: String): Boolean
    fun editing(): Boolean
    fun onTap(tile: ItemTile)
    fun onDoubleTap(tile: ItemTile)
    fun onSecondary(tile: ItemTile)
    fun onStartDrag(tile: ItemTile)
    fun onRemove(tile: ItemTile)
    fun onPin(tile: ItemTile)
}

/** One kept file or snippet: a preview (or a coloured type badge) and its name. */
class ItemTile(context: Context, private val pal: Palette, private val host: TileHost) : View(context) {
    var item: ShelfItem? = null
        set(v) {
            field = v
            thumb = null
            contentDescription = v?.name
            v?.let { load(it) }
            invalidate()
        }
    private var thumb: Bitmap? = null
    private var hover = 0f
    private var land = 1f
    private val hoverSpring = Spring(Motion.POP_K, 0.9f) { hover = it; invalidate() }
    private val landSpring = Spring(Motion.POP_K, Motion.POP_Z) { land = it; scaleX = 0.6f + 0.4f * it; scaleY = scaleX; alpha = it.coerceIn(0f, 1f) }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(9f)
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        textAlign = Paint.Align.CENTER
    }
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(9.5f)
        color = pal.sub
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cornerIcon = Glyphs.paint(Color.WHITE, 2.6f)
    private val rect = RectF()
    private val m = Matrix()

    private var downX = 0f
    private var downY = 0f
    private var dragStarted = false
    private var lastTap = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable { if (!dragStarted) { dragStarted = true; host.onStartDrag(this) } }

    init {
        isClickable = true
        isFocusable = true
    }

    fun landIn() {
        landSpring.snapTo(0f)
        landSpring.animateTo(1f)
    }

    private fun load(it: ShelfItem) {
        Thumbs.peek(it)?.let { b -> thumb = b; return }
        Thumbs.load(it, context.dpi(84f)) { b -> if (item?.id == it.id) { thumb = b; invalidate() } }
    }

    private fun badgeColors(it: ShelfItem): Pair<Int, Int> {
        val dark = pal.dark
        return when (it.ext) {
            "PDF" -> if (dark) 0x29FF7878 to 0xFFFFB4B4.toInt() else 0x1FD64040 to 0xFFA12A2A.toInt()
            "PPT", "PPTX", "KEY" -> if (dark) 0x29FFAA5A to 0xFFFFD3A6.toInt() else 0x24D68228 to 0xFF8A4B0C.toInt()
            "XLS", "XLSX", "CSV", "NUMB" -> if (dark) 0x246EDC96 to 0xFFB4F0C8.toInt() else 0x24289650 to 0xFF1D6B40.toInt()
            "DOC", "DOCX", "TXT", "RTF", "PAGE" -> if (dark) 0x298AB4FF to 0xFFC9D7FF.toInt() else 0x242F63E0 to 0xFF2453C7.toInt()
            "TEXT", "LINK" -> withAlpha(pal.glow, if (dark) 0.22f else 0.14f) to pal.accent
            else -> if (dark) 0x1FFFFFFF to 0xFFD5D9E0.toInt() else 0x1A14203C to 0xFF3A3F4C.toInt()
        }
    }

    override fun onDraw(c: Canvas) {
        val it = item ?: return
        val sel = host.isSelected(it.id)
        val p = context.dp(6f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = context.dp(13f)
        bg.color = when {
            sel -> withAlpha(pal.glow, if (pal.dark) 0.26f else 0.16f)
            else -> mix(pal.tile, pal.tileHover, hover)
        }
        if (sel) bg.setShadowLayer(context.dp(10f), 0f, 0f, withAlpha(pal.glow, 0.7f)) else bg.clearShadowLayer()
        c.drawRoundRect(rect, r, r, bg)

        // Preview area
        val th = height - p * 2 - context.dp(14f)
        rect.set(p, p, width - p, p + th)
        val tr = context.dp(8f)
        val b = thumb
        if (b != null) {
            val sx = rect.width() / b.width
            val sy = rect.height() / b.height
            val s = max(sx, sy)
            m.reset()
            m.setScale(s, s)
            m.postTranslate(rect.left + (rect.width() - b.width * s) / 2f, rect.top + (rect.height() - b.height * s) / 2f)
            val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(m)
            thumbPaint.shader = shader
            c.drawRoundRect(rect, tr, tr, thumbPaint)
        } else {
            val (fill, ink) = badgeColors(it)
            badge.color = fill
            c.drawRoundRect(rect, tr, tr, badge)
            badgeText.color = ink
            val label = if (it.isText && !it.isLink) "Aa" else it.ext
            c.drawText(label, rect.centerX(), rect.centerY() - (badgeText.descent() + badgeText.ascent()) / 2f, badgeText)
        }

        // Name
        name.color = if (sel) pal.text else pal.sub
        val shown = TextUtils.ellipsize(it.name.substringBeforeLast('.').ifEmpty { it.name }, name, width - p * 2, TextUtils.TruncateAt.END)
        c.drawText(shown, 0, shown.length, p, height - p - name.descent(), name)

        // Pinned mark (top-left), remove button when editing or hovered (top-right)
        val cr = context.dp(8f)
        if (it.pinned || host.editing()) {
            corner.color = if (it.pinned) pal.accent else withAlpha(Color.BLACK, 0.45f)
            c.drawCircle(cr + context.dp(1f), cr + context.dp(1f), cr, corner)
            cornerIcon.color = Color.WHITE
            Glyphs.draw(c, Glyph.PIN, cr + context.dp(1f), cr + context.dp(1f), context.dp(10f), cornerIcon)
        }
        if (host.editing() || hover > 0.5f) {
            corner.color = withAlpha(Color.BLACK, 0.55f)
            val x = width - cr - context.dp(1f)
            c.drawCircle(x, cr + context.dp(1f), cr, corner)
            Glyphs.draw(c, Glyph.CLOSE, x, cr + context.dp(1f), context.dp(10f), cornerIcon)
        }
    }

    private fun inRemove(x: Float, y: Float): Boolean {
        val s = context.dp(20f)
        return x > width - s && y < s
    }

    private fun inPin(x: Float, y: Float): Boolean {
        val s = context.dp(20f)
        return x < s && y < s
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> hoverSpring.animateTo(1f)
            MotionEvent.ACTION_HOVER_EXIT -> hoverSpring.animateTo(0f)
        }
        return super.onHoverEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val mouse = e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragStarted = false
                if (mouse && (e.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    host.onSecondary(this)
                    dragStarted = true // swallow the rest of this press
                    return true
                }
                if (!mouse) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragStarted && hypot(e.x - downX, e.y - downY) > slop) {
                    removeCallbacks(longPress)
                    dragStarted = true
                    // Mouse: press and pull starts a drag right away, like a desktop.
                    // Finger: moving before the long-press is a scroll, so leave it to the list.
                    if (mouse) host.onStartDrag(this)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!dragStarted) {
                    when {
                        (host.editing() || hover > 0.5f) && inRemove(e.x, e.y) -> host.onRemove(this)
                        host.editing() && inPin(e.x, e.y) -> host.onPin(this)
                        e.eventTime - lastTap < ViewConfiguration.getDoubleTapTimeout() -> { lastTap = 0L; host.onDoubleTap(this) }
                        else -> { lastTap = e.eventTime; host.onTap(this) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        hoverSpring.cancel(); landSpring.cancel()
        super.onDetachedFromWindow()
    }
}

/** Places item tiles in a three-column grid. */
class ItemGrid(context: Context) : ViewGroup(context) {
    val gap = context.dpi(8f)
    val tileH = context.dpi(66f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cw = (w - gap * 2) / 3
        var shown = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            ch.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY))
            shown++
        }
        val rows = (shown + 2) / 3
        setMeasuredDimension(w, if (rows == 0) 0 else rows * tileH + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (width - gap * 2) / 3
        var k = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            val col = k % 3
            val row = k / 3
            val x = col * (cw + gap)
            val y = row * (tileH + gap)
            ch.layout(x, y, x + cw, y + tileH)
            ch.pivotX = cw / 2f; ch.pivotY = tileH / 2f
            k++
        }
    }
}

/** A scroll view that never grows past [maxH]. */
class MaxHeightScroll(context: Context, private val maxH: Int) : ScrollView(context) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

/** The "drop files here" area shown while the shelf is empty. Glows a little brighter under a drag. */
class EmptyHint(context: Context, private val pal: Palette) : View(context) {
    var hot = 0f
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(11f)
        color = pal.sub
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = context.dp(14f)
        fill.color = withAlpha(pal.glow, (if (pal.dark) 0.08f else 0.06f) + 0.14f * hot)
        c.drawRoundRect(rect, r, r, fill)
        text.color = mix(pal.sub, pal.accent, hot)
        c.drawText("Drop files here", width / 2f, height / 2f - (text.descent() + text.ascent()) / 2f, text)
    }
}

internal fun dist(a: Float, b: Float) = abs(a - b)
internal fun lerpF(a: Float, b: Float, t: Float) = lerp(a, b, t)

/**
 * The shelf's top row. Grab it and push up to tuck the shelf back into the notch: the shelf
 * follows your finger or mouse, and a flick or a long enough push sends it home on a spring.
 */
class GrabHeader(
    context: Context,
    private val onPull: (dy: Float) -> Unit,
    private val onRelease: (dy: Float, vy: Float) -> Unit,
) : LinearLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var pulling = false
    private var vt: android.view.VelocityTracker? = null

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY; downX = e.rawX; pulling = false
                vt?.recycle(); vt = android.view.VelocityTracker.obtain()
                vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - downY
                if (!pulling && abs(dy) > slop && abs(dy) > abs(e.rawX - downX)) {
                    pulling = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return pulling
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> { if (pulling) onPull(e.rawY - downY); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    vt?.computeCurrentVelocity(1000)
                    onRelease(e.rawY - downY, vt?.yVelocity ?: 0f)
                }
                pulling = false
                vt?.recycle(); vt = null
                return true
            }
        }
        return true
    }
}
