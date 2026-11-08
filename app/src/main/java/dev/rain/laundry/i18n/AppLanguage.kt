package dev.rain.laundry.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", ""), SIMPLIFIED_CHINESE("zh-Hans", "简体中文"), ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"), TRADITIONAL_CHINESE("zh-Hant", "繁體中文"),
    RUSSIAN("ru", "Русский"), KOREAN("ko", "한국어");

    companion object {
        fun fromTag(tag: String): AppLanguage {
            if (tag.isBlank()) return SYSTEM
            val locale = Locale.forLanguageTag(tag)
            return when (locale.language) {
                "zh" -> when (locale.script) {
                    "Hans" -> SIMPLIFIED_CHINESE
                    "Hant" -> TRADITIONAL_CHINESE
                    else -> if (locale.country in setOf("TW", "HK", "MO"))
                        TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
                }
                "en" -> ENGLISH
                "ja" -> JAPANESE
                "ru" -> RUSSIAN
                "ko" -> KOREAN
                else -> SYSTEM
            }
        }
    }
}

/** Language is an app preference, independent of account, orders and theme updates. */
object LanguagePreferences {
    private const val FILE = "app_language"
    private const val KEY = "language_tag"

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (preferences.getBoolean("platform_migrated", false)) return
        val manager = context.getSystemService(LocaleManager::class.java)
        val saved = preferences.getString(KEY, "").orEmpty()
        if (manager.applicationLocales.isEmpty && saved.isNotEmpty())
            manager.applicationLocales = LocaleList.forLanguageTags(saved)
        preferences.edit().putBoolean("platform_migrated", true).apply()
    }

    fun selected(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= 33) {
        AppLanguage.fromTag(context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(','))
    } else AppLanguage.fromTag(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty())

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
        AppStrings.invalidate()
        if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
    }

    fun locale(context: Context): Locale {
        val selected = selected(context)
        if (selected != AppLanguage.SYSTEM) return Locale.forLanguageTag(selected.tag)
        val system = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).systemLocales else Resources.getSystem().configuration.locales
        for (index in 0 until system.size()) {
            val supported = AppLanguage.fromTag(system[index].toLanguageTag())
            if (supported != AppLanguage.SYSTEM) return Locale.forLanguageTag(supported.tag)
        }
        return Locale.forLanguageTag("zh-Hans")
    }

    fun wrap(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale(context)))
        return context.createConfigurationContext(configuration)
    }
}
