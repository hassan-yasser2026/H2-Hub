package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import com.example.ui.theme.H2HubShapes
import com.example.ui.theme.HubOnSurfaceVariant
import com.example.ui.theme.HubOutlineVariant
import com.example.ui.theme.HubSurfaceContainer
import com.example.ui.theme.HubSurfaceContainerHigh
import com.example.ui.theme.HubSurfaceContainerHighest
import com.example.ui.theme.HubSurfaceContainerLow
import com.example.ui.theme.HubSurfaceContainerLowest
import com.example.ui.theme.HubSurfaceVariant
import com.example.ui.theme.PurpleH2Surface

fun Modifier.smartCatActionHeight(): Modifier = heightIn(min = 48.dp)

@Composable
fun SmartCatScreenStyle(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme.copy(
        surface = PurpleH2Surface,
        surfaceVariant = HubSurfaceVariant,
        surfaceContainerLowest = HubSurfaceContainerLowest,
        surfaceContainerLow = HubSurfaceContainerLow,
        surfaceContainer = HubSurfaceContainer,
        surfaceContainerHigh = HubSurfaceContainerHigh,
        surfaceContainerHighest = HubSurfaceContainerHighest,
        onSurfaceVariant = HubOnSurfaceVariant,
        outlineVariant = HubOutlineVariant
    )
    MaterialTheme(
        colorScheme = colors,
        shapes = H2HubShapes,
        typography = MaterialTheme.typography,
        content = content
    )
}
