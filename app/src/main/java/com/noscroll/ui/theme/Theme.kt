package com.noscroll.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * A calm, deliberately quiet palette.
 *
 * The colour choices are part of the product argument: an app about spending less time on your phone
 * should not itself be loud. Deep teal-slate grounds, one mint accent for things that are working,
 * and a muted amber — never a punitive red — for "this needs attention". Nothing here shouts, and
 * nothing shames.
 */
private val Ink = Color(0xFF0E1417)
private val InkElevated = Color(0xFF131E24)
private val Mist = Color(0xFFF7F6F3)
private val MistElevated = Color(0xFFFFFFFF)
private val Mint = Color(0xFF2F8F76)
private val MintBright = Color(0xFF6FE3C4)
private val Slate = Color(0xFF44606C)
private val Amber = Color(0xFFB8813A)
private val AmberBright = Color(0xFFE3B36F)

private val LightColors = lightColorScheme(
    primary = Mint,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDE3),
    onPrimaryContainer = Color(0xFF06281F),
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E6EC),
    onSecondaryContainer = Color(0xFF10262F),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E4C9),
    onTertiaryContainer = Color(0xFF2E1F06),
    background = Mist,
    onBackground = Color(0xFF171C1E),
    surface = Mist,
    onSurface = Color(0xFF171C1E),
    surfaceVariant = Color(0xFFE6E5E1),
    onSurfaceVariant = Color(0xFF44484A),
    surfaceContainer = MistElevated,
    outline = Color(0xFF75797B),
    error = Color(0xFF9A4025),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = MintBright,
    onPrimary = Color(0xFF04271E),
    primaryContainer = Color(0xFF1B4A3D),
    onPrimaryContainer = Color(0xFFB9EFDE),
    secondary = Color(0xFFA9C6D2),
    onSecondary = Color(0xFF11303B),
    secondaryContainer = Color(0xFF2A4855),
    onSecondaryContainer = Color(0xFFC9E3ED),
    tertiary = AmberBright,
    onTertiary = Color(0xFF3A2A0B),
    tertiaryContainer = Color(0xFF56411C),
    onTertiaryContainer = Color(0xFFF7E1BE),
    background = Ink,
    onBackground = Color(0xFFE1E3E4),
    surface = Ink,
    onSurface = Color(0xFFE1E3E4),
    surfaceVariant = Color(0xFF3F484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
    surfaceContainer = InkElevated,
    outline = Color(0xFF899397),
    error = Color(0xFFFFB4A0),
    onError = Color(0xFF5C1A08),
)

/**
 * Typography with a real hierarchy: numbers are large and confident, supporting text is quiet.
 * Sizes are in sp throughout so the system font-size setting scales everything.
 */
private val NoScrollTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

@Composable
fun NoScrollTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    // LocalContext is read so previews and tests resolve resources against the right configuration.
    LocalContext.current

    MaterialTheme(
        colorScheme = colors,
        typography = NoScrollTypography,
        content = content,
    )
}
