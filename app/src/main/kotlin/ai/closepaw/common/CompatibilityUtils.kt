package ai.closepaw.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * API-level compatibility shims for running on Android 8.0 (API 26) through
 * current releases.
 *
 * Every branch is a complete implementation — no stubs. Callers use these
 * instead of inlining `Build.VERSION.SDK_INT` checks so version policy lives
 * in exactly one place.
 */
object CompatibilityUtils {

    const val NOTIFICATION_CHANNEL_ID = "closepaw_live_channel"
    const val NOTIFICATION_CHANNEL_NAME = "ClosePaw Active Session"

    /**
     * `FLAG_IMMUTABLE` exists since API 23; OR-ing it unconditionally is safe
     * on minSdk 26+, but the helper keeps call sites explicit and lets extra
     * flags (e.g. `FLAG_UPDATE_CURRENT`) ride along.
     */
    fun getImmutablePendingIntentFlags(extraFlags: Int = 0): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or extraFlags
        } else {
            extraFlags
        }
    }

    /**
     * Notification channels exist on API 26+. No-op below that (and on 26+
     * when the channel already exists), so repeated calls are safe.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing ClosePaw background automation and live casting"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * `startForeground` across API levels:
     * - API 34+: explicit service-type bitmask is mandatory, otherwise the
     *   system throws. MediaProjection and microphone types are OR-ed only
     *   when the caller actually uses them.
     * - API 29–33: the three-arg overload exists; only mediaProjection is
     *   passed (microphone type did not exist yet).
     * - API 26–28: legacy two-arg overload.
     */
    fun startForegroundServiceCompat(
        service: Service,
        notificationId: Int,
        notification: Notification,
        isMediaProjection: Boolean = true,
        isMicrophone: Boolean = true,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var serviceType = 0
            if (isMediaProjection) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (isMicrophone) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            service.startForeground(notificationId, notification, serviceType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = 0
            if (isMediaProjection) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            service.startForeground(notificationId, notification, serviceType)
        } else {
            service.startForeground(notificationId, notification)
        }
    }
}
