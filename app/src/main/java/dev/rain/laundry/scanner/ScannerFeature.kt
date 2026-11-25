package dev.rain.laundry.scanner

import android.content.Context

/** Camera scanning is independent of laundry, water, manual entry and saved devices. */
internal object ScannerFeature {
    const val PREFERENCES = "appearance"
    const val KEY_ENABLED = "scanner_enabled"

    fun isEnabled(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }.getOrDefault(false)
}
