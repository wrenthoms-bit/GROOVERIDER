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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.delrogue.grooverider.engine.GrainVisual
import com.delrogue.grooverider.render.ShareExporter
import com.delrogue.grooverider.ui.EngineViewModel
import com.delrogue.grooverider.ui.HelpButton
import com.delrogue.grooverider.ui.HelpDialog
import com.delrogue.grooverider.ui.source.SourceViewModel
import kotlinx.coroutines.delay
import kotlin.math.pow

private val kBackground = Color(0xFF0A0A0C)
private val kScrim = Color(0xFF0A0A0C)
private const val CURSOR_GLOW_RADIUS = 90f

/**
 * The performance screen (spec 5): landscape, one screen, no menus. The
 * waveform + live grain cloud fills the whole screen and *is* the control
 * surface -- touch position drives position/pitch directly, matching what's
 * drawn. Everything else (header, macro dials, hints) floats over it as
 * slim translucent overlays.
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
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(captureHint) {
        if (captureHint != null) {
            delay(2500)
            vm.dismissCaptureHint()
        }
    }

    Box(modifier.fillMaxSize().background(kBackground)) {
        CloudCanvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectCloudPadGestures(
                        onDrag = vm::onCanvasDrag,
                        onPinchDelta = vm::onCanvasPinch,
                        onRotateDelta = vm::onCanvasRotate,
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
                },
            grains = cloud.grains,
            peaks = selectedSource?.peaks ?: FloatArray(0),
            position = grain.position,
            pitchSt = grain.pitchSt,
            sprayMs = grain.sprayMs,
            sourceDurationMs = selectedSource?.let {
                if (it.record.sampleRate > 0) it.record.frames * 1000L / it.record.sampleRate else 0L
            } ?: 0L,
            frozen = frozen,
        )

        CloudHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(kScrim.copy(alpha = 0.85f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            seedName = seedName,
            peak = meters.peakL,
            voices = meters.activeVoices,
            canGoBack = canGoBack,
            onBack = vm::goBackInLineage,
            onCapture = vm::requestCapture,
            onExit = onExit,
            onHelp = { showHelp = true },
        )

        CornerDial(Modifier.align(Alignment.TopStart).padding(top = 56.dp, start = 8.dp)) {
            MacroKnob("TEXTURE", macro.texture, "texture" in macroLocks,
                { vm.toggleMacroLock("texture") }, vm::setMacroTexture)
        }
        CornerDial(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp)) {
            MacroKnob("PITCH", macro.pitch, "pitch" in macroLocks,
                { vm.toggleMacroLock("pitch") }, vm::setMacroPitch)
        }
        CornerDial(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp)) {
            MacroKnob("DRIFT", macro.drift, "drift" in macroLocks,
                { vm.toggleMacroLock("drift") }, vm::setMacroDrift)
        }
        CornerDial(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp)) {
            MacroKnob("SPACE", macro.space, "space" in macroLocks,
                { vm.toggleMacroLock("space") }, vm::setMacroSpace)
        }

        if (frozen) {
            Text(
                "FROZEN",
                color = Color(0xFF3FA7FF),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(kScrim.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        captureHint?.let {
            Text(
                it,
                color = Color(0xFFB0B0B8),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .background(kScrim.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
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

    if (showHelp) {
        HelpDialog(
            title = "Cloud",
            body = "Drag anywhere to scrub position (left/right) and pitch " +
                "(up/down) -- the cursor shows where you're reaching into the " +
                "grain cloud. Pinch to change stereo width, twist two fingers " +
                "for drift, long-press to freeze, double-tap to re-roll a " +
                "fresh variation, three-finger tap to capture the last 60 " +
                "seconds. The four corner dials lock and fine-tune " +
                "Texture/Pitch/Drift/Space.",
            onDismiss = { showHelp = false },
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
    onHelp: () -> Unit,
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
            HelpButton(onClick = onHelp, modifier = Modifier.padding(start = 10.dp), color = Color(0xFF6A6A70))
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

/** A small translucent chip that hosts one macro dial over the canvas. */
@Composable
private fun CornerDial(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .width(130.dp)
            .background(kScrim.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) { content() }
}

