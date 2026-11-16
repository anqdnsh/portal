package tech.atelier.portal

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import tech.atelier.portal.core.Prefs
import tech.atelier.portal.core.ShelfStore
import tech.atelier.portal.host.PortalHost
import tech.atelier.portal.host.PortalOverlayService
import tech.atelier.portal.ui.Palette
import tech.atelier.portal.ui.dpi

/** Settings and setup. The shelf itself lives at the top centre of the screen, not here. */
class MainActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var statusText: TextView
    private lateinit var a11yButton: Button
    private lateinit var overlayButton: Button
    private lateinit var checkText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShelfStore.init(this)
        pal = Palette.of(this)
        window.statusBarColor = 0
        window.navigationBarColor = 0
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun build(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dpi(22f)
            setPadding(p, dpi(40f), p, dpi(40f))
        }
        col.addView(text("PORTAL", 26f, pal.text, bold = true))
        col.addView(text("Drag anything to the top centre of the screen. Keep it. Drag it out again.", 14f, pal.sub).apply {
            setPadding(0, dpi(6f), 0, dpi(18f))
        })

        // --- Status / setup
        val setup = card()
        statusText = text("", 14f, pal.text)
        setup.addView(statusText)
        a11yButton = button("Turn on Portal (accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setup.addView(a11yButton)
        setup.addView(text(
            "Recommended. This is the only way Android lets an app see Shift and Ctrl + Alt + Space while you work in other apps. Portal only listens for keys; it doesn't read your screen.",
            12f, pal.sub,
        ).apply { setPadding(0, dpi(8f), 0, 0) })
        overlayButton = button("Use without shortcuts") { startOverlayMode() }
        setup.addView(overlayButton)
        setup.addView(button("Open the shelf") { PortalHost.hub?.open() })
        col.addView(setup)

        // --- Third action
        col.addView(section("Third quick action"))
        val slots = card()
        val group = RadioGroup(this)
        listOf(
            Prefs.SLOT_SHARE to "Share to other apps (default)",
            Prefs.SLOT_CONVERT to "Convert images to JPG",
            Prefs.SLOT_MERGE to "Merge PDFs (pages are re-drawn as images)",
            Prefs.SLOT_COPY to "Copy text and links",
        ).forEach { (id, label) ->
            group.addView(RadioButton(this).apply {
                text = label
                setTextColor(pal.text)
                tag = id
                this.id = View.generateViewId()
                isChecked = Prefs.slot3(this@MainActivity) == id
                setOnClickListener {
                    Prefs.setSlot3(this@MainActivity, id)
                    PortalHost.hub?.rebuild()
                }
            })
        }
        slots.addView(group)
        slots.addView(text("Quick Share and Compress are always the first two.", 12f, pal.sub))
        col.addView(slots)

        // --- Triggers
        col.addView(section("Opening the shelf"))
        val trig = card()
        trig.addView(toggle("Drag to the top centre (the notch)", Prefs.corner(this)) { Prefs.setBool(this, "corner", it) })
        trig.addView(toggle("Hold Shift while dragging", Prefs.shiftTrigger(this)) { Prefs.setBool(this, "shift_trigger", it) })
        trig.addView(toggle("Jiggle the pointer while dragging", Prefs.jiggle(this)) { Prefs.setBool(this, "jiggle", it) })
        trig.addView(toggle("Ctrl + Alt + Space", Prefs.hotkey(this)) { Prefs.setBool(this, "hotkey", it) })
        trig.addView(toggle("Show a handle at the top when the shelf has items", Prefs.showPill(this)) {
            Prefs.setBool(this, "pill", it)
            PortalHost.hub?.rebuild()
        })
        trig.addView(toggle("Close when I click elsewhere", Prefs.closeOnOutside(this)) { Prefs.setBool(this, "close_outside", it) })
        trig.addView(text("Put the shelf away: push its top edge up, or use the arrow, Esc or Ctrl + Alt + Space. Bring it back: click the handle at the top centre. Drag an item onto the bin to remove it.", 12f, pal.sub).apply {
            setPadding(0, dpi(6f), 0, 0)
        })
        col.addView(trig)

        // --- Drag check: what Portal can actually see on this device
        col.addView(section("Drag check"))
        val check = card()
        checkText = text("", 13f, pal.text)
        check.addView(checkText)
        check.addView(text(
            "Drag a file somewhere, press Shift, jiggle the pointer, then come back here. Each line should update. Send a screenshot of this if a trigger doesn't work.",
            12f, pal.sub,
        ).apply { setPadding(0, dpi(8f), 0, 0) })
        check.addView(button("Refresh") { refreshCheck() })
        col.addView(check)

        // --- Auto clear
        col.addView(section("Clear old items"))
        val clear = card()
        val cg = RadioGroup(this)
        listOf(0 to "Never", 24 to "After 24 hours", 168 to "After 7 days").forEach { (h, label) ->
            cg.addView(RadioButton(this).apply {
                text = label
                setTextColor(pal.text)
                this.id = View.generateViewId()
                isChecked = Prefs.autoClearHours(this@MainActivity) == h
                setOnClickListener {
                    Prefs.setAutoClearHours(this@MainActivity, h)
                    ShelfStore.applyAutoClear()
                }
            })
        }
        clear.addView(cg)
        clear.addView(text("Pinned items are never cleared.", 12f, pal.sub))
        col.addView(clear)

        col.addView(text("Version 0.4.4", 12f, pal.sub).apply {
            gravity = Gravity.CENTER
            setPadding(0, dpi(18f), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(if (pal.dark) 0xFF101113.toInt() else 0xFFF2F3F6.toInt())
            addView(col)
        }
    }

    private fun refresh() {
        val a11y = isA11yOn()
        val hosted = PortalHost.hub != null
        statusText.text = when {
            a11y -> "Portal is on, with keyboard shortcuts."
            hosted -> "Portal is on, without keyboard shortcuts."
            else -> "Portal is off. Turn it on to put the shelf at the top of the screen."
        }
        a11yButton.text = if (a11y) "Accessibility settings" else "Turn on Portal (accessibility)"
        overlayButton.visibility = if (a11y || hosted) View.GONE else View.VISIBLE
        refreshCheck()
    }

    private fun ago(t: Long): String {
        if (t == 0L) return "not yet"
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private fun refreshCheck() {
        val a11y = isA11yOn()
        val mouse = when {
            !a11y -> "needs the accessibility switch"
            android.os.Build.VERSION.SDK_INT < 35 -> "needs Android 15 or later"
            !PortalHost.mouseWatch -> "not available on this device"
            else -> "last movement " + ago(PortalHost.lastMouseAt)
        }
        checkText.text = buildString {
            append("Drag seen: ").append(ago(PortalHost.lastDragAt)).append('\n')
            append("Shift seen: ").append(if (a11y) ago(PortalHost.lastShiftAt) else "needs the accessibility switch").append('\n')
            append("Pointer: ").append(mouse).append('\n')
            append("Jiggle seen: ").append(ago(PortalHost.lastJiggleAt))
        }
    }

    private fun isA11yOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    private fun startOverlayMode() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, PortalOverlayService::class.java))
        statusText.postDelayed({ refresh() }, 600)
    }

    // ---------- small builders ----------

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, 700, false)
    }

    private fun section(s: String) = text(s.uppercase(), 11f, pal.sub, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dpi(4f), dpi(22f), 0, dpi(8f))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = dpi(16f)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = dpi(20f).toFloat()
            setColor(if (pal.dark) 0xFF1D1E21.toInt() else 0xFFFFFFFF.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (pal.dark) 0xFF08101F.toInt() else 0xFFFFFFFF.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dpi(14f).toFloat()
            setColor(pal.accent)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(46f)).apply { topMargin = dpi(12f) }
        setOnClickListener { onClick() }
    }

    @Suppress("DEPRECATION")
    private fun toggle(label: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextColor(pal.text)
        isChecked = on
        minHeight = dpi(44f)
        setOnCheckedChangeListener { _, v -> change(v) }
    }
}
