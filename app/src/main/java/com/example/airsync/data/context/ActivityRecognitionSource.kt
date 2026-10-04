package com.example.airsync.data.context

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.airsync.domain.model.UserActivity
import com.example.airsync.receiver.ActivityTransitionReceiver
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Activity Recognition *Transition* API: Play services batches sensor data in low-power hardware
 * and wakes us only when the activity changes (e.g. STILL → WALKING). No polling, no GPS.
 */
@Singleton
class ActivityRecognitionSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean {
        if (!hasPermission) return false
        val transitions = TRACKED.flatMap { type ->
            listOf(
                ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                ActivityTransition.ACTIVITY_TRANSITION_EXIT
            ).map { ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build() }
        }
        return try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent())
                .await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Activity transitions unavailable", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun stop() {
        if (!hasPermission) return
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent()).await() }
    }

    // Must be MUTABLE: Play services fills the result extras into the intent.
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    )

    companion object {
        private const val TAG = "ActivityRecognition"
        private val TRACKED = listOf(
            DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE
        )

        fun map(type: Int): UserActivity = when (type) {
            DetectedActivity.STILL -> UserActivity.STILL
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> UserActivity.WALKING
            DetectedActivity.RUNNING -> UserActivity.RUNNING
            DetectedActivity.ON_BICYCLE -> UserActivity.ON_BICYCLE
            DetectedActivity.IN_VEHICLE -> UserActivity.IN_VEHICLE
            else -> UserActivity.UNKNOWN
        }
    }
}
