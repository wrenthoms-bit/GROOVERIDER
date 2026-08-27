package com.delrogue.grooverider.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private data class OnboardingPage(val title: String, val body: String)

private val pages = listOf(
    OnboardingPage(
        "Turn anything into a texture",
        "Grooverider takes a vocal chop, a stem, a field recording -- and turns it into an evolving atmosphere.",
    ),
    OnboardingPage(
        "Chaos you can keep",
        "Every texture is a Seed: reproducible, nameable, and re-playable on a different source six months from now.",
    ),
    OnboardingPage(
        "Touch the cloud",
        "Drag, pinch, rotate on the XY pad. Long-press to freeze. Double-tap for a happy accident.",
    ),
)

@Composable
fun OnboardingScreen(modifier: Modifier = Modifier, vm: OnboardingViewModel = viewModel(), onFinished: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()

    if (done) { onFinished(); return }

    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C))
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                pages[page].title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                pages[page].body,
                color = Color(0xFFB0B0B8),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            pages.indices.forEach { i ->
                Column(Modifier.padding(4.dp)) {
                    Text(if (i == page) "●" else "○", color = Color(0xFF6A6A70))
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (page < pages.lastIndex) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page = pages.lastIndex }) { Text("Skip") }
                Button(onClick = { page++ }) { Text("Next") }
            }
        } else {
            Button(
                onClick = { vm.getStarted() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), color = Color.White)
                } else {
                    Text("Get started")
                }
            }
        }
    }
}
