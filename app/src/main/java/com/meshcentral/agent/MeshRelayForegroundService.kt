package com.meshcentral.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Always-on foreground service that keeps the agent's connection to the MeshCentral server
 * alive so the device is reachable "any time". Android requires a visible notification for
 * any persistent service — that is unavoidable and by design.
 *
 * INTEGRATION: the fork already has code that opens the /agent.ashx WebSocket and services
 * relay requests (screen capture, etc.). Call that from onStartCommand() where marked, or,
 * if the fork already ships its own Service, delete this class and instead:
 *   - add android:foregroundServiceType="specialUse|mediaProjection" to that Service, and
 *   - have BootReceiver start THAT service.
 */
class MeshRelayForegroundService : Service() {

    companion object {
        private const val TAG = "MeshRelayFgs"
        private const val CHANNEL_ID = "mesh_relay"
        private const val NOTIF_ID = 4114

        fun start(context: Context) {
            val i = Intent(context, MeshRelayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshRelayForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        acquireWakeLock()
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    /** Partial wake-lock keeps the CPU (and the OkHttp socket) alive through screen-lock/doze. */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meshcentral:agent")
                .apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock acquire failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Relay service running")
        // The MeshAgent connection is owned by MainActivity (g_autoConnect reconnects it).
        // If the app has no live Activity (e.g. right after boot), bring it up so the agent
        // connects; as Device Owner we are allowed to start an activity from the background.
        // Fully headless operation (no Activity) would require decoupling MeshAgent from
        // MainActivity -- see INTEGRATION.md "Known follow-up".
        try {
            if (g_mainActivity == null && meshAgent == null) {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconnect launch failed: ${e.message}")
        }
        // START_STICKY so the OS restarts us if we are ever killed.
        return START_STICKY
    }

    private fun startAsForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.mesh_fgs_title))
            .setContentText(getString(R.string.mesh_fgs_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.mesh_fgs_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
