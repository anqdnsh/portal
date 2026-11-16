package tech.atelier.portal.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager

/**
 * Keeps one shelf on every screen in use: the phone or tablet screen, and the monitor when
 * DeX runs on an external display.
 *
 * [windowFor] gives the context and window manager to use for a display (the accessibility
 * service and the overlay service do this differently).
 */
class ShelfHub(
    private val base: Context,
    private val type: Int,
    private val windowFor: (Display) -> Pair<Context, WindowManager>?,
) : DisplayManager.DisplayListener {

    private val dm = base.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val windows = LinkedHashMap<Int, ShelfWindow>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        dm.registerDisplayListener(this, main)
        reconcile()
    }

    fun stop() {
        if (!started) return
        started = false
        dm.unregisterDisplayListener(this)
        windows.values.forEach { it.destroy() }
        windows.clear()
    }

    /** Rebuild everything, e.g. after switching dark/light mode or folding/unfolding. */
    fun rebuild() {
        val wasOpen = windows.filterValues { it.isOpen }.keys
        windows.values.forEach { it.destroy() }
        windows.clear()
        reconcile()
        // Anything that was open stays open.
        wasOpen.forEach { windows[it]?.open(byDrag = false) }
    }

    private fun usable(d: Display): Boolean =
        d.state == Display.STATE_ON && (d.flags and Display.FLAG_PRIVATE) == 0

    private fun reconcile() {
        if (!started) return
        val want = dm.displays.filter(::usable).associateBy { it.displayId }
        windows.keys.filter { it !in want }.forEach { id -> windows.remove(id)?.destroy() }
        want.forEach { (id, d) ->
            if (id in windows) return@forEach
            val pair = try { windowFor(d) } catch (_: Exception) { null } ?: return@forEach
            val w = ShelfWindow(pair.first, pair.second, type, id, d)
            if (w.isAttached) windows[id] = w else w.destroy()
        }
    }

    val dragActive get() = windows.values.any { it.dragActive }
    val anyOpen get() = windows.values.any { it.isOpen }

    /** Shift (any screen) or a jiggle on [displayId] while dragging. */
    fun requestOpenDuringDrag(displayId: Int? = null) {
        val active = windows.values.filter { it.dragActive }
        when {
            active.isNotEmpty() -> active.forEach { it.requestOpenDuringDrag() }
            displayId != null && windows[displayId] != null -> windows[displayId]!!.requestOpenDuringDrag()
            else -> windows.values.forEach { it.requestOpenDuringDrag() }
        }
    }

    fun closeAll() = windows.values.forEach { it.close() }

    /** Opens on the screen where DeX's desktop is likely to be: an external monitor if there is one. */
    fun toggle() {
        windows.values.firstOrNull { it.isOpen }?.let { it.close(); return }
        val target = windows.values.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY } ?: windows.values.firstOrNull()
        target?.open(byDrag = false)
    }

    fun open() {
        if (anyOpen) return
        toggle()
    }

    override fun onDisplayAdded(displayId: Int) = reconcile()
    override fun onDisplayRemoved(displayId: Int) = reconcile()
    override fun onDisplayChanged(displayId: Int) = reconcile()
}

/** Whichever host is running (accessibility service preferred), so other screens can reach the shelf. */
object PortalHost {
    var hub: ShelfHub? = null

    // What Portal has noticed, shown in settings so you can see which triggers reach it.
    @Volatile var lastDragAt = 0L
    @Volatile var lastShiftAt = 0L
    @Volatile var lastMouseAt = 0L
    @Volatile var lastJiggleAt = 0L
    @Volatile var mouseWatch = false
}
