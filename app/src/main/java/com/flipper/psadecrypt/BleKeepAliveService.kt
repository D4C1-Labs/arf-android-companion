package com.flipper.psadecrypt

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BleKeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "psa_ble_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, BleKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleKeepAliveService::class.java))
        }

        fun updateBfProgress(context: Context, pct: Int, speed: String) {
            val intent = Intent(context, BleKeepAliveService::class.java).apply {
                action = "UPDATE_BF"
                putExtra("pct", pct)
                putExtra("speed", speed)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun clearBfProgress(context: Context) {
            val intent = Intent(context, BleKeepAliveService::class.java).apply {
                action = "CLEAR_BF"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var bfProgressText: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_BF" -> {
                val pct = intent.getIntExtra("pct", 0)
                val speed = intent.getStringExtra("speed") ?: ""
                bfProgressText = "BF: $pct% — $speed keys/sec"
            }
            "CLEAR_BF" -> {
                bfProgressText = null
            }
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = bfProgressText ?: "Connected to Flipper — BLE active"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARF Companion")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BLE connection alive while connected to Flipper"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
