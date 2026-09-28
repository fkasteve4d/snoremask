package com.snoremask.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.snoremask.app.MainActivity
import com.snoremask.app.R
import com.snoremask.app.audio.BrownNoiseEngine
import com.snoremask.app.audio.MaskState
import com.snoremask.app.audio.SnoreDetector
import com.snoremask.app.bt.BtPrefs

/**
 * Foreground service that both:
 *  1. **Masks** — owns the brown-noise engine and snore detector, and
 *  2. **Watches** — when a Bluetooth device is chosen for auto-start, stays
 *     resident (foreground) and listens for that device connecting/disconnecting.
 *
 * Being a foreground service the whole time it's armed is what makes auto-start
 * work in the background / screen-off: the service is started while the app is
 * in the foreground (so it's fully privileged, including microphone), then it
 * simply begins masking when the earbuds connect — no background service start,
 * no CompanionDeviceManager association needed for already-paired earbuds.
 *
 * Modes: WATCHING (resident, no audio) <-> MASKING (audio active).
 */
class MaskingService : Service() {

    private val engine = BrownNoiseEngine()
    private val detector = SnoreDetector()

    private var isForeground = false
    private var masking = false
    private var btRegistered = false
    private var noisyRegistered = false

    /** Connect/disconnect of the chosen Bluetooth device. */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = BtPrefs.getSelectedAddress(this@MaskingService) ?: return
            val device = IntentCompat.getParcelableExtra(
                intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
            ) ?: return
            if (!device.address.equals(target, ignoreCase = true)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.i(TAG, "Chosen device connected -> start masking")
                    startMasking()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "Chosen device disconnected -> stop masking")
                    stopMasking(keepAlive = true)
                }
            }
        }
    }

    /** Audio about to route to the phone speaker (earbuds dropped) — stop instantly. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "Audio becoming noisy -> stop (avoid speaker playback)")
                stopMasking(keepAlive = isArmed())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                // Pause masking, but stay armed/resident if a device is selected.
                stopMasking(keepAlive = isArmed())
                if (!isArmed()) return START_NOT_STICKY
            }
            ACTION_START_NOW -> {
                enterWatching()
                startMasking()
            }
            ACTION_DISARM -> {
                if (masking) enterWatching() else stopEverything()
                if (!masking) return START_NOT_STICKY
            }
            else -> {
                // ACTION_ARM or a sticky restart (null intent): resident, waiting.
                enterWatching()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        teardownReceivers()
        detector.stop()
        engine.stop()
        MaskState.running.value = false
        MaskState.masking.value = false
        MaskState.waiting.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isArmed(): Boolean = BtPrefs.getSelectedAddress(this) != null

    private fun hasMic(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** Become (or stay) a resident foreground service in the waiting state. */
    private fun enterWatching() {
        ensureForeground()
        registerBt()
        if (!masking) {
            MaskState.waiting.value = isArmed()
            updateNotification()
        }
    }

    private fun ensureForeground() {
        if (isForeground) return
        // connectedDevice covers the waiting state; media/mic cover active masking.
        // Declaring mic while started from the foreground keeps mic usable later.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        isForeground = true
    }

    private fun startMasking() {
        ensureForeground()
        if (masking) return
        engine.start()
        if (hasMic()) detector.start(this)
        registerNoisy()
        masking = true
        MaskState.running.value = true
        MaskState.waiting.value = false
        updateNotification()
    }

    /**
     * Stop audio. If [keepAlive] and a device is armed, drop back to WATCHING and
     * stay resident so the next connect re-triggers masking; otherwise shut down.
     */
    private fun stopMasking(keepAlive: Boolean) {
        if (masking) {
            detector.stop()
            engine.stop()
            masking = false
            MaskState.running.value = false
            MaskState.masking.value = false
        }
        if (keepAlive && isArmed()) {
            MaskState.waiting.value = true
            updateNotification()
        } else {
            stopEverything()
        }
    }

    private fun stopEverything() {
        if (masking) {
            detector.stop()
            engine.stop()
            masking = false
        }
        teardownReceivers()
        MaskState.running.value = false
        MaskState.masking.value = false
        MaskState.waiting.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun registerBt() {
        if (btRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        btRegistered = true
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun teardownReceivers() {
        if (btRegistered) {
            try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
            btRegistered = false
        }
        if (noisyRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyRegistered = false
        }
    }

    private fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val text = when {
            masking -> "Listening & masking"
            isArmed() -> {
                val name = BtPrefs.getSelectedName(this)
                if (name.isNullOrEmpty()) "Waiting for your earbuds" else "Waiting for $name"
            }
            else -> "Running"
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MaskingService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnoreMasker")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "Masking", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while SnoreMasker is waiting, listening, or masking" }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MaskingService"
        private const val CHANNEL_ID = "masking"
        private const val NOTIF_ID = 1

        const val ACTION_ARM = "com.snoremask.app.ARM"
        const val ACTION_DISARM = "com.snoremask.app.DISARM"
        const val ACTION_START_NOW = "com.snoremask.app.START_NOW"
        const val ACTION_STOP = "com.snoremask.app.STOP"
        const val ACTION_STOP_ALL = "com.snoremask.app.STOP_ALL"

        /** Start masking immediately (manual Start). */
        fun startNow(context: Context) =
            context.startForegroundService(intent(context, ACTION_START_NOW))

        /** Pause masking but stay armed/resident if a device is selected (manual Stop). */
        fun stop(context: Context) =
            context.startService(intent(context, ACTION_STOP))

        /** Arm: become resident and watch for the chosen device (call while app is foreground). */
        fun arm(context: Context) =
            context.startForegroundService(intent(context, ACTION_ARM))

        /** Disarm: stop watching (and stop the service if not currently masking). */
        fun disarm(context: Context) =
            context.startService(intent(context, ACTION_DISARM))

        private fun intent(context: Context, action: String) =
            Intent(context, MaskingService::class.java).setAction(action)
    }
}
