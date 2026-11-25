package dev.rain.laundry.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val LocalLaundryDarkTheme = staticCompositionLocalOf { false }
val LocalMiuixStyle = staticCompositionLocalOf { false }

@Composable
internal fun appShape(defaultRadius: Dp): Shape = RoundedCornerShape(
    defaultRadius
).let { if (LocalMiuixStyle.current) top.yukonga.miuix.kmp.utils.SmoothRoundedCornerShape(minOf(defaultRadius, 16.dp)) else it }

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
    if (settings.designStyle == DesignStyle.MIUIX) return miuixColorScheme(settings.pureBlack, dark)
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

/** The bridge uses the installed Miuix library roles; it does not emulate that toolkit. */
internal fun miuixLibraryColors(pureBlack: Boolean, dark: Boolean): top.yukonga.miuix.kmp.theme.Colors {
    if (!dark) return top.yukonga.miuix.kmp.theme.lightColorScheme()
    // Ordinary dark mode retains grey separation around cards; enhanced dark mode is pure black.
    val colors = top.yukonga.miuix.kmp.theme.darkColorScheme(background = Color(0xFF141414))
    return if (pureBlack) colors.copy(background = Color.Black,
        surface = Color.Black, surfaceVariant = Color.Black,
        surfaceContainer = Color.Black, surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
        secondary = Color.Black, secondaryVariant = Color.Black,
        disabledSecondary = Color.Black, disabledSecondaryVariant = Color.Black,
        secondaryContainer = Color.Black, secondaryContainerVariant = Color.Black,
        tertiaryContainerVariant = Color.Black,
        outline = Color.Black, dividerLine = Color.Black) else colors
}

private fun miuixColorScheme(pureBlack: Boolean, dark: Boolean): ColorScheme {
    val colors = miuixLibraryColors(pureBlack, dark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.primary, onPrimary = colors.onPrimary,
        primaryContainer = colors.tertiaryContainer, onPrimaryContainer = colors.onSurface,
        secondary = colors.primary, onSecondary = colors.onPrimary,
        secondaryContainer = colors.secondaryVariant, onSecondaryContainer = colors.onSecondaryVariant,
        tertiary = colors.primary, onTertiary = colors.onPrimary,
        tertiaryContainer = colors.tertiaryContainer, onTertiaryContainer = colors.onSurface,
        background = colors.background, onBackground = colors.onBackground,
        surface = colors.surface, onSurface = colors.onSurface,
        surfaceVariant = colors.secondaryVariant,
        onSurfaceVariant = colors.onSurfaceVariantSummary.compositeOver(colors.surface),
        surfaceDim = colors.background, surfaceBright = colors.surfaceContainerHighest,
        surfaceContainerLowest = colors.background, surfaceContainerLow = colors.surface,
        surfaceContainer = colors.surface, surfaceContainerHigh = colors.surfaceContainerHigh,
        surfaceContainerHighest = colors.surfaceContainerHighest,
        outline = colors.outline, outlineVariant = colors.dividerLine,
        inverseSurface = if (dark) Color.White else Color.Black,
        inverseOnSurface = if (dark) Color.Black else Color.White,
        inversePrimary = colors.primary, surfaceTint = colors.primary,
        scrim = Color.Black
    )
}

private fun miuixTypography(): Typography {
    val styles = top.yukonga.miuix.kmp.theme.defaultTextStyles()
    return Typography(
        displayLarge = styles.title1, displayMedium = styles.title1, displaySmall = styles.title2,
        headlineLarge = styles.title2, headlineMedium = styles.title3, headlineSmall = styles.title4,
        titleLarge = styles.headline1, titleMedium = styles.headline2, titleSmall = styles.subtitle,
        bodyLarge = styles.main, bodyMedium = styles.body1, bodySmall = styles.body2,
        labelLarge = styles.button, labelMedium = styles.footnote1, labelSmall = styles.footnote2
    )
}

@Composable
@Suppress("DEPRECATION")
fun LaundryTheme(settings: ThemeSettings = ThemeSettings(), darkSystemBars: Boolean = false,
    wallpaperBehindStatusBar: Boolean = false, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val miuix = settings.designStyle == DesignStyle.MIUIX
    val dynamic = !miuix && settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val wallpaperEnabled = settings.wallpaperBackgroundEnabled && rememberSystemWallpaperSupported()
    val wallpaperInStatusBar = wallpaperEnabled && wallpaperBehindStatusBar
    val wallpaper = if ((dynamic || wallpaperInStatusBar) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
        rememberDesktopWallpaperColors() else WallpaperColorInfo()
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
        remember(miuix, settings.palette, settings.colorStyle, settings.pureBlack, dark) {
            laundryColorScheme(settings, dark)
        }
    }
    val typography = remember(settings.font, miuix) { if (miuix) miuixTypography() else appTypography(settings.font) }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        themeActivity(view.context)?.window?.let { window ->
            // Older edge-to-edge navigation scrims were selected only once from the system theme.
            if (Build.VERSION.SDK_INT < 29) window.navigationBarColor =
                if (darkSystemBars) Color(0xFF111918).toArgb() else scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkSystemBars && if (wallpaperInStatusBar) {
                    val base = wallpaper.primary?.let { Color(it) } ?: scheme.background
                    // The crossover where black and white have equal contrast is about 0.179 luminance.
                    wallpaperBrightnessOverlay(settings.wallpaperBackgroundBrightness).compositeOver(base).luminance() > .179f
                } else !dark
                isAppearanceLightNavigationBars = !dark && !darkSystemBars
            }
        }
    }
    val shapes = if (miuix) Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp)
    ) else Shapes()
    CompositionLocalProvider(LocalLaundryDarkTheme provides dark, LocalMiuixStyle provides miuix,
        LocalWallpaperTextBacking provides wallpaperEnabled,
        LocalControlOpacity provides (1f - settings.effectiveControlTransparency(wallpaperEnabled)),
        // Native Miuix surfaces do not publish the Material foreground used by shared text and icons.
        LocalContentColor provides scheme.onBackground, LocalWallpaperColorInfo provides wallpaper) {
        if (miuix) {
            val nativeColors = remember(settings.pureBlack, dark) { miuixLibraryColors(settings.pureBlack, dark) }
            top.yukonga.miuix.kmp.theme.MiuixTheme(colors = nativeColors) {
                MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
            }
        } else MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

internal tailrec fun themeActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> if (context.baseContext !== context) themeActivity(context.baseContext) else null
    else -> null
}

