package com.delrogue.grooverider.ui.cloud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.delrogue.grooverider.ui.CaptureState
import kotlin.math.abs

/**
 * Capture review (spec 6.1): waveform of the last take, trim handles,
 * keep/discard. "Preview" is the trimmed-region playback a real build would
 * wire to the engine's preview player; this pass shows the trimmed waveform
 * live, which is the part of the review loop that matters for deciding
 * keep vs. discard.
 */
@Composable
fun CaptureReviewSheet(
    capture: CaptureState,
    rendering: Boolean,
    onTrimChange: (Float, Float) -> Unit,
    onDiscard: () -> Unit,
    onKeep: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .background(Color(0xFF1A1A1E)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Capture -- last 60 s",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp),
            )

            CaptureWaveform(
                pcm = capture.pcm,
                trimStart = capture.trimStart,
                trimEnd = capture.trimEnd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(horizontal = 16.dp),
            )

            RangeSlider(
                value = capture.trimStart..capture.trimEnd,
                onValueChange = { onTrimChange(it.start, it.endInclusive) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDiscard, enabled = !rendering) { Text("Discard") }
                Button(onClick = onKeep, enabled = !rendering) {
                    if (rendering) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), color = Color.White)
                    } else {
                        Text("Keep -- render HQ & share")
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureWaveform(pcm: ShortArray, trimStart: Float, trimEnd: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF0E0E11))) {
        val frames = pcm.size / 2
        if (frames == 0) return@Canvas
        val buckets = 300
        val framesPerBucket = maxOf(1, frames / buckets)
        val w = size.width
        val h = size.height
        val barW = w / buckets

        for (b in 0 until buckets) {
            val start = b * framesPerBucket
            if (start >= frames) break
            val end = minOf(frames, start + framesPerBucket)
            var peak = 0
            for (i in start until end) {
                val l = abs(pcm[i * 2].toInt())
                val r = abs(pcm[i * 2 + 1].toInt())
                peak = maxOf(peak, l, r)
            }
            val amp = (peak / 32768f) * h
            val inTrim = b.toFloat() / buckets in trimStart..trimEnd
            drawRect(
                color = if (inTrim) Color(0xFF7FE0C8) else Color(0xFF4A4A50),
                topLeft = Offset(b * barW, (h - amp) / 2f),
                size = Size(barW * 0.8f, amp),
            )
        }
    }
}
