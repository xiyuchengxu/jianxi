package dev.rain.laundry.notifications

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.rain.laundry.core.LaundryRunState
import dev.rain.laundry.core.LiveLaundryInput
import dev.rain.laundry.core.LiveLaundryLease
import dev.rain.laundry.core.LiveLaundryProgressPolicy
import dev.rain.laundry.core.OrderHistory
import dev.rain.laundry.core.SessionExpired
import dev.rain.laundry.data.SessionVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-visible, non-sticky monitoring. Local deadline rendering never waits for network I/O. */
class LaundryLiveMonitorService : Service() {
    private class Run(val lease: LiveLaundryLease, val startedElapsedMs: Long) {
        var pollJob: Job? = null
        var renderJob: Job? = null
        var lastCheckFailed = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentRun: Run? = null
    private var latestStartId = 0
    private val vaultListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "owner" || key == "token") {
            runCatching {
                val run = SessionVault(this).withLaundryStateLock { currentRun }
                if (run != null) scope.launch {
                    runCatching { if (!stillCurrent(run)) finishMonitor(run) }
                }
            }
        }
    }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            // The framework chronometer uses an elapsed-time base calculated from setWhen.
            // Rebuild after wall-clock/zone changes, without creating a network poll.
            runCatching {
                val run = SessionVault(this@LaundryLiveMonitorService).withLaundryStateLock { currentRun } ?: return
                run.renderJob?.cancel()
                run.renderJob = scope.launch { renderLoop(run) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { LiveLaundryNotification.ensureChannel(this) }
        runCatching { getSharedPreferences(VAULT_FILE, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(vaultListener) }
        runCatching {
            ContextCompat.registerReceiver(this, clockReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) },
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getStringExtra(LaundryLiveMonitor.EXTRA_LEASE_ID)
        val vault = SessionVault(this)
        vault.withLaundryStateLock {
            val lease = id?.let(LiveLaundryRuntime::forStart)
            if (lease == null) {
                // A delayed start must not stop a newer valid monitor. startId protects a
                // subsequently queued command even when no Run has been created yet.
                if (currentRun == null || currentRun?.let { !LiveLaundryRuntime.isCurrent(it.lease) } == true)
                    stopSelfResult(startId)
                return@withLaundryStateLock
            }
            val order = LaundryLiveMonitor.trackedOrder(this, lease)
            if (!LaundryLiveMonitor.eligible(order, lease.owner) ||
                !LiveLaundryPreferences.isEnabled(this) || !LiveLaundryNotification.notificationsEnabled(this) ||
                LiveLaundryPreferences.isDismissed(this, lease.owner, lease.orderKey)) {
                // This is still the desired lease, but it has become ineligible. Invalidate
                // it atomically; a different pending or active lease is never touched.
                if (LiveLaundryRuntime.isCurrent(lease)) LaundryLiveMonitor.stop(this)
                stopSelfResult(startId)
                return@withLaundryStateLock
            }
            if (currentRun?.lease == lease && LiveLaundryRuntime.isActive(lease)) return@withLaundryStateLock
            currentRun?.let { it.pollJob?.cancel(); it.renderJob?.cancel() }
            val run = Run(lease, SystemClock.elapsedRealtime())
            currentRun = run
            if (!LiveLaundryRuntime.activate(lease) || !postForegroundPlaceholder()) {
                finishMonitor(run)
                return@withLaundryStateLock
            }
            run.renderJob = scope.launch { renderLoop(run) }
            run.pollJob = scope.launch { pollLoop(run) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runCatching { getSharedPreferences(VAULT_FILE, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(vaultListener) }
        runCatching { unregisterReceiver(clockReceiver) }
        SessionVault(this).withLaundryStateLock {
            currentRun?.let { run ->
                if (LiveLaundryRuntime.invalidate(run.lease.id)) {
                    LiveLaundryNotification.cancel(this)
                    LiveLaundryStatusStore.clear(this)
                }
            }
            currentRun = null
            // The service lifecycle is dispatched on the main thread, before a replacement
            // instance receives onStartCommand. This only removes this instance's FGS state.
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        super.onDestroy()
    }

    private fun postForegroundPlaceholder(): Boolean = try {
        val notification = LiveLaundryNotification.buildChecking(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(LiveLaundryNotification.NOTIFICATION_ID,
            notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(LiveLaundryNotification.NOTIFICATION_ID, notification)
        true
    } catch (_: Exception) { false }

    private suspend fun renderLoop(run: Run) {
        try {
            while (scope.isActive) {
                val sleep = render(run)
                if (sleep == null) { finishMonitor(run); return }
                delay(sleep)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching { finishMonitor(run) }
        }
    }

    /** All identity checks, notify and status persistence share the login/logout lock. */
    private fun render(run: Run): Long? = SessionVault(this).withLaundryStateLock {
        if (!currentLocked(run)) return@withLaundryStateLock null
        val elapsed = SystemClock.elapsedRealtime() - run.startedElapsedMs
        val limitRemaining = LiveLaundryProgressPolicy.DEFAULT_MAX_MONITOR_DURATION_MS - elapsed
        if (limitRemaining <= 0L) return@withLaundryStateLock null
        val order = LaundryLiveMonitor.trackedOrder(this, run.lease) ?: return@withLaundryStateLock null
        val progress = order.laundryProgress
        val now = System.currentTimeMillis()
        val promotion = LiveLaundryNotification.canRequestPromotion(this)
        val decision = LiveLaundryProgressPolicy.evaluate(LiveLaundryInput(
            nowMs = now,
            currentOwner = run.lease.owner,
            orderOwner = order.owner,
            currentOrderKey = OrderHistory.key(order),
            trackedOrderKey = run.lease.orderKey,
            orderKey = OrderHistory.key(order),
            isWater = order.isWater,
            isLaundry = order.isLaundry,
            orderPhase = order.phase,
            runState = progress?.runState ?: LaundryRunState.UNKNOWN,
            completionConfirmed = progress?.completionConfirmed == true,
            expectedEndAtMs = progress?.expectedEndAt,
            snapshotUpdatedAtMs = progress?.updatedAt,
            monitoringEnabled = LiveLaundryPreferences.isEnabled(this),
            notificationsEnabled = LiveLaundryNotification.notificationsEnabled(this),
            networkAvailable = networkAvailable(),
            lastCheckFailed = run.lastCheckFailed,
            promotionSupported = promotion,
            promotionPermission = promotion
        ))
        if (!decision.visible || decision.shouldStopService) return@withLaundryStateLock null
        val posted = LiveLaundryNotification.update(this, order, decision, run.lease.token, run.lease.id) { currentLocked(run) }
        if (!posted) return@withLaundryStateLock null
        LiveLaundryStatusStore.save(this, run.lease.owner, decision.phase,
            decision.expectedEndAtMs ?: decision.countdownEndAtMs, now)
        val untilDeadline = decision.countdownEndAtMs?.minus(now)?.takeIf { it > 0L } ?: Long.MAX_VALUE
        // One local repaint at the deadline prevents an expired estimate counting below zero.
        minOf(30_000L, untilDeadline, limitRemaining).coerceAtLeast(1L)
    }

    private suspend fun pollLoop(run: Run) {
        try {
          while (scope.isActive && stillCurrent(run)) {
            val check = try {
                LaundryMonitorEngine.check(applicationContext, run.lease.owner,
                    isCurrent = { stillCurrent(run) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionExpired) {
                // Do not clear a newer login or call a failed credential "completed".
                finishMonitor(run)
                return
            } catch (_: Exception) { null }
            val continuePolling = SessionVault(this).withLaundryStateLock {
                if (!currentLocked(run)) return@withLaundryStateLock false
                val progress = LaundryLiveMonitor.trackedOrder(this, run.lease)?.laundryProgress
                val now = System.currentTimeMillis()
                val fresh = progress?.updatedAt?.let { now >= it && now - it <= 10_000L } == true
                run.lastCheckFailed = (check == null || check.retry) && !fresh
                render(run) != null
            }
            if (!continuePolling) { finishMonitor(run); return }
            val sleep = if (run.lastCheckFailed || !networkAvailable()) 60_000L
                else (check?.nextCheckAtMs?.minus(System.currentTimeMillis()) ?: 30_000L).coerceIn(30_000L, 60_000L)
            delay(sleep)
          }
          finishMonitor(run)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching { finishMonitor(run) }
        }
    }

    private fun stillCurrent(run: Run): Boolean = SessionVault(this).withLaundryStateLock { currentLocked(run) }
    private fun currentLocked(run: Run): Boolean = currentRun === run && LiveLaundryRuntime.isCurrent(run.lease) &&
        LaundryLiveMonitor.trackedOrder(this, run.lease) != null && LiveLaundryPreferences.isEnabled(this) &&
        !LiveLaundryPreferences.isDismissed(this, run.lease.owner, run.lease.orderKey)

    private fun finishMonitor(run: Run) = SessionVault(this).withLaundryStateLock {
        if (currentRun !== run || !LiveLaundryRuntime.isCurrent(run.lease)) return@withLaundryStateLock
        currentRun = null
        LiveLaundryRuntime.invalidate(run.lease.id)
        run.pollJob?.cancel()
        run.renderJob?.cancel()
        runCatching { LiveLaundryStatusStore.clear(this) }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        LiveLaundryNotification.cancel(this)
        runCatching { stopSelfResult(latestStartId) }
    }

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private companion object { const val VAULT_FILE = "laundry_vault" }
}
