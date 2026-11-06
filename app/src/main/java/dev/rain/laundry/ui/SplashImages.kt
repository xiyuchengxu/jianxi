package dev.rain.laundry.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import dev.rain.laundry.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private const val SPLASH_IMAGE_DIRECTORY = "splash_images"
private const val MAX_IMPORT_BYTES = 50L * 1024 * 1024
private val stagingImageName = Regex("import_\\d+\\.tmp")

// A successful import can be awaiting its caller on the main thread while cold-start cleanup runs.
// Protect these names until the preference update is safely on disk and startup cleanup has finished.
private object PendingSplashImports {
    val lock = Any()
    val names = mutableSetOf<String>()
}

/** Copies selected images into app-private storage; source images and their metadata are untouched. */
internal class SplashImageStore(context: Context) {
    private val context = context.applicationContext

    suspend fun importImages(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        require(uris.size in 1..MAX_SPLASH_IMAGES) { "请选择 1 至 $MAX_SPLASH_IMAGES 张图片" }
        val directory = File(context.filesDir, SPLASH_IMAGE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建开屏图片目录")
        }
        val created = mutableListOf<File>()
        var completed = false
        try {
            val imported = uris.map { uri ->
                currentCoroutineContext().ensureActive()
                val staging = synchronized(PendingSplashImports.lock) {
                    File.createTempFile("import_", ".tmp", directory).also { PendingSplashImports.names += it.name }
                }
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选图片")
                    input.use { source ->
                        staging.outputStream().use { target ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = source.read(buffer)
                                if (read < 0) break
                                count += read
                                if (count > MAX_IMPORT_BYTES) throw IOException("单张图片不能超过 50 MB")
                                target.write(buffer, 0, read)
                            }
                        }
                    }
                    val bitmap = decodeSplashImage({ staging.inputStream() }, readOrientation(staging))
                        ?: throw IOException("所选文件不是可读取的图片")
                    try {
                        currentCoroutineContext().ensureActive()
                        val extension = if (bitmap.hasAlpha()) "png" else "jpg"
                        val destination = File(directory, "splash_${UUID.randomUUID().toString().replace("-", "")}.$extension")
                        created += destination
                        synchronized(PendingSplashImports.lock) { PendingSplashImports.names += destination.name }
                        destination.outputStream().use { output ->
                            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                            if (!bitmap.compress(format, 92, output)) throw IOException("无法保存开屏图片")
                        }
                        destination.name
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    synchronized(PendingSplashImports.lock) {
                        staging.delete()
                        PendingSplashImports.names -= staging.name
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            completed = true
            imported
        } finally {
            if (!completed) synchronized(PendingSplashImports.lock) {
                created.forEach {
                    it.delete()
                    PendingSplashImports.names -= it.name
                }
            }
        }
    }

    /** Run at cold startup. Pending imports are excluded, even when they have not reached the UI yet. */
    suspend fun pruneUnused(retained: List<String>): Unit = withContext(Dispatchers.IO) {
        val retainedNames = normalizeSplashImageNames(retained).toSet()
        val directory = File(context.filesDir, SPLASH_IMAGE_DIRECTORY)
        directory.listFiles()?.forEach { file ->
            val generated = normalizeSplashImageNames(listOf(file.name)).isNotEmpty() || stagingImageName.matches(file.name)
            if (generated && file.name !in retainedNames) {
                synchronized(PendingSplashImports.lock) {
                    if (file.name !in PendingSplashImports.names) file.delete()
                }
            }
        }
    }

    /** Only delete references retired by this update, so concurrently imported files stay untouched. */
    internal suspend fun discardPrevious(previous: List<String>, retained: List<String>): Unit = withContext(Dispatchers.IO) {
        val retired = normalizeSplashImageNames(previous).toSet() - normalizeSplashImageNames(retained).toSet()
        val directory = File(context.filesDir, SPLASH_IMAGE_DIRECTORY)
        synchronized(PendingSplashImports.lock) {
            retired.forEach { name ->
                File(directory, name).delete()
                PendingSplashImports.names -= name
            }
            PendingSplashImports.names.removeAll(normalizeSplashImageNames(retained).toSet())
        }
    }
}

/** Both default resources and private custom images are decoded off the main thread with an edge bound. */
@Composable
internal fun rememberSplashBitmap(source: String): State<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    val state = remember(context, source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(context, source) {
        state.value = withContext(Dispatchers.IO) {
            val bitmap = loadSplashImage(context, source) ?: loadSplashImage(context, defaultSplashImages.first())
            bitmap?.asImageBitmap()
        }
    }
    return state
}

private fun loadSplashImage(context: Context, source: String): Bitmap? = try {
    val resource = when (source) {
        defaultSplashImages[0] -> R.drawable.splash_mascot_1
        defaultSplashImages[1] -> R.drawable.splash_mascot_2
        else -> null
    }
    if (resource != null) {
        decodeSplashImage({ context.resources.openRawResource(resource) })
    } else if (normalizeSplashImageNames(listOf(source)).isNotEmpty()) {
        val file = File(File(context.filesDir, SPLASH_IMAGE_DIRECTORY), source)
        decodeSplashImage({ file.inputStream() }, readOrientation(file))
    } else null
} catch (_: Exception) {
    null
}

private fun readOrientation(file: File): Int = try {
    ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun decodeSplashImage(open: () -> InputStream, orientation: Int = ExifInterface.ORIENTATION_NORMAL): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = splashSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    var bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    try {
        val (width, height) = splashImageDimensions(bitmap.width, bitmap.height)
        if (width != bitmap.width || height != bitmap.height) {
            val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (resized !== bitmap) bitmap.recycle()
            bitmap = resized
        }
        val transform = splashImageOrientation(orientation)
        if (transform.degrees != 0f || transform.mirror) {
            val matrix = Matrix().apply {
                setRotate(transform.degrees)
                if (transform.mirror) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
}
