@file:Suppress("MagicNumber")

package com.mistbell.tavern.android.ui.theme

import androidx.compose.ui.graphics.Color

// ==================== Mistbell Tavern Design System ====================
// A quiet ink background and warm lantern accent keep the product atmospheric
// without turning every screen into a themed illustration.
val LightBackground = Color(0xFFF7F4EE)
val LightSurface = Color(0xFFFFFCF7)
val LightSurfaceVariant = Color(0xFFEFEAE1)
val LightOnBackground = Color(0xFF26231F)
val LightOnSurface = Color(0xFF26231F)
val LightOnSurfaceVariant = Color(0xFF706A60)
val LightTertiary = Color(0xFF968C7D)
val LightBorder = Color(0xFFDCD4C8)
val LightBorderLight = Color(0xFFEAE3D8)
val LightHover = Color(0xFFF3EEE6)
val LightActive = Color(0xFFE9E1D5)
val LightInput = Color(0xFFF1ECE4)

// Lantern amber is reserved for primary actions and important state.
val AccentBlue = Color(0xFFB97832)
val AccentBlueHover = Color(0xFF965C22)
val AccentBlueLight = Color(0xFFF3E2C7)

// Semantic colors
val AccentGreen = Color(0xFF4D8064)
val AccentRed = Color(0xFFB6534A)
val AccentRedLight = Color(0x1AB6534A)
val AccentOrange = Color(0xFFC1843B)

// Message bubbles (Light)
val UserBubbleLight = AccentBlue
val UserBubbleGradientEnd = AccentBlueHover
val UserTextLight = Color(0xFFFFFFFF)
val AiBubbleLight = Color(0xFFFFFFFF)
val AiTextLight = Color(0xFF1F2937)

// ==================== Dark Theme ====================
val DarkBackground = Color(0xFF171614)
val DarkSurface = Color(0xFF211F1B)
val DarkSurfaceVariant = Color(0xFF2A2721)
val DarkOnBackground = Color(0xFFF3EEE5)
val DarkOnSurface = Color(0xFFF3EEE5)
val DarkOnSurfaceVariant = Color(0xFFB9B0A3)
val DarkTertiary = Color(0xFF8F8577)
val DarkBorder = Color(0xFF443E35)
val DarkBorderLight = Color(0xFF342F29)
val DarkHover = Color(0xFF302B24)
val DarkActive = Color(0xFF3B342B)
val DarkInput = Color(0xFF24211D)

// Accent (Dark)
val AccentBlueDark = Color(0xFFE2A35B)
val AccentBlueHoverDark = Color(0xFFF0B873)
val AccentBlueLightDark = Color(0xFF4C331E)

// Semantic (Dark)
val AccentGreenDark = Color(0xFF82B094)
val AccentRedDark = Color(0xFFE18478)
val AccentRedLightDark = Color(0x26E18478)
val AccentOrangeDark = Color(0xFFE0A55C)

// Message bubbles (Dark)
val UserBubbleDark = AccentBlueDark
val UserBubbleGradientEndDark = AccentBlueHoverDark
val UserTextDark = Color(0xFFFFFFFF)
val AiBubbleDark = Color(0xFF374151)
val AiTextDark = Color(0xFFF9FAFB)

// Character colors (shared palette)
val CharColors =
    listOf(
        Color(0xFF6C5CE7), Color(0xFF00B894), Color(0xFFE17055), Color(0xFFA29BFE),
        Color(0xFF0984E3), Color(0xFFFD79A8), Color(0xFF00CEC9), Color(0xFFE84393),
        Color(0xFF55EFC4), Color(0xFFFDCB6E), Color(0xFF74B9FF), Color(0xFFFAB1A0),
        Color(0xFF636E72), Color(0xFF2D3436), Color(0xFFD63031), Color(0xFFE17D60),
    )
