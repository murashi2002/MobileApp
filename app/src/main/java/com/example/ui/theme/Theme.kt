package com.example.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = OrangePrimary80,
    secondary = OrangeSecondary80,
    tertiary = OrangeTertiary80,
    background = CozyOrangeBackgroundDark,
    surface = CozyOrangeSurfaceDark,
    onPrimary = CozyOrangeBackgroundDark,
    onSecondary = CozyOrangeBackgroundDark,
    onBackground = OrangeTertiary80,
    onSurface = OrangeTertiary80
)

private val LightColorScheme = lightColorScheme(
    primary = OrangePrimary40,
    secondary = OrangeSecondary40,
    tertiary = OrangeTertiary40,
    background = CozyOrangeBackgroundLight,
    surface = CozyOrangeSurfaceLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = CozyOrangeBackgroundDark,
    onSurface = CozyOrangeBackgroundDark
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
