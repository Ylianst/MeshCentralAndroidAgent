package com.meshcentral.agent
import android.R
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.util.Pair

object NotificationUtils {
    const val NOTIFICATION_ID = 1337
    private const val NOTIFICATION_CHANNEL_ID = "com.meshcentral.agent.app"
    private const val NOTIFICATION_CHANNEL_NAME = "com.meshcentral.agent.app"
    fun getNotification(context: Context): Pair<Int, Notification> {
        NotificationUtils.createNotificationChannel(context)
        val notification: Notification = NotificationUtils.createNotification(context)
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

    private fun createNotification(context: Context): Notification {
        val builder = NotificationCompat.Builder(context, NotificationUtils.NOTIFICATION_CHANNEL_ID)
        builder.setSmallIcon(com.meshcentral.agent.R.drawable.ic_camera)
        builder.setContentTitle(context.getString(com.meshcentral.agent.R.string.meshcentral))
        builder.setContentText(context.getString(com.meshcentral.agent.R.string.displaysharing))
        builder.setOngoing(true)
        builder.setCategory(Notification.CATEGORY_SERVICE)
        builder.priority = Notification.PRIORITY_LOW
        builder.setShowWhen(true)
        return builder.build()
    }
}
