package dev.rain.laundry.experiment

import com.google.gson.JsonParser
import java.time.LocalDateTime
import java.time.ZoneId

data class DrumCleanFixture(val id: String, val label: String, val response: String,
    val expected: DrumCleanOutcome, val scan: String = "DEMO-MACHINE-A")

/** All IDs, amounts and responses here are invented. None is captured server eligibility. */
object DrumCleanFixtures {
    const val APPOINTMENT_ID = "demo-appointment"
    const val GOODS_ID = "demo-goods"
    val nowMs: Long = LocalDateTime.of(2026, 11, 7, 12, 0)
        .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    val base = """{
      "code":0,"data":{
        "appointmentOrder":{"id":"demo-appointment","goodsId":"demo-goods"},
        "machine":{"machineCode":"DEMO-MACHINE-A","imei":"DEMO-IMEI-A","nqt":"DEMO-NQT-A"},
        "skuItems":[{"skuId":"demo-clean","goodsId":"demo-goods","price":"0.00"}],
        "tzjFlag":false,"tzjCanEndTime":"2026-11-07 12:30:00"
      }
    }""".trimIndent()

    private fun changed(block: (com.google.gson.JsonObject) -> Unit): String =
        JsonParser.parseString(base).asJsonObject.apply(block).toString()
    val cases: List<DrumCleanFixture> = listOf(
        DrumCleanFixture("hidden", "未提供自洁 SKU", changed { it.getAsJsonObject("data").add("skuItems", com.google.gson.JsonArray()) }, DrumCleanOutcome.MISSING_SKU),
        DrumCleanFixture("zero", "零元 SKU（模拟）", base, DrumCleanOutcome.LOCAL_ZERO_ROUTE),
        DrumCleanFixture("paid", "付费 SKU（模拟）", base.replace("\"0.00\"", "\"1.50\""), DrumCleanOutcome.LOCAL_PAID_ROUTE),
        DrumCleanFixture("flag", "禁止使用标记", base.replace("\"tzjFlag\":false", "\"tzjFlag\":true"), DrumCleanOutcome.RESTRICTED),
        DrumCleanFixture("expired", "可用时间已截止", base.replace("12:30:00", "12:00:00"), DrumCleanOutcome.EXPIRED),
        DrumCleanFixture("machine", "扫描另一台机器", base, DrumCleanOutcome.MACHINE_MISMATCH, "DEMO-MACHINE-B"),
        DrumCleanFixture("price", "金额格式异常", base.replace("\"0.00\"", "\"oops\""), DrumCleanOutcome.INVALID_PRICE),
        DrumCleanFixture("missing-price", "缺少金额", changed { it.getAsJsonObject("data").getAsJsonArray("skuItems")[0].asJsonObject.remove("price") }, DrumCleanOutcome.INVALID_PRICE),
        DrumCleanFixture("time", "截止时间无效", base.replace("2026-11-07 12:30:00", "2026-02-30 12:30:00"), DrumCleanOutcome.INVALID_TIME),
        DrumCleanFixture("unknown-flag", "资格标记缺失", changed { it.getAsJsonObject("data").remove("tzjFlag") }, DrumCleanOutcome.UNKNOWN_RESTRICTION),
        DrumCleanFixture("identity", "预约订单不一致", base.replace("demo-appointment", "other-appointment"), DrumCleanOutcome.IDENTITY_MISMATCH),
        DrumCleanFixture("rejected", "服务端拒绝（模拟）", """{"code":403,"msg":"演示拒绝","data":null}""", DrumCleanOutcome.SERVER_REJECTED),
        DrumCleanFixture("inner-only", "只有内层列表有 SKU", changed {
            val data = it.getAsJsonObject("data")
            data.getAsJsonObject("appointmentOrder").add("skuItems", data.getAsJsonArray("skuItems").deepCopy())
            data.add("skuItems", com.google.gson.JsonArray())
        }, DrumCleanOutcome.MISSING_SKU)
    )

    fun inspect(fixture: DrumCleanFixture, forceEntry: Boolean) = DrumCleanInspector.inspect(
        fixture.response, APPOINTMENT_ID, GOODS_ID, fixture.scan, nowMs, forceEntry)
}
