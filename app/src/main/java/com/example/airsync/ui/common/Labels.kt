package com.example.airsync.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.airsync.R
import com.example.airsync.domain.automation.AutomationReason
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.model.UserActivity

/** Single place mapping domain enums to user-facing strings/icons (shared by UI, tile, widget). */

/** "Just now" under a minute, otherwise Android's relative span ("5 minutes ago", "Yesterday"). */
fun relativeTime(context: android.content.Context, timeMillis: Long): String {
    val now = System.currentTimeMillis()
    return if (now - timeMillis < android.text.format.DateUtils.MINUTE_IN_MILLIS) {
        context.getString(R.string.time_just_now)
    } else {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            timeMillis, now, android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
}

@get:StringRes
val AncMode.label: Int
    get() = when (this) {
        AncMode.ANC -> R.string.mode_anc
        AncMode.TRANSPARENCY -> R.string.mode_transparency
        AncMode.ADAPTIVE -> R.string.mode_adaptive
        AncMode.OFF -> R.string.mode_off
    }

@get:StringRes
val AncMode.shortLabel: Int
    get() = when (this) {
        AncMode.ANC -> R.string.mode_anc_short
        AncMode.TRANSPARENCY -> R.string.mode_transparency
        AncMode.ADAPTIVE -> R.string.mode_adaptive
        AncMode.OFF -> R.string.mode_off
    }

@get:StringRes
val AncMode.description: Int
    get() = when (this) {
        AncMode.ANC -> R.string.mode_anc_desc
        AncMode.TRANSPARENCY -> R.string.mode_transparency_desc
        AncMode.ADAPTIVE -> R.string.mode_adaptive_desc
        AncMode.OFF -> R.string.mode_off_desc
    }

@get:DrawableRes
val AncMode.iconRes: Int
    get() = when (this) {
        AncMode.ANC -> R.drawable.ic_anc
        AncMode.TRANSPARENCY -> R.drawable.ic_transparency
        AncMode.ADAPTIVE -> R.drawable.ic_adaptive
        AncMode.OFF -> R.drawable.ic_mode_off
    }

@get:StringRes
val EqPreset.label: Int
    get() = when (this) {
        EqPreset.BALANCED -> R.string.eq_balanced
        EqPreset.BASS_BOOST -> R.string.eq_bass
        EqPreset.VOCAL_CLARITY -> R.string.eq_vocal
    }

@get:StringRes
val UserActivity.label: Int
    get() = when (this) {
        UserActivity.UNKNOWN -> R.string.activity_unknown
        UserActivity.STILL -> R.string.activity_still
        UserActivity.WALKING -> R.string.activity_walking
        UserActivity.RUNNING -> R.string.activity_running
        UserActivity.ON_BICYCLE -> R.string.activity_cycling
        UserActivity.IN_VEHICLE -> R.string.activity_vehicle
    }

@get:StringRes
val AutomationReason.label: Int
    get() = when (this) {
        AutomationReason.WALKING -> R.string.rule_walking
        AutomationReason.NOISY -> R.string.rule_noisy
        AutomationReason.STATIONARY -> R.string.rule_stationary
    }
