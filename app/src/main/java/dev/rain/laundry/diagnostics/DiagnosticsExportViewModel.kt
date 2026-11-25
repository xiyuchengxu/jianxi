package dev.rain.laundry.diagnostics

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiagnosticsExportResult { SAVED, FAILED }

data class DiagnosticsExportState(
    val writing: Boolean = false,
    val result: DiagnosticsExportResult? = null
)

/** Activity-scoped writing survives configuration changes without retaining an Activity or View. */
class DiagnosticsExportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(DiagnosticsExportState())
    val state = mutableState.asStateFlow()

    fun export(target: Uri, snapshot: String) {
        if (mutableState.value.writing) return
        mutableState.value = DiagnosticsExportState(writing = true)
        viewModelScope.launch {
            try {
                val saved = writeDiagnosticsText(getApplication<Application>().contentResolver, target, snapshot)
                mutableState.value = DiagnosticsExportState(result =
                    if (saved) DiagnosticsExportResult.SAVED else DiagnosticsExportResult.FAILED)
            } finally {
                if (mutableState.value.writing) mutableState.value = DiagnosticsExportState()
            }
        }
    }

    fun clearResult() {
        if (!mutableState.value.writing) mutableState.value = DiagnosticsExportState()
    }

    fun pickerFailed() {
        if (!mutableState.value.writing)
            mutableState.value = DiagnosticsExportState(result = DiagnosticsExportResult.FAILED)
    }
}

/** No network URI handling and no raw exception text is returned to the screen or diagnostics. */
internal suspend fun writeDiagnosticsText(resolver: ContentResolver, target: Uri, snapshot: String): Boolean =
    withContext(Dispatchers.IO) {
        if (target.scheme !in setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE)) return@withContext false
        try {
            resolver.openOutputStream(target, "wt")?.use { stream ->
                stream.write(snapshot.toByteArray(Charsets.UTF_8))
            } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
