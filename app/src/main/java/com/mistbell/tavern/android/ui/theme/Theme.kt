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
import com.mistbell.tavern.android.data.repository.ThemePackRepository
import com.mistbell.tavern.android.data.theme.ParsedThemeColors
import com.mistbell.tavern.android.data.theme.resolved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

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
    tokens: ParsedThemeColors? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = (if (darkTheme) DarkColorScheme else LightColorScheme).let { base ->
        // 主题包 tokens 非空字段覆盖默认 scheme
        tokens?.let { t ->
            base.copy(
                primary = t.primary ?: base.primary,
                onPrimary = t.onPrimary ?: base.onPrimary,
                background = t.background ?: base.background,
                onBackground = t.onBackground ?: base.onBackground,
                surface = t.surface ?: base.surface,
                onSurface = t.onSurface ?: base.onSurface,
                surfaceVariant = t.surfaceVariant ?: base.surfaceVariant
            )
        } ?: base
    }

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
    val themeRepo = ThemePackRepository(context)

    // Read dark mode setting from database, combined with global theme pack tokens
    val themeState by combine(
        db.settingsDao().observeValue("dark_mode").map { it ?: "system" },
        themeRepo.observeTokensForCharacter(null)
    ) { mode, tokens -> mode to tokens }
        .collectAsState(initial = "system" to null)

    val darkModeSetting = themeState.first
    val themeTokens = themeState.second

    val isDark = when (darkModeSetting) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    MistbellTheme(darkTheme = isDark, tokens = themeTokens?.resolved(isDark), content = content)
}
