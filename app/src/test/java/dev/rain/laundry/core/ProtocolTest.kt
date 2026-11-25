package dev.rain.laundry.core

import com.google.gson.JsonParser
import com.google.gson.JsonNull
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class ProtocolTest {
    private val device = Device("g1", "测试洗衣机", "123456789", "WASH", "s1", "org", 0, emptyList())
    private val mode = WashMode("sku1", "标准洗", "4.00")
    @Test fun parsesDeviceKeysWithoutOpeningLinks() {
        assertEquals(DeviceCode("IMEI", "123456789"), DeviceCodes.parse("https://example.invalid/?IMEI=123456789"))
        assertEquals(DeviceCode("SN", "ABCD-1234"), DeviceCodes.parse("SN=ABCD-1234"))
        assertEquals(DeviceCode("NQT", "ABCD1234"), DeviceCodes.parse("https://example.invalid/?NQT=ABCD%31%32%33%34"))
    }
    @Test fun rejectsAmbiguousOrInjectedIdentifiers() {
        for (raw in listOf("https://example.invalid/", "IMEI=abcd&SN=efgh", "IMEI=abcd&IMEI=efgh", "SN=abcd%26token%3Dsecret", "SN=%xx")) {
            assertThrows(raw, ClientError::class.java) { DeviceCodes.parse(raw) }
        }
    }
    @Test fun signingMatchesIndependentKnownVectors() {
        assertEquals("a1114633c7431c9dd85b46077bff831dffe57a798bff9e0e8b95803d98cb76d1", WireProtocol.sign("/goods/normal/details", "test-token", "1700000000000"))
        assertEquals("304612052b2b8b47831abf7cda1d1e6e4d2ec550a05f1bfcb6dd8879ce312d84", WireProtocol.riskSign("/trade/create", "test-token", "1700000000000", device.imei, device.category, LocationFix(30.2, 120.1)))
    }
    @Test fun rejectsRoundedOrNegativeAmounts() {
        assertEquals("4.00", money("4"))
        assertThrows(ClientError::class.java) { money("4.001") }
        assertThrows(ClientError::class.java) { money("-1") }
        assertThrows(ClientError::class.java) { money("NaN") }
    }
    private fun quote(items: String = """[{"goodsId":"g1","skuId":"sku1","amount":"1"}]""", promotions: String = "[]") =
        JsonParser.parseString("""{"realPrice":"4.00","tradeType":1,"items":$items,"promotions":$promotions}""").asJsonObject
    @Test fun acceptsExactlySelectedWash() {
        assertEquals("4.00", PurchaseRules.verifyQuote(quote(), device, mode).amount)
    }
    @Test fun washQuoteAllowsOmittedOptionalNumButRejectsChangedNum() {
        val withNum = quote(items = """[{"goodsId":"g1","skuId":"sku1","amount":"1","num":"1"}]""")
        assertEquals("4.00", PurchaseRules.verifyQuote(withNum, device, mode).amount)
        val changed = quote(items = """[{"goodsId":"g1","skuId":"sku1","amount":"1","num":"2"}]""")
        assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(changed, device, mode) }
    }
    @Test fun dryerQuoteBindsAmountAndNumToTheSelectedMinutes() {
        val duration = DryerDuration(60, "3.00", selectedByPlatform = true)
        val dryerMode = WashMode("sku-d", "标准烘", "3.00", dryerDurations = listOf(duration))
        val dryer = device.copy(category = "02")
        val selected = JsonParser.parseString("""{"realPrice":"3.00","items":[{"goodsId":"g1","skuId":"sku-d","amount":"60","soldType":"1","num":"60"}],"promotions":[]}""").asJsonObject
        val result = PurchaseRules.verifyQuote(selected, dryer, dryerMode, duration)
        assertEquals(duration, result.dryerDuration)
        for (item in listOf(
            """{"goodsId":"g1","skuId":"sku-d","amount":"30","soldType":"1","num":"60"}""",
            """{"goodsId":"g1","skuId":"sku-d","amount":"60","soldType":"1","num":"30"}""",
            """{"goodsId":"g1","skuId":"sku-d","amount":"60","soldType":"1"}"""
        )) {
            val data = JsonParser.parseString("""{"realPrice":"3.00","items":[$item],"promotions":[]}""").asJsonObject
            assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(data, dryer, dryerMode, duration) }
        }
    }
    @Test fun rejectsExtraOrDifferentGoodsAndPromotions() {
        assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(quote("""[{"goodsId":"g1","skuId":"sku1"},{"goodsId":"vip"}]"""), device, mode) }
        assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(quote("""[{"goodsId":"g1","skuId":"different"}]"""), device, mode) }
        assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(quote(promotions = """[{"promotionType":"vip"}]"""), device, mode) }
    }
    @Test fun unusedPromotionCatalogueDoesNotMeanAnExtraPurchase() {
        val offered = """[{"promotionType":1,"isUsed":false,"options":[{"promotionId":"vip-offer","isUsed":false}]},{"promotionType":7,"options":[{"promotionId":"coupon-offer"}]}]"""
        assertEquals("4.00", PurchaseRules.verifyQuote(quote(promotions = offered), device, mode).amount)
    }
    @Test fun selectedOrMalformedPromotionsAreStillRejected() {
        for (promotions in listOf(
            """[{"promotionType":1,"isUsed":true,"options":[]}]""",
            """[{"promotionType":7,"isUsed":false,"options":[{"promotionId":"coupon","isUsed":true}]}]""",
            """[{"promotionType":7,"participateList":[{"promotionId":"coupon","isUsed":true}]}]""",
            """[{"promotionType":7,"isUsed":"false"}]"""
        )) assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(quote(promotions = promotions), device, mode) }
    }
    @Test fun explicitNoGoldChoiceIsAllowedButOtherAssetsAreRejected() {
        val excluded = """[{"promotionType":4,"isUsed":true,"options":[{"promotionId":"0","promotionType":4,"assetId":"0","oldPromotionId":"3482","orgId":"org","isUsed":true}]}]"""
        assertEquals("4.00", PurchaseRules.verifyQuote(quote(promotions = excluded), device, mode).amount)
        assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(quote(promotions = excluded.replace("\"assetId\":\"0\"", "\"assetId\":\"123\"")), device, mode) }
    }
    @Test fun jsonNullPromotionListsAreEmpty() {
        for (promotions in listOf(
            """[{"promotionType":7,"isUsed":false,"options":null,"participateList":null}]""",
            """[{"promotionType":7,"options":[],"participateList":null}]""",
            """[{"promotionType":7,"options":null,"participateList":[]}]"""
        )) assertEquals("4.00", PurchaseRules.verifyQuote(quote(promotions = promotions), device, mode).amount)
    }
    @Test fun nullableOptionalQuoteFieldsUseOriginalDefaults() {
        val data = quote(promotions = """[{"promotionType":null,"isUsed":null,"options":[{"isUsed":null}],"participateList":null}]""")
        data.add("tradeType", JsonNull.INSTANCE)
        val result = PurchaseRules.verifyQuote(data, device, mode)
        assertEquals("4.00", result.amount)
        assertEquals(0, result.tradeType)
    }
    @Test fun missingOrNullOptionsNeverHideASelectedPromotion() {
        for (promotions in listOf(
            """[{"promotionType":1,"isUsed":true,"options":null,"participateList":null}]""",
            """[{"promotionType":1,"isUsed":true}]""",
            """[{"promotionType":7,"isUsed":null,"options":[{"isUsed":true}],"participateList":null}]"""
        )) assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(quote(promotions = promotions), device, mode) }
    }
    @Test fun nonArrayPromotionDataIsStillRejectedWithShapeContext() {
        val cases = listOf(
            """[{"promotionType":7,"options":{}}]""" to "options：对象",
            """[{"promotionType":7,"participateList":"private-value"}]""" to "participateList：文本",
            """[{"promotionType":7,"options":[null]}]""" to "options[]：空值"
        )
        for ((promotions, context) in cases) {
            val error = assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(quote(promotions = promotions), device, mode) }
            assertTrue(error.message.orEmpty().contains(context))
            assertFalse(error.message.orEmpty().contains("private-value"))
        }
    }
    private fun alipay(extra: String = "", amount: String = "4.00", method: String = "alipay.trade.app.pay"): String {
        val biz = """{"total_amount":"$amount","product_code":"QUICK_MSECURITY_PAY"$extra}"""
        return "method=$method&biz_content=" + URLEncoder.encode(biz, "UTF-8") + "&sign=not-a-real-payment"
    }
    @Test fun acceptsSingleAlipayPaymentOnlyAtQuotedAmount() { PurchaseRules.verifyAlipay(alipay(), "4.00") }
    @Test fun acceptsAlipayDocumentedDefaultProductCode() {
        for (product in listOf("", ",\"product_code\":null", ",\"product_code\":\"\"")) {
            val business = """{"total_amount":"1.00","out_trade_no":"local-test-trade"$product}"""
            val payload = "method=alipay.trade.app.pay&biz_content=" + URLEncoder.encode(business, "UTF-8") + "&sign=local-test-only"
            PurchaseRules.verifyAlipay(payload, "1.00")
        }
    }
    @Test fun defaultProductNeverAllowsContractsOrUnknownProducts() {
        for (extra in listOf(",\"agreement_sign_params\":{}", ",\"product_code\":\"CYCLE_PAY_AUTH\"",
            ",\"product_code\":\"FAST_INSTANT_TRADE_PAY\"", ",\"product_code\":{}")) {
            val payload = "method=alipay.trade.app.pay&biz_content=" + URLEncoder.encode("""{"total_amount":"1.00"$extra}""", "UTF-8")
            assertThrows(ClientError::class.java) { PurchaseRules.verifyAlipay(payload, "1.00") }
        }
    }
    @Test fun rejectsContractsAndChangedPaymentAmounts() {
        assertThrows(ClientError::class.java) { PurchaseRules.verifyAlipay(alipay(extra = ""","agreement_sign_params":{}"""), "4.00") }
        assertThrows(ClientError::class.java) { PurchaseRules.verifyAlipay(alipay(extra = ""","nested":{"period_rule_params":{}}"""), "4.00") }
        assertThrows(ClientError::class.java) { PurchaseRules.verifyAlipay(alipay(amount = "8.00"), "4.00") }
        assertThrows(ClientError::class.java) { PurchaseRules.verifyAlipay(alipay(method = "alipay.user.agreement.page.sign"), "4.00") }
    }
}
