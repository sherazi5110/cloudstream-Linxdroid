package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Picture improvements, decided per playback.
 *
 * Anime enhance: animation is flat colour with hard lines, and a low-resolution source stretched to the screen
 * the usual way (bilinear) turns those lines soft and smeary. With it on, VLC upscales the video to the screen
 * itself with Lanczos, which keeps line edges crisp. Measured on a 240p test clip shown at 720p: 0.6 dB closer
 * to the original than before, 0.7 dB along the lines. (VLC's own sharpen and denoise filters were tried too:
 * sharpen draws white halos around every line and scored worse, denoise made no measurable difference, so
 * neither is used.)
 *
 * HDR: HDR10 / Dolby Vision / HLG video drawn as ordinary (SDR) colour looks grey and washed out. Sources
 * whose name says they are HDR get a contrast and saturation correction.
 */
object Picture {
    private val animatedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Cartoon)
    private val animatedTags = Regex("""\b(anime|animation|animated|cartoon|donghua)\b""", RegexOption.IGNORE_CASE)
    private val hdrWords = Regex("""(?<![a-z0-9])(hdr10\+?|hdr|dv|dovi|dolby[ ._-]?vision|hlg|bt\.?2020)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    fun isAnimated(response: LoadResponse): Boolean =
        response.type in animatedTypes || response.tags.orEmpty().any { animatedTags.containsMatchIn(it) }

    /** Should this playback get the anime filters? [setting] is Settings.animeEnhance. */
    fun enhance(response: LoadResponse, link: ExtractorLink?, setting: String = Settings.animeEnhance, saver: Boolean = Settings.batterySaver): Boolean = when (setting) {
        "ON" -> true
        "OFF" -> false
        // Auto: animated titles, unless the source is already 4K (nothing to gain, lots of CPU) or the battery saver is on.
        else -> !saver && isAnimated(response) && (link == null || link.quality <= 1440)
    }

    fun looksHdr(link: ExtractorLink?): Boolean =
        link != null && (hdrWords.containsMatchIn(link.name) || hdrWords.containsMatchIn(link.source) ||
            hdrWords.containsMatchIn(link.url.substringBefore('?').substringAfterLast('/')))

    fun hdrFix(link: ExtractorLink?, setting: String = Settings.hdrFix): Boolean = when (setting) {
        "ON" -> true
        "OFF" -> false
        else -> looksHdr(link)
    }

    val modes = listOf("AUTO" to "Auto", "ON" to "Always", "OFF" to "Off")
    fun modeName(v: String) = modes.firstOrNull { it.first == v }?.second ?: "Auto"
}
