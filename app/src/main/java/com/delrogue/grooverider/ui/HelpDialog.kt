package com.delrogue.grooverider.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A "?" glyph, styled to match this app's other icon-as-text affordances
 * (✕, ◂, ⏺, 🔒) rather than pulling in Material icons.
 */
@Composable
fun HelpButton(onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color(0xFF9AA0A6)) {
    Text(
        "?",
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** Plain-language, per-screen instructions -- dismissible, never auto-shown. */
@Composable
fun HelpDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text(title) },
        // long help scrolls, so a sideways phone can still reach the end of it
        text = { Text(body, Modifier.verticalScroll(rememberScrollState())) },
    )
}
