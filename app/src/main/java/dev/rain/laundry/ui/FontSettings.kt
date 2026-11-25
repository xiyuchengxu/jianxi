package dev.rain.laundry.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.rain.laundry.R

/**
 * The bundled Yunzhuan Duren face is the upstream, unmodified TTF. It only cmaps part of
 * Unicode; Android's text stack keeps its normal system fallback for every missing glyph.
 */
private val yunzhuanDurenFontFamily: FontFamily by lazy {
    FontFamily(Font(R.font.yunzhuan_duren, weight = FontWeight.Normal))
}

internal val AppFont.fontFamily: FontFamily
    get() = when (this) {
        AppFont.SYSTEM -> FontFamily.Default
        AppFont.YUNZHUAN_DUREN -> yunzhuanDurenFontFamily
    }

/**
 * Rebuild Material 3 Typography for the selected family only. Every font size, line height,
 * weight and letter spacing is copied from the untouched default typography.
 */
internal fun appTypography(font: AppFont): Typography {
    val base = Typography()
    if (font == AppFont.SYSTEM) return base
    val family = font.fontFamily
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)
    )
}
