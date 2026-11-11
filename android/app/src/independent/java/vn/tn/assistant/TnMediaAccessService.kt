package vn.tn.assistant

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** OS binding authorizes MediaSession access; notification contents are not collected. */
class TnMediaAccessService : NotificationListenerService() {
    companion object {
        fun hasAccess(context: Context): Boolean {
            val component = ComponentName(context, TnMediaAccessService::class.java)
            if (Build.VERSION.SDK_INT >= 27) {
                return context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationListenerAccessGranted(component) == true
            }
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }

        fun canRequestAccess(context: Context): Boolean =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).resolveActivity(context.packageManager) != null
    }
}
