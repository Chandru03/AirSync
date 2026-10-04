package com.example.airsync.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.airsync.data.repository.AirPodsRepositoryImpl
import com.example.airsync.domain.gesture.HeadGesture
import com.example.airsync.domain.gesture.HeadGestureDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Head gestures for incoming calls, like iOS on AirPods 4: nod to answer, shake to decline.
 *
 * Battery: the AirPods' 50 Hz motion stream is switched on only while a call is ringing and the
 * AirPods are worn, and switched off the moment the call is answered, declined or stops ringing.
 */
class CallGestureController(
    private val context: Context,
    private val airPods: AirPodsRepositoryImpl,
    private val scope: CoroutineScope
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val ringing = MutableStateFlow(false)
    private var listener: Any? = null
    private var watchJob: Job? = null

    private val canControlSimCalls: Boolean
        get() = granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS)

    fun start() {
        if (watchJob != null) return
        if (canControlSimCalls) register()
        watchJob = scope.launch {
            // Ringing = a SIM call ringing OR any app showing an incoming-call notification.
            // collectLatest: when ringing stops, the running session is cancelled and cleans up.
            combine(ringing, IncomingCallTracker.incoming) { sim, voip -> sim || voip != null }
                .distinctUntilChanged()
                .collectLatest { isRinging -> if (isRinging) runSession() }
        }
        Log.i(TAG, "head gestures armed (sim=$canControlSimCalls, apps=${IncomingCallTracker.hasAccess(context)})")
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        unregister()
        ringing.value = false
    }

    private suspend fun runSession() {
        val status = airPods.status.value
        val worn = !status.ear.isKnown || status.ear.budsInEar > 0
        if (!status.canControlHardware || !worn) return
        if (!airPods.setHeadTracking(true)) return
        Log.i(TAG, "call ringing: listening for nod / shake")
        try {
            val detector = HeadGestureDetector()
            val gesture = withTimeoutOrNull(MAX_RING_MS) {
                airPods.headPose.mapNotNull { p ->
                    detector.onSample(SystemClock.elapsedRealtime(), p.gx, p.gy, p.gz, p.ax, p.ay, p.az)
                }.first()
            }
            when (gesture) {
                HeadGesture.NOD -> answer()
                HeadGesture.SHAKE -> decline()
                null -> Unit
            }
        } finally {
            withContext(NonCancellable) { airPods.setHeadTracking(false) }
        }
    }

    /** App calls (WhatsApp…) are answered via their notification button; SIM calls via Telecom. */
    @SuppressLint("MissingPermission")
    private fun answer() {
        val call = IncomingCallTracker.incoming.value
        Log.i(TAG, "nod -> answering ${call?.packageName ?: "SIM call"}")
        if (call?.answer != null) { send(call.answer); return }
        if (!canControlSimCalls) return
        @Suppress("DEPRECATION")
        runCatching { telecom.acceptRingingCall() }.onFailure { Log.w(TAG, "answer failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun decline() {
        val call = IncomingCallTracker.incoming.value
        Log.i(TAG, "shake -> declining ${call?.packageName ?: "SIM call"}")
        if (call?.decline != null) { send(call.decline); return }
        if (!canControlSimCalls || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        @Suppress("DEPRECATION")
        runCatching { telecom.endCall() }.onFailure { Log.w(TAG, "decline failed", it) }
    }

    private fun send(intent: android.app.PendingIntent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Allow the app's answer activity to start from our background service.
                val opts = android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(context, 0, null, null, null, null, opts.toBundle())
            } else intent.send()
        }.onFailure { Log.w(TAG, "notification action failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun register(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    ringing.value = state == TelephonyManager.CALL_STATE_RINGING
                }
            }
            telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
            listener = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    ringing.value = state == TelephonyManager.CALL_STATE_RINGING
                }
            }
            @Suppress("DEPRECATION")
            telephony.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            listener = l
        }
        true
    }.onFailure { Log.w(TAG, "could not watch call state", it) }.getOrDefault(false)

    private fun unregister() {
        val l = listener ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && l is TelephonyCallback) {
                telephony.unregisterTelephonyCallback(l)
            } else if (l is PhoneStateListener) {
                @Suppress("DEPRECATION")
                telephony.listen(l, PhoneStateListener.LISTEN_NONE)
            }
        }
        listener = null
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CallGestures"
        const val MAX_RING_MS = 60_000L
    }
}
