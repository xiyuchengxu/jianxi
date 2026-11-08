package dev.rain.laundry.experiment

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.rain.laundry.data.LaundryApi
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DrumCleanInspectorTest {
    private fun inspect(raw: String, scan: String = "DEMO-MACHINE-A", now: Long = DrumCleanFixtures.nowMs,
        force: Boolean = true) = DrumCleanInspector.inspect(raw, DrumCleanFixtures.APPOINTMENT_ID,
        DrumCleanFixtures.GOODS_ID, scan, now, force)

    @Test fun forceVisibilityDoesNotChangeAnyFixtureEligibilityAndWritesReviewableMatrix() {
        val matrix = DrumCleanFixtures.cases.map { fixture ->
            val normal = DrumCleanFixtures.inspect(fixture, false)
            val forced = DrumCleanFixtures.inspect(fixture, true)
            assertEquals(fixture.id, fixture.expected, normal.outcome)
            assertEquals(fixture.id, normal.copy(entryVisible = true), forced)
            assertFalse(fixture.id, normal.canStart)
            assertFalse(fixture.id, forced.canStart)
            mapOf("id" to fixture.id, "label" to fixture.label, "source" to "synthetic-only",
                "scan" to fixture.scan, "response" to JsonParser.parseString(fixture.response),
                "normal" to normal, "forced" to forced, "canStart" to forced.canStart)
        }
        val report = File("build/reports/drum-clean-experiment/matrix.json")
        requireNotNull(report.parentFile).mkdirs()
        report.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(matrix))
        println("DRUM_CLEAN_MATRIX ${report.absolutePath}: ${matrix.size} synthetic cases; no real service or device used")
    }

    @Test fun hiddenEntryCanBeShownButCannotInventASku() {
        val fixture = DrumCleanFixtures.cases.first { it.id == "hidden" }
        assertFalse(DrumCleanFixtures.inspect(fixture, false).entryVisible)
        val forced = DrumCleanFixtures.inspect(fixture, true)
        assertTrue(forced.entryVisible)
        assertEquals(DrumCleanOutcome.MISSING_SKU, forced.outcome)
        assertNull(forced.amount)
    }

    @Test fun innerListVisibilityDoesNotImplyOuterDetailEligibility() {
        val value = DrumCleanFixtures.inspect(DrumCleanFixtures.cases.first { it.id == "inner-only" }, false)
        assertTrue(value.originalListVisible)
        assertFalse(value.originalDetailsVisible)
        assertEquals(DrumCleanOutcome.MISSING_SKU, value.outcome)
    }

    @Test fun zeroAndPaidRoutesRemainLocalAndCannotStart() {
        val zero = inspect(DrumCleanFixtures.base)
        assertEquals(DrumCleanOutcome.LOCAL_ZERO_ROUTE, zero.outcome)
        assertEquals("0.00", zero.amount)
        val paid = inspect(DrumCleanFixtures.base.replace("\"0.00\"", "\"1.50\""))
        assertEquals(DrumCleanOutcome.LOCAL_PAID_ROUTE, paid.outcome)
        assertEquals("1.50", paid.amount)
        assertFalse(zero.canStart || paid.canStart)
    }

    @Test fun serverRejectionWinsEvenIfResponseAlsoContainsPlausibleSkuData() {
        val result = inspect(DrumCleanFixtures.base.replace("\"code\":0", "\"code\":403"))
        assertTrue(result.entryVisible)
        assertFalse(result.originalListVisible)
        assertFalse(result.originalDetailsVisible)
        assertEquals(DrumCleanOutcome.SERVER_REJECTED, result.outcome)
        assertNull(result.amount)
    }

    @Test fun eligibilityExpiresExactlyAtDeadlineAndCannotReviveOnRedisplay() {
        val deadline = DrumCleanFixtures.nowMs + 30 * 60_000
        assertEquals(DrumCleanOutcome.LOCAL_ZERO_ROUTE, inspect(DrumCleanFixtures.base, now = deadline - 1).outcome)
        for (force in listOf(false, true)) assertEquals(DrumCleanOutcome.EXPIRED,
            inspect(DrumCleanFixtures.base, now = deadline, force = force).outcome)
    }

    @Test fun datesAreStrictAndShanghaiBasedRegardlessOfHostTimezone() {
        for (date in listOf("", "tomorrow", "2026-02-30 12:30:00", "2026-11-07 25:00:00", "2026-11-07 12:30:00junk")) {
            val raw = DrumCleanFixtures.base.replace("2026-11-07 12:30:00", date)
            assertEquals(date, DrumCleanOutcome.INVALID_TIME, inspect(raw).outcome)
        }
        assertEquals(DrumCleanOutcome.LOCAL_ZERO_ROUTE, inspect(DrumCleanFixtures.base).outcome)
    }

    @Test fun everySupportedMachineAliasMatchesExactly() {
        for (alias in listOf("DEMO-MACHINE-A", "DEMO-IMEI-A", "DEMO-NQT-A"))
            assertEquals(DrumCleanOutcome.LOCAL_ZERO_ROUTE, inspect(DrumCleanFixtures.base, scan = alias).outcome)
        for (wrong in listOf("", "DEMO-MACHINE-B", "demo-machine-a", "DEMO-MACHINE-A "))
            assertEquals(DrumCleanOutcome.MACHINE_MISMATCH, inspect(DrumCleanFixtures.base, scan = wrong).outcome)
    }

    @Test fun aBlankMachineIdentityCannotMatchAnEmptyScan() {
        val raw = DrumCleanFixtures.base.replace("DEMO-IMEI-A", "").replace("DEMO-MACHINE-A", "").replace("DEMO-NQT-A", "")
        assertEquals(DrumCleanOutcome.MACHINE_MISMATCH, inspect(raw, scan = "").outcome)
    }

    @Test fun badMoneyIsNeverTreatedAsFree() {
        for (price in listOf("", "oops", "-1", "NaN", "Infinity", "1e2", "0.001", " 0.00", "9999999", "00.00"))
            assertEquals(price, DrumCleanOutcome.INVALID_PRICE,
                inspect(DrumCleanFixtures.base.replace("\"0.00\"", "\"$price\"")).outcome)
    }

    @Test fun unknownRestrictionCannotGrantEligibility() {
        for (flag in listOf("null", "0", "\"false\"", "{}"))
            assertEquals(DrumCleanOutcome.UNKNOWN_RESTRICTION,
                inspect(DrumCleanFixtures.base.replace("\"tzjFlag\":false", "\"tzjFlag\":$flag")).outcome)
    }

    @Test fun appointmentAndGoodsAreBoundBeforeRouting() {
        for (raw in listOf(DrumCleanFixtures.base.replace("demo-appointment", "other"),
            DrumCleanFixtures.base.replace("demo-goods", "other")))
            assertEquals(DrumCleanOutcome.IDENTITY_MISMATCH, inspect(raw).outcome)
        val root = JsonParser.parseString(DrumCleanFixtures.base).asJsonObject
        root.getAsJsonObject("data").getAsJsonArray("skuItems")[0].asJsonObject.addProperty("goodsId", "other")
        assertEquals(DrumCleanOutcome.INVALID_SKU, inspect(root.toString()).outcome)
    }

    @Test fun multipleItemsCannotUseFirstZeroPriceToHideAnotherCharge() {
        val root = JsonParser.parseString(DrumCleanFixtures.base).asJsonObject
        val items = root.getAsJsonObject("data").getAsJsonArray("skuItems")
        items.add(items[0].deepCopy().also { it.asJsonObject.addProperty("price", "5.00") })
        assertEquals(DrumCleanOutcome.INVALID_SKU, inspect(root.toString()).outcome)
    }

    @Test fun malformedResponsesAndUnknownSchemasStopInspection() {
        for (raw in listOf("not json", "null", "[]", "{}", "{\"code\":0,\"data\":[]}", "{\"code\":0,\"data\":{}}"))
            assertEquals(raw, DrumCleanOutcome.INVALID_RESPONSE, inspect(raw).outcome)
    }

    @Test fun ordinaryLaundryStillAcceptsNamedCleanSkuAndHonorsScanAvailability() {
        val api = LaundryApi({ error("No credential should be accessed during parsing") }, "Offline")
        val response = JsonParser.parseString("""[
          {"skuId":"ordinary","name":"标准洗","price":"3.00"},
          {"skuId":"clean","name":"桶自洁","price":"0.00","scanAvailable":true},
          {"skuId":"unavailable","name":"筒自洁","price":"0.00","scanAvailable":false}
        ]""")
        assertEquals(listOf("ordinary", "clean"), api.parseModes(response).map { it.id })
        // The ordinary parser provides no evidence for the separate appointment protocol.
        assertEquals(DrumCleanOutcome.INVALID_RESPONSE, inspect(response.toString()).outcome)
    }
}
