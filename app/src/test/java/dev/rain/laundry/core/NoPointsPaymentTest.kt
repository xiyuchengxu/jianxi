package dev.rain.laundry.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NoPointsPaymentTest {
    private val order = LocalOrder("owner", "device", "mode", "4.00", PayMethod.ALIPAY,
        OrderPhase.CREATED, orderId = "order-id", orderNo = "order-no")
    private fun bill(extra: String) = JsonParser.parseString(
        """{"id":"order-id","orderNo":"order-no","payPrice":"4.00"$extra}""").asJsonObject

    @Test fun explicitEmptyDeductionsAndOtherVoucherTypesRemainPayable() {
        NoPointsPayment.verifyBill(bill(",\"promotionList\":[]"), order)
        NoPointsPayment.verifyBill(bill(",\"promotionList\":[],\"discountPrice\":\"\""), order)
        NoPointsPayment.verifyBill(bill(",\"promotionList\":[{\"promotionType\":7,\"discountAmount\":\"1.00\"}],\"discountPrice\":\"1.00\""), order)
    }

    @Test fun optionalPromotionListMayBeOmittedWhenOriginalAndPayablePricesMatch() {
        NoPointsPayment.verifyBill(bill(",\"markPrice\":\"4.00\",\"discountPrice\":\"0.00\""), order)
        NoPointsPayment.verifyBill(bill(",\"promotionList\":null,\"markPrice\":\"4\""), order)
        NoPointsPayment.verifyBill(bill(",\"markPrice\":\"4\",\"discountPrice\":\"\""), order)
    }

    @Test fun missingDeductionsWithoutIndependentFullPriceEvidenceAreUnknown() {
        for (extra in listOf("", ",\"markPrice\":\"5\"", ",\"markPrice\":\"4\",\"discountPrice\":\"1\"",
            ",\"promotionList\":[],\"discountPrice\":\"1\"")) {
            assertThrows(ClientError::class.java) { NoPointsPayment.verifyBill(bill(extra), order) }
        }
    }

    @Test fun actualPointsDeductionsAreRejectedForEveryDeviceAndPaymentMethod() {
        for (category in listOf("00", "02", "04")) for (method in PayMethod.entries) {
            val data = bill(",\"promotionList\":[{\"promotionType\":8,\"discountAmount\":\"0.50\"}]")
            assertThrows(ClientError::class.java) { NoPointsPayment.verifyBill(data, order.copy(category = category, payMethod = method)) }
        }
    }

    @Test fun malformedTypesAndContainersAreNotTreatedAsNoPoints() {
        for (extra in listOf(",\"promotionList\":{}", ",\"promotionList\":[null]",
            ",\"promotionList\":[{}]", ",\"promotionList\":[{\"promotionType\":true}]",
            ",\"promotionList\":[{\"promotionType\":8.5}]")) {
            assertThrows(ClientError::class.java) { NoPointsPayment.verifyBill(bill(extra), order) }
        }
    }

    @Test fun changedAmountOrBillIdentityCannotAuthorizePayment() {
        val valid = bill(",\"promotionList\":[]")
        for (changed in listOf(order.copy(orderId = "other"), order.copy(orderNo = "other"), order.copy(amount = "3.50"))) {
            assertThrows(ClientError::class.java) { NoPointsPayment.verifyBill(valid, changed) }
        }
    }

    @Test fun unusedPointsOffersDoNotBlockButSelectedNestedPointsDo() {
        val unused = JsonParser.parseString("""{"promotions":[{"promotionType":8,"isUsed":false,"discountPrice":"1.00","options":[{"isUsed":false}]}]}""").asJsonObject
        NoPointsPayment.verifyResponse(unused)
        for (key in listOf("options", "participateList")) {
            val selected = JsonParser.parseString("""{"promotions":[{"promotionType":8,"isUsed":false,"$key":[{"isUsed":true}]}]}""").asJsonObject
            assertThrows(ClientError::class.java) { NoPointsPayment.verifyResponse(selected) }
        }
    }

    @Test fun commissionOrdersUseTheirActualPromotionList() {
        NoPointsPayment.verifyBill(bill(",\"commissionTrade\":true,\"orderCommissionVO\":{\"promotions\":[]}"), order)
        assertThrows(ClientError::class.java) {
            NoPointsPayment.verifyBill(bill(",\"promotionList\":[],\"commissionTrade\":true,\"orderCommissionVO\":{\"promotions\":[{\"promotionType\":8}]}"), order)
        }
    }

    @Test fun diagnosticErrorsNeverIncludeRemotePromotionData() {
        val data = bill(",\"promotionList\":[{\"promotionType\":\"private-order-data\"}]")
        val error = assertThrows(ClientError::class.java) { NoPointsPayment.verifyBill(data, order) }
        assertFalse(error.message.orEmpty().contains("private-order-data"))
    }

    @Test fun blockingErrorsUseNeutralActionableMessages() {
        assertEquals("平台为这笔订单应用了不支持的抵扣，已停止付款。请在原应用调整后重试", PointsDeductionDetected().message)
        assertEquals("无法核对订单抵扣明细，已停止付款。请刷新订单后重试", PointsPaymentUnconfirmed().message)
        for (error in listOf(PointsDeductionDetected(), PointsPaymentUnconfirmed())) {
            assertFalse(error.message.orEmpty().contains("积分"))
        }
    }

    @Test fun waterOptOutMatchesTheOriginalExplicitNoPointsChoice() {
        val points = JsonParser.parseString(WaterRules.promotions()).asJsonArray.single {
            it.asJsonObject.text("promotionType") == "-8"
        }.asJsonObject
        assertEquals("0", points.text("assetId"))
        assertEquals("0", points.text("oldPromotionId"))
        assertEquals("0", points.text("promotionId"))
        assertEquals("0", points.text("orgId"))
    }

    @Test fun bothWashAndDryerQuotesRejectPlatformSelectedPoints() {
        for (category in listOf("00", "02")) {
            val duration = if (category == "02") DryerDuration(30, "4.00") else null
            val mode = WashMode("sku", "mode", "4.00", dryerDurations = listOfNotNull(duration))
            val device = Device("device", "device", "imei", category, "shop", "org", 0, listOf(mode))
            val items = PurchaseRules.items(device, mode, duration)
            val data = JsonParser.parseString("""{"items":$items,"realPrice":"4.00","promotions":[{"promotionType":8,"isUsed":true,"options":[]}]}""").asJsonObject
            assertThrows(ClientError::class.java) { PurchaseRules.verifyQuote(data, device, mode, duration) }
        }
    }
}
