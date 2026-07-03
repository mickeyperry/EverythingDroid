package com.mickey.everythingdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mickey.everythingdroid.data.StreamProxy

/**
 * Foreground service that keeps the process (and so [StreamProxy]) alive while an
 * external player is streaming through the relay. Without it, aggressive OEM
 * battery managers (OnePlus…) kill the app moments after the player takes the
 * foreground, and playback dies with a connection error.
 *
 * Started on each Play tap; stops itself once the relay has been idle for a while.
 */
class StreamKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Streaming to player")
            .setContentText("Relaying media from the Everything server")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        handler.removeCallbacksAndMessages(null)
        scheduleIdleCheck()
        return START_NOT_STICKY
    }

    private fun scheduleIdleCheck() {
        handler.postDelayed({
            if (StreamProxy.isIdle(IDLE_MS)) stopSelf() else scheduleIdleCheck()
        }, 60_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 1
        private const val IDLE_MS = 3 * 60_000L

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, StreamKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
    }
}
