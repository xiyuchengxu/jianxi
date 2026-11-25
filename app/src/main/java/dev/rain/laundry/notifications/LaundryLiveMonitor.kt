package dev.rain.laundry.notifications

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.ContextCompat
import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.LiveLaundryLease
import dev.rain.laundry.core.LiveLaundryLeaseGate
import dev.rain.laundry.core.LiveLaundryPhase
import dev.rain.laundry.core.LocalOrder
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.core.OrderPhase
import dev.rain.laundry.core.WireProtocol
import dev.rain.laundry.data.SessionVault

/** Dismissing one wash does not turn off reminders for future washes. */
object LiveLaundryPreferences {
    internal const val FILE = "laundry_live_progress"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISMISSED = "dismissed_orders"
    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) = SessionVault(context).withLaundryStateLock {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    internal fun isDismissed(context: Context, owner: String, key: String): Boolean =
        preferences(context).getStringSet(KEY_DISMISSED, emptySet()).orEmpty().contains(dismissalKey(owner, key))
    internal fun setDismissed(context: Context, owner: String, key: String, dismissed: Boolean) {
        val preferences = preferences(context)
        val records = preferences.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toMutableSet()
        val identity = dismissalKey(owner, key)
        if (dismissed) records.add(identity) else records.remove(identity)
        // Opaque hashes, with no order IDs or credentials. Always retain the current dismissal.
        val bounded = if (records.size <= 128) records else {
            records.filterNot { it == identity }.takeLast(127).toMutableSet().apply {
                if (dismissed) add(identity)
            }
        }
        preferences.edit().putStringSet(KEY_DISMISSED, bounded).apply()
    }
    private fun dismissalKey(owner: String, key: String) = WireProtocol.sha256("$owner:$key")
}

/** Owner-scoped rendering metadata; no phone, token, order ID or device name. */
data class LiveLaundryStatus(val phase: LiveLaundryPhase, val expectedEndAtMs: Long?, val updatedAtMs: Long)
object LiveLaundryStatusStore {
    internal const val FILE = "laundry_live_status"
    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun save(context: Context, owner: String, phase: LiveLaundryPhase, expectedEndAtMs: Long?, nowMs: Long) =
        SessionVault(context).withLaundryStateLock {
            val vault = SessionVault(context)
            if (owner.isNotBlank() && vault.owner() == owner && vault.token().isNotBlank()) {
                preferences(context).edit().putString("owner", owner).putString("phase", phase.name)
                    .putLong("end", expectedEndAtMs ?: 0L).putLong("updated", nowMs).apply()
            }
        }
    fun read(context: Context): LiveLaundryStatus? = SessionVault(context).withLaundryStateLock {
        val vault = SessionVault(context)
        val preferences = preferences(context)
        val owner = vault.owner()
        if (owner.isBlank() || vault.token().isBlank() || preferences.getString("owner", null) != owner)
            return@withLaundryStateLock null
        val phase = preferences.getString("phase", null)
            ?.let { runCatching { LiveLaundryPhase.valueOf(it) }.getOrNull() }
            ?: return@withLaundryStateLock null
        val updated = preferences.getLong("updated", 0L).takeIf { it > 0L } ?: return@withLaundryStateLock null
        LiveLaundryStatus(phase, preferences.getLong("end", 0L).takeIf { it > 0L }, updated)
    }
    fun clear(context: Context) = SessionVault(context).withLaundryStateLock {
        preferences(context).edit().clear().apply()
    }
}

/** All access is under SessionVault.withLaundryStateLock, including service callbacks. */
internal object LiveLaundryRuntime {
    private val gate = LiveLaundryLeaseGate()
    fun forIdentity(owner: String, token: String, key: String) = gate.forIdentity(owner, token, key)
    fun request(owner: String, token: String, key: String) = gate.request(owner, token, key)
    fun forStart(id: String) = gate.forStart(id)
    fun isCurrent(lease: LiveLaundryLease) = gate.isCurrent(lease)
    fun activate(lease: LiveLaundryLease) = gate.activate(lease)
    fun isActive(lease: LiveLaundryLease) = gate.isActive(lease)
    fun invalidate(id: String? = null) = gate.invalidate(id)
    fun matchesDismiss(owner: String, key: String, id: String) = gate.matchesDismiss(owner, key, id)
}

/** Only foreground user activity may request a monitor. Workers keep their existing fallback. */
object LaundryLiveMonitor {
    internal const val EXTRA_OWNER = "dev.rain.laundry.LIVE_OWNER"
    internal const val EXTRA_ORDER_KEY = "dev.rain.laundry.LIVE_ORDER_KEY"
    internal const val EXTRA_LEASE_ID = "dev.rain.laundry.LIVE_LEASE_ID"

