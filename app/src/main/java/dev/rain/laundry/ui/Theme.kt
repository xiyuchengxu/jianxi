package dev.rain.laundry.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

val LocalLaundryDarkTheme = staticCompositionLocalOf { false }

fun ThemePalette.accent(): Color = Color(when (this) {
    ThemePalette.SLATE -> 0xFF326780
    ThemePalette.BLUE -> 0xFF315DA8
    ThemePalette.TEAL -> 0xFF006B64
    ThemePalette.GREEN -> 0xFF356B47
    ThemePalette.PURPLE -> 0xFF73549A
    ThemePalette.PINK -> 0xFF984467
    ThemePalette.ORANGE -> 0xFF8B522A
    ThemePalette.GRAPHITE -> 0xFF59616B
})

/** Complete light/dark roles keep cards, dialogs and text fields in the same palette. */
fun laundryColorScheme(settings: ThemeSettings, dark: Boolean): ColorScheme {
    val accent = settings.palette.accent()
    val weight = when (settings.colorStyle) {
        ColorStyle.SOFT -> .65f
        ColorStyle.STANDARD -> 1f
        ColorStyle.VIVID -> 1.4f
    }
    fun tint(base: Color, amount: Float) = lerp(base, accent, (amount * weight).coerceIn(0f, 1f))
    val secondary = lerp(accent, Color(0xFF626872), .55f)
    val tertiary = when (settings.palette) {
        ThemePalette.PINK, ThemePalette.ORANGE -> Color(0xFF596B45)
        ThemePalette.GREEN, ThemePalette.TEAL -> Color(0xFF56638F)
        else -> Color(0xFF6C5C85)
    }
    return if (dark) {
        val background = if (settings.pureBlack) Color.Black else tint(Color(0xFF121416), .035f)
        darkColorScheme(
            primary = lerp(accent, Color.White, .66f), onPrimary = tint(Color(0xFF081720), .26f),
            primaryContainer = tint(Color(0xFF202A31), .42f), onPrimaryContainer = tint(Color(0xFFF1F5FC), .09f),
            secondary = lerp(secondary, Color.White, .65f), onSecondary = Color(0xFF182129),
            secondaryContainer = tint(Color(0xFF293039), .17f), onSecondaryContainer = Color(0xFFE0E6ED),
            tertiary = lerp(tertiary, Color.White, .65f), onTertiary = Color(0xFF241D32),
            tertiaryContainer = lerp(tertiary, Color(0xFF24202E), .55f), onTertiaryContainer = Color(0xFFF0E5FF),
            background = background, onBackground = Color(0xFFE5E7EB),
            surface = background, onSurface = Color(0xFFE5E7EB),
            surfaceVariant = tint(Color(0xFF3B4046), .08f), onSurfaceVariant = Color(0xFFC1C7CF),
            surfaceDim = background, surfaceBright = tint(Color(0xFF34373C), .05f),
            surfaceContainerLowest = if (settings.pureBlack) Color.Black else Color(0xFF0D1013),
            surfaceContainerLow = tint(Color(0xFF1B1E22), .05f),
            surfaceContainer = tint(Color(0xFF22262A), .05f),
            surfaceContainerHigh = tint(Color(0xFF2A2E33), .06f),
            surfaceContainerHighest = tint(Color(0xFF33373D), .06f),
            outline = Color(0xFF8C959F), outlineVariant = tint(Color(0xFF424A53), .1f),
            inverseSurface = Color(0xFFE1E7EE), inverseOnSurface = Color(0xFF232A32), inversePrimary = accent,
            surfaceTint = lerp(accent, Color.White, .66f), scrim = Color.Black
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = Color.White,
            primaryContainer = tint(Color(0xFFEEF3FA), .075f), onPrimaryContainer = tint(Color(0xFF10212E), .14f),
            secondary = secondary, onSecondary = Color.White,
            secondaryContainer = tint(Color(0xFFEBF0F6), .12f), onSecondaryContainer = Color(0xFF24323D),
            tertiary = tertiary, onTertiary = Color.White,
            tertiaryContainer = lerp(Color(0xFFF5EFFF), tertiary, .12f), onTertiaryContainer = Color(0xFF30253F),
            background = tint(Color(0xFFFAFAFF), .012f), onBackground = Color(0xFF252D33),
            surface = tint(Color(0xFFFAFAFF), .012f), onSurface = Color(0xFF252D33),
            surfaceVariant = tint(Color(0xFFE8EDF2), .085f), onSurfaceVariant = Color(0xFF56616A),
            surfaceDim = tint(Color(0xFFDCE2E8), .06f), surfaceBright = tint(Color(0xFFFAFAFF), .012f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = tint(Color(0xFFEFF2F6), .075f),
            surfaceContainer = tint(Color(0xFFE9EEF2), .045f),
            surfaceContainerHigh = tint(Color(0xFFE3E9EF), .095f),
            surfaceContainerHighest = tint(Color(0xFFDCE4EB), .11f),
            outline = Color(0xFF74808A), outlineVariant = tint(Color(0xFFCDD5DE), .055f),
            inverseSurface = Color(0xFF2C343C), inverseOnSurface = Color(0xFFF1F3F7),
            inversePrimary = lerp(accent, Color.White, .66f), surfaceTint = accent, scrim = Color.Black
        )
    }
}

@Composable
@Suppress("DEPRECATION")
fun LaundryTheme(settings: ThemeSettings = ThemeSettings(), darkSystemBars: Boolean = false, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val wallpaper = if (dynamic) rememberDesktopWallpaperColors() else WallpaperColorInfo()
    val scheme = if (dynamic) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val system = remember(context, configuration, dark, wallpaper.revision) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        remember(wallpaper.seed, system, dark, settings.pureBlack) {
            resolveWallpaperColorScheme(wallpaper.seed, system, dark, settings.pureBlack)
        }
    } else {
        remember(settings, dark) { laundryColorScheme(settings, dark) }
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        themeActivity(view.context)?.window?.let { window ->
            // Older edge-to-edge navigation scrims were selected only once from the system theme.
            if (Build.VERSION.SDK_INT < 29) window.navigationBarColor =
                if (darkSystemBars) Color(0xFF111918).toArgb() else scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark && !darkSystemBars
                isAppearanceLightNavigationBars = !dark && !darkSystemBars
            }
        }
    }
    CompositionLocalProvider(LocalLaundryDarkTheme provides dark, LocalWallpaperColorInfo provides wallpaper) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

internal tailrec fun themeActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> if (context.baseContext !== context) themeActivity(context.baseContext) else null
    else -> null
}

