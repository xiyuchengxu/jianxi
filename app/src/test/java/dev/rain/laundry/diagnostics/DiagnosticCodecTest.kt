package dev.rain.laundry.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticCodecTest {
    private fun entry(time: Long = 1_700_000_000_000) = DiagnosticEntry(time,
        DiagnosticOperation.ORDER_QUERY, DiagnosticStatus.SUCCESS, 123, null)

    @Test fun newestTwoHundredResultsSurviveRolloverAndReload() {
        var history = emptyList<DiagnosticEntry>()
        repeat(350) { history = DiagnosticCodec.append(history, entry(it.toLong())) }
        assertEquals(200, history.size)
        assertEquals(150L, history.first().timeMs)
        assertEquals(349L, history.last().timeMs)
        assertEquals(history, DiagnosticCodec.decode(DiagnosticCodec.encode(history)))
    }

    @Test fun everyPredefinedOperationAndStatusRoundTripsWithoutFreeText() {
        val values = DiagnosticOperation.entries.flatMap { operation ->
            DiagnosticStatus.entries.map { status -> DiagnosticEntry(1_700_000_000_000, operation, status) }
        }.takeLast(DiagnosticCodec.MAX_ENTRIES)
        assertEquals(values, DiagnosticCodec.decode(DiagnosticCodec.encode(values)))
    }

    @Test fun sensitiveTextOrAdditionalFieldsCannotBecomeAnExportedEvent() {
        val malicious = "v1\n" +
            "1700000000000|ORDER_QUERY|SUCCESS|123|-\n" +
            "1700000000001|https://server.invalid/private|FAILED|1|401\n" +
            "1700000000002|LOGIN|Authorization: private-session|2|-\n" +
            "1700000000003|LOGIN|FAILED|3|401|server-message\n" +
            "1700000000004|LOGIN|FAILED|private-body|401\n"
        val decoded = DiagnosticCodec.decode(malicious)
        assertEquals(listOf(entry()), decoded)
        val exported = DiagnosticCodec.export(decoded)
        assertFalse(exported.contains("private"))
        assertFalse(exported.contains("server-message"))
        assertFalse(exported.contains("Authorization"))
        assertTrue(exported.contains("ORDER_QUERY\tSUCCESS\t123\t-"))
    }

    @Test fun corruptedNumericFieldsAreRejectedInsteadOfDisplayed() {
        val rows = listOf(
            "-1|LOGIN|FAILED|1|401",
            "9999999999999999999|LOGIN|FAILED|1|401",
            "1700000000000|LOGIN|FAILED|-1|401",
            "1700000000000|LOGIN|FAILED|3600001|401",
            "1700000000000|LOGIN|FAILED|1|-1",
            "1700000000000|LOGIN|FAILED|1|1000000",
            "1700000000000|LOGIN|FAILED|1.5|401"
        )
        rows.forEach { assertTrue(it, DiagnosticCodec.decode("v1\n$it\n").isEmpty()) }
    }

    @Test fun emptyUnknownOrOversizedStorageDoesNotLeakRawContent() {
        for (raw in listOf(null, "", "v2\n", "{private-json}", "v1\n" + "x".repeat(65_536))) {
            assertTrue(DiagnosticCodec.decode(raw).isEmpty())
        }
    }

    @Test fun recorderBoundsTimesDurationsAndErrorCodes() {
        val normalized = DiagnosticCodec.entry(-1, DiagnosticOperation.LOGIN, DiagnosticStatus.HTTP_ERROR,
            Long.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(0L, normalized.timeMs)
        assertEquals(DiagnosticCodec.MAX_DURATION_MS, normalized.durationMs)
        assertNull(normalized.errorCode)
        val negative = DiagnosticCodec.entry(1, DiagnosticOperation.LOGIN, DiagnosticStatus.FAILED, -2, -1)
        assertNull(negative.durationMs)
        assertNull(negative.errorCode)
    }

    @Test fun textExportUsesUtcFixedColumnNamesAndOnlyBoundedRecords() {
        val history = List(250) { entry(1_700_000_000_000 + it) }
        val exported = DiagnosticCodec.export(history)
        assertTrue(exported.startsWith("Jianxi local diagnostics v1\ntime_utc\toperation\tstatus\tduration_ms\terror_code\n"))
        assertEquals(202, exported.lineSequence().filter { it.isNotEmpty() }.count())
        assertTrue(exported.contains("2023-11-14T22:13:20.050Z\tORDER_QUERY\tSUCCESS\t123\t-"))
        assertFalse(exported.contains("2023-11-14T22:13:20Z\t"))
    }

    @Test fun exportedPublicEntriesStillPassTheFixedSchema() {
        val invalid = entry().copy(timeMs = Long.MAX_VALUE, errorCode = -42)
        assertEquals(DiagnosticCodec.export(emptyList()), DiagnosticCodec.export(listOf(invalid)))
    }
}
