package com.mistbell.tavern.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mistbell.tavern.android.TavernApplication
import kotlinx.coroutines.flow.MutableStateFlow

private val LightColorScheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = UserTextLight,
    primaryContainer = AccentBlueLight,
    onPrimaryContainer = AccentBlue,
    secondary = AccentGreen,
    onSecondary = UserTextLight,
    tertiary = AccentOrange,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = AccentRed,
    onError = UserTextLight,
    outline = LightBorder,
    outlineVariant = LightBorderLight
)

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = DarkBackground,
    primaryContainer = AccentBlueLightDark,
    onPrimaryContainer = AccentBlueDark,
    secondary = AccentGreenDark,
    onSecondary = UserTextDark,
    secondaryContainer = Color(0xFF1F3A2E),
    onSecondaryContainer = AccentGreenDark,
    tertiary = AccentOrangeDark,
    onTertiary = UserTextDark,
    tertiaryContainer = Color(0xFF3A2E1F),
    onTertiaryContainer = AccentOrangeDark,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = AccentRedDark,
    onError = UserTextDark,
    outline = DarkBorder,
    outlineVariant = DarkBorderLight
)

@Composable
fun MistbellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MistbellThemeWithSettings(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val db = TavernApplication.instance.database

    // Read dark mode setting from database
    val darkModeSetting by kotlinx.coroutines.flow.flow {
        emit(db.settingsDao().getValue("dark_mode") ?: "system")
    }.collectAsState(initial = "system")

    val isDark = when (darkModeSetting) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    MistbellTheme(darkTheme = isDark, content = content)
}
