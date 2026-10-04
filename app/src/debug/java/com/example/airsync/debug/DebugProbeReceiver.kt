package com.example.airsync.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.airsync.data.repository.AirPodsRepositoryImpl
import com.example.airsync.di.ApplicationScope
import com.example.airsync.domain.repository.AirPodsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only hooks for on-device verification over adb:
 *   adb shell am broadcast -a com.example.airsync.debug.HEAD_PROBE -n com.example.airsync/.debug.DebugProbeReceiver
 *   adb shell am broadcast -a com.example.airsync.debug.CYCLE_MODE -n com.example.airsync/.debug.DebugProbeReceiver
 */
@AndroidEntryPoint
class DebugProbeReceiver : BroadcastReceiver() {

    @Inject lateinit var airPods: AirPodsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope
    @Inject lateinit var connector: com.example.airsync.data.bluetooth.AirPodsConnector

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    "com.example.airsync.debug.CYCLE_MODE" -> {
                        airPods.cycleNoiseMode()
                        Log.i(TAG, "cycled -> ${airPods.status.value.noiseMode}")
                    }
                    "com.example.airsync.debug.HEAD_PROBE" -> probeHead()
                    "com.example.airsync.debug.GESTURE_TEST" -> gestureTest()
                    "com.example.airsync.debug.CONNECT" -> Log.i(TAG, "connect result: ${connector.connect()}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Streams head tracking for 15 s, logging every 5th sample (~10 Hz). */
    private suspend fun probeHead() {
        val repo = airPods as? AirPodsRepositoryImpl ?: return Unit.also { Log.w(TAG, "no impl") }
        var n = 0
        val logger = scope.launch {
            repo.headPose.collect { p ->
                if (n++ % 5 == 0) Log.i(TAG, "#$n gyro=(${p.gx},${p.gy},${p.gz}) acc=(${p.ax},${p.ay},${p.az})")
            }
        }
        Log.i(TAG, "head tracking start sent=${repo.setHeadTracking(true)}")
        delay(15_000)
        repo.setHeadTracking(false)
        logger.cancel()
        Log.i(TAG, "head tracking stopped after $n samples")
    }

    /** Runs the real gesture detector on the live stream for 20 s and logs each gesture. */
    private suspend fun gestureTest() {
        val repo = airPods as? AirPodsRepositoryImpl ?: return
        val detector = com.example.airsync.domain.gesture.HeadGestureDetector()
        val job = scope.launch {
            repo.headPose.collect { p ->
                detector.onSample(android.os.SystemClock.elapsedRealtime(), p.gx, p.gy, p.gz, p.ax, p.ay, p.az)
                    ?.let { Log.i(TAG, "GESTURE: $it") }
            }
        }
        Log.i(TAG, "gesture test start sent=${repo.setHeadTracking(true)}")
        delay(20_000)
        repo.setHeadTracking(false)
        job.cancel()
        Log.i(TAG, "gesture test done")
    }

    private companion object { const val TAG = "HeadProbe" }
}
