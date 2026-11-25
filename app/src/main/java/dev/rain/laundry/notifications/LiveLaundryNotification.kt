package dev.rain.laundry.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.rain.laundry.MainActivity
import dev.rain.laundry.R
import dev.rain.laundry.core.LiveLaundryDecision
import dev.rain.laundry.core.LiveLaundryPhase
import dev.rain.laundry.core.LiveLaundryShortText
import dev.rain.laundry.core.LocalOrder
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.i18n.appText
import java.util.Date

/**
 * Standard Android ongoing notification for a running wash.
 *
 * It deliberately uses only public Android notification APIs:
 * - `setUsesChronometer(true)` + `setChronometerCountDown(true)` lets the system render the
 *   remaining time without a one-second app timer or a one-second network poll.
 * - Systems with the API 36.1 promotion contract use the published extras
 *   `android.requestPromotedOngoing` and `android.shortCriticalText`. The request-promotion
 *   builder method itself was added in API 36.1, so this stays forward-compatible when the app
 *   is still compiled against API 35.
 * - Promotion is only *requested*. The system, the user's Live Updates setting and OEM policy
 *   decide whether a promoted/status-chip form is shown. No OPPO private fluid-cloud SDK is used
 *   and no ColorOS capsule is promised.
 */
object LiveLaundryNotification {
    const val CHANNEL_ID = "laundry_live_progress"
    const val NOTIFICATION_ID = 7102

