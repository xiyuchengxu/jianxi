package dev.rain.laundry.cloud

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class CloudReminderApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CloudReminderApi

    @Before fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        api = CloudReminderApi(baseUrlOverride = server.url("/"), now = { 1_900_000_000_000L })
    }

    @After fun stop() { server.shutdown() }

    @Test fun bindSendsSecretsOnlyInAuthorizationAndJsonBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"device_token":"device-secret","account_id":"acct-1","device_id":"dev-1"}"""))
        val result = api.bind("https://reminder.example", "pairing-secret", "platform-secret",
            "miaotixing-secret", "Pixel 9")
        assertEquals("device-secret", result.deviceToken)
        assertEquals("acct-1", result.accountId)
        assertEquals("dev-1", result.deviceId)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/bind", request.path)
        assertEquals("Bearer pairing-secret", request.getHeader("Authorization"))
        val url = request.requestUrl.toString()
        assertFalse(url.contains("pairing-secret"))
        assertFalse(url.contains("platform-secret"))
        assertFalse(url.contains("miaotixing-secret"))
        assertFalse(url.contains("device-secret"))
        assertEquals(null, request.requestUrl?.query)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("platform_token", "miaotixing_code", "device_label"), body.keySet())
        assertEquals("platform-secret", body.get("platform_token").asString)
        assertEquals("miaotixing-secret", body.get("miaotixing_code").asString)
        assertEquals("Pixel 9", body.get("device_label").asString)
    }

    @Test fun statusCarriesAccountAndNotificationStateWithoutClaimingDelivery() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"account_status":"active","notification_configured":true,
             "subscriptions":[{"id":"sub-1","order_id":"o-1","order_no":null,"status":"active",
             "delivery":{"state":"accepted","accepted":true,"delivered":false}}]}
        """.trimIndent()))
        val status = api.status("https://reminder.example", "device-secret")
        assertEquals("active", status.accountStatus)
        assertTrue(status.notificationConfigured)
        assertEquals(1, status.subscriptions.size)
        assertEquals("accepted", status.subscriptions.single().deliveryState)
        assertTrue(status.subscriptions.single().accepted)
        assertFalse(status.subscriptions.single().delivered)
        val request = server.takeRequest()
        assertEquals("/v1/status", request.path)
        assertEquals("Bearer device-secret", request.getHeader("Authorization"))
        assertFalse(request.requestUrl.toString().contains("device-secret"))
    }

    @Test fun subscriptionUsesOnlyTheStrongestOrderIdentityAndTreatsDuplicateAsIdempotent() = runTest {
        server.enqueue(MockResponse().setResponseCode(409)
            .setBody("""{"detail":"该设备已订阅同一订单"}"""))
        val outcome = api.subscribe("https://reminder.example", "device-secret",
            CloudEligibleOrder("key", "order-1", "number-1"))
        assertEquals(CloudSubscribeOutcome.AlreadySubscribed, outcome)
        val request = server.takeRequest()
        assertEquals("/v1/subscriptions", request.path)
        assertEquals("Bearer device-secret", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        // order_id is authoritative; an unverified display/trade number must not be sent and
        // cannot make the server's strict identity check reject an otherwise valid subscription.
        assertEquals(setOf("order_id"), body.keySet())
        assertEquals("order-1", body.get("order_id").asString)
        assertFalse(request.requestUrl.toString().contains("device-secret"))
    }

    @Test fun subscriptionFallsBackToOrderNoAndValidatesOnlyTheSubmittedIdentifier() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"subscription":{"id":"s","status":"active","order_id":"server-id","order_no":"number-1"}}"""))
        val confirmed = api.subscribe("https://reminder.example", "device-secret",
            CloudEligibleOrder("key", "", "number-1"))
        assertTrue(confirmed is CloudSubscribeOutcome.Created)
        assertEquals("server-id", (confirmed as CloudSubscribeOutcome.Created).subscription.orderId)
        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("order_no"), body.keySet())
        assertEquals("number-1", body.get("order_no").asString)
    }

    @Test fun subscriptionAcknowledgementMayCarryAnUnsentOrderNoButMustMatchTheSentId() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"subscription":{"id":"s","status":"active","order_id":"order-1","order_no":"platform-number"}}"""))
        val confirmed = api.subscribe("https://reminder.example", "device-secret",
            CloudEligibleOrder("key", "order-1", "local-trade-number"))
        assertTrue(confirmed is CloudSubscribeOutcome.Created)
        assertEquals("platform-number",
            (confirmed as CloudSubscribeOutcome.Created).subscription.orderNo)
    }

    @Test fun nonDuplicateConflictIsRetryableAndNotReportedAsSubscribed() = runTest {
        server.enqueue(MockResponse().setResponseCode(409)
            .setBody("""{"detail":"仅可订阅运行中的洗衣订单"}"""))
        val failure = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(failure is CloudConflict)
    }

    @Test fun notificationTestIsExplicitEmptyPostAndParsesAllStates() = runTest {
        for (expected in CloudTestStatus.entries) {
            server.enqueue(MockResponse().setBody("""{"status":"${expected.wire}"}"""))
            assertEquals(expected, api.testNotification("https://reminder.example", "dev").status)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/notification/test", request.path)
            assertEquals(0L, request.bodySize)
            assertEquals("Bearer dev", request.getHeader("Authorization"))
            assertFalse(request.requestUrl.toString().contains("dev"))
        }
    }

    @Test fun notificationTest429ExposesCooldown() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "17")
            .setBody("""{"detail":"slow down"}"""))
        val failure = runCatching {
            api.testNotification("https://reminder.example", "dev")
        }.exceptionOrNull()
        assertTrue(failure is CloudRateLimited)
        assertEquals(17, (failure as CloudRateLimited).retryAfterSeconds)
    }

    @Test fun redirectsAreNeverFollowed() = runTest {
        server.enqueue(MockResponse().setResponseCode(307)
            .setHeader("Location", "https://evil.example/steal"))
        val failure = runCatching {
            api.status("https://reminder.example", "device-secret")
        }.exceptionOrNull()
        assertTrue(failure is CloudRejected)
        assertEquals(307, (failure as CloudRejected).statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun authFailuresAndUnknownTestStatusNeverLookSuccessful() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        assertTrue(runCatching { api.status("https://reminder.example", "dev") }
            .exceptionOrNull() is CloudAuthRequired)
        server.enqueue(MockResponse().setBody("""{"status":"delivered"}"""))
        assertTrue(runCatching { api.testNotification("https://reminder.example", "dev") }
            .exceptionOrNull() is CloudProtocolMismatch)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"upstream"}"""))
        assertTrue(runCatching { api.unbind("https://reminder.example", "dev") }
            .exceptionOrNull() is CloudRejected)
    }

    @Test fun unbindContractRequires204AndNoBodyIsSent() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.unbind("https://reminder.example", "device-secret")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/binding", request.path)
        assertEquals("Bearer device-secret", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
        assertFalse(request.requestUrl.toString().contains("device-secret"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertTrue(runCatching { api.unbind("https://reminder.example", "device-secret") }
            .exceptionOrNull() is CloudRejected)
    }

    @Test fun reauthorizeAndNotificationUpdateKeepCredentialsOutOfTheUrl() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"account_status":"active"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"notification_configured":true}"""))
        api.reauthorize("https://reminder.example", "device-secret", "platform-secret")
        api.updateNotification("https://reminder.example", "device-secret", "miaotixing-secret")
        val reauth = server.takeRequest()
        assertEquals("/v1/account/token", reauth.path)
        assertEquals("Bearer device-secret", reauth.getHeader("Authorization"))
        assertEquals(setOf("platform_token"),
            JsonParser.parseString(reauth.body.readUtf8()).asJsonObject.keySet())
        assertFalse(reauth.requestUrl.toString().contains("platform-secret"))
        val notification = server.takeRequest()
        assertEquals("/v1/notification", notification.path)
        assertEquals("Bearer device-secret", notification.getHeader("Authorization"))
        assertEquals(setOf("miaotixing_code"),
            JsonParser.parseString(notification.body.readUtf8()).asJsonObject.keySet())
        assertFalse(notification.requestUrl.toString().contains("miaotixing-secret"))
    }

    @Test fun bindAuthFailureIsAPairingRejectionNotAReusableDeviceAuthState() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad pairing"}"""))
        val failure = runCatching {
            api.bind("https://reminder.example", "pairing-secret", "platform-secret", "code", "label")
        }.exceptionOrNull()
        assertTrue(failure is CloudPairingRejected)
        assertFalse(failure is CloudAuthRequired)
    }

    @Test fun bindUses201ContractAndRejectsMissingToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"device_token":"","account_id":"a","device_id":"d"}"""))
        assertTrue(runCatching {
            api.bind("https://reminder.example", "pair", "platform", "code", "label")
        }.exceptionOrNull() is CloudProtocolMismatch)
    }

    @Test fun subscriptionRequiresMatchingActiveAcknowledgement() = runTest {
        val order = CloudEligibleOrder("key", "order-1", "number-1")
        for (body in listOf(
            "{}",
            """{"subscription":{"status":"active","order_id":"order-1"}}""",
            """{"subscription":{"id":"s","status":"active","order_id":"other"}}""",
            """{"subscription":{"id":"s","status":"completed","order_id":"order-1"}}"""
        )) {
            server.enqueue(MockResponse().setResponseCode(201).setBody(body))
            assertTrue(body, runCatching {
                api.subscribe("https://reminder.example", "device-secret", order)
            }.exceptionOrNull() is CloudProtocolMismatch)
        }
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"subscription":{"id":"s","status":"active","order_id":"order-1"}}"""))
        val confirmed = api.subscribe("https://reminder.example", "device-secret", order)
        assertTrue(confirmed is CloudSubscribeOutcome.Created)
        assertEquals("s", (confirmed as CloudSubscribeOutcome.Created).subscription.id)
    }

    @Test fun subscription429IsTransientAnd409IsTerminal() = runTest {
        server.enqueue(MockResponse().setResponseCode(429)
            .setHeader("Retry-After", "9"))
        val rate = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(rate is CloudRateLimited)
        assertEquals(9, (rate as CloudRateLimited).retryAfterSeconds)
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(409)
            .setBody("""{"detail":"仅可订阅运行中的洗衣订单"}"""))
        val rejected = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(rejected is CloudConflict)
        assertEquals(409, (rejected as CloudRejected).statusCode)
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(rejected.statusCode))
    }

    @Test fun serverRetryableHintOverridesTheGenericServerErrorFallback() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody(
            """{"detail":"平台读取失败，未建立订阅","error_code":"identity_mismatch","retryable":false}"""))
        val permanent = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(permanent is CloudRejected)
        assertEquals(false, (permanent as CloudRejected).retryableHint)
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(permanent.statusCode, permanent.retryableHint))

        server.enqueue(MockResponse().setResponseCode(502).setBody(
            """{"detail":"平台读取失败，未建立订阅","error_code":"upstream_unavailable","retryable":true}"""))
        val transient = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(transient is CloudRejected)
        assertEquals(true, (transient as CloudRejected).retryableHint)
        assertEquals(CloudFailureClass.TRANSIENT,
            CloudSyncFailurePolicy.classifyHttp(transient.statusCode, transient.retryableHint))

        server.enqueue(MockResponse().setResponseCode(502).setBody(
            """{"detail":"upstream"}"""))
        val legacy = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(legacy is CloudRejected)
        assertNull((legacy as CloudRejected).retryableHint)
        assertEquals(CloudFailureClass.TRANSIENT,
            CloudSyncFailurePolicy.classifyHttp(legacy.statusCode, legacy.retryableHint))
    }

    @Test fun upstream405IsTheDiagnosableReasonNotTheSelfHosted502() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody(
            """{"detail":"平台读取失败，未建立订阅","error_code":"upstream_http_405","retryable":false}"""))
        val failure = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(failure is CloudRejected)
        failure as CloudRejected
        assertEquals(502, failure.statusCode)
        assertEquals("upstream_http_405", failure.reasonCode)
        assertEquals("405", CloudUpstream.displayStatus(failure.reasonCode))
        assertEquals(false, failure.retryableHint)
        assertEquals(CloudFailureClass.TERMINAL,
            CloudSyncFailurePolicy.classifyHttp(failure.statusCode, failure.retryableHint,
                failure.reasonCode))
    }

    @Test fun unknownOrMaliciousErrorCodeNeverBecomesAReasonOrUiMessage() = runTest {
        for (body in listOf(
            """{"detail":"upstream","error_code":"upstream_http_405<script>alert(1)</script>","retryable":false}""",
            """{"detail":"upstream","error_code":"totally_unknown","retryable":false}""",
            """{"detail":"upstream","error":{"error_code":"upstream_http_405"},"retryable":false}""",
            """{"detail":"<html>Tengine aliyun 405</html>","retryable":false}"""
        )) {
            server.enqueue(MockResponse().setResponseCode(502).setBody(body))
            val failure = runCatching {
                api.subscribe("https://reminder.example", "device-secret",
                    CloudEligibleOrder("key", "order-1", "number-1"))
            }.exceptionOrNull()
            assertTrue(body, failure is CloudRejected)
            failure as CloudRejected
            assertNull(body, failure.reasonCode)
            // Unknown categories fall back to the HTTP status, never to a reflected message.
            assertEquals(body, CloudFailureClass.TERMINAL,
                CloudSyncFailurePolicy.classifyHttp(failure.statusCode, failure.retryableHint,
                    failure.reasonCode))
        }
        assertNull(CloudUpstream.sanitize("upstream_http_405<script>"))
        assertNull(CloudUpstream.sanitize("arbitrary_code"))
        assertEquals("upstream_http_405", CloudUpstream.sanitize(" upstream_http_405 "))
    }

    @Test fun explicitNonRetryable409SkipsTheStartupRaceRetry() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            """{"detail":"平台订单身份不一致，未建立订阅","retryable":false}"""))
        val failure = runCatching {
            api.subscribe("https://reminder.example", "device-secret",
                CloudEligibleOrder("key", "order-1", "number-1"))
        }.exceptionOrNull()
        assertTrue(failure is CloudConflict)
        failure as CloudRejected
        assertEquals(false, failure.retryableHint)
        assertFalse(CloudSyncFailurePolicy.shouldDeferNotRunning(409, failure.retryableHint, 1))
        assertFalse(CloudSyncFailurePolicy.shouldDeferNotRunning(409, false, 0))
        assertTrue(CloudSyncFailurePolicy.shouldDeferNotRunning(409, null, 1))
        assertTrue(CloudSyncFailurePolicy.shouldDeferNotRunning(409, true, 1))
        assertFalse(CloudSyncFailurePolicy.shouldDeferNotRunning(409, null, 3))
    }

    @Test fun deviceReportUsesMinimalBodyAndReadsSourceAndEstimateReadback() = runTest {
        val now = 1_900_000_000_000L
        server.enqueue(MockResponse().setResponseCode(201).setBody("""
            {"subscription":{"id":"sub-1","order_id":"order-1","order_no":null,
             "status":"active","source":"device_report",
             "expected_end_at_ms":${now + 180_000},
             "observed_at_ms":$now,
             "client_state":"running","completion_source":null,
             "delivery":{"state":"none","accepted":false,"delivered":false},
             "estimated_delivery":{"state":"accepted","accepted":true,"delivered":false}}}
        """.trimIndent()))
        val result = api.reportOrder("https://reminder.example", "device-secret",
            CloudOrderReport("order-1", "number-1", "running", now, now + 180_000))
        assertEquals("active", result.state)
        assertEquals("device_report", result.source)
        assertEquals(now + 180_000, result.expectedEndAtMs)
        assertEquals(now, result.observedAtMs)
        assertEquals("running", result.clientState)
        assertEquals("accepted", result.estimatedDeliveryState)
        assertTrue(result.estimatedAccepted)
        assertFalse(result.estimatedDelivered)
        assertFalse(result.accepted)
        assertFalse(result.delivered)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/order-reports", request.path)
        assertEquals("Bearer device-secret", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("order_id", "state", "observed_at_ms", "expected_end_at_ms"),
            body.keySet())
        assertEquals("order-1", body.get("order_id").asString)
        assertEquals("running", body.get("state").asString)
        assertEquals(now, body.get("observed_at_ms").asLong)
        assertEquals(now + 180_000, body.get("expected_end_at_ms").asLong)
        assertFalse(request.requestUrl.toString().contains("device-secret"))
    }

    @Test fun deviceReportUnknownEstimateSendsNullAndSupportsOrderNoFallback() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"subscription":{"id":"sub-2","order_no":"number-1","status":"active",
                "source":"device_report","observed_at_ms":1900000000000,
                "client_state":"running"}}"""))
        val result = api.reportOrder("https://reminder.example", "device-secret",
            CloudOrderReport("", "number-1", "running", 1_900_000_000_000L, null))
        assertEquals("sub-2", result.id)
        val request = server.takeRequest()
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("order_no", "state", "observed_at_ms", "expected_end_at_ms"),
            body.keySet())
        assertEquals("number-1", body.get("order_no").asString)
        assertTrue(body.get("expected_end_at_ms").isJsonNull)
    }

    @Test fun deviceReportAckRequiresSourceEventAndEchoConsistency() = runTest {
        val now = 1_900_000_000_000L
        val completed = CloudOrderReport("order-1", "", "completed", now, null)
        val badBodies = listOf(
            """{"subscription":{"id":"s","order_id":"order-1","status":"completed",
                "source":"platform","observed_at_ms":$now,"client_state":"completed",
                "completion_source":"device"}}""",
            """{"subscription":{"id":"s","order_id":"order-1","status":"active",
                "source":"device_report","observed_at_ms":$now,"client_state":"running"}}""",
            """{"subscription":{"id":"s","order_id":"order-1","status":"completed",
                "source":"device_report","observed_at_ms":${now - 1},"client_state":"completed",
                "completion_source":"device"}}""",
            """{"subscription":{"id":"s","order_id":"order-1","status":"completed",
                "source":"device_report","observed_at_ms":$now,"client_state":"running",
                "completion_source":"device"}}""",
            """{"subscription":{"id":"s","order_id":"order-1","status":"completed",
                "source":"device_report","observed_at_ms":$now,"client_state":"completed",
                "completion_source":"platform"}}"""
        )
        for (body in badBodies) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val failure = runCatching {
                api.reportOrder("https://reminder.example", "device-secret", completed)
            }.exceptionOrNull()
            assertTrue(body, failure is CloudProtocolMismatch)
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"subscription":{"id":"s","order_id":"order-1","status":"completed",
                "source":"device_report","observed_at_ms":$now,"client_state":"completed",
                "completion_source":"device"}}"""))
        val ack = api.reportOrder("https://reminder.example", "device-secret", completed)
        assertEquals("completed", ack.state)
        assertEquals(now, ack.observedAtMs)

        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"subscription":{"id":"s","order_id":"order-1","status":"cancelled",
                "source":"device_report","observed_at_ms":$now,"client_state":"cancelled"}}"""))
        val cancelled = api.reportOrder("https://reminder.example", "device-secret",
            CloudOrderReport("order-1", "", "cancelled", now, null))
        assertEquals("cancelled", cancelled.state)

        // A running registration may already have progressed to a terminal state; that still
        // acknowledges the running evidence but must not be treated as a completed request ACK.
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"subscription":{"id":"s","order_id":"order-1","status":"completed",
                "source":"device_report","observed_at_ms":$now,"client_state":"completed",
                "completion_source":"device"}}"""))
        val runningAck = api.reportOrder("https://reminder.example", "device-secret",
            CloudOrderReport("order-1", "", "running", now, null))
        assertEquals("completed", runningAck.state)
    }

    @Test fun missingOrderReportsRouteIsExplicitlyUnsupportedNotSuccess() = runTest {
        for (code in listOf(404, 405, 501)) {
            server.enqueue(MockResponse().setResponseCode(code)
                .setBody("""{"error_code":"unsupported"}"""))
            val failure = runCatching {
                api.reportOrder("https://reminder.example", "device-secret",
                    CloudOrderReport("order-1", "", "running", 1_900_000_000_000L, null))
            }.exceptionOrNull()
            assertTrue("HTTP $code", failure is CloudUnsupportedFeature)
        }
    }

    @Test fun runningAckCannotAcknowledgeADifferentExpectedDeadline() = runTest {
        val now = 1_900_000_000_000L
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"subscription":{"id":"s","order_id":"order-1","status":"active",
                "source":"device_report","observed_at_ms":$now,"client_state":"running",
                "expected_end_at_ms":${now + 120_000}}}"""))
        val failure = runCatching {
            api.reportOrder("https://reminder.example", "device-secret",
                CloudOrderReport("order-1", "", "running", now, now + 60_000))
        }.exceptionOrNull()
        assertTrue(failure is CloudProtocolMismatch)
    }

    @Test fun invalidDeviceReportIsRejectedBeforeAnyRequest() = runTest {
        val failure = runCatching {
            api.reportOrder("https://reminder.example", "device-secret",
                CloudOrderReport("order-1", "", "delivered", 1_900_000_000_000L, null))
        }.exceptionOrNull()
        assertTrue(failure is CloudProtocolMismatch)
        val expired = runCatching {
            api.reportOrder("https://reminder.example", "device-secret",
                CloudOrderReport("order-1", "", "running", 1_900_000_000_000L,
                    1_900_000_000_000L))
        }.exceptionOrNull()
        assertTrue(expired is CloudProtocolMismatch)
        assertEquals(0, server.requestCount)
    }
}
