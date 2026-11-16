package tech.atelier.portal.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import tech.atelier.portal.MainActivity
import tech.atelier.portal.core.ShelfStore

/**
 * Fallback for people who don't want to turn on the accessibility service.
 * The notch and the shelf work the same; keyboard shortcuts don't (Android doesn't allow it).
 */
class PortalOverlayService : Service() {

    private var hub: ShelfHub? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ShelfStore.init(this)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Settings.canDrawOverlays(this) || PortalHost.hub != null) {
            stopSelf()
            return
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val h = ShelfHub(this, type) { d ->
            val wc = createWindowContext(d, type, null)
            wc to wc.getSystemService(WindowManager::class.java)
        }
        hub = h
        PortalHost.hub = h
        h.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private var lastConfig: Configuration? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val old = lastConfig ?: Configuration(resources.configuration)
        lastConfig = Configuration(newConfig)
        val matters = android.content.pm.ActivityInfo.CONFIG_UI_MODE or android.content.pm.ActivityInfo.CONFIG_DENSITY or
            android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE or android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
        if (old.diff(newConfig) and matters != 0) hub?.rebuild()
    }

    override fun onDestroy() {
        hub?.stop()
        if (PortalHost.hub === hub) PortalHost.hub = null
        hub = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("portal", "Portal shelf", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "portal")
            .setSmallIcon(tech.atelier.portal.R.drawable.ic_stat_portal)
            .setContentTitle("Portal is ready")
            .setContentText("Drag anything to the top centre of the screen")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
