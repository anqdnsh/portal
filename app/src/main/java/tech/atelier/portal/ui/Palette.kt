package tech.atelier.portal.ui

import android.content.Context
import android.content.res.Configuration
import android.view.Display
import android.view.WindowManager

/** Frosted charcoal (dark) and frosted white (light), both with a blue glow. No borders anywhere. */
class Palette(
    val dark: Boolean,
    val panel: Int,
    val tile: Int,
    val tileHover: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
    val accentSoft: Int,
    val glow: Int,
    val badgeText: Int,
) {
    companion object {
        val DARK = Palette(
            dark = true,
            panel = 0xEB1D1E21.toInt(),
            tile = 0x12FFFFFF,
            tileHover = 0x21FFFFFF,
            text = 0xFFF2F4F8.toInt(),
            sub = 0xFFA4ABB8.toInt(),
            accent = 0xFF5C9BFF.toInt(),
            accentSoft = 0x524D8CFF,
            glow = 0xFF4D8CFF.toInt(),
            badgeText = 0xFF08101F.toInt(),
        )
        val LIGHT = Palette(
            dark = false,
            panel = 0xF0F8F9FB.toInt(),
            tile = 0x0D14203C,
            tileHover = 0x1A14203C,
            text = 0xFF12151C.toInt(),
            sub = 0xFF4A5263.toInt(),
            accent = 0xFF2F63E0.toInt(),
            accentSoft = 0x2E3A72F0,
            glow = 0xFF3A72F0.toInt(),
            badgeText = 0xFFFFFFFF.toInt(),
        )

        fun of(ctx: Context): Palette =
            if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Ask the display for its fastest mode (120 Hz where the screen has it) while Portal is on screen. */
fun preferFastest(lp: WindowManager.LayoutParams, display: Display?) {
    display ?: return
    val cur = display.mode
    val best = display.supportedModes
        .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        .maxByOrNull { it.refreshRate } ?: return
    lp.preferredDisplayModeId = best.modeId
    lp.preferredRefreshRate = best.refreshRate
}
