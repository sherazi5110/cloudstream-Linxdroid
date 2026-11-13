package io.github.sherazi5110.cloudstream.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.sherazi5110.cloudstream.App
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Syed Ali Hamza's goat artwork (assets/art, made from art/source by scripts/art.py): the logo, the eating
 * animation of the intro and the offline animation. Each picture comes with two masks, its circle and its inner
 * ears, so the app can paint them: the artwork's rainbow (the default) or the theme's accent colour.
 * The same rules as the desktop app's GoatArt.kt.
 */
object GoatArt {
    data class Frames(val size: Int, val cx: Double, val cy: Double, val r: Double, val count: Int)

    private val info: Map<String, Frames> by lazy {
        val o = JSONObject(App.context.assets.open("art/frames.json").bufferedReader().use { it.readText() })
        o.keys().asSequence().associateWith { k ->
            o.getJSONObject(k).let { Frames(it.getInt("size"), it.getDouble("cx"), it.getDouble("cy"), it.getDouble("r"), it.getInt("count")) }
        }
    }

    /** Logos in their sizes and styles (small: a few at most). */
    private val logos = LruCache<String, Bitmap>(12)

    private fun load(name: String, px: Int): Bitmap {
        val raw = App.context.assets.open("art/$name").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
            ?: error("art/$name")
        if (raw.width == px && raw.height == px) return raw
        return Bitmap.createScaledBitmap(raw, px, px, true).also { if (it !== raw) raw.recycle() }
    }

    /** The logo, [px] pixels square (the circle is 0.84 of it; horns and beard reach past it). */
    fun logo(px: Int, rainbow: Boolean, accent: Int): Bitmap {
        val key = "$px|$rainbow|$accent"
        logos.get(key)?.let { return it }
        val base = load("logo.png", px)
        val out = if (rainbow) base
            else paint(base, load("logo-circle.png", px), load("logo-ears.png", px), px / 2.0, px / 2.0, px * 0.42, shaded = false, rainbow = false, accent = accent)
        logos.put(key, out)
        return out
    }

    fun count(kind: String): Int = info.getValue(kind).count

    /** Frame [i] (from 0) of an animation ("eat" or "offline"), [px] pixels square. Slow-ish: call off the main thread. */
    fun frame(kind: String, i: Int, px: Int, rainbow: Boolean, accent: Int): Bitmap {
        val f = info.getValue(kind)
        val k = px.toDouble() / f.size
        val name = "%s%02d".format(kind, i + 1)
        return paint(load("$name.png", px), load("$name-tint.png", px), load("$name-ears.png", px), f.cx * k, f.cy * k, f.r * k,
            shaded = true, rainbow = rainbow, accent = accent)
    }

    /**
     * Paints the circle (and, in accent mode, the inner ears) of [base]. The masks carry the original shading as
     * their grey level, so the new colour keeps the drawing's light and shadow.
     */
    private fun paint(base: Bitmap, circle: Bitmap, ears: Bitmap, cx: Double, cy: Double, r: Double, shaded: Boolean, rainbow: Boolean, accent: Int): Bitmap {
        val w = base.width; val h = base.height
        fun pixels(b: Bitmap) = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val px = pixels(base); val c = pixels(circle); val e = pixels(ears)
        circle.recycle(); ears.recycle()
        // The rainbow texture's circle is 0.42 of its width, centred: scaled to this picture's circle.
        val texSize = (r / 0.42).roundToInt().coerceAtLeast(2)
        val tex = if (rainbow) load("rainbow.png", texSize).let { t -> IntArray(texSize * texSize).also { t.getPixels(it, 0, texSize, 0, 0, texSize, texSize); t.recycle() } } else null
        val tx0 = cx - texSize / 2.0; val ty0 = cy - texSize / 2.0
        val ar = (accent shr 16 and 255) / 255.0; val ag = (accent shr 8 and 255) / 255.0; val ab = (accent and 255) / 255.0
        fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
        val light = doubleArrayOf(mix(ar, 1.0, 0.32), mix(ag, 1.0, 0.32), mix(ab, 1.0, 0.32))
        val dark = doubleArrayOf(mix(ar, 0.0, 0.38), mix(ag, 0.0, 0.38), mix(ab, 0.0, 0.38))
        val ear = doubleArrayOf(mix(ar, 1.0, 0.45), mix(ag, 1.0, 0.45), mix(ab, 1.0, 0.45))
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
                    cr = mix(light[0], dark[0], d) * 255; cg = mix(light[1], dark[1], d) * 255; cb = mix(light[2], dark[2], d) * 255
                }
                rr = rr * (1 - pc) + (cr * shade).coerceAtMost(255.0) * pc
                gg = gg * (1 - pc) + (cg * shade).coerceAtMost(255.0) * pc
                bb = bb * (1 - pc) + (cb * shade).coerceAtMost(255.0) * pc
            }
            if (pe > 0.0) {
                val shade = 0.45 + 0.6 * ((e[i] and 255) / 255.0)
                rr = rr * (1 - pe) + (ear[0] * 255 * shade).coerceAtMost(255.0) * pe
                gg = gg * (1 - pe) + (ear[1] * 255 * shade).coerceAtMost(255.0) * pe
                bb = bb * (1 - pe) + (ear[2] * 255 * shade).coerceAtMost(255.0) * pe
            }
            px[i] = (px[i] and -0x1000000) or (rr.roundToInt().coerceIn(0, 255) shl 16) or (gg.roundToInt().coerceIn(0, 255) shl 8) or bb.roundToInt().coerceIn(0, 255)
        }
        val out = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true).also { base.recycle() }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
