package dev.rain.laundry.core

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WaterTest {
    private val outlet = WaterOutlet("water-sku", "常温水", "0.005 元/秒")
    private val device = WaterDevice("water-goods", "测试饮水机", "WATER-IMEI", "shop", "31001", listOf(outlet), true, tradeMain = 1)
    private val idle = WaterStatus(0, 1)
    private val using = WaterStatus(2, 2, "0.12", "water-identify")
    private val fix = LocationFix(30.2, 120.1)
    private val detail = """{"goodsId":"water-goods","name":"测试饮水机","imei":"WATER-IMEI","shopId":"shop","orgId":31001,"tradeMain":1,"categoryCode":"04","isBluetooth":0,"payment":"after","extAttr":"{\"flowmeter\":0}","supportAppUserFinish":true}"""
    private val skus = """[{"skuId":"water-sku","name":"常温水","price":"0","extAttr":"{\"level\":[{\"price\":0.005}]}"}]"""

    @Test fun readsWaterRatesWithoutRoundingToCurrencyCents() {
        val parsed = WaterRules.device(JsonParser.parseString(detail).asJsonObject, JsonParser.parseString(skus))
        assertEquals(device, parsed)
        assertEquals("0.005", WaterRules.decimal("0.0050"))
        assertEquals("0.3 元/升", WaterRules.device(JsonParser.parseString(detail.replace("flowmeter\\\":0", "flowmeter\\\":1")).asJsonObject,
            JsonParser.parseString(skus.replace("0.005", "0.3"))).outlets.single().rate)
    }
    @Test fun alipaySigningSdkParametersPreserveTheOriginalEscapedQuery() {
        val query = "app_id=test&biz_content=%7B%22name%22%3A%22water%22%7D&sign=a%2Bb%3D"
        val target = parseWaterAlipaySigningTarget("https://openapi.alipay.com/gateway.do?$query")
        assertEquals(WaterAlipaySigningTarget.Sdk(query), target)
    }
    @Test fun officialAlipaySchemeIsKeptForOpeningTheSigningPage() {
        val url = "alipays://platformapi/startapp?appId=20000067&url=https%3A%2F%2Fopenapi.alipay.com%2Fgateway.do"
        assertEquals(WaterAlipaySigningTarget.Scheme(url), parseWaterAlipaySigningTarget(url))
    }
    @Test fun signingTargetsRejectUntrustedSchemesHostsAndMissingParameters() {
        for (url in listOf(
            "", "javascript:alert(1)", "http://openapi.alipay.com/gateway.do?sign=test",
            "https://openapi.alipay.com.evil.invalid/gateway.do?sign=test",
            "https://example.invalid/gateway.do?sign=test", "https://openapi.alipay.com/gateway.do",
            "https://openapi.alipay.com/gateway.do?", "alipays://example.invalid/startapp?appId=test",
            "alipays://platformapi/other?appId=test", "alipays://platformapi/startapp"
        )) {
            assertThrows("Must reject signing target: $url", ClientError::class.java) { parseWaterAlipaySigningTarget(url) }
        }
    }
    @Test fun missingMerchantScopeRemainsUnknownInsteadOfDefaultingToZero() {
        val withoutScope = JsonParser.parseString(detail.replace(",\"tradeMain\":1", "")).asJsonObject
        assertNull(WaterRules.device(withoutScope, JsonParser.parseString(skus)).tradeMain)
    }
    @Test fun doesNotTreatOtherDevicesOrBluetoothAsNetworkWater() {
        for (json in listOf(detail.replace("\"04\"", "\"08\""), detail.replace("\"isBluetooth\":0", "\"isBluetooth\":1"),
            detail.replace("\"after\"", "\"before\""))) {
            assertThrows(ClientError::class.java) { WaterRules.device(JsonParser.parseString(json).asJsonObject, JsonParser.parseString(skus)) }
        }
    }
    @Test fun unavailableOutletsAndInvalidRatesCannotBeSelected() {
        assertThrows(ClientError::class.java) { WaterRules.device(JsonParser.parseString(detail).asJsonObject,
            JsonParser.parseString(skus.replace("\"price\":\"0\"", "\"scanAvailable\":false"))) }
        assertThrows(ClientError::class.java) { WaterRules.decimal("-0.1") }
        assertThrows(ClientError::class.java) { WaterRules.decimal("NaN") }
    }
    @Test fun requiresOwnRunningStatusAndRecognizesSettlement() {
        val session = WaterSession("owner", device, outlet, WaterPhase.WAITING)
        assertEquals(WaterPhase.USING, WaterRules.apply(session, using).phase)
        assertEquals(WaterPhase.WAITING, WaterRules.apply(session, idle).phase)
        assertEquals(WaterPhase.SETTLING, WaterRules.apply(session, WaterStatus(3, 1, "0.15", "identify")).phase)
        assertEquals(WaterPhase.ENDED, WaterRules.apply(session.copy(phase = WaterPhase.USING), WaterStatus(5, 1, "0")).phase)
        assertEquals(WaterPhase.UNKNOWN, WaterRules.apply(session, WaterStatus(3, 3, "1", "another-user")).phase)
        assertThrows(ClientError::class.java) { WaterRules.status(JsonParser.parseString("""{"status":2,"workStatus":99}""")) }
    }
    @Test fun anOldFinishedBillDoesNotConfirmANewStartFinished() {
        val previous = WaterStatus(3, 1, "0.15", "previous-usage")
        val waiting = WaterSession("owner", device, outlet, WaterPhase.WAITING, orderNo = "new-usage", initialIdentify = "previous-usage")
        assertEquals(WaterPhase.WAITING, WaterRules.apply(waiting, previous).phase)
        assertEquals(WaterPhase.UNKNOWN, WaterRules.apply(waiting.copy(phase = WaterPhase.UNKNOWN), previous).phase)
        assertEquals(WaterPhase.SETTLING, WaterRules.apply(waiting, previous.copy(identify = "new-usage")).phase)
    }
    private inner class Gateway : WaterGateway {
        var status = idle
        var fresh = device
        var starts = 0
        var startsAfterAuthorization = 0
        var stops = 0
        var refreshes = 0
        var statusReads = 0
        var startedDevice: WaterDevice? = null
        var error: Exception? = null
        var persisted: WaterSession? = null
        val workflow = WaterWorkflow(this, { persisted }, { persisted = it })
        override suspend fun refreshWaterDevice(device: WaterDevice): WaterDevice { refreshes++; return fresh }
        override suspend fun waterStatus(outlet: WaterOutlet): WaterStatus { statusReads++; return status }
        override suspend fun startWater(device: WaterDevice, outlet: WaterOutlet, fix: LocationFix, payment: WaterPayRoute): WaterStart {
            starts++
            startedDevice = device
            assertEquals(WaterPhase.STARTING, persisted?.phase)
            assertEquals(payment, persisted?.payment)
            error?.let { throw it }
            return WaterStart("order-no", "message-id")
        }
        override suspend fun endWater(device: WaterDevice) { stops++; assertEquals(WaterPhase.ENDING, persisted?.phase) }
        override suspend fun startWaterAfterAuthorization(device: WaterDevice, outlet: WaterOutlet, fix: LocationFix,
            payment: WaterPayRoute, returnedTradeMain: Int): WaterStart {
            startsAfterAuthorization++
            return startWater(device, outlet, fix, payment)
        }
    }
    @Test fun recordsIntentAndPreventsDuplicateStart() = runTest {
        val gateway = Gateway()
        assertEquals(WaterPhase.WAITING, gateway.workflow.start(device, outlet, "owner", fix).phase)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix) }.isFailure)
        assertEquals(1, gateway.starts)
    }
    @Test fun aNewExplicitConfirmationAfterAuthorizationStillStoresIntentAndCannotRepeatAnActiveStart() = runTest {
        val gateway = Gateway()
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        assertEquals(WaterPhase.WAITING, gateway.workflow.start(device, outlet, "owner", fix, payment, 1).phase)
        assertEquals(1, gateway.startsAfterAuthorization)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix, payment, 1) }.isFailure)
        assertEquals(1, gateway.starts)
    }
    @Test fun aChangedMerchantCannotReuseTheReturnFromAnotherAuthorization() = runTest {
        val gateway = Gateway().apply { fresh = device.copy(tradeMain = 2) }
        assertTrue(runCatching { gateway.workflow.start(device.copy(tradeMain = 1), outlet, "owner", fix,
            WaterPayRoute("15", PayMethod.ALIPAY), 1) }.isFailure)
        assertEquals(0, gateway.startsAfterAuthorization)
        assertEquals(0, gateway.starts)
    }
    @Test fun idleOutletCanStartAfterAHistoricalFailedUsage() = runTest {
        val gateway = Gateway().apply { status = WaterStatus(6, 1, identify = "old-failed-usage") }
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        val started = gateway.workflow.start(device, outlet, "owner", fix, payment)
        assertEquals(payment, started.payment)
        assertEquals("old-failed-usage", started.initialIdentify)
        assertEquals(WaterPhase.WAITING, started.phase)
        assertEquals(1, gateway.starts)
    }
    @Test fun theFreshDevicesMerchantScopeIsUsedForTheExplicitStart() = runTest {
        val gateway = Gateway().apply { fresh = device.copy(tradeMain = 2) }
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        val started = gateway.workflow.start(device.copy(tradeMain = null), outlet, "owner", fix, payment)
        assertEquals(2, gateway.startedDevice?.tradeMain)
        assertEquals(gateway.fresh, started.device)
        assertEquals(gateway.fresh, gateway.persisted?.device)
        assertEquals(payment, started.payment)
        assertEquals(1, gateway.starts)
    }
    @Test fun unknownOccupancyAndActiveFailureDoNotPermitAnotherStart() = runTest {
        val gateway = Gateway().apply { status = WaterStatus(6, 0) }
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix) }.isFailure)
        assertEquals(0, gateway.starts)
        gateway.status = WaterStatus(6, 1)
        gateway.persisted = WaterSession("owner", device, outlet, WaterPhase.UNKNOWN)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix) }.isFailure)
        assertEquals(0, gateway.starts)
    }
    @Test fun timeoutRequiresReconciliationRatherThanAnotherStart() = runTest {
        val gateway = Gateway().apply { error = ClientError("timeout") }
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix, payment) }.isFailure)
        assertEquals(WaterPhase.UNKNOWN, gateway.persisted?.phase)
        assertEquals(payment, gateway.persisted?.payment)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix, payment) }.isFailure)
        assertEquals(1, gateway.starts)
        assertEquals(1, gateway.refreshes)
        assertEquals(1, gateway.statusReads)
    }
    @Test fun rejectedPaymentPreparationAllowsARefreshAndRetry() = runTest {
        val gateway = Gateway().apply { error = WaterStartNotSent("支付渠道已更新") }
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix, payment) }.isFailure)
        assertEquals(WaterPhase.REJECTED, gateway.persisted?.phase)
        assertEquals(payment, gateway.persisted?.payment)
        assertEquals(1, gateway.starts)
        assertEquals(1, gateway.refreshes)
        assertEquals(1, gateway.statusReads)
        gateway.error = null
        val confirmed = gateway.workflow.start(device, outlet, "owner", fix, payment)
        assertEquals(WaterPhase.WAITING, confirmed.phase)
        assertEquals(payment, confirmed.payment)
        assertEquals(2, gateway.starts)
        assertEquals(2, gateway.refreshes)
        assertEquals(2, gateway.statusReads)
    }
    @Test fun serverAuthorizationRejectionIsRetainedInsteadOfBecomingAStartedSession() = runTest {
        val rejection = BusinessRejected(4602, "请先在支付宝开通先享后付免密", "启动饮水机失败")
        val gateway = Gateway().apply { error = rejection }
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        val error = runCatching { gateway.workflow.start(device, outlet, "owner", fix, payment) }.exceptionOrNull()
        assertSame(rejection, error)
        assertEquals(WaterPhase.REJECTED, gateway.persisted?.phase)
        assertEquals(payment, gateway.persisted?.payment)
        assertFalse(requireNotNull(gateway.persisted).active)
        assertEquals(1, gateway.starts)
        assertEquals(1, gateway.refreshes)
        assertEquals(1, gateway.statusReads)
    }
    @Test fun signingHandoffStaysRejectedAndDoesNotCreateOrReplayAUsage() = runTest {
        val authorization = WaterAlipayAuthorization(1, "https://openapi.alipay.com/gateway.do?app_id=test&sign=abc")
        val handoff = WaterStartNotSent("请先完成支付宝签约", authorization)
        val gateway = Gateway().apply { error = handoff }
        val payment = WaterPayRoute("15", PayMethod.ALIPAY)
        val error = runCatching { gateway.workflow.start(device, outlet, "owner", fix, payment) }.exceptionOrNull()
        assertSame(handoff, error)
        assertEquals(authorization, (error as WaterStartNotSent).authorization)
        assertEquals(WaterPhase.REJECTED, gateway.persisted?.phase)
        assertEquals(payment, gateway.persisted?.payment)
        assertEquals("", gateway.persisted?.orderNo)
        assertEquals("", gateway.persisted?.msgId)
        assertEquals(1, gateway.starts)
    }
    @Test fun changedRateStopsBeforeAnyActivation() = runTest {
        val gateway = Gateway().apply { fresh = device.copy(outlets = listOf(outlet.copy(rate = "0.02 元/秒"))) }
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix) }.isFailure)
        assertEquals(0, gateway.starts)
        assertNull(gateway.persisted)
    }
    @Test fun endNeverTargetsAnotherUsersSession() = runTest {
        val gateway = Gateway().apply {
            persisted = WaterSession("owner", device, outlet, WaterPhase.USING)
            status = WaterStatus(2, 3)
        }
        assertTrue(runCatching { gateway.workflow.finish() }.isFailure)
        assertEquals(0, gateway.stops)
        gateway.status = using
        assertEquals(WaterPhase.ENDING, gateway.workflow.finish().phase)
        assertEquals(1, gateway.stops)
    }
    @Test fun remoteEndRequiresDeviceSupport() = runTest {
        val gateway = Gateway().apply {
            persisted = WaterSession("owner", device.copy(supportAppFinish = false), outlet, WaterPhase.USING)
            status = using
        }
        assertTrue(runCatching { gateway.workflow.finish() }.isFailure)
        assertEquals(0, gateway.stops)
    }
}
