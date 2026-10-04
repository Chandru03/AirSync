package com.example.airsync.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.airsync.MainActivity
import com.example.airsync.R
import com.example.airsync.data.bluetooth.AirPodsConnector
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.BluetoothStatus
import com.example.airsync.domain.repository.AirPodsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Connect AirPods" Quick Settings tile (Samsung Quick panel too).
 *  - Disconnected: tap connects the paired AirPods in ~2 s.
 *  - Bluetooth off: tap asks to turn Bluetooth on.
 *  - Connected: shows battery; tap opens AirSync.
 */
@AndroidEntryPoint
class ConnectTileService : TileService() {

    @Inject lateinit var airPods: AirPodsRepository
    @Inject lateinit var connector: AirPodsConnector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        airPods.refresh()
        listening?.cancel()
        listening = combine(airPods.status, connector.connecting) { s, c -> s to c }
            .onEach { (status, connecting) -> render(status, connecting) }
            .launchIn(scope)
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val status = airPods.status.value
        when {
            status.isConnected -> openApp()
            status.bluetooth == BluetoothStatus.OFF -> requestBluetooth()
            else -> scope.launch {
                when (connector.connect()) {
                    AirPodsConnector.Result.NOT_PAIRED -> openApp()
                    AirPodsConnector.Result.BLUETOOTH_OFF -> requestBluetooth()
                    else -> Unit // the tile reflects success or failure through the status flow
                }
            }
        }
    }

    private fun render(status: AirPodsStatus, connecting: Boolean) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_bud_pair)
        tile.label = getString(R.string.connect_tile_label)
        val subtitle: String
        when {
            status.isConnected -> {
                tile.state = Tile.STATE_ACTIVE
                val battery = status.battery.lowestBud?.let { " · $it%" }.orEmpty()
                subtitle = getString(R.string.status_connected) + battery
            }
            connecting -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = getString(R.string.connect_connecting)
            }
            status.bluetooth == BluetoothStatus.OFF -> {
                tile.state = Tile.STATE_INACTIVE
                subtitle = getString(R.string.connect_tile_bt_off)
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                subtitle = getString(R.string.connect_tile_tap)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.updateTile()
    }

    private fun openApp() = launch(Intent(this, MainActivity::class.java))

    /** Apps can't switch Bluetooth on silently on Android 13+; show the system prompt instead. */
    @SuppressLint("MissingPermission")
    private fun requestBluetooth() = launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, intent.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
