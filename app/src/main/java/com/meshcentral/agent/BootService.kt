package com.meshcentral.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

class BootService : Service() {
    private val CHANNEL_ID = "BootServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.starting_meshcentral_bootservice))
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        performStartupTasks()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Boot Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification channel for BootService"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun performStartupTasks() {
        val pm: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val g_autoStart = pm.getBoolean("pref_autostart", false)
        if (g_autoStart == true) {
            Thread {
                try {
                    Thread.sleep(5000)
                    val launchIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val pendingIntent = PendingIntent.getActivity(
                            this,
                            0,
                            launchIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        pendingIntent.send()
                    } else {
                        startActivity(launchIntent)
                    }

                } catch (e: Exception) {
                }
            }.start()
        }
    }
}