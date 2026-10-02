package com.delrogue.grooverider.ui.cloud.gl

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.util.Log
import android.view.Choreographer

/**
 * Hosts [ObservatoryRenderer]. Draws on demand, paced from the display's own
 * frame clock: 60 frames a second on mains power, 30 on battery. Nothing is
 * drawn while paused -- call [onPause] / [onResume] with the screen it lives on.
 */
@SuppressLint("ViewConstructor")
class ObservatoryGlView(
    context: Context,
    source: SceneSource,
    onUnavailable: (String) -> Unit,     // called on the main thread, once, if OpenGL ES 3.0 cannot draw this
) : GLSurfaceView(context), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    // The web caps its pixel ratio at 1.5 (1 once it has had to shed quality); same here.
    @Volatile private var resolutionScale = minOf(1f, 1.5f / density)
    private var fullWidth = 0
    private var fullHeight = 0
    // The web's body sizes are meant for a browser window some 760 px tall. A
    // phone on its side is about half that, so the same bodies would crowd
    // into a white mass; scale them to the screen they are on.
    @Volatile private var bodyScale = 1f

    private val renderer = ObservatoryRenderer(
        source = source,
        densityScale = { density * resolutionScale * bodyScale },
        onQuality = { quality -> if (quality >= 3) post { setResolutionScale(minOf(1f, 1f / density)) } },
        onUnavailable = { reason -> post { stopFrames(); onUnavailable(reason) } },
    )

    private var running = false
    private var lastRenderNanos = 0L
    private var lastBatteryCheckNanos = 0L
    private var onBattery = false

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)        // no depth, no stencil: everything is a flat pass
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    val measuredFps: Float get() = renderer.measuredFps
    val quality: Int get() = renderer.quality

    override fun onAttachedToWindow() { super.onAttachedToWindow(); startFrames() }
    override fun onDetachedFromWindow() { stopFrames(); super.onDetachedFromWindow() }
    override fun onResume() { super.onResume(); startFrames() }
    override fun onPause() { stopFrames(); super.onPause() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fullWidth = w; fullHeight = h
        bodyScale = (h / density / 760f).coerceIn(0.55f, 1f)
        applyResolution()
    }

    private fun setResolutionScale(scale: Float) {
        if (scale == resolutionScale) return
        resolutionScale = scale
        applyResolution()
    }

    private fun applyResolution() {
        if (fullWidth <= 0 || fullHeight <= 0) return
        if (resolutionScale >= 1f) holder.setSizeFromLayout()
        else holder.setFixedSize(maxOf(2, (fullWidth * resolutionScale).toInt()), maxOf(2, (fullHeight * resolutionScale).toInt()))
    }

    private fun startFrames() {
        if (running || !isAttachedToWindow) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopFrames() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (frameTimeNanos - lastBatteryCheckNanos > 2_000_000_000L) {
            lastBatteryCheckNanos = frameTimeNanos
            onBattery = isOnBattery()
            renderer.targetFps = if (onBattery) 30f else 60f
        }
        // a little under the interval, so a display running at exactly the target rate is not halved by jitter
        val interval = if (onBattery) 33_333_333L else 16_666_667L
        if (frameTimeNanos - lastRenderNanos >= interval - 2_000_000L) {
            lastRenderNanos = frameTimeNanos
            requestRender()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun isOnBattery(): Boolean {
        // a sticky broadcast: reading it registers nothing
        val status: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
    }

    companion object {
        /**
         * Whether this device offers OpenGL ES 3.0 at all. For comparing against
         * the canvas cloud, or if the visuals misbehave on some device, they can
         * be switched off from a computer: `adb shell setprop log.tag.grvr-nogl DEBUG`
         * (and `... INFO` to bring them back), then reopen the Cloud screen.
         */
        fun isSupported(context: Context): Boolean {
            if (Log.isLoggable("grvr-nogl", Log.DEBUG)) return false
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            return manager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        }
    }
}
