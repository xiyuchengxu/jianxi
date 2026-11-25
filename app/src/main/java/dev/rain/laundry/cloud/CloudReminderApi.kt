package dev.rain.laundry.cloud

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the self-hosted reminder service.
 *
 * Security invariants:
 * - the URL is validated as HTTPS before every production request; tests may inject an HTTP
 *   local server through [baseUrlOverride];
 * - bearer credentials and 喵码 travel only in headers/JSON bodies, never in a URL;
 * - redirects are disabled, so a 3xx never forwards Authorization to another origin;
 * - TLS uses the platform trust manager; there is no trust-all or hostname-verifier override.
 */
class CloudReminderApi(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrlOverride: HttpUrl? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_BODY = 1_000_000
        private const val MAX_DETAIL = 180

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun bind(serverUrl: String, pairingCode: String, platformToken: String,
        miaotixingCode: String, deviceLabel: String
    ): CloudBindResponse {
        val body = JsonObject().apply {
            addProperty("platform_token", platformToken)
            addProperty("miaotixing_code", miaotixingCode)
            addProperty("device_label", deviceLabel)
        }
        val response = execute("POST", base(serverUrl), "v1/bind", pairingCode, body)
        if (response.code != 200 && response.code != 201)
            throw rejectedFor(response, pairing = true)
        val root = parseObject(response)
        val token = root.string("device_token")
        val accountId = root.string("account_id")
        val deviceId = root.string("device_id")
        if (token.isBlank() || accountId.isBlank() || deviceId.isBlank())
            throw CloudProtocolMismatch()
        return CloudBindResponse(token, accountId, deviceId)
    }

    suspend fun status(serverUrl: String, deviceToken: String): CloudServerStatus {
        val response = execute("GET", base(serverUrl), "v1/status", deviceToken, null)
        if (response.code != 200) throw rejectedFor(response)
        val root = parseObject(response)
        val accountStatus = root.string("account_status")
        if (accountStatus.isBlank()) throw CloudProtocolMismatch()
        val configured = root.bool("notification_configured", false)
        val paused = root.bool("paused", false)
        val subscriptions = root.get("subscriptions")?.let { parseSubscriptions(it) }.orEmpty()
        return CloudServerStatus(accountStatus, configured, paused, subscriptions)
    }

    suspend fun reauthorize(serverUrl: String, deviceToken: String, platformToken: String) {
        val body = JsonObject().apply { addProperty("platform_token", platformToken) }
        val response = execute("PUT", base(serverUrl), "v1/account/token", deviceToken, body)
        if (response.code !in 200..299) throw rejectedFor(response)
    }

    suspend fun updateNotification(serverUrl: String, deviceToken: String, miaotixingCode: String) {
        val body = JsonObject().apply { addProperty("miaotixing_code", miaotixingCode) }
        val response = execute("PUT", base(serverUrl), "v1/notification", deviceToken, body)
        if (response.code !in 200..299) throw rejectedFor(response)
    }

    suspend fun unbind(serverUrl: String, deviceToken: String) {
        val response = execute("DELETE", base(serverUrl), "v1/binding", deviceToken, null)
        // 204 is the agreed proof of remote revocation. Never clear local state on another status.
        if (response.code != 204) throw rejectedFor(response)
    }

    suspend fun testNotification(serverUrl: String, deviceToken: String): CloudTestResult {
        // No body at all; this route is explicitly user-triggered.
        val response = execute("POST", base(serverUrl), "v1/notification/test", deviceToken, null)
        if (response.code == 429) throw CloudRateLimited(response.retryAfterSeconds() ?: 60)
        if (response.code != 200) throw rejectedFor(response)
        val root = parseObject(response)
        val status = CloudTestStatus.fromWire(root.string("status"))
            ?: throw CloudProtocolMismatch()
        return CloudTestResult(status, now())
    }

    suspend fun subscribe(serverUrl: String, deviceToken: String, order: CloudEligibleOrder):
        CloudSubscribeOutcome {
        // The platform query and the server both treat order_id as the stronger identity. Send
        // only one identifier: an unsent local order_no (for example a display/trade number) must
        // not make an otherwise correctly identified subscription fail the server's identity check.
        val sentId = order.orderId.trim()
        val sentNo = order.orderNo.trim()
        if (sentId.isBlank() && sentNo.isBlank()) throw CloudProtocolMismatch()
        val body = JsonObject().apply {
            if (sentId.isNotBlank()) addProperty("order_id", sentId)
            else addProperty("order_no", sentNo)
        }
        val response = execute("POST", base(serverUrl), "v1/subscriptions", deviceToken, body)
        if (response.code == 200 || response.code == 201) {
            val root = parseObject(response)
            val subscription = root.get("subscription")
            if (subscription == null || !subscription.isJsonObject)
                throw CloudProtocolMismatch()
            val confirmed = parseSubscription(subscription.asJsonObject)
            val identityConfirmed = if (sentId.isNotBlank()) confirmed.orderId == sentId
                else confirmed.orderNo == sentNo
            if (confirmed.id.isBlank() || confirmed.state != "active" || !identityConfirmed)
                throw CloudProtocolMismatch()
            return CloudSubscribeOutcome.Created(confirmed)
        }
        if (response.code == 429) throw CloudRateLimited(response.retryAfterSeconds() ?: 60)
        if (response.code == 409) {
            val detail = response.detail().orEmpty()
            if (detail.contains("已订阅") || detail.contains("already", ignoreCase = true))
                return CloudSubscribeOutcome.AlreadySubscribed
            throw CloudConflict(detail, response.retryableHint(), response.reasonCode())
        }
        throw rejectedFor(response)
    }

    /**
     * Phone-assisted report. Only the strongest identity is sent; the server must return the
     * existing/created subscription with source/readback fields. Missing route is a version
     * mismatch, never a local success.
     */
    suspend fun reportOrder(serverUrl: String, deviceToken: String,
        report: CloudOrderReport
    ): CloudSubscriptionSummary {
        if (report.state !in setOf("running", "completed", "cancelled"))
            throw CloudProtocolMismatch()
        if (report.observedAtMs <= 0) throw CloudProtocolMismatch()
        val sentId = report.orderId.trim()
        val sentNo = report.orderNo.trim()
        if (sentId.isBlank() && sentNo.isBlank()) throw CloudProtocolMismatch()
        if (report.expectedEndAtMs != null &&
            report.expectedEndAtMs <= report.observedAtMs) throw CloudProtocolMismatch()
        val body = JsonObject().apply {
            if (sentId.isNotBlank()) addProperty("order_id", sentId)
            else addProperty("order_no", sentNo)
            addProperty("state", report.state)
            addProperty("observed_at_ms", report.observedAtMs)
            if (report.expectedEndAtMs != null)
                addProperty("expected_end_at_ms", report.expectedEndAtMs)
            else add("expected_end_at_ms", JsonNull.INSTANCE)
        }
        val response = execute("POST", base(serverUrl), "v1/order-reports", deviceToken, body)
        if (response.code == 404 || response.code == 405 || response.code == 501)
            throw CloudUnsupportedFeature()
        if (response.code == 429) throw CloudRateLimited(response.retryAfterSeconds() ?: 60)
        if (response.code == 200 || response.code == 201) {
            val root = parseObject(response)
            val subscription = root.get("subscription")
            if (subscription == null || !subscription.isJsonObject)
                throw CloudProtocolMismatch()
            val confirmed = parseSubscription(subscription.asJsonObject)
            val identityConfirmed = if (sentId.isNotBlank()) confirmed.orderId == sentId
                else confirmed.orderNo == sentNo
            if (confirmed.id.isBlank() || !identityConfirmed ||
                confirmed.state !in setOf("active", "completed", "cancelled"))
                throw CloudProtocolMismatch()
            validateReportAck(report, confirmed)
            return confirmed
        }
        throw rejectedFor(response)
    }

    /**
     * A report ACK must prove the event it acknowledges, not merely return a subscription id.
     * Running may be acknowledged by an already-terminal record (registration existed), but a
     * completed/cancelled request must return that exact terminal state and an echo at or after
     * the phone's observation.
     */
    private fun validateReportAck(report: CloudOrderReport,
        confirmed: CloudSubscriptionSummary
    ) {
        if (confirmed.source != "device_report") throw CloudProtocolMismatch()
        val echo = confirmed.observedAtMs ?: throw CloudProtocolMismatch()
        if (echo < report.observedAtMs) throw CloudProtocolMismatch()
        when (report.state) {
            "running" -> when (confirmed.state) {
                "active" -> if (confirmed.clientState != "running" ||
                    confirmed.expectedEndAtMs != report.expectedEndAtMs)
                    throw CloudProtocolMismatch()
                "completed" -> if (confirmed.clientState != "completed" ||
                    confirmed.completionSource != "device") throw CloudProtocolMismatch()
                "cancelled" -> if (confirmed.clientState != "cancelled")
                    throw CloudProtocolMismatch()
                else -> throw CloudProtocolMismatch()
            }
            "completed" -> {
                if (confirmed.state != "completed" || confirmed.clientState != "completed" ||
                    confirmed.completionSource != "device") throw CloudProtocolMismatch()
            }
            "cancelled" -> {
                if (confirmed.state != "cancelled" || confirmed.clientState != "cancelled")
                    throw CloudProtocolMismatch()
            }
            else -> throw CloudProtocolMismatch()
        }
    }

    private fun base(serverUrl: String): HttpUrl =
        baseUrlOverride ?: CloudReminderEndpoint.httpUrl(serverUrl)

    private suspend fun execute(method: String, base: HttpUrl, path: String, bearer: String,
        json: JsonObject?
    ): HttpResult = withContext(Dispatchers.IO) {
        val url = CloudReminderEndpoint.resolve(base, path)
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $bearer")
            .header("User-Agent", "JianxiCloud/1")
        val body: RequestBody? = when {
            json != null -> json.toString().toRequestBody(JSON)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val request = builder.method(method, body).build()
        try {
            client.newCall(request).execute().use { response ->
                val source = response.body?.source()
                if (source != null && source.request(MAX_BODY.toLong() + 1)) {
                    throw CloudProtocolMismatch()
                }
                val text = source?.readUtf8().orEmpty()
                if (text.length > MAX_BODY) throw CloudProtocolMismatch()
                HttpResult(response.code, text, response.header("Retry-After"))
            }
        } catch (failure: CloudReminderException) {
            throw failure
        } catch (_: IOException) {
            throw CloudNetworkUnavailable()
        }
    }

    private fun rejectedFor(response: HttpResult, pairing: Boolean = false): CloudReminderException {
        val detail = response.detail()
        if (response.code == 401 || response.code == 403) {
            if (pairing) return CloudPairingRejected(detail)
            return CloudAuthRequired(detail, response.reasonCode())
        }
        return CloudRejected(detail, response.code, response.retryableHint(), response.reasonCode())
    }

    private fun parseObject(response: HttpResult): JsonObject {
        val raw = response.body
        if (raw.isBlank()) return JsonObject()
        return try {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw CloudProtocolMismatch()
        } catch (_: CloudReminderException) {
            throw CloudProtocolMismatch()
        } catch (_: Exception) {
            throw CloudProtocolMismatch()
        }
    }

    private fun parseSubscriptions(value: JsonElement): List<CloudSubscriptionSummary> {
        if (!value.isJsonArray) throw CloudProtocolMismatch()
        return value.asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) throw CloudProtocolMismatch()
            parseSubscription(element.asJsonObject)
        }
    }

    private fun parseSubscription(value: JsonObject): CloudSubscriptionSummary {
        val delivery = value.get("delivery")?.takeIf { it.isJsonObject }?.asJsonObject
        val estimated = value.get("estimated_delivery")?.takeIf { it.isJsonObject }?.asJsonObject
        val state = value.string("status")
        if (state.isBlank()) throw CloudProtocolMismatch()
        val deliveryState = delivery?.string("state").orEmpty()
        val estimatedState = estimated?.string("state").orEmpty()
        return CloudSubscriptionSummary(
            id = value.string("id"),
            orderId = value.optionalString("order_id"),
            orderNo = value.optionalString("order_no"),
            state = state,
            deliveryState = deliveryState,
            accepted = delivery?.bool("accepted", deliveryState == "accepted") == true,
            // The server always reports false today; keep it false for any omitted/ambiguous value.
            delivered = delivery?.bool("delivered", false) == true,
            lastError = value.optionalString("last_error"),
            source = value.optionalString("source"),
            expectedEndAtMs = value.optionalLong("expected_end_at_ms"),
            observedAtMs = value.optionalLong("observed_at_ms"),
            clientState = value.optionalString("client_state"),
            completionSource = value.optionalString("completion_source"),
            estimatedDeliveryState = estimatedState.takeIf { it.isNotBlank() },
            estimatedAccepted = estimated?.bool("accepted", estimatedState == "accepted") == true,
            estimatedDelivered = estimated?.bool("delivered", false) == true
        )
    }

    private fun HttpResult.detail(): String? {
        if (body.isBlank()) return null
        return try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            val value = listOf("detail", "msg", "message", "error")
                .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            value?.trim()?.take(MAX_DETAIL)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Optional server hint; absence keeps the status-code fallback for older deployments. */
    private fun HttpResult.retryableHint(): Boolean? {
        if (body.isBlank()) return null
        return try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return null
            val value = root.asJsonObject.get("retryable") ?: return null
            if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Only the backend's fixed top-level category may cross into diagnostics. Unknown, missing or
     * malicious values become null; the caller then falls back to the HTTP status. The short
     * detail/message fields are never used for this decision.
     */
    private fun HttpResult.reasonCode(): String? {
        if (body.isBlank()) return null
        return try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return null
            val value = root.asJsonObject.get("error_code") ?: return null
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
            CloudUpstream.sanitize(value.asString)
        } catch (_: Exception) {
            null
        }
    }

    private fun HttpResult.retryAfterSeconds(): Int? {
        retryAfter?.trim()?.toIntOrNull()?.let { return it.coerceIn(1, 3600) }
        return try {
            val root = JsonParser.parseString(body)
            if (root.isJsonObject) {
                val value = root.asJsonObject.get("retry_after")
                if (value != null && value.isJsonPrimitive)
                    value.asInt.takeIf { it > 0 }?.coerceAtMost(3600)
                else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.trim().orEmpty()

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw CloudProtocolMismatch()
        return value.asString.trim().takeIf { it.isNotBlank() }
    }

    /** Unix milliseconds only; malformed/ambiguous values fail closed to null. */
    private fun JsonObject.optionalLong(name: String): Long? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || value.asJsonPrimitive.isBoolean)
            throw CloudProtocolMismatch()
        return try {
            if (value.asJsonPrimitive.isNumber) value.asLong
            else value.asString.trim().toLongOrNull()
        } catch (_: Exception) {
            throw CloudProtocolMismatch()
        }?.takeIf { it > 0 }
    }

    private fun JsonObject.bool(name: String, default: Boolean): Boolean {
        val value = get(name) ?: return default
        if (value.isJsonNull) return default
        if (!value.isJsonPrimitive) throw CloudProtocolMismatch()
        if (value.asJsonPrimitive.isBoolean) return value.asBoolean
        return when (value.asString.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> default
        }
    }

    private data class HttpResult(val code: Int, val body: String, val retryAfter: String?)
}
