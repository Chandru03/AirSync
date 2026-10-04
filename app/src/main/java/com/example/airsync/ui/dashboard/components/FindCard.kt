package com.example.airsync.ui.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.airsync.R
import com.example.airsync.domain.model.LastSeenLocation
import com.example.airsync.domain.repository.FindSoundTarget
import com.example.airsync.ui.common.relativeTime
import com.example.airsync.ui.common.SoundWaves
import com.example.airsync.ui.common.rememberReduceMotion
import androidx.compose.foundation.layout.requiredSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

@Composable
fun FindCard(
    lastSeen: LastSeenLocation?,
    connected: Boolean,
    locationGranted: Boolean,
    soundPlaying: Boolean,
    onEnableLocation: () -> Unit,
    onOpenMaps: (LastSeenLocation) -> Unit,
    onPlaySound: (FindSoundTarget) -> Unit,
    onStopSound: () -> Unit,
    modifier: Modifier = Modifier
) {
    var target by rememberSaveable { mutableStateOf(FindSoundTarget.BOTH) }
    var confirm by rememberSaveable { mutableStateOf(false) }

    SectionCard(
        title = stringResource(R.string.section_find),
        subtitle = lastSeen?.let {
            stringResource(
                R.string.find_last_seen,
                relativeTime(LocalContext.current, it.timestampMillis),
                it.accuracyMeters.roundToInt()
            )
        } ?: stringResource(R.string.find_no_location),
        modifier = modifier
    ) {
        if (lastSeen != null) {
            MapPreview(lastSeen, Modifier.clickable { onOpenMaps(lastSeen) })
            FilledTonalButton(onClick = { onOpenMaps(lastSeen) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Map, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_open_maps))
            }
        } else if (!locationGranted) {
            OutlinedButton(onClick = onEnableLocation, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_enable_find))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.find_play_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    FindSoundTarget.BOTH to R.string.find_both,
                    FindSoundTarget.LEFT to R.string.battery_left,
                    FindSoundTarget.RIGHT to R.string.battery_right
                ).forEach { (t, label) ->
                    FilterChip(selected = target == t, onClick = { target = t }, label = { Text(stringResource(label)) })
                }
            }
            if (soundPlaying) {
                Button(
                    onClick = onStopSound,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    // Waves ripple out of the icon while the tone is playing.
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (!rememberReduceMotion()) {
                            SoundWaves(MaterialTheme.colorScheme.onError, Modifier.requiredSize(40.dp))
                        }
                        Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_stop_sound))
                }
            } else {
                Button(onClick = { confirm = true }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_play_sound))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(if (connected) R.string.find_case_unavailable else R.string.find_needs_connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
            title = { Text(stringResource(R.string.find_confirm_title)) },
            text = { Text(stringResource(R.string.find_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onPlaySound(target) }) { Text(stringResource(R.string.action_play_sound)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

/**
 * Lightweight static map: a 3×3 grid of OpenStreetMap raster tiles positioned so the last-seen
 * point sits in the centre. No SDK or API key required; attribution is shown as OSM requires.
 */
@Composable
private fun MapPreview(location: LastSeenLocation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        val zoom = 16
        val n = 1 shl zoom
        val latRad = location.latitude * PI / 180
        val worldX = (location.longitude + 180) / 360 * n * TILE
        val worldY = (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n * TILE
        val tileX = floor(worldX / TILE).toInt()
        val tileY = floor(worldY / TILE).toInt()
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // Screen offset of the centre tile's top-left corner.
        val originX = widthPx / 2 - (worldX - tileX * TILE).toFloat() * tileScale(density.density)
        val originY = heightPx / 2 - (worldY - tileY * TILE).toFloat() * tileScale(density.density)
        val tileSizePx = (TILE * tileScale(density.density)).toFloat()
        val tileDp = with(density) { tileSizePx.toDp() }

        for (dx in -1..1) for (dy in -1..1) {
            val x = Math.floorMod(tileX + dx, n)
            val y = tileY + dy
            if (y !in 0 until n) continue
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("https://tile.openstreetmap.org/$zoom/$x/$y.png")
                    .addHeader("User-Agent", "AirSync/1.1 (Android; AirPods companion)")
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(tileDp)
                    .offset { IntOffset((originX + dx * tileSizePx).roundToInt(), (originY + dy * tileSizePx).roundToInt()) }
            )
        }
        if (dark) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))

        // Accuracy halo + pin.
        Box(Modifier.align(Alignment.Center)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                modifier = Modifier.size(56.dp).align(Alignment.Center)
            ) {}
            Icon(
                Icons.Rounded.LocationOn, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp).align(Alignment.Center).offset(y = (-14).dp)
            )
        }
        Text(
            "© OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF3C4043),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(Color.White.copy(alpha = 0.8f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private const val TILE = 256.0

/** OSM tiles are 256 px at mdpi; scale to keep street labels legible on dense screens. */
private fun tileScale(density: Float): Float = (density / 1.5f).coerceAtLeast(1f)
