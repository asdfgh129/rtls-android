package com.rtls.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = TealContainer,
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF47615B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E7E2),
    onSecondaryContainer = Color(0xFF10201C),
    tertiary = Amber,
    onTertiary = Color.White,
    background = Sand,
    onBackground = Ink,
    surface = CardLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE8F0ED),
    onSurfaceVariant = InkMuted,
    outline = LineLight,
    error = Coral,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = Color(0xFF003731),
    primaryContainer = TealContainerDark,
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFB4CCC5),
    onSecondary = Color(0xFF20332F),
    secondaryContainer = Color(0xFF2C443F),
    onSecondaryContainer = Color(0xFFD0E8E1),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF3F2A00),
    background = DarkBackground,
    onBackground = Color(0xFFE4F0EC),
    surface = DarkSurface,
    onSurface = Color(0xFFE4F0EC),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB3C7C1),
    outline = Color(0xFF3B524C),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
)


private val RtlsTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun RtlsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = RtlsTypography,
        content = content,
    )
}
