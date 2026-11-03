package dev.rain.laundry.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.rain.laundry.vendor.material.hct.Hct
import dev.rain.laundry.vendor.material.palettes.TonalPalette

/** Prefer a visible chromatic wallpaper color over a black/grey background sample. */
internal fun selectWallpaperSeed(colors: List<Int>): Int? {
    if (colors.isEmpty()) return null
    val chromatic = colors.mapIndexed { index, argb ->
        val hct = Hct.fromInt(argb)
        Triple(argb, hct, hct.chroma * when (index) { 0 -> 1.0; 1 -> .85; else -> .7 })
    }.filter { (_, hct, _) -> hct.chroma >= 8.0 && hct.tone in 8.0..95.0 }
    return (chromatic.maxByOrNull { it.third }?.first ?: colors.first()) or (0xFF shl 24)
}

/** Material tonal roles generated from the wallpaper seed, independent of the OS accent color. */
internal fun wallpaperSeedColorScheme(seed: Int, dark: Boolean): ColorScheme {
    val source = Hct.fromInt(seed)
    val neutral = source.chroma < 8.0
    val primary = TonalPalette.fromHueAndChroma(source.hue, if (neutral) 0.0 else 36.0)
    val secondary = TonalPalette.fromHueAndChroma(source.hue, if (neutral) 0.0 else 16.0)
    val tertiary = TonalPalette.fromHueAndChroma((source.hue + 60.0) % 360.0, if (neutral) 0.0 else 24.0)
    val surface = TonalPalette.fromHueAndChroma(source.hue, if (neutral) 0.0 else 6.0)
    val variant = TonalPalette.fromHueAndChroma(source.hue, if (neutral) 0.0 else 8.0)
    val error = TonalPalette.fromHueAndChroma(25.0, 84.0)
    fun TonalPalette.at(tone: Int) = Color(this.tone(tone))
    return if (dark) darkColorScheme(
        primary = primary.at(80), onPrimary = primary.at(20),
        primaryContainer = primary.at(30), onPrimaryContainer = primary.at(90),
        secondary = secondary.at(80), onSecondary = secondary.at(20),
        secondaryContainer = secondary.at(30), onSecondaryContainer = secondary.at(90),
        tertiary = tertiary.at(80), onTertiary = tertiary.at(20),
        tertiaryContainer = tertiary.at(30), onTertiaryContainer = tertiary.at(90),
        error = error.at(80), onError = error.at(20), errorContainer = error.at(30), onErrorContainer = error.at(90),
        background = surface.at(6), onBackground = surface.at(90), surface = surface.at(6), onSurface = surface.at(90),
        surfaceVariant = variant.at(30), onSurfaceVariant = variant.at(80),
        surfaceDim = surface.at(6), surfaceBright = surface.at(24),
        surfaceContainerLowest = surface.at(4), surfaceContainerLow = surface.at(10),
        surfaceContainer = surface.at(12), surfaceContainerHigh = surface.at(17), surfaceContainerHighest = surface.at(22),
        outline = variant.at(60), outlineVariant = variant.at(30),
        inverseSurface = surface.at(90), inverseOnSurface = surface.at(20), inversePrimary = primary.at(40),
        surfaceTint = primary.at(80), scrim = Color.Black
    ) else lightColorScheme(
        primary = primary.at(40), onPrimary = primary.at(100),
        primaryContainer = primary.at(90), onPrimaryContainer = primary.at(10),
        secondary = secondary.at(40), onSecondary = secondary.at(100),
        secondaryContainer = secondary.at(90), onSecondaryContainer = secondary.at(10),
        tertiary = tertiary.at(40), onTertiary = tertiary.at(100),
        tertiaryContainer = tertiary.at(90), onTertiaryContainer = tertiary.at(10),
        error = error.at(40), onError = error.at(100), errorContainer = error.at(90), onErrorContainer = error.at(10),
        background = surface.at(98), onBackground = surface.at(10), surface = surface.at(98), onSurface = surface.at(10),
        surfaceVariant = variant.at(90), onSurfaceVariant = variant.at(30),
        surfaceDim = surface.at(87), surfaceBright = surface.at(98),
        surfaceContainerLowest = surface.at(100), surfaceContainerLow = surface.at(96),
        surfaceContainer = surface.at(94), surfaceContainerHigh = surface.at(92), surfaceContainerHighest = surface.at(90),
        outline = variant.at(50), outlineVariant = variant.at(80),
        inverseSurface = surface.at(20), inverseOnSurface = surface.at(95), inversePrimary = primary.at(80),
        surfaceTint = primary.at(40), scrim = Color.Black
    )
}

internal fun resolveWallpaperColorScheme(seed: Int?, system: ColorScheme, dark: Boolean, pureBlack: Boolean): ColorScheme {
    val scheme = if (seed != null) wallpaperSeedColorScheme(seed, dark) else system
    return if (dark && pureBlack) scheme.copy(background = Color.Black, surface = Color.Black,
        surfaceDim = Color.Black, surfaceContainerLowest = Color.Black) else scheme
}
