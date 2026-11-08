package dev.rain.laundry.notifications

import dev.rain.laundry.i18n.appText
import dev.rain.laundry.i18n.localizedMessage
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.rain.laundry.MainActivity
import dev.rain.laundry.R
import dev.rain.laundry.core.LocalOrder
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.data.SessionVault

data class LaundryReminderRecord(
    val key: String,
    val order: LocalOrder,
    val armedAtMs: Long,
    val expectedEndAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val notificationClaimed: Boolean = false
)

internal interface LaundryReminderDelivery {
    fun canPost(): Boolean
    fun post(order: LocalOrder)
}

/** Only server-observed running -> completed transitions can generate a reminder. */
object LaundryReminders {
    const val CHANNEL_ID = "laundry_completion"
    const val EXTRA_OPEN_ORDERS = "dev.rain.laundry.OPEN_ORDERS"
    const val EXTRA_OWNER = "dev.rain.laundry.ORDER_OWNER"
    const val EXTRA_ORDER_KEY = "dev.rain.laundry.ORDER_KEY"
    const val EXTRA_ORDER_ID = "dev.rain.laundry.ORDER_ID"
    const val EXTRA_ORDER_NO = "dev.rain.laundry.ORDER_NO"

    fun refreshLanguage(context: Context) { ensureChannel(context) }

    fun notificationsEnabled(context: Context): Boolean {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun observe(context: Context, order: LocalOrder, running: Boolean, completed: Boolean,
        expectedEndAtMs: Long? = null
    ): Boolean = observe(SessionVault(context), order, running, completed, expectedEndAtMs,
        System.currentTimeMillis(), SystemDelivery(context.applicationContext))

    internal fun observe(vault: SessionVault, order: LocalOrder, running: Boolean, completed: Boolean,
        expectedEndAtMs: Long?, nowMs: Long, delivery: LaundryReminderDelivery
    ): Boolean {
        if (order.isWater || order.owner.isBlank() || (running && completed) ||
            (order.orderId.isBlank() && order.orderNo.isBlank())) return false
        val records = vault.updateLaundryReminders(order.owner) { previous ->
            val existing = previous.firstOrNull { OrderHistory.sameOrder(it.order, order) }
            val updated = when {
                existing == null && running -> LaundryReminderRecord(OrderHistory.key(order), order, nowMs,
                    expectedEndAtMs?.takeIf { it > 0 })
                existing != null && completed -> existing.copy(order = order,
                    completedAtMs = existing.completedAtMs ?: nowMs)
                existing != null && running && existing.completedAtMs == null -> existing.copy(order = order,
                    expectedEndAtMs = expectedEndAtMs?.takeIf { it > 0 } ?: existing.expectedEndAtMs)
                else -> existing
            }
            if (updated == null) previous else
                (previous.filterNot { it.key == updated.key } + updated)
                    .sortedByDescending { it.armedAtMs }.take(128)
        } ?: return false
        val ready = records.firstOrNull { OrderHistory.sameOrder(it.order, order) && it.completedAtMs != null }
            ?: return false
        return deliver(vault, ready, nowMs, delivery)
    }

    fun publishPending(context: Context) {
        val vault = SessionVault(context)
        val now = System.currentTimeMillis()
        val delivery = SystemDelivery(context.applicationContext)
        vault.laundryReminders().filter { it.completedAtMs != null && !it.notificationClaimed }
            .forEach { deliver(vault, it, now, delivery) }
    }

    /** A closed/refunded/cancelled attempt is no longer an eligible washing cycle. */
    fun forget(context: Context, order: LocalOrder) {
        SessionVault(context).updateLaundryReminders(order.owner) { records ->
            records.filterNot { OrderHistory.sameOrder(it.order, order) && it.completedAtMs == null }
        }
    }

    internal fun deliver(vault: SessionVault, record: LaundryReminderRecord, nowMs: Long,
        delivery: LaundryReminderDelivery
    ): Boolean = try {
        vault.deliverLaundryReminder(record.order.owner, record.key, nowMs, delivery::canPost, delivery::post)
    } catch (_: SecurityException) {
        // Revocation between the permission check and notify cannot break order refresh.
        false
    }

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, appText(R.string.msg_87c4fd5fd71e), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = appText(R.string.msg_ea126e4e9491)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
    }

    private class SystemDelivery(private val context: Context) : LaundryReminderDelivery {
        override fun canPost(): Boolean = notificationsEnabled(context)
        override fun post(order: LocalOrder) {
            val key = OrderHistory.key(order)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = Uri.Builder().scheme("jianxi").authority("laundry-completed").appendPath(key).build()
                putExtra(EXTRA_OPEN_ORDERS, true)
                putExtra(EXTRA_OWNER, order.owner)
                putExtra(EXTRA_ORDER_KEY, key)
                putExtra(EXTRA_ORDER_ID, order.orderId)
                putExtra(EXTRA_ORDER_NO, order.orderNo)
            }
            val tap = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_laundry)
                .setContentTitle(appText(R.string.msg_409c4e104012))
                .setContentText(appText(R.string.msg_ca80d3c72c64 , order.deviceName, order.modeName))
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(key, 1, notification)
        }
    }
}
