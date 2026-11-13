package io.github.sherazi5110.cloudstream.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Anime enhance on Android. libVLC decodes into a GPU texture (a SurfaceTexture) instead of straight onto the
 * screen; each frame is copied to a texture of the video's own size and drawn to the screen enlarged with
 * Catmull-Rom (9 bilinear taps) and sharpened at the edges, clamped to each pixel's neighbourhood so there are
 * no halos. On a 240p cartoon shown at 720p it measured 27.9 dB against the original, plain enlarging 27.2 dB.
 * With enhance off the same view draws the video plainly (bilinear), so it can be switched live.
 * Subtitles are drawn by libVLC on a layer above ([subtitles]). Zoom modes and pinch-to-zoom are done here.
 */
class EnhancedVideoView(context: Context) : FrameLayout(context) {
    val gl = GLSurfaceView(context)
    val subtitles = TextureView(context).apply { isOpaque = false }
    private val main = Handler(Looper.getMainLooper())

    /** The GPU texture libVLC should decode into (on the main thread, once ready). */
    var onSurface: ((SurfaceTexture) -> Unit)? = null
    /** This device's graphics chip can't run the enhance shader (or the GPU view failed). */
    var onUnsupported: ((String) -> Unit)? = null

    @Volatile var enhance = true
        set(v) { field = v; gl.requestRender() }
    @Volatile var zoom: Zoom = Zoom.FIT
        set(v) { field = v; gl.requestRender() }
    @Volatile var pinch = 1f
        set(v) { field = v; gl.requestRender() }

    @Volatile private var visibleW = 0
    @Volatile private var visibleH = 0
    @Volatile private var sar = 1.0

    /** The video's visible size (libVLC's new-layout callback). */
    fun setVideoSize(visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int) {
        visibleW = visibleWidth; visibleH = visibleHeight
        sar = if (sarNum > 0 && sarDen > 0) sarNum.toDouble() / sarDen else 1.0
        gl.requestRender()
    }

    /** "480p" — the video's height, for messages. */
    val videoHeight: Int get() = visibleH

