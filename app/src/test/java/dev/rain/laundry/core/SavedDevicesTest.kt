package dev.rain.laundry.core

import org.junit.Assert.*
import org.junit.Test

class SavedDevicesTest {
    @Test fun overflowingTimestampIsSkippedWithoutLosingHealthyRecords() {
        val healthy = com.google.gson.Gson().toJson(record(id = "healthy", time = 20))
        val overflow = healthy.replace("\"lastUsedAt\":20", "\"lastUsedAt\":1e99999")
        val decoded = SavedDevices.decode("[$overflow,$healthy]")
        assertEquals(listOf("healthy"), decoded.map { it.deviceId })
        assertEquals(20L, decoded.single().lastUsedAt)
    }

    private fun record(
        owner: String = "owner",
        kind: SavedDeviceKind = SavedDeviceKind.LAUNDRY,
        id: String = "device-1",
        codeKey: String = "IMEI",
        codeValue: String = "ABCD1234",
        name: String = "测试洗衣机",
        note: String = "",
        time: Long = 1L
    ) = SavedDevice(owner, kind.wire, id, codeKey, codeValue, name, note, time)

    private fun laundry(id: String, name: String = "测试洗衣机") = ScannedDevice.Laundry(
        Device(id, name, "LOCAL-$id", "00", "shop", "org", 0,
            listOf(WashMode("sku-1", "标准洗", "4.00")))
    )

    private fun water(id: String, name: String = "测试饮水机") = ScannedDevice.Water(
        WaterDevice(id, name, "LOCAL-$id", "shop", "org",
            listOf(WaterOutlet("outlet-1", "常温水")), true)
    )

    private fun dryer(id: String, name: String = "测试烘干机") = ScannedDevice.Dryer(
        Device(id, name, "LOCAL-$id", "02", "shop", "org", 0,
            listOf(WashMode("sku-d", "标准烘", "3.00",
                dryerDurations = listOf(DryerDuration(60, "3.00")))))
    )

    @Test fun mergeDeduplicatesByKindAndDeviceIdAndPreservesTheUserNote() {
        val old = record(id = "same", note = "宿舍楼下", time = 10)
        val fresh = record(id = "same", codeValue = "NEW12345", name = "新的平台名称", time = 20)
        val merged = SavedDevices.merge("owner", listOf(old), fresh)

        assertEquals(1, merged.size)
        assertEquals("NEW12345", merged.single().codeValue)
        assertEquals("新的平台名称", merged.single().name)
        assertEquals("宿舍楼下", merged.single().note)
        assertEquals(20L, merged.single().lastUsedAt)

        val waterSameId = record(kind = SavedDeviceKind.WATER, id = "same", time = 15)
        assertEquals(2, SavedDevices.merge("owner", listOf(fresh, waterSameId)).size)
    }

    @Test fun historyKeepsOnlyTheTwentyMostRecentDevices() {
        val records = (1..25).map { record(id = "device-$it", time = it.toLong()) }
        val merged = SavedDevices.merge("owner", records)

        assertEquals(20, merged.size)
        assertEquals((25 downTo 6).map { it.toLong() }, merged.map { it.lastUsedAt })
    }

    @Test fun recordsAreOwnerScopedAndBadKindsIdsCodesOrTimesAreSkipped() {
        val valid = record(id = "valid", time = 1)
        val otherOwner = record(owner = "other", id = "other", time = 2)
        val unknownKind = record(id = "unknown-kind", time = 3).copy(kind = "unknown")
        val wrongCodeKey = record(id = "wrong-key", time = 4).copy(codeKey = "imei")
        val badCodeValue = record(id = "bad-value", time = 5).copy(codeValue = "x")
        val blankId = record(id = "", time = 6)
        val noTime = record(id = "no-time", time = 0)
        val merged = SavedDevices.merge("owner",
            listOf(otherOwner, unknownKind, wrongCodeKey, badCodeValue, blankId, noTime, valid))

        assertEquals(listOf("valid"), merged.map { it.deviceId })
        assertTrue(SavedDevices.merge("", listOf(valid)).isEmpty())
    }

    @Test fun defensiveDecodeSkipsMissingFieldsAndMalformedRows() {
        val raw = """
            [
              {"owner":"owner","kind":"laundry","deviceId":"valid","codeKey":"IMEI","codeValue":"ABCD1234","name":"洗衣机","note":"","lastUsedAt":10},
              {"owner":"owner","kind":"laundry","deviceId":"missing-code","codeKey":"IMEI","lastUsedAt":11},
              {"owner":"owner","kind":"unknown","deviceId":"unknown","codeKey":"IMEI","codeValue":"ABCD1234","lastUsedAt":12},
              {"owner":"owner","kind":"laundry","deviceId":"bad-code","codeKey":"imei","codeValue":"ABCD1234","lastUsedAt":13},
              {"owner":null,"kind":"laundry","deviceId":"null-owner","codeKey":"IMEI","codeValue":"ABCD1234","lastUsedAt":14},
              "not-an-object"
            ]
        """.trimIndent()

        val decoded = SavedDevices.decode(raw)
        assertEquals(3, decoded.size)
        assertEquals(listOf("valid"), SavedDevices.merge("owner", decoded).map { it.deviceId })
        assertTrue(SavedDevices.decode("not-json").isEmpty())
        assertTrue(SavedDevices.decode("""{"not":"a list"}""").isEmpty())
    }

