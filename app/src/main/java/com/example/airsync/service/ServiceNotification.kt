package com.example.airsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.airsync.MainActivity
import com.example.airsync.R
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.BatterySource
import com.example.airsync.receiver.NoiseControlReceiver
import com.example.airsync.ui.common.label

/** Builds the (silent, low-importance) foreground-service notification. */
object ServiceNotification {

    const val CHANNEL_ID = "airsync_status"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, status: AirPodsStatus): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airpods)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val device = status.device
        if (device == null) {
            builder.setContentTitle(context.getString(R.string.notification_waiting_title))
                .setContentText(context.getString(R.string.notification_waiting_text))
        } else {
            builder.setContentTitle(device.name)
                .setContentText(batterySummary(context, status) + " · " + context.getString(status.noiseMode.label))
                .addAction(
                    R.drawable.ic_anc,
                    context.getString(R.string.action_switch_mode),
                    NoiseControlReceiver.cycleIntent(context)
                )
        }
        return builder.build()
    }

    fun batterySummary(context: Context, status: AirPodsStatus): String {
        val b = status.battery
        fun pct(v: Int?) = v?.let { "$it%" } ?: "—"
        return when (b.source) {
            BatterySource.NONE -> context.getString(R.string.status_connected)
            BatterySource.HEADSET_PROFILE -> context.getString(R.string.battery_single, pct(b.lowestBud))
            else -> context.getString(R.string.battery_summary, pct(b.left.level), pct(b.right.level), pct(b.case.level))
        }
    }
}
