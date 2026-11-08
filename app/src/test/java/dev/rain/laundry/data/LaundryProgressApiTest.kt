package dev.rain.laundry.data

import dev.rain.laundry.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class LaundryProgressApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LaundryApi

    @Before fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val testUrl = server.url("/").newBuilder().host("127.0.0.1").build()
        api = LaundryApi({ "example-token" }, "Test", baseUrl = testUrl, now = { 1_700_000_000_000L })
    }

    @After fun stop() { server.shutdown() }

    private fun order() = LocalOrder("owner", "洗衣机", "标准洗", "4.00", PayMethod.ALIPAY,
        OrderPhase.PAID, orderId = "order-1", orderNo = "trade-1")

    @Test fun orderDetailsReturnsPaymentStatusAndProgressInOneLegacyQuery() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"code":0,"data":{"id":"order-1","orderNo":"trade-1","orderStatus":2,
             "machineId":"machine-1","imei":"imei-1","markMinutes":"45",
             "beginTime":"2026-01-01 10:00:00","completeTime":"2026-01-01 10:45:00"}}
        """.trimIndent()))
        val details = api.orderDetails(order())
        assertEquals(RemoteOrderStatus.IN_USE, details.status)
        val progress = details.laundryProgress!!
        assertEquals(45, progress.totalMinutes)
        assertEquals("machine-1", progress.deviceId)
        assertEquals(LaundryRunState.RUNNING, progress.runState)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/order/detail", request.path)
        assertEquals("orderId=order-1&token=example-token", request.body.readUtf8())
    }

    @Test fun legacyOrderStatusStillWorksWhenTimingFieldsAreMalformed() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"code":0,"data":{"id":"order-1","orderStatus":2,"markMinutes":"oops",
             "completeTime":"whenever"}}
        """))
        assertEquals(RemoteOrderStatus.IN_USE, api.orderStatus(order()))
        assertEquals(1, server.requestCount)
    }

    @Test fun aDifferentOrderIdIsRejectedBeforeAnyProgressIsReturned() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"code":0,"data":{"id":"other-order","orderNo":"trade-1","orderStatus":2,
             "markMinutes":"45","completeTime":"2026-01-01 10:45:00"}}
        """))
        assertTrue(runCatching { api.orderDetails(order()) }.exceptionOrNull() is ClientError)
    }

    @Test fun aMatchingIdCannotHideAConflictingOrderNumberOrDevice() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"order-1","orderNo":"other-trade","orderStatus":2}}"""))
        assertTrue(runCatching { api.orderDetails(order()) }.exceptionOrNull() is ClientError)
        val previous = LaundryProgress("order-1", "trade-1", deviceId = "machine-1",
            orderStatus = RemoteOrderStatus.IN_USE, updatedAt = 0)
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"order-1","machineId":"other-machine","orderStatus":2}}"""))
        assertTrue(runCatching { api.orderDetails(order().copy(laundryProgress = previous)) }.exceptionOrNull() is ClientError)
    }

    @Test fun completedLaundryNeedsNoOptionalTimestampAndMeteredOrdersHaveNoLaundryProgress() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"order-1","orderStatus":3}}"""))
        assertTrue(api.orderDetails(order()).laundryProgress!!.completionConfirmed)
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"order-1","orderStatus":2,"orderType":3,"markMinutes":"30"}}"""))
        assertNull(api.orderDetails(order()).laundryProgress)
    }

    @Test fun orderNumberFallbackBindsProgressToTheReturnedOrder() = runTest {
        val byNumber = order().copy(orderId = "", orderNo = "trade-1")
        server.enqueue(MockResponse().setBody("""
            {"code":0,"data":{"id":"order-1","orderNo":"trade-1","orderStatus":2,
             "markMinutes":"45","completeTime":"2026-01-01 10:45:00"}}
        """))
        val details = api.orderDetails(byNumber)
        assertEquals("order-1", details.laundryProgress!!.orderId)
        assertEquals("trade-1", details.laundryProgress!!.orderNo)

        server.enqueue(MockResponse().setBody("""
            {"code":0,"data":{"id":"order-2","orderNo":"other-trade","orderStatus":2,
             "markMinutes":"45","completeTime":"2026-01-01 10:45:00"}}
        """))
        assertTrue(runCatching { api.orderDetails(byNumber) }.exceptionOrNull() is ClientError)
    }

    @Test fun nonActiveStatusesDoNotExposeALaundryRunSnapshot() = runTest {
        for (status in listOf(RemoteOrderStatus.UNPAID, RemoteOrderStatus.CLOSED,
            RemoteOrderStatus.REFUNDING, RemoteOrderStatus.REFUNDED)) {
            server.enqueue(MockResponse().setBody("""
                {"code":0,"data":{"id":"order-1","orderStatus":${status.wire},
                 "markMinutes":"45","completeTime":"2026-01-01 10:45:00"}}
            """))
            val details = api.orderDetails(order())
            assertEquals(status, details.status)
            assertNull(details.laundryProgress)
        }
    }
}
