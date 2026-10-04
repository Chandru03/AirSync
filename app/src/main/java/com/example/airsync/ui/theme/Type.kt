package com.example.airsync.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

/** M3 type scale with slightly firmer weights for headings, Pixel-style. */
internal val AppTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Normal),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Normal),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Normal),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Normal),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Normal),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium)
)
