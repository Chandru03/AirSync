package com.example.airsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.airsync.data.context.ActivityRecognitionSource
import com.example.airsync.data.context.AutomationController
import com.example.airsync.data.context.ContextSignals
import com.example.airsync.di.ApplicationScope
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receives activity changes from Play services and re-runs the automation rules. */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {

    @Inject lateinit var signals: ContextSignals
    @Inject lateinit var automation: AutomationController
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val entered = result.transitionEvents
            .lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            ?: return
        signals.activity.value = ActivityRecognitionSource.map(entered.activityType)

        val pending = goAsync()
        scope.launch {
            try {
                automation.evaluate()
            } finally {
                pending.finish()
            }
        }
    }
}
