package com.example.airsync.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ThumbsUpDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.airsync.R
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.AppSettings
import com.example.airsync.domain.model.ControlChannel
import com.example.airsync.domain.model.UserActivity
import com.example.airsync.ui.common.iconRes
import com.example.airsync.ui.common.label
import com.example.airsync.ui.common.relativeTime
import androidx.compose.ui.platform.LocalContext
import com.example.airsync.ui.main.AutomationUi
import com.example.airsync.ui.common.VoiceBars
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlin.math.roundToInt

@Composable
fun ListeningCard(
    status: AirPodsStatus,
    settings: AppSettings,
    onConversationalAwareness: (Boolean) -> Unit,
    onRemoveToPause: (Boolean) -> Unit,
    onHeadGestures: (Boolean) -> Unit,
    notificationAccess: Boolean,
    onRequestNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = stringResource(R.string.section_listening), modifier = modifier) {
        val realCa = status.controlChannel == ControlChannel.CONNECTED
        SwitchRow(
            title = stringResource(R.string.ca_title),
            subtitle = when {
                realCa && status.wearerSpeaking && settings.conversationalAwareness -> stringResource(R.string.ca_subtitle_speaking)
                realCa -> stringResource(R.string.ca_subtitle_real)
                else -> stringResource(R.string.ca_subtitle_unavailable)
            },
            checked = settings.conversationalAwareness,
            onCheckedChange = onConversationalAwareness,
            titleAccessory = {
                AnimatedVisibility(
                    visible = realCa && status.wearerSpeaking && settings.conversationalAwareness,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) { VoiceBars(MaterialTheme.colorScheme.primary) }
            },
            enabled = realCa || settings.conversationalAwareness,
            icon = Icons.Outlined.RecordVoiceOver
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        val earSubtitle = when {
            !status.isConnected -> stringResource(R.string.rtp_subtitle)
            status.ear.isKnown -> stringResource(R.string.rtp_subtitle_active)
            else -> stringResource(R.string.rtp_subtitle_waiting)
        }
        SwitchRow(
            title = stringResource(R.string.rtp_title),
            subtitle = earSubtitle,
            checked = settings.removeToPause,
            onCheckedChange = onRemoveToPause,
            icon = Icons.Outlined.PauseCircle
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SwitchRow(
            title = stringResource(R.string.head_gestures_title),
            subtitle = stringResource(
                if (status.controlChannel == ControlChannel.CONNECTED || !status.isConnected) R.string.head_gestures_subtitle
                else R.string.ca_subtitle_unavailable
            ),
            checked = settings.headGestures,
            onCheckedChange = onHeadGestures,
            icon = Icons.Outlined.ThumbsUpDown
        )
        if (settings.headGestures && !notificationAccess) {
            InlineAction(
                text = stringResource(R.string.head_gestures_access_note),
                action = stringResource(R.string.action_allow),
                onAction = onRequestNotificationAccess
            )
        }

    }
}

@Composable
fun AutomationCard(
    settings: AppSettings,
    automation: AutomationUi,
    onEnabled: (Boolean) -> Unit,
    onRulesChanged: (walking: Boolean, noisy: Boolean, stationary: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = stringResource(R.string.section_automation),
        subtitle = stringResource(R.string.automation_subtitle),
        modifier = modifier
    ) {
        SwitchRow(
            title = stringResource(R.string.automation_switch),
            subtitle = null,
            checked = settings.automationEnabled,
            onCheckedChange = onEnabled,
            icon = Icons.Outlined.AutoMode
        )
        AnimatedVisibility(
            visible = settings.automationEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Live context chips: what the engine currently sees.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContextChip(
                        icon = if (automation.activity == UserActivity.WALKING || automation.activity == UserActivity.RUNNING)
                            Icons.AutoMirrored.Outlined.DirectionsWalk else Icons.Outlined.Chair,
                        text = stringResource(automation.activity.label)
                    )
                    automation.ambientDb?.let {
                        ContextChip(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.ambient_db, it.roundToInt()))
                    }
                }
                RuleRow(
                    Icons.AutoMirrored.Outlined.DirectionsWalk, stringResource(R.string.rule_walking),
                    stringResource(R.string.mode_transparency), R.drawable.ic_transparency, settings.walkingRule
                ) { onRulesChanged(it, settings.noisyRule, settings.stationaryRule) }
                RuleRow(
                    Icons.Outlined.Hearing, stringResource(R.string.rule_noisy),
                    stringResource(R.string.mode_anc), R.drawable.ic_anc, settings.noisyRule
                ) { onRulesChanged(settings.walkingRule, it, settings.stationaryRule) }
                RuleRow(
                    Icons.Outlined.Chair, stringResource(R.string.rule_stationary),
                    stringResource(R.string.mode_adaptive), R.drawable.ic_adaptive, settings.stationaryRule
                ) { onRulesChanged(settings.walkingRule, settings.noisyRule, it) }

                automation.lastEvent?.let { event ->
                    Text(
                        stringResource(
                            R.string.automation_last,
                            stringResource(event.decision.mode.label),
                            stringResource(event.decision.reason.label).lowercase(),
                            relativeTime(LocalContext.current, event.atMillis)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                InfoNote(stringResource(R.string.automation_note), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun RuleRow(
    icon: ImageVector,
    condition: String,
    target: String,
    targetIcon: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(condition, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(painterResource(targetIcon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(target, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ContextChip(icon: ImageVector, text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) }
    )
}

@Composable
fun BackgroundCard(
    settings: AppSettings,
    notificationsGranted: Boolean,
    batteryOptimized: Boolean,
    onBackground: (Boolean) -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = stringResource(R.string.section_background), modifier = modifier) {
        SwitchRow(
            title = stringResource(R.string.background_title),
            subtitle = stringResource(R.string.background_subtitle),
            checked = settings.backgroundEnabled,
            onCheckedChange = onBackground,
            icon = Icons.Outlined.NotificationsActive
        )
        if (settings.backgroundEnabled && !notificationsGranted) {
            ActionNote(stringResource(R.string.notifications_needed), stringResource(R.string.action_allow), onRequestNotifications)
        }
        if (settings.backgroundEnabled && batteryOptimized) {
            ActionNote(stringResource(R.string.battery_optimized_note), stringResource(R.string.action_allow), onRequestBatteryExemption)
        }
    }
}

@Composable
private fun ActionNote(text: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.BatterySaver, null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** Transparent "what's real vs. fallback" panel. Useful for users and for debugging on-device. */
@Composable
fun ConnectionDetailsCard(status: AirPodsStatus, modifier: Modifier = Modifier) {
    val device = status.device ?: return
    SectionCard(title = stringResource(R.string.section_details), modifier = modifier) {
        DetailRow(
            stringResource(R.string.detail_model),
            device.modelNumber?.let { "${device.model.displayName} · $it" } ?: device.model.displayName
        )
        device.firmware?.let { DetailRow(stringResource(R.string.detail_firmware), it) }
        DetailRow(stringResource(R.string.detail_address), device.address)
        DetailRow(
            stringResource(R.string.detail_battery_source),
            stringResource(
                when (status.battery.source) {
                    com.example.airsync.domain.model.BatterySource.NONE -> R.string.source_none
                    com.example.airsync.domain.model.BatterySource.HEADSET_PROFILE -> R.string.source_hfp
                    com.example.airsync.domain.model.BatterySource.BLE_BROADCAST -> R.string.source_ble
                    com.example.airsync.domain.model.BatterySource.CONTROL_CHANNEL -> R.string.source_aap
                }
            )
        )
        DetailRow(
            stringResource(R.string.detail_control),
            stringResource(
                when (status.controlChannel) {
                    ControlChannel.CONNECTED -> R.string.control_connected
                    ControlChannel.CONNECTING -> R.string.control_connecting
                    ControlChannel.UNAVAILABLE -> R.string.control_unavailable
                }
            )
        )
        DetailRow(
            stringResource(R.string.detail_ear),
            stringResource(if (status.ear.isKnown) R.string.ear_detection_on else R.string.ear_detection_waiting)
        )
        DetailRow(stringResource(R.string.detail_mode), stringResource(status.noiseMode.label), status.noiseMode.iconRes)
    }
}

@Composable
private fun DetailRow(label: String, value: String, iconRes: Int? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (iconRes != null) {
            Icon(painterResource(iconRes), null, Modifier.padding(end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InlineAction(text: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 40.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}
