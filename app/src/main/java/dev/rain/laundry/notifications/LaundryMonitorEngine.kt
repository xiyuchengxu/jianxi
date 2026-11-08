package dev.rain.laundry.notifications

import android.content.Context
import android.os.Build
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
            order.owner == owner && !order.isWater &&
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
                LaundryReminders.forget(context, order)
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
        LaundryMonitorCheck(next, retry)
    }

    fun observe(context: Context, order: LocalOrder) {
        val progress = order.laundryProgress
        LaundryReminders.observe(context, order,
            running = progress?.runState == LaundryRunState.RUNNING,
            completed = progress?.completionConfirmed == true,
            expectedEndAtMs = progress?.expectedEndAt)
        if (order.phase in setOf(OrderPhase.CLOSED, OrderPhase.CANCELLED, OrderPhase.REJECTED,
                OrderPhase.REFUNDING, OrderPhase.REFUNDED)) LaundryReminders.forget(context, order)
    }
}
