package tech.atelier.portal.host

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.OnReceiveContentListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tech.atelier.portal.MainActivity
import tech.atelier.portal.core.Action
import tech.atelier.portal.core.Actions
import tech.atelier.portal.core.Prefs
import tech.atelier.portal.core.ShelfItem
import tech.atelier.portal.core.ShelfStore
import tech.atelier.portal.ui.ActionTile
import tech.atelier.portal.ui.EmptyHint
import tech.atelier.portal.ui.Glyph
import tech.atelier.portal.ui.IconButtonView
import tech.atelier.portal.ui.ItemGrid
import tech.atelier.portal.ui.ItemTile
import tech.atelier.portal.ui.MaxHeightScroll
import tech.atelier.portal.ui.Motion
import tech.atelier.portal.ui.Palette
import tech.atelier.portal.ui.PanelLayout
import tech.atelier.portal.ui.ShelfRoot
import tech.atelier.portal.ui.Spring
import tech.atelier.portal.ui.Thumbs
import tech.atelier.portal.ui.TileHost
import tech.atelier.portal.ui.dp
import tech.atelier.portal.ui.dpi

/** Marks a drag that started on Portal's own shelf, so dropping it back on the shelf does nothing. */
class PortalDrag(val ids: List<String>)

/**
 * The shelf on one display. It is a single small window at the top centre of the screen (the "notch")
 * that changes size:
 *
 *  - IDLE: a thin invisible strip along the top centre. It exists so Android tells it about every drag.
 *  - SENSING: a drag is happening somewhere. The notch glows and breathes blue.
 *  - OPEN: the shelf, sprung down out of the notch.
 *
 * Using one window (instead of adding a new one mid-drag) means the shelf can always receive the
 * drop that is already in progress.
 */
