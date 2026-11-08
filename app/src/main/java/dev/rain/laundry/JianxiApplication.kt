package dev.rain.laundry

import android.app.Application
import android.content.res.Configuration
import dev.rain.laundry.i18n.AppStrings

class JianxiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStrings.initialize(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppStrings.invalidate()
    }
}
