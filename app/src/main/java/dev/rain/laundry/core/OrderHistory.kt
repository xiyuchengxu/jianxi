package dev.rain.laundry.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class OrderWeek(
    val start: LocalDate,
    val end: LocalDate,
    val title: String,
    val orders: List<LocalOrder>
)

/** Local account history is separate from the current purchase / payment intent. */
object OrderHistory {
    const val LIMIT = 20

    fun sameOrder(a: LocalOrder, b: LocalOrder): Boolean {
        if (a.owner != b.owner || a.isWater != b.isWater) return false
        // Even a shared number cannot reconcile conflicting strong identifiers.
        if (a.orderId.isNotBlank() && b.orderId.isNotBlank() && a.orderId != b.orderId) return false
        if (a.orderNo.isNotBlank() && b.orderNo.isNotBlank() && a.orderNo != b.orderNo) return false
        if (a.orderId.isNotBlank() && a.orderId == b.orderId) return true
        if (a.orderNo.isNotBlank() && a.orderNo == b.orderNo) return true
        val aHasIdentity = a.orderId.isNotBlank() || a.orderNo.isNotBlank()
        val bHasIdentity = b.orderId.isNotBlank() || b.orderNo.isNotBlank()
        if (aHasIdentity && bHasIdentity) return false
        // CREATING -> CREATED receives the server identifiers after the same saved intent.
        return a.createdAt == b.createdAt && a.deviceName == b.deviceName &&
            a.modeName == b.modeName && a.payMethod == b.payMethod && a.amount == b.amount
    }

    /** Later entries (and incoming) carry the latest state; updates do not move creation time. */
    fun merge(owner: String, existing: List<LocalOrder>, incoming: LocalOrder? = null): List<LocalOrder> {
        if (owner.isBlank()) return emptyList()
        val result = mutableListOf<LocalOrder>()
        for (order in existing + listOfNotNull(incoming)) {
            if (order.owner != owner) continue
            val index = result.indexOfFirst { sameOrder(it, order) }
            if (index < 0) result.add(order)
            else {
                val previous = result[index]
                result[index] = order.copy(
                    createdAt = previous.createdAt,
                    orderId = order.orderId.ifBlank { previous.orderId },
                    orderNo = order.orderNo.ifBlank { previous.orderNo },
                    thirdChannel = order.thirdChannel ?: previous.thirdChannel,
                    outVoucherId = order.outVoucherId ?: previous.outVoucherId,
                    category = order.category ?: previous.category,
                    syncRequested = order.syncRequested || previous.syncRequested
                )
            }
        }
        return result.sortedWith(compareByDescending<LocalOrder> { it.createdAt }.thenBy(::key)).take(LIMIT)
    }

    fun key(order: LocalOrder): String {
        val identity = when {
            order.orderNo.isNotBlank() -> listOf("number", order.orderNo, order.orderId)
            order.orderId.isNotBlank() -> listOf("id", order.orderId)
            else -> listOf("attempt", order.createdAt.toString(), order.deviceName, order.modeName,
                order.isWater.toString(), order.payMethod.name, order.amount)
        }
        // Length prefixes keep arbitrary device / order strings from colliding with delimiters.
        return (listOf(order.owner, order.isWater.toString()) + identity).joinToString("") { "${it.length}:$it" }
    }

    fun weeklyGroups(
        orders: List<LocalOrder>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)
    ): List<OrderWeek> {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return orders.groupBy { order ->
            Instant.ofEpochMilli(order.createdAt).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }.toSortedMap(compareByDescending { it }).map { (start, records) ->
            val end = start.plusDays(6)
            val prefix = when (start) {
                thisWeek -> "本周 · "
                thisWeek.minusWeeks(1) -> "上周 · "
                else -> ""
            }
            OrderWeek(start, end, "$prefix$start 至 $end",
                records.sortedWith(compareByDescending<LocalOrder> { it.createdAt }.thenBy(::key)))
        }
    }
}
