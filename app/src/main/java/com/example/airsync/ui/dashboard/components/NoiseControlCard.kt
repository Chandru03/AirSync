package com.example.airsync.ui.dashboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.airsync.ui.common.pressScale
import com.example.airsync.ui.common.rememberReduceMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import com.example.airsync.R
import com.example.airsync.domain.model.AncMode
import com.example.airsync.ui.common.description
import com.example.airsync.ui.common.iconRes
import com.example.airsync.ui.common.shortLabel
import kotlin.math.roundToInt

private val DisplayOrder = listOf(AncMode.ANC, AncMode.TRANSPARENCY, AncMode.ADAPTIVE, AncMode.OFF)

@Composable
fun NoiseControlCard(
    mode: AncMode,
    enabled: Boolean,
    canControlHardware: Boolean,
    level: Float,
    onModeSelected: (AncMode) -> Unit,
    onLevelChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = stringResource(R.string.section_noise_control),
        modifier = modifier
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "modeDescription"
        ) {
            Text(
                stringResource(it.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
            )
        }

        val haptics = LocalHapticFeedback.current
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DisplayOrder.forEach { option ->
                ModeTile(
                    mode = option,
                    selected = option == mode,
                    enabled = enabled,
                    onClick = {
                        if (option != mode) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onModeSelected(option)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // AirPods expose one adjustable strength over AAP (AutoANC, 0x2E) and it only applies in
        // Adaptive; Transparency has no amount control on AirPods 4, so no slider is shown for it.
        AnimatedVisibility(
            visible = mode == AncMode.ADAPTIVE,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            // Local state while dragging so DataStore isn't written 60×/s; commit on release.
            var dragValue by remember(level) { mutableFloatStateOf(level) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (mode == AncMode.ADAPTIVE) R.string.adaptive_level else R.string.transparency_level),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(dragValue * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = dragValue,
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = { onLevelChanged(dragValue) },
                    enabled = enabled
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(if (mode == AncMode.ADAPTIVE) R.string.level_less_noise else R.string.level_natural),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(if (mode == AncMode.ADAPTIVE) R.string.level_more_noise else R.string.level_amplified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (enabled && !canControlHardware) {
            InfoNote(stringResource(R.string.noise_control_simulated_note))
        } else if (!enabled) {
            InfoNote(stringResource(R.string.noise_control_disconnected_note))
        }
    }
}

@Composable
private fun ModeTile(
    mode: AncMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.primary else scheme.surfaceContainerHighest, label = "tileContainer"
    )
    val content by animateColorAsState(
        if (selected) scheme.onPrimary else scheme.onSurfaceVariant, label = "tileContent"
    )
    val corner by animateDpAsState(
        if (selected) 20.dp else 32.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "tileCorner"
    )
    val iconScale by animateFloatAsState(
        if (selected) 1.1f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale"
    )
    val reduce = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    // Selection burst: a ring that expands and fades from the tile the moment it's chosen.
    val burst = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !wasSelected && !reduce) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        }
        wasSelected = selected
    }
    val burstColor = scheme.primary

    Column(
        modifier = modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(corner),
            color = if (enabled) container else scheme.surfaceContainerHighest.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pressScale(interaction)
                .drawBehind {
                    val p = burst.value
                    if (p < 1f) {
                        val grow = 6.dp.toPx() * p * 3f
                        drawRoundRect(
                            color = burstColor.copy(alpha = 0.45f * (1f - p)),
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + grow * 2, size.height + grow * 2),
                            cornerRadius = CornerRadius(corner.toPx() + grow),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                .clip(RoundedCornerShape(corner))
                .indication(interaction, ripple())
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(mode.iconRes),
                    contentDescription = null,
                    tint = if (enabled) content else scheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(26.dp).scale(iconScale)
                )
            }
        }
        // Shrinks "Transparency" to fit a narrow tile rather than breaking it mid-word.
        Text(
            stringResource(mode.shortLabel),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )
    }
}
