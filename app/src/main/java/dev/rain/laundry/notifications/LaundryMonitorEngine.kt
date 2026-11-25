package dev.rain.laundry.notifications

import android.content.Context
import android.os.Build
import dev.rain.laundry.cloud.CloudReminderVault
import dev.rain.laundry.cloud.CloudReminderWork
import dev.rain.laundry.cloud.CloudReminderOperations
import dev.rain.laundry.cloud.CloudSyncOutcome
import dev.rain.laundry.core.*
import dev.rain.laundry.data.LaundryApi
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared, read-only reconciliation for the foreground and persistent background worker. */
object LaundryMonitorEngine {
    private val mutex = Mutex()
    private val foregroundOperations = AtomicInteger()
    private val foregroundGeneration = AtomicLong()
    private const val MAX_MONITOR_AGE = 7 * 24 * 60 * 60 * 1000L

    fun beginForeground() { foregroundOperations.incrementAndGet(); foregroundGeneration.incrementAndGet() }
    fun endForeground() { foregroundOperations.decrementAndGet() }

    suspend fun check(context: Context, owner: String, suppliedApi: LaundryApi? = null,
        isCurrent: () -> Boolean = { true }
    ): LaundryMonitorCheck = mutex.withLock {
        val vault = SessionVault(context)
        val token = vault.token()
        val generation = foregroundGeneration.get()
        if (foregroundOperations.get() > 0)
            return@withLock LaundryMonitorCheck(System.currentTimeMillis() + 30_000)
        fun valid() = owner.isNotBlank() && token.isNotBlank() && vault.owner() == owner &&
            vault.token() == token && isCurrent() && foregroundOperations.get() == 0 &&
            foregroundGeneration.get() == generation
        fun interrupted() = if (vault.owner() == owner && vault.token() == token && token.isNotBlank())
            LaundryMonitorCheck(System.currentTimeMillis() + 30_000) else LaundryMonitorCheck()
        if (!valid()) return@withLock LaundryMonitorCheck()
        val now = System.currentTimeMillis()
        val reminders = vault.laundryReminders().filter { it.completedAtMs == null }
        val history = vault.orders() + listOfNotNull(vault.order())
        val candidates = (history + reminders.map { it.order }).filter { order ->
            order.owner == owner && order.isLaundry &&
                (order.orderId.isNotBlank() || order.orderNo.isNotBlank()) &&
                (order.phase == OrderPhase.PAID || reminders.any { OrderHistory.sameOrder(it.order, order) }) &&
                now - order.createdAt in 0..MAX_MONITOR_AGE
        }.distinctBy(OrderHistory::key)
        val api = suppliedApi ?: LaundryApi(vault::token, Build.MANUFACTURER)
        var retry = false
        var next: Long? = null
        for (order in candidates) {
            if (!valid()) return@withLock interrupted()
            if (order.phase in setOf(OrderPhase.CLOSED, OrderPhase.CANCELLED, OrderPhase.REJECTED,
                    OrderPhase.REFUNDING, OrderPhase.REFUNDED)) {
                observe(context, order, fresh = false)
                continue
            }
            try {
                val detail = api.orderDetails(order)
                if (!valid()) return@withLock interrupted()
                val updated = OrderStates.apply(order, detail.status).copy(laundryProgress = detail.laundryProgress)
                val applied = vault.withLaundrySession(owner, token, ::valid) {
                    if (!vault.updateLaundryOrder(owner, token, order, updated, ::valid)) false
                    else { if (valid()) observe(context, updated); true }
                }
                if (!applied) {
                    if (!valid()) return@withLock interrupted()
                    next = now + 30_000
                    continue
                }
                if (detail.status == RemoteOrderStatus.IN_USE ||
                    detail.status == RemoteOrderStatus.UNPAID ||
                    (detail.status == RemoteOrderStatus.COMPLETED && updated.laundryProgress?.completionConfirmed != true &&
                        reminders.any { OrderHistory.sameOrder(it.order, order) })) {
                    // Recheck an overdue estimate; never claim completion based on the local clock.
                    val end = updated.laundryProgress?.expectedEndAt
                    val at = if (end != null && end > now) minOf(end, now + 5 * 60_000) else now + 60_000
                    next = next?.let { minOf(it, at) } ?: at
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: SessionExpired) {
                throw expired
            } catch (_: Exception) {
                if (!valid()) return@withLock interrupted()
                retry = true
                next = next?.let { minOf(it, now + 60_000) } ?: now + 60_000
            }
        }
        if (valid()) LaundryReminders.publishPending(context)
        // The cloud reminder is opt-in and read-only. Scheduling a short worker here never blocks
        // the existing local notification/order refresh path.
        if (valid() && runCatching { CloudReminderVault(context).binding(owner) }.getOrNull() != null)
            CloudReminderWork.requestSync(context, owner)
        LaundryMonitorCheck(next, retry)
    }

    fun observe(context: Context, order: LocalOrder, fresh: Boolean = true) {
        val progress = order.laundryProgress
        val cancelled = order.phase in setOf(OrderPhase.CLOSED, OrderPhase.CANCELLED, OrderPhase.REJECTED,
            OrderPhase.REFUNDING, OrderPhase.REFUNDED)
        val running = !cancelled && progress?.runState == LaundryRunState.RUNNING
        val completed = !cancelled && progress?.completionConfirmed == true
        LaundryReminders.observe(context, order,
            running = running,
            completed = completed,
            expectedEndAtMs = progress?.expectedEndAt)
        // Persist a device-assisted transition before stopping the live service. Its lease may
        // become invalid below, so check()'s trailing sync scheduling is not sufficient here.
        runCatching {
            val session = SessionVault(context)
            val token = session.token()
            session.withLaundrySession(order.owner, token, { order.isLaundry }) {
                val binding = CloudReminderVault(context).binding(order.owner)
                    ?.takeIf { it.deviceAssisted } ?: return@withLaundrySession false
                if (CloudReminderOperations(context).queueObservation(order.owner, order, binding, fresh) == CloudSyncOutcome.PENDING)
                    CloudReminderWork.requestSync(context, order.owner)
                true
            }
        }
        if (cancelled) LaundryReminders.forget(context, order)
        // The live notification is separate from the true completion notification. It only
        // follows the current owner's current order and only starts from a visible Activity.
        runCatching { LaundryLiveMonitor.onOrderObserved(context, order, running, completed) }
    }
}