    // Published Android framework extras; older platforms ignore the promotion request.
    internal const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    internal const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
    internal const val PERMISSION_POST_PROMOTED_NOTIFICATIONS =
        "android.permission.POST_PROMOTED_NOTIFICATIONS"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID,
            appText(R.string.live_laundry_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = appText(R.string.live_laundry_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun notificationsEnabled(context: Context): Boolean {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Returns true only when the device exposes the API 36.1 opt-in method, the manifest
     * declares POST_PROMOTED_NOTIFICATIONS, and the user/system currently allow promoted
     * notifications. Failing this check leaves an ordinary ongoing notification behind.
     */
    fun canRequestPromotion(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val supported = runCatching {
            Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", java.lang.Boolean.TYPE)
        }.isSuccess
        if (!supported) return false
        val permitted = runCatching {
            context.checkSelfPermission(PERMISSION_POST_PROMOTED_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permitted) return false
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching false
            manager.javaClass.getMethod("canPostPromotedNotifications").invoke(manager) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun buildChecking(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_laundry_notification)
            .setContentTitle(appText(R.string.live_laundry_title_checking))
            .setContentText(appText(R.string.live_laundry_text_checking))
            .setStyle(NotificationCompat.BigTextStyle().bigText(appText(R.string.live_laundry_text_checking)))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColorized(false)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(context: Context, order: LocalOrder, decision: LiveLaundryDecision,
        expectedToken: String, leaseId: String, isCurrent: () -> Boolean
    ): Boolean {
        if (!decision.visible || decision.shouldStopService || expectedToken.isBlank() || leaseId.isBlank()) return false
        ensureChannel(context)
        val text = contentText(context, decision)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_laundry_notification)
            .setContentTitle(title(context, order, decision))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent(context, order))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColorized(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (hasDismissReceiver(context)) builder.setDeleteIntent(dismissIntent(context, order, leaseId))

        val countdown = decision.countdownEndAtMs
        if (countdown != null) {
            builder.setWhen(countdown)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false).setUsesChronometer(false)
        }

        val extras = Bundle()
        if (decision.requestPromotion && canRequestPromotion(context)) {
            extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }
        shortText(context, decision.shortText)?.let {
            extras.putString(EXTRA_SHORT_CRITICAL_TEXT, it)
        }
        if (!extras.isEmpty()) builder.addExtras(extras)

        val notification = builder.build()
        // The final post shares the vault's lock with login/logout, cancel and lease replacement.
        // Building before this boundary cannot revive a revoked order or a previous login.
        val vault = SessionVault(context)
        return vault.withLaundrySession(order.owner, expectedToken, {
            val current = vault.order()
            isCurrent() && LiveLaundryPreferences.isEnabled(context) && notificationsEnabled(context) &&
                current != null && OrderHistory.sameOrder(current, order) &&
                current.phase == dev.rain.laundry.core.OrderPhase.PAID &&
                current.laundryProgress?.runState == dev.rain.laundry.core.LaundryRunState.RUNNING
        }) {
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
                true
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS may be revoked after the preflight permission check.
                false
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun title(context: Context, order: LocalOrder, decision: LiveLaundryDecision): String {
        val device = order.deviceName.ifBlank { appText(R.string.app_name) }
        if (decision.offline) return appText(R.string.live_laundry_title_offline, device)
        return when (decision.phase) {
            LiveLaundryPhase.RUNNING -> appText(R.string.live_laundry_title_running, device)
            LiveLaundryPhase.TIME_UNKNOWN -> appText(R.string.live_laundry_title_unknown, device)
            LiveLaundryPhase.ESTIMATE_EXPIRED -> appText(R.string.live_laundry_title_expired, device)
            LiveLaundryPhase.OFFLINE -> appText(R.string.live_laundry_title_offline, device)
            LiveLaundryPhase.FINISHED -> appText(R.string.live_laundry_title_expired, device)
            LiveLaundryPhase.STOPPED -> appText(R.string.live_laundry_title_checking)
        }
    }

    private fun contentText(context: Context, decision: LiveLaundryDecision): String {
        val end = decision.expectedEndAtMs ?: decision.countdownEndAtMs
        return when {
            decision.offline && end == null ->
                appText(R.string.live_laundry_text_offline_unknown)
            decision.offline && decision.countdownEndAtMs == null ->
                appText(R.string.live_laundry_text_offline_expired)
            decision.offline ->
                appText(R.string.live_laundry_text_offline, time(context, end))
            decision.phase == LiveLaundryPhase.TIME_UNKNOWN ->
                appText(R.string.live_laundry_text_unknown)
            decision.phase == LiveLaundryPhase.ESTIMATE_EXPIRED ->
                appText(R.string.live_laundry_text_expired, time(context, end))
            decision.stale && decision.countdownEndAtMs != null ->
                appText(R.string.live_laundry_text_stale, time(context, end))
            decision.countdownEndAtMs != null ->
                appText(R.string.live_laundry_text_running, time(context, end))
            else -> appText(R.string.live_laundry_text_unknown)
        }
    }

    private fun time(context: Context, atMs: Long?): String =
        if (atMs == null) "" else DateFormat.getTimeFormat(context).format(Date(atMs))

    private fun shortText(context: Context, text: LiveLaundryShortText): String? = when (text) {
        LiveLaundryShortText.NONE -> null
        LiveLaundryShortText.WAITING_CONFIRMATION -> appText(R.string.live_laundry_short_waiting)
        LiveLaundryShortText.TIME_UNKNOWN -> appText(R.string.live_laundry_short_unknown)
    }

    private fun openIntent(context: Context, order: LocalOrder): PendingIntent {
        val key = dev.rain.laundry.core.OrderHistory.key(order)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.Builder().scheme("jianxi").authority("laundry-live").appendPath(key).build()
            putExtra(LaundryReminders.EXTRA_OPEN_ORDERS, true)
            putExtra(LaundryReminders.EXTRA_OWNER, order.owner)
            putExtra(LaundryReminders.EXTRA_ORDER_KEY, key)
            putExtra(LaundryReminders.EXTRA_ORDER_ID, order.orderId)
            putExtra(LaundryReminders.EXTRA_ORDER_NO, order.orderNo)
        }
        return PendingIntent.getActivity(context, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun dismissIntent(context: Context, order: LocalOrder, leaseId: String): PendingIntent {
        val intent = Intent(context, LiveLaundryDismissReceiver::class.java)
            .setAction(LiveLaundryDismissReceiver.ACTION_DISMISS)
            .setData(Uri.Builder().scheme("jianxi").authority("live-dismiss").appendPath(leaseId).build())
            .putExtra(LaundryLiveMonitor.EXTRA_OWNER, order.owner)
            .putExtra(LaundryLiveMonitor.EXTRA_ORDER_KEY, OrderHistory.key(order))
            .putExtra(LaundryLiveMonitor.EXTRA_LEASE_ID, leaseId)
        return PendingIntent.getBroadcast(context, leaseId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Avoid a dangling delete intent until the parent adds the optional receiver declaration. */
    private fun hasDismissReceiver(context: Context): Boolean = runCatching {
        context.packageManager.getReceiverInfo(
            ComponentName(context, LiveLaundryDismissReceiver::class.java), 0)
        true
    }.getOrDefault(false)
}
