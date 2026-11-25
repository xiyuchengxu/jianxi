package dev.rain.laundry.ui

import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class WallpaperColorInfo(
    val seed: Int? = null,
    val primary: Int? = null,
    val loading: Boolean = false,
    val revision: Int = 0,
    val refresh: () -> Unit = {}
)

internal val LocalWallpaperColorInfo = staticCompositionLocalOf { WallpaperColorInfo() }

/** Only reads wallpaper color metadata. Does not access the wallpaper bitmap or request storage access. */
@RequiresApi(Build.VERSION_CODES.O_MR1)
@Composable
internal fun rememberDesktopWallpaperColors(): WallpaperColorInfo {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember(context) { runCatching { WallpaperManager.getInstance(context) }.getOrNull() }
    var revision by remember { mutableIntStateOf(0) }
    var colors by remember { mutableStateOf<android.app.WallpaperColors?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(manager, configuration, revision) {
        loading = true
        suspend fun readColors() = withContext(Dispatchers.IO) {
            try {
                manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            } catch (_: RuntimeException) { null }
        }
        // Color metadata can briefly be unavailable while a new wallpaper is being processed.
        colors = readColors() ?: run { delay(350); readColors() }
        loading = false
    }

    DisposableEffect(manager, lifecycleOwner) {
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) revision++
        }
        val registered = manager != null && runCatching {
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            true
        }.getOrDefault(false)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) runCatching { manager?.removeOnColorsChangedListener(listener) }
        }
    }
    val seed = remember(colors) {
        colors?.let { selectWallpaperSeed(listOfNotNull(it.primaryColor, it.secondaryColor, it.tertiaryColor).map { color -> color.toArgb() }) }
    }
    return WallpaperColorInfo(seed = seed, primary = colors?.primaryColor?.toArgb(),
        loading = loading, revision = revision, refresh = { revision++ })
}
