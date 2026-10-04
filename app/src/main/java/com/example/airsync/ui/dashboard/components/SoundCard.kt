package com.example.airsync.ui.dashboard.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.drawscope.clipRect
import com.example.airsync.ui.common.Motion
import com.example.airsync.ui.common.rememberReduceMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.airsync.R
import com.example.airsync.domain.model.EqCurves
import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.model.SpatialAudioState
import com.example.airsync.domain.model.SpatialAudioStatus
import androidx.compose.material.icons.outlined.SpatialAudio
import androidx.compose.material.icons.outlined.SpatialAudioOff
import androidx.compose.material3.HorizontalDivider
import com.example.airsync.ui.common.label
import com.example.airsync.ui.main.AppEqEntry
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

@Composable
fun SoundCard(
    spatialAudio: SpatialAudioState,
    airPodsConnected: Boolean,
    preset: EqPreset,
    apps: List<AppEqEntry>,
    onPresetSelected: (EqPreset) -> Unit,
    onAppPresetSelected: (String, EqPreset?) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = stringResource(R.string.section_sound),
        subtitle = stringResource(R.string.sound_subtitle),
        modifier = modifier
    ) {
        SpatialAudioRow(spatialAudio, airPodsConnected)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        EqCurve(preset, Modifier.fillMaxWidth().height(112.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EqPreset.entries.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == preset,
                    onClick = { onPresetSelected(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, EqPreset.entries.size),
                    label = { Text(stringResource(p.label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.per_app_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (apps.isEmpty()) {
                InfoNote(stringResource(R.string.per_app_empty), icon = Icons.Outlined.Apps)
            } else {
                apps.forEach { app -> AppPresetRow(app, preset, onAppPresetSelected) }
            }
        }
    }
}

@Composable
private fun AppPresetRow(app: AppEqEntry, defaultPreset: EqPreset, onSelected: (String, EqPreset?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.active) {
                Text(
                    stringResource(R.string.per_app_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    app.preset?.let { stringResource(it.label) }
                        ?: stringResource(R.string.per_app_default, stringResource(defaultPreset.label))
                )
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.per_app_use_default)) },
                    onClick = { onSelected(app.packageName, null); expanded = false }
                )
                EqPreset.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(stringResource(p.label)) },
                        onClick = { onSelected(app.packageName, p); expanded = false }
                    )
                }
            }
        }
    }
}

/**
 * Frequency-response preview. Morphs between presets, draws itself in on first appearance, and
 * sits on a dB grid with Bass / Mid / Treble markers so even a flat "Balanced" reads as intended.
 */
@Composable
private fun EqCurve(preset: EqPreset, modifier: Modifier = Modifier) {
    val samples = 48
    val targets = remember(preset) {
        FloatArray(samples) { i -> EqCurves.gainAt(preset, frequencyAt(i, samples)) }
    }
    val animated = remember { Array(samples) { Animatable(0f) } }
    LaunchedEffect(targets) {
        // All points animate in parallel so the whole curve morphs as one.
        animated.forEachIndexed { i, a ->
            launch { a.animateTo(targets[i], Motion.bouncy()) }
        }
    }
    // Left-to-right reveal, once per session.
    val reduce = rememberReduceMotion()
    var revealed by rememberSaveable { mutableStateOf(reduce) }
    val reveal = remember { Animatable(if (revealed) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!revealed) {
            reveal.animateTo(1f, tween(900, delayMillis = 250, easing = FastOutSlowInEasing))
            revealed = true
        }
    }

    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val cd = stringResource(R.string.eq_curve_cd, stringResource(preset.label))
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = cd }) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val pad = 6.dp.toPx()
            val mid = size.height / 2
            val dbToY = { db: Float -> mid - (db / EqCurves.MAX_ABS_DB) * (size.height / 2 - pad) }
            val hzToX = { hz: Float ->
                size.width * ((ln(hz) - ln(EqCurves.MIN_HZ)) / (ln(EqCurves.MAX_HZ) - ln(EqCurves.MIN_HZ)))
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
            // Grid: ±6 dB faint, 0 dB stronger; decade markers at 100 Hz, 1 kHz, 10 kHz.
            listOf(6f, -6f).forEach { db ->
                drawLine(grid.copy(alpha = 0.35f), Offset(0f, dbToY(db)), Offset(size.width, dbToY(db)), 1.dp.toPx(), pathEffect = dash)
            }
            drawLine(grid, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx(), pathEffect = dash)
            listOf(100f, 1_000f, 10_000f).forEach { hz ->
                drawLine(grid.copy(alpha = 0.25f), Offset(hzToX(hz), 0f), Offset(hzToX(hz), size.height), 1.dp.toPx())
            }

            val path = Path()
            animated.forEachIndexed { i, a ->
                val x = size.width * i / (samples - 1)
                val y = dbToY(a.value)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Shade between the curve and 0 dB: boosts glow upward, cuts downward.
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, mid)
                lineTo(0f, mid)
                close()
            }
            clipRect(right = size.width * reveal.value) {
                drawPath(fill, line.copy(alpha = 0.16f))
                drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(R.string.eq_band_bass, R.string.eq_band_mid, R.string.eq_band_treble).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = label)
            }
        }
    }
}

private fun frequencyAt(i: Int, samples: Int): Float {
    val t = i.toFloat() / (samples - 1)
    return exp(ln(EqCurves.MIN_HZ) + t * (ln(EqCurves.MAX_HZ) - ln(EqCurves.MIN_HZ)))
}

/** Read-only status of Android's spatializer for the current output. */
@Composable
private fun SpatialAudioRow(state: SpatialAudioState, airPodsConnected: Boolean) {
    val active = state.status == SpatialAudioStatus.ACTIVE
    val (title, body) = when (state.status) {
        SpatialAudioStatus.ACTIVE -> stringResource(
            if (airPodsConnected) R.string.spatial_on_airpods else R.string.spatial_on
        ) to stringResource(if (state.headTracking) R.string.spatial_body_tracked else R.string.spatial_body_fixed)
        SpatialAudioStatus.ON_INACTIVE -> stringResource(R.string.spatial_paused) to stringResource(R.string.spatial_body_paused)
        SpatialAudioStatus.OFF -> stringResource(R.string.spatial_off) to stringResource(R.string.spatial_body_off)
        SpatialAudioStatus.UNSUPPORTED -> stringResource(R.string.spatial_unsupported) to stringResource(R.string.spatial_body_unsupported)
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            if (active) Icons.Outlined.SpatialAudio else Icons.Outlined.SpatialAudioOff,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.spatial_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
