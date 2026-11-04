package dev.rain.laundry.data

import com.google.gson.JsonParser
import dev.rain.laundry.core.*
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

class WaterApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LaundryApi
    private val outlet = WaterOutlet("water-sku", "常温水", "0.3 元/升")
    private val device = WaterDevice("water-device", "饮水机", "WATER-IMEI", "shop-uuid", "31001", listOf(outlet), true)
    @Before fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = LaundryApi({ "test-token" }, "Test", baseUrl = server.url("/").newBuilder().host("127.0.0.1").build(), now = { 1700000000000 })
    }
    @After fun stop() { server.shutdown() }
    private fun reply(data: String) { server.enqueue(MockResponse().setBody("""{"code":0,"data":$data}""")) }
    private fun fields(request: RecordedRequest) = request.body.readUtf8().split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test fun scanningRecognizesWaterAndRetainsFractionalRates() = runTest {
        reply("""{"id":"water-device","categoryCode":"04"}""")
        reply("""{"goodsId":"water-device","name":"饮水机","imei":"WATER-IMEI","shopId":"shop-uuid","orgId":31001,"payment":"after","categoryCode":"04","isBluetooth":0,"supportAppUserFinish":true,"extAttr":"{\"flowmeter\":1}"}""")
        reply("""[{"skuId":"water-sku","name":"常温水","extAttr":"{\"level\":[{\"price\":0.3}]}"}]""")
        val result = api.scanDevice(DeviceCode("IMEI", "WATER-IMEI")) as ScannedDevice.Water
        assertEquals(device, result.device)
        assertEquals(listOf("/goods/scan/v2", "/goods/normal/details", "/goods/normal/skus"),
            (1..3).map { server.takeRequest().path })
    }
    @Test fun startUsesOriginalAfterPayFieldsAndExplicitCouponExclusions() = runTest {
        reply("""[{"method":2,"isThird":0,"needOpenFlag":false}]""")
        reply("\"selected\"")
        reply("""{"orderNo":"usage-order","msgId":"message-id"}""")
        assertEquals(WaterStart("usage-order", "message-id"), api.startWater(device, outlet, LocationFix(30.2, 120.1)))
        val channels = server.takeRequest()
        assertEquals("/payChannelRoute/afterPay", channels.path)
        assertEquals(mapOf("imei" to "WATER-IMEI", "useTicket" to "0", "shopId" to "31001", "goodsCategory" to "04", "token" to "test-token"), fields(channels))
        val selection = server.takeRequest()
        assertEquals("/payChannelRoute/addUserAfterPayChannel", selection.path)
        assertEquals("2", fields(selection)["method"])
        val unlock = server.takeRequest()
        assertEquals("/goods/water/unlock", unlock.path)
        assertEquals("WATER-IMEI", unlock.getHeader("imei")); assertEquals("04", unlock.getHeader("categoryCode"))
        assertEquals("30.2", unlock.getHeader("lat")); assertEquals("120.1", unlock.getHeader("lng"))
        assertEquals(WireProtocol.sign("/goods/water/unlock", "test-token", "1700000000000"), unlock.getHeader("sign"))
        val body = fields(unlock)
        assertEquals(setOf("skuId", "promotions", "token"), body.keys)
        assertEquals("water-sku", body["skuId"])
        val promotions = JsonParser.parseString(body["promotions"]).asJsonArray
        assertEquals(setOf("-6", "-7", "-8"), promotions.map { it.asJsonObject.text("promotionType") }.toSet())
        assertTrue(promotions.all { it.asJsonObject.text("assetId") == "0" })
    }
    @Test fun absenceOfPlainBalanceNeverSelectsAutoPayOrActivatesMachine() = runTest {
        reply("""[{"method":1,"autoPayState":1,"needOpenFlag":true}]""")
        assertTrue(runCatching { api.startWater(device, outlet, LocationFix(0.0, 0.0)) }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun statusAndStopUseSkuAndGoodsIdentifiersRespectively() = runTest {
        reply("""{"status":2,"workStatus":2,"amount":"0.125","identify":"session-no"}""")
        assertEquals(WaterStatus(2, 2, "0.125", "session-no"), api.waterStatus(outlet))
        val status = server.takeRequest()
        assertEquals("/goods/water/sync", status.path); assertEquals("water-sku", fields(status)["skuId"])
        reply("true"); api.endWater(device)
        val stop = server.takeRequest()
        assertEquals("/goods/endWater", stop.path); assertEquals("water-device", fields(stop)["goodsId"])
        reply("false"); assertTrue(runCatching { api.endWater(device) }.isFailure)
    }
    @Test fun settlementLoadsTheActualBillWithoutStartingOrPayingAgain() = runTest {
        reply("""{"orderId":"water-bill","orderNo":"final-no","payType":2,"orderStatus":3}""")
        reply("""{"id":"water-bill","orderNo":"final-no","payType":2,"orderStatus":3,"payPrice":"0.15"}""")
        val session = WaterSession("owner", device, outlet, WaterPhase.SETTLING, identify = "session-no")
        val order = api.waterSettlement(session)!!
        assertTrue(order.isWater); assertEquals("0.15", order.amount); assertEquals(OrderPhase.COMPLETED, order.phase)
        assertTrue(order.syncRequested)
        val creation = server.takeRequest()
        assertEquals("/order/afterPay/creating", creation.path); assertEquals("session-no", fields(creation)["orderNo"])
        assertEquals("/order/detail", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }
    @Test fun foreignOrChangedPaymentBillsAreRejected() = runTest {
        reply("""{"orderId":"water-bill"}""")
        reply("""{"id":"other-bill","orderNo":"final-no","payType":2,"orderStatus":0,"payPrice":"0.15"}""")
        assertTrue(runCatching { api.waterSettlement(WaterSession("owner", device, outlet, WaterPhase.SETTLING, identify = "session-no")) }.isFailure)
        reply("""{"orderId":"water-bill"}""")
        reply("""{"id":"water-bill","orderNo":"final-no","payType":1,"orderStatus":0,"payPrice":"0.15"}""")
        assertTrue(runCatching { api.waterSettlement(WaterSession("owner", device, outlet, WaterPhase.SETTLING, identify = "session-no")) }.isFailure)
    }
}
