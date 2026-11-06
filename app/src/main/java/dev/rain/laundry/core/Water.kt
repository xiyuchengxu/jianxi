package dev.rain.laundry.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WaterOutlet(val id: String, val name: String, val rate: String = "", val feature: String = "")
data class WaterPayRoute(val wire: String, val method: PayMethod, val preferred: Boolean = false) {
    val requiresAlipayAgreement: Boolean get() = wire == "15" && method == PayMethod.ALIPAY
    val title: String get() = if (requiresAlipayAgreement) "支付宝先享后付（免密）"
        else if (method == PayMethod.ALIPAY) "支付宝支付" else method.title
    companion object { val BALANCE = WaterPayRoute("2", PayMethod.BALANCE) }
}
data class WaterDevice(
    val id: String, val name: String, val imei: String, val shopId: String, val orgId: String,
    val outlets: List<WaterOutlet>, val supportAppFinish: Boolean, val error: String = "", val announcement: String = "",
    val tradeMain: Int? = null
)
sealed interface ScannedDevice {
    data class Laundry(val device: Device) : ScannedDevice
    data class Water(val device: WaterDevice) : ScannedDevice
}
data class WaterStatus(val status: Int, val workStatus: Int, val amount: String = "", val identify: String = "") {
    val ended: Boolean get() = status == 3 || status == 5
    val mine: Boolean get() = workStatus == 2 && !ended && status != 6
    // status describes a usage result; workStatus describes whether the outlet is available now.
    val available: Boolean get() = workStatus == 1
}
enum class WaterPhase { STARTING, WAITING, USING, ENDING, SETTLING, ENDED, UNKNOWN, REJECTED }
data class WaterSession(
    val owner: String, val device: WaterDevice, val outlet: WaterOutlet, val phase: WaterPhase,
    val orderNo: String = "", val msgId: String = "", val amount: String = "", val identify: String = "",
    val startedAt: Long = System.currentTimeMillis(), val lastStatus: WaterStatus? = null,
    val settlementOrder: LocalOrder? = null, val initialIdentify: String = "", val observedRunning: Boolean = false,
    val payment: WaterPayRoute? = null, val recovered: Boolean = false
) {
    val active: Boolean get() = phase != WaterPhase.ENDED && phase != WaterPhase.REJECTED
}
data class WaterStart(val orderNo: String, val msgId: String)
class WaterStartNotSent(message: String, val authorization: WaterAlipayAuthorization? = null) : ClientError(message)

object WaterRules {
    private fun falseFlag(value: String) = value in setOf("", "0", "false")

