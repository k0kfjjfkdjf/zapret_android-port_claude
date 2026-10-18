package io.github.zapretdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ByeByeDPI's palette: blue #5976DF with a darker #3C52A3, light grey surfaces.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3C52A3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF00164E),
    secondary = Color(0xFF5976DF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3FF),
    onSecondaryContainer = Color(0xFF0F1B4D),
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFFE3E4EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C4FF),
    onPrimary = Color(0xFF0E2A7A),
    primaryContainer = Color(0xFF3C52A3),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFF8FA4F5),
    onSecondary = Color(0xFF0E2A7A),
    secondaryContainer = Color(0xFF34457F),
    onSecondaryContainer = Color(0xFFDDE3FF),
    background = Color(0xFF16171C),
    surface = Color(0xFF16171C),
    surfaceVariant = Color(0xFF2A2C36),
)

@Composable
fun ZapretTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Always ByeByeDPI's colors (no wallpaper colors): buttons stand out the same on every phone.
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
