package dev.queuepilot.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The QueuePilot look for screens written in Compose for TV. Every color comes from [TvTheme],
 * so a Compose screen and a View screen draw the same tokens; this file holds no color literal
 * of its own (`tools/theme_check.py` enforces that). The first screen moved to Compose adds
 * Outfit and the type ramp here.
 */
@Composable
fun QueuePilotTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = queuePilotColors, content = content)
}

/**
 * Charcuterie's tokens mapped onto every TV Material color role, so no role falls back to
 * Material's own palette. Tertiary roles use the neutral intent, error roles the danger intent,
 * and the inverse roles swap the page and its text.
 */
private val queuePilotColors =
    darkColorScheme(
        primary = Color(TvTheme.ACCENT_SOLID),
        onPrimary = Color(TvTheme.ACCENT_ON_SOLID),
        primaryContainer = Color(TvTheme.ACCENT_SURFACE),
        onPrimaryContainer = Color(TvTheme.ACCENT_CONTENT),
        inversePrimary = Color(TvTheme.ACCENT_BORDER),
        secondary = Color(TvTheme.ACCENT_CONTENT),
        onSecondary = Color(TvTheme.ACCENT_ON_SOLID),
        secondaryContainer = Color(TvTheme.NEUTRAL_SURFACE),
        onSecondaryContainer = Color(TvTheme.CONTENT_PRIMARY),
        tertiary = Color(TvTheme.CONTENT_SECONDARY),
        onTertiary = Color(TvTheme.SURFACE_BASE),
        tertiaryContainer = Color(TvTheme.NEUTRAL_SURFACE),
        onTertiaryContainer = Color(TvTheme.CONTENT_PRIMARY),
        background = Color(TvTheme.SURFACE_BASE),
        onBackground = Color(TvTheme.CONTENT_PRIMARY),
        surface = Color(TvTheme.SURFACE_RAISED),
        onSurface = Color(TvTheme.CONTENT_PRIMARY),
        surfaceVariant = Color(TvTheme.SURFACE_OVERLAY),
        onSurfaceVariant = Color(TvTheme.CONTENT_SECONDARY),
        surfaceTint = Color(TvTheme.SURFACE_OVERLAY),
        inverseSurface = Color(TvTheme.CONTENT_PRIMARY),
        inverseOnSurface = Color(TvTheme.SURFACE_BASE),
        error = Color(TvTheme.DANGER_SOLID),
        onError = Color(TvTheme.DANGER_ON_SOLID),
        errorContainer = Color(TvTheme.DANGER_SURFACE),
        onErrorContainer = Color(TvTheme.DANGER_CONTENT),
        border = Color(TvTheme.FOCUS_RING),
        borderVariant = Color(TvTheme.BORDER_DEFAULT),
        scrim = Color(TvTheme.SCRIM)
    )
