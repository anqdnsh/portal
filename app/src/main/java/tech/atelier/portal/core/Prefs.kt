package tech.atelier.portal.core

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. */
object Prefs {
    const val SLOT_SHARE = "share"
    const val SLOT_CONVERT = "convert"
    const val SLOT_MERGE = "merge"
    const val SLOT_COPY = "copy"

    fun of(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("portal", Context.MODE_PRIVATE)

    /** Third quick action. Default: share to other apps. */
    fun slot3(ctx: Context): String = of(ctx).getString("slot3", SLOT_SHARE) ?: SLOT_SHARE
    fun setSlot3(ctx: Context, v: String) = of(ctx).edit().putString("slot3", v).apply()

    /** Unpinned items older than this are removed. 0 = never. */
    fun autoClearHours(ctx: Context): Int = of(ctx).getInt("auto_clear_h", 0)
    fun setAutoClearHours(ctx: Context, h: Int) = of(ctx).edit().putInt("auto_clear_h", h).apply()

    /** Hold Shift while dragging anything to open the shelf. */
    fun shiftTrigger(ctx: Context): Boolean = of(ctx).getBoolean("shift_trigger", true)

    /** Ctrl + Alt + Space toggles the shelf. */
    fun hotkey(ctx: Context): Boolean = of(ctx).getBoolean("hotkey", true)

    /** Drag to the top centre of the screen (the notch) to open the shelf. Key kept as "corner" from 0.4.0. */
    fun corner(ctx: Context): Boolean = of(ctx).getBoolean("corner", true)

    fun setBool(ctx: Context, key: String, v: Boolean) = of(ctx).edit().putBoolean(key, v).apply()
}
