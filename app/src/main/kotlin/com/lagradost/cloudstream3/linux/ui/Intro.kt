package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.Settings
import javafx.animation.Animation
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
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Circle
import javafx.scene.shape.SVGPath
import javafx.scene.transform.Rotate
import javafx.util.Duration
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A picture-by-picture animation of the goat artwork: each frame shows for [frameMs], blending into the next
 * over [fadeMs]. Loops until stopped (or plays once).
 */
class FlipBook(private val frames: List<Image>, size: Double, private val frameMs: Double, private val fadeMs: Double = 120.0,
               private val loop: Boolean = true) : StackPane() {
    private val back = ImageView().apply { fitWidth = size; fitHeight = size; isSmooth = true }
    private val front = ImageView().apply { fitWidth = size; fitHeight = size; isSmooth = true }
    private var index = 0
    private val ticker = Timeline(KeyFrame(Duration.millis(frameMs), { next() })).apply { cycleCount = Animation.INDEFINITE }

    init {
        children.addAll(back, front)
        setMinSize(size, size); setMaxSize(size, size)
        isMouseTransparent = true
        front.image = frames.firstOrNull()
    }

    /** Plays only while it's on screen (a looping animation left running would keep the CPU busy). */
    fun playWhileShown(): FlipBook = apply {
        sceneProperty().addListener { _, _, s -> if (s == null) stop() else play() }
        if (scene != null) play()
    }

    fun play() = ticker.play()
    fun stop() = ticker.stop()

    /** Shows frame [i] at once (for still pictures). */
    fun show(i: Int) { index = i.mod(frames.size); front.image = frames[index]; front.opacity = 1.0 }

    private fun next() {
        if (frames.size < 2) return
        if (!loop && index == frames.lastIndex) { ticker.stop(); return }
        index = (index + 1) % frames.size
        back.image = front.image
        front.image = frames[index]
        front.opacity = 0.0
        FadeTransition(Duration.millis(fadeMs), front).apply { toValue = 1.0 }.play()
    }
}

/**
 * The startup screen: a short intro (about 3 seconds) while extensions load. Syed Ali Hamza's goat chews its grass
 * (the eating animation, in the logo's colours) while an arc of stars sweeps over it, then the name and credit
 * fade in. A click or a key skips it; Settings → Profiles → Startup animation turns it off (then it's a still splash).
 */
class SplashScreen(animate: Boolean = Settings.startupAnimation) : StackPane() {
    private val statusLabel = label("Starting…", "splash-status")
    private var onDone: (() -> Unit)? = null
    private var animation: javafx.animation.Animation? = null
    private val goat = FlipBook(GoatArt.frames("eat", 480), GOAT, frameMs = 300.0, loop = false)

    /** True once the intro has played (or was skipped / is off). */
    var finished = !animate
        private set

    private val accent: Color = runCatching { Color.web(Theme.accent) }.getOrDefault(Color.web(Logo.DEFAULT_ACCENT))

