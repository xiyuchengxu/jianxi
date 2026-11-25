package dev.rain.laundry.keepalive

import java.util.concurrent.atomic.AtomicBoolean

internal interface ResidentLease : AutoCloseable { fun isAlive(): Boolean }
internal interface ResidentTarget {
    /** False for a force-stopped, removed, disabled or replaced installation. */
    fun mayHold(): Boolean
    fun acquire(): ResidentLease
}

/** The privileged process holds exactly one self-provider reference. A late acquire cannot undo stop. */
internal class ResidentGuard(private val target: ResidentTarget) {
    private val requested = AtomicBoolean(false)
    private var lease: ResidentLease? = null

    fun start(): Boolean { requested.set(true); return refresh() }
    fun stop() { requested.set(false); synchronized(this) { release() } }
    fun isRequested() = requested.get()
    @Synchronized fun isRunning() = requested.get() && lease?.isAlive() == true

    @Synchronized fun refresh(): Boolean {
        if (!requested.get()) { release(); return false }
        // A dead provider may mean user force-stop. Do not restart the app from this daemon.
        if (lease != null && lease?.isAlive() != true) { requested.set(false); release(); return false }
        if (!target.mayHold()) { requested.set(false); release(); return false }
        if (lease != null) return true
        release()
        val acquired = target.acquire()
        var adopted = false
        try {
            if (!requested.get()) return false
            if (!target.mayHold() || !acquired.isAlive()) { requested.set(false); return false }
            lease = acquired
            adopted = true
            return true
        } finally {
            if (!adopted) acquired.close()
        }
    }

    private fun release() {
        val old = lease
        lease = null
        old?.close()
    }
}
