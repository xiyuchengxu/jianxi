package dev.rain.laundry.data

import com.google.gson.JsonParser
import dev.rain.laundry.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
    private val device = WaterDevice("water-device", "饮水机", "WATER-IMEI", "shop-uuid", "31001", listOf(outlet), true, tradeMain = 1)
    private val detail = """{"goodsId":"water-device","name":"饮水机","imei":"WATER-IMEI","shopId":"shop-uuid","orgId":31001,"tradeMain":1,"payment":"after","categoryCode":"04","isBluetooth":0,"supportAppUserFinish":true,"extAttr":"{\"flowmeter\":1}"}"""
    private val skus = """[{"skuId":"water-sku","name":"常温水","extAttr":"{\"level\":[{\"price\":0.3}]}"}]"""
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
        reply(detail)
        reply(skus)
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
        assertEquals("Balance starts must not call an Alipay signing endpoint", 3, server.requestCount)
    }
    @Test fun absenceOfPlainBalanceNeverSelectsAutoPayOrActivatesMachine() = runTest {
        reply("""[{"method":1,"autoPayState":1,"needOpenFlag":true}]""")
        assertTrue(runCatching { api.startWater(device, outlet, LocationFix(0.0, 0.0)) }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun alipayOnlyWaterMachineUsesItsAdvertisedAfterPayChannel() = runTest {
        reply("""[{"method":15,"methodName":"支付宝支付","isThird":0,"needOpenFlag":true,"defaultFlag":true},{"method":53,"methodName":"云闪付免密支付"}]""")
        reply("[]"); reply("true")
        reply("\"selected\"")
        reply("""{"orderNo":"usage-order","msgId":"message-id"}""")
        val payment = WaterPayRoute("15", PayMethod.ALIPAY, true)
        assertEquals(WaterStart("usage-order", "message-id"), api.startWater(device, outlet, LocationFix(30.2, 120.1), payment))
        assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
        val authorization = server.takeRequest()
        assertEquals("/popup/shop", authorization.path)
        assertEquals(mapOf("shopId" to "shop-uuid", "goodsId" to "water-device", "tradeMain" to "1", "multipleSwitch" to "2", "token" to "test-token"), fields(authorization))
        assertEquals("/alipay/isSign", server.takeRequest().path)
        val selection = server.takeRequest()
        assertEquals("/payChannelRoute/addUserAfterPayChannel", selection.path)
        assertEquals("15", fields(selection)["method"])
        val start = server.takeRequest()
        assertEquals("/goods/water/unlock", start.path)
        assertFalse(fields(start).containsKey("payType"))
        assertEquals(5, server.requestCount)
    }
    @Test fun aShopOpeningPromptFetchesTheEntryWithoutSelectingOrUnlocking() = runTest {
        val url = "https://openapi.alipay.com/gateway.do?app_id=test&sign=abc"
        for (type in listOf(1, 4)) {
            val before = server.requestCount
            reply("""[{"method":15,"isThird":0,"needOpenFlag":true}]""")
            reply("""[{"popupType":$type,"tradeMain":2}]"""); reply("false")
            reply("""{"url":"$url"}""")
            val error = runCatching {
                api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY))
            }.exceptionOrNull()
            assertTrue(error is WaterStartNotSent)
            assertEquals(WaterAlipayAuthorization(2, url), (error as WaterStartNotSent).authorization)
            assertEquals(4, server.requestCount - before)
            assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
            assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
            val entry = server.takeRequest()
            assertEquals("/alipay/signUrl/pinpoint", entry.path)
            assertEquals(mapOf("tradeMain" to "2", "token" to "test-token"), fields(entry))
        }
    }
    @Test fun absentMalformedOrUntrustedOpeningEntriesNeverSelectOrUnlock() = runTest {
        val responses = listOf(
            """{"code":0}""", """{"code":0,"data":null}""", """{"code":0,"data":[]}""",
            """{"code":0,"data":{}}""", """{"code":0,"data":{"url":""}}""",
            """{"code":0,"data":{"url":null}}""", """{"code":0,"data":{"url":42}}""",
            """{"code":0,"data":{"url":"http://openapi.alipay.com/gateway.do?sign=test"}}""",
            """{"code":0,"data":{"url":"https://alipay.com.example.invalid/gateway.do?sign=test"}}"""
        )
        for (response in responses) {
            val before = server.requestCount
            reply("""[{"method":15,"isThird":0}]""")
            reply("""[{"popupType":1}]"""); reply("false")
            server.enqueue(MockResponse().setBody(response))
            val error = runCatching {
                api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY))
            }.exceptionOrNull()
            assertTrue(error is WaterStartNotSent)
            assertNull((error as WaterStartNotSent).authorization)
            assertEquals(4, server.requestCount - before)
            assertEquals(listOf("/payChannelRoute/afterPay", "/popup/shop", "/alipay/isSign", "/alipay/signUrl/pinpoint"),
                (1..4).map { server.takeRequest().path })
        }
    }
    @Test fun returningAndRecheckingShopDoesNotGenerateAnotherSigningLink() = runTest {
        reply("""[{"popupType":1}]"""); reply("false")
        assertEquals(WaterAlipayRequirement(1, true), api.waterAlipayRequirement(device))
        reply("[]"); reply("true")
        assertEquals(WaterAlipayRequirement(1, false), api.waterAlipayRequirement(device))
        assertEquals(4, server.requestCount)
        assertEquals(listOf("/popup/shop", "/alipay/isSign", "/popup/shop", "/alipay/isSign"), (1..4).map { server.takeRequest().path })
    }
    @Test fun alreadyOpenedAccountDoesNotLoopEvenWhenSigningEntryRemainsAvailable() = runTest {
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val paths = mutableListOf<String>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths += request.path.orEmpty() }
                val data = when(request.path) {
                    "/payChannelRoute/afterPay" -> """[{"method":15,"isThird":0}]"""
                    "/popup/shop" -> """[{"popupType":1}]"""
                    "/alipay/isSign" -> opened.get().toString()
                    // Generating an entry remains possible after signing: this is the regression trigger.
                    "/alipay/signUrl/pinpoint" -> """{"url":"https://openapi.alipay.com/gateway.do?sign=still-available"}"""
                    "/payChannelRoute/addUserAfterPayChannel" -> "null"
                    "/goods/water/unlock" -> """{"orderNo":"usage-order","msgId":"message-id"}"""
                    else -> error("Unexpected request")
                }
                return MockResponse().setBody("""{"code":0,"data":$data}""")
            }
        }
        val method = WaterPayRoute("15", PayMethod.ALIPAY)
        assertTrue(runCatching { api.startWater(device, outlet, LocationFix(30.2, 120.1), method) }.exceptionOrNull() is WaterStartNotSent)
        opened.set(true)
        assertFalse(api.waterAlipayRequirement(device).needsSigning)
        assertEquals(0, paths.count { it == "/goods/water/unlock" })
        assertEquals(WaterStart("usage-order", "message-id"), api.startWater(device, outlet, LocationFix(30.2, 120.1), method))
        assertEquals(1, paths.count { it == "/alipay/signUrl/pinpoint" })
        assertEquals(1, paths.count { it == "/goods/water/unlock" })
    }
    @Test fun malformedOrRejectedShopRequirementsDoNotFetchEntrySelectOrUnlock() = runTest {
        val responses = listOf("""{"code":0,"data":{}}""", """{"code":0,"data":[null]}""",
            """{"code":0,"data":[{}]}""", """{"code":0,"data":[{"popupType":{}}]}""",
            """{"code":0,"data":[{"popupType":1,"tradeMain":-1}]}""",
            """{"code":0,"data":[{"popupType":1,"tradeMain":1},{"popupType":4,"tradeMain":2}]}""",
            """{"code":4601,"msg":"Please verify in original app","data":[]}""")
        for (response in responses) {
            val before = server.requestCount
            reply("""[{"method":15,"isThird":0}]""")
            server.enqueue(MockResponse().setBody(response))
            assertTrue(runCatching { api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY)) }.isFailure)
            assertEquals(2, server.requestCount - before)
            assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
            assertEquals("/popup/shop", server.takeRequest().path)
        }
    }
    @Test fun unrelatedShopPopupDoesNotOpenOrPurchaseAnUnrelatedProduct() = runTest {
        reply("""[{"popupType":2},{"popupType":7}]"""); reply("true")
        assertEquals(WaterAlipayRequirement(1, false), api.waterAlipayRequirement(device))
        assertEquals(2, server.requestCount)
        assertEquals("/popup/shop", server.takeRequest().path)
        assertEquals("/alipay/isSign", server.takeRequest().path)
    }
    @Test fun aSuccessfulNullableShopPromptStillRequiresAnIndependentAccountStatus() = runTest {
        for (envelope in listOf("""{"code":0}""", """{"code":0,"data":null}""")) {
            val before = server.requestCount
            server.enqueue(MockResponse().setBody(envelope))
            reply("true")
            assertFalse(api.waterAlipayRequirement(device).needsSigning)
            assertEquals(2, server.requestCount - before)
            assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
        }
    }
    @Test fun returnThenExplicitConfirmationCanContinueAnOptionalPromptWithoutAnotherSigningLoop() = runTest {
        reply("""[{"method":15,"isThird":0}]""")
        reply("""[{"popupType":4,"isForceUse":0}]""")
        reply("false")
        reply("null")
        reply("""{"orderNo":"usage-order","msgId":"message-id"}""")
        assertEquals(WaterStart("usage-order", "message-id"), api.startWaterAfterAuthorization(device, outlet,
            LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY), 1))
        assertEquals(listOf("/payChannelRoute/afterPay", "/popup/shop", "/alipay/isSign",
            "/payChannelRoute/addUserAfterPayChannel", "/goods/water/unlock"), (1..5).map { server.takeRequest().path })
        assertEquals(5, server.requestCount)
    }
    @Test fun returningFromAuthorizationCannotOverrideTheDevicesMandatoryOpeningRequirement() = runTest {
        reply("""[{"method":15,"isThird":0}]""")
        reply("""[{"popupType":1,"isForceUse":1,"tradeMain":2}]""")
        reply("""{"url":"https://openapi.alipay.com/gateway.do?sign=mandatory"}""")
        val error = runCatching { api.startWaterAfterAuthorization(device, outlet, LocationFix(30.2, 120.1),
            WaterPayRoute("15", PayMethod.ALIPAY), 1) }.exceptionOrNull()
        assertTrue(error is WaterStartNotSent)
        assertEquals(2, (error as WaterStartNotSent).authorization?.tradeMain)
        assertEquals(listOf("/payChannelRoute/afterPay", "/popup/shop", "/alipay/signUrl/pinpoint"),
            (1..3).map { server.takeRequest().path })
        assertEquals(3, server.requestCount)
    }
    @Test fun aNewShopMerchantCannotReuseTheReturnFromThePreviousMerchantsSigning() = runTest {
        reply("""[{"method":15,"isThird":0}]""")
        reply("""[{"popupType":4,"tradeMain":3}]""")
        reply("false")
        reply("""{"url":"https://openapi.alipay.com/gateway.do?sign=new-merchant"}""")
        val error = runCatching { api.startWaterAfterAuthorization(device, outlet, LocationFix(30.2, 120.1),
            WaterPayRoute("15", PayMethod.ALIPAY), 2) }.exceptionOrNull()
        assertTrue(error is WaterStartNotSent)
        assertEquals(3, (error as WaterStartNotSent).authorization?.tradeMain)
        assertEquals(listOf("/payChannelRoute/afterPay", "/popup/shop", "/alipay/isSign", "/alipay/signUrl/pinpoint"),
            (1..4).map { server.takeRequest().path })
        assertEquals(4, server.requestCount)
    }
    @Test fun accountChangeDuringTheSigningStatusReadCannotFetchAnEntryOrActivateTheDevice() = runTest {
        val account = java.util.concurrent.atomic.AtomicReference("old-fixture-token")
        val guarded = LaundryApi(account::get, "Test", baseUrl = server.url("/").newBuilder().host("127.0.0.1").build())
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val data = when (request.path) {
                    "/payChannelRoute/afterPay" -> """[{"method":15,"isThird":0}]"""
                    "/popup/shop" -> "[]"
                    "/alipay/isSign" -> { account.set("new-fixture-token"); "false" }
                    else -> error("A changed account must not reach another operation")
                }
                return MockResponse().setBody("""{"code":0,"data":$data}""")
            }
        }
        assertTrue(runCatching { guarded.startWater(device, outlet, LocationFix(30.2, 120.1),
            WaterPayRoute("15", PayMethod.ALIPAY)) }.exceptionOrNull() is WaterStartNotSent)
        assertEquals(3, server.requestCount)
        server.takeRequest(); server.takeRequest()
        val status = server.takeRequest()
        assertEquals("/alipay/isSign", status.path)
        assertEquals(mapOf("token" to "old-fixture-token"), fields(status))
    }
    @Test fun accountSigningFailuresAndNonBooleanRepliesNeverBecomeAnAuthorizationGrant() = runTest {
        for (response in listOf("""{"code":0}""", """{"code":0,"data":null}""",
            """{"code":0,"data":"true"}""", """{"code":0,"data":1}""",
            """{"code":0,"data":{}}""", """{"code":4601,"data":true}""")) {
            val before = server.requestCount
            reply("""[{"method":15,"isThird":0}]""")
            reply("[]")
            server.enqueue(MockResponse().setBody(response))
            assertTrue(runCatching { api.startWaterAfterAuthorization(device, outlet, LocationFix(30.2, 120.1),
                WaterPayRoute("15", PayMethod.ALIPAY), 1) }.isFailure)
            assertEquals(3, server.requestCount - before)
            assertEquals(listOf("/payChannelRoute/afterPay", "/popup/shop", "/alipay/isSign"),
                (1..3).map { server.takeRequest().path })
        }
    }
    @Test fun missingCachedMerchantScopeIsRefetchedFromTheSameDeviceRatherThanDefaulted() = runTest {
        reply(detail.replace("\"tradeMain\":1", "\"tradeMain\":2"))
        reply(skus)
        reply("[]"); reply("true")
        assertNull(api.waterAlipayAuthorization(device.copy(tradeMain = null)))
        assertEquals(4, server.requestCount)
        assertEquals("/goods/normal/details", server.takeRequest().path)
        assertEquals("/goods/normal/skus", server.takeRequest().path)
        val authorization = server.takeRequest()
        assertEquals("/popup/shop", authorization.path)
        assertEquals("2", fields(authorization)["tradeMain"])
        assertEquals("/alipay/isSign", server.takeRequest().path)
    }
    @Test fun missingMerchantScopeAfterRefreshDoesNotGuessZeroOrRequestSigning() = runTest {
        reply(detail.replace(",\"tradeMain\":1", ""))
        reply(skus)
        assertTrue(runCatching { api.waterAlipayAuthorization(device.copy(tradeMain = null)) }.isFailure)
        assertEquals(2, server.requestCount)
        assertEquals("/goods/normal/details", server.takeRequest().path)
        assertEquals("/goods/normal/skus", server.takeRequest().path)
    }
    @Test fun refreshedDifferentDeviceCannotSupplyMerchantAuthorizationScope() = runTest {
        for (changedDetail in listOf(
            detail.replace("water-device", "other-device"),
            detail.replace("WATER-IMEI", "OTHER-IMEI"),
            detail.replace("31001", "31002")
        )) {
            val before = server.requestCount
            reply(changedDetail)
            reply(skus)
            assertTrue(runCatching { api.waterAlipayAuthorization(device.copy(tradeMain = null)) }.isFailure)
            assertEquals(2, server.requestCount - before)
            assertEquals("/goods/normal/details", server.takeRequest().path)
            assertEquals("/goods/normal/skus", server.takeRequest().path)
        }
    }
    @Test fun alipaySinglePaymentStartDoesNotProbeTheWire15SigningScope() = runTest {
        reply("""[{"method":13,"isThird":0}]""")
        reply("null")
        reply("""{"orderNo":"usage-order","msgId":"message-id"}""")
        assertEquals(WaterStart("usage-order", "message-id"), api.startWater(device.copy(tradeMain = null), outlet,
            LocationFix(30.2, 120.1), WaterPayRoute("13", PayMethod.ALIPAY)))
        assertEquals(3, server.requestCount)
        assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
        val selection = server.takeRequest()
        assertEquals("/payChannelRoute/addUserAfterPayChannel", selection.path)
        assertEquals("13", fields(selection)["method"])
        assertEquals("/goods/water/unlock", server.takeRequest().path)
    }
    @Test fun successfulAlipayChannelAcknowledgementsAllowNullMissingAndPrimitiveData() = runTest {
        // This endpoint acknowledges a channel preference. Its payload is not an authorization grant.
        val acknowledgements = listOf(
            "missing data" to """{"code":0}""",
            "null" to """{"code":0,"data":null}""",
            "true" to """{"code":0,"data":true}""",
            "false" to """{"code":0,"data":false}""",
            "zero" to """{"code":0,"data":0}""",
            "number" to """{"code":0,"data":1.5}""",
            "empty string" to """{"code":0,"data":""}"""
        )
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        for ((label, envelope) in acknowledgements) {
            val before = server.requestCount
            reply("""[{"method":2,"isThird":0},{"method":15,"isThird":0,"needOpenFlag":true}]""")
            reply("[]"); reply("true")
            server.enqueue(MockResponse().setBody(envelope))
            reply("""{"orderNo":"usage-order","msgId":"message-id"}""")
            assertEquals(label, WaterStart("usage-order", "message-id"),
                api.startWater(device, outlet, LocationFix(30.2, 120.1), payment))
            assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
            assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
            val selection = server.takeRequest()
            assertEquals("/payChannelRoute/addUserAfterPayChannel", selection.path)
            assertEquals("The selected Alipay channel must not silently become balance: $label", "15", fields(selection)["method"])
            assertEquals("/goods/water/unlock", server.takeRequest().path)
            assertEquals("One explicit start sends one unlock: $label", 5, server.requestCount - before)
        }
    }
    @Test fun complexChannelAcknowledgementsDoNotSendAnUnlock() = runTest {
        for (payload in listOf("{}", """{"selected":true}""", "[]", "[true]")) {
            val before = server.requestCount
            reply("""[{"method":15,"isThird":0,"needOpenFlag":true}]""")
            reply("[]"); reply("true")
            reply(payload)
            val error = runCatching {
                api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY))
            }.exceptionOrNull()
            assertTrue("Unexpected channel data must fail before activation: $payload", error is WaterStartNotSent)
            assertEquals(4, server.requestCount - before)
            assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
            assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
            assertEquals("/payChannelRoute/addUserAfterPayChannel", server.takeRequest().path)
        }
    }
    @Test fun rejectedChannelSelectionDoesNotSendAnUnlockEvenWithPrimitiveData() = runTest {
        reply("""[{"method":15,"isThird":0,"needOpenFlag":true}]""")
        reply("[]"); reply("true")
        server.enqueue(MockResponse().setBody("""{"code":4601,"msg":"支付宝免密尚未授权","data":true}"""))
        val error = runCatching {
            api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY))
        }.exceptionOrNull()
        assertTrue(error is BusinessRejected)
        assertEquals(4601, (error as BusinessRejected).code)
        assertEquals("选择饮水支付方式失败", error.operation)
        assertEquals(4, server.requestCount)
        assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
        assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
        assertEquals("/payChannelRoute/addUserAfterPayChannel", server.takeRequest().path)
    }
    @Test fun disconnectedChannelSelectionRemainsNotSentAndDoesNotRetryOrUnlock() = runTest {
        reply("""[{"method":15,"isThird":0,"needOpenFlag":true}]""")
        reply("[]"); reply("true")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val error = runCatching {
            api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY))
        }.exceptionOrNull()
        assertTrue(error is WaterStartNotSent)
        assertEquals(4, server.requestCount)
        assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
        assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
        val selection = server.takeRequest()
        assertEquals("/payChannelRoute/addUserAfterPayChannel", selection.path)
        assertEquals("15", fields(selection)["method"])
    }
    @Test fun successfulPreferenceAcknowledgementNeverBypassesTheUnlockAuthorizationRejection() = runTest {
        reply("""[{"method":15,"isThird":0,"needOpenFlag":true}]""")
        reply("[]"); reply("true")
        reply("null")
        server.enqueue(MockResponse().setBody("""{"code":4602,"msg":"请先在支付宝开通先享后付免密","data":null}"""))
        val error = runCatching {
            api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY))
        }.exceptionOrNull()
        assertTrue(error is BusinessRejected)
        assertEquals(4602, (error as BusinessRejected).code)
        assertEquals("启动饮水机失败", error.operation)
        assertTrue(error.message.orEmpty().contains("开通先享后付免密"))
        assertEquals(5, server.requestCount)
        assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
        assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
        assertEquals("/payChannelRoute/addUserAfterPayChannel", server.takeRequest().path)
        assertEquals("/goods/water/unlock", server.takeRequest().path)
    }
    @Test fun disconnectedUnlockIsUnknownRatherThanNotSentAndIsNotAutomaticallyRetried() = runTest {
        reply("""[{"method":15,"isThird":0,"needOpenFlag":true}]""")
        reply("[]"); reply("true")
        reply("false")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val error = runCatching {
            api.startWater(device, outlet, LocationFix(30.2, 120.1), WaterPayRoute("15", PayMethod.ALIPAY))
        }.exceptionOrNull()
        assertTrue(error is ClientError)
        assertFalse(error is WaterStartNotSent)
        assertFalse(error is BusinessRejected)
        assertEquals(5, server.requestCount)
        assertEquals("/payChannelRoute/afterPay", server.takeRequest().path)
        assertEquals("/popup/shop", server.takeRequest().path)
            assertEquals("/alipay/isSign", server.takeRequest().path)
        assertEquals("/payChannelRoute/addUserAfterPayChannel", server.takeRequest().path)
        assertEquals("/goods/water/unlock", server.takeRequest().path)
    }
    @Test fun absentSelectedChannelDoesNotSilentlySwitchPaymentOrStart() = runTest {
        reply("""[{"method":15,"isThird":false,"needOpenFlag":false}]""")
        assertTrue(runCatching { api.startWater(device, outlet, LocationFix(0.0, 0.0)) }.isFailure)
        assertEquals(1, server.requestCount)
    }
    @Test fun alipayVariantsUseThePlatformsPreferredRouteWithoutDuplicateChoices() = runTest {
        reply("""[{"method":13},{"method":15,"defaultFlag":true},{"method":103},{"method":2}]""")
        val routes = api.waterPaymentRoutes(device)
        assertEquals(2, routes.size)
        assertEquals("15", routes.single { it.method == PayMethod.ALIPAY }.wire)
        assertTrue(routes.single { it.method == PayMethod.ALIPAY }.preferred)
        assertEquals(1, server.requestCount)
    }
    @Test fun unavailableMembershipAndThirdPartyRoutesDoNotStartWater() = runTest {
        reply("""[{"method":15,"pgVipType":1},{"method":15,"canChecked":false},{"method":13,"isThird":1},{"method":103,"thirdChannel":"external"},{"method":53}]""")
        assertTrue(runCatching { api.startWater(device, outlet, LocationFix(0.0, 0.0), WaterPayRoute("15", PayMethod.ALIPAY)) }.isFailure)
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
        reply("""{"id":"water-bill","orderNo":"final-no","payType":13,"orderStatus":0,"payPrice":"0.15"}""")
        assertTrue(runCatching { api.waterSettlement(WaterSession("owner", device, outlet, WaterPhase.SETTLING, identify = "session-no")) }.isFailure)
    }
    @Test fun alipaySettlementResolvesSinglePaymentChannelBeforePrepay() = runTest {
        reply("""{"orderId":"water-bill"}""")
        reply("""{"id":"water-bill","orderNo":"final-no","payType":15,"orderStatus":0,"payPrice":"0.15"}""")
        val session = WaterSession("owner", device, outlet, WaterPhase.SETTLING, identify = "session-no",
            payment = WaterPayRoute("15", PayMethod.ALIPAY))
        val order = api.waterSettlement(session)!!
        assertEquals(PayMethod.ALIPAY, order.payMethod)
        assertEquals(OrderPhase.CREATED, order.phase)
        assertTrue(order.syncRequested)
        assertEquals(2, server.requestCount)
        server.takeRequest(); server.takeRequest()
        reply("""[{"method":15,"autoPayState":1},{"method":13,"isThird":0,"autoPayState":0,"needOpenFlag":true}]""")
        val payable = api.waterBillPaymentRoute(order)
        val channels = server.takeRequest()
        assertEquals("/payChannelRoute/againPay", channels.path)
        assertEquals(mapOf("orderNo" to "final-no", "confirmSelectedStatus" to "false", "token" to "test-token"), fields(channels))
        reply("""{"prepayParam":"server-payment-payload"}""")
        assertEquals("server-payment-payload", api.prepay(payable))
        val prepay = server.takeRequest()
        assertEquals("/pay/unify/prePay", prepay.path)
        val prepayFields = fields(prepay)
        assertEquals("13", prepayFields["payType"])
        assertEquals("0", prepayFields["againFlag"])
        assertEquals(4, server.requestCount)
    }
    @Test fun missingSingleAlipayBillRouteDoesNotPayOrActivateAnything() = runTest {
        reply("""[{"method":15,"autoPayState":1},{"method":53}]""")
        val order = LocalOrder("owner", "饮水机", "冷水", "0.15", PayMethod.ALIPAY, OrderPhase.CREATED,
            orderId = "bill", orderNo = "bill-no", syncRequested = true, category = "04")
        assertTrue(runCatching { api.waterBillPaymentRoute(order) }.isFailure)
        assertEquals(1, server.requestCount)
        assertEquals("/payChannelRoute/againPay", server.takeRequest().path)
        assertEquals(OrderPhase.CREATED, order.phase)
    }
    @Test fun autoPaidAlipayWaterBillIsRetainedAsCompleted() = runTest {
        reply("""{"orderId":"water-bill"}""")
        reply("""{"id":"water-bill","orderNo":"final-no","payType":15,"orderStatus":3,"payPrice":"0.15"}""")
        val session = WaterSession("owner", device, outlet, WaterPhase.SETTLING, identify = "session-no",
            payment = WaterPayRoute("15", PayMethod.ALIPAY))
        val order = api.waterSettlement(session)!!
        assertEquals(OrderPhase.COMPLETED, order.phase)
        assertEquals(PayMethod.ALIPAY, order.payMethod)
        assertEquals(2, server.requestCount)
    }
    @Test fun recoveredOwnWaterSessionCanReadAnAlipayBill() = runTest {
        reply("""{"orderId":"water-bill"}""")
        reply("""{"id":"water-bill","orderNo":"final-no","payType":103,"orderStatus":0,"payPrice":"0.15"}""")
        val session = WaterSession("owner", device, outlet, WaterPhase.SETTLING, identify = "session-no", recovered = true)
        assertEquals(PayMethod.ALIPAY, api.waterSettlement(session)!!.payMethod)
        assertEquals(2, server.requestCount)
    }
}
