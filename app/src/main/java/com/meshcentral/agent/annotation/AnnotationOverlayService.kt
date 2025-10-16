package com.meshcentral.agent.annotation

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.meshcentral.agent.R
import android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
import com.meshcentral.agent.meshAgent

class AnnotationOverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var overlay: DrawingOverlayView
    private lateinit var lp: WindowManager.LayoutParams
    private var foregroundStarted = false



    override fun onCreate() {
        super.onCreate()
        AnnotationServiceBus.attach(this)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay = DrawingOverlayView(this)

        overlay.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,   // ✅ pass-through
            PixelFormat.TRANSLUCENT
        )
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1) Foreground ASAP here (some OEMs require this location)
        if (!foregroundStarted) {
            startForeground(NOTIF_ID, buildNotif())
            foregroundStarted = true
        }

        // 2) Now attach the overlay; catching any race-y issues is okay
        try {
            if (overlay.windowToken == null) {
                wm.addView(overlay, lp)
            }
        } catch (_: Exception) { /* ignore duplicate add */ }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // Notify server that annotations stopped
        try {
            try {
                meshAgent?.sendJson(org.json.JSONObject().apply {
                    put("action", "annotationAck")
                    put("op", "event")
                    put("event", "stopped")
                })
            } catch (e: Exception) {
                android.util.Log.e("AnnotationOverlayService", "Failed to send stop event: ${e.message}")
            }
        } catch (e: Exception) {
            // Log but don't crash
        }

        AnnotationServiceBus.detach(this)
        try { wm.removeView(overlay) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun applyCommand(cmd: DrawCmd) = overlay.applyCommand(cmd)
    fun setStyle(style: DrawStyle) = overlay.setStyle(style)
    fun clear() = overlay.clear()
    fun removeById(id: String) = overlay.removeById(id)

    private fun buildNotif(): Notification {
        val channelId = "annotations"
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(channelId, "Remote annotations", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val stopPI = PendingIntent.getBroadcast(
            this, 1, Intent(this, StopAnnotationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle("Remote annotations active")
            .setContentText("Tap Stop to hide annotations")
            .addAction(0, "Stop", stopPI)
            .setOngoing(true)
            .build()
    }

    companion object { const val NOTIF_ID = 911001 }
}