    fun paymentRoutes(data: JsonElement): List<WaterPayRoute> {
        if (!data.isJsonArray) throw ClientError("饮水支付方式格式尚未适配")
        val routes = data.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val row = item.asJsonObject
            if (!falseFlag(row.text("isThird", "0")) || row.text("thirdChannel").isNotBlank() ||
                row.text("pgVipType", "0") !in setOf("", "0") || row.text("canChecked", "true") in setOf("0", "false"))
                return@mapNotNull null
            val wire = row.text("method")
            val method = when (wire) {
                "2" -> if (falseFlag(row.text("needOpenFlag", "0"))) PayMethod.BALANCE else null
                // AfterPayUseAct uses 15 for Alipay, including the option to continue without signing up.
                // needOpenFlag on this row is also used for the original client's ticket preference.
                "13", "15", "103" -> PayMethod.ALIPAY
                else -> null
            } ?: return@mapNotNull null
            WaterPayRoute(wire, method, row.text("defaultFlag") in setOf("1", "true"))
        }.distinctBy { it.wire }.groupBy { it.method }.values.map { choices ->
            choices.firstOrNull { it.preferred } ?: choices.first()
        }
        if (routes.isEmpty()) {
            val unionPay = data.asJsonArray.any { it.isJsonObject && it.asJsonObject.text("method") == "53" }
            throw ClientError(if (unionPay) "这台饮水机当前仅提供云闪付免密等未适配渠道，请先在原应用使用"
                else "这台饮水机暂未提供可用的余额或支付宝渠道，请在原应用核对支付方式")
        }
        return routes
    }

    // Explicit no-coupon/no-water-card/no-points choices from AfterPayUseAct.unLock, APK 1.144.0.
    fun promotions(): String = listOf("-6", "-7", "-8").map { type -> JsonObject().apply {
        addProperty("promotionId", "0"); addProperty("promotionType", type); addProperty("assetId", "0")
        addProperty("oldPromotionId", ""); addProperty("orgId", "0")
    } }.joinToString(prefix = "[", postfix = "]")

    fun decimal(raw: String): String {
        val value = raw.toBigDecimalOrNull() ?: throw ClientError("饮水计费数据格式异常，请刷新设备")
        if (value.signum() < 0 || value.scale() > 12 || value > "1000000000".toBigDecimal())
            throw ClientError("饮水计费数据异常，请刷新设备")
        return value.stripTrailingZeros().toPlainString()
    }

    private fun objectValue(value: JsonElement?): JsonObject? = when {
        value == null || value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> runCatching {
            JsonParser.parseString(value.asString).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
        else -> null
    }

    fun device(detail: JsonObject, skus: JsonElement): WaterDevice {
        if (detail.text("categoryCode") != "04") throw ClientError("请扫描饮水机二维码")
        if (detail.text("isBluetooth", "0") != "0") throw ClientError("这台饮水机需要蓝牙连接，请先使用原应用取水")
        if (detail.text("payment") != "after") throw ClientError("这台饮水机不是已适配的按用量后付费设备，请先使用原应用")
        val id = detail.text("goodsId").ifBlank { throw ClientError("饮水机详情缺少设备编号") }
        val imei = detail.text("imei").ifBlank { throw ClientError("饮水机详情缺少设备标识") }
        val orgId = detail.text("orgId").takeIf { it.toLongOrNull() != null }
            ?: throw ClientError("饮水机机构编号无效，请重新扫码")
        val data = (if (skus.isJsonObject && skus.asJsonObject.has("code")) skus.asJsonObject.get("data") else skus)
            ?: throw ClientError("饮水机出水口数据格式尚未适配")
        val array = when {
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> listOf("skus", "skuList", "list").firstNotNullOfOrNull {
                data.asJsonObject.get(it)?.takeIf { value -> value.isJsonArray }?.asJsonArray
            }
            else -> null
        } ?: throw ClientError("饮水机出水口数据格式尚未适配")
        val flowmeter = objectValue(detail.get("extAttr"))?.text("flowmeter")?.toIntOrNull()
        val outlets = array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val item = element.asJsonObject
            val skuId = item.text("skuId")
            if (skuId.isBlank() || item.text("scanAvailable", "true") == "false") return@mapNotNull null
            val levels = objectValue(item.get("extAttr"))?.get("level")?.takeIf { it.isJsonArray }?.asJsonArray
            val price = levels?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.text("price").orEmpty()
            val unit = when (flowmeter) { 1 -> "升"; 0 -> "秒"; else -> "" }
            val rate = if (price.isNotBlank() && unit.isNotBlank())
                "${decimal(price)} 元/$unit" + if ((levels?.size() ?: 0) > 1) "起（阶梯计费）" else "" else ""
            WaterOutlet(skuId, item.text("name", "出水口"), rate, item.text("feature"))
        }
        if (outlets.isEmpty()) throw ClientError("饮水机当前没有可用出水口")
        return WaterDevice(id, detail.text("name", "饮水机"), imei, detail.text("shopId"), orgId, outlets,
            detail.text("supportAppUserFinish") == "true", detail.text("deviceErrorMsg"), detail.text("announcementContent"),
            detail.text("tradeMain").toIntOrNull()?.takeIf { it >= 0 })
    }

    fun status(data: JsonElement): WaterStatus {
        if (!data.isJsonObject) throw ClientError("饮水状态尚未确认，请稍后刷新")
        val value = data.asJsonObject
        val status = value.text("status").toIntOrNull()?.takeIf { it in 0..6 }
            ?: throw ClientError("饮水状态格式尚未适配")
        val work = value.text("workStatus", "0").toIntOrNull()?.takeIf { it in 0..3 }
            ?: throw ClientError("饮水设备占用状态格式尚未适配")
        val amount = value.text("amount").let { if (it.isBlank()) "" else decimal(it) }
        return WaterStatus(status, work, amount, value.text("identify"))
    }

    fun apply(session: WaterSession, status: WaterStatus): WaterSession {
        val runningSeen = session.observedRunning || session.phase in setOf(WaterPhase.USING, WaterPhase.ENDING) || status.mine
        val freshResult = runningSeen || (status.identify.isNotBlank() &&
            (status.identify != session.initialIdentify || status.identify == session.orderNo))
        val phase = when {
            status.status == 6 -> WaterPhase.UNKNOWN
            status.workStatus == 3 -> WaterPhase.UNKNOWN
            status.ended && !freshResult -> if (session.phase in setOf(WaterPhase.STARTING, WaterPhase.WAITING))
                WaterPhase.WAITING else WaterPhase.UNKNOWN
            status.ended -> if (status.amount.isNotBlank() && status.amount.toBigDecimal().signum() > 0)
                WaterPhase.SETTLING else WaterPhase.ENDED
            status.mine -> if (session.phase == WaterPhase.ENDING) WaterPhase.ENDING else WaterPhase.USING
            else -> if (session.phase in setOf(WaterPhase.STARTING, WaterPhase.WAITING)) WaterPhase.WAITING else WaterPhase.UNKNOWN
        }
        return session.copy(phase = phase, lastStatus = status, amount = status.amount, observedRunning = runningSeen,
            identify = status.identify.ifBlank { session.identify })
    }
}

