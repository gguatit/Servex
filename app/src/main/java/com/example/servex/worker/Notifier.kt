package com.example.servex.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.servex.R
import com.example.servex.domain.ServerStatus

class Notifier(private val context: Context) {
    private val channelId = "servex_alerts"

    fun notify(serverName: String, status: ServerStatus) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(channelId, "Servex Alerts", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val bodyRes = when (status) {
            ServerStatus.WARNING -> R.string.status_warning
            ServerStatus.ERROR -> R.string.status_error
            ServerStatus.OFFLINE -> R.string.status_offline
            else -> R.string.status_normal
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_status_warning)
            .setContentTitle(serverName)
            .setContentText(context.getString(bodyRes))
            .setAutoCancel(true)
            .build()
        mgr.notify(serverName.hashCode(), notification)
    }
}
