package io.github.sivasubburaj.videodownloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Neutral greys and the Windows blue accent, matching the desktop app's Sun Valley theme
private val Light = lightColorScheme(
    primary = Color(0xFF005FB8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E5F8), onPrimaryContainer = Color(0xFF00274D),
    inversePrimary = Color(0xFF60CDFF),
    secondary = Color(0xFF4F5B67), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE5EE), onSecondaryContainer = Color(0xFF1A2530),
    tertiary = Color(0xFF7C3AED), onTertiary = Color.White,
    background = Color(0xFFF3F3F3), onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFBFBFB), onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE6E6E6), onSurfaceVariant = Color(0xFF5D5D5D),
    surfaceTint = Color(0xFF005FB8),
    inverseSurface = Color(0xFF2B2B2B), inverseOnSurface = Color(0xFFF3F3F3),
    error = Color(0xFFC42B1C), onError = Color.White,
    errorContainer = Color(0xFFFDE7E9), onErrorContainer = Color(0xFF5F1412),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFE0E0E0),
    surfaceBright = Color(0xFFFBFBFB), surfaceDim = Color(0xFFE0E0E0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF3F3F3), surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE6E6E6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF60CDFF), onPrimary = Color(0xFF00243B),
    primaryContainer = Color(0xFF004A7A), onPrimaryContainer = Color(0xFFCDE7FF),
    inversePrimary = Color(0xFF005FB8),
    secondary = Color(0xFFB8C4D0), onSecondary = Color(0xFF22303B),
    secondaryContainer = Color(0xFF3A4652), onSecondaryContainer = Color(0xFFD8E2EC),
    tertiary = Color(0xFFC4B5FD), onTertiary = Color(0xFF2E1065),
    background = Color(0xFF202020), onBackground = Color(0xFFF3F3F3),
    surface = Color(0xFF2B2B2B), onSurface = Color(0xFFF3F3F3),
    surfaceVariant = Color(0xFF3D3D3D), onSurfaceVariant = Color(0xFFA3A3A3),
    surfaceTint = Color(0xFF60CDFF),
    inverseSurface = Color(0xFFF3F3F3), inverseOnSurface = Color(0xFF2B2B2B),
    error = Color(0xFFFF99A4), onError = Color(0xFF5F1412),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD9),
    outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFF3D3D3D),
    surfaceBright = Color(0xFF3A3A3A), surfaceDim = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color(0xFF1A1A1A), surfaceContainerLow = Color(0xFF262626),
    surfaceContainer = Color(0xFF2B2B2B), surfaceContainerHigh = Color(0xFF323232),
    surfaceContainerHighest = Color(0xFF3A3A3A),
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** A rounded, lightly outlined card, like the desktop app's sections. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val color = MaterialTheme.colorScheme.surface
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}
