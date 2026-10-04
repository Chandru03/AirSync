package com.example.airsync.ui.common

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared motion language for AirSync: one set of springs/durations, and every animation honours
 * Android's "Remove animations" accessibility setting (animator duration scale = 0).
 */
object Motion {
    /** Snappy, slightly bouncy — for presses and selections. */
    fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    /** Calm settle — for values that should feel physical but not playful. */
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    const val EMPHASIZED_MS = 500
    const val STAGGER_MS = 70
}

/** True when the user turned animations off (Settings › Accessibility › Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

// ---------------------------------------------------------------------------------------------
// Staggered entrance: one timeline for the whole screen, each card offset by its index.
// Runs once per launch (survives scrolling and rotation/folding thanks to rememberSaveable).
// ---------------------------------------------------------------------------------------------

private val LocalEntrance = compositionLocalOf<(() -> Float)?> { null }

@Composable
fun EntranceHost(content: @Composable () -> Unit) {
    val reduce = rememberReduceMotion()
    var played by rememberSaveable { androidx.compose.runtime.mutableStateOf(reduce) }
    val clock = remember { Animatable(if (played) 10f else 0f) }
    LaunchedEffect(Unit) {
        if (!played) {
            clock.animateTo(10f, tween(durationMillis = 1_400, easing = LinearEasing))
            played = true
        }
    }
    CompositionLocalProvider(LocalEntrance provides { clock.value }) { content() }
}

/**
 * Fades and lifts a card into place. [index] staggers it after the previous cards. The clock is
 * read inside graphicsLayer, so the animation never triggers recomposition.
 */
fun Modifier.entrance(index: Int): Modifier = composed {
    val clock = LocalEntrance.current ?: return@composed this
    graphicsLayer {
        // Clock runs 0..10 over 1.4 s; each card animates over 0.45 s after its stagger.
        val elapsedMs = clock() * 140f
        val t = ((elapsedMs - index * Motion.STAGGER_MS) / 450f).coerceIn(0f, 1f)
        val eased = FastOutSlowInEasing.transform(t)
        alpha = eased
        translationY = (1f - eased) * 24.dp.toPx()
    }
}

/** Squish on press: the classic Material 3 Expressive press feedback. */
fun Modifier.pressScale(interactionSource: InteractionSource, pressed: Float = 0.94f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, Motion.bouncy(), label = "pressScale")
    this.scale(scale)
}

/** A status dot that breathes softly, like the "live" indicators in Google's apps. */
@Composable
fun BreathingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val reduce = rememberReduceMotion()
    val t by rememberInfiniteTransition(label = "breathe").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t"
    )
    Box(modifier.size(size * 2.5f), contentAlignment = Alignment.Center) {
        if (!reduce) {
            Canvas(Modifier.size(size * 2.5f)) {
                drawCircle(color.copy(alpha = 0.28f * (1f - t)), radius = this.size.minDimension / 2 * (0.4f + 0.6f * t))
            }
        }
        Box(Modifier.size(size).background(color, RoundedCornerShape(50)))
    }
}

/** Three bouncing bars — "someone is talking". Used for live conversation awareness. */
@Composable
fun VoiceBars(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "voice")
    val phases = listOf(0, 160, 320)
    Row(modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        phases.forEach { delay ->
            val h by transition.animateFloat(
                0.3f, 1f,
                infiniteRepeatable(tween(420, delayMillis = delay, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar$delay"
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(h)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

/** Rings rippling outward — "sound is playing". Drawn behind an icon. */
@Composable
fun SoundWaves(color: Color, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "waves").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "t"
    )
    Canvas(modifier) {
        repeat(3) { i ->
            val p = (t + i / 3f) % 1f
            drawCircle(
                color.copy(alpha = 0.45f * (1f - p)),
                radius = size.minDimension / 2 * (0.35f + 0.65f * p),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
