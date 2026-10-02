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
import androidx.compose.foundation.layout.widthIn
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
import com.delrogue.grooverider.ui.KEY_NAMES
import com.delrogue.grooverider.ui.SCALE_NAMES
import com.delrogue.grooverider.midi.MidiTarget
import com.delrogue.grooverider.onboarding.FactoryContent
import com.delrogue.grooverider.ui.cloud.gl.ObservatoryBackdrop
import com.delrogue.grooverider.ui.cloud.gl.ObservatoryGlView
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
    val captureHint by vm.captureHint.collectAsStateWithLifecycle()
    val subtitle by vm.subtitle.collectAsStateWithLifecycle()
    val chord by vm.chord.collectAsStateWithLifecycle()
    val keysMode by vm.keysMode.collectAsStateWithLifecycle()
    val midiDevices by vm.midiDevices.collectAsStateWithLifecycle()
    val midiArmed by vm.midiArmed.collectAsStateWithLifecycle()
    val midiBound by vm.midiBound.collectAsStateWithLifecycle()
    val midiNote by vm.midiNote.collectAsStateWithLifecycle()
    var showDetail by remember { mutableStateOf(false) }
    val captureReview by vm.captureReview.collectAsStateWithLifecycle()
    val rendering by vm.rendering.collectAsStateWithLifecycle()
    val selectedSource by sourceVm.selected.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var showHelp by remember { mutableStateOf(false) }
    // The Observatory visuals need OpenGL ES 3.0. Where that is missing, or
    // turns out not to work, the original canvas cloud is drawn instead.
    var glVisuals by remember { mutableStateOf(ObservatoryGlView.isSupported(context)) }

    LaunchedEffect(midiNote) {
        if (midiNote != null && midiArmed == null) {      // an armed control keeps its prompt up until it is bound
            delay(3000)
            vm.dismissMidiNote()
        }
    }

    LaunchedEffect(captureHint) {
        if (captureHint != null) {
            delay(2500)
            vm.dismissCaptureHint()
        }
    }

    Box(modifier.fillMaxSize().background(kBackground)) {
        if (glVisuals) {
            ObservatoryBackdrop(vm, Modifier.fillMaxSize(), onUnavailable = { glVisuals = false })
        }
        // Over the Observatory this layer is see-through and draws only the
        // playing surface (waveform, position, cursor). On its own it is the
        // whole picture, grains included.
        CloudLayer(
            vm = vm,
            overlay = glVisuals,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectCloudPadGestures(
                        onDrag = vm::onCanvasDrag,
                        onPinchDelta = vm::onCanvasPinch,
                        onRotateDelta = vm::onCanvasRotate,
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.onLongPress()
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
            peaks = selectedSource?.peaks ?: FloatArray(0),
            position = grain.position,
            // where the cursor sits up and down: tone with the Observatory, pitch without
            cursorHeight = if (grain.observatory) grain.tone * 2f - 1f
                else ((2f.pow(grain.pitchSt / 12f) - 1f) / 2f).coerceIn(-1f, 1f),
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
            subtitle = subtitle,
            presets = vm.factoryPresets,
            onPreset = vm::loadPreset,
            peak = meters.peakL,
            voices = meters.activeVoices,
            canGoBack = canGoBack,
            onBack = vm::goBackInLineage,
            onCapture = vm::requestCapture,
            onExit = onExit,
            onHelp = { showHelp = true },
        )

        // The four rings, in the corners where thumbs rest.
        @Composable
        fun ring(target: MidiTarget, label: String, value: Float, onValue: (Float) -> Unit, modifier: Modifier) =
            MacroRing(label, value, onValue, onReset = { vm.resetMacro(target) }, onLearn = { vm.armMidi(target) },
                armed = midiArmed == target, bound = midiBound[target], modifier = modifier)
        ring(MidiTarget.TEXTURE, "TEXTURE", macro.texture, vm::setMacroTexture,
            Modifier.align(Alignment.TopStart).padding(top = 58.dp, start = 14.dp))
        ring(MidiTarget.PITCH, "PITCH", macro.pitch, vm::setMacroPitch,
            Modifier.align(Alignment.TopEnd).padding(top = 58.dp, end = 14.dp))
        ring(MidiTarget.DRIFT, "DRIFT", macro.drift, vm::setMacroDrift,
            Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 6.dp))
        ring(MidiTarget.SPACE, "SPACE", macro.space, vm::setMacroSpace,
            Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 6.dp))

        // Drone, tuning and the way into the fine controls. They belong to the
        // Observatory, so they dim on a Seed that does not use it.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            DroneButton(grain.drone, enabled = grain.observatory, armed = midiArmed == MidiTarget.DRONE,
                onToggle = { vm.setDroneOn(!grain.drone) }, onLearn = { vm.armMidi(MidiTarget.DRONE) })
            PickerChip(KEY_NAMES[grain.key.coerceIn(0, 11)], KEY_NAMES, grain.key, grain.observatory, vm::setKey)
            PickerChip(SCALE_NAMES[grain.scale.coerceIn(0, 6)], SCALE_NAMES, grain.scale, grain.observatory, vm::setScale)
            Text(
                if (midiDevices.isEmpty()) "Detail" else "Detail ◉",
                color = kInk, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(kScrim.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .clickable { showDetail = !showDetail }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
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

        (captureHint ?: midiNote)?.let {
            Text(
                it,
                color = Color(0xFFB0B0B8),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 58.dp)
                    .background(kScrim.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        DetailPanel(
            visible = showDetail, onClose = { showDetail = false },
            shimmer = grain.shimmer, onShimmer = vm::setShimmer,
            tone = grain.tone, onTone = vm::setTone,
            register = grain.register, onRegister = vm::setRegister,
            scan = grain.scan, onScan = vm::setScan, scanLatched = grain.drone,
            keysMode = keysMode, onKeysMode = vm::setKeysMode,
            chord = chord, onClearChord = vm::clearChord,
            midiDevices = midiDevices,
            midiArmed = midiArmed, midiBound = midiBound, onLearn = vm::armMidi, onForget = vm::forgetMidiBindings,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
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
            body = CLOUD_HELP,
            onDismiss = { showHelp = false },
        )
    }
}

@Composable
private fun CloudHeader(
    modifier: Modifier,
    seedName: String,
    subtitle: String,
    presets: List<FactoryContent.Preset>,
    onPreset: (String) -> Unit,
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
            PresetTitle(seedName, subtitle, presets, onPreset, Modifier.widthIn(max = 360.dp))
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

/**
 * The canvas layer. Reading the grain cloud redraws at 60 frames a second, so
 * it is only collected here, and only when this layer is the one drawing the
 * grains.
 */
@Composable
private fun CloudLayer(
    vm: EngineViewModel,
    overlay: Boolean,
    modifier: Modifier,
    peaks: FloatArray,
    position: Float,
    cursorHeight: Float,
    sprayMs: Float,
    sourceDurationMs: Long,
    frozen: Boolean,
) {
    if (overlay) {
        CloudCanvas(modifier, true, emptyList(), peaks, position, cursorHeight, sprayMs, sourceDurationMs, frozen)
    } else {
        val cloud by vm.cloud.collectAsStateWithLifecycle()
        CloudCanvas(modifier, false, cloud.grains, peaks, position, cursorHeight, sprayMs, sourceDurationMs, frozen)
    }
}

@Composable
private fun CloudCanvas(
    modifier: Modifier,
    overlay: Boolean,
    grains: List<GrainVisual>,
    peaks: FloatArray,
    position: Float,
    cursorHeight: Float,        // -1 .. 1, bottom to top
    sprayMs: Float,
    sourceDurationMs: Long,
    frozen: Boolean,
) {
    Canvas(if (overlay) modifier else modifier.background(Color(0xFF0B0B0E))) {
        val w = size.width
        val h = size.height
        val waveTop = h * 0.55f
        val waveHeight = h * 0.35f

        // Touch cursor: last touched position/pitch, plotted on the same
        // axes the grains use, so it sits exactly among the grains it is
        // spawning. Holds still after lift -- no snap-back (spec 5.3).
        val cursorX = position * w
        val cursorY = h * 0.5f - cursorHeight.coerceIn(-1f, 1f) * (h * 0.4f)
        val cursorColor = if (frozen) Color(0xFF3FA7FF) else Color(0xFFE0574D)

        // Faint vignette so the field has depth instead of a flat fill.
        if (!overlay) drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF17171D), Color(0xFF0B0B0E)),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = maxOf(w, h) * 0.8f,
            ),
            size = size,
        )
        // Over the Observatory the waveform is a faint guide, not a wall.
        val waveAlpha = if (overlay) 0.35f else 1f

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
                    color = tint.copy(alpha = waveAlpha),
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
        drawRect(Color(0xFFE0E0E6).copy(alpha = if (overlay) 0.5f else 1f), topLeft = Offset(position * w - 1f, 0f),
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

private const val CLOUD_HELP =
    "Headphones help. The stereo width and the drones are a big part of the sound, and a phone speaker loses most of that.\n\n" +
    "SHAPE IT\n" +
    "• Drag left and right to move through the sound, up and down to make it brighter or darker.\n" +
    "• TEXTURE goes from long, smooth grains to short, dense ones.\n" +
    "• DRIFT sets how far and how fast the sound wanders on its own. Low drifts over minutes; high churns.\n" +
    "• SPACE is the reverb, from a room to an ocean. PITCH is how far the grains scatter in pitch.\n" +
    "• DRONE holds one spot in the sound and lets it evolve endlessly. A long press on the field does the same.\n" +
    "• KEY and SCALE keep everything in tune. Choose Free to unlock. Key assumes the sound is in C.\n" +
    "• Detail opens the fine controls: shimmer, tone, register and scan.\n" +
    "• Tap the name at the top for the other presets.\n\n" +
    "CONTROLS\n" +
    "• Rings: drag up or right for more, down or left for less. Double-tap returns to the preset's value.\n" +
    "• Pinch for SPACE, twist two fingers to scan through the sound, double-tap the field for a new variation.\n" +
    "• Three-finger tap, or the record button, keeps the last 60 seconds as a WAV with its reverb tail.\n\n" +
    "MIDI KEYBOARD AND KNOBS\n" +
    "• Plug a USB MIDI keyboard into the phone and just play: notes set the pitch, and chords spread the cloud across the notes. A sustain pedal holds them.\n" +
    "• In Detail, Latch keeps a chord after you let go; Gate only sounds while keys are held.\n" +
    "• Knobs: long-press a ring, or a slider's name in Detail, then turn a knob. For DRONE, long-press it, then hit a pad.\n" +
    "• Your mappings are remembered.\n\n" +
    "TIPS\n" +
    "• Long, sustained sounds -- voice, strings, field recordings -- make the best pads and drones.\n" +
    "• For a drone: DRONE on, SPACE high, DRIFT low. Then leave it alone for a minute -- it keeps changing.\n" +
    "• For movement: DRONE off, raise DRIFT, and nudge Scan in Detail.\n" +
    "• Shimmer adds a halo an octave up. A little goes a long way.\n" +
    "• On a Seed made before the Observatory, the rings and gestures work as they always did, and DRONE, key and scale are dimmed."
