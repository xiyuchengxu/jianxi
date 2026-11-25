package dev.rain.laundry.core

import java.util.UUID

/** In-memory authorization for one monitor request. Tokens never enter intents or preferences. */
internal data class LiveLaundryLease(val id: String, val owner: String, val token: String, val orderKey: String) {
    override fun toString(): String = "LiveLaundryLease(id=$id)"
}

/** Pure generation gate. Android callers serialize every operation with the vault state lock. */
internal class LiveLaundryLeaseGate(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private var desired: LiveLaundryLease? = null
    private var activeId: String? = null

    fun forIdentity(owner: String, token: String, orderKey: String): LiveLaundryLease? =
        desired?.takeIf { it.owner == owner && it.token == token && it.orderKey == orderKey }

    fun request(owner: String, token: String, orderKey: String): LiveLaundryLease? {
        if (owner.isBlank() || token.isBlank() || orderKey.isBlank()) return null
        forIdentity(owner, token, orderKey)?.let { return it }
        return LiveLaundryLease(newId(), owner, token, orderKey).also { desired = it; activeId = null }
    }

    fun forStart(id: String): LiveLaundryLease? = desired?.takeIf { it.id == id }
    fun isCurrent(lease: LiveLaundryLease): Boolean = desired == lease
    fun activate(lease: LiveLaundryLease): Boolean {
        if (!isCurrent(lease)) return false
        activeId = lease.id
        return true
    }
    fun isActive(lease: LiveLaundryLease): Boolean = isCurrent(lease) && activeId == lease.id
    fun matchesDismiss(owner: String, orderKey: String, id: String): Boolean =
        desired?.let { it.owner == owner && it.orderKey == orderKey && it.id == id } == true

    /** Stale cleanup must not erase a replacement request, including a re-login to the same owner. */
    fun invalidate(id: String? = null): Boolean {
        if (id != null && desired?.id != id) return false
        desired = null
        activeId = null
        return true
    }
}
