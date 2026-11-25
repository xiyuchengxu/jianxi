package dev.rain.laundry.diagnostics

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/** Local, bounded diagnostics. This API deliberately cannot accept any request or exception text. */
object DiagnosticsLog {
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jianxi-local-diagnostics").apply { isDaemon = true }
    }
    private val mutableEntries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    private val mutableStorageError = MutableStateFlow(false)
    private var file: AtomicFile? = null
    private var generation = 0L
    val entries = mutableEntries.asStateFlow()
    val storageError = mutableStorageError.asStateFlow()
    const val MAX_ENTRIES = DiagnosticCodec.MAX_ENTRIES

    fun initialize(context: Context) = synchronized(lock) {
        if (file != null) return@synchronized
        val target = AtomicFile(File(context.applicationContext.noBackupFilesDir, "local-diagnostics-v1.txt"))
        file = target
        val restored = try {
            target.openRead().use { stream ->
                val content = stream.readBytesBounded(DiagnosticCodec.MAX_SERIALIZED_CHARS)
                if (content == null) emptyList() else DiagnosticCodec.decode(content.toString(Charsets.UTF_8))
            }
        } catch (_: FileNotFoundException) {
            emptyList()
        } catch (_: Exception) {
            mutableStorageError.value = true
            emptyList()
        }
        mutableEntries.value = (restored + mutableEntries.value).takeLast(MAX_ENTRIES)
    }

    fun record(operation: DiagnosticOperation, status: DiagnosticStatus,
        durationMs: Long? = null, errorCode: Int? = null) = synchronized(lock) {
        val value = DiagnosticCodec.entry(System.currentTimeMillis(), operation, status, durationMs, errorCode)
        mutableEntries.value = DiagnosticCodec.append(mutableEntries.value, value)
        persist()
    }

    fun clear() = synchronized(lock) {
        // Clearing is persisted before returning, even if the app is stopped immediately.
        generation++
        mutableEntries.value = emptyList()
        file?.let { target ->
            mutableStorageError.value = runCatching {
                target.delete()
                target.baseFile.exists()
            }.getOrDefault(true)
        }
    }

    fun exportText(): String = DiagnosticCodec.export(mutableEntries.value)

    /** Called under lock; pending obsolete writes cannot restore records after Clear. */
    private fun persist() {
        val target = file ?: return
        val revision = ++generation
        val content = DiagnosticCodec.encode(mutableEntries.value).toByteArray(Charsets.UTF_8)
        writer.execute {
            synchronized(lock) {
                if (revision != generation) return@synchronized
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = target.startWrite()
                    stream.write(content)
                    target.finishWrite(stream)
                    mutableStorageError.value = false
                } catch (_: Exception) {
                    if (stream != null) runCatching { target.failWrite(stream) }
                    mutableStorageError.value = true
                }
            }
        }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray? {
        val buffer = ByteArray(limit + 1)
        var size = 0
        while (size < buffer.size) {
            val count = read(buffer, size, buffer.size - size)
            if (count < 0) break
            if (count == 0) return null
            size += count
        }
        return if (size > limit) null else buffer.copyOf(size)
    }
}
