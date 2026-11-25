package dev.rain.laundry.data

import dev.rain.laundry.core.ClientError
import dev.rain.laundry.core.WashMode
import dev.rain.laundry.core.SavedDevice
import dev.rain.laundry.core.SavedDeviceKind
import dev.rain.laundry.core.SavedDeviceStatus
import dev.rain.laundry.core.querySavedDeviceStatuses
import java.net.InetAddress
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SavedDeviceStatusApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LaundryApi
    private val mode = WashMode("fresh-sku", "标准洗", "4.00")
    @Before fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = LaundryApi({ "synthetic-token" }, "Test", baseUrl = server.url("/").newBuilder()
            .host("127.0.0.1").build())
    }
    @After fun stop() { server.shutdown() }
    private fun reply(data: String) = server.enqueue(MockResponse().setBody("""{"code":0,"data":$data}"""))

    @Test fun readOnlyAvailabilityCheckTargetsTheFreshSkuWithoutPreviewOrOrderOperations() = runTest {
        reply("true")
        assertTrue(api.machineModeAvailable(mode))
        val request = server.takeRequest()
        assertEquals("/appoint/checkMachineCanUse", request.path)
        val fields = request.body.readUtf8().split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("fresh-sku", fields["skuId"])
        assertEquals("synthetic-token", fields["token"])
        assertEquals(1, server.requestCount)
        reply("false")
        assertFalse(api.machineModeAvailable(mode))
        assertEquals("/appoint/checkMachineCanUse", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun unknownAvailabilityShapesFailInsteadOfBecomingIdle() = runTest {
        for (body in listOf("null", "{}", "0", "\"true\"", "[]")) {
            reply(body)
            val failure = runCatching { api.machineModeAvailable(mode) }.exceptionOrNull()
            assertTrue("shape=$body, failure=$failure", failure is ClientError)
            assertEquals("/appoint/checkMachineCanUse", server.takeRequest().path)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun waterHistoryFixtureNeverReachesAnyAvailabilityOrScanRequest() = runTest {
        val records = listOf(SavedDevice("owner", SavedDeviceKind.WATER.wire, "water-device", "IMEI",
            "SYNTHETIC-WATER", "饮水机", lastUsedAt = 100))
        reply("true") // A regression reaches a harmless local fixture, never a real platform.
        var queried = false
        querySavedDeviceStatuses(records, { true }, query = {
            queried = true
            api.machineModeAvailable(mode)
            SavedDeviceStatus.checking(it)
        }, onResult = { _, _ -> fail("water history must not produce status UI data") },
            onFailure = { _, _ -> fail("water history must not produce an availability error") })
        assertFalse(queried)
        assertEquals(0, server.requestCount)
    }
}
