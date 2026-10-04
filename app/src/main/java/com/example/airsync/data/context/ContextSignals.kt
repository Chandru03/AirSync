package com.example.airsync.data.context

import com.example.airsync.domain.automation.AutomationDecision
import com.example.airsync.domain.model.UserActivity
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AutomationEvent(val decision: AutomationDecision, val atMillis: Long)

/** In-memory context shared between receivers, the worker, the service and the UI. */
@Singleton
class ContextSignals @Inject constructor() {
    val activity = MutableStateFlow(UserActivity.UNKNOWN)
    val ambientDb = MutableStateFlow<Float?>(null)
    val manualOverrideUntilMillis = MutableStateFlow(0L)
    val lastAutomation = MutableStateFlow<AutomationEvent?>(null)
}
