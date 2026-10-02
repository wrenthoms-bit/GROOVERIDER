package com.delrogue.grooverider.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// One look, always dark: this is an instrument for a dim room, and the Cloud
// screen is dark whatever the phone's own theme says. Every Material component
// (buttons, sliders, switches, menus, dialogs, the tab bar) takes its colours
// from here.
private val ObservatoryColors = darkColorScheme(
    primary = Amber, onPrimary = OnAccent,
    primaryContainer = Color(0xFF3B2A12), onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Cyan, onSecondary = OnAccent,
    secondaryContainer = Color(0xFF12323A), onSecondaryContainer = Color(0xFFBFEFF5),
    tertiary = Mint, onTertiary = OnAccent,
    background = Abyss, onBackground = Ink,
    surface = Abyss, onSurface = Ink,
    surfaceVariant = Panel, onSurfaceVariant = Dim,
    surfaceContainerLowest = Abyss, surfaceContainerLow = Color(0xFF0A1013),
    surfaceContainer = Color(0xFF0C1215),            // the tab bar, menus
    surfaceContainerHigh = PanelRaised,              // dialogs
    surfaceContainerHighest = Panel,                 // cards
    outline = Color(0xFF45555C), outlineVariant = Hairline,
    error = Coral, onError = Color.White,
    errorContainer = Color(0xFF4A1512), onErrorContainer = Color(0xFFFFDAD5),
)

@Composable
fun GROOVERIDERTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ObservatoryColors, typography = Typography, content = content)
}

/** A panel: a dark card with a hairline round it, amber when it is the selected one. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    containerColor: Color = if (selected) PanelRaised else Panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, if (selected) Amber.copy(alpha = 0.7f) else Hairline),
        content = content,
    )
}
