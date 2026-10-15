package com.nardech.vdoplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nardech.vdoplayer.data.ThemeMode

private val BrandBlue = Color(0xFF5B8CFF)
private val BrandBlueDark = Color(0xFF3459C6)
private val AccentAmber = Color(0xFFFFB84D)
private val AccentTeal = Color(0xFF4FD1C5)

private val LightScheme = lightColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF11224F),
    secondary = Color(0xFFB8791F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6C2),
    onSecondaryContainer = Color(0xFF3A2500),
    tertiary = Color(0xFF12766C),
    onTertiary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF15161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15161C),
    surfaceVariant = Color(0xFFE4E6EF),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF757780),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF0B1F4D),
    primaryContainer = Color(0xFF24356E),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = AccentAmber,
    onSecondary = Color(0xFF3A2500),
    secondaryContainer = Color(0xFF57390A),
    onSecondaryContainer = Color(0xFFFFE6C2),
    tertiary = AccentTeal,
    onTertiary = Color(0xFF00312D),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE7E8EE),
    surface = Color(0xFF16181F),
    onSurface = Color(0xFFE7E8EE),
    surfaceVariant = Color(0xFF2A2D36),
    onSurfaceVariant = Color(0xFFC3C6D1),
    outline = Color(0xFF8D9099),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun VdoPlayerTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkScheme else LightScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content,
    )
}
