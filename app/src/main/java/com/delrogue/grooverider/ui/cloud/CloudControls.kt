package com.delrogue.grooverider.ui.cloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.delrogue.grooverider.midi.KeyboardChord
import com.delrogue.grooverider.midi.MidiTarget
import com.delrogue.grooverider.onboarding.FactoryContent
import com.delrogue.grooverider.ui.theme.Abyss
import com.delrogue.grooverider.ui.theme.Amber
import com.delrogue.grooverider.ui.theme.Cyan
import com.delrogue.grooverider.ui.theme.Dim
import com.delrogue.grooverider.ui.theme.Ink
import com.delrogue.grooverider.ui.theme.OnAccent
import kotlin.math.floor
import kotlin.math.roundToInt

internal val kAmber = Amber
internal val kCyan = Cyan
internal val kInk = Ink
internal val kDim = Dim
private val kPanel = Abyss

/** A light tick: a detent passed, a step taken. */
internal fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.TextHandleMove)
/** A firmer one: a switch thrown, an octave crossed. */
internal fun HapticFeedback.thump() = performHapticFeedback(HapticFeedbackType.LongPress)

/** Rings click at the quarters and at both ends. */
private fun crossedDetent(from: Float, to: Float): Boolean {
    if (from == to) return false
    if ((to == 0f || to == 1f)) return true
    return floor(from * 4f) != floor(to * 4f)
}

/**
 * One of the four macro rings, as on the web: drag up or right for more.
 * Double-tap returns to the loaded patch's value; long-press arms it for a
 * MIDI knob.
 */
@Composable
fun MacroRing(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    onReset: () -> Unit,
    onLearn: () -> Unit,
    armed: Boolean,
    bound: String?,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val setValue by rememberUpdatedState(onValue)
    val reset by rememberUpdatedState(onReset)
    val learn by rememberUpdatedState(onLearn)
    val travel = with(LocalDensity.current) { 190.dp.toPx() }     // a full sweep, as on the web

    Column(
        // a soft backing, so the label reads over the tide and the brighter water
        modifier.background(kPanel.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { haptic.thump(); reset() },
                        onLongPress = { haptic.thump(); learn() },
                    )
                }
                .pointerInput(Unit) {
                    var start = 0f; var travelled = 0f; var last = 0f
                    detectDragGestures(onDragStart = { start = current; travelled = 0f; last = current }) { change, drag ->
                        change.consume()
                        travelled += drag.x - drag.y
                        val next = (start + travelled / travel).coerceIn(0f, 1f)
                        if (crossedDetent(last, next)) haptic.tick()
                        last = next
                        setValue(next)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                drawCircle(kPanel.copy(alpha = 0.62f), radius = size.minDimension / 2f)
                drawArc(Color.White.copy(alpha = 0.13f), 135f, 270f, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
                if (value > 0.004f) drawArc(
                    Brush.linearGradient(listOf(kAmber, kCyan), start = Offset(0f, size.height), end = Offset(size.width, 0f)),
                    135f, 270f * value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (armed) drawCircle(kAmber, radius = size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
            Text("${(value * 100f).roundToInt()}", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Text(label, color = kInk, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (armed) "turn a knob…" else bound ?: " ",
            color = if (armed) kAmber else kDim, style = MaterialTheme.typography.labelSmall, maxLines = 1,
        )
    }
}

/** Latches the playhead and lets the spot evolve. Long-press arms it for a MIDI pad. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DroneButton(on: Boolean, enabled: Boolean, armed: Boolean, onToggle: () -> Unit, onLearn: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    Text(
        if (armed) "hit a pad…" else "DRONE",
        color = if (on) OnAccent else kInk,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (on) kAmber else kPanel.copy(alpha = 0.7f), shape)
            .border(1.dp, if (armed || on) kAmber else Color.White.copy(alpha = 0.18f), shape)
            .combinedClickable(
                enabled = enabled,
                onClick = { haptic.thump(); onToggle() },
                onLongClick = { haptic.thump(); onLearn() },
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/** A chip that opens a short list: key, scale. */
@Composable
fun PickerChip(text: String, options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(modifier) {
        Text(
            "$text ▾", color = kInk, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.4f)
                .background(kPanel.copy(alpha = 0.7f), shape)
                .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name, fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; if (index != selected) { haptic.tick(); onSelect(index) } },
                )
            }
        }
    }
}

/** The preset's name and what it is; tap for the other presets. */
@Composable
fun PresetTitle(
    name: String,
    subtitle: String,
    presets: List<FactoryContent.Preset>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.clickable { open = true }) {
            Text("$name ▾", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = kDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(preset.name, fontWeight = FontWeight.SemiBold)
                            Text(preset.subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; onPick(preset.id) },
                )
            }
        }
    }
}