@SuppressLint("ViewConstructor")
class ShelfWindow(
    base: Context,
    private val wm: WindowManager,
    private val type: Int,
    val displayId: Int,
    private val display: Display?,
) : TileHost {

    enum class State { IDLE, SENSING, OPEN }

    private val ctx: Context = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault_DayNight)
    private val pal = Palette.of(ctx)
    private val main = Handler(Looper.getMainLooper())

    var state = State.IDLE
        private set
    var dragActive = false
        private set
    val isOpen get() = state == State.OPEN

    private var openedByDrag = false
    private var droppedThisDrag = false
    private var pinned = false
    private var editing = false
    private var keepHovers = 0
    private val selection = LinkedHashSet<String>()

    // ---------- views ----------

    private val root = ShelfRoot(ctx, pal)
    private val panel = PanelLayout(ctx, pal)
    private val title = tech.atelier.portal.ui.TitleView(ctx, pal)
    private val pinBtn = IconButtonView(ctx, pal, Glyph.PIN, "Keep shelf open")
    private val clearBtn = IconButtonView(ctx, pal, Glyph.TRASH, "Edit and clear shelf")
    private val tiles = listOf(
        ActionTile(ctx, pal, Action.QUICK_SHARE),
        ActionTile(ctx, pal, Action.COMPRESS),
        ActionTile(ctx, pal, Action.slot3(ctx)),
    )
    private val actionRow = LinearLayout(ctx)
    private val grid = ItemGrid(ctx)
    private val scroll = MaxHeightScroll(ctx, grid.tileH * 3 + grid.gap * 2)
    private val empty = EmptyHint(ctx, pal)
    private val status = TextView(ctx)
    private val itemTiles = HashMap<String, ItemTile>()
    private val stagger = ArrayList<View>()

    private val panelW = ctx.dpi(236f)
    private val padIn = ctx.dpi(12f)

    // ---------- motion ----------

    private val openSpring = Spring(Motion.OPEN_K, Motion.OPEN_Z) { applyOpen(it) }
    private val glowSpring = Spring(Motion.OPEN_K, Motion.OPEN_Z) { root.glow = it.coerceAtLeast(0f) }
    private val hotSpring = Spring(Motion.POP_K, 0.9f) { root.hot = it; empty.hot = it }
    private val kidSprings = HashMap<View, Spring>()

    private val lp = WindowManager.LayoutParams(
        0, 0, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = 0
        y = 0
        title = "Portal shelf"
        windowAnimations = 0 // Portal animates itself
    }

    private var attached = false
    private val storeListener: () -> Unit = { main.post { refreshItems(animateNew = true) } }
    private val statusReset = Runnable { showDefaultStatus() }

    val isAttached get() = attached

    // ---------- building ----------

    private fun build() {
        root.panel = panel
        root.panelRadius = panel.radius
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        panel.setPadding(padIn, ctx.dpi(10f), padIn, ctx.dpi(10f))
        panel.visibility = View.GONE
        panel.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.pivotX = v.width / 2f
            v.pivotY = 0f
        }

        // Header: title, pin, clear
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(0, ctx.dpi(30f), 1f))
        header.addView(pinBtn, LinearLayout.LayoutParams(ctx.dpi(30f), ctx.dpi(30f)))
        header.addView(clearBtn, LinearLayout.LayoutParams(ctx.dpi(30f), ctx.dpi(30f)).apply { marginStart = ctx.dpi(2f) })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        title.setOnClickListener { openSettings() }
        pinBtn.setOnClickListener {
            pinned = !pinned
            pinBtn.active = pinned
            flash(if (pinned) "Shelf stays open" else "Shelf closes when you click away")
        }
        clearBtn.setOnClickListener { onClearPressed() }

        // Three quick actions
        actionRow.orientation = LinearLayout.HORIZONTAL
        tiles.forEachIndexed { i, t ->
            actionRow.addView(t, LinearLayout.LayoutParams(0, ctx.dpi(64f), 1f).apply {
                if (i > 0) marginStart = ctx.dpi(8f)
            })
            t.setOnClickListener { onActionClicked(t) }
            t.setOnDragListener(actionDrag(t))
            t.setOnReceiveContentListener(MIMES, OnReceiveContentListener { _, payload ->
                droppedThisDrag = true
                runExternal(t, payload.clip)
                null
            })
        }
        panel.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8f)
        })

        // Kept items
        scroll.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8f)
        })
        panel.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(66f)).apply {
            topMargin = ctx.dpi(8f)
        })

        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        status.setTextColor(pal.sub)
        status.gravity = Gravity.CENTER
        status.maxLines = 1
        status.ellipsize = TextUtils.TruncateAt.END
        status.typeface = Typeface.DEFAULT
        panel.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(18f)).apply {
            topMargin = ctx.dpi(6f)
        })

        root.addView(panel, FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = ctx.dpi(34f)
            rightMargin = ctx.dpi(34f)
            topMargin = ctx.dpi(8f)
            bottomMargin = ctx.dpi(34f)
        })

        stagger.clear()
        stagger.add(header)
        stagger.addAll(tiles)
        stagger.add(scroll)
        stagger.add(empty)

        // Drag sensing and "drop to keep" everywhere except on the action tiles.
        root.setOnDragListener(rootDrag)
        root.setOnReceiveContentListener(MIMES, keepReceive)
        listOf<View>(panel, header, title, pinBtn, clearBtn, actionRow, scroll, grid, empty, status).forEach(::keepTarget)

        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (state == State.OPEN && !pinned && !dragActive) close()
                true
            } else if (state != State.OPEN && e.actionMasked == MotionEvent.ACTION_UP) {
                // Clicking the notch strip also opens the shelf.
                open(byDrag = false)
                true
            } else state != State.OPEN
        }
    }

    private fun keepTarget(v: View) {
        v.setOnDragListener(keepDrag)
        v.setOnReceiveContentListener(MIMES, keepReceive)
    }

    // ---------- window sizes ----------

    private fun sizeFor(s: State) {
        when (s) {
            State.IDLE -> { lp.width = ctx.dpi(180f); lp.height = ctx.dpi(8f) }
            State.SENSING -> { lp.width = ctx.dpi(300f); lp.height = ctx.dpi(84f) }
            State.OPEN -> { lp.width = WindowManager.LayoutParams.WRAP_CONTENT; lp.height = WindowManager.LayoutParams.WRAP_CONTENT }
        }
    }

    private fun setWindow(s: State) {
        sizeFor(s)
        if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    private fun preferFastestMode() = tech.atelier.portal.ui.preferFastest(lp, display)

    // ---------- open and close ----------

    fun toggle() = if (state == State.OPEN && openSpring.target > 0.5f) close() else open(byDrag = false)

    fun open(byDrag: Boolean) {
        if (state == State.OPEN && openSpring.target > 0.5f) return
        openedByDrag = byDrag
        val wasClosed = openSpring.value < 0.02f
        state = State.OPEN
        setWindow(State.OPEN)
        refreshItems(animateNew = false)
        panel.visibility = View.VISIBLE
        root.setPulsing(true)
        glowSpring.animateTo(1f, Motion.OPEN_K, Motion.OPEN_Z)
        openSpring.animateTo(1f, Motion.OPEN_K, Motion.OPEN_Z)
        if (wasClosed) cascadeIn()
    }

    fun close() {
        if (state != State.OPEN) return
        setEditing(false)
        keepHovers = 0
        hotSpring.animateTo(0f)
        if (!dragActive) glowSpring.animateTo(0f, Motion.CLOSE_K, Motion.CLOSE_Z)
        openSpring.animateTo(0f, Motion.CLOSE_K, Motion.CLOSE_Z) {
            panel.visibility = View.GONE
            state = if (dragActive) State.SENSING else State.IDLE
            setWindow(state)
            if (!dragActive) root.setPulsing(false)
        }
    }

    /** Panel drops down out of the notch and tucks back up into it. */
    private fun applyOpen(p: Float) {
        val s = (0.08f + 0.92f * p).coerceAtLeast(0.04f)
        panel.scaleX = s
        panel.scaleY = s
        panel.alpha = (p * 2.2f).coerceIn(0f, 1f)
        val shift = ctx.dp(18f) * (1f - p)
        panel.translationX = 0f
        panel.translationY = -shift
        root.invalidate()
    }

    /** Header, actions and items follow the panel a few milliseconds apart, each on its own spring. */
    private fun cascadeIn() {
        stagger.forEachIndexed { i, v ->
            val sp = kidSprings.getOrPut(v) {
                Spring(Motion.OPEN_K, 0.72f) { t ->
                    v.alpha = t.coerceIn(0f, 1f)
                    v.translationY = -ctx.dp(10f) * (1f - t)
                }
            }
            sp.snapTo(0f)
            main.postDelayed({ sp.animateTo(1f) }, 40L + Motion.STAGGER_MS * i)
        }
    }

    // ---------- drag sensing ----------

    /** Called for every drag that starts anywhere on this display, including drags out of Portal. */
    private fun onAnyDragStart() {
        dragActive = true
        droppedThisDrag = false
        keepHovers = 0
        if (state == State.IDLE) {
            state = State.SENSING
            setWindow(State.SENSING)
            root.setPulsing(true)
            glowSpring.animateTo(0.75f, Motion.OPEN_K, Motion.OPEN_Z)
        }
    }

    private fun onAnyDragEnd() {
        dragActive = false
        keepHovers = 0
        hotSpring.animateTo(0f)
        tiles.forEach { it.setDragHover(false) }
        when (state) {
            State.OPEN -> if (openedByDrag && !droppedThisDrag && !pinned) close()
            State.SENSING -> {
                state = State.IDLE
                glowSpring.animateTo(0f, Motion.CLOSE_K, Motion.CLOSE_Z) {
                    if (state == State.IDLE) {
                        setWindow(State.IDLE)
                        root.setPulsing(false)
                    }
                }
            }
            State.IDLE -> {}
        }
    }

    /** Shift pressed while something is being dragged. */
    fun shiftPressed() {
        if (dragActive && state != State.OPEN) open(byDrag = true)
    }

    private val rootDrag = View.OnDragListener { _, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> { onAnyDragStart(); true }
            DragEvent.ACTION_DRAG_ENTERED -> {
                if (state != State.OPEN && Prefs.corner(ctx)) open(byDrag = true)
                enterKeep()
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> { exitKeep(); true }
            DragEvent.ACTION_DROP -> e.localState is PortalDrag // ours: nothing to do. Others: keep (below).
            DragEvent.ACTION_DRAG_ENDED -> { onAnyDragEnd(); true }
            else -> true
        }
    }

    private val keepDrag = View.OnDragListener { _, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_ENTERED -> { enterKeep(); true }
            DragEvent.ACTION_DRAG_EXITED -> { exitKeep(); true }
            DragEvent.ACTION_DROP -> e.localState is PortalDrag
            else -> true
        }
    }

    // Returning false for a drop from another app lets Android take the drop permission and
    // hand the content to this listener.
    private val keepReceive = OnReceiveContentListener { _, payload ->
        droppedThisDrag = true
        keepHovers = 0
        hotSpring.animateTo(0f)
        val incoming = ShelfStore.open(ctx, payload.clip)
        if (incoming.isEmpty()) flash("Couldn't read that drop")
        else ShelfStore.keep(incoming) { made ->
            flash(if (made.size == 1) "Kept ${made[0].name}" else "Kept ${made.size} items")
        }
        null
    }

    private fun enterKeep() {
        keepHovers++
        hotSpring.animateTo(1f)
    }

    private fun exitKeep() {
        keepHovers = (keepHovers - 1).coerceAtLeast(0)
        main.post { if (keepHovers == 0) hotSpring.animateTo(0f) }
    }

    private fun actionDrag(t: ActionTile) = View.OnDragListener { _, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> { t.setDragHover(true); true }
            DragEvent.ACTION_DRAG_EXITED -> { t.setDragHover(false); true }
            DragEvent.ACTION_DRAG_ENDED -> { t.setDragHover(false); true }
            DragEvent.ACTION_DROP -> {
                t.setDragHover(false)
                val local = e.localState
                if (local is PortalDrag) {
                    droppedThisDrag = true
                    runOnItems(t, local.ids)
                    true
                } else false
            }
            else -> true
        }
    }

    // ---------- actions ----------

    private fun onActionClicked(t: ActionTile) {
        val ids = if (selection.isNotEmpty()) selection.toList() else ShelfStore.all().map { it.id }
        if (ids.isEmpty()) { flash("Drop files onto ${t.action.label}"); return }
        runOnItems(t, ids)
    }

    private fun runOnItems(t: ActionTile, ids: List<String>) {
        val payloads = ids.mapNotNull { ShelfStore.get(it) }.map { ShelfStore.payloadOf(it) }
        start(t, payloads)
    }

    private fun runExternal(t: ActionTile, clip: ClipData) {
        val incoming = ShelfStore.open(ctx, clip)
        if (incoming.isEmpty()) { flash("Couldn't read that drop"); return }
        t.busy = true
        ShelfStore.io.execute {
            val staged = ShelfStore.stage(incoming)
            main.post {
                t.busy = false
                start(t, staged)
            }
        }
    }

    private fun start(t: ActionTile, payloads: List<tech.atelier.portal.core.Payload>) {
        val a = t.action
        val slow = a == Action.COMPRESS || a == Action.CONVERT || a == Action.MERGE
        if (slow) t.busy = true
        Actions.run(ctx.applicationContext, a, payloads, displayId) { msg ->
            t.busy = false
            if (msg != null) flash(msg)
            if ((a == Action.QUICK_SHARE || a == Action.SHARE) && msg == null && !pinned) close()
        }
    }

    // ---------- items ----------

    private fun refreshItems(animateNew: Boolean) {
        val list = ShelfStore.all()
        val ids = list.map { it.id }.toSet()
        selection.retainAll(ids)
        // Drop tiles that are gone
        val gone = itemTiles.keys.filter { it !in ids }
        gone.forEach { id -> itemTiles.remove(id)?.let { grid.removeView(it) } }
        // Add new tiles, keep order
        val fresh = ArrayList<ItemTile>()
        list.forEachIndexed { i, item ->
            var tile = itemTiles[item.id]
            if (tile == null) {
                tile = ItemTile(ctx, pal, this)
                keepTarget(tile)
                itemTiles[item.id] = tile
                fresh.add(tile)
            }
            tile.item = item
            if (grid.indexOfChild(tile) != i) {
                if (tile.parent != null) grid.removeView(tile)
                grid.addView(tile, i.coerceAtMost(grid.childCount))
            }
        }
        title.count = list.size
        scroll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (animateNew && state == State.OPEN) fresh.forEach { it.landIn() }
        grid.children().forEach { it.invalidate() }
        if (!main.hasCallbacks(statusReset)) showDefaultStatus()
    }

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    private fun showDefaultStatus() {
        val n = ShelfStore.count()
        status.setTextColor(pal.sub)
        status.text = when {
            editing -> "Tap × to remove · trash again clears unpinned"
            selection.isNotEmpty() -> "${selection.size} selected · drag out together"
            n == 0 -> "Drag here, or hold Shift while dragging"
            else -> "Drag out to any app"
        }
    }

    private fun flash(msg: String) {
        status.setTextColor(pal.accent)
        status.text = msg
        main.removeCallbacks(statusReset)
        main.postDelayed(statusReset, 2400L)
    }

    private fun onClearPressed() {
        if (!editing) setEditing(true)
        else {
            ShelfStore.clearUnpinned()
            setEditing(false)
            flash("Cleared")
        }
    }

    private fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        clearBtn.active = on
        grid.children().forEach { it.invalidate() }
        showDefaultStatus()
    }

    private fun openSettings() {
        Actions.launch(ctx.applicationContext, Intent(ctx, MainActivity::class.java), displayId)
        if (!pinned) close()
    }

    // ---------- TileHost ----------

    override fun isSelected(id: String) = id in selection
    override fun editing() = editing

    override fun onTap(tile: ItemTile) {
        val id = tile.item?.id ?: return
        if (!selection.remove(id)) selection.add(id)
        tile.invalidate()
        main.removeCallbacks(statusReset)
        showDefaultStatus()
    }

    override fun onDoubleTap(tile: ItemTile) {
        val item = tile.item ?: return
        selection.remove(item.id)
        val ok = when {
            item.isLink -> Actions.openLink(ctx.applicationContext, item.text!!, displayId)
            item.isText -> { Actions.run(ctx.applicationContext, Action.COPY, listOf(ShelfStore.payloadOf(item)), displayId) { it?.let(::flash) }; return }
            else -> Actions.open(ctx.applicationContext, ShelfStore.payloadOf(item), displayId)
        }
        if (!ok) flash("No app can open this") else if (!pinned) close()
    }

    override fun onSecondary(tile: ItemTile) = setEditing(!editing)

    override fun onRemove(tile: ItemTile) {
        val id = tile.item?.id ?: return
        selection.remove(id)
        ShelfStore.remove(id)
    }

    override fun onPin(tile: ItemTile) {
        tile.item?.id?.let { ShelfStore.togglePin(it) }
    }

    override fun onStartDrag(tile: ItemTile) {
        val item = tile.item ?: return
        val ids = if (item.id in selection) selection.toList() else listOf(item.id)
        val items = ids.mapNotNull { ShelfStore.get(it) }
        if (items.isEmpty()) return
        val clipItems = items.map { s ->
            if (s.isText) ClipData.Item(s.text) else ClipData.Item(tech.atelier.portal.core.ShelfProvider.itemUri(s))
        }
        val mimes = items.map { it.mime }.distinct().toTypedArray()
        val clip = ClipData(ClipDescription(if (items.size == 1) items[0].name else "Portal", mimes), clipItems[0])
        for (i in 1 until clipItems.size) clip.addItem(clipItems[i])
        val flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
        tile.startDragAndDrop(clip, StackShadow(ctx, pal, items), PortalDrag(ids), flags)
    }

    // ---------- lifecycle ----------

    fun destroy() {
        ShelfStore.removeListener(storeListener)
        main.removeCallbacksAndMessages(null)
        openSpring.cancel(); glowSpring.cancel(); hotSpring.cancel()
        kidSprings.values.forEach { it.cancel() }
        root.setPulsing(false)
        if (attached) try { wm.removeViewImmediate(root) } catch (_: Exception) {}
        attached = false
    }

    // Last, so every listener above exists before the views are wired up.
    init {
        build()
        sizeFor(State.IDLE)
        preferFastestMode()
        try {
            wm.addView(root, lp)
            attached = true
        } catch (_: Exception) {
            attached = false
        }
        ShelfStore.addListener(storeListener)
        refreshItems(animateNew = false)
    }

    companion object {
        /** Everything. (Android won't accept a bare "*" here, so list the top-level types.) */
        val MIMES = arrayOf(
            "text/*", "image/*", "video/*", "audio/*", "application/*",
            "font/*", "model/*", "message/*", "multipart/*",
        )
    }
}

