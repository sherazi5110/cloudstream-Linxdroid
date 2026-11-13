package io.github.sherazi5110.cloudstream.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Each eating picture shows this long; the next one blends in over [FADE_MS]. */
private const val FRAME_MS = 300
private const val FADE_MS = 120
/** The goat's size on the 1000x620 board. */
private const val GOAT = 440f

/**
 * The startup intro (about 3 seconds): Syed Ali Hamza's goat chews its grass (the eating animation, in the logo's
 * colours) while an arc of stars sweeps over it, then the name and credit fade in. A tap or any key skips it;
 * Settings → Appearance turns it off.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val accent = Palette.accent
    val frames = rememberGoatFrames("eat", 480)
    val glow = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val goat = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val clock = remember { Animatable(0f) }
    val twinkles = remember { List(60) { Triple(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat() * 1.4f) } }
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    // Ends the intro even if no animation frames come (screen off, a slow device): it must never hold the app up.
    LaunchedEffect(Unit) { delay(5000); done() }
    LaunchedEffect(Unit) {
        coroutineScope {
            listOf(
                async { glow.animateTo(1f, tween(1200)) },
                async { goat.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) },
                async { delay(350); sweep.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) },
                async { delay(1400); words.animateTo(1f, tween(800)) },
                // Through the ten pictures: 0 → 10 over 3 s.
                async { val n = frames.size.coerceAtLeast(1); clock.animateTo(n.toFloat(), tween(n * FRAME_MS, easing = LinearEasing)) },
            ).awaitAll()
        }
        delay(200)
        done()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF05060C))
            .clickable(remember { MutableInteractionSource() }, null) { done() }
            .onKeyEvent { done(); true },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // A 1000x620 board, scaled to fit inside the screen (the sky fills the rest), so nothing is cut off.
            val s = min(size.width / 1000f, size.height / 620f)
            val ox = (size.width - 1000f * s) / 2
            val oy = (size.height - 620f * s) / 2
            drawRect(Brush.verticalGradient(listOf(Color(0xFF05060C), lerp(accent, Color(0xFF05060C), 0.85f), Color(0xFF0B0B10))))
            translate(ox, oy) {
                scale(s, s, pivot = Offset.Zero) {
                    twinkles.forEach { (x, y, r) -> drawCircle(Color.White.copy(alpha = 0.15f + (r - 0.6f) / 3.5f), r, Offset(x * 1000f, y * 620f)) }
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.38f * glow.value), Color.Transparent), Offset(500f, 250f), 340f), 340f, Offset(500f, 250f))
                    // Stars sweep in along the arc over the goat.
                    val count = 15
                    for (i in 0 until count) {
                        val t = ((sweep.value * 1.6f) - i * 0.04f).coerceIn(0f, 1f)
                        if (t <= 0f) continue
                        val final = 195f + 150f * i / (count - 1)
                        val angle = Math.toRadians((final - 120f * (1 - t)).toDouble())
                        val c = Offset(500f + 225f * cos(angle).toFloat(), 250f + 225f * sin(angle).toFloat())
                        drawStar(c, 9f, Color.White.copy(alpha = t))
                    }
                    // The goat: pops in, then the pictures follow each other.
                    if (goat.value > 0f) {
                        val pop = 0.6f + 0.4f * goat.value + 0.08f * sin(Math.PI * goat.value).toFloat()
                        val i = clock.value.toInt().coerceIn(0, (frames.size - 1).coerceAtLeast(0))
                        val fade = ((clock.value - i) * FRAME_MS / FADE_MS).coerceIn(0f, 1f)
                        val current = frames.getOrNull(i) ?: frames.take(i).lastOrNull { it != null }
                        val previous = if (i > 0) frames.take(i).lastOrNull { it != null } else null
                        translate(500f, 250f) {
                            scale(pop, pop, pivot = Offset.Zero) {
                                if (previous != null && previous !== current && fade < 1f) drawGoat(previous, goat.value)
                                if (current != null) drawGoat(current, goat.value * (if (previous != null && previous !== current) fade else 1f))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).graphicsLayer { alpha = words.value; translationY = 18f * (1 - words.value) },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Stream Goat", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text("Created by Syed Ali Hamza", color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A frame centred on the origin, [GOAT] board units square. */
private fun DrawScope.drawGoat(image: ImageBitmap, alpha: Float) {
    val half = GOAT / 2
    drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset((-half).roundToInt(), (-half).roundToInt()),
        IntSize(GOAT.roundToInt(), GOAT.roundToInt()), alpha = alpha.coerceIn(0f, 1f), filterQuality = FilterQuality.High)
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (k in 0 until 10) {
        val rad = if (k % 2 == 0) r else r * 0.45f
        val a = Math.toRadians(-90.0 + k * 36.0)
        val x = c.x + rad * cos(a).toFloat(); val y = c.y + rad * sin(a).toFloat()
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}
