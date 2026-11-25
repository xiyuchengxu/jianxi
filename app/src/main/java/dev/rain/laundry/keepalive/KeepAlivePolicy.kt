package dev.rain.laundry.keepalive

/** introduced is a write-ahead restoration journal, not a claim that Shizuku is still connected. */
data class KeepAlivePreference(val enabled: Boolean = false, val introduced: Boolean = false)

internal interface KeepAliveStore {
    fun read(): KeepAlivePreference
    /** Must durably persist before returning; a failure must throw before a system mutation. */
    fun write(value: KeepAlivePreference)
}

internal interface SelfIdleWhitelist {
    fun containsSelf(): Boolean
    fun addSelf()
    fun removeSelf()
}

/** Calls are serialized by the manager. Original user whitelist entries are never owned or removed. */
internal object KeepAlivePolicy {
    fun enable(store: KeepAliveStore, whitelist: SelfIdleWhitelist) {
        val before = store.read()
        if (whitelist.containsSelf()) {
            store.write(before.copy(enabled = true))
            return
        }
        // A process death between the command and its reply must leave a recoverable record.
        store.write(KeepAlivePreference(enabled = false, introduced = true))
        whitelist.addSelf()
        check(whitelist.containsSelf()) { "Whitelist addition was not confirmed" }
        store.write(KeepAlivePreference(enabled = true, introduced = true))
    }

    fun disable(store: KeepAliveStore, whitelist: SelfIdleWhitelist?) {
        val before = store.read()
        store.write(before.copy(enabled = false))
        if (!before.introduced) return // A whitelist set before our opt-in belongs to the user.
        checkNotNull(whitelist) { "Shizuku is needed to restore our change" }
        if (whitelist.containsSelf()) whitelist.removeSelf()
        check(!whitelist.containsSelf()) { "Whitelist restoration was not confirmed" }
        store.write(KeepAlivePreference())
    }
}
