package dev.rain.laundry.keepalive

import android.content.Context
import android.os.Binder
import android.os.Process
import androidx.annotation.Keep
import dev.rain.laundry.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A daemon UserService with fixed self-only capabilities. No credentials or orders enter this process. */
@Keep
class ShizukuKeepAliveService : IKeepAliveService.Stub {
    private var clientUid: Int = -1
    private var guard: ResidentGuard? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var ticker: java.util.concurrent.ScheduledFuture<*>? = null

    @Keep constructor() : super()

    @Keep constructor(context: Context) : super() {
        check(context.packageName == BuildConfig.APPLICATION_ID)
        clientUid = context.applicationInfo.uid
    }

    private fun verifyCaller() {
        check(clientUid >= Process.FIRST_APPLICATION_UID && Binder.getCallingUid() == clientUid) {
            "Only the owning app may use this capability"
        }
    }

    @Synchronized override fun isUserWhitelisted(): Boolean {
        verifyCaller()
        return when (command("=${BuildConfig.APPLICATION_ID}").trim()) {
            "true" -> true
            "false" -> false
            else -> error("Unrecognized deviceidle response")
        }
    }

    @Synchronized override fun addSelf() {
        verifyCaller()
        command("+${BuildConfig.APPLICATION_ID}")
    }

    @Synchronized override fun removeSelf() {
        verifyCaller()
        command("-${BuildConfig.APPLICATION_ID}")
    }

    @Synchronized override fun startGuard(): Boolean {
        verifyCaller()
        val resident = guard ?: ResidentGuard(PrivilegedResidentTarget(clientUid)).also { guard = it }
        val started = resident.start()
        if (ticker == null) ticker = scheduler.scheduleWithFixedDelay({
            // Check stopped state before reacquiring. Never launch an Activity or clear FLAG_STOPPED.
            runCatching { resident.refresh() }
            if (!resident.isRequested()) {
                resident.stop()
                kotlin.system.exitProcess(0)
            }
        }, 10, 10, TimeUnit.SECONDS)
        return started
    }

    override fun stopGuard() {
        verifyCaller()
        guard?.stop()
        synchronized(this) { ticker?.cancel(false); ticker = null }
    }

    override fun isGuardRunning(): Boolean { verifyCaller(); return guard?.isRunning() == true }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == 0 || caller == 2000 || caller == clientUid)
        guard?.stop()
        scheduler.shutdownNow()
        kotlin.system.exitProcess(0)
    }

    private fun command(operation: String): String {
        // operation comes exclusively from the three fixed methods above; there is no shell parser.
        val process = ProcessBuilder("/system/bin/cmd", "deviceidle", "whitelist", operation)
            .redirectErrorStream(true).start()
        val reader = Executors.newSingleThreadExecutor()
        val output = reader.submit<String> {
            process.inputStream.bufferedReader().use { stream ->
                val result = StringBuilder()
                val buffer = CharArray(1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 8192) { "Unexpectedly large deviceidle response" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        }
        return try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "deviceidle timed out" }
            check(process.exitValue() == 0) { "deviceidle rejected operation" }
            output.get(1, TimeUnit.SECONDS)
        } finally {
            process.destroy()
            output.cancel(true)
            reader.shutdownNow()
        }
    }
}
