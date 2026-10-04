package com.example.airsync

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.airsync.service.ServiceNotification
import com.example.airsync.widget.AirPodsWidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AirSyncApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var widgetUpdater: AirPodsWidgetUpdater

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        ServiceNotification.createChannel(this)
        // Keeps home-screen widgets live for as long as the process is alive.
        widgetUpdater.start(this)
    }
}
