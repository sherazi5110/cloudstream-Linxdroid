package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Settings
import javafx.animation.FadeTransition
import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.ParallelTransition
import javafx.animation.PauseTransition
import javafx.animation.ScaleTransition
import javafx.animation.SequentialTransition
import javafx.animation.Timeline
import javafx.animation.TranslateTransition
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.effect.DropShadow
import javafx.scene.effect.GaussianBlur
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.transform.Rotate
import javafx.util.Duration
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The startup screen: a short intro (about 3 seconds) while extensions load. Snowy peaks rise out of a night
 * sky, an arc of stars sweeps over the summit, the goat appears on top and the name and credit fade in.
 * A click or a key skips it; Settings → Appearance → Startup animation turns it off (then it's a plain splash).
 */
class SplashScreen(animate: Boolean = Settings.startupAnimation) : StackPane() {
    private val statusLabel = label("Starting…", "splash-status")
    private var onDone: (() -> Unit)? = null
    private var animation: javafx.animation.Animation? = null

    /** True once the intro has played (or was skipped / is off). */
    var finished = !animate
        private set

    private val accent: Color = runCatching { Color.web(Theme.accent) }.getOrDefault(Color.web(Logo.DEFAULT_ACCENT))

    init {
        styleClass += "splash"
        isFocusTraversable = true
        val stage = Pane().apply { isMouseTransparent = true }
        // The scene is drawn on a 1000x620 board that scales to the window.
        val board = Group()
        stage.children += board
        widthProperty().addListener { _, _, _ -> fit(board) }
        heightProperty().addListener { _, _, _ -> fit(board) }

        val sky = Rectangle(1000.0, 620.0).apply {
            fill = LinearGradient(0.0, 0.0, 0.0, 1.0, true, CycleMethod.NO_CYCLE,
                Stop(0.0, Color.web("#05060c")), Stop(0.65, accent.interpolate(Color.web("#05060c"), 0.82)), Stop(1.0, Color.web("#0b0b10")))
        }
        val glow = Circle(500.0, 300.0, 300.0).apply {
            fill = RadialGradient(0.0, 0.0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                Stop(0.0, accent.deriveColor(0.0, 1.0, 1.0, 0.35)), Stop(1.0, Color.TRANSPARENT))
            opacity = 0.0
        }
        val twinkles = Group((0 until 70).map {
            Circle(Random.nextDouble(0.0, 1000.0), Random.nextDouble(0.0, 330.0), Random.nextDouble(0.6, 1.8), Color.WHITE)
                .apply { opacity = Random.nextDouble(0.15, 0.7) }
        })
        // The logo's peaks, much bigger: centre peak at x=500.
        val peaks = Group(
            SVGPath().apply {
                content = Logo.MOUNTAIN
                fill = LinearGradient(0.0, 222.0, 0.0, 460.0, false, CycleMethod.NO_CYCLE,
                    Stop(0.0, accent.interpolate(Color.WHITE, 0.15)), Stop(1.0, accent.interpolate(Color.BLACK, 0.6)))
            },
            SVGPath().apply { content = Logo.SNOW; fill = Color.WHITE },
            SVGPath().apply { content = "M256 222 L282 263 L270 258 L256 270 L242 258 L230 263 Z"; fill = Color.WHITE },
        ).apply {
            transforms += javafx.scene.transform.Translate(500.0 - 256 * 1.9, 620.0 - 460 * 1.9)
            transforms += javafx.scene.transform.Scale(1.9, 1.9, 0.0, 0.0)
        }
        val peakLayer = Group(peaks).apply { translateY = 380.0 }
        // The goat (white, as in the logo) standing at the summit.
        val goat = Group(*(Logo.GOAT_PARTS.map { d ->
            SVGPath().apply { content = d; fill = Color.WHITE; stroke = Color.web("#0b0b10"); strokeWidth = Logo.STROKE; strokeLineJoin = StrokeLineJoin.ROUND }
        } + SVGPath().apply { content = Logo.EYES; fill = Color.web("#0b0b10") }).toTypedArray()).apply {
            // Head centre (256, 300) on the logo grid → just above the summit.
            transforms += javafx.scene.transform.Translate(500.0 - 256 * 0.62, 196.0 - 300 * 0.62)
            transforms += javafx.scene.transform.Scale(0.62, 0.62, 0.0, 0.0)
        }
        val goatLayer = Group(goat).apply {
            opacity = 0.0; scaleX = 0.6; scaleY = 0.6
            effect = DropShadow(30.0, accent.deriveColor(0.0, 1.0, 1.0, 0.8))
        }
        // An arc of stars sweeping over the summit, like the classic studio intro.
        val center = javafx.geometry.Point2D(500.0, 250.0)
        val radius = 230.0
        val count = 15
        val stars = (0 until count).map { i ->
            val angle = 200.0 + 140.0 * i / (count - 1)        // from left (200°) over the top to right (340°)
            val holder = Group(star(9.0)).apply { opacity = 0.0 }
            val rotate = Rotate(-120.0, center.x, center.y)
            val node = Group(holder).apply {
                val a = Math.toRadians(angle)
                holder.layoutX = center.x + radius * cos(a)
                holder.layoutY = center.y + radius * sin(a)
                transforms += rotate
            }
            Triple(node, holder, rotate)
        }
        val starLayer = Group(*stars.map { it.first }.toTypedArray())

        board.children.addAll(sky, glow, twinkles, starLayer, peakLayer, goatLayer)

        val title = label("Stream Goat", "intro-title").apply { opacity = 0.0; translateY = 18.0 }
        val credit = label("Created by Syed Ali Hamza", "intro-credit").apply { opacity = 0.0 }
        val words = vbox(6.0, title, credit).apply { alignment = Pos.CENTER; isMouseTransparent = true; maxHeight = Region.USE_PREF_SIZE }
        val bottom = vbox(8.0, statusLabel).apply { alignment = Pos.CENTER; maxHeight = Region.USE_PREF_SIZE; isMouseTransparent = true }
        children.addAll(stage, words, bottom)
        StackPane.setAlignment(words, Pos.BOTTOM_CENTER)
        StackPane.setMargin(words, Insets(0.0, 0.0, 86.0, 0.0))
        StackPane.setAlignment(bottom, Pos.BOTTOM_CENTER)
        StackPane.setMargin(bottom, Insets(0.0, 0.0, 30.0, 0.0))

        if (!animate) {
            peakLayer.translateY = 0.0; goatLayer.opacity = 1.0; goatLayer.scaleX = 1.0; goatLayer.scaleY = 1.0
            title.opacity = 1.0; title.translateY = 0.0; credit.opacity = 1.0; glow.opacity = 1.0
            stars.forEach { (_, h, r) -> h.opacity = 1.0; r.angle = 0.0 }
        } else {
            val rise = TranslateTransition(Duration.millis(1100.0), peakLayer).apply { toY = 0.0; interpolator = Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0) }
            val glowIn = FadeTransition(Duration.millis(1400.0), glow).apply { toValue = 1.0 }
            val sweep = Timeline(*stars.flatMapIndexed { i, (_, holder, rotate) ->
                val start = 500.0 + i * 55.0
                listOf(
                    KeyFrame(Duration.millis(start), KeyValue(rotate.angleProperty(), -120.0), KeyValue(holder.opacityProperty(), 0.0)),
                    KeyFrame(Duration.millis(start + 700.0), KeyValue(rotate.angleProperty(), 0.0, Interpolator.SPLINE(0.1, 0.7, 0.3, 1.0)), KeyValue(holder.opacityProperty(), 1.0)),
                )
            }.toTypedArray())
            val goatIn = ParallelTransition(
                FadeTransition(Duration.millis(650.0), goatLayer).apply { toValue = 1.0 },
                SequentialTransition(
                    ScaleTransition(Duration.millis(450.0), goatLayer).apply { toX = 1.08; toY = 1.08; interpolator = Interpolator.SPLINE(0.2, 0.8, 0.3, 1.0) },
                    ScaleTransition(Duration.millis(200.0), goatLayer).apply { toX = 1.0; toY = 1.0 },
                ),
            ).let { SequentialTransition(PauseTransition(Duration.millis(1150.0)), it) }
            val wordsIn = SequentialTransition(PauseTransition(Duration.millis(1700.0)), ParallelTransition(
                FadeTransition(Duration.millis(600.0), title).apply { toValue = 1.0 },
                TranslateTransition(Duration.millis(600.0), title).apply { toY = 0.0 },
                SequentialTransition(PauseTransition(Duration.millis(250.0)), FadeTransition(Duration.millis(600.0), credit).apply { toValue = 1.0 }),
            ))
            val all = ParallelTransition(rise, glowIn, sweep, goatIn, wordsIn, PauseTransition(Duration.millis(3200.0)))
            all.setOnFinished { finish() }
            animation = all
            all.play()
            // Click or any key skips to the end.
            setOnMouseClicked { skip() }
            setOnKeyPressed { skip() }
            sceneProperty().addListener { _, _, s -> if (s != null) requestFocus() }
        }
    }

    private fun fit(board: Group) {
        val w = width.takeIf { it > 0 } ?: return
        val h = height.takeIf { it > 0 } ?: return
        val scale = maxOf(w / 1000.0, h / 620.0)
        board.scaleX = scale; board.scaleY = scale
        board.layoutX = (w - 1000.0) / 2; board.layoutY = (h - 620.0) / 2
    }

    private fun star(r: Double): Node {
        val points = (0 until 10).joinToString(" ") { k ->
            val rad = if (k % 2 == 0) r else r * 0.45
            val a = Math.toRadians(-90.0 + k * 36.0)
            "${if (k == 0) "M" else "L"}${"%.2f".format(java.util.Locale.ROOT, rad * cos(a))} ${"%.2f".format(java.util.Locale.ROOT, rad * sin(a))}"
        } + "Z"
        return SVGPath().apply { content = points; fill = Color.WHITE; effect = GaussianBlur(0.6) }
            .let { Group(it).apply { effect = DropShadow(8.0, Color.web("#ffffffaa")) } }
    }

    private fun skip() {
        animation?.jumpTo("end")
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        onDone?.invoke()
    }

    /** Runs [block] once the intro has finished (now, if it already has). */
    fun whenFinished(block: () -> Unit) {
        if (finished) block() else onDone = block
    }

    fun status(text: String) {
        statusLabel.text = text
    }
}
