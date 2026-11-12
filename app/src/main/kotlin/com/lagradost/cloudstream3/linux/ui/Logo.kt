package com.lagradost.cloudstream3.linux.ui

import javafx.scene.Group
import javafx.scene.SnapshotParameters
import javafx.scene.image.Image
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.transform.Scale

/**
 * The Stream Goat logo: a white mountain goat in front of snowy peaks on a dark tile. The peaks take the theme's
 * accent colour, so the logo changes with it. Vector paths on a 512 grid, the same as
 * packaging/linux/cloudstream-linux.svg and the Android app's res/drawable/logo.xml.
 */
class Logo(size: Double, accent: String? = null, tile: Boolean = true) : Pane() {
    private val peaks = SVGPath().apply { content = MOUNTAIN }

    init {
        val shapes = mutableListOf<javafx.scene.Node>()
        if (tile) shapes += SVGPath().apply { content = TILE; fill = Color.web("#0b0b10") }
        shapes += peaks
        shapes += SVGPath().apply { content = SNOW; fill = Color.WHITE }
        GOAT_PARTS.forEach { d ->
            shapes += SVGPath().apply {
                content = d; fill = Color.WHITE; stroke = Color.web("#0b0b10"); strokeWidth = STROKE; strokeLineJoin = StrokeLineJoin.ROUND
            }
        }
        shapes += SVGPath().apply { content = EYES; fill = Color.web("#0b0b10") }
        children += Group(*shapes.toTypedArray()).apply { transforms += Scale(size / 512, size / 512, 0.0, 0.0) }
        setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size)
        isMouseTransparent = true
        recolor(accent ?: Theme.accent)
        if (accent == null) Theme.onChange { recolor(Theme.accent) }
    }

    private fun recolor(hex: String) {
        val c = runCatching { Color.web(hex) }.getOrDefault(Color.web(DEFAULT_ACCENT))
        peaks.fill = LinearGradient(0.0, 222.0, 0.0, 460.0, false, CycleMethod.NO_CYCLE,
            Stop(0.0, c.interpolate(Color.WHITE, 0.18)), Stop(1.0, c.interpolate(Color.BLACK, 0.45)))
    }

    companion object {
        const val DEFAULT_ACCENT = "#3b82f6"
        const val STROKE = 9.5
        const val TILE = "M112 0H400A112 112 0 0 1 512 112V400A112 112 0 0 1 400 512H112A112 112 0 0 1 0 400V112A112 112 0 0 1 112 0Z"
        const val MOUNTAIN = "M20 460 L126 270 L182 340 L256 222 L330 340 L388 262 L492 460 Z"
        const val SNOW = "M126 270 L150 312 L138 306 L126 318 L114 306 L102 310 Z M388 262 L413 306 L400 300 L388 312 L376 300 L364 304 Z"
        const val EYES = "M227.4 268.1a7.6 10.1 0 1 0 15.2 0a7.6 10.1 0 1 0 -15.2 0Z M269.4 268.1a7.6 10.1 0 1 0 15.2 0a7.6 10.1 0 1 0 -15.2 0Z M242.6 353.8a13.4 7.6 0 1 0 26.8 0a13.4 7.6 0 1 0 -26.8 0Z"
        val GOAT_PARTS = listOf(
            "M 230.8 214.3 C 217.4 163.9 192.2 125.3 148.5 110.2 C 180.4 137 198.9 179 205.6 227.8Z",
            "M 281.2 214.3 C 294.6 163.9 319.8 125.3 363.5 110.2 C 331.6 137 313.1 179 306.4 227.8Z",
            "M 214 246.2 C 183.8 231.1 153.5 236.2 133.4 258 C 165.3 269.8 192.2 268.1 217.4 263Z",
            "M 298 246.2 C 328.2 231.1 358.5 236.2 378.6 258 C 346.7 269.8 319.8 268.1 294.6 263Z",
            "M 205.6 216 Q 256 195.8 306.4 216 L 298 300 Q 291.3 352.1 269.4 372.2 Q 256 380.6 242.6 372.2 Q 220.7 352.1 214 300Z",
            "M 240.9 370.6 Q 256 427.7 271.1 370.6Z",
        )

        /** The logo as an image in the current accent, e.g. for the window icon. */
        fun image(size: Double): Image = Logo(size, Theme.accent).snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }
}
