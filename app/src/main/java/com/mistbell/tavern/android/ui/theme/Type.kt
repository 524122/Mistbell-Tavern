package com.mistbell.tavern.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography =
    Typography(
        headlineLarge =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.01).sp,
            ),
        headlineMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
        titleMedium =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
        bodyLarge =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            ),
        bodySmall =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
        labelMedium =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
    )
