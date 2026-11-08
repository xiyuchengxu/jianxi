package dev.rain.laundry.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LaundryProgressTest {
    private fun order() = LocalOrder("owner", "洗衣机", "标准洗", "4.00", PayMethod.ALIPAY,
        OrderPhase.PAID, orderId = "order-1", orderNo = "trade-1")

    private fun detail(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun realOrderDetailShapeUsesMinutesAndShanghaiServerTime() {
        val data = detail("""
            {"id":"order-1","orderNo":"trade-1","orderStatus":2,"machineId":"machine-1",
             "imei":"imei-1","markMinutes":"45","beginTime":"2026-01-01 10:00:00",
             "completeTime":"2026-01-01 10:45:00","finishTime":""}
        """)
        val progress = LaundryProgressProtocol.parse(data, order(), RemoteOrderStatus.IN_USE, 1_700_000_000_000L)!!
        assertEquals("order-1", progress.orderId)
        assertEquals("trade-1", progress.orderNo)
        assertEquals("machine-1", progress.deviceId)
        assertEquals("imei-1", progress.imei)
        assertEquals(45, progress.totalMinutes)
        assertEquals(LaundryRunState.RUNNING, progress.runState)
        assertEquals(LaundryProgressProtocol.parseTimestamp("2026-01-01 10:00:00"), progress.startedAt)
        assertEquals(LaundryProgressProtocol.parseTimestamp("2026-01-01 10:45:00"), progress.expectedEndAt)
        assertEquals(45 * 60_000L, progress.expectedEndAt!! - progress.startedAt!!)
        assertEquals(1_700_000_000_000L, progress.updatedAt)
        assertNull(progress.finishedAt)
        assertFalse(progress.completionConfirmed)

        // The server format is China local time, not UTC: eight hours apart.
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("2026-01-01 10:45:00")!!.time
        assertEquals(-8 * 60 * 60 * 1000L, progress.expectedEndAt!! - utc)
    }

    @Test fun missingAndMalformedTimingIsUnknownInsteadOfGuessed() {
        val data = detail("""
            {"id":"order-1","orderNo":"trade-1","orderStatus":2,"markMinutes":"abc",
             "beginTime":"not-a-time","completeTime":"2026-13-99 25:70:00","finishTime":""}
        """)
        val progress = LaundryProgressProtocol.parse(data, order(), RemoteOrderStatus.IN_USE, 10L)!!
        assertNull(progress.totalMinutes)
        assertNull(progress.startedAt)
        assertNull(progress.expectedEndAt)
        assertNull(progress.finishedAt)
        assertNull(progress.remainingMillis(0L))
        assertNull(progress.remainingFraction(0L))
        assertEquals(LaundryRunState.RUNNING, progress.runState)
    }

    @Test fun absentTimingDataStaysUnknownWithoutThrowing() {
        val data = detail("""{"id":"order-1","orderNo":"trade-1","orderStatus":2}""")
        val progress = LaundryProgressProtocol.parse(data, order(), RemoteOrderStatus.IN_USE, 10L)!!
        assertNull(progress.totalMinutes)
        assertNull(progress.expectedEndAt)
        assertNull(progress.remainingMillis(0L))
        assertEquals(LaundryRunState.RUNNING, progress.runState)
    }

    @Test fun countdownReachingZeroNeverCountsAsFinished() {
        val end = 1_000_000L
        val progress = LaundryProgress(
            orderId = "order-1", orderNo = "trade-1", totalMinutes = 10,
            expectedEndAt = end, orderStatus = RemoteOrderStatus.IN_USE, updatedAt = 0L
        )
        assertEquals(600_000L, progress.remainingMillis(end - 600_000L))
        assertEquals(0.5, progress.remainingFraction(end - 300_000L)!!.toDouble(), 0.0001)
        assertEquals(0L, progress.remainingMillis(end))
        assertEquals(0L, progress.remainingMillis(end + 3_600_000L))
        assertEquals(0.0, progress.remainingFraction(end + 1L)!!.toDouble(), 0.0001)
        assertEquals(LaundryRunState.RUNNING, progress.runState)
        assertFalse(progress.completionConfirmed)
    }

    @Test fun serverCompletedStateIsAuthoritativeEvenWhenOptionalFinishTimeIsMissing() {
        val progress = LaundryProgress("order-1", "trade-1", expectedEndAt = 5_000L,
            orderStatus = RemoteOrderStatus.COMPLETED, updatedAt = 0L)
        assertEquals(LaundryRunState.FINISHED, progress.runState)
        assertTrue(progress.completionConfirmed)
        assertNull(progress.finishedAt)
    }

    @Test fun rawMeteredOrderIsExcludedEvenWhenOldLocalCategoryWasMissing() {
        val data = detail("""{"id":"order-1","orderStatus":2,"orderType":3,"markMinutes":"45"}""")
        assertNull(LaundryProgressProtocol.parse(data, order(), RemoteOrderStatus.IN_USE, 10L))
    }

    @Test fun waterOrdersNeverReadLaundryTimingFields() {
        val water = order().copy(category = "04")
        val data = detail("""
            {"id":"order-1","orderNo":"trade-1","orderStatus":2,"markMinutes":"20",
             "completeTime":"2026-01-01 10:20:00"}
        """)
        assertNull(LaundryProgressProtocol.parse(data, water, RemoteOrderStatus.IN_USE, 10L))
    }

    @Test fun progressSnapshotSurvivesGsonAndMissingFieldDefaultsToNull() {
        val progress = LaundryProgress(
            orderId = "order-1", orderNo = "trade-1", deviceId = "machine-1",
            imei = "imei-1", totalMinutes = 45,
            startedAt = LaundryProgressProtocol.parseTimestamp("2026-01-01 10:00:00"),
            expectedEndAt = LaundryProgressProtocol.parseTimestamp("2026-01-01 10:45:00"),
            orderStatus = RemoteOrderStatus.IN_USE, updatedAt = 123L
        )
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(order().copy(laundryProgress = progress)), LocalOrder::class.java)
        assertEquals(progress, restored.laundryProgress)
        assertNull(gson.fromJson(gson.toJson(order()), LocalOrder::class.java).laundryProgress)
    }
}
