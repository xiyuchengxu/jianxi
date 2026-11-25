package dev.rain.laundry.core

import com.google.gson.JsonObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * What the server says about the wash run, kept separate from payment/persistence.
 *
 * The original ordinary-laundry UI treats server orderStatus=3 as completed. A local
 * countdown reaching zero never changes this state; metered orderType=3 is excluded.
 */
enum class LaundryRunState { RUNNING, FINISHED, UNKNOWN }

/**
 * Gson-serializable snapshot of one laundry order's timing contract.
 *
 * Field evidence (supplied APK, 1.144.0):
 * - `OrderDetailsBean.markMinutes`: String, total marked minutes; original UI parses it
 *   with `Integer.parseInt` and passes it to `RemainTimeTicker.renderProgress`.
 * - `OrderDetailsBean.completeTime`: String, expected completion time; original UI feeds
 *   it to `RemainTimeTicker.bind` and pauses/hides the ticker when it expires.
 * - `OrderDetailsBean.finishTime`: String, order/use finish time; original UI shows
 *   "订单完成时间" for ordinary orders and "结束使用时间" for orderType 3 (water/metered).
 * - `OrderDetailsBean.beginTime`: String, start/unlock time.
 * - `machineId`/`imei`: device binding on the order.
 * - Server times use `yyyy-MM-dd HH:mm:ss` (see DeviceStartupAct.startSuccess).
 */
data class LaundryProgress(
    val orderId: String,
    val orderNo: String,
    val deviceId: String? = null,
    val imei: String? = null,
    val totalMinutes: Int? = null,
    val startedAt: Long? = null,
    val expectedEndAt: Long? = null,
    val finishedAt: Long? = null,
    val orderStatus: RemoteOrderStatus,
    val updatedAt: Long
) {
    val runState: LaundryRunState
        get() = when {
            orderStatus == RemoteOrderStatus.IN_USE -> LaundryRunState.RUNNING
            orderStatus == RemoteOrderStatus.COMPLETED -> LaundryRunState.FINISHED
            else -> LaundryRunState.UNKNOWN
        }

    /** Only an explicit server finish signal may trigger a "finished" notification. */
    val completionConfirmed: Boolean get() = runState == LaundryRunState.FINISHED

    /**
     * Remaining time according to the latest server snapshot. Clamped at zero; a zero
     * value here is not proof that washing finished.
     */
    fun remainingMillis(now: Long): Long? = expectedEndAt?.let { (it - now).coerceAtLeast(0L) }

    /** Remaining share of the marked total, clamped to 0..1; null when timing is unknown. */
    fun remainingFraction(now: Long): Float? {
        val totalMs = totalMinutes?.takeIf { it > 0 }?.toLong()?.times(60_000L) ?: return null
        val remaining = remainingMillis(now) ?: return null
        return (remaining.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}

/** One `order/detail` result: legacy payment/order status plus optional laundry timing. */
data class RemoteOrderDetails(val status: RemoteOrderStatus, val laundryProgress: LaundryProgress?)

/** Pure parsing/state rules for [LaundryProgress]. Optional fields fail open to null. */
object LaundryProgressProtocol {
    private const val SERVER_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private val SERVER_TIME_SHAPE = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    private val SERVER_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /**
     * Parses the original server format exactly. Unknown/blank/malformed values return
     * null instead of being guessed as minutes or epoch values.
     */
    fun parseTimestamp(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (!SERVER_TIME_SHAPE.matches(value)) return null
        return try {
            val format = SimpleDateFormat(SERVER_TIME_PATTERN, Locale.US).apply {
                isLenient = false
                timeZone = SERVER_ZONE
            }
            val position = ParsePosition(0)
            val parsed = format.parse(value, position)
            if (parsed == null || position.index != value.length || position.errorIndex != -1) null else parsed.time
        } catch (_: Exception) {
            null
        }
    }

    /** `markMinutes` is a decimal minutes string in the supplied APK. Non-positive is unknown. */
    fun parseMinutes(raw: String?): Int? = raw?.trim()
        ?.takeIf { it.matches(Regex("""\d{1,6}""")) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    /**
     * Builds a progress snapshot from one `order/detail` response. Only in-use/completed
     * laundry orders get a snapshot; water orders are explicitly excluded so the water
     * flow cannot accidentally read laundry timing fields.
     */
    fun parse(data: JsonObject, order: LocalOrder, status: RemoteOrderStatus, now: Long): LaundryProgress? {
        if (!order.isLaundry || data.text("orderType") == "3") return null
        if (status !in setOf(RemoteOrderStatus.IN_USE, RemoteOrderStatus.COMPLETED)) return null
        return LaundryProgress(
            orderId = data.text("id").ifBlank { order.orderId },
            orderNo = data.text("orderNo").ifBlank { order.orderNo },
            deviceId = data.text("machineId").takeIf { it.isNotBlank() },
            imei = data.text("imei").takeIf { it.isNotBlank() },
            totalMinutes = parseMinutes(data.text("markMinutes")),
            startedAt = parseTimestamp(data.text("beginTime")),
            expectedEndAt = parseTimestamp(data.text("completeTime")),
            finishedAt = parseTimestamp(data.text("finishTime")),
            orderStatus = status,
            updatedAt = now
        )
    }
}
