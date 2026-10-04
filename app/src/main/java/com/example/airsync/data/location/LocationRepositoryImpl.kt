package com.example.airsync.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.airsync.domain.model.LastSeenLocation
import com.example.airsync.domain.repository.SettingsRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

interface LocationRepository {
    val hasPermission: Boolean

    /** Captures the phone's location as the AirPods' last-seen position. Returns false if unavailable. */
    suspend fun recordLastSeen(): Boolean
}

/**
 * Find My fallback: the phone is where the AirPods were when they disconnected, so we store the
 * phone's position on connect/disconnect. Works while the app or the location-typed foreground
 * service is active; otherwise Android denies background location and we keep the previous fix.
 */
@Singleton
class LocationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
) : LocationRepository {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    override val hasPermission: Boolean
        get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    override suspend fun recordLastSeen(): Boolean {
        if (!hasPermission) return false
        val location: Location? = try {
            val token = CancellationTokenSource()
            withTimeoutOrNull(10_000) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token).await()
            }.also { if (it == null) token.cancel() } ?: client.lastLocation.await()
        } catch (e: Exception) {
            Log.w(TAG, "Location unavailable", e)
            null
        }
        location ?: return false
        settings.setLastSeen(
            LastSeenLocation(location.latitude, location.longitude, location.accuracy, System.currentTimeMillis())
        )
        return true
    }

    private companion object {
        const val TAG = "LocationRepository"
    }
}