/** The fine controls, sliding in from the right: shimmer, tone, register, scan, and the keyboard. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailPanel(
    visible: Boolean,
    onClose: () -> Unit,
    shimmer: Float, onShimmer: (Float) -> Unit,
    tone: Float, onTone: (Float) -> Unit,
    register: Float, onRegister: (Float) -> Unit,
    scan: Float, onScan: (Float) -> Unit,
    scanLatched: Boolean,
    keysMode: KeyboardChord.Mode, onKeysMode: (KeyboardChord.Mode) -> Unit,
    chord: List<Int>, onClearChord: () -> Unit,
    midiDevices: List<String>,
    midiArmed: MidiTarget?, midiBound: Map<MidiTarget, String>, onLearn: (MidiTarget) -> Unit, onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    AnimatedVisibility(visible, modifier, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
        Column(
            Modifier
                .width(300.dp).fillMaxHeight()
                .background(kPanel)      // solid: the controls behind it must not show through
                // swallow touches so the field underneath is not played through the panel
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Detail", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("✕", color = kDim, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            }

            DetailSlider("Shimmer (+12)", "${(shimmer * 100).roundToInt()}%", shimmer, onShimmer,
                MidiTarget.SHIMMER, midiArmed, midiBound, onLearn)
            val toneHz = 500f * Math.pow(36.0, tone.toDouble()).toFloat()
            DetailSlider("Tone", if (toneHz >= 1000f) "%.1f kHz".format(toneHz / 1000f) else "${toneHz.roundToInt()} Hz", tone, onTone,
                MidiTarget.TONE, midiArmed, midiBound, onLearn)
            DetailSlider("Register", "%+d st".format(register.roundToInt()), (register + 24f) / 48f, { position ->
                val semitones = (position * 48f - 24f).roundToInt().toFloat()
                if (semitones != register) {
                    if (semitones.toInt() % 12 == 0) haptic.thump() else haptic.tick()     // octaves land harder
                    onRegister(semitones)
                }
            }, MidiTarget.REGISTER, midiArmed, midiBound, onLearn)
            DetailSlider("Scan", if (scanLatched) "held by drone" else "%+.2f×".format(scan), (scan + 1f) / 2f,
                { onScan(it * 2f - 1f) }, MidiTarget.SCAN, midiArmed, midiBound, onLearn, enabled = !scanLatched)

            Spacer(Modifier.height(4.dp))
            Text("Keyboard", color = kInk, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mode, name) in listOf(KeyboardChord.Mode.OFF to "Off", KeyboardChord.Mode.LATCH to "Latch", KeyboardChord.Mode.GATE to "Gate")) {
                    val selected = mode == keysMode
                    Text(
                        name, color = if (selected) OnAccent else kInk, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .background(if (selected) kCyan else Color.Transparent, RoundedCornerShape(50))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                            .clickable { if (!selected) { haptic.tick(); onKeysMode(mode) } }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Text(
                when (keysMode) {
                    KeyboardChord.Mode.OFF -> "Keys are ignored; pitch follows key + register."
                    KeyboardChord.Mode.LATCH -> "A chord stays after you let go."
                    KeyboardChord.Mode.GATE -> "The cloud only sounds while keys are held."
                },
                color = kDim, style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chord.isEmpty()) "— follows key + register" else chord.joinToString(" ") { KeyboardChord.noteName(it) },
                    color = if (chord.isEmpty()) kDim else kAmber, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (chord.isNotEmpty()) {
                    Text("Clear", color = kInk, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable(onClick = onClearChord).padding(8.dp))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("MIDI", color = kInk, style = MaterialTheme.typography.labelLarge)
            Text(
                if (midiDevices.isEmpty()) "No controller connected. Plug a USB MIDI keyboard into the phone."
                else "◉ " + midiDevices.joinToString(", "),
                color = if (midiDevices.isEmpty()) kDim else kCyan, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Long-press a ring or a slider's name, then turn a knob. Long-press DRONE, then hit a pad.",
                color = kDim, style = MaterialTheme.typography.bodySmall,
            )
            if (midiBound.isNotEmpty()) {
                Text("Forget mappings", color = kInk, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(onClick = onForget).padding(vertical = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailSlider(
    label: String, valueText: String, position: Float, onPosition: (Float) -> Unit,
    target: MidiTarget, armed: MidiTarget?, bound: Map<MidiTarget, String>, onLearn: (MidiTarget) -> Unit,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    Column {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { haptic.thump(); onLearn(target) }),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = kInk, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (armed == target) "turn a knob…" else listOfNotNull(valueText, bound[target]).joinToString("  ·  "),
                color = if (armed == target) kAmber else kDim, style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = position.coerceIn(0f, 1f), onValueChange = onPosition, enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = kAmber, activeTrackColor = kAmber.copy(alpha = 0.7f)),
        )
    }
}
