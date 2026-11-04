package dev.rain.laundry.core

import java.math.BigDecimal
import java.math.RoundingMode

data class DeviceCode(val key: String, val value: String)
data class WashMode(val id: String, val name: String, val price: String, val feature: String = "")
data class Device(
    val id: String, val name: String, val imei: String, val category: String,
    val shopId: String, val orgId: String, val remainingMinutes: Int,
    val modes: List<WashMode>, val error: String = ""
)
data class Quote(val device: Device, val mode: WashMode, val amount: String, val itemsJson: String, val tradeType: Int)
enum class PayMethod(val wire: String, val title: String) { ALIPAY("13", "支付宝"), BALANCE("2", "账户余额") }
data class PayRoute(val method: PayMethod, val thirdChannel: String = "", val outVoucherId: String = "") {
    val title: String get() = method.title
}
data class CreatedOrder(val id: String, val number: String)
data class LocationFix(val latitude: Double, val longitude: Double)
enum class OrderPhase { CREATING, UNCONFIRMED, CREATED, PAYMENT_PENDING, PAID, CANCELLED, REJECTED, CLOSED, COMPLETED, REFUNDING, REFUNDED }
val OrderPhase.finished: Boolean get() = this in setOf(OrderPhase.PAID, OrderPhase.CANCELLED, OrderPhase.REJECTED,
    OrderPhase.CLOSED, OrderPhase.COMPLETED, OrderPhase.REFUNDING, OrderPhase.REFUNDED)
data class LocalOrder(
    val owner: String, val deviceName: String, val modeName: String, val amount: String,
    val payMethod: PayMethod, val phase: OrderPhase, val createdAt: Long = System.currentTimeMillis(),
    val orderId: String = "", val orderNo: String = "", val syncRequested: Boolean = false,
    val thirdChannel: String? = null, val outVoucherId: String? = null, val category: String? = null
) {
    val isWater: Boolean get() = category == "04"
}
open class ClientError(message: String) : Exception(message)
class BusinessRejected(val code: Int, message: String, val operation: String? = null) : ClientError(message) {
    val displayMessage: String
        get() = operation?.let { "$it：$message（错误码 $code）" } ?: message.orEmpty()
}
class SessionExpired : Exception("登录已过期，请重新登录")

fun money(raw: String): String {
    val value = raw.toBigDecimalOrNull() ?: throw ClientError("金额格式异常，请刷新后重试")
    if (value < BigDecimal.ZERO) throw ClientError("金额异常，请刷新后重试")
    return try { value.setScale(2, RoundingMode.UNNECESSARY).toPlainString() }
    catch (_: ArithmeticException) { throw ClientError("金额精度异常，请刷新后重试") }
}
