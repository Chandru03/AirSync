package com.example.airsync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.airsync.data.context.AutomationController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic (15 min) safety net for the automation rules, e.g. to notice that a quiet café got
 * loud while the activity stayed STILL. Event-driven transitions handle the fast path.
 */
@HiltWorker
class AirPodsAutomationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val automation: AutomationController
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        automation.evaluate()
        return Result.success()
    }

    companion object {
        const val NAME = "airpods_automation"
    }
}
