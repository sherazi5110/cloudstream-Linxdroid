package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Anime enhance and the HDR fix: when they apply, and that the anime filters really sharpen the picture. */
class PictureTest {
    private val tests = File(System.getProperty("tests.dir"))
    private lateinit var server: Process
    private var port = 0
    private val base get() = "http://localhost:$port"

    @BeforeTest
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = ProcessBuilder(System.getProperty("test.python", "python3"), "-I", tests.resolve("mock-archive/server.py").path, port.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        repeat(50) {
            if (runCatching { URI("http://127.0.0.1:$port/advancedsearch.php").toURL().readText() }.isSuccess) return@repeat
            Thread.sleep(100)
        }
    }

    @AfterTest
    fun stop() = server.destroy()

    private fun link(name: String, quality: Int = 720, url: String = "https://cdn.example/v.m3u8"): ExtractorLink = runBlocking {
        newExtractorLink("Test", name, url) { this.quality = quality }
    }

    @Test
    fun `anime enhance applies to animation, HDR fix to sources marked HDR`() = runBlocking {
        val api = object : com.lagradost.cloudstream3.MainAPI() { override var name = "Test" }
        val anime = api.newMovieLoadResponse("Pixel", "u", TvType.AnimeMovie, "d")
        val film = api.newMovieLoadResponse("Film", "u", TvType.Movie, "d")
        val cartoon = api.newMovieLoadResponse("Toon", "u", TvType.Movie, "d") { tags = listOf("Family", "Animation") }
        assertTrue(Picture.enhance(anime, link("Server 1"), "AUTO", saver = false))
        assertTrue(Picture.enhance(cartoon, link("Server 1"), "AUTO", saver = false))
        assertFalse(Picture.enhance(film, link("Server 1"), "AUTO", saver = false))
        assertFalse(Picture.enhance(anime, link("Server 1", 2160), "AUTO", saver = false))
        assertFalse(Picture.enhance(anime, link("Server 1"), "AUTO", saver = true))
        assertTrue(Picture.enhance(film, link("Server 1"), "ON", saver = true))
        assertFalse(Picture.enhance(anime, link("Server 1"), "OFF", saver = false))

        assertTrue(Picture.looksHdr(link("4K HDR10 · Server")))
        assertTrue(Picture.looksHdr(link("Torrentio 2160p DV")))
        assertTrue(Picture.looksHdr(link("Main", url = "https://x/Movie.2160p.Dolby.Vision.mkv")))
        assertFalse(Picture.looksHdr(link("DVD rip")))
        assertFalse(Picture.looksHdr(link("Server HD")))
        assertTrue(Picture.hdrFix(link("Server"), "ON"))
        assertFalse(Picture.hdrFix(link("HDR"), "OFF"))
        Unit
    }

    /** The 8th frame of [file] (BGRA), in a buffer of [size] (VLC scales into it) or the video's own size. */
    private fun frame(factory: MediaPlayerFactory, file: File, size: Pair<Int, Int>?): Pair<Int, ByteArray> {
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        var w = 0
        var h = 0
        val got = CountDownLatch(1)
        var pixels = ByteArray(0)
        var shown = 0
        player.videoSurface().set(CallbackVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sw: Int, sh: Int): BufferFormat {
                    w = size?.first ?: sw; h = size?.second ?: sh
                    return BufferFormat("BGRA", w, h, intArrayOf(w * 4), intArrayOf(h))
                }
                override fun newFormatSize(bw: Int, bh: Int, dw: Int, dh: Int) = Unit
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) = Unit
            },
            object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer) = Unit
                override fun unlock(mediaPlayer: MediaPlayer) = Unit
                override fun display(mediaPlayer: MediaPlayer, b: Array<ByteBuffer>, f: BufferFormat, dw: Int, dh: Int) {
                    if (++shown == 8) { pixels = ByteArray(w * h * 4).also { b[0].duplicate().get(it) }; got.countDown() }
                }
            },
            true, VideoSurfaceAdapters.getVideoSurfaceAdapter(),
        ))
        try {
            assertTrue(player.media().play(file.toPath().toUri().toString(), ":no-audio"))
            assertTrue(got.await(20, TimeUnit.SECONDS), "no frame")
        } finally {
            player.controls().stop(); player.release()
        }
        return w to pixels
    }

    private fun image(w: Int, px: ByteArray) = java.awt.image.BufferedImage(w, px.size / 4 / w, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
        for (i in 0 until px.size / 4) setRGB(i % w, i / w, ((px[i * 4 + 2].toInt() and 255) shl 16) or ((px[i * 4 + 1].toInt() and 255) shl 8) or (px[i * 4].toInt() and 255))
    }

    /** Peak signal-to-noise ratio against the reference, in dB: higher is closer to the original. */
    private fun psnr(a: java.awt.image.BufferedImage, ref: java.awt.image.BufferedImage): Double {
        var sum = 0.0
        for (y in 0 until ref.height) for (x in 0 until ref.width) {
            val p = a.getRGB(x, y); val q = ref.getRGB(x, y)
            for (s in intArrayOf(0, 8, 16)) { val d = ((p shr s) and 255) - ((q shr s) and 255); sum += d * d }
        }
        return 10 * log10(255.0 * 255 / (sum / (ref.width * ref.height * 3)))
    }

    @Test
    fun `anime enhance upscales low-quality animation closer to the original`() {
        if (!NativeDiscovery().discover()) { println("libVLC not installed; skipping"); return }
        // A cartoon frame drawn at 720p, encoded badly at 240p as a still clip, so whichever frame is grabbed matches.
        val media = tests.resolve("mock-archive/media")
        val ref = javax.imageio.ImageIO.read(media.resolve("anime-720p-frame8.png"))
        val clip = media.resolve("anime-240p.mp4")
        val common = arrayOf("--quiet", "--no-xlib", "--aout=dummy", "--no-video-title-show")
        // Before: decoded at 240p, stretched to 720p by the GPU (bilinear).
        val plainFactory = MediaPlayerFactory(*common)
        val (w1, small) = try { frame(plainFactory, clip, null) } finally { plainFactory.release() }
        val stretched = java.awt.image.BufferedImage(ref.width, ref.height, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(image(w1, small), 0, 0, ref.width, ref.height, null); dispose()
            }
        }
        // Anime enhance: VLC upscales with Lanczos (the engine option the app sets) into a screen-sized buffer.
        val enhancedFactory = MediaPlayerFactory(*common, "--swscale-mode=9")
        val (w2, big) = try { frame(enhancedFactory, clip, ref.width to ref.height) } finally { enhancedFactory.release() }
        val before = psnr(stretched, ref)
        val after = psnr(image(w2, big), ref)
        println("anime 240p → 720p: before %.2f dB, enhanced %.2f dB".format(before, after))
        assertTrue(after > before + 0.3, "enhanced $after dB vs before $before dB")
    }
}
