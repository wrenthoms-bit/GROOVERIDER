package com.delrogue.grooverider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.log2
import kotlin.math.pow

private const val SOAK_TARGET_SECONDS = 600L   // M0: ten minutes, zero xRuns

@Composable
fun DebugPanelScreen(
    modifier: Modifier = Modifier,
    vm: EngineViewModel = viewModel(),
    sourceVm: com.delrogue.grooverider.ui.source.SourceViewModel = viewModel(),
) {
    val meters by vm.meters.collectAsStateWithLifecycle()
    val tone by vm.tone.collectAsStateWithLifecycle()
    val grain by vm.grain.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val masterSeed by vm.masterSeed.collectAsStateWithLifecycle()
    val seeds by vm.seeds.collectAsStateWithLifecycle()
    val seedLoadError by vm.seedLoadError.collectAsStateWithLifecycle()
    val selectedSource by sourceVm.selected.collectAsStateWithLifecycle()

    var showHelp by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var xrunsAtStart by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(meters.running) {
        if (!meters.running) {
            elapsedSeconds = 0L
            xrunsAtStart = -1L
        } else {
            if (xrunsAtStart < 0) xrunsAtStart = meters.xruns.toLong()
            while (true) {
                delay(1000)
                elapsedSeconds += 1
            }
        }
    }

    val xrunsThisRun = if (xrunsAtStart < 0) 0 else (meters.xruns - xrunsAtStart).toInt()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GROOVERIDER", style = MaterialTheme.typography.headlineMedium)
            HelpButton(onClick = { showHelp = true })
        }
        Text(
            "M0 — Skeleton & Signal Path",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        // ---- transport ----------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.startEngine() },
                enabled = !meters.running,
            ) { Text("Start engine") }
            OutlinedButton(
                onClick = { vm.stopEngine() },
                enabled = meters.running,
            ) { Text("Stop") }
        }

        // ---- soak test ----------------------------------------------------
        SoakCard(
            running = meters.running,
            elapsedSeconds = elapsedSeconds,
            xrunsThisRun = xrunsThisRun,
        )

        // ---- stream readout -----------------------------------------------
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Granted stream")
                Mono(config)
                Spacer(Modifier.height(6.dp))
                Readout("Latency", "%.2f ms".format(meters.latencyMs))
                Readout("Buffer", "${meters.bufferFrames} frames")
                Readout("Buffer widened", "${meters.bufferGrows}x after xRun")
                Readout("xRuns (total)", "${meters.xruns}")
                Readout("Callback load", "%.1f %%".format(meters.cpuLoad * 100f))
                Readout("Peak", "%.3f".format(meters.peakL))
                Readout("Active grains", "${meters.activeVoices}")
                OutlinedButton(
                    onClick = { vm.refreshConfig() },
                    enabled = meters.running,
                ) { Text("Re-read config") }
            }
        }

        // ---- tone controls -------------------------------------------------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Test tone")

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enabled")
                    Switch(
                        checked = tone.enabled,
                        onCheckedChange = { vm.setToneEnabled(it) },
                        enabled = meters.running,
                    )
                }

                // Log-scaled so the useful range is not crammed into the last inch.
                LabelledSlider(
                    label = "Frequency",
                    valueText = "%.1f Hz".format(tone.hz),
                    position = hzToPosition(tone.hz),
                    onPosition = { vm.setToneHz(positionToHz(it)) },
                    enabled = meters.running,
                )
                LabelledSlider(
                    label = "Tone gain",
                    valueText = "%.2f".format(tone.gain),
                    position = tone.gain,
                    onPosition = { vm.setToneGain(it) },
                    enabled = meters.running,
                )
                LabelledSlider(
                    label = "Master gain",
                    valueText = "%.2f".format(tone.masterGain),
                    position = tone.masterGain,
                    onPosition = { vm.setMasterGain(it) },
                    enabled = meters.running,
                )
            }
        }

        // ---- grain engine (M2) ---------------------------------------------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Grain engine")
                    OutlinedButton(onClick = { vm.recallGrainDefaults() }, enabled = meters.running) {
                        Text("Recall default")
                    }
                }
                Text(
                    "Load a source from the Sources tab first -- the grain cloud " +
                        "reads whatever is currently loaded for preview.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LabelledSlider("Density", "%.1f /s".format(grain.density),
                    logPosition(grain.density, 0.5f, 200f),
                    { vm.setGrainDensity(logValue(it, 0.5f, 200f)) }, meters.running)
                LabelledSlider("Timing jitter", "%.2f".format(grain.timingJitter),
                    grain.timingJitter, { vm.setGrainTimingJitter(it) }, meters.running)
                LabelledSlider("Grain size", "%.0f ms".format(grain.grainSizeMs),
                    logPosition(grain.grainSizeMs, 5f, 2000f),
                    { vm.setGrainSizeMs(logValue(it, 5f, 2000f)) }, meters.running)
                LabelledSlider("Size jitter", "%.2f".format(grain.sizeJitter),
                    grain.sizeJitter, { vm.setGrainSizeJitter(it) }, meters.running)
                LabelledSlider("Position", "%.2f".format(grain.position),
                    grain.position, { vm.setGrainPosition(it) }, meters.running)
                LabelledSlider("Spray", "%.0f ms".format(grain.sprayMs),
                    grain.sprayMs / 5000f, { vm.setGrainSprayMs(it * 5000f) }, meters.running)
                LabelledSlider("Drift", "%.2f".format(grain.drift),
                    (grain.drift + 2f) / 4f, { vm.setGrainDrift(it * 4f - 2f) }, meters.running)
                LabelledSlider("Pitch", "%.1f st".format(grain.pitchSt),
                    (grain.pitchSt + 24f) / 48f, { vm.setGrainPitchSt(it * 48f - 24f) }, meters.running)
                LabelledSlider("Pitch spray", "%.2f st".format(grain.pitchSpraySt),
                    grain.pitchSpraySt / 24f, { vm.setGrainPitchSpraySt(it * 24f) }, meters.running)
                LabelledSlider("Reverse prob.", "%.2f".format(grain.reverseProb),
                    grain.reverseProb, { vm.setGrainReverseProb(it) }, meters.running)
                LabelledSlider("Spread", "%.2f".format(grain.spread),
                    grain.spread, { vm.setGrainSpread(it) }, meters.running)

                WindowTypeSelector(grain.windowType, vm::setGrainWindowType, meters.running)

                Spacer(Modifier.height(4.dp))
                SectionLabel("Output stage")
                LabelledSlider("Width", "%.2f".format(grain.outputWidth),
                    grain.outputWidth / 2f, { vm.setOutputWidth(it * 2f) }, meters.running)
                LabelledSlider("Gain", "%.2f".format(grain.outputGain),
                    grain.outputGain, { vm.setOutputGain(it) }, meters.running)
            }
        }

        // ---- modulation & chaos (M4) ----------------------------------------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Modulation & chaos")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("First Light routes")
                    Switch(
                        checked = grain.chaosEnabled,
                        onCheckedChange = { vm.setChaosEnabled(it) },
                        enabled = meters.running,
                    )
                }
                LabelledSlider("Chaos rate", "%.2f".format(grain.chaosRate),
                    grain.chaosRate, { vm.setChaosRate(it) }, meters.running)
                Text(
                    "Lorenz -> position/pitchSpray/spread, Drift A/B -> " +
                        "grainSize/density. Toggle off to A/B against a static cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Seed persistence (M3) ------------------------------------------
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Seed")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mono("masterSeed = $masterSeed")
                    OutlinedButton(onClick = { vm.regenerateMasterSeed() }) { Text("Re-roll") }
                }
                Button(
                    onClick = {
                        val src = selectedSource ?: return@Button
                        val thumb = ByteArray(256) { i ->
                            val f = src.peaks.getOrElse(i * src.peaks.size / 256) { 0f }
                            (f.coerceIn(0f, 1f) * 255f).toInt().toByte()
                        }
                        vm.saveSeed(src.record.hash, thumb)
                    },
                    enabled = selectedSource != null,
                ) { Text("Save current as Seed") }
                if (selectedSource == null) {
                    Text(
                        "Select a source in the Sources tab first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (seeds.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SectionLabel("Library (${seeds.size})")
                    seedLoadError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    seeds.forEach { seed ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(seed.name, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.loadSeed(seed) }) { Text("Load") }
                                OutlinedButton(onClick = { vm.deleteSeed(seed) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Sweep the frequency slider hard while listening. Any zipper noise means " +
                "parameter smoothing is not doing its job (spec 2.8).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showHelp) {
        HelpDialog(
            title = "Engine",
            body = "This is the engine's raw control panel. Hit Start engine, " +
                "then pick a source from the Sources tab. The Grain engine " +
                "sliders shape the sound -- Recall default resets them to a " +
                "neutral starting point. Most performance happens on the " +
                "Cloud tab; this screen is for fine control and diagnostics.",
            onDismiss = { showHelp = false },
        )
    }
}

@Composable
private fun WindowTypeSelector(selected: Int, onSelect: (Int) -> Unit, enabled: Boolean) {
    Column {
        Text("Window", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Gaussian", "Tukey", "Hann").forEachIndexed { index, label ->
                if (selected == index) {
                    Button(onClick = { onSelect(index) }, enabled = enabled) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onSelect(index) }, enabled = enabled) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun SoakCard(running: Boolean, elapsedSeconds: Long, xrunsThisRun: Int) {
    val progress = (elapsedSeconds.toFloat() / SOAK_TARGET_SECONDS).coerceIn(0f, 1f)
    val passed = elapsedSeconds >= SOAK_TARGET_SECONDS && xrunsThisRun == 0
    val failed = xrunsThisRun > 0

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                passed -> MaterialTheme.colorScheme.primaryContainer
                failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("M0 acceptance — 10 minutes, zero xRuns")
            Text(
                when {
                    !running -> "Not running"
                    passed -> "PASSED — ${formatDuration(elapsedSeconds)}, no xRuns"
                    failed -> "FAILED — $xrunsThisRun xRun(s) at ${formatDuration(elapsedSeconds)}"
                    else -> "${formatDuration(elapsedSeconds)} / 10:00 clean"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    valueText: String,
    position: Float,
    onPosition: (Float) -> Unit,
    enabled: Boolean,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Mono(valueText)
        }
        Slider(
            value = position,
            onValueChange = onPosition,
            enabled = enabled,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun Readout(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Mono(value)
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

// 20 Hz .. 20 kHz mapped logarithmically onto 0 .. 1
private fun hzToPosition(hz: Float): Float {
    val clamped = hz.coerceIn(20f, 20000f)
    return ((log2(clamped) - log2(20f)) / (log2(20000f) - log2(20f))).coerceIn(0f, 1f)
}

private fun positionToHz(position: Float): Float {
    val span = log2(20000f) - log2(20f)
    return 2f.pow(log2(20f) + position.coerceIn(0f, 1f) * span)
}

// Generic log mapping for wide-range grain params (density, grainSize).
private fun logPosition(value: Float, min: Float, max: Float): Float =
    ((log2(value.coerceIn(min, max)) - log2(min)) / (log2(max) - log2(min))).coerceIn(0f, 1f)

private fun logValue(position: Float, min: Float, max: Float): Float =
    2f.pow(log2(min) + position.coerceIn(0f, 1f) * (log2(max) - log2(min)))

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
