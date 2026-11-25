package dev.rain.laundry

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rain.laundry.core.LaundryProgress
import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.LiveLaundryInput
import dev.rain.laundry.core.LiveLaundryPhase
import dev.rain.laundry.core.LiveLaundryProgressPolicy
import dev.rain.laundry.core.LocalOrder
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.core.OrderPhase
import dev.rain.laundry.core.PayMethod
import dev.rain.laundry.core.RemoteOrderStatus
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.i18n.AppLanguage
import dev.rain.laundry.i18n.AppStrings
import dev.rain.laundry.i18n.LanguagePreferences
import dev.rain.laundry.notifications.LiveLaundryNotification
import dev.rain.laundry.notifications.LiveLaundryPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runtime screenshot fixture for the real [LiveLaundryNotification.update] path.
 *
 * It creates a local, synthetic running order inside the app's own vault, evaluates the
 * production policy and calls the production notification builder. It never performs a
 * network request, never starts a wash and never sends the synthetic token anywhere.
 */
@RunWith(AndroidJUnit4::class)
class LiveLaundryScreenshotRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun realOngoingNotificationIsCapturedInExpandedShade() {
        assumeTrue("Only the dedicated Android emulator may run this screenshot fixture",
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val prefs = listOf("laundry_vault", "laundry_live_progress", "laundry_live_status",
            "app_language").associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val previousLanguage = LanguagePreferences.selected(context)
        val directory = File(context.getExternalFilesDir(null), "native-live-screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
        val vault = SessionVault(context)
        try {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            shell("wm dismiss-keyguard")
            shell("cmd statusbar collapse")
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(1_200)
            notifications.cancel(LiveLaundryNotification.NOTIFICATION_ID)

            // Force the app's own locale so the real notification strings are Simplified Chinese.
            LanguagePreferences.set(context, AppLanguage.SIMPLIFIED_CHINESE)
            AppStrings.invalidate()

            val now = System.currentTimeMillis()
            val end = now + 16 * 60_000L
            vault.login("demo", "local-demo-token")
            val owner = vault.owner()
            val orderId = "demo-live-order"
            val orderNo = "demo-live-number"
            val order = LocalOrder(
                owner = owner,
                deviceName = "测试洗衣机",
                modeName = "标准洗",
                amount = "4.00",
                payMethod = PayMethod.ALIPAY,
                phase = OrderPhase.PAID,
                createdAt = now - 60_000L,
                orderId = orderId,
                orderNo = orderNo,
                syncRequested = true,
                laundryProgress = LaundryProgress(
                    orderId = orderId,
                    orderNo = orderNo,
                    deviceId = "DEMO-LOCAL-MACHINE",
                    imei = "DEMO-LOCAL-IMEI",
                    totalMinutes = 16,
                    startedAt = now,
                    expectedEndAt = end,
                    orderStatus = RemoteOrderStatus.IN_USE,
                    updatedAt = now
                )
            )
            vault.saveOrder(order)
            LiveLaundryPreferences.setEnabled(context, true)
            LiveLaundryNotification.ensureChannel(context)
            assertTrue("POST_NOTIFICATIONS and app notifications must be enabled",
                LiveLaundryNotification.notificationsEnabled(context))

            val promotionSupported = LiveLaundryNotification.canRequestPromotion(context)
            val key = OrderHistory.key(order)
            val decision = LiveLaundryProgressPolicy.evaluate(LiveLaundryInput(
                nowMs = now,
                currentOwner = owner,
                orderOwner = owner,
                currentOrderKey = key,
                trackedOrderKey = key,
                orderKey = key,
                isWater = false,
                orderPhase = OrderPhase.PAID,
                runState = LaundryRunState.RUNNING,
                completionConfirmed = false,
                expectedEndAtMs = end,
                snapshotUpdatedAtMs = now,
                monitoringEnabled = true,
                notificationsEnabled = true,
                networkAvailable = true,
                lastCheckFailed = false,
                paused = false,
                promotionSupported = promotionSupported,
                promotionPermission = promotionSupported
            ))
            assertEquals(LiveLaundryPhase.RUNNING, decision.phase)
            assertTrue(decision.visible)
            assertFalse(decision.shouldStopService)
            assertEquals(end, decision.countdownEndAtMs)

            val leaseId = "screenshot-lease-$now"
            assertTrue("The production update path must post under the synthetic demo session",
                LiveLaundryNotification.update(context, order, decision, vault.token(), leaseId) { true })
            val posted = notifications.activeNotifications.firstOrNull {
                it.id == LiveLaundryNotification.NOTIFICATION_ID
            }
            assertNotNull("LiveLaundryNotification.update did not post a system notification", posted)
            val notification = requireNotNull(posted).notification
            val flags = notification.flags
            val chronometer = notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            val countdown = notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
            assertEquals("The system countdown target is the synthetic 16-minute estimate",
                end, notification.`when`)
            val promotedExtra = notification.extras.getBoolean(
                LiveLaundryNotification.EXTRA_REQUEST_PROMOTED_ONGOING, false)
            val shortText = notification.extras.getString(
                LiveLaundryNotification.EXTRA_SHORT_CRITICAL_TEXT)
            Log.i(TAG, "POSTED title=${notification.extras.getCharSequence(Notification.EXTRA_TITLE)} " +
                "text=${notification.extras.getCharSequence(Notification.EXTRA_TEXT)} " +
                "bigText=${notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)} " +
                "flags=0x${flags.toString(16)} chronometer=$chronometer countdown=$countdown " +
                "when=${notification.`when`} promoted=$promotedExtra shortText=$shortText " +
                "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} " +
                "promotionSupported=$promotionSupported")

            // Capture the real system dump while this notification is still posted. This is the
            // authoritative source for the flags/extras that SystemUI receives.
            val dump = shellText("dumpsys notification --noredact")
            File(directory, "notification-dump-full.txt").writeText(dump)
            Log.i(TAG, "DUMPSYS bytes=${dump.length} matches=" +
                dump.lineSequence().count { it.contains(context.packageName) })

            // Keep the notification present for both captures. The status bar capture is best effort.
            shell("cmd statusbar collapse")
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(1_500)
            capture(File(directory, "statusbar.png"))

            shell("cmd statusbar expand-notifications")
            SystemClock.sleep(2_800)
            capture(File(directory, "notification-shade.png"))
            tryExpandNotification()
            SystemClock.sleep(1_200)
            capture(File(directory, "notification-expanded.png"))

            File(directory, "notification-facts.txt").writeText(buildString {
                appendLine("package=${context.packageName}")
                appendLine("notificationId=${LiveLaundryNotification.NOTIFICATION_ID}")
                appendLine("channel=${LiveLaundryNotification.CHANNEL_ID}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("release=${Build.VERSION.RELEASE}")
                appendLine("hardware=${Build.HARDWARE}")
                appendLine("avd=${Build.MODEL}")
                appendLine("when=${notification.`when`}")
                appendLine("chronometer=$chronometer")
                appendLine("chronometerCountDown=$countdown")
                appendLine("requestPromotedOngoing=$promotedExtra")
                appendLine("shortCriticalText=$shortText")
                appendLine("promotionSupported=$promotionSupported")
                appendLine("title=${notification.extras.getCharSequence(Notification.EXTRA_TITLE)}")
                appendLine("text=${notification.extras.getCharSequence(Notification.EXTRA_TEXT)}")
                appendLine("bigText=${notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)}")
                appendLine("flags=0x${flags.toString(16)}")
                appendLine("capturedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            })
            Log.i(TAG, "CAPTURED ${directory.absolutePath}")
        } finally {
            notifications.cancel(LiveLaundryNotification.NOTIFICATION_ID)
            LiveLaundryPreferences.setEnabled(context, false)
            vault.logout()
            LanguagePreferences.set(context, previousLanguage)
            AppStrings.invalidate()
            restoreAll(prefs)
            shell("cmd statusbar collapse")
        }
    }

    private fun tryExpandNotification() {
        val root = automation.rootInActiveWindow ?: return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val description = node.contentDescription?.toString().orEmpty().trim()
            // The quick-settings header also exposes an expand_button, but its description is
            // "Collapse". Only the notification row's own "Expand"/"展开" control is relevant.
            if (description == "Expand" || description == "展开" ||
                description.startsWith("Expand ") || description.startsWith("展开")) {
                nodes += node
            }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(root)
        fun boundsOf(node: AccessibilityNodeInfo): android.graphics.Rect =
            android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val target = nodes.maxByOrNull { boundsOf(it).top }
        Log.i(TAG, "EXPAND_CANDIDATES=${nodes.map { "${it.contentDescription}|${it.viewIdResourceName}|${boundsOf(it)}" }}")
        Log.i(TAG, "EXPAND_CLICK=${target?.contentDescription}|${target?.let { boundsOf(it) }}")
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun capture(file: File) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            FileOutputStream(file).use {
                assertTrue("Could not write ${file.absolutePath}",
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            Log.i(TAG, "SCREENSHOT ${file.absolutePath} ${file.length()} bytes")
        } finally {
            bitmap.recycle()
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun shellText(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun restoreAll(snapshots: Map<String, Map<String, *>>) {
        snapshots.forEach { (name, previous) ->
            val preferences: SharedPreferences = context.getSharedPreferences(name, 0)
            val editor = preferences.edit().clear()
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key,
                        value.filterIsInstance<String>().toSet())
                }
            }
            assertTrue("Could not restore $name", editor.commit())
        }
    }

    private companion object {
        const val TAG = "LiveLaundryScreenshot"
    }
}
