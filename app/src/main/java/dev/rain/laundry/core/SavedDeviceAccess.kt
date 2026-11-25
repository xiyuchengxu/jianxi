package dev.rain.laundry.core

import java.util.concurrent.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A response or failure from an old login must not affect the current account. */
suspend fun queryDeviceForCurrentSession(
    isCurrent: () -> Boolean,
    query: suspend () -> ScannedDevice
): ScannedDevice? = queryCurrentDeviceValue(isCurrent, query)

/** Reuse the same success AND failure boundary for the second, read-only status request. */
suspend fun <T> queryCurrentDeviceValue(
    isCurrent: () -> Boolean,
    query: suspend () -> T
): T? {
    if (!isCurrent()) return null
    return try {
        val device = query()
        device.takeIf { isCurrent() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (isCurrent()) throw failure else null
    }
}

enum class SavedDeviceStatusPhase { CHECKING, AVAILABLE, USING, OCCUPIED, UNAVAILABLE, UNKNOWN, ERROR }

/** Water history is a shortcut only; opening it must never start an availability request. */
fun shouldCheckSavedDeviceStatuses(kind: SavedDeviceKind?): Boolean = kind == SavedDeviceKind.LAUNDRY

/** A laundry-only, in-memory observation. Never persisted or reused as authorization. */
data class SavedDeviceStatus(
    val owner: String,
    val kind: SavedDeviceKind,
    val deviceId: String,
    val name: String,
    val phase: SavedDeviceStatusPhase = SavedDeviceStatusPhase.CHECKING,
    val subject: String = "",
    val selectionId: String = "",
    val remainingMinutes: Int = 0,
    val detail: String = "",
    val checkedAt: Long = 0L
) {
    fun matches(record: SavedDevice): Boolean = matches(record.savedKind, record.deviceId, record.owner)
    fun matches(kind: SavedDeviceKind?, deviceId: String, owner: String): Boolean =
        owner.isNotBlank() && this.owner == owner && this.kind == kind && this.deviceId == deviceId

    companion object {
        fun key(record: SavedDevice): String = record.kind + ":" + record.deviceId
        fun checking(record: SavedDevice): SavedDeviceStatus {
            require(shouldCheckSavedDeviceStatuses(record.savedKind))
            return SavedDeviceStatus(record.owner, SavedDeviceKind.LAUNDRY, record.deviceId, record.name)
        }

        fun laundry(owner: String, device: Device, mode: WashMode, available: Boolean, now: Long) =
            SavedDeviceStatus(owner, SavedDeviceKind.LAUNDRY, device.id, device.name,
                phase = when {
                    device.error.isNotBlank() -> SavedDeviceStatusPhase.UNAVAILABLE
                    device.remainingMinutes > 0 -> SavedDeviceStatusPhase.USING
                    available -> SavedDeviceStatusPhase.AVAILABLE
                    else -> SavedDeviceStatusPhase.UNAVAILABLE
                }, subject = mode.name, selectionId = mode.id,
                remainingMinutes = device.remainingMinutes.coerceAtLeast(0), detail = device.error,
                checkedAt = now)

    }
}

/** Immutable identity of one opened, kind-scoped list; old rows cannot be revived after deletion. */
data class SavedDeviceStatusRequest(val owner: String, val token: String, val generation: Long,
    val kind: SavedDeviceKind) {
    fun accepts(record: SavedDevice, currentOwner: String, currentToken: String,
        currentGeneration: Long, currentRecords: List<SavedDevice>): Boolean =
        shouldCheckSavedDeviceStatuses(kind) && owner.isNotBlank() && token.isNotBlank() &&
            owner == currentOwner && token == currentToken &&
            generation == currentGeneration && record.owner == owner && record.savedKind == kind &&
            currentRecords.any { it.owner == owner && it.savedKind == kind && it.deviceId == record.deviceId &&
                it.codeKey == record.codeKey && it.codeValue == record.codeValue && it.lastUsedAt == record.lastUsedAt }
}

/** Each row fails independently. Cancellation and stale identities suppress both results and errors. */
suspend fun querySavedDeviceStatuses(
    records: List<SavedDevice>,
    isCurrent: (SavedDevice) -> Boolean,
    query: suspend (SavedDevice) -> SavedDeviceStatus?,
    onResult: (SavedDevice, SavedDeviceStatus) -> Unit,
    onFailure: (SavedDevice, Exception) -> Unit
) = coroutineScope {
    val limiter = Semaphore(2)
    records.filter { shouldCheckSavedDeviceStatuses(it.savedKind) }.map { record -> launch {
        limiter.withPermit {
            try {
                val result = queryCurrentDeviceValue({ isCurrent(record) }) { query(record) }
                    ?: return@withPermit
                if (isCurrent(record)) onResult(record, result)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(record)) onFailure(record, failure)
            }
        }
    } }.joinAll()
}

enum class SavedDeviceWrite { SAVED, STALE, UNAVAILABLE }

/** History is optional; a storage failure cannot block use of freshly queried device data. */
fun saveOptionalDeviceHistory(
    isCurrent: () -> Boolean,
    save: () -> Boolean
): SavedDeviceWrite {
    if (!isCurrent()) return SavedDeviceWrite.STALE
    return try {
        if (save() && isCurrent()) SavedDeviceWrite.SAVED else SavedDeviceWrite.STALE
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (isCurrent()) SavedDeviceWrite.UNAVAILABLE else SavedDeviceWrite.STALE
    }
}
