package com.delrogue.grooverider.ui.cloud.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What the visuals are shown each frame. Implementations are called on the GL
 * thread and must only read state that is safe to read from any thread.
 */
interface SceneSource {
    /** Fills [into] with the engine's state as of now. */
    fun read(into: SceneFrame)
}

class SceneFrame {
    /** Audio is running on a source: draw its grains. Otherwise a quiet preview of the patch is drawn. */
    var live = false
    /** [grainCount] grains in [MoteField]'s cloud layout. */
    val cloud = FloatArray(256 * MoteField.CLOUD_STRIDE)
    var grainCount = 0
    var patch = MoteField.Patch(400f, 40f, 0f, 0.15f, null, 0.8f, 0.2f, 0.5f)
    var chaosX = 0f; var chaosY = 0f; var chaosZ = 0f
    var level = 0f
    /** [SpectralTide.BINS] magnitudes of the output, valid when [hasSpectrum]. */
    val spectrum = FloatArray(SpectralTide.BINS)
    var hasSpectrum = false
    var sampleRate = 48000
}

/**
 * The Observatory, drawn with OpenGL ES 3.0: the web app's WebGL renderer
 * (docs/index.html, `makeGL` and the frame loop) with the same passes.
 *
 *   1. trails  - last frame's trail buffer fades and drifts; the motes are added (half resolution)
 *   2. bloom   - a separable blur of the trails (quarter resolution)
 *   3. water   - caustics and aurora, plus trails and bloom
 *   4. tide    - the spectral waterfall along the bottom
 *   5. motes   - the bodies themselves, crisp, on top
 *
 * Runs on GLSurfaceView's own thread. It reads the engine only through
 * [SceneSource] and never touches the audio thread.
 */
