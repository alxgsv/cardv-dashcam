package com.cardv.dashcam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The dashcam's orange shutter button, warmed up for a night-drive palette.
private val Ember = Color(0xFFFF6B3D)
private val EmberSoft = Color(0xFFFFD8C8)
private val Ink = Color(0xFF0C0D0F)
private val InkSurface = Color(0xFF15171B)
private val InkRaised = Color(0xFF1E2126)
private val Bone = Color(0xFFF6F1E9)
private val Sand = Color(0xFFB7ADA1)

private val DarkColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF200E06),
    primaryContainer = Color(0xFF51200F),
    onPrimaryContainer = EmberSoft,
    secondary = Color(0xFFE9C8A6),
    onSecondary = Color(0xFF2B1A0D),
    secondaryContainer = Color(0xFF3A2A1D),
    onSecondaryContainer = Color(0xFFF6DFC8),
    tertiary = Color(0xFF9FC6D8),
    onTertiary = Color(0xFF06202A),
    background = Ink,
    onBackground = Bone,
    surface = InkSurface,
    onSurface = Bone,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Sand,
    outline = Color(0xFF3A3D44),
    outlineVariant = Color(0xFF26292F),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF3A0905),
    errorContainer = Color(0xFF5C1A12),
    onErrorContainer = Color(0xFFFFDAD4),
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD94A1B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF745B43),
    onSecondary = Color.White,
    background = Color(0xFFF7F2E9),
    onBackground = Color(0xFF231F1A),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF231F1A),
    surfaceVariant = Color(0xFFEDE4D7),
    onSurfaceVariant = Color(0xFF6E6357),
    outline = Color(0xFFD4C7B7),
    outlineVariant = Color(0xFFE8DED0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0x99000000),
)

private val CarDVTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.8).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

private val CarDVShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

@Composable
fun CarDVTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CarDVTypography,
        shapes = CarDVShapes,
        content = content,
    )
}
