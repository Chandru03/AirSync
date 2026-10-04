package com.example.airsync.di

import com.example.airsync.data.audio.AudioRepositoryImpl
import com.example.airsync.data.location.LocationRepository
import com.example.airsync.data.location.LocationRepositoryImpl
import com.example.airsync.data.repository.AirPodsRepositoryImpl
import com.example.airsync.data.settings.SettingsRepositoryImpl
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.domain.repository.AudioRepository
import com.example.airsync.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-lifetime scope for singletons that must outlive any single screen or service. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds @Singleton
    abstract fun bindAirPodsRepository(impl: AirPodsRepositoryImpl): AirPodsRepository

    @Binds @Singleton
    abstract fun bindAudioRepository(impl: AudioRepositoryImpl): AudioRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindLocationRepository(impl: LocationRepositoryImpl): LocationRepository

    companion object {
        @Provides @Singleton @ApplicationScope
        fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
