package dev.rain.laundry.core

import java.net.URI

/** Ephemeral server-provided signing information, never an authorization-success assertion. */
data class WaterAlipayAuthorization(val tradeMain: Int, val url: String)

/** Account signing plus mandatory device requirements; optional prompts/URLs are not contract status. */
data class WaterAlipayRequirement(val tradeMain: Int, val needsSigning: Boolean, val forcedByDevice: Boolean = false)

/** Identifies transport failures so only read-only authorization checks can be retried. */
class NetworkUnavailable : ClientError("网络连接失败，请检查网络后重试")

sealed interface WaterAlipaySigningTarget {
    data class Scheme(val url: String) : WaterAlipaySigningTarget
    data class Sdk(val parameters: String) : WaterAlipaySigningTarget
}

/** Preserve the signed query exactly. Only launch Alipay or its SDK, never a generic browser. */
fun parseWaterAlipaySigningTarget(url: String): WaterAlipaySigningTarget {
    fun invalid(): Nothing = throw ClientError("支付宝签约链接格式尚未适配，请在胖乖核对后重试")
    if (url.length !in 1..65_536 || url.any { it.code < 32 || it.code == 127 }) invalid()
    val uri = try { URI(url) } catch (_: Exception) { invalid() }
    if (uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery.isNullOrBlank()) invalid()
    val host = uri.host?.lowercase() ?: invalid()
    return when {
        uri.scheme.equals("alipays", true) && host == "platformapi" &&
            uri.path.equals("/startapp", true) && uri.port == -1 -> WaterAlipaySigningTarget.Scheme(url)
        uri.scheme.equals("https", true) && (host == "alipay.com" || host.endsWith(".alipay.com")) &&
            uri.port in setOf(-1, 443) -> WaterAlipaySigningTarget.Sdk(requireNotNull(uri.rawQuery))
        else -> invalid()
    }
}
