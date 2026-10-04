package com.example.airsync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.airsync.di.ApplicationScope
import com.example.airsync.domain.repository.SettingsRepository
import com.example.airsync.service.AirSyncService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts background monitoring after reboot / app update. BOOT_COMPLETED is one of the few
 * contexts where Android still allows starting a connectedDevice foreground service.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        scope.launch {
            try {
                if (settings.awaitLoaded().backgroundEnabled) AirSyncService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