/** The picture under the pointer while dragging out: a little stack of the items, with a count. */
private class StackShadow(
    private val ctx: Context,
    private val pal: Palette,
    private val items: List<ShelfItem>,
) : View.DragShadowBuilder() {
    private val size = ctx.dpi(64f)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.badgeText
        textSize = ctx.dp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.text
        textSize = ctx.dp(9f)
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(size, size)
        outShadowTouchPoint.set(size / 2, size / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val s = ctx.dp(44f)
        val c = size / 2f
        val shown = items.take(3).reversed()
        shown.forEachIndexed { i, it ->
            val depth = shown.size - 1 - i
            canvas.save()
            canvas.rotate(depth * 7f - 4f, c, c)
            r.set(c - s / 2, c - s / 2, c + s / 2, c + s / 2)
            card.color = if (pal.dark) 0xFF2A2C31.toInt() else 0xFFFFFFFF.toInt()
            card.setShadowLayer(ctx.dp(6f), 0f, ctx.dp(2f), 0x66000000)
            canvas.drawRoundRect(r, ctx.dp(10f), ctx.dp(10f), card)
            val b = Thumbs.peek(it)
            if (b != null) {
                val sc = maxOf(s / b.width, s / b.height)
                canvas.save()
                canvas.clipRect(r)
                canvas.translate(c - b.width * sc / 2, c - b.height * sc / 2)
                canvas.scale(sc, sc)
                canvas.drawBitmap(b, 0f, 0f, img)
                canvas.restore()
            } else {
                canvas.drawText(it.ext, c, c + label.textSize / 3, label)
            }
            canvas.restore()
        }
        if (items.size > 1) {
            val br = ctx.dp(9f)
            canvas.drawCircle(size - br - ctx.dp(2f), br + ctx.dp(2f), br, badge)
            canvas.drawText(items.size.toString(), size - br - ctx.dp(2f), br + ctx.dp(2f) + badgeText.textSize / 3, badgeText)
        }
    }
}
