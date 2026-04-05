package com.sumitgouthaman.busdash.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BusDashWearColorScheme = ColorScheme(
    primary = TransitBlue,
    onPrimary = TransitBackground,
    primaryContainer = TransitBlueDark,
    onPrimaryContainer = TransitOnSurface,
    secondary = TransitTeal,
    onSecondary = TransitBackground,
    secondaryContainer = TransitTealDark,
    onSecondaryContainer = TransitOnSurface,
    tertiary = TransitAmber,
    onTertiary = TransitBackground,
    tertiaryContainer = TransitTealDark,
    onTertiaryContainer = TransitOnSurface,
    error = TransitError,
    onError = TransitBackground,
    background = TransitBackground,
    onBackground = TransitOnSurface,
    surfaceContainerLow = TransitSurface,
    surfaceContainer = TransitSurfaceCard,
    surfaceContainerHigh = TransitSurfaceCard,
    onSurface = TransitOnSurface,
    onSurfaceVariant = TransitOnSurfaceDim,
)

private val BusDashWearTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun BusDashWearTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BusDashWearColorScheme,
        typography = BusDashWearTypography,
        content = content
    )
}
