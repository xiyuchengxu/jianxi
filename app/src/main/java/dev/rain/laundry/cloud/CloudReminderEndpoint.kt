package dev.rain.laundry.cloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Strict validation of the self-hosted reminder origin.
 *
 * Only standard HTTPS is accepted. User-info, query strings and fragments are rejected because
 * they are unnecessary for this API and could leak credentials into logs/history. TLS verification
 * is left entirely to the platform trust manager; this app never installs a permissive trust
 * manager or hostname verifier.
 */
object CloudReminderEndpoint {
    const val DEFAULT_SERVER_URL = "https://8.153.173.106"
    private const val MAX_URL_LENGTH = 2048

    sealed class Result {
        data class Valid(val url: HttpUrl, val normalized: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun validate(raw: String): Result {
        val value = raw.trim()
        if (value.isEmpty()) return Result.Invalid("empty")
        if (value.length > MAX_URL_LENGTH) return Result.Invalid("too_long")
        if (value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f })
            return Result.Invalid("whitespace_or_control")
        val parsed = value.toHttpUrlOrNull() ?: return Result.Invalid("malformed")
        if (!parsed.isHttps) return Result.Invalid("not_https")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty())
            return Result.Invalid("userinfo")
        if (parsed.query != null) return Result.Invalid("query")
        if (parsed.fragment != null) return Result.Invalid("fragment")
        if (parsed.host.isBlank()) return Result.Invalid("host")
        val normalized = parsed.newBuilder().build().toString().trimEnd('/')
        return Result.Valid(parsed, normalized)
    }

    fun normalize(raw: String): String = when (val result = validate(raw)) {
        is Result.Valid -> result.normalized
        is Result.Invalid -> throw CloudInvalidUrl(result.reason)
    }

    fun httpUrl(raw: String): HttpUrl = when (val result = validate(raw)) {
        is Result.Valid -> result.url
        is Result.Invalid -> throw CloudInvalidUrl(result.reason)
    }

    /** Build a contract path without ever placing a credential in the URL. */
    fun resolve(base: HttpUrl, path: String): HttpUrl {
        val clean = path.trim('/')
        require(clean.isNotEmpty()) { "empty cloud endpoint path" }
        require(clean.split('/').none { it == ".." || it == "." }) { "invalid cloud endpoint path" }
        return base.newBuilder().addPathSegments(clean).build()
    }
}

open class CloudReminderException(message: String) : Exception(message)

class CloudInvalidUrl(val validationReason: String) : CloudReminderException("invalid HTTPS server URL")

open class CloudRejected(val serverMessage: String?, val statusCode: Int,
    /** Optional server contract hint; false means retrying the same request cannot succeed. */
    val retryableHint: Boolean? = null,
    /** Allowlisted upstream category from the server, or null for legacy/unknown values. */
    val reasonCode: String? = null
) : CloudReminderException(serverMessage ?: "cloud server rejected the request")

class CloudPairingRejected(serverMessage: String?) : CloudRejected(serverMessage, 401)

class CloudAuthRequired(serverMessage: String?, reasonCode: String? = null) :
    CloudRejected(serverMessage, 401, null, reasonCode)

class CloudConflict(serverMessage: String?, retryableHint: Boolean? = null,
    reasonCode: String? = null
) : CloudRejected(serverMessage, 409, retryableHint, reasonCode)

class CloudRateLimited(val retryAfterSeconds: Int) :
    CloudReminderException("cloud request is rate limited")

class CloudNetworkUnavailable : CloudReminderException("cloud server unavailable")

class CloudProtocolMismatch : CloudReminderException("unexpected cloud response")

/** The deployed server has no order-reports route; never turn this into a local success. */
class CloudUnsupportedFeature : CloudReminderException("cloud server does not support device reports")

sealed class CloudSubscribeOutcome {
    data class Created(val subscription: CloudSubscriptionSummary) : CloudSubscribeOutcome()
    data object AlreadySubscribed : CloudSubscribeOutcome()
}
