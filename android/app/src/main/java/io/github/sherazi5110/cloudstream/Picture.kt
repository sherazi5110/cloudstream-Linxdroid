package io.github.sherazi5110.cloudstream

import android.app.Activity
import android.os.Build
import android.view.Display
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * HDR on Android: video drawn into a TextureView (needed for pinch-to-zoom) is always shown in SDR. A
 * SurfaceView lets the hardware decoder hand HDR10 / HLG / Dolby Vision frames straight to an HDR screen.
 * So an HDR source on an HDR screen plays on a SurfaceView (pinch-to-zoom is off there; the zoom modes work).
 */
object Picture {
    private val animatedTypes = setOf(com.lagradost.cloudstream3.TvType.Anime, com.lagradost.cloudstream3.TvType.AnimeMovie,
        com.lagradost.cloudstream3.TvType.OVA, com.lagradost.cloudstream3.TvType.Cartoon)
    private val animatedTags = Regex("""\b(anime|animation|animated|cartoon|donghua)\b""", RegexOption.IGNORE_CASE)

    fun isAnimated(response: com.lagradost.cloudstream3.LoadResponse): Boolean =
        response.type in animatedTypes || response.tags.orEmpty().any { animatedTags.containsMatchIn(it) }

    /** Should this playback get Anime enhance (Settings → Player)? The same rule as on Windows and Linux. */
    fun enhance(response: com.lagradost.cloudstream3.LoadResponse, link: ExtractorLink?, setting: String = Settings.animeEnhance): Boolean = when (setting) {
        "ON" -> true
        "OFF" -> false
        // Auto: animated titles, unless the source is already 4K (nothing to gain).
        else -> isAnimated(response) && (link == null || link.quality <= 1440)
    }

    /** Set when this device's graphics chip can't run the enhance shader: it isn't offered again until restart. */
    @Volatile var enhanceUnsupported: String? = null

    private val hdrWords = Regex("""(?<![a-z0-9])(hdr10\+?|hdr|dv|dovi|dolby[ ._-]?vision|hlg|bt\.?2020)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    fun looksHdr(link: ExtractorLink?): Boolean =
        link != null && (hdrWords.containsMatchIn(link.name) || hdrWords.containsMatchIn(link.source) ||
            hdrWords.containsMatchIn(link.url.substringBefore('?').substringAfterLast('/')))

    /** HDR types the screen shows (HDR10, HLG, Dolby Vision…); empty on SDR screens. */
    fun screenHdrTypes(activity: Activity?): List<String> {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        @Suppress("DEPRECATION")
        val display: Display = activity.windowManager.defaultDisplay
        return display.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                4 -> "HDR10+"
                else -> "HDR"
            }
        }.orEmpty()
    }

    fun useHdrSurface(link: ExtractorLink?, screenHdr: Boolean, setting: String = Settings.hdrOutput): Boolean = when (setting) {
        "ON" -> true
        "OFF" -> false
        else -> screenHdr && looksHdr(link)
    }
}
