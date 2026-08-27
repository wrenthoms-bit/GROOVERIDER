package com.delrogue.grooverider.ui.cloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.delrogue.grooverider.engine.GrainVisual
import com.delrogue.grooverider.render.ShareExporter
import com.delrogue.grooverider.ui.EngineViewModel
import com.delrogue.grooverider.ui.MacroState
import com.delrogue.grooverider.ui.source.SourceViewModel
import kotlinx.coroutines.delay

private val kBackground = Color(0xFF0A0A0C)

/**
 * The performance screen (spec 5): landscape, one screen, no menus. Every
 * control needed to sculpt a texture lives here.
 */
@Composable
fun CloudScreen(
    modifier: Modifier = Modifier,
    vm: EngineViewModel = viewModel(),
    sourceVm: SourceViewModel = viewModel(),
    onExit: () -> Unit = {},
) {
    LockLandscape()

    val meters by vm.meters.collectAsStateWithLifecycle()
    val grain by vm.grain.collectAsStateWithLifecycle()
    val macro by vm.macro.collectAsStateWithLifecycle()
    val macroLocks by vm.macroLocks.collectAsStateWithLifecycle()
    val frozen by vm.frozen.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBackInLineage.collectAsStateWithLifecycle()
    val seedName by vm.seedName.collectAsStateWithLifecycle()
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    val captureHint by vm.captureHint.collectAsStateWithLifecycle()
    val captureReview by vm.captureReview.collectAsStateWithLifecycle()
    val rendering by vm.rendering.collectAsStateWithLifecycle()
    val selectedSource by sourceVm.selected.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    LaunchedEffect(captureHint) {
        if (captureHint != null) {
            delay(2500)
            vm.dismissCaptureHint()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(kBackground)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CloudHeader(
            modifier = Modifier.fillMaxWidth().weight(0.08f),
            seedName = seedName,
            peak = meters.peakL,
            voices = meters.activeVoices,
            canGoBack = canGoBack,
            onBack = vm::goBackInLineage,
            onCapture = vm::requestCapture,
            onExit = onExit,
        )

        CloudCanvas(
            modifier = Modifier.fillMaxWidth().weight(0.34f),
            grains = cloud.grains,
            peaks = selectedSource?.peaks ?: FloatArray(0),
            position = grain.position,
            sprayMs = grain.sprayMs,
            sourceDurationMs = selectedSource?.let {
                if (it.record.sampleRate > 0) it.record.frames * 1000L / it.record.sampleRate else 0L
            } ?: 0L,
        )

        XyPad(
            modifier = Modifier.fillMaxWidth().weight(0.40f),
            positionX = grain.position,
            textureY = macro.texture,
            frozen = frozen,
            onDrag = { x, y -> vm.onPadDrag(x, y) },
            onPinchDelta = vm::onPadPinch,
            onRotateDelta = vm::onPadRotate,
            onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.setFrozen(!frozen)
            },
            onDoubleTap = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.reRoll()
            },
            onThreeFingerTap = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.requestCapture()
            },
        )

        MacroRow(
            modifier = Modifier.fillMaxWidth().weight(0.18f),
            macro = macro,
            locks = macroLocks,
            onTexture = vm::setMacroTexture,
            onDrift = vm::setMacroDrift,
            onPitch = vm::setMacroPitch,
            onSpace = vm::setMacroSpace,
            onToggleLock = vm::toggleMacroLock,
        )

        captureHint?.let {
            Text(it, color = Color(0xFFB0B0B8), style = MaterialTheme.typography.bodySmall)
        }
    }

    captureReview?.let { capture ->
        CaptureReviewSheet(
            capture = capture,
            rendering = rendering,
            onTrimChange = vm::setCaptureTrim,
            onDiscard = vm::discardCapture,
            onKeep = {
                val hash = selectedSource?.record?.hash ?: ""
                vm.keepCapture(hash) { file -> ShareExporter.share(context, file) }
            },
        )
    }
}

@Composable
private fun CloudHeader(
    modifier: Modifier,
    seedName: String,
    peak: Float,
    voices: Int,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onCapture: () -> Unit,
    onExit: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✕",
                color = Color(0xFF9AA0A6),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clickable(onClick = onExit)
                    .padding(end = 12.dp),
            )
            if (canGoBack) {
                Text(
                    "◂",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 8.dp),
                )
            }
            Text(seedName, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        MeterBar(peak = peak, modifier = Modifier.width(120.dp))
        Text("voices $voices", color = Color(0xFF9AA0A6), style = MaterialTheme.typography.bodySmall)
        Text(
            "⏺",
            color = Color(0xFFE0574D),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clickable(onClick = onCapture).padding(start = 8.dp),
        )
    }
}

@Composable
private fun MeterBar(peak: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.height(8.dp)) {
        val w = size.width * peak.coerceIn(0f, 1f)
        drawRect(Color(0xFF2A2A2E))
        drawRect(Color(0xFF7FE0C8), size = size.copy(width = w))
    }
}

