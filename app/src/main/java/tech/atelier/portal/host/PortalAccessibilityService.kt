package tech.atelier.portal.host

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import tech.atelier.portal.core.Prefs
import tech.atelier.portal.core.ShelfStore

/**
 * Preferred way to run Portal. Android only lets an accessibility service see keyboard keys
 * system-wide, which is what makes Shift-while-dragging and Ctrl + Alt + Space work.
 * Portal does not read screen content: it asks only for key events.
 */
class PortalAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var hub: ShelfHub? = null
    private var lastUiMode = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        ShelfStore.init(this)
        // Only one host at a time.
        stopService(Intent(this, PortalOverlayService::class.java))
        lastUiMode = resources.configuration.uiMode
        val h = ShelfHub(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) { d ->
            if (d.displayId == Display.DEFAULT_DISPLAY) {
                this to getSystemService(WindowManager::class.java)
            } else {
                val dc = createDisplayContext(d)
                dc to dc.getSystemService(WindowManager::class.java)
            }
        }
        hub = h
        PortalHost.hub = h
        h.start()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val h = hub ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (down && event.repeatCount == 0 && Prefs.shiftTrigger(this) && h.dragActive) {
                    main.post { h.onShift() }
                }
                return false // never swallow Shift
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (event.isCtrlPressed && event.isAltPressed && Prefs.hotkey(this)) {
                    if (down && event.repeatCount == 0) main.post { h.toggle() }
                    return true
                }
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (h.anyOpen) {
                    if (down) main.post { h.closeAll() }
                    return true
                }
            }
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark/light switch, fold/unfold, DeX on/off: rebuild the shelves to match.
        if (newConfig.uiMode != lastUiMode) {
            lastUiMode = newConfig.uiMode
        }
        main.post { hub?.rebuild() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        hub?.stop()
        if (PortalHost.hub === hub) PortalHost.hub = null
        hub = null
    }

    companion object {
        fun isRunning() = PortalHost.hub != null
    }
}
