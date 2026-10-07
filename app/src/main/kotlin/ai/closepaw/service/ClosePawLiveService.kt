package ai.closepaw.service

import ai.closepaw.R
import ai.closepaw.common.CompatibilityUtils
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service hosting Gemini Live screen-cast + voice sessions.
 *
 * Started with [ACTION_START_LIVE], torn down with [ACTION_STOP_LIVE].
 * The ongoing notification keeps the process alive while the socket,
 * microphone and virtual display are held; all version-specific foreground
 * mechanics delegate to [CompatibilityUtils]. Stopping is idempotent and
 * always calls `stopSelf()` so no zombie service survives a session end.
 */
class ClosePawLiveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CompatibilityUtils.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LIVE -> {
                Log.i(TAG, "Stop requested; shutting down live service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                CompatibilityUtils.startForegroundServiceCompat(
                    service = this,
                    notificationId = NOTIFICATION_ID,
                    notification = buildNotification(),
                    isMediaProjection = true,
                    isMicrophone = true,
                )
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CompatibilityUtils.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ClosePaw Agent Running")
            .setContentText("Autonomous session active")
            .setSmallIcon(R.drawable.ic_paw)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "ClosePawLiveService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_LIVE = "ai.closepaw.action.START_LIVE"
        const val ACTION_STOP_LIVE = "ai.closepaw.action.STOP_LIVE"

        /** Start (or re-signal) the live foreground service. */
        fun start(context: Context) {
            val intent = Intent(context, ClosePawLiveService::class.java).setAction(ACTION_START_LIVE)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** Request teardown of the live foreground service. */
        fun stop(context: Context) {
            val intent = Intent(context, ClosePawLiveService::class.java).setAction(ACTION_STOP_LIVE)
            context.startService(intent)
        }
    }
}