@Composable
private fun CloudCanvas(
    modifier: Modifier,
    grains: List<GrainVisual>,
    peaks: FloatArray,
    position: Float,
    sprayMs: Float,
    sourceDurationMs: Long,
) {
    Canvas(modifier.background(Color(0xFF111114))) {
        val w = size.width
        val h = size.height
        val waveTop = h * 0.55f
        val waveHeight = h * 0.35f

        // Waveform, muted grey.
        if (peaks.isNotEmpty()) {
            val barW = w / peaks.size
            for (i in peaks.indices) {
                val amp = peaks[i].coerceIn(0f, 1f) * waveHeight
                drawRect(
                    color = Color(0xFF4A4A50),
                    topLeft = Offset(i * barW, waveTop - amp / 2f),
                    size = androidx.compose.ui.geometry.Size(barW * 0.8f, amp),
                )
            }
        }

        // Spray window: a soft luminous band around the position marker.
        if (sourceDurationMs > 0) {
            val sprayNorm = (sprayMs / sourceDurationMs.toFloat()).coerceIn(0f, 1f)
            val bandLeft = ((position - sprayNorm).coerceIn(0f, 1f)) * w
            val bandRight = ((position + sprayNorm).coerceIn(0f, 1f)) * w
            drawRect(
                color = Color(0xFF3FA7FF).copy(alpha = 0.12f),
                topLeft = Offset(bandLeft, 0f),
                size = androidx.compose.ui.geometry.Size(bandRight - bandLeft, h),
            )
        }

        // Position marker.
        drawRect(Color(0xFFE0E0E6), topLeft = Offset(position * w - 1f, 0f),
            size = androidx.compose.ui.geometry.Size(2f, h))

        // Grain particles, additive-ish on a near-black field (spec 5.2, 5.5).
        for (g in grains) {
            val x = g.sourcePosNorm * w
            val pitchNorm = ((g.pitchRatio - 1f) / 2f).coerceIn(-1f, 1f)   // centre = unity
            val y = h * 0.5f - pitchNorm * (h * 0.4f)
            val radius = 2f + (1f - g.age01) * 6f
            val hue = 0.5f + g.pan * 0.15f   // left cooler, right warmer (spec 5.5)
            val color = hsvColor(hue, 0.6f, 1f).copy(alpha = g.amp.coerceIn(0f, 1f))
            drawCircle(color = color, radius = radius, center = Offset(x, y), style = Fill)
        }
    }
}

private fun hsvColor(hue01: Float, saturation: Float, value: Float): Color {
    val hsv = floatArrayOf(hue01 * 360f, saturation, value)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
private fun XyPad(
    modifier: Modifier,
    positionX: Float,
    textureY: Float,
    frozen: Boolean,
    onDrag: (Float, Float) -> Unit,
    onPinchDelta: (Float) -> Unit,
    onRotateDelta: (Float) -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    onThreeFingerTap: () -> Unit,
) {
    Box(
        modifier
            .background(if (frozen) Color(0xFF12181A) else Color(0xFF0E0E11))
            .pointerInput(Unit) {
                detectCloudPadGestures(
                    onDrag = onDrag,
                    onPinchDelta = onPinchDelta,
                    onRotateDelta = onRotateDelta,
                    onLongPress = onLongPress,
                    onDoubleTap = onDoubleTap,
                    onThreeFingerTap = onThreeFingerTap,
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = positionX * size.width
            val cy = (1f - textureY) * size.height
            drawCircle(Color(0xFF2A2A2E), radius = size.minDimension * 0.02f, center = Offset(cx, size.height / 2f))
            drawCircle(
                color = if (frozen) Color(0xFF3FA7FF) else Color(0xFFE0574D),
                radius = 10f,
                center = Offset(cx, cy),
            )
        }
        Text(
            if (frozen) "FROZEN" else "X = position   Y = texture",
            color = Color(0xFF6A6A70),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        )
    }
}

@Composable
private fun MacroRow(
    modifier: Modifier,
    macro: MacroState,
    locks: Set<String>,
    onTexture: (Float) -> Unit,
    onDrift: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onSpace: (Float) -> Unit,
    onToggleLock: (String) -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MacroKnob("TEXTURE", macro.texture, "texture" in locks, { onToggleLock("texture") }, onTexture, Modifier.weight(1f))
        MacroKnob("DRIFT", macro.drift, "drift" in locks, { onToggleLock("drift") }, onDrift, Modifier.weight(1f))
        MacroKnob("PITCH", macro.pitch, "pitch" in locks, { onToggleLock("pitch") }, onPitch, Modifier.weight(1f))
        MacroKnob("SPACE", macro.space, "space" in locks, { onToggleLock("space") }, onSpace, Modifier.weight(1f))
    }
}

@Composable
private fun MacroKnob(
    label: String,
    value: Float,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (locked) "🔒 " else "") + label,
                color = if (locked) Color(0xFF3FA7FF) else Color(0xFFB0B0B8),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onToggleLock),
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            enabled = !locked,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFE0574D),
                activeTrackColor = Color(0xFFE0574D).copy(alpha = 0.6f),
            ),
        )
    }
}

@Composable
private fun LockLandscape() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
