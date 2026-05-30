package com.meshcentral.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class AgentForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        AgentController.attachService(this)
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentController.init(applicationContext)
        promoteToForeground()
        when (intent?.action) {
            ACTION_CONNECT -> if (meshAgent == null) AgentController.toggleAgentConnection(false)
            ACTION_DISCONNECT -> if (!AgentController.enterpriseEnforced && meshAgent != null) AgentController.toggleAgentConnection(true)
            ACTION_STOP_SCREEN_SHARING -> AgentController.stopScreenSharingByUser()
            ACTION_STOP -> {
                if (!AgentController.enterpriseEnforced) {
                    if (meshAgent != null) AgentController.toggleAgentConnection(true)
                    stopSelf()
                }
            }
            else -> {
                if (AgentController.shouldAutoStart() && meshAgent == null && !g_userDisconnect) {
                    AgentController.toggleAgentConnection(false)
                }
            }
        }
        updateNotification()
        val keepRunning = AgentController.shouldKeepForegroundServiceRunning()
        if (!keepRunning) {
            stopSelf()
        }
        return if (keepRunning) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            NotificationManagerCompat.from(this).cancel(SESSION_NOTIFICATION_ID)
        } catch (_: SecurityException) {
        }
        AgentController.detachService(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(this))
        } catch (_: SecurityException) {
        }
        updateSessionNotification()
    }

    private fun updateSessionNotification() {
        val manager = NotificationManagerCompat.from(this)
        val users = if (g_sessionNotification) AgentController.activeSessionUsers() else emptyList()
        if (users.isEmpty()) {
            try {
                manager.cancel(SESSION_NOTIFICATION_ID)
            } catch (_: SecurityException) {
            }
            return
        }
        createSessionNotificationChannel(this)
        val text = if (users.size == 1) {
            getString(R.string.session_connected_one, users[0])
        } else {
            getString(R.string.session_connected_many, users.size)
        }
        val notification = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle(getString(R.string.session_active_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(users.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppPendingIntent(this, null))
            .also { builder ->
                if (!AgentController.enterpriseEnforced && AgentController.hasActiveDesktopTunnel()) {
                    builder.addAction(
                        R.drawable.ic_cloud,
                        getString(R.string.stopsharescreen),
                        servicePendingIntent(this, ACTION_STOP_SCREEN_SHARING, 3)
                    )
                }
            }
            .build()
        try {
            manager.notify(SESSION_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun promoteToForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this), type)
    }

    companion object {
        private const val CHANNEL_ID = "meshcentral_agent_foreground"
        private const val CHANNEL_NAME = "MeshCentral Agent"
        private const val SESSION_CHANNEL_ID = "meshcentral_agent_session"
        private const val SESSION_CHANNEL_NAME = "Remote session active"
        private const val NOTIFICATION_ID = 2401
        private const val RUNTIME_NOTIFICATION_ID = 2402
        private const val SESSION_NOTIFICATION_ID = 2403
        private const val ACTION_CONNECT = "com.meshcentral.agent.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.meshcentral.agent.action.DISCONNECT"
        private const val ACTION_STOP_SCREEN_SHARING = "com.meshcentral.agent.action.STOP_SCREEN_SHARING"
        private const val ACTION_STOP = "com.meshcentral.agent.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun connect(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            intent.action = ACTION_CONNECT
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            intent.action = ACTION_DISCONNECT
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun showOneShotNotification(context: Context, title: String?, body: String?, url: String?) {
            createNotificationChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle(title ?: context.getString(R.string.app_name))
                .setContentText(body ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: ""))
                .setContentIntent(openAppPendingIntent(context, url))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(RUNTIME_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }

        private fun buildNotification(context: Context): Notification {
            val state = when (meshAgent?.state ?: 0) {
                1 -> context.getString(R.string.connecting)
                2 -> context.getString(R.string.authenticating)
                3 -> context.getString(R.string.connected)
                else -> context.getString(R.string.disconnected)
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cloud)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(state)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .setContentIntent(openAppPendingIntent(context, null))

            if (!AgentController.enterpriseEnforced) {
                if (meshAgent == null) {
                    builder.addAction(R.drawable.ic_cloud, context.getString(R.string.connect), servicePendingIntent(context, ACTION_CONNECT, 1))
                } else {
                    builder.addAction(R.drawable.ic_cloud, context.getString(R.string.disconnect), servicePendingIntent(context, ACTION_DISCONNECT, 2))
                }
            }
            return builder.build()
        }

        private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, AgentForegroundService::class.java)
            intent.action = action
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getService(context, requestCode, intent, flags)
        }

        private fun openAppPendingIntent(context: Context, url: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (url != null) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    intent.data = Uri.parse(url)
                }
                intent.putExtra("url", url)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getActivity(context, 0, intent, flags)
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                channel.lightColor = Color.BLUE
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }

        private fun createSessionNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(SESSION_CHANNEL_ID, SESSION_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                channel.lightColor = Color.BLUE
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }
    }
}
