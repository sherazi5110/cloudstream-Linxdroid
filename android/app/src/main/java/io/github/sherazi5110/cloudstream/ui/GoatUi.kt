package io.github.sherazi5110.cloudstream.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The Stream Goat logo: Syed Ali Hamza's goat eating grass in front of a circle, the artwork's rainbow or the
 * theme's accent colour (Settings → Appearance → Logo). Follows theme changes.
 */
@Composable
fun Logo(size: Dp = 36.dp) {
    val px = with(LocalDensity.current) { (size.toPx() * 1.25f).roundToInt().coerceIn(16, 768) }
    val rainbow = ThemeState.logoRainbow
    val accent = Palette.accent.toArgb()
    val bitmap = remember(px, rainbow, accent) { runCatching { GoatArt.logo(px, rainbow, accent).asImageBitmap() }.getOrNull() }
    if (bitmap != null) Image(bitmap, contentDescription = "Stream Goat", modifier = Modifier.size(size))
    else Image(painterResource(R.drawable.logo), contentDescription = "Stream Goat", modifier = Modifier.size(size))
}

/** An animation's frames, painted in the background in the current logo style (null until each is ready). */
@Composable
fun rememberGoatFrames(kind: String, px: Int): SnapshotStateList<ImageBitmap?> {
    val rainbow = ThemeState.logoRainbow
    val accent = Palette.accent.toArgb()
    val frames = remember(kind, px, rainbow, accent) { mutableStateListOf<ImageBitmap?>().apply { repeat(runCatching { GoatArt.count(kind) }.getOrDefault(0)) { add(null) } } }
    LaunchedEffect(frames) {
        for (i in frames.indices) {
            val b = withContext(Dispatchers.Default) { runCatching { GoatArt.frame(kind, i, px, rainbow, accent).asImageBitmap() }.getOrNull() }
            frames[i] = b
        }
    }
    return frames
}

/**
 * No internet: the goat's offline animation (ten pictures, one after the other), Try again, and the way to the
 * downloads, which still play.
 */
@Composable
fun OfflineState(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    val frames = rememberGoatFrames("offline", 480)
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames.size) {
        while (frames.isNotEmpty()) { delay(1700); index = (index + 1) % frames.size }
    }
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The pictures have room around the circle for their signs and cables.
        Box(Modifier.size(260.dp).padding(bottom = 0.dp), contentAlignment = Alignment.Center) {
            Crossfade(targetState = index, animationSpec = tween(280), label = "offline") { i ->
                // Until a frame is ready, the one before it (or the first) stays.
                (frames.getOrNull(i) ?: frames.take(i).lastOrNull { it != null } ?: frames.firstOrNull())?.let {
                    Image(it, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Text("You're offline", style = MaterialTheme.typography.titleLarge)
        Text("Stream Goat can't reach the internet. Check your Wi-Fi, then try again. Downloaded titles still play.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = Palette.muted)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Try again", Icons.Filled.Refresh, compact = true) { onRetry() }
            PillButton("Downloads", Icons.Filled.Download, primary = false, compact = true) { openLibraryDownloads = true; Nav.select(Tab.LIBRARY) }
        }
    }
}