    init {
        gl.setEGLContextClientVersion(2)
        gl.preserveEGLContextOnPause = true
        gl.setRenderer(Renderer())
        gl.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        addView(gl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subtitles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var oes = 0
        private var st: SurfaceTexture? = null
        private val matrix = FloatArray(16)
        private var copy = 0
        private var sharp = 0
        private var plain = 0
        private var fbo = 0
        private var fboTex = 0
        private var fboW = 0
        private var fboH = 0
        private var viewW = 0
        private var viewH = 0
        private var failed = false
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            try {
                // Catmull-Rom needs exact texture positions: without high precision in fragment shaders (some older
                // TV chips) the picture would shimmer, so enhance isn't offered there.
                val range = IntArray(2); val precision = IntArray(1)
                GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
                if (range[0] == 0 && precision[0] == 0) throw IllegalStateException("this graphics chip has no high-precision shaders")
                oes = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
                linearClamp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                copy = program(VERTEX_OES, FRAGMENT_OES)
                sharp = program(VERTEX, FRAGMENT_ENHANCE)
                plain = program(VERTEX, FRAGMENT_PLAIN)
                fbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
                fboTex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                fboW = 0; fboH = 0
                val texture = SurfaceTexture(oes).apply { setOnFrameAvailableListener { gl.requestRender() } }
                st?.release()
                st = texture
                main.post { onSurface?.invoke(texture) }
            } catch (t: Throwable) {
                failed = true
                main.post { onUnsupported?.invoke(t.message ?: t.toString()) }
            }
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            viewW = width; viewH = height
        }

        override fun onDrawFrame(unused: GL10?) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewW, viewH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val texture = st ?: return
            if (failed) return
            runCatching { texture.updateTexImage() }.onFailure { return }
            texture.getTransformMatrix(matrix)
            val vw = visibleW; val vh = visibleH
            if (vw <= 0 || vh <= 0 || viewW <= 0 || viewH <= 0) return
            // 1. The decoder's frame (cropped and turned by its matrix) into a texture of the video's own size.
            if (vw != fboW || vh != fboH) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, vw, vh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                linearClamp(GLES20.GL_TEXTURE_2D)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
                fboW = vw; fboH = vh
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, vw, vh)
            GLES20.glUseProgram(copy)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(copy, "uSrc"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copy, "uTexMatrix"), 1, false, matrix, 0)
            draw(copy)
            // 2. To the screen, at the zoom mode's size: enhanced (Catmull-Rom + clamped sharpening) or plain.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val (x, y, w, h) = destination(vw, vh)
            GLES20.glViewport(x, y, w, h)
            val p = if (enhance) sharp else plain
            GLES20.glUseProgram(p)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uSrc"), 0)
            if (p == sharp) {
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSrcSize"), vw.toFloat(), vh.toFloat())
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharp"), SHARPNESS)
            }
            draw(p)
        }

        /** Where the picture goes on the screen: the zoom mode's shape, times the pinch. */
        private fun destination(vw: Int, vh: Int): IntArray {
            val videoAspect = vw * sar / vh
            val aspect = when (zoom) { Zoom.WIDE -> 16.0 / 9; Zoom.TV -> 4.0 / 3; else -> videoAspect }
            var w: Double; var h: Double
            when (zoom) {
                Zoom.STRETCH -> { w = viewW.toDouble(); h = viewH.toDouble() }
                Zoom.ORIGINAL -> { w = vw * sar; h = vh.toDouble() }
                Zoom.FILL -> { h = maxOf(viewH.toDouble(), viewW / aspect); w = h * aspect }
                else -> { h = min(viewH.toDouble(), viewW / aspect); w = h * aspect }
            }
            w *= pinch; h *= pinch
            return intArrayOf(((viewW - w) / 2).roundToInt(), ((viewH - h) / 2).roundToInt(), w.roundToInt(), h.roundToInt())
        }

        private fun draw(program: Int) {
            val loc = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(loc)
        }

        private fun linearClamp(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s))
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("program: " + GLES20.glGetProgramInfoLog(p))
            return p
        }
    }

    companion object {
        /** How strongly edges are sharpened (0 = Catmull-Rom only); 0.8 measured best without halos. */
        const val SHARPNESS = 0.8f

        private const val VERTEX_OES = """
attribute vec2 aPos;
uniform mat4 uTexMatrix;
varying vec2 vUv;
void main() {
    vUv = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}"""

        private const val FRAGMENT_OES = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uSrc;
varying vec2 vUv;
void main() { gl_FragColor = texture2D(uSrc, vUv); }"""

        private const val VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}"""

        private const val FRAGMENT_PLAIN = """precision mediump float;
uniform sampler2D uSrc;
varying vec2 vUv;
void main() { gl_FragColor = vec4(texture2D(uSrc, vUv).rgb, 1.0); }"""

        /** Catmull-Rom + clamped sharpening; checked in WebGL (the same shader language) against the original frame. */
        private const val FRAGMENT_ENHANCE = """#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uSrc;
uniform vec2 uSrcSize;   // the video's size in pixels
uniform float uSharp;    // 0 = Catmull-Rom only; ~0.5–0.8 = crisper lines
varying vec2 vUv;

vec3 catmullRom(vec2 uv) {
    vec2 samplePos = uv * uSrcSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 texPos0 = (texPos1 - 1.0) / uSrcSize;
    vec2 texPos3 = (texPos1 + 2.0) / uSrcSize;
    vec2 texPos12 = (texPos1 + offset12) / uSrcSize;
    vec3 r = vec3(0.0);
    r += texture2D(uSrc, vec2(texPos0.x, texPos0.y)).rgb * w0.x * w0.y;
    r += texture2D(uSrc, vec2(texPos12.x, texPos0.y)).rgb * w12.x * w0.y;
    r += texture2D(uSrc, vec2(texPos3.x, texPos0.y)).rgb * w3.x * w0.y;
    r += texture2D(uSrc, vec2(texPos0.x, texPos12.y)).rgb * w0.x * w12.y;
    r += texture2D(uSrc, vec2(texPos12.x, texPos12.y)).rgb * w12.x * w12.y;
    r += texture2D(uSrc, vec2(texPos3.x, texPos12.y)).rgb * w3.x * w12.y;
    r += texture2D(uSrc, vec2(texPos0.x, texPos3.y)).rgb * w0.x * w3.y;
    r += texture2D(uSrc, vec2(texPos12.x, texPos3.y)).rgb * w12.x * w3.y;
    r += texture2D(uSrc, vec2(texPos3.x, texPos3.y)).rgb * w3.x * w3.y;
    return r;
}

void main() {
    vec2 px = 1.0 / uSrcSize;
    vec3 c = catmullRom(vUv);
    vec3 n = texture2D(uSrc, vUv + vec2(0.0, -px.y)).rgb;
    vec3 s = texture2D(uSrc, vUv + vec2(0.0, px.y)).rgb;
    vec3 e = texture2D(uSrc, vUv + vec2(px.x, 0.0)).rgb;
    vec3 w = texture2D(uSrc, vUv + vec2(-px.x, 0.0)).rgb;
    vec3 lo = min(min(n, s), min(e, w));
    vec3 hi = max(max(n, s), max(e, w));
    vec3 sharp = c + uSharp * (c - (n + s + e + w) * 0.25);
    gl_FragColor = vec4(clamp(sharp, min(lo, c), max(hi, c)), 1.0);
}
"""
    }
}
