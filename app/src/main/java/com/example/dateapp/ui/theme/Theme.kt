package com.example.dateapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = SoftPink,
    onPrimary = WarmInk,
    background = CloudWhite,
    onBackground = WarmInk,
    surface = MistWhite,
    onSurface = WarmInk,
    surfaceVariant = SoftGray,
    onSurfaceVariant = MutedInk
)

private val DarkColors = darkColorScheme(
    primary = SoftPinkDark,
    onPrimary = WarmInkDark,
    background = DeepNightBlue,
    onBackground = CloudWhite,
    surface = DeepNightSurface,
    onSurface = CloudWhite,
    surfaceVariant = DeepNightVariant,
    onSurfaceVariant = DeepNightMuted
)

object DateAppThemeDefaults {
    val ScreenPadding = 24.dp
}

@Composable
fun DateAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
