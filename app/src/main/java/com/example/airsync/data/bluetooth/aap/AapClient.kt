package com.example.airsync.data.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import com.example.airsync.di.ApplicationScope
import com.example.airsync.domain.model.ControlChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AAP control channel over a classic (BR/EDR) L2CAP socket.
 *
 * Android's public L2CAP API only builds LE CoC sockets; AirPods expose AAP on a classic link, so
 * the hidden `BluetoothDevice.createInsecureL2capSocket(psm)` is the only route. On Android 17 the
 * hidden-API filter marks it *blocked*, so [HiddenApiBypass] (pure Java, process-local, no root)
 * lifts the filter first. Verified on Galaxy Z Fold (One UI, Android 17) with AirPods 4.
 *
 * If anything in that chain fails the channel stays [ControlChannel.UNAVAILABLE] and the app
 * degrades to profile-only state. Nothing here may crash the app.
 */
@Singleton
class AapClient @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope
) {
    private val _channel = MutableStateFlow(ControlChannel.UNAVAILABLE)
    val channel: StateFlow<ControlChannel> = _channel.asStateFlow()

    private val _events = MutableSharedFlow<AapProtocol.Event>(extraBufferCapacity = 32)
    val events: SharedFlow<AapProtocol.Event> = _events.asSharedFlow()

    private val writeLock = Mutex()
    @Volatile private var socket: BluetoothSocket? = null
    private var sessionJob: Job? = null
    private var connectedAddress: String? = null

    /** Set once socket creation proves impossible on this build, so we stop retrying. */
    @Volatile private var unsupportedOnThisDevice = false
    @Volatile private var bypassReady: Boolean? = null
    private var unknownLogged = 0
    private var headRawLogged = 0

    fun connect(device: BluetoothDevice) {
        if (unsupportedOnThisDevice) return
        if (connectedAddress == device.address && sessionJob?.isActive == true) return
        disconnect()
        connectedAddress = device.address
        sessionJob = scope.launch(Dispatchers.IO) { runSession(device) }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        connectedAddress = null
        closeSocket()
        _channel.value = ControlChannel.UNAVAILABLE
    }

    suspend fun send(packet: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val out = socket?.takeIf { _channel.value == ControlChannel.CONNECTED }?.outputStream
            ?: return@withContext false
        writeLock.withLock {
            runCatching { out.write(packet); out.flush() }
                .onFailure { Log.w(TAG, "AAP write failed", it) }
                .isSuccess
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runSession(device: BluetoothDevice) {
        // Let A2DP/HFP settle first; connecting during profile setup is refused by the pods.
        delay(INITIAL_DELAY_MS)
        var attempt = 0
        while (currentCoroutineContext().isActive && attempt < MAX_ATTEMPTS && !unsupportedOnThisDevice) {
            attempt++
            _channel.value = ControlChannel.CONNECTING
            val s = createSocket(device)
            if (s == null) {
                unsupportedOnThisDevice = true
                break
            }
            socket = s
            // connect() blocks and ignores coroutine cancellation; a watchdog closing the socket aborts it.
            val watchdog = scope.launch { delay(CONNECT_TIMEOUT_MS); runCatching { s.close() } }
            val connected = runCatching { s.connect() }
                .onFailure { Log.i(TAG, "AAP connect attempt $attempt failed: ${it.message}") }
                .isSuccess
            watchdog.cancel()
            if (!connected) {
                closeSocket()
                _channel.value = ControlChannel.UNAVAILABLE
                delay(backoff(attempt))
                continue
            }

            attempt = 0
            _channel.value = ControlChannel.CONNECTED
            Log.i(TAG, "AAP channel connected")
            send(AapProtocol.HANDSHAKE)
            send(AapProtocol.SET_FEATURES)
            send(AapProtocol.REQUEST_NOTIFICATIONS)
            send(AapProtocol.ENABLE_ALL_LISTENING_MODES)
            readLoop(s)
            closeSocket()
            _channel.value = ControlChannel.UNAVAILABLE
            Log.i(TAG, "AAP channel dropped; reconnecting")
            delay(backoff(1))
        }
        if (_channel.value != ControlChannel.CONNECTED) _channel.value = ControlChannel.UNAVAILABLE
    }

    private fun readLoop(s: BluetoothSocket) {
        val buffer = ByteArray(2048)
        val input = runCatching { s.inputStream }.getOrNull() ?: return
        while (true) {
            // L2CAP is packet based: each read returns exactly one AAP packet.
            val n = try { input.read(buffer) } catch (_: IOException) { -1 }
            if (n <= 0) return
            val packet = buffer.copyOf(n)
            val event = AapProtocol.parse(packet)
            if (event is AapProtocol.Event.Battery || event is AapProtocol.Event.Ear) Log.d(TAG, "$event <- ${packet.toHex()}")
            if (event != null) _events.tryEmit(event)
            else if (unknownLogged++ < 20) Log.d(TAG, "AAP unhandled: ${packet.toHex()}")
            if (n > 6 && packet[4] == 0x17.toByte() && headRawLogged++ < 3) Log.d(TAG, "AAP 0x17 len=$n: ${packet.toHex()}")
        }
    }

    /**
     * Opens the classic L2CAP socket via the hidden API. [HiddenApiBypass] is applied lazily and
     * once; if it fails (future Android hardening) we report the channel as unsupported.
     */
    private fun createSocket(device: BluetoothDevice): BluetoothSocket? {
        if (!ensureBypass()) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.invoke(
                    BluetoothDevice::class.java, device, "createInsecureL2capSocket", AapProtocol.PSM
                ) as BluetoothSocket
            } else {
                // No hidden-API filter before Android 9: plain reflection reaches the method.
                BluetoothDevice::class.java.getMethod("createInsecureL2capSocket", Int::class.javaPrimitiveType)
                    .invoke(device, AapProtocol.PSM) as BluetoothSocket
            }
        } catch (t: Throwable) {
            Log.i(TAG, "AAP not supported on this build: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun ensureBypass(): Boolean = synchronized(this) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        bypassReady ?: runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/bluetooth/BluetoothDevice;", "Landroid/bluetooth/BluetoothSocket;"
            )
        }.onFailure { Log.w(TAG, "Hidden API bypass failed", it) }.isSuccess.also { bypassReady = it }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun backoff(attempt: Int) =
        (BASE_RETRY_MS * (1L shl (attempt - 1).coerceIn(0, 5))).coerceAtMost(MAX_RETRY_MS)

    private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it) }

    private companion object {
        const val TAG = "AapClient"
        const val MAX_ATTEMPTS = 8
        const val INITIAL_DELAY_MS = 1_500L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val BASE_RETRY_MS = 500L
        const val MAX_RETRY_MS = 15_000L
    }
}
