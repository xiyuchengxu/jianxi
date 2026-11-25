package dev.rain.laundry.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedDeviceStatusTest {
    private fun record(id: String, kind: SavedDeviceKind = SavedDeviceKind.LAUNDRY) =
        SavedDevice("owner", kind.wire, id, "IMEI", "CODE-$id", id, lastUsedAt = 10L)
    private val mode = WashMode("mode-a", "标准洗", "4.00")
    private val laundry = Device("washer", "洗衣机", "washer-imei", "00", "shop", "org", 0, listOf(mode))

    @Test fun rowRequestsRejectOldAccountsTokensGenerationsKindsDeletedAndReplacedRecords() {
        val target = record("same")
        val request = SavedDeviceStatusRequest("owner", "token", 7, SavedDeviceKind.LAUNDRY)
        fun accepts(owner: String = "owner", token: String = "token", generation: Long = 7,
            records: List<SavedDevice> = listOf(target)) = request.accepts(target, owner, token, generation, records)
        assertTrue(accepts())
        assertFalse(accepts(owner = "new-owner"))
        assertFalse(accepts(token = "new-token"))
        assertFalse(accepts(generation = 8))
        assertFalse(accepts(records = emptyList()))
        assertFalse(accepts(records = listOf(target.copy(codeValue = "NEW-CODE"))))
        assertFalse(accepts(records = listOf(target.copy(lastUsedAt = 20))))
        assertFalse(request.accepts(record("same", SavedDeviceKind.WATER), "owner", "token", 7,
            listOf(record("same", SavedDeviceKind.WATER))))
        assertTrue(accepts(records = listOf(target.copy(note = "updated note"))))
    }

    @Test fun waterHistoryCannotEnableAStatusRequestOrCreateACheckingSnapshot() {
        val target = record("water", SavedDeviceKind.WATER)
        assertTrue(shouldCheckSavedDeviceStatuses(SavedDeviceKind.LAUNDRY))
        assertFalse(shouldCheckSavedDeviceStatuses(SavedDeviceKind.WATER))
        assertFalse(shouldCheckSavedDeviceStatuses(null))
        val request = SavedDeviceStatusRequest("owner", "token", 7, SavedDeviceKind.WATER)
        assertFalse(request.accepts(target, "owner", "token", 7, listOf(target)))
        assertTrue(runCatching { SavedDeviceStatus.checking(target) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun batchEngineSkipsWaterRecordsEvenIfACallerSuppliesAMixedOrWaterOnlyList() = runTest {
        val washer = record("washer")
        val water = record("water", SavedDeviceKind.WATER)
        val queried = mutableListOf<String>()
        val results = mutableListOf<String>()
        suspend fun query(records: List<SavedDevice>) = querySavedDeviceStatuses(records, { true },
            query = { queried += it.deviceId; SavedDeviceStatus.checking(it) },
            onResult = { record, _ -> results += record.deviceId },
            onFailure = { _, _ -> fail("water records must not reach a request or callback") })
        query(listOf(water))
        assertTrue(queried.isEmpty())
        assertTrue(results.isEmpty())
        query(listOf(water, washer, water.copy(deviceId = "other-water")))
        assertEquals(listOf("washer"), queried)
        assertEquals(listOf("washer"), results)
    }

    @Test fun washerAvailabilityIsModeScopedAndReportedRunningOrErrorsWin() {
        val available = SavedDeviceStatus.laundry("owner", laundry, mode, true, 123)
        assertEquals(SavedDeviceStatusPhase.AVAILABLE, available.phase)
        assertEquals("mode-a", available.selectionId)
        assertEquals("标准洗", available.subject)
        assertEquals(123L, available.checkedAt)
        assertEquals(SavedDeviceStatusPhase.UNAVAILABLE,
            SavedDeviceStatus.laundry("owner", laundry, mode, false, 123).phase)
        assertEquals(SavedDeviceStatusPhase.USING,
            SavedDeviceStatus.laundry("owner", laundry.copy(remainingMinutes = 12), mode, true, 123).phase)
        assertEquals(SavedDeviceStatusPhase.UNAVAILABLE,
            SavedDeviceStatus.laundry("owner", laundry.copy(error = "设备异常"), mode, true, 123).phase)
    }

    @Test fun checkingSnapshotCannotBeConfusedWithAnotherOwnerOrBusiness() {
        val target = record("same")
        val snapshot = SavedDeviceStatus.checking(target)
        assertEquals(SavedDeviceStatusPhase.CHECKING, snapshot.phase)
        assertEquals(0L, snapshot.checkedAt)
        assertTrue(snapshot.matches(target))
        assertFalse(snapshot.matches(target.copy(owner = "other")))
        assertFalse(snapshot.matches(target.copy(kind = SavedDeviceKind.WATER.wire)))
        assertFalse(snapshot.matches(target.copy(deviceId = "other")))
        assertNotEquals(SavedDeviceStatus.key(target), SavedDeviceStatus.key(target.copy(kind = "water")))
    }

    @Test fun batchNeverExceedsTwoRequestsAndOneFailureDoesNotStopOtherRows() = runTest {
        val records = (1..4).map { record(it.toString()) }
        val gates = records.associate { it.deviceId to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val results = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var active = 0
        var maximum = 0
        val job = launch {
            querySavedDeviceStatuses(records, { true }, query = {
                active++
                maximum = maxOf(maximum, active)
                started += it.deviceId
                try {
                    gates.getValue(it.deviceId).await()
                    if (it.deviceId == "1") throw IOException("one row failed")
                    SavedDeviceStatus.checking(it).copy(phase = SavedDeviceStatusPhase.UNKNOWN, checkedAt = 100)
                } finally { active-- }
            }, onResult = { record, _ -> results += record.deviceId },
                onFailure = { record, _ -> failed += record.deviceId })
        }
        runCurrent()
        assertEquals(listOf("1", "2"), started)
        gates.getValue("1").complete(Unit)
        runCurrent()
        assertEquals(listOf("1", "2", "3"), started)
        gates.values.forEach { it.complete(Unit) }
        job.join()
        assertEquals(2, maximum)
        assertEquals(listOf("1"), failed)
        assertEquals(setOf("2", "3", "4"), results.toSet())
    }

    @Test fun dismissedOrDeletedRowsCannotPublishLateSuccessOrAuthenticationFailure() = runTest {
        val records = listOf(record("a"), record("b"))
        val gates = records.associate { it.deviceId to CompletableDeferred<Unit>() }
        var generationCurrent = true
        val callbacks = mutableListOf<String>()
        val job = launch {
            querySavedDeviceStatuses(records, { generationCurrent }, query = {
                gates.getValue(it.deviceId).await()
                if (it.deviceId == "b") throw SessionExpired()
                SavedDeviceStatus.checking(it)
            }, onResult = { _, _ -> callbacks += "success" }, onFailure = { _, _ -> callbacks += "error" })
        }
        runCurrent()
        generationCurrent = false
        gates.values.forEach { it.complete(Unit) }
        job.join()
        assertTrue(callbacks.isEmpty())
        var queried = false
        querySavedDeviceStatuses(records, { false }, query = { queried = true; SavedDeviceStatus.checking(it) },
            onResult = { _, _ -> fail("stale success") }, onFailure = { _, _ -> fail("stale failure") })
        assertFalse(queried)
    }

    @Test fun cancellationStopsQueuedRowsAndIsNeverTurnedIntoAnError() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val job = launch {
            querySavedDeviceStatuses((1..3).map { record(it.toString()) }, { true }, query = {
                started += it.deviceId
                gate.await()
                SavedDeviceStatus.checking(it)
            }, onResult = { _, _ -> fail("cancelled success") }, onFailure = { _, _ -> fail("cancelled error") })
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("1", "2"), started)
    }
}
