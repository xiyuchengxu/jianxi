package dev.rain.laundry.data

import com.google.gson.JsonParser
import dev.rain.laundry.core.*
import dev.rain.laundry.diagnostics.DiagnosticOperation
import dev.rain.laundry.diagnostics.DiagnosticStatus
import dev.rain.laundry.diagnostics.DiagnosticsLog
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.URLDecoder

class NoPointsPaymentApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LaundryApi
    private val order = LocalOrder("owner", "device", "mode", "4.00", PayMethod.ALIPAY,
        OrderPhase.CREATED, orderId = "id", orderNo = "no")

    @Before fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = LaundryApi({ "test-token" }, "Test", baseUrl = server.url("/")
            .newBuilder().host("127.0.0.1").build())
    }
    @After fun stop() { server.shutdown() }
    private fun reply(data: String) { server.enqueue(MockResponse().setBody("""{"code":0,"data":$data}""")) }
    private fun fields(request: RecordedRequest) = request.body.readUtf8().split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test fun everyPaymentMethodAndDeviceChecksTheBillBeforePayment() = runTest {
        for (category in listOf("00", "02", "04")) for (method in PayMethod.entries) {
            reply("""{"id":"id","orderNo":"no","payPrice":"4.00","promotionList":[]}""")
            reply(if (method == PayMethod.ALIPAY) """{"prepayParam":"single-payment-payload"}""" else "true")
            val target = order.copy(category = category, payMethod = method)
            if (method == PayMethod.ALIPAY) assertEquals("single-payment-payload", api.prepay(target))
            else api.balancePay(target)
            assertEquals(DiagnosticStatus.SUCCESS, DiagnosticsLog.entries.value.last {
                it.operation == DiagnosticOperation.PAYMENT_CHECK
            }.status)
            val check = server.takeRequest()
            assertEquals("/order/detail", check.path)
            assertEquals("id", fields(check)["orderId"])
            val payment = server.takeRequest()
            assertEquals(if (method == PayMethod.ALIPAY) "/pay/unify/prePay" else "/pay/unify/pay", payment.path)
            assertEquals(method.wire, fields(payment)["payType"])
        }
        assertEquals(12, server.requestCount)
    }

    @Test fun actualPointsBlockBothPrepayAndBalanceDebit() = runTest {
        for (method in PayMethod.entries) {
            reply("""{"id":"id","orderNo":"no","payPrice":"4.00","promotionList":[{"promotionType":8,"discountAmount":"1.00"}]}""")
            val target = order.copy(payMethod = method)
            val result = runCatching { if (method == PayMethod.ALIPAY) api.prepay(target) else api.balancePay(target) }
            assertTrue(result.exceptionOrNull() is ClientError)
            assertEquals(DiagnosticStatus.REJECTED, DiagnosticsLog.entries.value.last {
                it.operation == DiagnosticOperation.PAYMENT_CHECK
            }.status)
            assertEquals("/order/detail", server.takeRequest().path)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun uncertainOrChangedBillNeverReachesPaymentEndpoint() = runTest {
        for (data in listOf("null", """{"id":"id","orderNo":"no","payPrice":"4.00"}""",
            """{"id":"different","orderNo":"no","payPrice":"4.00","promotionList":[]}""",
            """{"id":"id","orderNo":"no","payPrice":"3.50","promotionList":[]}""")) {
            reply(data)
            assertTrue(runCatching { api.prepay(order) }.exceptionOrNull() is ClientError)
            assertEquals(DiagnosticStatus.INVALID_RESPONSE, DiagnosticsLog.entries.value.last {
                it.operation == DiagnosticOperation.PAYMENT_CHECK
            }.status)
            assertEquals("/order/detail", server.takeRequest().path)
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun fullPriceEvidenceSupportsOmittedOptionalListWithoutInventingPromotionParameters() = runTest {
        reply("""{"id":"id","orderNo":"no","payPrice":"4","markPrice":"4.00","discountPrice":"0"}""")
        reply("""{"prepayParam":"payload"}""")
        assertEquals("payload", api.prepay(order.copy(thirdChannel = "route", outVoucherId = "voucher")))
        server.takeRequest()
        val payment = fields(server.takeRequest())
        assertEquals("route", payment["thirdChannel"])
        assertEquals("voucher", payment["outVoucherId"])
        assertFalse(payment.containsKey("promotions"))
        assertFalse(payment.containsKey("useIntegral"))
    }

    @Test fun createNeverForwardsAnyPointsSelectionFromAQuote() = runTest {
        val device = Device("g", "device", "imei", "00", "shop", "org", 0, emptyList())
        val quote = Quote(device, WashMode("sku", "wash", "4"), "4.00",
            PurchaseRules.items(device, WashMode("sku", "wash", "4")), 0)
        reply("""{"orderId":"id","tradeNo":"no"}""")
        api.create(quote, LocationFix(30.0, 120.0))
        val request = server.takeRequest()
        val data = fields(request)
        assertEquals("/trade/create", request.path)
        val promotions = JsonParser.parseString(data["promotions"]).asJsonArray
        assertEquals(listOf("4"), promotions.map { it.asJsonObject.text("promotionType") })
        assertTrue(promotions.none { it.asJsonObject.text("promotionType") == "8" })
    }

    @Test fun prepayResponseWithPointsDoesNotReturnTheSdkPayload() = runTest {
        reply("""{"id":"id","orderNo":"no","payPrice":"4.00","promotionList":[]}""")
        reply("""{"prepayParam":"unusable-payload","promotionList":[{"promotionType":8}]}""")
        assertTrue(runCatching { api.prepay(order) }.exceptionOrNull() is ClientError)
        val check = DiagnosticsLog.entries.value.last { it.operation == DiagnosticOperation.PAYMENT_CHECK }
        assertEquals(DiagnosticStatus.REJECTED, check.status)
        assertFalse(DiagnosticsLog.exportText().contains("unusable-payload"))
        assertEquals(2, server.requestCount)
    }

    @Test fun blockedCreateResponseRetainsTheCreatedBusinessIdentity() = runTest {
        val device = Device("g", "device", "imei", "00", "shop", "org", 0, emptyList())
        reply("""{"orderId":"created-id","tradeNo":"created-no","promotionList":[{"promotionType":8}]}""")
        val created = api.create(Quote(device, WashMode("sku", "mode", "4"), "4.00", "[]", 0), LocationFix(0.0, 0.0))
        assertEquals("created-id", created.id)
        assertEquals("created-no", created.number)
        assertTrue(created.paymentIssue is PointsDeductionDetected)
        assertEquals(1, server.requestCount)
    }

    @Test fun failedBalancePreflightDoesNotMarkPaymentPending() = runTest {
        var stored = order.copy(payMethod = PayMethod.BALANCE)
        reply("""{"id":"id","orderNo":"no","payPrice":"4.00","promotionList":[{"promotionType":8}]}""")
        assertTrue(runCatching { api.balancePay(stored) { stored = stored.copy(phase = OrderPhase.PAYMENT_PENDING) } }.isFailure)
        assertEquals(OrderPhase.CREATED, stored.phase)
        assertEquals(1, server.requestCount)
    }

    @Test fun submittedBalanceResponseIssueKeepsPendingAndRequestsStatusVerification() = runTest {
        var stored = order.copy(payMethod = PayMethod.BALANCE)
        var callbacks = 0
        reply("""{"id":"id","orderNo":"no","payPrice":"4.00","promotionList":[]}""")
        reply("""{"promotionList":[{"promotionType":8}]}""")
        val error = runCatching { api.balancePay(stored) {
            callbacks++
            assertEquals(1, server.requestCount)
            stored = stored.copy(phase = OrderPhase.PAYMENT_PENDING)
        } }.exceptionOrNull()
        assertTrue(error is PaymentSubmittedUnconfirmed)
        assertFalse(error?.message.orEmpty().contains("停止付款"))
        assertEquals(OrderPhase.PAYMENT_PENDING, stored.phase)
        assertEquals(1, callbacks)
        assertEquals(2, server.requestCount)
    }
}
