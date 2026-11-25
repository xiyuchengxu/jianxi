package dev.rain.laundry.data

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

class PointsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: LaundryApi
    private lateinit var api: PointsApi

    @Before fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        transport = LaundryApi({ "test-session" }, "Test", baseUrl = server.url("/")
            .newBuilder().host("127.0.0.1").build(), now = { 1700000000000 })
        api = PointsApi(transport)
    }

    @After fun stop() { server.shutdown() }

    private fun answer(data: String) { server.enqueue(MockResponse().setBody("""{"code":0,"data":$data}""")) }

    private fun fields(request: RecordedRequest): Map<String, String> = request.body.readUtf8().split('&')
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun assertContract(path: String, extra: Map<String, String> = emptyMap()) {
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/$path", request.path)
        assertEquals("test-session", request.getHeader("Authorization"))
        assertEquals(WireProtocol.VERSION, request.getHeader("Version"))
        assertEquals(WireProtocol.CHANNEL, request.getHeader("channel"))
        assertEquals("1700000000000", request.getHeader("timestamp"))
        assertEquals(WireProtocol.sign("/$path", "test-session", "1700000000000"), request.getHeader("sign"))
        assertEquals(extra + ("token" to "test-session"), fields(request))
    }

    @Test fun requestsUseTheirOwnPathSignatureAndCurrentActivityId() = runTest {
        answer("""{"integral":"12.3400"}""")
        assertEquals("12.34", api.balance())
        assertContract("user/balance")
        answer("""{"isSignIn":false}""")
        assertFalse(api.signedInToday())
        assertContract("signin/todaySignIn")
        answer("""{"id":"server-activity-2026"}""")
        val activity = api.signInActivity()
        assertEquals("server-activity-2026", activity)
        assertContract("signin/signInAcList")
        answer("""{"signInResult":true}""")
        api.signIn(activity)
        assertContract("signin/doUserSignIn", mapOf("activityId" to "server-activity-2026"))
        answer("""{"items":[]}""")
        assertTrue(api.tasks().isEmpty())
        assertContract("task/list")
    }

    @Test fun decimalBalanceKeepsFractionalPointsWithoutFloatingPointRounding() = runTest {
        for ((wire, expected) in listOf("0.0001" to "0.0001", "12345678901234567890.25" to "12345678901234567890.25", "0.00" to "0")) {
            answer("""{"integral":"$wire"}""")
            assertEquals(expected, api.balance())
            server.takeRequest()
        }
    }

    @Test fun missingOrMalformedBalanceIsNeverDisplayedAsZero() = runTest {
        for (data in listOf("null", "[]", "{}", """{"integral":12.5}""", """{"integral":true}""",
            """{"integral":"-1"}""", """{"integral":"1e2"}""", """{"integral":"NaN"}""", """{"integral":""}""")) {
            answer(data)
            assertTrue(data, runCatching { api.balance() }.exceptionOrNull() is PointsDataInvalid)
            server.takeRequest()
        }
    }

    @Test fun signStatusAcceptsOnlyActualBooleans() = runTest {
        answer("""{"isSignIn":true}""")
        assertTrue(api.signedInToday())
        server.takeRequest()
        for (data in listOf("null", "{}", """{"isSignIn":"true"}""", """{"isSignIn":1}""", """{"isSignIn":null}""")) {
            answer(data)
            assertTrue(data, runCatching { api.signedInToday() }.exceptionOrNull() is PointsDataInvalid)
            server.takeRequest()
        }
    }

    @Test fun falseOrMalformedSignAcknowledgementIsNotSuccessful() = runTest {
        answer("""{"signInResult":false}""")
        assertTrue(runCatching { api.signIn("active") }.exceptionOrNull() is PointsResultUnknown)
        server.takeRequest()
        for (data in listOf("null", "{}", """{"signInResult":"true"}""", """{"signInResult":1}""")) {
            answer(data)
            assertTrue(data, runCatching { api.signIn("active") }.exceptionOrNull() is PointsDataInvalid)
            server.takeRequest()
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun activityIdMustComeFromAValidServerString() = runTest {
        for (data in listOf("null", "{}", """{"id":123}""", """{"id":" "}""", """{"id":"${"x".repeat(201)}"}""")) {
            answer(data)
            assertTrue(data, runCatching { api.signInActivity() }.exceptionOrNull() is PointsDataInvalid)
            server.takeRequest()
        }
    }

    @Test fun invalidLocalActivityIdSendsNoRequest() = runTest {
        assertTrue(runCatching { api.signIn("") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { api.signIn("x".repeat(201)) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test fun taskListUsesItemsAndCompletedStatusOneMeansFinished() = runTest {
        answer("""{"items":[{"taskCode":"task-a","title":"Browse","completedFreq":2,"dailyTaskLimit":3,"completedStatus":0,"subTitle":"Details"},{"taskCode":"task-b","title":"Finished","completedFreq":3,"dailyTaskLimit":3,"completedStatus":1}]}""")
        val tasks = api.tasks()
        assertContract("task/list")
        assertEquals(2, tasks.size)
        assertEquals("task-a", tasks[0].id)
        assertEquals("Browse", tasks[0].name)
        assertEquals("Details", tasks[0].description)
        assertEquals(2, tasks[0].completed)
        assertEquals(3, tasks[0].limit)
        assertFalse(tasks[0].done)
        assertTrue(tasks[1].done)
        assertTrue(tasks.all { it.reward == null })
    }

    @Test fun malformedTaskContainersAndMissingCodesAreRejected() = runTest {
        for (data in listOf("[]", "null", "{}", """{"items":null}""", """{"items":{}}""", """{"items":[null]}""",
            """{"items":[{"taskCode":5}]}""", """{"items":[{"taskCode":" "}]}""")) {
            answer(data)
            assertTrue(data, runCatching { api.tasks() }.exceptionOrNull() is PointsDataInvalid)
            server.takeRequest()
        }
    }

    @Test fun optionalMalformedCountersAreNotInventedAndStringStatusIsNotFinished() = runTest {
        answer("""{"items":[{"taskCode":"a","completedFreq":"5","dailyTaskLimit":-1,"completedStatus":"1"},{"taskCode":"b","completedFreq":1.5,"dailyTaskLimit":0,"completedStatus":9}]}""")
        val tasks = api.tasks()
        for (task in tasks) {
            assertNull(task.completed)
            assertNull(task.limit)
            assertFalse(task.done)
        }
    }

    @Test fun sessionExpiryPropagatesAndMutationIsNotRetried() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { api.signIn("active") }.exceptionOrNull() is SessionExpired)
        assertEquals(1, server.requestCount)
        assertContract("signin/doUserSignIn", mapOf("activityId" to "active"))
    }

    @Test fun backendFailureDoesNotRetryTheSignMutation() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { api.signIn("active") }.exceptionOrNull() is ClientError)
        assertEquals(1, server.requestCount)
    }

    @Test fun unverifiedTaskCompletionAndRewardEndpointsRemainUnavailable() = runTest {
        for (path in listOf("task/completed", "task/getTaskReward")) {
            assertFalse(path in LaundryApi.ALLOWED_PATHS)
            assertTrue(runCatching { transport.post(path, mapOf("taskCode" to "task-a")) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
    }
}
