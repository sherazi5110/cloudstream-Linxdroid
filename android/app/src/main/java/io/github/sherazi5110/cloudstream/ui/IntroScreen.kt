package io.github.sherazi5110.cloudstream.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// The Stream Goat logo's shapes (a 512 grid; the same as res/drawable/logo.xml and the desktop app's Logo.kt).
private const val MOUNTAIN = "M20 460 L126 270 L182 340 L256 222 L330 340 L388 262 L492 460 Z"
private const val SNOW = "M126 270 L150 312 L138 306 L126 318 L114 306 L102 310 Z M388 262 L413 306 L400 300 L388 312 L376 300 L364 304 Z"
private const val PEAK_SNOW = "M256 222 L282 263 L270 258 L256 270 L242 258 L230 263 Z"
private const val EYES = "M227.4 268.1a7.6 10.1 0 1 0 15.2 0a7.6 10.1 0 1 0 -15.2 0Z M269.4 268.1a7.6 10.1 0 1 0 15.2 0a7.6 10.1 0 1 0 -15.2 0Z M242.6 353.8a13.4 7.6 0 1 0 26.8 0a13.4 7.6 0 1 0 -26.8 0Z"
private val GOAT_PARTS = listOf(
        "M 230.8 214.3 C 217.4 163.9 192.2 125.3 148.5 110.2 C 180.4 137 198.9 179 205.6 227.8Z",
        "M 281.2 214.3 C 294.6 163.9 319.8 125.3 363.5 110.2 C 331.6 137 313.1 179 306.4 227.8Z",
        "M 214 246.2 C 183.8 231.1 153.5 236.2 133.4 258 C 165.3 269.8 192.2 268.1 217.4 263Z",
        "M 298 246.2 C 328.2 231.1 358.5 236.2 378.6 258 C 346.7 269.8 319.8 268.1 294.6 263Z",
        "M 205.6 216 Q 256 195.8 306.4 216 L 298 300 Q 291.3 352.1 269.4 372.2 Q 256 380.6 242.6 372.2 Q 220.7 352.1 214 300Z",
        "M 240.9 370.6 Q 256 427.7 271.1 370.6Z",
)

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

/**
 * The startup intro (about 3 seconds): snowy peaks rise out of a night sky, an arc of stars sweeps over the
 * summit, the goat appears and the name and credit fade in. A tap skips it; Settings → Appearance turns it off.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val accent = Palette.accent
    val rise = remember { Animatable(1f) }
    val sweep = remember { Animatable(0f) }
    val goat = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val twinkles = remember { List(60) { Triple(Random.nextFloat(), Random.nextFloat() * 0.55f, 0.6f + Random.nextFloat() * 1.4f) } }
    val shapes = remember { Triple(path(MOUNTAIN), path(SNOW) to path(PEAK_SNOW), GOAT_PARTS.map(::path) to path(EYES)) }
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    // Ends the intro even if no animation frames come (screen off, a slow device): it must never hold the app up.
    LaunchedEffect(Unit) { delay(5000); done() }
    LaunchedEffect(Unit) {
        coroutineScope {
            listOf(
                async { rise.animateTo(0f, tween(1100, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) },
                async { delay(450); sweep.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) },
                async { delay(1150); goat.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) },
                async { delay(1700); words.animateTo(1f, tween(800)) },
            ).awaitAll()
        }
        delay(500)
        done()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF05060C))
            .clickable(remember { MutableInteractionSource() }, null) { done() }
            .onKeyEvent { done(); true },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // A 1000x620 board, scaled to cover the screen.
            val s = max(size.width / 1000f, size.height / 620f)
            val ox = (size.width - 1000f * s) / 2
            val oy = (size.height - 620f * s) / 2
            drawRect(Brush.verticalGradient(listOf(Color(0xFF05060C), lerp(accent, Color(0xFF05060C), 0.82f), Color(0xFF0B0B10))))
            translate(ox, oy) {
                scale(s, s, pivot = Offset.Zero) {
                    twinkles.forEach { (x, y, r) -> drawCircle(Color.White.copy(alpha = 0.18f + (r - 0.6f) / 3f), r, Offset(x * 1000f, y * 620f)) }
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.32f * (1 - rise.value)), Color.Transparent), Offset(500f, 300f), 320f), 320f, Offset(500f, 300f))
                    // Stars sweep in along the arc over the summit.
                    val count = 15
                    for (i in 0 until count) {
                        val t = ((sweep.value * 1.6f) - i * 0.04f).coerceIn(0f, 1f)
                        if (t <= 0f) continue
                        val final = 200f + 140f * i / (count - 1)
                        val angle = Math.toRadians((final - 120f * (1 - t)).toDouble())
                        val c = Offset(500f + 230f * cos(angle).toFloat(), 250f + 230f * sin(angle).toFloat())
                        drawStar(c, 9f, Color.White.copy(alpha = t))
                    }
                    translate(500f - 256f * 1.9f, 620f - 460f * 1.9f + 380f * rise.value) {
                        scale(1.9f, 1.9f, pivot = Offset.Zero) {
                            drawPath(shapes.first, Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.15f), lerp(accent, Color.Black, 0.6f)), 222f, 460f))
                            drawPath(shapes.second.first, Color.White); drawPath(shapes.second.second, Color.White)
                        }
                    }
                    if (goat.value > 0f) {
                        val g = 0.62f * (0.6f + 0.4f * goat.value)
                        translate(500f - 256f * g, 196f - 300f * g) {
                            scale(g, g, pivot = Offset.Zero) {
                                shapes.third.first.forEach { p ->
                                    drawPath(p, Color.White.copy(alpha = goat.value))
                                    drawPath(p, Color(0xFF0B0B10).copy(alpha = goat.value), style = Stroke(width = 9.5f, join = StrokeJoin.Round))
                                }
                                drawPath(shapes.third.second, Color(0xFF0B0B10).copy(alpha = goat.value))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp).graphicsLayer { alpha = words.value; translationY = 18f * (1 - words.value) },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Stream Goat", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Text("Created by Syed Ali Hamza", color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStar(c: Offset, r: Float, color: Color) {
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
