package dev.rain.laundry.keepalive

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import dev.rain.laundry.BuildConfig
import dev.rain.laundry.data.SessionVault
import dev.rain.laundry.core.SessionExpired
import dev.rain.laundry.notifications.LaundryMonitorEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

enum class KeepAliveIssue { UNAVAILABLE, OLD_VERSION, DENIED, FAILED, STORAGE }

data class ShizukuKeepAliveState(
    val preference: KeepAlivePreference = KeepAlivePreference(),
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    val guardRunning: Boolean = false,
    val busy: Boolean = false,
    val issue: KeepAliveIssue? = null
)

/** Opt-in residency. Credentials and read-only order checks remain in the ordinary app process. */
class ShizukuKeepAliveManager private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("optional_shizuku_keepalive", Context.MODE_PRIVATE)
    private val store = object : KeepAliveStore {
        override fun read() = KeepAlivePreference(
            runCatching { preferences.getBoolean("enabled", false) }.getOrDefault(false),
            runCatching { preferences.getBoolean("introduced", false) }.getOrDefault(false))
        override fun write(value: KeepAlivePreference) {
            check(preferences.edit().putBoolean("enabled", value.enabled)
                .putBoolean("introduced", value.introduced).commit()) { "Preference write failed" }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationLock = Mutex()
    private var residentService: IKeepAliveService? = null
    private var residentConnection: ServiceConnection? = null
    private var reconcileJob: Job? = null
    private val serviceArgs get() = Shizuku.UserServiceArgs(ComponentName(context.packageName,
        ShizukuKeepAliveService::class.java.name))
        .tag("jianxi-resident-${Process.myUid()}")
        .daemon(true).processNameSuffix("resident_guard")
        .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private val mutableState = MutableStateFlow(ShizukuKeepAliveState(preference = store.read()))
    val state = mutableState.asStateFlow()
    private var pendingPermissionAction: Boolean? = null
    private var pendingRequestCode: Int? = null
    private var permissionRequestSequence = 0L
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        residentService = null
        residentConnection = null
        mutableState.update { it.copy(guardRunning = false) }
        if (pendingPermissionAction != null) {
            pendingPermissionAction = null
            pendingRequestCode = null
            permissionRequestSequence++
            mutableState.update { it.copy(busy = false, issue = KeepAliveIssue.UNAVAILABLE) }
        }
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grant ->
        if (requestCode == pendingRequestCode && pendingPermissionAction != null) {
            val enable = pendingPermissionAction ?: false
            pendingPermissionAction = null
            pendingRequestCode = null
            permissionRequestSequence++
            if (grant == PackageManager.PERMISSION_GRANTED) perform(enable)
            else mutableState.update { it.copy(busy = false, issue = KeepAliveIssue.DENIED) }
            refresh()
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
        scope.launch {
            var nextCheckAt = 0L
            var previousOwner = ""
            var previousToken = ""
            var sessionExpired = false
            while (isActive) {
                delay(15_000)
                refresh()
                val current = mutableState.value
                if (!current.preference.enabled || !current.permissionGranted || !current.guardRunning) {
                    nextCheckAt = 0L
                    continue
                }
                val vault = SessionVault(context)
                val owner = vault.owner()
                val token = vault.token()
                if (owner != previousOwner || token != previousToken) {
                    nextCheckAt = 0L; previousOwner = owner; previousToken = token; sessionExpired = false
                }
                if (sessionExpired || owner.isBlank() || token.isBlank() || System.currentTimeMillis() < nextCheckAt) continue
                try {
                    val check = withContext(Dispatchers.IO) {
                        LaundryMonitorEngine.check(context, owner, isCurrent = {
                            store.read().enabled && mutableState.value.guardRunning &&
                                mutableState.value.permissionGranted
                        })
                    }
                    val now = System.currentTimeMillis()
                    nextCheckAt = (check.nextCheckAtMs ?: (now + 60_000)).coerceIn(now + 30_000, now + 300_000)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SessionExpired) { sessionExpired = true }
                catch (_: Exception) { nextCheckAt = System.currentTimeMillis() + 60_000 }
            }
        }
    }

    /** Reconnects only after a saved opt-in; never prompts or changes the battery whitelist here. */
    fun refresh(reconcile: Boolean = true) {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val exempt = runCatching {
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        mutableState.update { it.copy(preference = store.read(), running = running,
            permissionGranted = granted, batteryExempt = exempt) }
        if (!reconcile || reconcileJob?.isActive == true || mutableState.value.busy) return
        reconcileJob = scope.launch {
            operationLock.withLock {
                try {
                    if (!store.read().enabled || !granted) {
                        withContext(Dispatchers.IO) { residentService?.stopGuard() }
                        releaseService()
                        mutableState.update { it.copy(guardRunning = false) }
                    } else if (runCatching { Shizuku.getVersion() >= 13 }.getOrDefault(false)) {
                        withService { service ->
                            if (!store.read().enabled) {
                                service.stopGuard()
                                return@withService
                            }
                            val held = service.isGuardRunning || service.startGuard()
                            check(held) { "Residency was not confirmed" }
                            mutableState.update { it.copy(guardRunning = true, issue = null) }
                        }
                    }
                } catch (failure: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.w("JianxiResident", "Guard reconciliation failed", failure)
                    mutableState.update { it.copy(guardRunning = false, issue = KeepAliveIssue.FAILED) }
                }
            }
        }
    }

    /** Called only by the explicit switch/retry button in notification settings. */
    fun setEnabled(enable: Boolean) {
        if (mutableState.value.busy) return
        // Persist opting out even if permission has been revoked or Shizuku has stopped.
        if (!enable) {
            try { store.write(store.read().copy(enabled = false)) }
            catch (_: Exception) {
                mutableState.update { it.copy(issue = KeepAliveIssue.STORAGE) }
                return
            }
            // Opt-out takes effect before requesting any privilege needed to restore the whitelist.
            mutableState.update { it.copy(guardRunning = false) }
            if (!store.read().introduced || !mutableState.value.permissionGranted) {
                scope.launch {
                    operationLock.withLock {
                        withContext(Dispatchers.IO) { runCatching { residentService?.stopGuard() } }
                        releaseService()
                    }
                }
            }
            if (!store.read().introduced) {
                mutableState.update { it.copy(issue = null) }
                refresh()
                return
            }
        }
        refresh(reconcile = false)
        if (!mutableState.value.running) {
            mutableState.update { it.copy(issue = KeepAliveIssue.UNAVAILABLE) }
            return
        }
        if (runCatching { Shizuku.isPreV11() || Shizuku.getVersion() < 13 }.getOrDefault(true)) {
            mutableState.update { it.copy(issue = KeepAliveIssue.OLD_VERSION) }
            return
        }
        mutableState.update { it.copy(busy = true, issue = null) }
        if (mutableState.value.permissionGranted) perform(enable)
        else {
            pendingPermissionAction = enable
            val sequence = ++permissionRequestSequence
            val requestCode = REQUEST_PERMISSION + (sequence % 100_000).toInt()
            pendingRequestCode = requestCode
            try {
                Shizuku.requestPermission(requestCode)
                // A dismissed/lost permission reply must not leave the optional card stuck forever.
                scope.launch {
                    delay(60_000)
                    if (sequence == permissionRequestSequence && pendingPermissionAction != null) {
                        pendingPermissionAction = null
                        pendingRequestCode = null
                        permissionRequestSequence++
                        mutableState.update { it.copy(busy = false, issue = KeepAliveIssue.DENIED) }
                    }
                }
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("JianxiResident", "Permission request failed", failure)
                pendingPermissionAction = null
                pendingRequestCode = null
                mutableState.update { it.copy(busy = false, issue = KeepAliveIssue.UNAVAILABLE) }
            }
        }
    }

    private fun perform(enable: Boolean) {
        scope.launch {
            try {
                operationLock.withLock {
                    withService { service ->
                        val whitelist = object : SelfIdleWhitelist {
                            override fun containsSelf() = service.isUserWhitelisted
                            override fun addSelf() = service.addSelf()
                            override fun removeSelf() = service.removeSelf()
                        }
                        if (enable) {
                            KeepAlivePolicy.enable(store, whitelist)
                            check(service.startGuard()) { "Residency was not confirmed" }
                            mutableState.update { it.copy(guardRunning = true) }
                        } else {
                            service.stopGuard()
                            KeepAlivePolicy.disable(store, whitelist)
                            mutableState.update { it.copy(guardRunning = false) }
                        }
                    }
                }
                mutableState.update { it.copy(issue = null) }
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("JianxiResident", "Guard switch failed", failure)
                mutableState.update { it.copy(issue = KeepAliveIssue.FAILED) }
            } finally {
                if (!store.read().enabled) releaseService()
                mutableState.update { it.copy(busy = false) }
                refresh()
            }
        }
    }

    private suspend fun <T> withService(action: (IKeepAliveService) -> T): T {
        residentService?.takeIf { it.asBinder().isBinderAlive }?.let {
            return withContext(Dispatchers.IO) { action(it) }
        }
        val connected = CompletableDeferred<IKeepAliveService>()
        val args = serviceArgs
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (binder.pingBinder()) connected.complete(IKeepAliveService.Stub.asInterface(binder))
                else connected.completeExceptionally(IllegalStateException("User service unavailable"))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (residentConnection === this) {
                    residentService = null
                    mutableState.update { it.copy(guardRunning = false) }
                }
                connected.completeExceptionally(IllegalStateException("User service disconnected"))
            }
        }
        residentConnection?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
        residentConnection = connection
        return try {
            Shizuku.bindUserService(args, connection)
            val service = withTimeout(10_000) { connected.await() }
            residentService = service
            withContext(Dispatchers.IO) { action(service) }
        } catch (failure: Exception) {
            releaseService()
            throw failure
        }
    }

    private suspend fun releaseService() {
        val connection = residentConnection
        residentConnection = null
        residentService = null
        withContext(NonCancellable + Dispatchers.IO) {
            connection?.let { runCatching { Shizuku.unbindUserService(serviceArgs, it, true) } }
        }
    }

    companion object {
        private const val REQUEST_PERMISSION = 8401
        @SuppressLint("StaticFieldLeak") // Only applicationContext is retained.
        @Volatile private var instance: ShizukuKeepAliveManager? = null
        fun get(context: Context): ShizukuKeepAliveManager = instance ?: synchronized(this) {
            instance ?: ShizukuKeepAliveManager(context.applicationContext).also { instance = it }
        }
    }
}
