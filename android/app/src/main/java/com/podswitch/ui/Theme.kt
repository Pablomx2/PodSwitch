package com.podswitch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** PodSwitch brand color palette. */
object PodSwitchColors {
    val Indigo = Color(0xFF5145E6)
    val Cyan = Color(0xFF06B6D4)

    val IndigoDark = Color(0xFF3E34C9)
    val IndigoContainer = Color(0xFFE5E2FF)
    val OnIndigoContainer = Color(0xFF160A6B)

    /** Accent used to highlight the battery-optimization tip. */
    val Amber = Color(0xFFF59E0B)
    val AmberContainer = Color(0xFFFFF3D6)
    val OnAmberContainer = Color(0xFF5C3D00)
}

/** The diagonal indigo -> cyan brand gradient, top-start to bottom-end. */
val BrandGradient: Brush
    get() = Brush.linearGradient(
        colors = listOf(PodSwitchColors.Indigo, PodSwitchColors.Cyan),
    )

private val LightColors = lightColorScheme(
    primary = PodSwitchColors.Indigo,
    onPrimary = Color.White,
    primaryContainer = PodSwitchColors.IndigoContainer,
    onPrimaryContainer = PodSwitchColors.OnIndigoContainer,
    secondary = PodSwitchColors.Cyan,
    onSecondary = Color.White,
    background = Color(0xFFF6F6FB),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEDEDF4),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFFC4C4CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEB6FF),
    onPrimary = Color(0xFF1E0F8C),
    primaryContainer = PodSwitchColors.IndigoDark,
    onPrimaryContainer = Color(0xFFE5E2FF),
    secondary = Color(0xFF66E0F0),
    onSecondary = Color(0xFF00363D),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF1C1C24),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF45454F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    outline = Color(0xFF90909A),
)

@Composable
fun PodSwitchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
