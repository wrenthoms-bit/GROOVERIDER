package com.delrogue.grooverider.ui.cloud.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.delrogue.grooverider.engine.GrooveriderEngine
import com.delrogue.grooverider.source.SourceRepository
import com.delrogue.grooverider.ui.EngineViewModel
import com.delrogue.grooverider.ui.GrainState
import kotlin.math.asin
import kotlin.math.sin

/**
 * The Observatory visuals behind the Cloud screen. [onUnavailable] is called if
 * OpenGL ES 3.0 turns out not to be able to draw them, so the caller can show
 * the canvas cloud instead.
 */
@Composable
fun ObservatoryBackdrop(vm: EngineViewModel, modifier: Modifier = Modifier, onUnavailable: () -> Unit) {
    val source = remember(vm) { EngineSceneSource(vm) }
    val holder = remember { arrayOfNulls<ObservatoryGlView>(1) }
    AndroidView(
        modifier = modifier,
        factory = { context -> ObservatoryGlView(context, source) { onUnavailable() }.also { holder[0] = it } },
        onRelease = { it.onPause(); holder[0] = null },
    )
    // nothing is drawn while the app is in the background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * Reads the engine for the visuals, on the GL thread. Everything here is a
 * snapshot the view model already publishes for the UI (StateFlow values are
 * safe to read from any thread) or a read of the engine's capture ring.
 */
private class EngineSceneSource(private val vm: EngineViewModel) : SceneSource {
    private var sampleRate = 48000
    private var reads = 0

    override fun read(into: SceneFrame) {
        val meters = vm.meters.value
        val grain = vm.grain.value
        into.live = meters.running && SourceRepository.engineSourceHash.value.isNotEmpty()
        into.patch = patchOf(grain)
        into.chaosX = meters.chaosX; into.chaosY = meters.chaosY; into.chaosZ = meters.chaosZ
        into.level = maxOf(meters.peakL, meters.peakR)

        val grains = vm.cloud.value.grains
        val n = minOf(grains.size, 256)
        for (i in 0 until n) {
            val g = grains[i]
            val o = i * MoteField.CLOUD_STRIDE
            into.cloud[o] = g.sourcePosNorm
            into.cloud[o + 1] = g.pitchRatio
            into.cloud[o + 2] = g.amp
            into.cloud[o + 3] = g.age01
            // the engine publishes pan as right gain minus left; the motes want the right gain
            into.cloud[o + 4] = sin(QUARTER_PI + asin((g.pan / SQRT2).coerceIn(-1f, 1f)))
        }
        into.grainCount = n

        if (reads++ % 120 == 0) GrooveriderEngine.engineSampleRate().let { if (it > 0) sampleRate = it }
        into.sampleRate = sampleRate
        into.hasSpectrum = into.live && GrooveriderEngine.spectrum(into.spectrum)
    }

    private fun patchOf(g: GrainState): MoteField.Patch =
        if (g.observatory) {
            val scaleSet = MoteField.SCALE_SETS.getOrNull(g.scale)
            MoteField.Patch(
                grainMs = g.grainSizeMs, density = g.density,
                transposeSt = (if (g.key > 6) g.key - 12 else g.key) + g.register,
                pitchSpreadSt = if (scaleSet != null) g.pitchAmount * 24f else g.pitchAmount * g.pitchAmount * 18f,
                scaleSet = scaleSet, spread = g.spread, reverse = g.reverseProb, position = g.position,
            )
        } else {
            MoteField.Patch(
                grainMs = g.grainSizeMs, density = g.density, transposeSt = g.pitchSt, pitchSpreadSt = g.pitchSpraySt,
                scaleSet = null, spread = g.spread, reverse = g.reverseProb, position = g.position,
            )
        }

    private companion object {
        const val QUARTER_PI = 0.7853982f
        const val SQRT2 = 1.4142135f
    }
}
