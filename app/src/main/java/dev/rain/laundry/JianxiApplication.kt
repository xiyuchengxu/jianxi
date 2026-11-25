package dev.rain.laundry

import android.app.Application
import android.content.res.Configuration
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.diagnostics.DiagnosticsLog

class JianxiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStrings.initialize(this)
        DiagnosticsLog.initialize(this)
        dev.rain.laundry.keepalive.ShizukuKeepAliveManager.get(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppStrings.invalidate()
    }
}
