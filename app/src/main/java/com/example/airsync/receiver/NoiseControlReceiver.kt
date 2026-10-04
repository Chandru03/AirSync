package com.example.airsync.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.airsync.di.ApplicationScope
import com.example.airsync.domain.repository.AirPodsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared "cycle noise mode" action for the widget button and the notification action. */
@AndroidEntryPoint
class NoiseControlReceiver : BroadcastReceiver() {

    @Inject lateinit var airPods: AirPodsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CYCLE) return
        val pending = goAsync()
        scope.launch {
            try {
                airPods.cycleNoiseMode()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CYCLE = "com.example.airsync.action.CYCLE_NOISE_MODE"

        fun cycleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, NoiseControlReceiver::class.java).setAction(ACTION_CYCLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
