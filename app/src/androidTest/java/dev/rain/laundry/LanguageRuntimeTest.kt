package dev.rain.laundry

import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Xml
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.LocalOrder
import dev.rain.laundry.core.OrderPhase
import dev.rain.laundry.core.PayMethod
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.i18n.AppLanguage
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.LanguagePreferences
import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import dev.rain.laundry.notifications.LaundryReminders
import dev.rain.laundry.ui.DEFAULT_HOME_MESSAGE
import dev.rain.laundry.ui.LanguageSettings
import dev.rain.laundry.ui.LaundryTheme
import dev.rain.laundry.ui.SettingsScreen
import dev.rain.laundry.ui.ThemeScreen
import dev.rain.laundry.ui.ThemeSettings
import dev.rain.laundry.ui.displayHomeMessage
import dev.rain.laundry.ui.homeMessageFromDraft
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Offline locale checks. The activity test stays behind the consent gate, so no API model is created. */
@RunWith(AndroidJUnit4::class)
class LanguageRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun everyLanguageTranslatesUiScannerAndNotificationResources() = withOriginalState {
        val expectedLabels = linkedMapOf(
            AppLanguage.SIMPLIFIED_CHINESE to "语言", AppLanguage.ENGLISH to "Language",
            AppLanguage.JAPANESE to "言語", AppLanguage.TRADITIONAL_CHINESE to "語言",
            AppLanguage.RUSSIAN to "Язык", AppLanguage.KOREAN to "언어"
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val originalChannel = manager.getNotificationChannel(LaundryReminders.CHANNEL_ID)
        try {
            for ((language, expectedLabel) in expectedLabels) {
                select(language)
                val wrapped = LanguagePreferences.wrap(context)
                assertEquals(language.name, expectedLabel, appText(R.string.msg_9f6fee1aba17))
                assertEquals(expectedLabel, wrapped.getString(R.string.msg_9f6fee1aba17))
                val scanner = appText(R.string.scanner_title)
                val scannerHint = appText(R.string.scanner_screen_hint)
                assertTrue(scanner.isNotBlank())
                assertTrue(scannerHint.isNotBlank())
                assertEquals(wrapped.getString(R.string.scanner_title), scanner)
                if (language != AppLanguage.SIMPLIFIED_CHINESE) {
                    assertNotEquals("扫描洗衣机或饮水机二维码", scanner)
                    assertNotEquals("洗衣已结束", appText(R.string.msg_409c4e104012))
                }
                LaundryReminders.refreshLanguage(context)
                val channel = requireNotNull(manager.getNotificationChannel(LaundryReminders.CHANNEL_ID))
                assertEquals(appText(R.string.msg_87c4fd5fd71e), channel.name.toString())
                assertEquals(appText(R.string.msg_ea126e4e9491), channel.description)
                val rawDevice = "我同意 / Device 01"
                val rawMode = "无 / Wash 02"
                val body = appText(R.string.msg_ca80d3c72c64, rawDevice, rawMode)
                assertTrue("Device name must remain verbatim", body.contains(rawDevice))
                assertTrue("Mode name must remain verbatim", body.contains(rawMode))
                assertFalse("Format placeholders must be replaced", body.contains("%1\$s"))
            }
        } finally {
            if (originalChannel == null) manager.deleteNotificationChannel(LaundryReminders.CHANNEL_ID)
            else manager.createNotificationChannel(originalChannel)
        }
    }

    @Test fun languageChangesPreserveCustomGreetingAccountAndOrders() = withOriginalState {
        val vault = SessionVault(context)
        vault.login("language-runtime-owner", "offline-language-fixture-token")
        val order = LocalOrder(vault.owner(), "语言", "我同意", "4.00", PayMethod.BALANCE,
            OrderPhase.CREATED, createdAt = 1_700_000_000_000L,
            orderId = "offline-language-order", orderNo = "offline-language-order-no")
        vault.saveOrder(order)
        val appearance = preferences("appearance")
        val custom = "我同意\nHello · 日本語 · 한국어"
        assertTrue(appearance.edit().putString("home_message", custom).putString("mode", "DARK")
            .putString("palette", "TEAL").putBoolean("water_enabled", false).commit())
        val savedAppearance = HashMap(appearance.all)
        val savedVault = HashMap(preferences("laundry_vault").all)
        for (language in AppLanguage.entries.filter { it != AppLanguage.SYSTEM }) {
            select(language)
            assertEquals(custom, displayHomeMessage(custom))
            // A custom value that happens to equal a translatable app label must also stay unchanged.
            assertEquals("我同意", displayHomeMessage("我同意"))
            assertEquals(custom, homeMessageFromDraft(custom))
            val localizedDefault = localizedMessage(DEFAULT_HOME_MESSAGE)
            assertEquals(localizedDefault, displayHomeMessage(DEFAULT_HOME_MESSAGE))
            assertEquals(localizedDefault, displayHomeMessage("  "))
            assertEquals(DEFAULT_HOME_MESSAGE, homeMessageFromDraft(localizedDefault))
            assertEquals(savedAppearance, appearance.all)
            assertEquals(savedVault, preferences("laundry_vault").all)
            assertEquals("offline-language-fixture-token", SessionVault(context).token())
            assertEquals(order, SessionVault(context).order())
        }
    }

