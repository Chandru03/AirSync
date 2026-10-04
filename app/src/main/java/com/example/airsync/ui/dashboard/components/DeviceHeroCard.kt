package com.example.airsync.ui.dashboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import com.example.airsync.domain.model.AncMode
import com.example.airsync.ui.common.BreathingDot
import com.example.airsync.ui.common.Motion
import com.example.airsync.ui.common.rememberReduceMotion
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.airsync.R
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.BatterySource
import com.example.airsync.domain.model.BluetoothStatus
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.model.WearState
import com.example.airsync.ui.common.label
import com.example.airsync.ui.common.relativeTime
import androidx.compose.ui.platform.LocalContext
import com.example.airsync.ui.theme.BatteryCharging
import com.example.airsync.ui.theme.BatteryLow

@Composable
fun DeviceHeroCard(
    status: AirPodsStatus,
    connecting: Boolean,
    onConnect: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onChooseDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // The hero's glow follows the listening mode, so the card itself says what you're hearing.
    val glowTarget = when {
        !status.isConnected -> scheme.primaryContainer
        status.noiseMode == AncMode.ANC -> scheme.primaryContainer
        status.noiseMode == AncMode.TRANSPARENCY -> scheme.tertiaryContainer
        status.noiseMode == AncMode.ADAPTIVE -> scheme.secondaryContainer
        else -> scheme.surfaceContainerHighest
    }
    val glow by animateColorAsState(glowTarget, tween(700), label = "heroGlow")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainer
    ) {
        Box(
            Modifier.background(
                Brush.verticalGradient(listOf(glow.copy(alpha = 0.6f), scheme.surfaceContainer))
            )
        ) {
            val state = when {
                status.bluetooth == BluetoothStatus.OFF -> HeroState.BLUETOOTH_OFF
                status.isConnected -> HeroState.CONNECTED
                else -> HeroState.DISCONNECTED
            }
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (fadeIn(tween(350, delayMillis = 90)) + scaleIn(tween(350, delayMillis = 90), initialScale = 0.94f)) togetherWith
                        fadeOut(tween(150)) using SizeTransform(clip = false)
                },
                label = "hero"
            ) { target ->
                when (target) {
                    HeroState.CONNECTED -> ConnectedHero(status)
                    HeroState.DISCONNECTED -> EmptyHero(
                        icon = { ScanningPulse() },
                        title = stringResource(R.string.hero_not_connected_title),
                        body = stringResource(R.string.hero_not_connected_body),
                        primaryLabel = stringResource(if (connecting) R.string.connect_connecting else R.string.action_connect),
                        onPrimary = onConnect,
                        primaryBusy = connecting,
                        secondaryLabel = stringResource(R.string.action_bluetooth_settings),
                        onSecondary = onOpenBluetoothSettings
                    )
                    HeroState.BLUETOOTH_OFF -> EmptyHero(
                        icon = {
                            Icon(Icons.Outlined.BluetoothDisabled, null, Modifier.size(40.dp), tint = scheme.onSurfaceVariant)
                        },
                        title = stringResource(R.string.hero_bt_off_title),
                        body = stringResource(R.string.hero_bt_off_body),
                        primaryLabel = stringResource(R.string.action_turn_on),
                        onPrimary = onEnableBluetooth
                    )
                }
            }
        }
    }
}

private enum class HeroState { CONNECTED, DISCONNECTED, BLUETOOTH_OFF }

private const val CASE_FRESH_MS = 2 * 60 * 1000L