    @Test fun identityMustMatchBothKindAndStableDeviceId() {
        val savedLaundry = record(id = "laundry-a")
        assertTrue(SavedDevices.matches(savedLaundry, laundry("laundry-a")))
        assertFalse(SavedDevices.matches(savedLaundry, laundry("laundry-b")))
        assertFalse(SavedDevices.matches(savedLaundry, water("laundry-a")))

        val savedWater = record(kind = SavedDeviceKind.WATER, id = "water-a")
        assertTrue(SavedDevices.matches(savedWater, water("water-a")))
        assertFalse(SavedDevices.matches(savedWater, water("water-b")))
        assertFalse(SavedDevices.matches(savedWater, laundry("water-a")))

        val savedDryer = record(kind = SavedDeviceKind.DRYER, id = "dryer-a")
        assertTrue(SavedDevices.matches(savedDryer, dryer("dryer-a")))
        assertFalse(SavedDevices.matches(savedDryer, dryer("dryer-b")))
        assertFalse(SavedDevices.matches(savedDryer, laundry("dryer-a")))
        assertFalse(SavedDevices.matches(savedDryer, water("dryer-a")))
    }

    @Test fun kindScopedEntriesExposeOnlyTheirOwnWorkflowWithoutDroppingHistory() {
        val laundry = record(id = "laundry-a", time = 3)
        val water = record(kind = SavedDeviceKind.WATER, id = "water-a", time = 2)
        val unknownKind = record(id = "unknown-kind", time = 1).copy(kind = "other")
        val records = listOf(water, unknownKind, laundry)

        assertEquals(listOf("laundry-a"),
            SavedDevices.ofKind(records, SavedDeviceKind.LAUNDRY).map { it.deviceId })
        assertEquals(listOf("water-a"),
            SavedDevices.ofKind(records, SavedDeviceKind.WATER).map { it.deviceId })
        val dryer = record(kind = SavedDeviceKind.DRYER, id = "dryer-a", time = 4)
        val all = records + dryer
        assertEquals(listOf("dryer-a"), SavedDevices.ofKind(all, SavedDeviceKind.DRYER).map { it.deviceId })
        assertTrue(SavedDevices.ofKind(emptyList(), SavedDeviceKind.LAUNDRY).isEmpty())
        // Filtering is presentation-only; the shared, total-capacity history is untouched.
        assertEquals(4, all.size)
        assertEquals(listOf("water-a", "unknown-kind", "laundry-a", "dryer-a"), all.map { it.deviceId })
    }

    @Test fun staleResponsesAreRejectedAfterOwnerTokenOrRevisionChanges() {
        assertTrue(SavedDevices.acceptsStoredResponse(
            "owner", "token", "owner", "token", "owner", true))
        assertFalse(SavedDevices.acceptsStoredResponse(
            "owner", "token", "other", "token", "owner", true))
        assertFalse(SavedDevices.acceptsStoredResponse(
            "owner", "token", "owner", "other-token", "owner", true))
        assertFalse(SavedDevices.acceptsStoredResponse(
            "owner", "token", "owner", "token", "other", true))
        assertFalse(SavedDevices.acceptsStoredResponse(
            "owner", "token", "owner", "token", "owner", false))
        assertFalse(SavedDevices.acceptsStoredResponse(
            "owner", "", "owner", "", "owner", true))
    }

    @Test fun scanCandidateStoresOnlyVerifiedIdentityAndLeavesTheUserNoteToTheVault() {
        val candidate = SavedDevices.fromScan(
            "owner", DeviceCode("SN", "ABCD1234"), laundry("laundry-a", "平台洗衣机"), 100L)

        assertEquals("owner", candidate.owner)
        assertEquals("laundry", candidate.kind)
        assertEquals("laundry-a", candidate.deviceId)
        assertEquals("SN", candidate.codeKey)
        assertEquals("ABCD1234", candidate.codeValue)
        assertEquals("平台洗衣机", candidate.name)
        assertEquals("", candidate.note)
        assertEquals(100L, candidate.lastUsedAt)
        assertEquals(SavedDeviceKind.LAUNDRY, candidate.savedKind)
        // The merge test above proves that an existing user note survives a fresh scan.
        assertEquals("宿舍楼下", SavedDevices.merge("owner",
            listOf(record(id = "laundry-a", note = "宿舍楼下", time = 1)), candidate).single().note)

        val waterCandidate = SavedDevices.fromScan(
            "owner", DeviceCode("NQT", "WXYZ1234"), water("water-a"), 101L)
        assertEquals("water", waterCandidate.kind)
        assertEquals("water-a", waterCandidate.deviceId)
        assertEquals("测试饮水机", waterCandidate.name)

        val dryerCandidate = SavedDevices.fromScan(
            "owner", DeviceCode("IMEI", "DRYER123"), dryer("dryer-a"), 102L)
        assertEquals("dryer", dryerCandidate.kind)
        assertEquals("dryer-a", dryerCandidate.deviceId)
    }

    @Test fun codeValidationAndNoteNormalizationAreStrictAndBounded() {
        assertTrue(SavedDevices.validCode(DeviceCode("IMEI", "ABCD")))
        assertTrue(SavedDevices.validCode(DeviceCode("NQT", "abcd-1234")))
        assertFalse(SavedDevices.validCode(DeviceCode("imei", "ABCD")))
        assertFalse(SavedDevices.validCode(DeviceCode("IMEI", "abc")))
        assertFalse(SavedDevices.validCode(DeviceCode("IMEI", "ABCD EFGH")))

        assertEquals("宿舍楼下", SavedDevices.normalizeNote("  宿舍\n楼下  "))
        assertTrue(SavedDevices.normalizeNote("x".repeat(100)).length <= SavedDevices.MAX_NOTE_LENGTH)
    }
}
