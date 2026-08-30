package com.delrogue.grooverider.ui.source

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.delrogue.grooverider.ui.HelpButton
import com.delrogue.grooverider.ui.HelpDialog

@Composable
fun SourceScreen(modifier: Modifier = Modifier, vm: SourceViewModel = viewModel()) {
    val context = LocalContext.current
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val micLevel by vm.micLevel.collectAsStateWithLifecycle()
    val micError by vm.micError.collectAsStateWithLifecycle()
    val playhead by vm.playhead.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { vm.importAudio(it, queryName(context, it)) }
    }

    // Recording silently does nothing without RECORD_AUDIO -- ask for it
    // here rather than letting the native start fail with no feedback.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.toggleMic() }

    var showHelp by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sources", style = MaterialTheme.typography.headlineMedium)
            HelpButton(onClick = { showHelp = true })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !busy && !recording) {
                Text("Import audio")
            }
            Button(
                onClick = {
                    if (recording ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.toggleMic()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !busy,
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                         else ButtonDefaults.buttonColors(),
            ) { Text(if (recording) "Stop (${"%.0f".format(micLevel * 100)}%)" else "Record mic") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        micError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // --- selected source: waveform + trim + transport ---
        selected?.let { sel ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(sel.record.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "${sel.record.channels}ch · ${sel.record.sampleRate} Hz · " +
                            "${"%.2f".format(sel.record.durationMs / 1000f)} s",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                    WaveformView(
                        peaks = sel.peaks,
                        inFrac = if (sel.engineFrames > 0) sel.inFrame.toFloat() / sel.engineFrames else 0f,
                        outFrac = if (sel.engineFrames > 0) sel.outFrame.toFloat() / sel.engineFrames else 1f,
                        playFrac = if (sel.engineFrames > 0) playhead.toFloat() / sel.engineFrames else 0f,
                        onTrim = { i, o -> vm.setTrim((i * sel.engineFrames).toLong(), (o * sel.engineFrames).toLong()) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.play() }) { Text("Play") }
                        OutlinedButton(onClick = { vm.stop() }) { Text("Stop") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.deleteSelected() }) { Text("Delete") }
                    }
                }
            }
        }

        Text("Library", style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sources, key = { it.hash }) { s ->
                val isSel = selected?.record?.hash == s.hash
                Card(
                    colors = if (isSel) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                             else CardDefaults.cardColors(),
                    modifier = Modifier.fillMaxWidth().clickable { vm.select(s) },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(s.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                        Text("${"%.1f".format(s.durationMs / 1000f)} s · ${s.sampleRate} Hz · ${s.channels}ch",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showHelp) {
        HelpDialog(
            title = "Sources",
            body = "Import audio from your device, or record from the mic. " +
                "Tap a source to select it -- its waveform appears above with " +
                "drag handles to trim the in/out points. This trimmed region " +
                "is what the grain engine reads from on the Cloud screen.",
            onDismiss = { showHelp = false },
        )
    }
}

@Composable
private fun WaveformView(
    peaks: FloatArray,
    inFrac: Float,
    outFrac: Float,
    playFrac: Float,
    onTrim: (Float, Float) -> Unit,
) {
    var width by remember { mutableStateOf(1f) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    val x = (change.position.x / width).coerceIn(0f, 1f)
                    // drag near the closer handle
                    if (kotlin.math.abs(x - inFrac) <= kotlin.math.abs(x - outFrac)) onTrim(x, outFrac)
                    else onTrim(inFrac, x)
                }
            }
    ) {
        width = size.width
        val mid = size.height / 2f
        val n = peaks.size.coerceAtLeast(1)
        val dx = size.width / n
        // waveform
        for (i in 0 until n) {
            val h = peaks.getOrElse(i) { 0f } * mid
            val x = i * dx
            drawLine(Color(0xFF8A8376), Offset(x, mid - h), Offset(x, mid + h), 1f)
        }
        // region shading
        val inX = inFrac * size.width
        val outX = outFrac * size.width
        drawRect(Color(0x33E4A253), topLeft = Offset(inX, 0f), size = androidx.compose.ui.geometry.Size(outX - inX, size.height))
        drawLine(Color(0xFFE4A253), Offset(inX, 0f), Offset(inX, size.height), 3f)
        drawLine(Color(0xFFE4A253), Offset(outX, 0f), Offset(outX, size.height), 3f)
        // playhead
        val px = playFrac.coerceIn(0f, 1f) * size.width
        drawLine(Color(0xFF5CC3D8), Offset(px, 0f), Offset(px, size.height), 2f)
    }
}

private fun queryName(context: android.content.Context, uri: Uri): String {
    var name = "Imported audio"
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
    }
    return name
}
