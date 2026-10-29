package org.sunsetware.phocid.bridge

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF006B5B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF9EF2DD),
        onPrimaryContainer = Color(0xFF00201A),
        secondary = Color(0xFF4A635D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE8E0),
        onSecondaryContainer = Color(0xFF05201B),
        tertiary = Color(0xFF456179),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFCBE6FF),
        onTertiaryContainer = Color(0xFF001E31),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF5FBF8),
        onBackground = Color(0xFF171D1B),
        surface = Color(0xFFF5FBF8),
        onSurface = Color(0xFF171D1B),
        surfaceVariant = Color(0xFFDAE5E0),
        onSurfaceVariant = Color(0xFF3F4946),
        outline = Color(0xFF6F7976),
        outlineVariant = Color(0xFFBEC9C4),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEFF5F1),
        surfaceContainer = Color(0xFFE9EFEB),
        surfaceContainerHigh = Color(0xFFE4EAE6),
        surfaceContainerHighest = Color(0xFFDEE4E0),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF82D7C3),
        onPrimary = Color(0xFF00382E),
        primaryContainer = Color(0xFF005143),
        onPrimaryContainer = Color(0xFF9EF2DD),
        secondary = Color(0xFFB1CCC4),
        onSecondary = Color(0xFF1C352F),
        secondaryContainer = Color(0xFF324B45),
        onSecondaryContainer = Color(0xFFCCE8E0),
        tertiary = Color(0xFFADCAE5),
        onTertiary = Color(0xFF173349),
        tertiaryContainer = Color(0xFF2F4A61),
        onTertiaryContainer = Color(0xFFCBE6FF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0E1513),
        onBackground = Color(0xFFDEE4E0),
        surface = Color(0xFF0E1513),
        onSurface = Color(0xFFDEE4E0),
        surfaceVariant = Color(0xFF3F4946),
        onSurfaceVariant = Color(0xFFBEC9C4),
        outline = Color(0xFF889390),
        outlineVariant = Color(0xFF3F4946),
        surfaceContainerLowest = Color(0xFF090F0E),
        surfaceContainerLow = Color(0xFF171D1B),
        surfaceContainer = Color(0xFF1B211F),
        surfaceContainerHigh = Color(0xFF252B29),
        surfaceContainerHighest = Color(0xFF303634),
    )
