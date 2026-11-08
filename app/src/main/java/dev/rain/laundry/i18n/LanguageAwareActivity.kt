package dev.rain.laundry.i18n

import android.content.Context
import androidx.activity.ComponentActivity

open class LanguageAwareActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppStrings.initialize(newBase.applicationContext)
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }
}
