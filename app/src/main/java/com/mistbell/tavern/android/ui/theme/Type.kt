package com.mistbell.tavern.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Web frontend base: 15px = ~11.25sp at Android density
// Using 14sp as base for better mobile readability
val Typography =
    Typography(
        // Welcome title: 1.5rem → ~22sp
        headlineLarge =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.02).sp,
            ),
        // Modal title: 1.1rem → ~16.5sp
        headlineMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                letterSpacing = (-0.02).sp,
            ),
        // Header name: 0.95rem → ~14sp
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        // Section header: 0.88rem → ~13sp
        titleMedium =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        // Message text: 0.95rem → ~14sp
        bodyLarge =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            ),
        // Body/input: 0.85-0.88rem → ~13sp
        bodyMedium =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
        // Meta/hint: 0.78rem → ~11.5sp
        bodySmall =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            ),
        // Timestamp/tags: 0.7-0.72rem → ~10.5sp
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
            ),
        // Button text: 0.82rem → ~12sp
        labelMedium =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
    )