    init {
        styleClass += "splash"
        isFocusTraversable = true
        val stage = Pane().apply { isMouseTransparent = true }
        // The scene is drawn on a 1000x620 board that scales to fit the window (nothing is cut off).
        val board = Group()
        stage.children += board
        widthProperty().addListener { _, _, _ -> fit(board) }
        heightProperty().addListener { _, _, _ -> fit(board) }

        val glow = Circle(500.0, 250.0, 330.0).apply {
            fill = RadialGradient(0.0, 0.0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                Stop(0.0, accent.deriveColor(0.0, 1.0, 1.0, 0.38)), Stop(1.0, Color.TRANSPARENT))
            opacity = 0.0
        }
        val twinkles = Group((0 until 70).map {
            Circle(Random.nextDouble(0.0, 1000.0), Random.nextDouble(0.0, 620.0), Random.nextDouble(0.6, 1.8), Color.WHITE)
                .apply { opacity = Random.nextDouble(0.12, 0.6) }
        })
        // The goat in the middle of the board.
        val goatLayer = Group(goat.apply { layoutX = 500.0 - GOAT / 2; layoutY = 250.0 - GOAT / 2 }).apply {
            opacity = 0.0; scaleX = 0.6; scaleY = 0.6
            effect = DropShadow(40.0, accent.deriveColor(0.0, 1.0, 1.0, 0.7))
        }
        // An arc of stars sweeping over the goat, like the classic studio intro.
        val center = javafx.geometry.Point2D(500.0, 250.0)
        val radius = 225.0
        val count = 15
        val stars = (0 until count).map { i ->
            val angle = 195.0 + 150.0 * i / (count - 1)        // from left (195°) over the top to right (345°)
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
        board.children.addAll(glow, twinkles, starLayer, goatLayer)

        val title = label("Stream Goat", "intro-title").apply { opacity = 0.0; translateY = 18.0 }
        val credit = label("Created by Syed Ali Hamza", "intro-credit").apply { opacity = 0.0 }
        val words = vbox(6.0, title, credit).apply { alignment = Pos.CENTER; isMouseTransparent = true; maxHeight = Region.USE_PREF_SIZE }
        val bottom = vbox(8.0, statusLabel).apply { alignment = Pos.CENTER; maxHeight = Region.USE_PREF_SIZE; isMouseTransparent = true }
        children.addAll(stage, words, bottom)
        StackPane.setAlignment(words, Pos.BOTTOM_CENTER)
        StackPane.setMargin(words, Insets(0.0, 0.0, 70.0, 0.0))
        StackPane.setAlignment(bottom, Pos.BOTTOM_CENTER)
        StackPane.setMargin(bottom, Insets(0.0, 0.0, 26.0, 0.0))

        if (!animate) {
            goatLayer.opacity = 1.0; goatLayer.scaleX = 1.0; goatLayer.scaleY = 1.0
            title.opacity = 1.0; title.translateY = 0.0; credit.opacity = 1.0; glow.opacity = 1.0
            stars.forEach { (_, h, r) -> h.opacity = 1.0; r.angle = 0.0 }
        } else {
            val glowIn = FadeTransition(Duration.millis(1200.0), glow).apply { toValue = 1.0 }
            val goatIn = ParallelTransition(
                FadeTransition(Duration.millis(450.0), goatLayer).apply { toValue = 1.0 },
                SequentialTransition(
                    ScaleTransition(Duration.millis(420.0), goatLayer).apply { toX = 1.08; toY = 1.08; interpolator = Interpolator.SPLINE(0.2, 0.8, 0.3, 1.0) },
                    ScaleTransition(Duration.millis(200.0), goatLayer).apply { toX = 1.0; toY = 1.0 },
                ),
            )
            val sweep = Timeline(*stars.flatMapIndexed { i, (_, holder, rotate) ->
                val start = 350.0 + i * 55.0
                listOf(
                    KeyFrame(Duration.millis(start), KeyValue(rotate.angleProperty(), -120.0), KeyValue(holder.opacityProperty(), 0.0)),
                    KeyFrame(Duration.millis(start + 700.0), KeyValue(rotate.angleProperty(), 0.0, Interpolator.SPLINE(0.1, 0.7, 0.3, 1.0)), KeyValue(holder.opacityProperty(), 1.0)),
                )
            }.toTypedArray())
            val wordsIn = SequentialTransition(PauseTransition(Duration.millis(1400.0)), ParallelTransition(
                FadeTransition(Duration.millis(600.0), title).apply { toValue = 1.0 },
                TranslateTransition(Duration.millis(600.0), title).apply { toY = 0.0 },
                SequentialTransition(PauseTransition(Duration.millis(250.0)), FadeTransition(Duration.millis(600.0), credit).apply { toValue = 1.0 }),
            ))
            // The goat chews through its ten pictures (300 ms each) while the rest plays.
            val all = ParallelTransition(glowIn, goatIn, sweep, wordsIn, PauseTransition(Duration.millis(3300.0)))
            all.setOnFinished { finish() }
            animation = all
            all.play()
            goat.play()
            // Click or any key skips to the end.
            setOnMouseClicked { skip() }
            setOnKeyPressed { skip() }
            sceneProperty().addListener { _, _, s -> if (s != null) requestFocus() }
        }
    }

    private fun fit(board: Group) {
        val w = width.takeIf { it > 0 } ?: return
        val h = height.takeIf { it > 0 } ?: return
        val scale = minOf(w / 1000.0, h / 620.0)
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
        goat.stop()
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        goat.stop()
        onDone?.invoke()
    }

    /** Runs [block] once the intro has finished (now, if it already has). */
    fun whenFinished(block: () -> Unit) {
        if (finished) block() else onDone = block
    }

    fun status(text: String) {
        statusLabel.text = text
    }

    private companion object {
        /** The goat's size on the 1000x620 board. */
        const val GOAT = 440.0
    }
}
