package dev.rain.laundry.ui

import android.app.WallpaperManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
internal fun rememberSystemWallpaperSupported(): Boolean {
    val view = LocalView.current
    if (view.isInEditMode) return false
    val context = view.context.applicationContext
    return remember(context) {
        runCatching { context.getSystemService(WallpaperManager::class.java)?.isWallpaperSupported == true }
            .getOrDefault(false)
    }
}

/** Lets the window compositor show the system wallpaper through transparent page regions. */
@Composable
internal fun useSystemWallpaperWindow(enabled: Boolean): Boolean {
    val view = LocalView.current
    if (!enabled || !rememberSystemWallpaperSupported()) return false
    val activity = remember(view) { themeActivity(view.context) } ?: return false
    if (activity.isFinishing || activity.isDestroyed) return false
    val window = activity.window ?: return false
    DisposableEffect(window) {
        val originalBackground = window.decorView.background
        val originalFormat = window.attributes.format
        val originalFlags = window.attributes.flags and FLAG_SHOW_WALLPAPER
        val originalTouch = if (Build.VERSION.SDK_INT >= 34) window.attributes.areWallpaperTouchEventsEnabled() else false

        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Apply the touch policy before making a live wallpaper visible behind this activity.
        if (Build.VERSION.SDK_INT >= 34) {
            window.attributes = window.attributes.apply { setWallpaperTouchEventsEnabled(false) }
        }
        window.addFlags(FLAG_SHOW_WALLPAPER)

        onDispose {
            window.setFlags(originalFlags, FLAG_SHOW_WALLPAPER)
            if (Build.VERSION.SDK_INT >= 34) {
                window.attributes = window.attributes.apply { setWallpaperTouchEventsEnabled(originalTouch) }
            }
            window.setBackgroundDrawable(originalBackground)
            window.setFormat(originalFormat)
        }
    }
    return true
}
