package com.snoremask.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.snoremask.app.MainActivity
import com.snoremask.app.R
import com.snoremask.app.audio.BrownNoiseEngine
import com.snoremask.app.audio.MaskState
import com.snoremask.app.audio.SnoreDetector

/**
 * Foreground service that owns the audio engine and snore detector so masking
 * keeps running with the screen off and the app backgrounded.
 */
class MaskingService : Service() {

    private val engine = BrownNoiseEngine()
    private val detector = SnoreDetector()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Declare only the foreground-service types we actually have permission for.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (hasMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

        engine.start()
        if (hasMic) detector.start(this) // no mic permission -> baseline only, no detection
        MaskState.running.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        detector.stop()
        engine.stop()
        MaskState.running.value = false
        MaskState.masking.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MaskingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnoreMask")
            .setContentText("Listening & masking")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Masking", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while SnoreMask is listening and masking" }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "masking"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.snoremask.app.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MaskingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MaskingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
