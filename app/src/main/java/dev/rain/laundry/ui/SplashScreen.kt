package dev.rain.laundry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** A single quiet image. The next cold launch selects the next image in preferences. */
@Composable
internal fun SplashScreen(
    image: String,
    durationMs: Int,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap by rememberSplashBitmap(image)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val finish by rememberUpdatedState(onFinished)
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: themeActivity(view.context)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        var active = true
        fun hideBars() {
            if (active) {
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        hideBars()
        // Apply again after attachment so the initial window flags cannot reveal bars over the image.
        view.post { hideBars() }
        onDispose {
            active = false
            previousBehavior?.let { controller?.systemBarsBehavior = it }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler { finish() }
    LaunchedEffect(lifecycle, bitmap, durationMs) {
        // Count the visible time after decoding, and never advance while the app is in the background.
        if (bitmap != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(durationMs.coerceIn(MIN_SPLASH_DURATION_MS, MAX_SPLASH_DURATION_MS).toLong())
            finish()
        }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it, when (image) {
                defaultSplashImages[0] -> "开屏图片 1"
                defaultSplashImages[1] -> "开屏图片 2"
                else -> "自定义开屏图片"
            }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