class ObservatoryRenderer(
    private val source: SceneSource,
    private val densityScale: () -> Float,          // screen density x the surface's resolution scale
    private val onQuality: (Int) -> Unit,           // quality level 1..4 changed; called on the GL thread
    private val onUnavailable: (String) -> Unit,    // shaders or framebuffers failed; called on the GL thread
) : GLSurfaceView.Renderer {

    private class Program(val id: Int) {
        private val uniforms = HashMap<String, Int>()
        fun u(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }
    private class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)

    private val frame = SceneFrame()
    private val motes = MoteField()
    private val tide = SpectralTide()
    private val ghostCloud = FloatArray(256 * MoteField.CLOUD_STRIDE)
    private val moteBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(MoteField.MAX_POINTS * MoteField.STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val tideColumn = ByteArray(SpectralTide.ROWS * 4)
    private val tideColumnBuffer: ByteBuffer = ByteBuffer.allocateDirect(SpectralTide.ROWS * 4)

    private var scene: Program? = null
    private var fade: Program? = null
    private var blur: Program? = null
    private var mote: Program? = null
    private var tideProgram: Program? = null
    private var triangle = 0
    private var points = 0
    private var tideTexture = 0
    private var trailA: Target? = null
    private var trailB: Target? = null
    private var bloomA: Target? = null
    private var bloomB: Target? = null
    private var width = 0
    private var height = 0
    private var ready = false

    private val startNanos = System.nanoTime()
    private var lastT = 0.0
    private var frames = 0L
    private var fpsEma = 60f
    private var slowFor = 0f
    private var alive = 0.5f
    private var idleLevel = 0f
    private var tideHead = 0
    private var tideDebt = 0.0

    /** The frame rate the view is pacing us at; quality is shed if we cannot hold it. */
    @Volatile var targetFps = 60f
    /** Last measured frames per second, for diagnostics. */
    @Volatile var measuredFps = 0f
        private set
    val quality: Int get() = motes.quality

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        ready = false
        try {
            scene = link(Shaders.VS_QUAD, Shaders.FS_SCENE, "aP")
            fade = link(Shaders.VS_QUAD, Shaders.FS_FADE, "aP")
            blur = link(Shaders.VS_QUAD, Shaders.FS_BLUR, "aP")
            tideProgram = link(Shaders.VS_QUAD, Shaders.FS_TIDE, "aP")
            mote = link(Shaders.VS_MOTE, Shaders.FS_MOTE, "aPos", "aCol", "aShape")
        } catch (e: RuntimeException) {
            Log.w(TAG, "shaders unavailable, falling back to the canvas cloud: ${e.message}")
            onUnavailable(e.message ?: "shader failure")
            return
        }
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        triangle = ids[0]; points = ids[1]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triangle)
        val tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).apply { position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 6 * 4, tri, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, points)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MoteField.MAX_POINTS * MoteField.STRIDE * 4, null, GLES30.GL_DYNAMIC_DRAW)

        // the tide: TIDE_COLUMNS of history, starting as still, dark water
        GLES30.glGenTextures(1, ids, 0)
        tideTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tideTexture)
        val still = ByteBuffer.allocateDirect(TIDE_COLUMNS * SpectralTide.ROWS * 4)
        for (i in 0 until TIDE_COLUMNS * SpectralTide.ROWS) { still.put(SpectralTide.LUT, 0, 3); still.put(255.toByte()) }
        still.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, TIDE_COLUMNS, SpectralTide.ROWS, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, still)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        tideHead = 0

        // a new context has no targets; onSurfaceChanged builds them
        trailA = null; trailB = null; bloomA = null; bloomB = null
        width = 0; height = 0
        ready = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        if (!ready || (w == width && h == height)) return
        width = w; height = h
        for (t in listOf(trailA, trailB, bloomA, bloomB)) if (t != null) {
            GLES30.glDeleteTextures(1, intArrayOf(t.texture), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(t.framebuffer), 0)
        }
        val hw = max(2, w shr 1); val hh = max(2, h shr 1); val qw = max(2, w shr 2); val qh = max(2, h shr 2)
        try {
            trailA = target(hw, hh); trailB = target(hw, hh); bloomA = target(qw, qh); bloomB = target(qw, qh)
        } catch (e: RuntimeException) {
            ready = false
            Log.w(TAG, "framebuffers unavailable, falling back to the canvas cloud: ${e.message}")
            onUnavailable(e.message ?: "framebuffer failure")
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val tA = trailA; val tB = trailB; val bA = bloomA; val bB = bloomB
        if (!ready || tA == null || tB == null || bA == null || bB == null) {
            GLES30.glClearColor(0.012f, 0.02f, 0.024f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val t = (System.nanoTime() - startNanos) / 1e9
        val dt = min(0.1, if (lastT == 0.0) 1.0 / 60 else t - lastT).toFloat()
        lastT = t; frames++
        if (dt > 0f) fpsEma += (1f / dt - fpsEma) * 0.05f
        measuredFps = fpsEma
        holdFrameRate(dt)
        // The web's per-frame constants assume 60 frames a second; `step` rescales them to the time that passed.
        val step = dt * 60f

        source.read(frame)
        val cx: Float; val cy: Float; val cz: Float; val level: Float
        if (frame.live) {
            cx = frame.chaosX; cy = frame.chaosY; cz = frame.chaosZ
            idleLevel = frame.level; level = frame.level
        } else {                                        // idle: the attractor still drifts, slowly
            val k = 0.05
            cx = (sin(t * k * 1.3) * 0.6 + sin(t * k * 0.37) * 0.3).toFloat(); cy = (cos(t * k) * 0.6).toFloat()
            cz = (sin(t * k * 0.71) * 0.5).toFloat()
            idleLevel *= 0.95f.pow(step); level = idleLevel
        }
        alive += ((if (frame.live) 1f else 0.5f) - alive) * (1f - 0.96f.pow(step))

        val patch = frame.patch
        val aspect = width.toFloat() / height
        if (frame.live) motes.build(t, frame.cloud, frame.grainCount, patch, aspect, cx, cy)
        else motes.build(t, ghostCloud, motes.ghost(t, patch, ghostCloud), patch, aspect, cx, cy)
        moteBuffer.position(0); moteBuffer.put(motes.data, 0, motes.count * MoteField.STRIDE); moteBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, points)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, motes.count * MoteField.STRIDE * 4, moteBuffer)

        advanceTide(dt)
        val px = densityScale()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        // 1. trails: the previous frame decays (and sinks very slightly), new bodies are added
        val decay = if (patch.grainMs < 400f) 0.95f else 0.9f      // short grains leave longer wakes
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tB.framebuffer); GLES30.glViewport(0, 0, tB.width, tB.height)
        val fadeP = quad(fade!!); bindTexture(0, tA.texture); GLES30.glUniform1i(fadeP.u("uTex"), 0)
        GLES30.glUniform1f(fadeP.u("uDecay"), decay.pow(step)); GLES30.glUniform1f(fadeP.u("uFloor"), 0.004f * step)
        GLES30.glUniform2f(fadeP.u("uDrift"), cx * 0.0006f * step, 0.0007f * step)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        drawMotes(px * 0.5f * 1.15f, 0.55f * alive * min(2f, step))

        // 2. bloom: separable blur of the trail buffer at quarter resolution
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bA.framebuffer); GLES30.glViewport(0, 0, bA.width, bA.height)
        val blurP = quad(blur!!); bindTexture(0, tB.texture); GLES30.glUniform1i(blurP.u("uTex"), 0)
        GLES30.glUniform2f(blurP.u("uDir"), 1.6f / bA.width, 0f); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bB.framebuffer)
        bindTexture(0, bA.texture); GLES30.glUniform2f(blurP.u("uDir"), 0f, 1.6f / bA.height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 3. the water, plus trails and bloom
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, width, height)
        val sceneP = quad(scene!!); bindTexture(0, tB.texture); bindTexture(1, bB.texture)
        GLES30.glUniform1i(sceneP.u("uTrail"), 0); GLES30.glUniform1i(sceneP.u("uBloom"), 1)
        GLES30.glUniform2f(sceneP.u("uRes"), width.toFloat(), height.toFloat())
        GLES30.glUniform1f(sceneP.u("uT"), (t % 3600.0).toFloat())
        GLES30.glUniform3f(sceneP.u("uCh"), cx, cy, cz); GLES30.glUniform1f(sceneP.u("uLevel"), min(1f, level * 1.6f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 4. the spectral tide, along the bottom
        GLES30.glViewport(0, 0, width, (height * TIDE_HEIGHT).toInt())
        val tideP = quad(tideProgram!!); bindTexture(0, tideTexture); GLES30.glUniform1i(tideP.u("uTide"), 0)
        GLES30.glUniform1f(tideP.u("uHead"), tideHead.toFloat() / TIDE_COLUMNS)
        GLES30.glUniform1f(tideP.u("uColumns"), TIDE_COLUMNS.toFloat())
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glViewport(0, 0, width, height)

        // 5. the bodies themselves, crisp, on top
        drawMotes(px, 0.9f * alive)

        trailA = tB; trailB = tA

        if (frames % 600L == 0L) {
            Log.d(TAG, "%.0f fps (target %.0f), quality %d, %dx%d, %d motes".format(fpsEma, targetFps, motes.quality, width, height, motes.count))
        }
    }

    /** One tide column per 1/30 s of real time, whatever the frame rate. */
    private fun advanceTide(dt: Float) {
        tideDebt += dt * TIDE_COLUMNS_PER_SECOND
        var columns = min(4, tideDebt.toInt())
        if (columns == 0) return
        tideDebt -= tideDebt.toInt()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tideTexture)
        while (columns-- > 0) {
            tide.column(if (frame.live && frame.hasSpectrum) frame.spectrum else null, frame.sampleRate, tideColumn)
            tideColumnBuffer.position(0); tideColumnBuffer.put(tideColumn); tideColumnBuffer.position(0)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, tideHead, 0, 1, SpectralTide.ROWS,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, tideColumnBuffer)
            tideHead = (tideHead + 1) % TIDE_COLUMNS
        }
    }

    /** If the frame rate sags below target, shed followers, then resolution, then plankton -- as the web does. */
    private fun holdFrameRate(dt: Float) {
        if (frames <= 90) return
        slowFor = if (fpsEma < targetFps * 0.83f) slowFor + dt else max(0f, slowFor - dt * 0.5f)
        if (slowFor > 2.5f && motes.quality < 4) {
            motes.quality++; slowFor = 0f
            when (motes.quality) {
                2 -> motes.followers = 1
                3 -> motes.followers = 0
                else -> motes.plankton = 60
            }
            Log.d(TAG, "%.0f fps against a target of %.0f: quality -> %d".format(fpsEma, targetFps, motes.quality))
            onQuality(motes.quality)
        }
    }

    private fun drawMotes(px: Float, gain: Float) {
        val p = mote!!
        GLES30.glUseProgram(p.id); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, points)
        val stride = MoteField.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1); GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glUniform1f(p.u("uPx"), px); GLES30.glUniform1f(p.u("uGain"), gain)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, motes.count)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2)
    }

    private fun quad(p: Program): Program {
        GLES30.glUseProgram(p.id); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triangle)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        return p
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun target(w: Int, h: Int): Target {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0); val texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, ids, 0); val framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw RuntimeException("framebuffer ${w}x$h incomplete")
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        return Target(texture, framebuffer, w, h)
    }

    private fun link(vertex: String, fragment: String, vararg attributes: String): Program {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, vertex))
        GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, fragment))
        attributes.forEachIndexed { index, name -> GLES30.glBindAttribLocation(program, index, name) }
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("link: " + GLES30.glGetProgramInfoLog(program))
        return Program(program)
    }

    private fun compile(type: Int, sourceCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, sourceCode)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("compile: " + GLES30.glGetShaderInfoLog(shader))
        return shader
    }

    companion object {
        const val TAG = "grvr-visuals"
        private const val TIDE_COLUMNS = 512
        private const val TIDE_COLUMNS_PER_SECOND = 30.0     // 17 s of history across the screen
        private const val TIDE_HEIGHT = 0.17f                 // of the screen
    }
}
