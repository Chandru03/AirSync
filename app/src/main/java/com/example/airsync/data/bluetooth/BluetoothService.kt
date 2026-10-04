package com.example.airsync.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.airsync.domain.model.BluetoothStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level Bluetooth data source. Owns every Android Bluetooth API the app touches:
 *
 *  - Adapter on/off and runtime-permission state.
 *  - Which audio devices are *actually connected* (A2DP or HFP), via profile proxies + broadcasts.
 *    Being bonded/paired is not the same as being connected.
 *  - The HFP battery level Android itself reads from AirPods (coarse fallback).
 *  - A hardware-filtered BLE scan for Apple proximity adverts (precise battery + ear detection).
 */
@Singleton
class BluetoothService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _status = MutableStateFlow(computeStatus())
    val status: StateFlow<BluetoothStatus> = _status.asStateFlow()

    /** Connected audio devices keyed by address. A device is present while A2DP *or* HFP is up. */
    private val _connectedAudio = MutableStateFlow<Map<String, BluetoothDevice>>(emptyMap())
    val connectedAudio: StateFlow<Map<String, BluetoothDevice>> = _connectedAudio.asStateFlow()
    private val a2dpConnected = mutableSetOf<String>()
    private val hfpConnected = mutableSetOf<String>()

    /** Battery reported through HFP (`AT+IPHONEACCEV`), keyed by address. Single value for both buds. */
    private val _headsetBattery = MutableStateFlow<Map<String, Int>>(emptyMap())
    val headsetBattery: StateFlow<Map<String, Int>> = _headsetBattery.asStateFlow()

    private val _proximity = MutableSharedFlow<ProximityMessage>(extraBufferCapacity = 16)
    val proximity: SharedFlow<ProximityMessage> = _proximity.asSharedFlow()

    /** Raw Apple frame proven (by address or IRK) to come from the user's own AirPods. */
    data class OwnAdvert(val address: String, val data: ByteArray, val rssi: Int, val atMillis: Long)

    private val _ownAdverts = MutableSharedFlow<OwnAdvert>(extraBufferCapacity = 16)
    val ownAdverts: SharedFlow<OwnAdvert> = _ownAdverts.asSharedFlow()

    /** Identity address → IRK, for resolving rotating advert addresses. */
    @Volatile var identityKeys: Map<String, ByteArray> = emptyMap()
    /** Advert address → identity address, or [NOT_OURS] (ConcurrentHashMap forbids null values). */
    private val rpaCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null
    private val scanStarts = ArrayDeque<Long>()
    private var advertsLogged = 0
    private var rawLogged = 0
    private val typeCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    val hasConnectPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(Manifest.permission.BLUETOOTH_CONNECT)

    val hasScanPermission: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
                    device?.let { onProfileState(it, a2dpConnected, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
                    device?.let { onProfileState(it, hfpConnected, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> device?.let { forget(it.address) }
                ACTION_BATTERY_LEVEL_CHANGED -> device?.let {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    _headsetBattery.update { m -> if (level in 0..100) m + (it.address to level) else m - it.address }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // These are protected system broadcasts sent by the Bluetooth process, hence EXPORTED.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    /** Re-evaluates adapter/permission state and (re)binds profile proxies. Idempotent. */
    fun refresh() {
        _status.value = computeStatus()
        if (_status.value != BluetoothStatus.ON) {
            a2dpConnected.clear(); hfpConnected.clear()
            _connectedAudio.value = emptyMap()
            stopScan()
            return
        }
        if (a2dpProxy == null) bindProfile(BluetoothProfile.A2DP)
        if (headsetProxy == null) bindProfile(BluetoothProfile.HEADSET)
        seedFromProxies()
    }

    @SuppressLint("MissingPermission")
    fun bondedAudioDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission) return emptyList()
        return runCatching {
            adapter?.bondedDevices.orEmpty().filter { d ->
                d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    d.name?.contains("AirPods", ignoreCase = true) == true
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Asks the A2DP and HFP profiles to connect [device] using the hidden `connect()` that
     * Settings uses. It is privileged on modern Android, so this usually fails with a
     * SecurityException; it costs nothing to try and is logged so we know on each device.
     * @return true if at least one profile accepted the request.
     */
    fun tryConnectProfiles(device: BluetoothDevice): Boolean {
        var accepted = false
        for ((name, proxy) in listOf("A2DP" to a2dpProxy, "HFP" to headsetProxy)) {
            proxy ?: continue
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(proxy.javaClass, proxy, "connect", device) as? Boolean ?: false
                } else {
                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) as? Boolean ?: false
                }
            }.onFailure { Log.i(TAG, "$name connect() refused: ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (ok) Log.i(TAG, "$name connect() accepted")
            accepted = accepted || ok
        }
        return accepted
    }

    @SuppressLint("MissingPermission")
    fun nameOf(device: BluetoothDevice): String? =
        if (!hasConnectPermission) null else runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias ?: device.name else device.name
        }.getOrNull()

    /** HFP battery via hidden getter (reflection); the broadcast covers later updates. */
    fun readHeadsetBattery(device: BluetoothDevice) {
        val level = runCatching {
            BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
        }.getOrDefault(-1)
        if (level in 0..100) _headsetBattery.update { it + (device.address to level) }
    }

    /**
     * Starts a hardware-filtered scan for Apple proximity adverts. The ScanFilter matters: Android
     * silently suspends *unfiltered* scans while the screen is off, which would kill ear detection.
     */
    @SuppressLint("MissingPermission")
    fun startScan(lowLatency: Boolean): Boolean {
        if (_isScanning.value) return true
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        if (!hasScanPermission) return false
        // Android blocks apps that start more than 5 scans per 30 s; stay well below that.
        val now = SystemClock.elapsedRealtime()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > 30_000) scanStarts.removeFirst()
        if (scanStarts.size >= 4) return false

        // Company-ID-only filter: still a *filtered* scan (so it keeps running with the screen off),
        // but the Samsung/Qualcomm offload filter does not prefix-match the "07 19" header, so the
        // message-type check happens in software in ProximityPairingParser.
        val filter = ScanFilter.Builder()
            .setManufacturerData(ProximityPairingParser.APPLE_COMPANY_ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        return try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started (lowLatency=$lowLatency)")
            scanStarts.addLast(now)
            _isScanning.value = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        _isScanning.value = false
        runCatching { if (hasScanPermission) adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(ProximityPairingParser.APPLE_COMPANY_ID)
            if (data != null && data.isNotEmpty()) {
                resolveOwner(result.device.address)?.let { owner ->
                    _ownAdverts.tryEmit(OwnAdvert(owner, data, result.rssi, System.currentTimeMillis()))
                }
                // Diagnostics: one raw dump per Apple message type, plus a periodic histogram.
                val type = data[0].toInt() and 0xFF
                val seen = typeCounts.merge(type, 1, Int::plus) ?: 1
                if (seen <= 2) Log.d(TAG, "apple type=0x${"%02x".format(type)} rssi=${result.rssi} data=${data.joinToString(" ") { "%02x".format(it) }}")
            }
            ProximityPairingParser.parse(data, result.rssi)?.let {
                if (advertsLogged++ < 5) Log.d(TAG, "advert model=0x${it.modelId.toString(16)} rssi=${it.rssi} battery=${it.battery} ear=${it.ear}")
                _proximity.tryEmit(it)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onScanResult(0, it) }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    /** Identity address of our AirPods if [address] is theirs (directly or as an RPA), else null. */
    private fun resolveOwner(address: String): String? {
        val keys = identityKeys
        if (keys.isEmpty()) return null
        if (address in keys) return address
        if (rpaCache.size > 512) rpaCache.clear()
        val owner = rpaCache.getOrPut(address) {
            keys.entries.firstOrNull { (_, irk) -> BleCrypto.verifyRpa(address, irk) }?.key ?: NOT_OURS
        }
        return owner.takeIf { it != NOT_OURS }
    }

    private fun bindProfile(profile: Int) {
        if (!hasConnectPermission) return
        runCatching {
            adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    if (p == BluetoothProfile.A2DP) a2dpProxy = proxy else headsetProxy = proxy
                    seedFromProxies()
                }

                override fun onServiceDisconnected(p: Int) {
                    if (p == BluetoothProfile.A2DP) a2dpProxy = null else headsetProxy = null
                }
            }, profile)
        }.onFailure { Log.w(TAG, "getProfileProxy($profile) failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun seedFromProxies() {
        if (!hasConnectPermission) return
        val a2dp = runCatching { a2dpProxy?.connectedDevices }.getOrNull()
        val hfp = runCatching { headsetProxy?.connectedDevices }.getOrNull()
        synchronized(this) {
            if (a2dp != null) { a2dpConnected.clear(); a2dp.forEach { a2dpConnected += it.address } }
            if (hfp != null) { hfpConnected.clear(); hfp.forEach { hfpConnected += it.address } }
            val all = (a2dp.orEmpty() + hfp.orEmpty()).associateBy { it.address }
            publish(all + _connectedAudio.value.filterKeys { it in a2dpConnected || it in hfpConnected })
        }
        (a2dp.orEmpty() + hfp.orEmpty()).forEach { readHeadsetBattery(it) }
    }

    private fun onProfileState(device: BluetoothDevice, set: MutableSet<String>, state: Int) {
        Log.i(TAG, "profile ${if (set === a2dpConnected) "A2DP" else "HFP"} state=$state for ${nameOf(device)}")
        synchronized(this) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> set += device.address
                BluetoothProfile.STATE_DISCONNECTED -> set -= device.address
                else -> return
            }
            val up = device.address in a2dpConnected || device.address in hfpConnected
            publish(if (up) _connectedAudio.value + (device.address to device) else _connectedAudio.value - device.address)
        }
        if (state == BluetoothProfile.STATE_CONNECTED) readHeadsetBattery(device)
        else if (device.address !in _connectedAudio.value) _headsetBattery.update { it - device.address }
    }

    private fun forget(address: String) = synchronized(this) {
        a2dpConnected -= address; hfpConnected -= address
        publish(_connectedAudio.value - address)
        _headsetBattery.update { it - address }
    }

    private fun publish(map: Map<String, BluetoothDevice>) {
        if (map.keys != _connectedAudio.value.keys) _connectedAudio.value = map
    }

    private fun computeStatus(): BluetoothStatus = when {
        adapter == null -> BluetoothStatus.UNSUPPORTED
        !hasConnectPermission -> BluetoothStatus.PERMISSION_REQUIRED
        !adapter.isEnabled -> BluetoothStatus.OFF
        else -> BluetoothStatus.ON
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BluetoothService"
        const val NOT_OURS = "-"
        // Hidden-but-stable framework constants (BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED).
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
