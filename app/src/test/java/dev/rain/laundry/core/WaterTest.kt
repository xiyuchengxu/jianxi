package dev.rain.laundry.core

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WaterTest {
    private val outlet = WaterOutlet("water-sku", "常温水", "0.005 元/秒")
    private val device = WaterDevice("water-goods", "测试饮水机", "WATER-IMEI", "shop", "31001", listOf(outlet), true)
    private val idle = WaterStatus(0, 1)
    private val using = WaterStatus(2, 2, "0.12", "water-identify")
    private val fix = LocationFix(30.2, 120.1)
    private val detail = """{"goodsId":"water-goods","name":"测试饮水机","imei":"WATER-IMEI","shopId":"shop","orgId":31001,"categoryCode":"04","isBluetooth":0,"payment":"after","extAttr":"{\"flowmeter\":0}","supportAppUserFinish":true}"""
    private val skus = """[{"skuId":"water-sku","name":"常温水","price":"0","extAttr":"{\"level\":[{\"price\":0.005}]}"}]"""

    @Test fun readsWaterRatesWithoutRoundingToCurrencyCents() {
        val parsed = WaterRules.device(JsonParser.parseString(detail).asJsonObject, JsonParser.parseString(skus))
        assertEquals(device, parsed)
        assertEquals("0.005", WaterRules.decimal("0.0050"))
        assertEquals("0.3 元/升", WaterRules.device(JsonParser.parseString(detail.replace("flowmeter\\\":0", "flowmeter\\\":1")).asJsonObject,
            JsonParser.parseString(skus.replace("0.005", "0.3"))).outlets.single().rate)
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
        var stops = 0
        var error: Exception? = null
        var persisted: WaterSession? = null
        val workflow = WaterWorkflow(this, { persisted }, { persisted = it })
        override suspend fun refreshWaterDevice(device: WaterDevice) = fresh
        override suspend fun waterStatus(outlet: WaterOutlet) = status
        override suspend fun startWater(device: WaterDevice, outlet: WaterOutlet, fix: LocationFix): WaterStart {
            starts++
            assertEquals(WaterPhase.STARTING, persisted?.phase)
            error?.let { throw it }
            return WaterStart("order-no", "message-id")
        }
        override suspend fun endWater(device: WaterDevice) { stops++; assertEquals(WaterPhase.ENDING, persisted?.phase) }
    }
    @Test fun recordsIntentAndPreventsDuplicateStart() = runTest {
        val gateway = Gateway()
        assertEquals(WaterPhase.WAITING, gateway.workflow.start(device, outlet, "owner", fix).phase)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix) }.isFailure)
        assertEquals(1, gateway.starts)
    }
    @Test fun timeoutRequiresReconciliationRatherThanAnotherStart() = runTest {
        val gateway = Gateway().apply { error = ClientError("timeout") }
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix) }.isFailure)
        assertEquals(WaterPhase.UNKNOWN, gateway.persisted?.phase)
        assertTrue(runCatching { gateway.workflow.start(device, outlet, "owner", fix) }.isFailure)
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
