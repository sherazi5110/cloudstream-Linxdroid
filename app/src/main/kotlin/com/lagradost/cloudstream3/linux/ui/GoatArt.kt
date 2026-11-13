package com.lagradost.cloudstream3.linux.ui

import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Syed Ali Hamza's goat artwork (resources/ui/art, made from art/source by scripts/art.py): the logo, the eating
 * animation of the intro and the offline animation. Each picture comes with two masks, its circle and its inner
 * ears, so the apps can paint them: the artwork's rainbow (the default) or the theme's accent colour.
 */
object GoatArt {
    data class Frames(val size: Int, val cx: Double, val cy: Double, val r: Double, val count: Int)

    /** The circle in each animation's frames, on their own canvas size (art/out/frames.json). */
    private val info: Map<String, Frames> by lazy {
        val o = JSONObject(GoatArt::class.java.getResourceAsStream("/ui/art/frames.json")!!.bufferedReader().readText())
        o.keySet().associateWith { k ->
            o.getJSONObject(k).let { Frames(it.getInt("size"), it.getDouble("cx"), it.getDouble("cy"), it.getDouble("r"), it.getInt("count")) }
        }
    }

    private val cache = object : LinkedHashMap<String, Image>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?) = size > 80
    }

    private fun load(name: String, px: Int): Image =
        Image(GoatArt::class.java.getResource("/ui/art/$name")!!.toExternalForm(), px.toDouble(), px.toDouble(), true, true)

    private fun accent(): Color = runCatching { Color.web(Theme.accent) }.getOrDefault(Color.web("#3b82f6"))

    private fun key(vararg parts: Any) = parts.joinToString("|") + "|" + (if (Theme.logoRainbow) "rainbow" else Theme.accent)

    /** The logo, [px] pixels square (the circle is 0.84 of it; horns and beard reach past it). */
    fun logo(px: Int): Image = synchronized(cache) {
        cache.getOrPut(key("logo", px)) {
            val base = load("logo.png", px)
            if (Theme.logoRainbow) base
            else paint(base, load("logo-circle.png", px), load("logo-ears.png", px), px / 2.0, px / 2.0, px * 0.42, shaded = false)
        }
    }

    /** The frames of an animation ("eat" or "offline"), [px] pixels square. */
    fun frames(kind: String, px: Int): List<Image> {
        val f = info.getValue(kind)
        val k = px.toDouble() / f.size
        return (1..f.count).map { i ->
            val name = "%s%02d".format(kind, i)
            synchronized(cache) {
                cache.getOrPut(key(name, px)) {
                    paint(load("$name.png", px), load("$name-tint.png", px), load("$name-ears.png", px), f.cx * k, f.cy * k, f.r * k, shaded = true)
                }
            }
        }
    }

    /**
     * Paints the circle (and, in accent mode, the inner ears) of [base]. The masks carry the original shading as
     * their grey level, so the new colour keeps the drawing's light and shadow.
     */
    private fun paint(base: Image, circle: Image, ears: Image, cx: Double, cy: Double, r: Double, shaded: Boolean): Image {
        val w = base.width.toInt(); val h = base.height.toInt()
        val fmt = PixelFormat.getIntArgbInstance()
        fun pixels(img: Image) = IntArray(w * h).also { img.pixelReader.getPixels(0, 0, w, h, fmt, it, 0, w) }
        val px = pixels(base); val c = pixels(circle); val e = pixels(ears)
        val rainbow = Theme.logoRainbow
        // The rainbow texture's circle is 0.42 of its width, centred: scaled to this picture's circle.
        val texSize = (r / 0.42).roundToInt().coerceAtLeast(2)
        val tex = if (rainbow) IntArray(texSize * texSize).also { load("rainbow.png", texSize).pixelReader.getPixels(0, 0, texSize, texSize, fmt, it, 0, texSize) } else null
        val tx0 = cx - texSize / 2.0; val ty0 = cy - texSize / 2.0
        val a = accent()
        val light = a.interpolate(Color.WHITE, 0.32); val dark = a.interpolate(Color.BLACK, 0.38)
        val ear = a.interpolate(Color.WHITE, 0.45)
        // Lit from the top left, like the artwork.
        val lx = cx - 0.3 * r; val ly = cy - 0.35 * r
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val pc = (c[i] ushr 24) / 255.0
            val pe = if (rainbow) 0.0 else (e[i] ushr 24) / 255.0
            if (pc <= 0.0 && pe <= 0.0) continue
            var rr = ((px[i] shr 16) and 255).toDouble(); var gg = ((px[i] shr 8) and 255).toDouble(); var bb = (px[i] and 255).toDouble()
            if (pc > 0.0) {
                val shade = if (shaded) 0.35 + 0.75 * ((c[i] and 255) / 255.0) else 1.0
                val cr: Double; val cg: Double; val cb: Double
                if (tex != null) {
                    val tx = (x - tx0).roundToInt().coerceIn(0, texSize - 1); val ty = (y - ty0).roundToInt().coerceIn(0, texSize - 1)
                    val t = tex[ty * texSize + tx]
                    cr = ((t shr 16) and 255).toDouble(); cg = ((t shr 8) and 255).toDouble(); cb = (t and 255).toDouble()
                } else {
                    val d = (hypot(x - lx, y - ly) / (1.55 * r)).coerceIn(0.0, 1.0).pow(1.2)
                    cr = (light.red + (dark.red - light.red) * d) * 255; cg = (light.green + (dark.green - light.green) * d) * 255; cb = (light.blue + (dark.blue - light.blue) * d) * 255
                }
                rr = rr * (1 - pc) + (cr * shade).coerceAtMost(255.0) * pc
                gg = gg * (1 - pc) + (cg * shade).coerceAtMost(255.0) * pc
                bb = bb * (1 - pc) + (cb * shade).coerceAtMost(255.0) * pc
            }
            if (pe > 0.0) {
                val shade = 0.45 + 0.6 * ((e[i] and 255) / 255.0)
                rr = rr * (1 - pe) + (ear.red * 255 * shade).coerceAtMost(255.0) * pe
                gg = gg * (1 - pe) + (ear.green * 255 * shade).coerceAtMost(255.0) * pe
                bb = bb * (1 - pe) + (ear.blue * 255 * shade).coerceAtMost(255.0) * pe
            }
            px[i] = (px[i] and -0x1000000) or (rr.roundToInt().coerceIn(0, 255) shl 16) or (gg.roundToInt().coerceIn(0, 255) shl 8) or bb.roundToInt().coerceIn(0, 255)
        }
        return WritableImage(w, h).apply { pixelWriter.setPixels(0, 0, w, h, fmt, px, 0, w) }
    }
}
