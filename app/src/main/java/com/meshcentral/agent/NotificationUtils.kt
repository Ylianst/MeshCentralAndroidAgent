package com.meshcentral.agent

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.CountDownTimer
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.util.Pair

object NotificationUtils {
    const val NOTIFICATION_ID = 1337
    private const val NOTIFICATION_CHANNEL_ID = "com.meshcentral.agent.app"
    private const val NOTIFICATION_CHANNEL_NAME = "com.meshcentral.agent.app"

    private var notificationBlinkTimer: CountDownTimer? = null

    fun getNotification(context: Context, isBlinking: Boolean = false): Pair<Int, Notification> {
        NotificationUtils.createNotificationChannel(context)
        val notification: Notification = NotificationUtils.createNotification(context, isBlinking)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NotificationUtils.NOTIFICATION_ID, notification)
        return Pair(NotificationUtils.NOTIFICATION_ID, notification)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationUtils.NOTIFICATION_CHANNEL_ID,
                NotificationUtils.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(context: Context, isBlinking: Boolean = false): Notification {
        val builder = NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_ID)

        if (isBlinking) {
            builder.setSmallIcon(com.meshcentral.agent.R.drawable.ic_camera)
            builder.setColor(0xFFFF0000.toInt()) // Red when blinking
        } else {
            builder.setSmallIcon(com.meshcentral.agent.R.drawable.ic_camera)
        }

        builder.setContentTitle(context.getString(com.meshcentral.agent.R.string.meshcentral))
        builder.setContentText(context.getString(com.meshcentral.agent.R.string.displaysharing))
        builder.setOngoing(true)
        builder.setCategory(Notification.CATEGORY_SERVICE)
        builder.priority = Notification.PRIORITY_LOW
        builder.setShowWhen(true)
        return builder.build()
    }


    fun startNotificationBlink(context: Context) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            // Show toast once when blink starts - ONLY if setting is enabled
            if (g_autoConsentNotification) {
                Toast.makeText(context, "Remote View Connected", Toast.LENGTH_SHORT).show()
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var blinkCount = 0
            notificationBlinkTimer?.cancel()

            notificationBlinkTimer = object : CountDownTimer(2000, 250) { // 8 blinks over 2 seconds
                override fun onTick(millisUntilFinished: Long) {
                    blinkCount++

                    // Alternate between canceling and re-posting notification
                    // This forces the system to redraw the status bar area
                    notificationManager.cancel(NOTIFICATION_ID)

                    // Wait 50ms then repost (creates visible blink effect)
                    mainHandler.postDelayed({
                        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                        builder.setSmallIcon(com.meshcentral.agent.R.drawable.ic_camera)
                        builder.setContentTitle(context.getString(com.meshcentral.agent.R.string.meshcentral))
                        builder.setContentText(context.getString(com.meshcentral.agent.R.string.displaysharing))
                        builder.setOngoing(true)
                        builder.setCategory(Notification.CATEGORY_SERVICE)
                        builder.priority = NotificationCompat.PRIORITY_LOW
                        builder.setShowWhen(true)

                        // Alternate color
                        if (blinkCount % 2 == 1) {
                            builder.setColor(0xFFFF0000.toInt())
                        }

                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }, 50)
                }

                override fun onFinish() {
                    // Ensure notification is back to normal
                    mainHandler.postDelayed({
                        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                        builder.setSmallIcon(com.meshcentral.agent.R.drawable.ic_camera)
                        builder.setContentTitle(context.getString(com.meshcentral.agent.R.string.meshcentral))
                        builder.setContentText(context.getString(com.meshcentral.agent.R.string.displaysharing))
                        builder.setOngoing(true)
                        builder.setCategory(Notification.CATEGORY_SERVICE)
                        builder.priority = NotificationCompat.PRIORITY_LOW
                        builder.setShowWhen(true)
                        notificationManager.notify(NOTIFICATION_ID, builder.build())

                        notificationBlinkTimer = null
                    }, 100)
                }
            }
            notificationBlinkTimer?.start()
        }
    }

    // Stop any ongoing blink
    fun stopNotificationBlink() {
        notificationBlinkTimer?.cancel()
        notificationBlinkTimer = null
    }
}