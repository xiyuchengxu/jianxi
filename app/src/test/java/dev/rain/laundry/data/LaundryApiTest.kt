package dev.rain.laundry.data

import dev.rain.laundry.core.*
import dev.rain.laundry.diagnostics.DiagnosticOperation
import dev.rain.laundry.diagnostics.DiagnosticStatus
import dev.rain.laundry.diagnostics.DiagnosticsLog
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.URLDecoder

class LaundryApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LaundryApi
    @Before fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val testUrl = server.url("/").newBuilder().host("127.0.0.1").build()
        api = LaundryApi({ "example-token" }, "Test", baseUrl = testUrl, now = { 1700000000000 })
    }
    @After fun stop() { server.shutdown() }
    @Test fun registrationSmsUsesPublicTemplateAndNoSession() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":"短信已发送"}"""))
        api.sendCode("13800000000")
        val request = server.takeRequest()
        assertEquals("/common/sms/sendCode", request.path)
        assertEquals("POST", request.method)
        assertEquals("", request.getHeader("Authorization"))
        assertEquals(WireProtocol.sign("/common/sms/sendCode", "", "1700000000000"), request.getHeader("sign"))
        val body = request.body.readUtf8()
        assertEquals(setOf("phone=13800000000", "template=reg"), body.split('&').toSet())
    }
    @Test fun loginUsesRealContractAndSignsRequest() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"token":"new-token"}}"""))
        assertEquals("new-token", api.login("13800000000", "123456"))
        val loginEntry = DiagnosticsLog.entries.value.last()
        assertEquals(DiagnosticOperation.LOGIN, loginEntry.operation)
        assertEquals(DiagnosticStatus.SUCCESS, loginEntry.status)
        assertNotNull(loginEntry.durationMs)
        val exported = DiagnosticsLog.exportText()
        for (privateValue in listOf("new-token", "13800000000", "/user/reg", "example-token"))
            assertFalse(exported.contains(privateValue))
        val request = server.takeRequest()
        assertEquals("/user/reg", request.path); assertEquals("POST", request.method)
        assertEquals("", request.getHeader("Authorization"))
        assertEquals(WireProtocol.sign("/user/reg", "", "1700000000000"), request.getHeader("sign"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("verify=123456")); assertFalse(body.contains("token="))
    }
    @Test fun serverRejectionIsNotTreatedAsSuccess() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":1001,"msg":"验证码错误","data":null}"""))
        val error = runCatching { api.login("13800000000", "123456") }.exceptionOrNull()
        assertTrue(error is ClientError); assertEquals("验证码错误", error?.message)
        val entry = DiagnosticsLog.entries.value.last()
        assertEquals(DiagnosticStatus.REJECTED, entry.status)
        assertEquals(1001, entry.errorCode)
        assertFalse(DiagnosticsLog.exportText().contains("验证码错误"))
    }
    @Test fun membershipEndpointsAreNotCallable() = runTest {
        assertTrue(runCatching { api.post("alipay/periodicDeductSignUrl", emptyMap()) }.isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun orderCreationDoesNotAppendUpsellParameters() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"orderId":"id","tradeNo":"trade"}}"""))
        val device = Device("g1", "test", "1234", "WASH", "shop", "org", 0, emptyList())
        api.create(Quote(device, WashMode("s1", "wash", "4.00"), "4.00", "[]", 1), LocationFix(30.2, 120.1))
        val request = server.takeRequest(); val body = request.body.readUtf8()
        val promotions = body.split('&').first { it.startsWith("promotions=") }.substringAfter('=')
            .let { JsonParser.parseString(URLDecoder.decode(it, "UTF-8")) }
        assertEquals(JsonParser.parseString("""[{"promotionId":"0","promotionType":"4","assetId":"0","oldPromotionId":"3482","orgId":"org"}]"""), promotions)
        assertFalse(body.contains("valueCouponPackChecked")); assertFalse(body.contains("suishoubuItems"))
        assertEquals("30.2", request.getHeader("lat")); assertNotNull(request.getHeader("orderRiskSign"))
    }
    @Test fun previewExplicitlyDisablesAutoSelection() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":true}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"realPrice":"4","items":[{"goodsId":"g","skuId":"s","amount":"1"}],"promotions":[]}}"""))
        val mode = WashMode("s", "wash", "4")
        api.quote(Device("g", "test", "1234", "WASH", "shop", "org", 0, listOf(mode)), mode)
        val availability = server.takeRequest()
        assertEquals("/appoint/checkMachineCanUse", availability.path)
        assertTrue(availability.body.readUtf8().contains("skuId=s"))
        assertTrue(server.takeRequest().body.readUtf8().contains("isAutoSelectPromotion=0"))
    }
    @Test fun previewUsesOriginalNumericSelectionFlagAndExcludedPromotion() = runTest {
        // Contract from OrderPayPreviewAct.orderPre and OrderPayPreviewVm.orderPreview, APK 1.144.0.
        val excluded = JsonParser.parseString("""[{"promotionId":"0","promotionType":"4","assetId":"0","oldPromotionId":"3482","orgId":"test-org"}]""")
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/appoint/checkMachineCanUse") {
                    return MockResponse().setBody("""{"code":0,"data":true}""")
                }
                val fields = request.body.clone().readUtf8().split('&').associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val compatible = fields["isAutoSelectPromotion"] == "0" && fields["promotions"] == "[]" &&
                    fields["forbiddenPromotions"]?.let { JsonParser.parseString(it) == excluded } == true
                return MockResponse().setBody(if (compatible)
                    """{"code":0,"data":{"realPrice":"4.00","items":[{"goodsId":"g","skuId":"s","amount":"1"}],"promotions":[]}}"""
                else """{"code":1,"msg":"参数不正确","data":null}""")
            }
        }
        val mode = WashMode("s", "wash", "4")
        val quote = api.quote(Device("g", "test", "1234", "00", "shop", "test-org", 0, listOf(mode)), mode)
        assertEquals("4.00", quote.amount)
        assertEquals(2, server.requestCount)
    }
    @Test fun previewOmitsOrgSpecificExclusionWhenNoOrgWasReturned() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":true}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"realPrice":"4.00","items":[{"goodsId":"g","skuId":"s","amount":"1"}],"promotions":[]}}"""))
        val mode = WashMode("s", "wash", "4")
        api.quote(Device("g", "test", "1234", "00", "shop", "", 0, listOf(mode)), mode)
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("isAutoSelectPromotion=0"))
        assertFalse(body.contains("forbiddenPromotions"))
    }
    @Test fun onlyBooleanBackendPaidStateIsAccepted() = runTest {
        val order = LocalOrder("owner", "test", "wash", "4.00", PayMethod.ALIPAY, OrderPhase.PAYMENT_PENDING, orderNo = "t1")
        server.enqueue(MockResponse().setBody("""{"code":0,"data":false}""")); assertFalse(api.paid(order))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":true}""")); assertTrue(api.paid(order))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":"9000"}""")); assertTrue(runCatching { api.paid(order) }.isFailure)
    }
    @Test fun cancellationUsesOrderIdAndStringAcknowledgement() = runTest {
        val order = LocalOrder("owner", "test", "wash", "4.00", PayMethod.ALIPAY, OrderPhase.CREATED, orderId = "id1", orderNo = "t1")
        server.enqueue(MockResponse().setBody("""{"code":0,"data":"取消成功"}"""))
        api.cancel(order)
        val request = server.takeRequest()
        assertEquals("/order/cancel", request.path); assertTrue(request.body.readUtf8().contains("orderId=id1"))
    }
    @Test fun orderDetailsUseOrderIdAndRecognizeAllDocumentedStates() = runTest {
        val order = LocalOrder("owner", "test", "wash", "1.00", PayMethod.ALIPAY, OrderPhase.CREATED,
            orderId = "local-id", orderNo = "local-trade")
        for (status in RemoteOrderStatus.entries) {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"local-id","orderStatus":${status.wire}}}"""))
            assertEquals(status, api.orderStatus(order))
            val request = server.takeRequest()
            assertEquals("/order/detail", request.path)
            assertEquals("orderId=local-id&token=example-token", request.body.readUtf8())
        }
    }
    @Test fun unknownOrderDetailsCannotReleaseAnUnfinishedOrder() = runTest {
        val order = LocalOrder("owner", "test", "wash", "1.00", PayMethod.ALIPAY, OrderPhase.CREATED, orderId = "local-id")
        for (data in listOf("null", "{}", """{"id":"other-id","orderStatus":1}""",
            """{"id":"local-id"}""", """{"id":"local-id","orderStatus":99}""")) {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":$data}"""))
            assertTrue(runCatching { api.orderStatus(order) }.exceptionOrNull() is ClientError)
        }
    }
    @Test fun orderNumberFallbackMustMatchTheReturnedOrder() = runTest {
        val order = LocalOrder("owner", "test", "wash", "1.00", PayMethod.ALIPAY, OrderPhase.CREATED, orderNo = "local-trade")
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"local-id","orderNo":"local-trade","orderStatus":1}}"""))
        assertEquals(RemoteOrderStatus.CLOSED, api.orderStatus(order))
        assertEquals("orderNo=local-trade&token=example-token", server.takeRequest().body.readUtf8())
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"other-id","orderNo":"other-trade","orderStatus":1}}"""))
        assertTrue(runCatching { api.orderStatus(order) }.exceptionOrNull() is ClientError)
    }
    @Test fun obsoleteAuthenticatedSmsEndpointCannotBeCalled() = runTest {
        assertTrue(runCatching { api.post("user/smsCode", mapOf("phone" to "13800000000")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }
    @Test fun anUnavailableModeNeverReachesPreviewOrCreate() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":false}"""))
        val mode = WashMode("s", "wash", "4")
        val error = runCatching { api.quote(Device("g", "test", "1234", "00", "shop", "org", 0, listOf(mode)), mode) }.exceptionOrNull()
        assertTrue(error is ClientError)
        assertEquals(1, server.requestCount)
        assertEquals("/appoint/checkMachineCanUse", server.takeRequest().path)
    }
    @Test fun canonicalDeviceIdAndMachineImeiComeFromDetails() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"scan-id","categoryCode":"00"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"goodsId":"canonical-id","name":"洗衣机","imei":"machine-imei","categoryCode":"00","isBluetooth":0,"payment":"before","shopId":"shop","orgId":"org"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":[{"skuId":"s1","name":"标准洗","price":"4.00","scanAvailable":true}]}"""))
        val device = api.device(DeviceCode("SN", "serial-number"))
        assertEquals("canonical-id", device.id); assertEquals("machine-imei", device.imei)
        assertEquals("/goods/scan/v2", server.takeRequest().path)
        assertTrue(server.takeRequest().body.readUtf8().contains("goodsId=scan-id"))
        assertTrue(server.takeRequest().body.readUtf8().contains("goodsId=canonical-id"))
    }
    @Test fun scanCategory02RoutesToDryerAndFiltersUnavailableTimes() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"scan-dryer","categoryCode":"02"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"goodsId":"dryer-1","imei":"dryer-imei","name":"烘干机","isBluetooth":0,"payment":"before","orgId":"31001"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":[{"skuId":"sku-d","name":"标准烘","extAttr":{"perPrice":[{"time":"60","price":"3.00","status":true,"selected":true},{"time":"30","price":"2.00","status":false}]}}]}"""))
        val scanned = api.scanDevice(DeviceCode("SN", "dryer-serial")) as ScannedDevice.Dryer
        assertTrue(scanned.device.isDryer)
        assertEquals("dryer-1", scanned.device.id)
        assertEquals(listOf(60), scanned.device.modes.single().dryerDurations.map { it.minutes })
        assertTrue(scanned.device.modes.single().dryerDurations.single().selectedByPlatform)
        assertEquals(listOf("/goods/scan/v2", "/goods/normal/details", "/goods/normal/skus"),
            List(3) { server.takeRequest().path })
    }
    @Test fun dryerPreviewRefreshesSkuAndSendsSelectedMinutesAsAmountAndNum() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":[{"skuId":"sku-d","name":"标准烘","extAttr":{"perPrice":[{"time":"60","price":"3.00","status":true,"selected":true}]}}]}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":true}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"realPrice":"3.00","items":[{"goodsId":"dryer-1","skuId":"sku-d","amount":"60","soldType":"1","num":"60"}],"promotions":[]}}"""))
        val device = Device("dryer-1", "烘干机", "dryer-imei", "02", "shop", "org", 0, emptyList())
        val duration = DryerDuration(60, "3.00", selectedByPlatform = true)
        val mode = WashMode("sku-d", "标准烘", "3.00", dryerDurations = listOf(duration))
        val quote = api.quoteDryer(device, mode, duration)
        assertEquals(duration, quote.dryerDuration)
        assertEquals("3.00", quote.amount)
        assertEquals("/goods/normal/skus", server.takeRequest().path)
        assertEquals("/appoint/checkMachineCanUse", server.takeRequest().path)
        val preview = server.takeRequest()
        assertEquals("/trade/preview/v4", preview.path)
        val fields = preview.body.readUtf8().split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val item = JsonParser.parseString(fields.getValue("items")).asJsonArray.single().asJsonObject
        assertEquals("60", item.get("amount").asString)
        assertEquals("60", item.get("num").asString)
        assertEquals("1", item.get("soldType").asString)
    }
    @Test fun unsupportedApplianceStopsBeforeFetchingWashModes() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":"g1"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"goodsId":"g1","categoryCode":"04","imei":"12345678"}}"""))
        assertTrue(runCatching { api.device(DeviceCode("IMEI", "12345678")) }.exceptionOrNull() is ClientError)
        assertEquals(2, server.requestCount)
    }
    @Test fun skuUnpaidOrderResponseIsAnActionableBusinessRejection() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2128,"msg":"有未支付订单","data":{"incompleteOrderId":"old-order"}}"""))
        val error = runCatching { api.post("goods/normal/skus", mapOf("goodsId" to "g1")) }.exceptionOrNull()
        assertTrue(error is BusinessRejected); assertEquals(2128, (error as BusinessRejected).code)
        assertTrue(error.message.orEmpty().contains("原应用"))
    }
    @Test fun publicLoginFailureAndProtectedSessionExpiryAreDifferent() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":401,"msg":"未登录"}"""))
        assertTrue(runCatching { api.sendCode("13800000000") }.exceptionOrNull() is BusinessRejected)
        server.enqueue(MockResponse().setBody("""{"code":401,"msg":"未登录"}"""))
        assertTrue(runCatching { api.post("goods/normal/details", mapOf("goodsId" to "g1")) }.exceptionOrNull() is SessionExpired)
    }
    @Test fun forbiddenRequestDoesNotDiscardTheSession() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val error = runCatching { api.post("goods/normal/details", mapOf("goodsId" to "g1")) }.exceptionOrNull()
        assertTrue(error is BusinessRejected); assertFalse(error is SessionExpired)
    }
    @Test fun paymentChannelsUseOrgIdRatherThanDeviceShopId() = runTest {
        // Original OrderPayPreviewAct -> PayModeFragment passes orgId into the wire field named shopId.
        val physicalShopId = "b6da19d1-118b-4f68-967a-000000000001"
        val orgId = "7234567890123456789" // Preserve identifiers beyond Double's exact integer range.
        for (orgJson in listOf(orgId, "\"$orgId\"")) {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = when (request.path) {
                        "/goods/scan/v2" -> """{"code":0,"data":{"id":"g1","categoryCode":"00"}}"""
                        "/goods/normal/details" -> """{"code":0,"data":{"goodsId":"g1","imei":"test-imei","categoryCode":"00","shopId":"$physicalShopId","orgId":$orgJson}}"""
                        "/goods/normal/skus" -> """{"code":0,"data":[{"skuId":"s1","name":"单脱水","price":"1.00"}]}"""
                        "/appoint/checkMachineCanUse" -> """{"code":0,"data":true}"""
                        "/trade/preview/v4" -> """{"code":0,"data":{"realPrice":"1.00","items":[{"goodsId":"g1","skuId":"s1","amount":1}],"promotions":null}}"""
                        "/payChannelRoute/prePay" -> {
                            val fields = request.body.clone().readUtf8().split('&').associate {
                                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                            }
                            if (fields["shopId"] == orgId)
                                """{"code":0,"data":[{"method":13,"autoPayState":0},{"method":2,"autoPayState":0}]}"""
                            else """{"code":1,"msg":"Failed to convert property value of type 'java.lang.String' to required type 'java.lang.Long' for property 'shopId'"}"""
                        }
                        else -> """{"code":1,"msg":"Unexpected endpoint"}"""
                    }
                    return MockResponse().setBody(response)
                }
            }
            val device = api.device(DeviceCode("SN", "test-serial"))
            assertEquals(physicalShopId, device.shopId)
            assertEquals(orgId, device.orgId)
            val quote = api.quote(device, device.modes.single())
            assertEquals(listOf(PayMethod.ALIPAY, PayMethod.BALANCE), api.channels(quote).map { it.method })
            assertEquals(listOf("/goods/scan/v2", "/goods/normal/details", "/goods/normal/skus",
                "/appoint/checkMachineCanUse", "/trade/preview/v4", "/payChannelRoute/prePay"),
                List(6) { server.takeRequest().path })
        }
        assertEquals(12, server.requestCount)
    }
    @Test fun invalidOrgIdStopsBeforeRequestingPaymentChannels() = runTest {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setBody("""{"code":0,"data":[{"method":13,"autoPayState":0}]}""")
        }
        for (orgId in listOf("", "org-uuid", "1.5", "9223372036854775808")) {
            val quote = Quote(Device("g1", "test", "test-imei", "00", "physical-shop", orgId, 0, emptyList()),
                WashMode("s1", "单脱水", "1.00"), "1.00", "[]", 0)
            val error = runCatching { api.channels(quote) }.exceptionOrNull()
            assertTrue(error is ClientError)
            assertTrue(error?.message.orEmpty().contains("机构编号"))
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun routedPaymentKeepsItsChannelAndVoucherParameters() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":[{"method":13,"autoPayState":0,"thirdChannel":"route-1","outVoucherId":"voucher-1"},{"method":2,"autoPayState":0},{"method":13,"autoPayState":1}]}"""))
        val quote = Quote(Device("g", "test", "1234", "00", "shop", "3100001", 0, emptyList()), WashMode("s", "wash", "4"), "4.00", "[]", 1)
        val routes = api.channels(quote)
        val route = routes.single { it.method == PayMethod.ALIPAY }
        assertEquals("route-1", route.thirdChannel)
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"orderNo":"t1","payPrice":"4.00","promotionList":[]}}"""))
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"prepayParam":"payload"}}"""))
        api.prepay(LocalOrder("owner", "test", "wash", "4.00", route.method, OrderPhase.CREATED, orderNo = "t1", thirdChannel = route.thirdChannel, outVoucherId = route.outVoucherId))
        server.takeRequest()
        assertEquals("/order/detail", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("/pay/unify/prePay", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("thirdChannel=route-1")); assertTrue(body.contains("outVoucherId=voucher-1"))
        assertTrue(body.contains("againFlag=0"))
    }
    @Test fun explicitCreateRejectionRemainsDistinguishableFromLostResponses() = runTest {
        val quote = Quote(Device("g", "test", "1234", "00", "shop", "org", 0, emptyList()), WashMode("s", "wash", "4"), "4.00", "[]", 1)
        server.enqueue(MockResponse().setBody("""{"code":4001,"msg":"设备不可用"}"""))
        assertTrue(runCatching { api.create(quote, LocationFix(30.2, 120.1)) }.exceptionOrNull() is BusinessRejected)
        server.enqueue(MockResponse().setResponseCode(503))
        val uncertain = runCatching { api.create(quote, LocationFix(30.2, 120.1)) }.exceptionOrNull()
        assertTrue(uncertain is ClientError); assertFalse(uncertain is BusinessRejected)
    }
    @Test fun parameterRejectionsIdentifyTheActualPaymentStep() = runTest {
        val quote = Quote(Device("g", "test", "1234", "00", "shop", "3100001", 0, emptyList()), WashMode("s", "wash", "4"), "4.00", "[]", 1)
        val order = LocalOrder("owner", "test", "wash", "4.00", PayMethod.ALIPAY, OrderPhase.CREATED, orderNo = "trade1")
        val cases = listOf<Pair<String, suspend () -> Unit>>(
            "获取支付方式失败" to { api.channels(quote); Unit },
            "创建订单失败" to { api.create(quote, LocationFix(30.2, 120.1)); Unit },
            "获取支付信息失败" to { api.prepay(order); Unit },
            "查询付款状态失败" to { api.paid(order); Unit }
        )
        for ((operation, request) in cases) {
            if (operation == "获取支付信息失败") server.enqueue(MockResponse().setBody(
                """{"code":0,"data":{"orderNo":"trade1","payPrice":"4.00","promotionList":[]}}"""))
            server.enqueue(MockResponse().setBody("""{"code":1001,"msg":"参数不正确","data":null}"""))
            val error = runCatching { request() }.exceptionOrNull() as BusinessRejected
            assertEquals(operation, error.operation)
            assertEquals("$operation：参数不正确（错误码 1001）", error.displayMessage)
        }
    }
}
