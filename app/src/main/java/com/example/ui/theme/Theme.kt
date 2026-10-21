package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    secondary = DarkSecondary,
    tertiary = DarkTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = HubSurfaceVariant,
    surfaceContainerLowest = HubSurfaceContainerLowest,
    surfaceContainerLow = HubSurfaceContainerLow,
    surfaceContainer = HubSurfaceContainer,
    surfaceContainerHigh = HubSurfaceContainerHigh,
    surfaceContainerHighest = HubSurfaceContainerHighest,
    onSurfaceVariant = HubOnSurfaceVariant,
    outlineVariant = HubOutlineVariant,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    secondary = LightSecondary,
    tertiary = LightTertiary,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface
)

private val PurpleColorScheme = darkColorScheme(
    primary = PurpleH2Primary,
    secondary = PurpleH2Secondary,
    tertiary = PurpleH2Tertiary,
    background = PurpleH2Background,
    surface = PurpleH2Surface,
    surfaceVariant = HubSurfaceVariant,
    surfaceContainerLowest = HubSurfaceContainerLowest,
    surfaceContainerLow = HubSurfaceContainerLow,
    surfaceContainer = HubSurfaceContainer,
    surfaceContainerHigh = HubSurfaceContainerHigh,
    surfaceContainerHighest = HubSurfaceContainerHighest,
    onSurfaceVariant = HubOnSurfaceVariant,
    outlineVariant = HubOutlineVariant,
    onBackground = PurpleH2OnBackground,
    onSurface = PurpleH2OnSurface
)

@Composable
fun MyApplicationTheme(
    themeMode: String = "purple",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode.lowercase()) {
        "light" -> LightColorScheme
        "dark" -> DarkColorScheme
        "purple" -> PurpleColorScheme
        else -> PurpleColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = H2HubShapes,
        content = content
    )
}
