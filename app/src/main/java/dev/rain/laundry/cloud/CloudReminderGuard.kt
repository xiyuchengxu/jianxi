package dev.rain.laundry.cloud

internal data class CloudSessionStamp(val owner: String, val epoch: Long)

internal data class CloudOperationStamp(val session: CloudSessionStamp, val revision: Long)

/**
 * Serializes the decision to accept a result with its local commit. A session reset invalidates
 * results even when the same account logs in again. Operation stamps additionally implement
 * latest-operation-wins for foreground UI results; background work uses a session stamp instead.
 * Keep network requests outside these blocks.
 */
internal class CloudReminderGuard {
    private val lock = Any()
    private var owner = ""
    private var epoch = 0L
    private var revision = 0L

    fun reset(owner: String, block: () -> Unit = {}) = synchronized(lock) {
        epoch++
        revision++
        this.owner = owner
        block()
    }

    fun session(owner: String): CloudSessionStamp? = synchronized(lock) {
        if (owner.isBlank() || owner != this.owner) null else CloudSessionStamp(owner, epoch)
    }

    /** Includes the logged-out state so an asynchronous logout result belongs to one logout only. */
    fun snapshot(): CloudSessionStamp = synchronized(lock) { CloudSessionStamp(owner, epoch) }

    fun begin(owner: String, block: () -> Unit = {}): CloudOperationStamp? = synchronized(lock) {
        if (owner.isBlank() || owner != this.owner) return@synchronized null
        val stamp = CloudOperationStamp(CloudSessionStamp(owner, epoch), ++revision)
        block()
        stamp
    }

    fun <T> withCurrent(stamp: CloudSessionStamp, block: () -> T): T? = synchronized(lock) {
        if (!matches(stamp)) null else block()
    }

    fun <T> withCurrent(stamp: CloudOperationStamp, block: () -> T): T? = synchronized(lock) {
        if (!matches(stamp.session) || stamp.revision != revision) null else block()
    }

    fun isCurrent(stamp: CloudSessionStamp): Boolean = synchronized(lock) { matches(stamp) }

    fun isCurrent(stamp: CloudOperationStamp): Boolean = synchronized(lock) {
        matches(stamp.session) && stamp.revision == revision
    }

    private fun matches(stamp: CloudSessionStamp): Boolean =
        stamp.owner == owner && stamp.epoch == epoch
}
