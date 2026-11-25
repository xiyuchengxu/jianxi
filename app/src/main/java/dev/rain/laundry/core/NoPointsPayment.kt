package dev.rain.laundry.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigDecimal

class PointsDeductionDetected : ClientError("平台为这笔订单应用了不支持的抵扣，已停止付款。请在原应用调整后重试")
class PointsPaymentUnconfirmed : ClientError("无法核对订单抵扣明细，已停止付款。请刷新订单后重试")
class PaymentSubmittedUnconfirmed : ClientError("付款请求已提交，请刷新订单查询最终状态")

/** APK 1.144.0: promotion type 8 spends points; -8 is the water client's explicit opt-out. */
object NoPointsPayment {
    private fun invalid(): Nothing = throw PointsPaymentUnconfirmed()
    private fun selected(): Nothing = throw PointsDeductionDetected()

    private fun type(value: JsonObject): Int {
        val raw = value.get("promotionType") ?: invalid()
        if (!raw.isJsonPrimitive || raw.asJsonPrimitive.isBoolean) invalid()
        return raw.asString.takeIf { it.matches(Regex("-?[0-9]+")) }?.toIntOrNull() ?: invalid()
    }

    private fun decimal(value: JsonElement?): BigDecimal? {
        if (value == null || value.isJsonNull) return null
        if (!value.isJsonPrimitive || value.asJsonPrimitive.isBoolean) invalid()
        val raw = value.asString
        if (raw.length > 64 || !raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) invalid()
        return raw.toBigDecimalOrNull() ?: invalid()
    }

    // OrderDetailsBean's optional discountPrice defaults to "" in the original APK.
    private fun aggregateDiscount(data: JsonObject): BigDecimal? {
        val value = data.get("discountPrice")
        if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString && value.asString.isEmpty()) return null
        return decimal(value)
    }

    /** Actual bill deductions, unlike the preview's catalogue of available offers. */
    private fun deductionList(raw: JsonElement): Int {
        if (!raw.isJsonArray) invalid()
        for (entry in raw.asJsonArray) {
            if (!entry.isJsonObject) invalid()
            if (type(entry.asJsonObject) == 8) selected()
        }
        return raw.asJsonArray.size()
    }

    /** Check optional deduction evidence without mistaking unused preview offers for spent points. */
    fun verifyResponse(data: JsonObject) {
        data.get("promotionList")?.takeUnless { it.isJsonNull }?.let(::deductionList)
        data.get("promotions")?.takeUnless { it.isJsonNull }?.let { raw ->
            if (!raw.isJsonArray) invalid()
            fun inspect(entry: JsonElement, inheritedType: Int? = null) {
                if (!entry.isJsonObject) invalid()
                val row = entry.asJsonObject
                val rowType = if (row.has("promotionType") && !row.get("promotionType").isJsonNull) type(row) else inheritedType
                val flag = row.get("isUsed")?.takeUnless { it.isJsonNull }
                if (flag != null && (!flag.isJsonPrimitive || !flag.asJsonPrimitive.isBoolean)) invalid()
                if (rowType == 8 && flag?.asBoolean == true) selected()
                for (key in listOf("options", "participateList")) {
                    row.get(key)?.takeUnless { it.isJsonNull }?.let { options ->
                        if (!options.isJsonArray) invalid()
                        options.asJsonArray.forEach { inspect(it, rowType) }
                    }
                }
            }
            raw.asJsonArray.forEach { inspect(it) }
        }
    }

    /** A fresh, identity-bound bill is required before any SDK prepay or balance debit. */
    fun verifyBill(data: JsonObject, order: LocalOrder) {
        if (order.orderId.isBlank() && order.orderNo.isBlank()) invalid()
        if (order.orderId.isNotBlank() && data.text("id") != order.orderId) invalid()
        if (order.orderNo.isNotBlank() && data.text("orderNo") != order.orderNo) invalid()
        val payable = decimal(data.get("payPrice")) ?: invalid()
        if (payable.compareTo(order.amount.toBigDecimalOrNull() ?: invalid()) != 0) invalid()
        verifyResponse(data)

        val commission = data.get("commissionTrade")?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid()
            it.asBoolean
        } ?: false
        val deductions = if (commission) {
            val details = data.get("orderCommissionVO")?.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()
            details.get("promotions")?.takeUnless { it.isJsonNull }
        } else data.get("promotionList")?.takeUnless { it.isJsonNull }

        if (deductions != null) {
            val count = deductionList(deductions)
            // An empty list cannot explain a nonzero aggregate deduction.
            if (count == 0 && (aggregateDiscount(data) ?: BigDecimal.ZERO).signum() > 0) invalid()
            return
        }
        // Optional promotionList may be omitted for an ordinary full-price order. Require
        // independent price evidence rather than interpreting every missing list as empty.
        val original = decimal(data.get("markPrice")) ?: invalid()
        if (original.compareTo(payable) != 0 ||
            (aggregateDiscount(data) ?: BigDecimal.ZERO).signum() != 0) invalid()
    }
}