interface WaterGateway {
    suspend fun refreshWaterDevice(device: WaterDevice): WaterDevice
    suspend fun waterStatus(outlet: WaterOutlet): WaterStatus
    suspend fun startWater(device: WaterDevice, outlet: WaterOutlet, fix: LocationFix,
        payment: WaterPayRoute = WaterPayRoute.BALANCE): WaterStart
    suspend fun startWaterAfterAuthorization(device: WaterDevice, outlet: WaterOutlet, fix: LocationFix,
        payment: WaterPayRoute, returnedTradeMain: Int): WaterStart = startWater(device, outlet, fix, payment)
    suspend fun endWater(device: WaterDevice)
}

/** Store an attempt before sending a start command. Timeouts are reconciled by reading, never replayed. */
class WaterWorkflow(private val gateway: WaterGateway, private val read: () -> WaterSession?, private val save: (WaterSession) -> Unit) {
    private val mutex = Mutex()
    suspend fun start(device: WaterDevice, outlet: WaterOutlet, owner: String, fix: LocationFix,
        payment: WaterPayRoute = WaterPayRoute.BALANCE, returnedAuthorizationTradeMain: Int? = null): WaterSession = mutex.withLock {
        if (read()?.active == true) throw ClientError("请先确认上一次饮水的状态")
        if (outlet !in device.outlets || device.error.isNotBlank()) throw ClientError("饮水机当前不可用，请刷新设备")
        val fresh = gateway.refreshWaterDevice(device)
        if (fresh.id != device.id || fresh.imei != device.imei || fresh.orgId != device.orgId || fresh.shopId != device.shopId ||
            (returnedAuthorizationTradeMain != null && fresh.tradeMain != device.tradeMain) || fresh.error.isNotBlank() ||
            fresh.outlets.firstOrNull { it.id == outlet.id } != outlet)
            throw ClientError("饮水机状态或计费标准已更新，请重新扫码确认")
        val status = gateway.waterStatus(outlet)
        if (!status.available) throw ClientError("饮水机正在使用或状态尚未确认，请先刷新状态")
        val attempt = WaterSession(owner, fresh, outlet, WaterPhase.STARTING, lastStatus = status,
            initialIdentify = status.identify, payment = payment)
        save(attempt)
        try {
            val result = if (returnedAuthorizationTradeMain != null) gateway.startWaterAfterAuthorization(fresh, outlet, fix, payment, returnedAuthorizationTradeMain)
                else gateway.startWater(fresh, outlet, fix, payment)
            attempt.copy(phase = WaterPhase.WAITING, orderNo = result.orderNo, msgId = result.msgId).also(save)
        } catch (error: WaterStartNotSent) {
            save(attempt.copy(phase = WaterPhase.REJECTED)); throw error
        } catch (error: BusinessRejected) {
            save(attempt.copy(phase = WaterPhase.REJECTED)); throw error
        } catch (error: Exception) {
            save(attempt.copy(phase = WaterPhase.UNKNOWN)); throw error
        }
    }

    suspend fun finish(): WaterSession = mutex.withLock {
        val session = read()?.takeIf { it.active } ?: throw ClientError("当前没有进行中的饮水记录")
        val latest = gateway.waterStatus(session.outlet)
        val current = WaterRules.apply(session, latest).also(save)
        if (latest.ended) return@withLock current
        if (!latest.mine) throw ClientError("尚未确认这是你的用水会话，请先刷新状态")
        if (!session.device.supportAppFinish) throw ClientError("这台设备不支持远程结束，请按饮水机上的停止键")
        val ending = current.copy(phase = WaterPhase.ENDING).also(save)
        gateway.endWater(session.device)
        ending
    }
}
