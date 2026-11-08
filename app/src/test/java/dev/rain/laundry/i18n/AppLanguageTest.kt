package dev.rain.laundry.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun emptyOrUnsupportedTagsFollowSystem() {
        for (tag in listOf("", " ", "fr-FR", "de", "und")) {
            assertEquals(tag, AppLanguage.SYSTEM, AppLanguage.fromTag(tag))
        }
    }

    @Test fun supportedLanguagesAcceptRegionalVariants() {
        val cases = mapOf(
            "en" to AppLanguage.ENGLISH,
            "en-GB" to AppLanguage.ENGLISH,
            "ja-JP" to AppLanguage.JAPANESE,
            "ru-RU" to AppLanguage.RUSSIAN,
            "ko-KR" to AppLanguage.KOREAN
        )
        for ((tag, expected) in cases) assertEquals(tag, expected, AppLanguage.fromTag(tag))
    }

    @Test fun chineseRegionsProvideFallbackWhenScriptIsAbsent() {
        for (tag in listOf("zh", "zh-CN", "zh-SG")) {
            assertEquals(tag, AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTag(tag))
        }
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO")) {
            assertEquals(tag, AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag(tag))
        }
    }

    @Test fun explicitChineseScriptTakesPriorityOverRegion() {
        for (tag in listOf("zh-Hans", "zh-Hans-TW", "zh-Hans-HK", "zh-Hans-MO")) {
            assertEquals(tag, AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTag(tag))
        }
        for (tag in listOf("zh-Hant", "zh-Hant-CN", "zh-Hant-SG")) {
            assertEquals(tag, AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag(tag))
        }
    }
}
