package dev.rain.laundry.keepalive

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import dev.rain.laundry.BuildConfig

/** Only used inside the shell/root UserService, where non-SDK access is supported by Shizuku. */
internal class PrivilegedResidentTarget(private val appUid: Int) : ResidentTarget {
    private val userId = appUid / 100_000
    private val authority = BuildConfig.APPLICATION_ID + ".resident"
    private val amClass = Class.forName("android.app.IActivityManager")
    private val am = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
    private val pmClass = Class.forName("android.content.pm.IPackageManager")
    private val pm = Class.forName("android.app.ActivityThread").getDeclaredMethod("getPackageManager").invoke(null)
    private val acquireMethod = amClass.methods.first {
        it.name == "getContentProviderExternal" && it.parameterCount in 3..4
    }
    private val removeMethod = amClass.methods.firstOrNull {
        it.name == "removeContentProviderExternalAsUser" && it.parameterCount == 3
    } ?: amClass.methods.first { it.name == "removeContentProviderExternal" && it.parameterCount == 2 }

    init {
        require(appUid >= android.os.Process.FIRST_APPLICATION_UID)
        // Older releases cannot identify the target user when releasing a provider.
        require(removeMethod.parameterCount == 3 || userId == 0)
    }

    override fun mayHold(): Boolean {
        val method = pmClass.methods.first { it.name == "getApplicationInfo" && it.parameterCount == 3 }
        val flags: Any = if (method.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
        val info = method.invoke(pm, BuildConfig.APPLICATION_ID, flags, userId) as? ApplicationInfo
            ?: return false
        return info.uid == appUid && info.enabled && info.flags and ApplicationInfo.FLAG_STOPPED == 0
    }

    override fun acquire(): ResidentLease {
        check(mayHold()) { "Installation is stopped or unavailable" }
        val token = Binder()
        var released = false
        fun release() {
            if (released) return
            if (removeMethod.parameterCount == 3) removeMethod.invoke(am, authority, token, userId)
            else removeMethod.invoke(am, authority, token)
            released = true
        }
        try {
            val holder = if (acquireMethod.parameterCount == 4)
                acquireMethod.invoke(am, authority, userId, token, "jianxi-resident")
            else acquireMethod.invoke(am, authority, userId, token)
            checkNotNull(holder) { "Provider reference was not granted" }
            val provider = holder.javaClass.getField("provider").get(holder) as? IInterface
            val binder: IBinder = checkNotNull(provider?.asBinder()) { "Provider has not connected" }
            check(binder.pingBinder()) { "Provider is not alive" }
            return object : ResidentLease {
                override fun isAlive() = binder.isBinderAlive
                override fun close() = release()
            }
        } catch (failure: Exception) {
            runCatching { release() }
            throw failure
        }
    }
}
