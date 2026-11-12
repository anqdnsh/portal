package tech.atelier.portal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class PortalOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, PortalOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        startForeground(NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PORTAL shelf is active")
            .setContentText("Drop files on PORTAL or tap to expand")
            .addAction(Notification.Action.Builder(null, "Turn off", stopIntent).build())
            .setOngoing(true).build())
        if (android.provider.Settings.canDrawOverlays(this)) showShelf() else stopSelf()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(20, 23, 29))
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), Color.rgb(65, 111, 190))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

    private fun showShelf() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            elevation = dp(12).toFloat()
        }
        val header = label("◈  PORTAL   ·   DROP FILES HERE", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { toggleExpanded() }
        }
        container.addView(header, LinearLayout.LayoutParams(dp(230), dp(48)))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        container.addView(content)
        val footer = label("OPEN FULL SHELF  ↗", 10f, Color.rgb(110, 160, 255), true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { openMain() }
        }
        content.addView(footer, LinearLayout.LayoutParams(-1, dp(40)))
        container.tag = content
        container.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.hasMimeType("text/uri-list") == true ||
                    event.clipDescription?.hasMimeType("application/octet-stream") == true ||
                    event.clipDescription != null
                DragEvent.ACTION_DROP -> {
                    val uris = mutableListOf<Uri>()
                    val clip = event.clipData
                    if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
                    if (uris.isNotEmpty()) {
                        FileShelf.importUris(this, uris)
                        refreshRows(content)
                        content.visibility = View.VISIBLE
                        expanded = true
                        updateSize()
                    }
                    true
                }
                else -> true
            }
        }
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        header.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = event.rawX; downY = event.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    if (moved) { params.x = (startX - dx).coerceAtLeast(0); params.y = (startY + dy).coerceAtLeast(0); runCatching { windowManager.updateViewLayout(container, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggleExpanded(); true }
                else -> true
            }
        }
        params = WindowManager.LayoutParams(dp(230), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16); y = dp(180)
        }
        root = container
        windowManager.addView(container, params)
        refreshRows(content)
    }

    private fun toggleExpanded() {
        val container = root ?: return
        val content = container.tag as? LinearLayout ?: return
        expanded = !expanded
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        refreshRows(content)
        updateSize()
    }

    private fun updateSize() {
        root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    private fun refreshRows(content: LinearLayout) {
        while (content.childCount > 1) content.removeViewAt(0)
        val files = FileShelf.all(this).take(5)
        files.forEach { file ->
            val row = label("▧   ${file.displayName}", 11f, Color.WHITE).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    runCatching {
                        val uri = FileShelf.uri(this@PortalOverlayService, file)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            }
            content.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        }
        if (files.isEmpty()) content.addView(label("Drop files here to collect them", 11f, Color.LTGRAY),
            LinearLayout.LayoutParams(-1, dp(42)), 0)
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "PORTAL floating shelf", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "tech.atelier.portal.STOP_OVERLAY"
        private const val CHANNEL_ID = "portal_overlay"
        private const val NOTIFICATION_ID = 41
    }
}