package com.example.airsync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.airsync.MainActivity
import com.example.airsync.R
import com.example.airsync.di.ApplicationScope
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.BatterySource
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.receiver.NoiseControlReceiver
import com.example.airsync.ui.common.iconRes
import com.example.airsync.ui.common.label
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@AndroidEntryPoint
class AirPodsWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var airPods: AirPodsRepository

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        airPods.refresh()
        AirPodsWidgetRenderer.render(context, manager, widgetIds, airPods.status.value)
    }
}

/** Pushes live state to all widget instances whenever something user-visible changes. */
@Singleton
class AirPodsWidgetUpdater @Inject constructor(
    private val airPods: AirPodsRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun start(context: Context) {
        val app = context.applicationContext
        airPods.status
            .map { WidgetKey(it.device?.name, it.battery.left, it.battery.right, it.battery.case, it.noiseMode, it.battery.source) to it }
            .distinctUntilChanged { a, b -> a.first == b.first }
            .onEach { (_, status) ->
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, AirPodsWidgetProvider::class.java))
                if (ids.isNotEmpty()) AirPodsWidgetRenderer.render(app, manager, ids, status)
            }
            .launchIn(scope)
    }

    private data class WidgetKey(
        val name: String?, val l: PodBattery, val r: PodBattery, val c: PodBattery,
        val mode: AncMode, val source: BatterySource
    )
}

object AirPodsWidgetRenderer {

    fun render(context: Context, manager: AppWidgetManager, ids: IntArray, status: AirPodsStatus) {
        val views = RemoteViews(context.packageName, R.layout.widget_airpods)
        val device = status.device
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))

        if (device == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.tile_label))
            views.setTextViewText(R.id.widget_status, context.getString(R.string.status_not_connected))
            views.setViewVisibility(R.id.widget_batteries, View.GONE)
            views.setViewVisibility(R.id.widget_mode, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        } else {
            views.setTextViewText(R.id.widget_title, device.name)
            views.setTextViewText(R.id.widget_status, context.getString(R.string.status_connected))
            views.setViewVisibility(R.id.widget_empty, View.GONE)
            views.setViewVisibility(R.id.widget_batteries, View.VISIBLE)
            views.setViewVisibility(R.id.widget_mode, View.VISIBLE)

            val b = status.battery
            val single = b.source == BatterySource.HEADSET_PROFILE
            bind(views, R.id.widget_left_value, R.id.widget_left_bar, if (single) b.left.copy(level = b.lowestBud) else b.left)
            views.setTextViewText(
                R.id.widget_left_label,
                context.getString(if (single) R.string.battery_airpods else R.string.battery_left)
            )
            views.setViewVisibility(R.id.widget_right_group, if (single) View.GONE else View.VISIBLE)
            bind(views, R.id.widget_right_value, R.id.widget_right_bar, b.right)
            bind(views, R.id.widget_case_value, R.id.widget_case_bar, b.case)

            views.setTextViewText(R.id.widget_mode_text, context.getString(status.noiseMode.label))
            views.setImageViewResource(R.id.widget_mode_icon, status.noiseMode.iconRes)
            views.setOnClickPendingIntent(R.id.widget_mode, NoiseControlReceiver.cycleIntent(context))
            views.setContentDescription(
                R.id.widget_mode,
                context.getString(R.string.widget_switch_mode_cd, context.getString(status.noiseMode.label))
            )
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun bind(views: RemoteViews, valueId: Int, barId: Int, battery: PodBattery) {
        val level = battery.level
        val text = when {
            level == null -> "—"
            battery.charging -> "⚡$level%"
            else -> "$level%"
        }
        views.setTextViewText(valueId, text)
        views.setProgressBar(barId, 100, level ?: 0, false)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
