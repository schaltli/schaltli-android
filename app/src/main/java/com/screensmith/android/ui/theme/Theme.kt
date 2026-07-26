package com.screensmith.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = ScreensmithBlue,
    background = ScreensmithBackground,
    surface = ScreensmithSurface,
)

private val DarkColors = darkColorScheme(
    primary = ScreensmithBlueDark,
    background = ScreensmithBackgroundDark,
    surface = ScreensmithSurfaceDark,
)

@Composable
fun ScreensmithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color (Material You) is opt-out by default: this app's job is
    // to reproduce a screen design pixel-for-pixel-ish, not adapt it to the
    // user's wallpaper - the app chrome around the rendered screen can still
    // look native, but the rendered screen itself shouldn't be re-tinted.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
