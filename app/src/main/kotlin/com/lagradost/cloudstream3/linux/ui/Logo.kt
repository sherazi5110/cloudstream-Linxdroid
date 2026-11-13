package com.lagradost.cloudstream3.linux.ui

import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import kotlin.math.ceil

/**
 * The Stream Goat logo: Syed Ali Hamza's goat eating grass in front of a circle. The circle is the artwork's
 * rainbow, or the theme's accent colour (Settings → Appearance → Logo), and follows theme changes.
 */
class Logo(private val size: Double) : StackPane() {
    private val view = ImageView().apply { fitWidth = size; fitHeight = size; isSmooth = true; isPreserveRatio = true }

    init {
        children += view
        setMinSize(size, size); setPrefSize(size, size); setMaxSize(size, size)
        isMouseTransparent = true
        refresh()
        Theme.onChange { refresh() }
    }

    /** Drawn at twice its size, so it stays sharp on high-density screens. */
    private fun refresh() {
        view.image = GoatArt.logo(ceil(size * 2).toInt().coerceAtLeast(16))
    }

    companion object {
        const val DEFAULT_ACCENT = "#3b82f6"

        /** The logo as an image, e.g. for the window icon. */
        fun image(size: Double): Image = GoatArt.logo(size.toInt())
    }
}