    fun startForCurrentOrder(context: Context, userVisible: Boolean = false): Boolean {
        if (!userVisible && !isAppForeground(context)) return false
        val app = context.applicationContext
        val vault = SessionVault(app)
        return vault.withLaundryStateLock {
            val owner = vault.owner()
            val token = vault.token()
            val order = vault.order()
            if (owner.isBlank() || token.isBlank() || !eligible(order, owner) ||
                !LiveLaundryPreferences.isEnabled(app) || !LiveLaundryNotification.notificationsEnabled(app))
                return@withLaundryStateLock false
            val key = OrderHistory.key(requireNotNull(order))
            if (LiveLaundryPreferences.isDismissed(app, owner, key)) return@withLaundryStateLock false
            if (LiveLaundryRuntime.forIdentity(owner, token, key) != null) return@withLaundryStateLock true
            val lease = LiveLaundryRuntime.request(owner, token, key) ?: return@withLaundryStateLock false
            val intent = Intent(app, LaundryLiveMonitorService::class.java).putExtra(EXTRA_LEASE_ID, lease.id)
            try {
                ContextCompat.startForegroundService(app, intent)
                true
            } catch (_: Exception) {
                if (LiveLaundryRuntime.isCurrent(lease)) stopLocked(app)
                LiveLaundryFallback.enqueue(app, owner, key)
                false
            }
        }
    }
    fun stop(context: Context) = SessionVault(context).withLaundryStateLock { stopLocked(context.applicationContext) }
    private fun stopLocked(context: Context) {
        LiveLaundryRuntime.invalidate()
        runCatching { context.stopService(Intent(context, LaundryLiveMonitorService::class.java)) }
        LiveLaundryNotification.cancel(context)
        runCatching { LiveLaundryStatusStore.clear(context) }
    }
    fun onOrderObserved(context: Context, order: LocalOrder, running: Boolean, completed: Boolean) {
        val vault = SessionVault(context)
        vault.withLaundryStateLock {
            val current = vault.order() ?: return@withLaundryStateLock
            // A superseded snapshot for the same order cannot stop or restart a newer run.
            if (order.owner != vault.owner() || current != order || !order.isLaundry) return@withLaundryStateLock
            if (completed || !running || !eligible(current, order.owner)) stopLocked(context)
            else if (isAppForeground(context)) startForCurrentOrder(context)
        }
    }
    /** Called only by a visible Activity's lifecycle callback. */
    fun onAppForeground(context: Context) {
        if (!LiveLaundryPreferences.isEnabled(context) || !LiveLaundryNotification.notificationsEnabled(context)) stop(context)
        else startForCurrentOrder(context, userVisible = true)
    }
    /** Explicit enable/resume clears this order's dismissal; automatic refresh never does. */
    fun onPreferenceChanged(context: Context, enabled: Boolean) = SessionVault(context).withLaundryStateLock {
        LiveLaundryPreferences.setEnabled(context, enabled)
        val vault = SessionVault(context)
        vault.order()?.takeIf { it.owner == vault.owner() }?.let {
            if (enabled) LiveLaundryPreferences.setDismissed(context, it.owner, OrderHistory.key(it), false)
        }
        if (!enabled) stopLocked(context) else startForCurrentOrder(context, userVisible = true)
        Unit
    }
    fun isCurrentOrderDismissed(context: Context): Boolean = SessionVault(context).withLaundryStateLock {
        val vault = SessionVault(context)
        val order = vault.order()
        vault.token().isNotBlank() && eligible(order, vault.owner()) &&
            LiveLaundryPreferences.isDismissed(context, requireNotNull(order).owner, OrderHistory.key(order))
    }
    fun dismiss(context: Context, owner: String, key: String, leaseId: String) = SessionVault(context).withLaundryStateLock {
        val vault = SessionVault(context)
        val lease = LiveLaundryRuntime.forStart(leaseId)
        if (lease != null && LiveLaundryRuntime.matchesDismiss(owner, key, leaseId) &&
            vault.owner() == owner && vault.token() == lease.token &&
            vault.order()?.let { it.owner == owner && OrderHistory.key(it) == key } == true) {
            LiveLaundryPreferences.setDismissed(context, owner, key, true)
            stopLocked(context)
        }
    }
    internal fun trackedOrder(context: Context, lease: LiveLaundryLease): LocalOrder? = SessionVault(context).withLaundryStateLock {
        val vault = SessionVault(context)
        if (!LiveLaundryRuntime.isCurrent(lease) || vault.owner() != lease.owner || vault.token() != lease.token)
            return@withLaundryStateLock null
        vault.order()?.takeIf { it.owner == lease.owner && it.isLaundry && OrderHistory.key(it) == lease.orderKey }
    }
    internal fun eligible(order: LocalOrder?, owner: String): Boolean =
        owner.isNotBlank() && order != null && order.owner == owner && order.isLaundry &&
            order.phase == OrderPhase.PAID && order.laundryProgress?.runState == LaundryRunState.RUNNING &&
            order.laundryProgress?.completionConfirmed == false && (order.orderId.isNotBlank() || order.orderNo.isNotBlank())
    internal fun isAppForeground(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val processes = runCatching { manager.runningAppProcesses }.getOrNull() ?: return false
        return processes.any { it.pid == Process.myPid() && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
    }
}

/** The immutable PendingIntent identifies an exact rendered lease, not a global stop switch. */
class LiveLaundryDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISMISS) return
        val owner = intent.getStringExtra(LaundryLiveMonitor.EXTRA_OWNER) ?: return
        val key = intent.getStringExtra(LaundryLiveMonitor.EXTRA_ORDER_KEY) ?: return
        val lease = intent.getStringExtra(LaundryLiveMonitor.EXTRA_LEASE_ID) ?: return
        LaundryLiveMonitor.dismiss(context, owner, key, lease)
    }
    companion object { const val ACTION_DISMISS = "dev.rain.laundry.LIVE_LAUNDRY_DISMISSED" }
}
