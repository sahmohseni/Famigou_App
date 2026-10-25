package com.famigou.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography

private val IranSans = FontFamily(
    Font(com.famigou.app.R.font.iransans_fanum_light, FontWeight.Light),
    Font(com.famigou.app.R.font.iransans_fanum, FontWeight.Normal),
    Font(com.famigou.app.R.font.iransans_fanum_bold, FontWeight.Bold)
)

private val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = IranSans),
        displayMedium = displayMedium.copy(fontFamily = IranSans),
        displaySmall = displaySmall.copy(fontFamily = IranSans),
        headlineLarge = headlineLarge.copy(fontFamily = IranSans),
        headlineMedium = headlineMedium.copy(fontFamily = IranSans),
        headlineSmall = headlineSmall.copy(fontFamily = IranSans),
        titleLarge = titleLarge.copy(fontFamily = IranSans),
        titleMedium = titleMedium.copy(fontFamily = IranSans),
        titleSmall = titleSmall.copy(fontFamily = IranSans),
        bodyLarge = bodyLarge.copy(fontFamily = IranSans),
        bodyMedium = bodyMedium.copy(fontFamily = IranSans),
        bodySmall = bodySmall.copy(fontFamily = IranSans),
        labelLarge = labelLarge.copy(fontFamily = IranSans),
        labelMedium = labelMedium.copy(fontFamily = IranSans),
        labelSmall = labelSmall.copy(fontFamily = IranSans)
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EBFF),
    onPrimaryContainer = Color(0xFF0D477F),
    secondary = Color(0xFF8FCBFF),
    onSecondary = Color(0xFF163A58),
    background = Color(0xFFFFFFFF),
    surface = Color.White,
    surfaceVariant = Color(0xFFF5FAFF),
    outline = Color(0xFFBFDFF7),
    outlineVariant = Color(0xFFDCEEFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCBFF),
    onPrimary = Color(0xFF06345F),
    primaryContainer = Color(0xFF155A96),
    secondary = Color(0xFF8FCBFF),
    background = Color(0xFF12202C),
    surface = Color(0xFF1C2C3A),
    surfaceVariant = Color(0xFF294154),
    outlineVariant = Color(0xFF4B6B83)
)

@Composable
fun KidoraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = AppTypography, content = content)
}
