package com.example.translator.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Esquema oscuro refinado con superficies escalonadas y acento azul de marca OverChat
private val DarkColorScheme = darkColorScheme(
    primary = Blue500,
    onPrimary = Color.White,
    primaryContainer = BlueDarkContainer,
    onPrimaryContainer = BlueDarkOnContainer,
    secondary = InkMutedDark,
    onSecondary = ObsidianBg,
    surface = ObsidianSurface,
    onSurface = InkLight,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = InkMutedDark,
    surfaceContainer = ObsidianSurface,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    background = ObsidianBg,
    onBackground = InkLight,
    outline = Slate700,
    outlineVariant = ObsidianBorder,
    error = ErrorDark,
    onError = ObsidianBg
)

// Esquema claro minimalista con papel neutro, bordes capilares y azul de marca OverChat
private val LightColorScheme = lightColorScheme(
    primary = Blue600,
    onPrimary = Color.White,
    primaryContainer = BlueLightContainer,
    onPrimaryContainer = BlueLightOnContainer,
    secondary = InkMutedLight,
    onSecondary = PaperSurface,
    surface = PaperSurface,
    onSurface = InkDark,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = InkMutedLight,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceHigh,
    background = PaperBg,
    onBackground = InkDark,
    outline = Slate400,
    outlineVariant = PaperBorder,
    error = ErrorLight,
    onError = PaperSurface
)

@Composable
fun TranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Por qué: Mantener la paleta de marca fija armoniza los menús con las burbujas flotantes esmeralda/obsidiana.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