    @Test fun followSystemUsesSupportedLocaleAndRegionMappings() = withOriginalState {
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag("zh-TW"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag("zh-HK"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag("zh-Hant-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTag("zh-Hans-TW"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fr-FR"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        select(AppLanguage.SYSTEM)
        val system = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).systemLocales
            else Resources.getSystem().configuration.locales
        val expected = (0 until system.size()).asSequence()
            .map { AppLanguage.fromTag(system[it].toLanguageTag()) }
            .firstOrNull { it != AppLanguage.SYSTEM } ?: AppLanguage.SIMPLIFIED_CHINESE
        assertEquals(expected.tag, LanguagePreferences.locale(context).toLanguageTag())
        assertEquals("", preferences("app_language").getString("language_tag", null))
        assertEquals(7, AppLanguage.entries.size)
    }

    @Test fun pickerSelectionPersistsAcrossActivityRecreation() = withOriginalState {
        // Keep MainActivity's original content behind the agreement gate throughout this test.
        // Only the real language settings component is mounted; no login/session model is initialized.
        assertTrue(preferences("usage_agreement").edit().clear().commit())
        assertTrue(preferences("appearance").edit().putBoolean("splash_enabled", false).commit())
        select(AppLanguage.SIMPLIFIED_CHINESE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { LaundryTheme(ThemeSettings(splashEnabled = false)) { LanguageSettings(true) } }
            }
            click("语言")
            awaitNode { it.text?.toString() == "English" }
            awaitNode { it.text?.toString() == "日本語" }
            click("English")
            await { LanguagePreferences.selected(context) == AppLanguage.ENGLISH }
            val translatedTitle = appText(R.string.msg_517e649764b1)
            // The picker triggers the first recreation; wait for it before explicitly recreating again.
            awaitNode { it.text?.toString() == translatedTitle }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("en", activity.resources.configuration.locales[0].language)
                assertEquals(AppLanguage.ENGLISH, LanguagePreferences.selected(activity))
            }
            awaitNode { it.text?.toString() == translatedTitle }
            assertEquals("en", preferences("app_language").getString("language_tag", null))
        }
        // A newly launched activity reads the saved choice as well.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals("en", it.resources.configuration.locales[0].language) }
            awaitNode { it.text?.toString() == appText(R.string.msg_517e649764b1) }
        }
    }

    @Test fun sixLanguageSettingsAndPickersRenderForVisualReview() = withOriginalState {
        assertTrue(preferences("usage_agreement").edit().clear().commit())
        assertTrue(preferences("appearance").edit().putBoolean("splash_enabled", false).commit())
        for (language in AppLanguage.entries.filter { it != AppLanguage.SYSTEM }) {
            // Select before launch: any platform recreation must finish before mounting the test content.
            select(language)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        var appearance by remember { mutableStateOf(ThemeSettings(splashEnabled = false)) }
                        LaundryTheme(appearance) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(
                                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                                        .verticalScroll(rememberScrollState()).padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    Text(appText(R.string.msg_df3d58c7d84b), style = MaterialTheme.typography.headlineMedium)
                                    SettingsScreen(appearance, onChange = { appearance = it }, openTheme = {}, enabled = true)
                                }
                            }
                        }
                    }
                }
                val label = appText(R.string.msg_9f6fee1aba17)
                awaitNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
                captureLanguage("${language.tag}-settings")
                click(label)
                awaitNode { it.text?.toString() == appText(R.string.msg_b90a8ac9c488) }
                captureLanguage("${language.tag}-picker")
                seekVisibleText(appText(R.string.msg_217cfe7db1e3))
                for (choice in AppLanguage.entries.filter { it != AppLanguage.SYSTEM }) {
                    seekVisibleText(choice.nativeName)
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                awaitNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
                assertEquals(language, LanguagePreferences.selected(context))

                if (language == AppLanguage.ENGLISH) {
                    scenario.onActivity { activity ->
                        activity.setContent {
                            var appearance by remember { mutableStateOf(ThemeSettings(splashEnabled = false)) }
                            LaundryTheme(appearance) {
                                Surface(Modifier.fillMaxSize()) {
                                    ThemeScreen(appearance, onChange = { appearance = it },
                                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp))
                                }
                            }
                        }
                    }
                    // waitForIdleSync alone can still see the previous composition's accessibility tree.
                    // Wait for a unique ThemeScreen node before scrolling its list.
                    awaitNode { it.contentDescription?.toString() == appText(R.string.msg_f78a40791721) }
                    instrumentation.waitForIdleSync()
                    captureLanguage("en-theme")
                    dumpLanguageUi("en-theme-initial")
                    val homeMessageLabel = appText(R.string.msg_92863c1ad1f0)
                    seekVisibleText(homeMessageLabel, prefix = true)
                    clickVisiblePrefix(homeMessageLabel)
                    awaitNode { it.isEditable }
                    captureLanguage("en-home-message")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                }
            }
        }
    }

    private fun captureLanguage(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(220)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "language-checks")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$name.png")
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Language screenshot unavailable" }
        try {
            FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("Screenshot must contain PNG data", file.length() > 0)
        } finally { bitmap.recycle() }
    }

    private fun seekVisibleText(text: String, prefix: Boolean = false) {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        repeat(32) { attempt ->
            instrumentation.waitForIdleSync()
            if (find(automation.rootInActiveWindow) {
                it.isVisibleToUser && matchesText(it, text, prefix)
            } != null) return
            val list = find(automation.rootInActiveWindow) { it.isVisibleToUser && it.isScrollable }
            if (attempt == 16) direction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val moved = list?.performAction(direction) == true
            if (!moved && direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                direction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            SystemClock.sleep(160)
        }
        captureLanguage("failed-locale-lookup")
        dumpLanguageUi("failed-locale-lookup")
        fail("Locale choice or setting is not visible: $text")
    }

    private fun matchesText(node: AccessibilityNodeInfo, text: String, prefix: Boolean = false): Boolean {
        val actual = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        return actual == text || description == text ||
            (prefix && (actual.startsWith(text) || description.startsWith(text)))
    }

    private fun clickVisiblePrefix(text: String) {
        await {
            var node = find(automation.rootInActiveWindow) { it.isVisibleToUser && matchesText(it, text, prefix = true) }
            while (node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }

    private fun dumpLanguageUi(name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "language-checks")
        assertTrue(directory.isDirectory || directory.mkdirs())
        FileOutputStream(File(directory, "$name.xml")).use { stream ->
            val xml = Xml.newSerializer().apply { setOutput(stream, "UTF-8"); startDocument("UTF-8", true) }
            fun writeNode(node: AccessibilityNodeInfo?) {
                if (node == null) return
                xml.startTag(null, "node")
                xml.attribute(null, "text", node.text?.toString().orEmpty())
                xml.attribute(null, "description", node.contentDescription?.toString().orEmpty())
                xml.attribute(null, "class", node.className?.toString().orEmpty())
                xml.attribute(null, "visible", node.isVisibleToUser.toString())
                xml.attribute(null, "clickable", node.isClickable.toString())
                xml.attribute(null, "scrollable", node.isScrollable.toString())
                xml.attribute(null, "bounds", Rect().also(node::getBoundsInScreen).toShortString())
                for (index in 0 until node.childCount) writeNode(node.getChild(index))
                xml.endTag(null, "node")
            }
            xml.startTag(null, "hierarchy")
            writeNode(automation.rootInActiveWindow)
            xml.endTag(null, "hierarchy")
            xml.endDocument()
        }
    }

    private fun preferences(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun select(language: AppLanguage) {
        instrumentation.runOnMainSync { LanguagePreferences.set(context, language) }
        await { LanguagePreferences.selected(context) == language }
        AppStrings.invalidate()
    }

    private fun withOriginalState(block: () -> Unit) {
        val names = listOf("app_language", "appearance", "laundry_vault", "usage_agreement")
        val snapshots = names.associateWith { HashMap(preferences(it).all) }
        val platformLocales = if (Build.VERSION.SDK_INT >= 33)
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else null
        try {
            AppStrings.initialize(context)
            block()
        } finally {
            if (Build.VERSION.SDK_INT >= 33 && platformLocales != null) instrumentation.runOnMainSync {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(platformLocales)
            }
            snapshots.forEach { (name, snapshot) ->
                val editor = preferences(name).edit().clear()
                snapshot.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue("Restore $name", editor.commit())
            }
            AppStrings.invalidate()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for locale state")
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = find(automation.rootInActiveWindow, predicate); result != null }
        return requireNotNull(result)
    }

    private fun click(text: String) {
        await {
            var node = find(automation.rootInActiveWindow) {
                it.text?.toString() == text || it.contentDescription?.toString() == text
            }
            while (node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
}
