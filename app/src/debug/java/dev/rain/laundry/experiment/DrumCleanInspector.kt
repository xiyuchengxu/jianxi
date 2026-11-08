package dev.rain.laundry.experiment

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class DrumCleanOutcome {
    INVALID_RESPONSE, SERVER_REJECTED, MISSING_SKU, RESTRICTED, UNKNOWN_RESTRICTION,
    INVALID_TIME, EXPIRED, IDENTITY_MISMATCH, MACHINE_MISMATCH, INVALID_SKU,
    INVALID_PRICE, LOCAL_ZERO_ROUTE, LOCAL_PAID_ROUTE
}

data class DrumCleanInspection(
    val originalListVisible: Boolean,
    val originalDetailsVisible: Boolean,
    val entryVisible: Boolean,
    val outcome: DrumCleanOutcome,
    val explanation: String,
    val machine: String = "未确认",
    val amount: String? = null
) {
    // No executable order, payment, device command or production gateway is represented here.
    val canStart: Boolean get() = false
}

/**
 * Offline inspection of a synthetic AppointOrderDetail-shaped response, not a server client.
 * Visibility follows the audited list/detail rules. Every later check is independent of forceEntry.
 * Unknown time/flag/price and multiple items deliberately fail closed, unlike the original router.
 */
object DrumCleanInspector {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val priceFormat = Regex("(?:0|[1-9][0-9]{0,5})(?:\\.[0-9]{1,2})?")

    fun inspect(response: String, expectedAppointmentId: String, expectedGoodsId: String,
        scannedValue: String, nowMs: Long, forceEntry: Boolean): DrumCleanInspection {
        val root = runCatching { JsonParser.parseString(response).asJsonObject }.getOrNull()
        val code = root?.scalar("code")?.toIntOrNull()
        val data = root?.objectValue("data")
        val appointment = data?.objectValue("appointmentOrder")
        val outerItems = data?.get("skuItems")?.takeIf { it.isJsonArray }?.asJsonArray
        val innerItems = appointment?.get("skuItems")?.takeIf { it.isJsonArray }?.asJsonArray
        val detailsVisible = code == 0 && outerItems?.isEmpty == false
        val listVisible = code == 0 && (detailsVisible || innerItems?.isEmpty == false)
        // Force shows the diagnostic entry even with an empty/rejected response. It grants nothing.
        val visible = forceEntry || detailsVisible
        fun result(outcome: DrumCleanOutcome, reason: String, machine: String = "未确认", amount: String? = null) =
            DrumCleanInspection(listVisible, detailsVisible, visible, outcome, reason, machine, amount)

        if (code == null) return result(DrumCleanOutcome.INVALID_RESPONSE, "响应缺少有效状态码")
        if (code != 0) return result(DrumCleanOutcome.SERVER_REJECTED, "模拟服务端拒绝（$code）；显示入口不能获得资格")
        if (data == null || appointment == null) return result(DrumCleanOutcome.INVALID_RESPONSE, "预约详情结构不完整")
        if (outerItems == null || outerItems.isEmpty) return result(DrumCleanOutcome.MISSING_SKU, "详情未提供自洁 SKU；不能凭名称或内层列表补造")

        val restriction = data.get("tzjFlag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        if (restriction == null) return result(DrumCleanOutcome.UNKNOWN_RESTRICTION, "缺少明确的 tzjFlag，资格未知")
        if (restriction) return result(DrumCleanOutcome.RESTRICTED, "tzjFlag 禁止使用；强制展示不解除限制")
        val endMs = runCatching {
            LocalDateTime.parse(data.scalar("tzjCanEndTime"), dateFormat).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return result(DrumCleanOutcome.INVALID_TIME, "可用截止时间缺失或格式无效；停止本地路由")
        if (nowMs >= endMs) return result(DrumCleanOutcome.EXPIRED, "已到自洁截止时间；强制展示不能延长时间")

        if (expectedAppointmentId.isBlank() || expectedGoodsId.isBlank() ||
            appointment.scalar("id") != expectedAppointmentId || appointment.scalar("goodsId") != expectedGoodsId)
            return result(DrumCleanOutcome.IDENTITY_MISMATCH, "预约订单或商品与本次目标不一致")
        val machine = data.objectValue("machine")
        val imei = machine?.scalar("imei").orEmpty()
        val identities = listOf("machineCode", "imei", "nqt").mapNotNull { machine?.scalar(it) }.filter { it.isNotBlank() }
        if (imei.isBlank() || scannedValue.isBlank() || scannedValue !in identities)
            return result(DrumCleanOutcome.MACHINE_MISMATCH, "扫码值与预约机器不匹配，或机器身份缺失")

        // The reference routes by the first price yet forwards all items. Do not infer a total.
        if (outerItems.size() != 1) return result(DrumCleanOutcome.INVALID_SKU, "多项自洁 SKU 尚未适配；不能只按首项推断金额", imei)
        val item = outerItems[0].takeIf { it.isJsonObject }?.asJsonObject
        if (item == null || item.scalar("skuId").isBlank() || item.scalar("goodsId") != expectedGoodsId)
            return result(DrumCleanOutcome.INVALID_SKU, "自洁 SKU 缺少标识，或不属于预约设备", imei)
        val price = item.scalar("price")
        if (!priceFormat.matches(price)) return result(DrumCleanOutcome.INVALID_PRICE, "金额缺失、负数或格式异常；不会降为零元", imei)
        val amount = BigDecimal(price).setScale(2).toPlainString()
        return if (BigDecimal(amount).signum() == 0) result(DrumCleanOutcome.LOCAL_ZERO_ROUTE,
            "模拟数据满足本地零元引导分流；真实流程仍需核验、建单与付款，本实验到此停止", imei, amount)
        else result(DrumCleanOutcome.LOCAL_PAID_ROUTE,
            "模拟数据满足本地付费收银台分流；实际金额须由服务端重新报价，本实验到此停止", imei, amount)
    }

    private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.scalar(key: String): String = get(key)?.takeIf {
        it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber)
    }?.asString.orEmpty()
}