@Composable
private fun CloudCanvas(
    modifier: Modifier,
    grains: List<GrainVisual>,
    peaks: FloatArray,
    position: Float,
    pitchSt: Float,
    sprayMs: Float,
    sourceDurationMs: Long,
    frozen: Boolean,
) {
    Canvas(modifier.background(Color(0xFF0B0B0E))) {
        val w = size.width
        val h = size.height
        val waveTop = h * 0.55f
        val waveHeight = h * 0.35f

        // Touch cursor: last touched position/pitch, plotted on the same
        // axes the grains use, so it sits exactly among the grains it is
        // spawning. Holds still after lift -- no snap-back (spec 5.3).
        val cursorPitchRatio = 2f.pow(pitchSt / 12f)
        val cursorPitchNorm = ((cursorPitchRatio - 1f) / 2f).coerceIn(-1f, 1f)
        val cursorX = position * w
        val cursorY = h * 0.5f - cursorPitchNorm * (h * 0.4f)
        val cursorColor = if (frozen) Color(0xFF3FA7FF) else Color(0xFFE0574D)

        // Faint vignette so the field has depth instead of a flat fill.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF17171D), Color(0xFF0B0B0E)),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = maxOf(w, h) * 0.8f,
            ),
            size = size,
        )

        // Waveform -- bars near the cursor's column pick up its colour, so
        // the source and the touch that's reaching into it read as linked.
        if (peaks.isNotEmpty()) {
            val barW = w / peaks.size
            val reach = w * 0.16f
            for (i in peaks.indices) {
                val amp = peaks[i].coerceIn(0f, 1f) * waveHeight
                val barX = i * barW
                val proximity = (1f - (kotlin.math.abs(barX - cursorX) / reach).coerceIn(0f, 1f))
                val tint = lerp(Color(0xFF3C3C44), cursorColor, proximity * 0.55f)
                drawRect(
                    color = tint,
                    topLeft = Offset(barX, waveTop - amp / 2f),
                    size = Size(barW * 0.8f, amp),
                )
            }
        }

        // Spray window: a soft-edged luminous band around the position marker.
        if (sourceDurationMs > 0) {
            val sprayNorm = (sprayMs / sourceDurationMs.toFloat()).coerceIn(0f, 1f)
            val bandLeft = ((position - sprayNorm).coerceIn(0f, 1f)) * w
            val bandRight = ((position + sprayNorm).coerceIn(0f, 1f)) * w
            if (bandRight > bandLeft) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, cursorColor.copy(alpha = 0.16f), Color.Transparent),
                        startX = bandLeft,
                        endX = bandRight,
                    ),
                    topLeft = Offset(bandLeft, 0f),
                    size = Size(bandRight - bandLeft, h),
                )
            }
        }

        // Position marker.
        drawRect(Color(0xFFE0E0E6), topLeft = Offset(position * w - 1f, 0f),
            size = Size(2f, h))

        // Grain particles: a soft additive halo plus a bright core, on a
        // near-black field (spec 5.2, 5.5). Hue sweeps green -> cyan -> blue
        // -> violet across the stereo field, kept clear of the red/orange
        // accents used for the cursor and UI so the two never collide.
        // Fresh grains are saturated and bright; ageing ones wash out.
        // Grains inside the cursor's glow read as caught by it.
        val glowRadiusSq = CURSOR_GLOW_RADIUS * CURSOR_GLOW_RADIUS
        for (g in grains) {
            val x = g.sourcePosNorm * w
            val pitchNorm = ((g.pitchRatio - 1f) / 2f).coerceIn(-1f, 1f)   // centre = unity
            val y = h * 0.5f - pitchNorm * (h * 0.4f)
            val dx = x - cursorX
            val dy = y - cursorY
            val caught = dx * dx + dy * dy < glowRadiusSq

            val fresh = 1f - g.age01
            val hue = (0.56f + g.pan * 0.26f + pitchNorm * 0.05f).mod(1f)
            val sat = 0.4f + fresh * 0.5f
            val value = 0.65f + fresh * 0.35f
            val core = hsvColor(hue, sat, value)
            val ampBoost = if (caught) 1.3f else 1f
            val alpha = (g.amp * ampBoost).coerceIn(0f, 1f)
            val coreRadius = (1.5f + fresh * 3.5f) * (if (caught) 1.35f else 1f)

            drawCircle(
                color = core.copy(alpha = alpha * 0.35f),
                radius = coreRadius * 3f,
                center = Offset(x, y),
                blendMode = BlendMode.Plus,
            )
            drawCircle(color = core.copy(alpha = alpha), radius = coreRadius, center = Offset(x, y), style = Fill)
            if (caught) {
                drawCircle(
                    Color.White.copy(alpha = alpha * 0.5f),
                    radius = coreRadius + 2f,
                    center = Offset(x, y),
                    style = Stroke(width = 1.5f),
                )
            }
        }

        // Soft glow behind the cursor -- reaching into the cloud. A slow
        // breathing pulse and a hot white core keep it feeling alive even
        // when the touch is still.
        val pulse = 0.85f + 0.15f * kotlin.math.sin(
            (System.currentTimeMillis() % 2000L) / 2000f * (2f * Math.PI.toFloat())
        )
        val glowRadius = CURSOR_GLOW_RADIUS * pulse
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.5f),
                    cursorColor.copy(alpha = 0.32f),
                    cursorColor.copy(alpha = 0f),
                ),
                center = Offset(cursorX, cursorY),
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = Offset(cursorX, cursorY),
        )

        // Cursor dot on top of everything.
        drawCircle(cursorColor, radius = 10f, center = Offset(cursorX, cursorY))
        drawCircle(
            Color.White.copy(alpha = 0.5f),
            radius = 10f,
            center = Offset(cursorX, cursorY),
            style = Stroke(width = 2f),
        )
    }
}

private fun hsvColor(hue01: Float, saturation: Float, value: Float): Color {
    val hsv = floatArrayOf(hue01 * 360f, saturation, value)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
