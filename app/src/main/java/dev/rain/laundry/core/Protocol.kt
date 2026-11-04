package dev.rain.laundry.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object DeviceCodes {
    fun parse(raw: String): DeviceCode {
        if (raw.length > 4096) throw ClientError("二维码内容过长")
        val query = raw.trim().substringAfter('?', raw.trim()).substringBefore('#')
        val pairs = try {
            query.split('&').filter { it.contains('=') }.map {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        } catch (_: IllegalArgumentException) { throw ClientError("二维码编码无效") }
        val keys = setOf("IMEI", "NQT", "SN")
        val matches = pairs.filter { it.first in keys }
        if (matches.size != 1) throw ClientError("请使用包含 IMEI、SN 或 NQT 的设备二维码")
        val (key, value) = matches.single()
        if (!value.matches(Regex("[A-Za-z0-9_.-]{4,96}"))) throw ClientError("设备编号格式异常")
        return DeviceCode(key, value)
    }
}

/** Wire compatibility with the supplied 1.144.0 APK; not the new app's version. */
object WireProtocol {
    const val VERSION = "1.144.0"
    const val CHANNEL = "android_app"
    // Public client-side constant from the supplied APK, never an account token.
    private const val CLIENT_CONSTANT = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun sign(path: String, token: String, timestamp: String): String {
        require(path.startsWith('/') && !path.contains('?'))
        return sha256("appSecret=$CLIENT_CONSTANT&channel=$CHANNEL&timestamp=$timestamp&token=$token&version=$VERSION&$path")
    }
    fun riskSign(path: String, token: String, timestamp: String, imei: String, category: String, fix: LocationFix): String =
        sha256("appSecret=$CLIENT_CONSTANT&categoryCode=$category&channel=$CHANNEL&imei=$imei&lat=${fix.latitude}&lng=${fix.longitude}&timestamp=$timestamp&token=$token&version=$VERSION&$path")
}

object PurchaseRules {
    fun items(device: Device, mode: WashMode): String = JsonObject().apply {
        addProperty("goodsId", device.id); addProperty("skuId", mode.id)
        addProperty("amount", "1"); addProperty("soldType", "1"); addProperty("num", "1"); addProperty("price", "")
    }.let { "[$it]" }

    /** Original client's explicit no-gold choice; this does not select or purchase an asset. */
    fun goldExclusion(orgId: String): String = JsonObject().apply {
        addProperty("promotionId", "0"); addProperty("promotionType", "4")
        addProperty("assetId", "0"); addProperty("oldPromotionId", "3482"); addProperty("orgId", orgId)
    }.let { "[$it]" }

    fun verifyQuote(data: JsonObject, device: Device, mode: WashMode): Quote {
        val items = data.getAsJsonArray("items") ?: throw ClientError("报价缺少洗衣项目")
        if (items.size() != 1) throw ClientError("报价包含额外商品，请先在原应用核对")
        val item = items[0].asJsonObject
        if (item.text("goodsId") != device.id || item.text("skuId") != mode.id || item.text("amount", "1") != "1")
            throw ClientError("报价项目与所选洗衣模式不一致")
        verifyPromotions(data, device.orgId)
        val rawTradeType = data.get("tradeType")?.takeUnless { it.isJsonNull }
        val tradeType = if (rawTradeType == null) 0 else {
            if (!rawTradeType.isJsonPrimitive) throw ClientError("报价订单类型格式尚未适配")
            rawTradeType.asString.toIntOrNull() ?: throw ClientError("报价订单类型格式尚未适配")
        }
        return Quote(device, mode, money(data.text("realPrice")), items(device, mode), tradeType)
    }

    private fun verifyPromotions(data: JsonObject, orgId: String) {
        val raw = data.get("promotions")?.takeUnless { it.isJsonNull } ?: return
        if (!raw.isJsonArray) throw ClientError("报价优惠数据格式尚未适配")
        fun used(item: JsonObject): Boolean {
            // Original converter uses coerceInputValues=true, including explicit JSON nulls.
            val flag = item.get("isUsed")?.takeUnless { it.isJsonNull } ?: return false
            if (!flag.isJsonPrimitive || !flag.asJsonPrimitive.isBoolean) throw ClientError("报价优惠选中状态格式尚未适配")
            return flag.asBoolean
        }
        fun shape(value: com.google.gson.JsonElement): String = when {
            value.isJsonNull -> "空值"
            value.isJsonArray -> "数组"
            value.isJsonObject -> "对象"
            value.asJsonPrimitive.isString -> "文本"
            value.asJsonPrimitive.isBoolean -> "布尔值"
            else -> "数值"
        }
        fun goldExcluded(item: JsonObject) = item.text("promotionType") == "4" &&
            item.text("promotionId") == "0" && item.text("assetId") == "0" &&
            item.text("oldPromotionId") == "3482" && item.text("orgId") == orgId
        for (entry in raw.asJsonArray) {
            if (!entry.isJsonObject) throw ClientError("报价优惠数据格式尚未适配")
            val promotion = entry.asJsonObject
            val rawType = promotion.get("promotionType")?.takeUnless { it.isJsonNull }
            val promotionType = if (rawType == null) 0 else {
                if (!rawType.isJsonPrimitive) throw ClientError("报价优惠类型尚未适配")
                rawType.asString.toIntOrNull() ?: throw ClientError("报价优惠类型尚未适配")
            }
            var excludesGold = false
            for (key in listOf("options", "participateList")) {
                val options = promotion.get(key)?.takeUnless { it.isJsonNull } ?: continue
                if (!options.isJsonArray) throw ClientError("报价优惠选项格式尚未适配（$key：${shape(options)}）")
                for (option in options.asJsonArray) {
                    if (!option.isJsonObject) throw ClientError("报价优惠选项格式尚未适配（$key[]：${shape(option)}）")
                    if (used(option.asJsonObject)) {
                        if (!goldExcluded(option.asJsonObject)) throw ClientError("报价附加了未选择的优惠，请先核对")
                        excludesGold = true
                    }
                }
            }
            if (used(promotion) && !(promotionType == 4 && excludesGold)) {
                throw ClientError("报价附加了未选择的优惠，请先核对")
            }
        }
    }

    /** Reject agreement-signing payloads; never turn a single wash into a contract. */
    fun verifyAlipay(raw: String, expectedAmount: String) {
        val args = try { raw.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        } } catch (_: Exception) { throw ClientError("支付宝支付参数无效") }
        if (args["method"] != "alipay.trade.app.pay") throw ClientError("当前返回的不是单次支付宝付款")
        val business = try { JsonParser.parseString(args["biz_content"] ?: "").asJsonObject }
        catch (_: Exception) { throw ClientError("支付宝订单内容无法确认") }
        val forbidden = setOf("agreement_sign_params", "agreement_no", "period_rule_params", "sign_scene", "personal_product_code")
        fun containsContract(o: com.google.gson.JsonElement): Boolean = when {
            o.isJsonObject -> o.asJsonObject.entrySet().any { it.key in forbidden || containsContract(it.value) }
            o.isJsonArray -> o.asJsonArray.any { containsContract(it) }
            else -> false
        }
        if (containsContract(business) || args.keys.any { it in forbidden }) throw ClientError("付款包含签约内容，已停止")
        // AlipayTradeAppPayModel documents QUICK_MSECURITY_PAY as the omitted product_code default.
        val rawProduct = business.get("product_code")?.takeUnless { it.isJsonNull }
        if (rawProduct != null && (!rawProduct.isJsonPrimitive || !rawProduct.asJsonPrimitive.isString))
            throw ClientError("支付宝支付产品码格式尚未适配")
        val product = rawProduct?.asString.orEmpty()
        if (product.isNotEmpty() && product != "QUICK_MSECURITY_PAY") {
            val diagnostic = product.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,64}")) } ?: "未知格式"
            throw ClientError("暂不支持此支付宝支付产品（产品码：$diagnostic）")
        }
        if (money(business.text("total_amount")) != money(expectedAmount)) throw ClientError("支付金额与确认金额不一致")
    }
}

fun JsonObject.text(key: String, fallback: String = ""): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