@Composable
private fun ConnectedHero(status: AirPodsStatus) {
    val device = status.device ?: return
    Column(
        Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                device.name,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BreathingDot(BatteryCharging, Modifier.offset(x = (-6).dp))
                Text(
                    stringResource(R.string.status_connected) + " ·",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(x = (-6).dp)
                )
                // The mode name slides up when it changes (from the app, the tile or a stem press).
                AnimatedContent(
                    targetState = status.noiseMode,
                    transitionSpec = {
                        (slideInVertically(Motion.gentle()) { it / 2 } + fadeIn()) togetherWith
                            (slideOutVertically(Motion.gentle()) { -it / 2 } + fadeOut())
                    },
                    label = "heroMode",
                    modifier = Modifier.offset(x = (-6).dp)
                ) { mode ->
                    Text(
                        stringResource(mode.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val battery = status.battery
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (battery.source == BatterySource.HEADSET_PROFILE) {
                BatteryGauge(
                    label = stringResource(R.string.battery_airpods),
                    battery = PodBattery(battery.lowestBud),
                    iconRes = R.drawable.ic_bud_pair,
                    wear = null
                )
            } else {
                BatteryGauge(stringResource(R.string.battery_left), battery.left, R.drawable.ic_bud_left, status.ear.left)
                BatteryGauge(stringResource(R.string.battery_right), battery.right, R.drawable.ic_bud_right, status.ear.right)
            }
            val caseAge = battery.caseUpdatedAtMillis.takeIf { it > 0L && battery.case.level != null }
                ?.let { at -> if (System.currentTimeMillis() - at > CASE_FRESH_MS) relativeTime(LocalContext.current, at) else null }
            BatteryGauge(stringResource(R.string.battery_case), battery.case, R.drawable.ic_case, null, sublabel = caseAge)
        }

        val hint = when {
            battery.source == BatterySource.NONE -> R.string.battery_hint_waiting
            battery.source == BatterySource.HEADSET_PROFILE -> R.string.battery_hint_hfp
            battery.case.level == null -> R.string.battery_hint_case_lid
            else -> null
        }
        if (hint != null) {
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Circular gauge with the component glyph inside, percentage and wear state below. */
@Composable
private fun BatteryGauge(
    label: String,
    battery: PodBattery,
    iconRes: Int,
    wear: WearState?,
    size: Dp = 84.dp,
    /** Overrides the sublabel (e.g. "12 min ago" for a remembered case reading). */
    sublabel: String? = null
) {
    val level = battery.level
    val reduce = rememberReduceMotion()
    val progress by animateFloatAsState(
        targetValue = (level ?: 0) / 100f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "battery"
    )
    // First appearance: the ring sweeps in and the number counts up with it (once per session).
    var introDone by rememberSaveable(label) { mutableStateOf(reduce) }
    val intro = remember { Animatable(if (introDone) 1f else 0f) }
    LaunchedEffect(level != null) {
        if (!introDone && level != null) {
            intro.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing))
            introDone = true
        }
    }
    // Charging breathes; low battery pulses a little faster to draw the eye.
    val urgent = level != null && !battery.charging && level <= 20
    val pulse = if (!reduce && (battery.charging || urgent)) {
        val t by rememberInfiniteTransition(label = "ringPulse").animateFloat(
            0.55f, 1f,
            infiniteRepeatable(tween(if (urgent) 700 else 1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse"
        )
        t
    } else 1f
    // A bud being put in the ear gives a tiny "pop".
    val pop = remember { Animatable(1f) }
    var lastWear by remember { mutableStateOf(wear) }
    LaunchedEffect(wear) {
        if (!reduce && wear == WearState.IN_EAR && lastWear != null && lastWear != WearState.IN_EAR) {
            pop.animateTo(1.18f, tween(120))
            pop.animateTo(1f, Motion.bouncy())
        }
        lastWear = wear
    }
    val scheme = MaterialTheme.colorScheme
    val ringColor by animateColorAsState(
        when {
            level == null -> scheme.outlineVariant
            battery.charging -> BatteryCharging
            level <= 20 -> BatteryLow
            else -> scheme.primary
        },
        label = "ringColor"
    )
    val track = scheme.surfaceContainerHighest
    val wearText = wear?.let {
        when (it) {
            WearState.IN_EAR -> stringResource(R.string.wear_in_ear)
            WearState.IN_CASE -> stringResource(R.string.wear_in_case)
            WearState.OUT_OF_EAR -> stringResource(R.string.wear_out)
            WearState.UNKNOWN -> null
        }
    }
    val shownLevel = level?.let { if (intro.value < 1f) (it * intro.value).roundToInt() else it }
    val levelText = shownLevel?.let { "$it%" } ?: "—"
    val cd = buildString {
        append(label).append(", ").append(level?.let { "$it percent" } ?: "unknown")
        if (battery.charging) append(", charging")
        wearText?.let { append(", ").append(it) }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = cd }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Canvas(Modifier.size(size)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                if (level != null) {
                    drawArc(
                        ringColor.copy(alpha = pulse), -90f, 360f * progress * intro.value, false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(size * 0.42f).scale(pop.value).alpha(if (level == null) 0.5f else 1f),
                tint = scheme.onSurface
            )
            if (battery.charging) {
                Icon(
                    Icons.Rounded.Bolt, null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .background(scheme.surfaceContainer, CircleShape)
                        .padding(2.dp),
                    tint = BatteryCharging
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(levelText, style = MaterialTheme.typography.titleMedium)
            AnimatedContent(
                targetState = sublabel ?: wearText ?: label,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                label = "wearLabel"
            ) { text ->
                Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyHero(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryBusy: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) { icon() }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        // Flows onto two rows in a narrow pane instead of wrapping button labels.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            if (secondaryLabel != null) TextButton(onClick = onSecondary) { Text(secondaryLabel, maxLines = 1) }
            FilledTonalButton(onClick = onPrimary, enabled = !primaryBusy) {
                if (primaryBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Bluetooth, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(primaryLabel, maxLines = 1)
            }
        }
    }
}

/** Soft radar pulse behind the case glyph while we wait for AirPods. */
@Composable
private fun ScanningPulse() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "t"
    )
    val color = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics { }) {
        Canvas(Modifier.size(88.dp)) {
            drawCircle(color.copy(alpha = 0.25f * (1 - t)), radius = size.minDimension / 2 * (0.5f + 0.5f * t))
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Outlined.BluetoothSearching, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
