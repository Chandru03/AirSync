package com.example.airsync.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.airsync.MainActivity
import com.example.airsync.R
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.ui.common.iconRes
import com.example.airsync.ui.common.label
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile (works in Samsung's Quick panel too).
 *  - Tap: cycles ANC → Transparency → Adaptive (opens the app when AirPods aren't connected).
 *  - Long-press: Android opens MainActivity via its QS_TILE_PREFERENCES intent filter.
 */
@AndroidEntryPoint
class AirSyncTileService : TileService() {

    @Inject lateinit var airPods: AirPodsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        airPods.refresh()
        listening?.cancel()
        listening = airPods.status.onEach(::render).launchIn(scope)
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!airPods.status.value.isConnected) {
            openApp()
            return
        }
        // Optimistic UI: show the next mode immediately; the flow confirms it a moment later.
        render(airPods.status.value.let { it.copy(noiseMode = it.noiseMode.next()) })
        scope.launch { airPods.cycleNoiseMode() }
    }

    private fun render(status: AirPodsStatus) {
        val tile = qsTile ?: return
        val device = status.device
        if (device == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_airpods)
            setSubtitle(tile, getString(R.string.status_not_connected))
        } else {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(status.noiseMode.label)
            tile.icon = Icon.createWithResource(this, status.noiseMode.iconRes)
            val battery = status.battery.lowestBud?.let { " · $it%" }.orEmpty()
            setSubtitle(tile, device.model.displayName + battery)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = tile.label
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
