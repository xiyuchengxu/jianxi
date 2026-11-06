package dev.rain.laundry.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class OrderHistoryTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun record(index: Int, owner: String = "owner", water: Boolean = false) = LocalOrder(
        owner, "device-$index", "mode", "1.00", PayMethod.ALIPAY, OrderPhase.CREATED,
        createdAt = index.toLong(), orderId = "id-$index", orderNo = "number-$index",
        category = if (water) "04" else null
    )
    private fun at(time: String): Long = ZonedDateTime.parse(time).toInstant().toEpochMilli()

    @Test fun historyKeepsTwentyNewestAcrossBothBusinessesAndUpdatingAnOldOrderDoesNotBumpIt() {
        val history = (1..26).map { record(it, water = it % 2 == 0) }
        val recent = OrderHistory.merge("owner", history)
        assertEquals((26 downTo 7).map(Int::toLong), recent.map { it.createdAt })
        assertEquals(10, recent.count { it.isWater })
        assertEquals(10, recent.count { !it.isWater })
        val updated = OrderHistory.merge("owner", recent, record(10, water = true).copy(phase = OrderPhase.COMPLETED))
        assertEquals(20, updated.size)
        assertEquals(OrderPhase.COMPLETED, updated.single { it.orderId == "id-10" }.phase)
        assertEquals(26L, updated.first().createdAt)
        assertEquals(recent, OrderHistory.merge("owner", recent, record(1).copy(phase = OrderPhase.COMPLETED)))
    }

    @Test fun creationAndPaymentSnapshotsUpdateOneIntentAndRetainTheStrongIdentity() {
        val attempt = record(1).copy(orderId = "", orderNo = "", phase = OrderPhase.CREATING)
        val created = attempt.copy(orderId = "created-id", orderNo = "created-number", phase = OrderPhase.CREATED)
        val pending = created.copy(phase = OrderPhase.PAYMENT_PENDING)
        val complete = pending.copy(phase = OrderPhase.COMPLETED, syncRequested = true)
        val history = OrderHistory.merge("owner", listOf(attempt, created, pending), complete)
        assertEquals(listOf(complete), history)
        val withoutNumber = complete.copy(orderNo = "", phase = OrderPhase.REFUNDING, createdAt = 99L)
        val refreshed = OrderHistory.merge("owner", history, withoutNumber).single()
        assertEquals("created-number", refreshed.orderNo)
        assertEquals(1L, refreshed.createdAt)
        assertEquals(OrderPhase.REFUNDING, refreshed.phase)
        assertTrue(refreshed.syncRequested)
    }

    @Test fun conflictingIdentifiersAndDifferentAttemptsNeverCollapse() {
        val first = record(1)
        val conflictingId = first.copy(orderId = "another-id")
        val conflictingNumber = first.copy(orderNo = "another-number")
        assertFalse(OrderHistory.sameOrder(first, conflictingId))
        assertFalse(OrderHistory.sameOrder(first, conflictingNumber))
        assertNotEquals(OrderHistory.key(first), OrderHistory.key(conflictingId))
        assertNotEquals(OrderHistory.key(first), OrderHistory.key(conflictingNumber))
        val missing = first.copy(orderId = "", orderNo = "")
        assertFalse(OrderHistory.sameOrder(missing, missing.copy(createdAt = 2L)))
        assertFalse(OrderHistory.sameOrder(missing, missing.copy(category = "04")))
        assertFalse(OrderHistory.sameOrder(missing, missing.copy(payMethod = PayMethod.BALANCE)))
        assertEquals(3, OrderHistory.merge("owner", listOf(first, conflictingId, conflictingNumber)).size)
    }

    @Test fun ownerFilteringAndKeysKeepAccountsSeparateEvenForIdenticalOrderNumbers() {
        val own = record(1)
        val other = own.copy(owner = "other", phase = OrderPhase.COMPLETED)
        assertFalse(OrderHistory.sameOrder(own, other))
        assertNotEquals(OrderHistory.key(own), OrderHistory.key(other))
        assertEquals(listOf(own), OrderHistory.merge("owner", listOf(other, own), other))
        assertEquals(listOf(other), OrderHistory.merge("other", listOf(own, other), own))
        assertTrue(OrderHistory.merge("", listOf(own)).isEmpty())
    }

    @Test fun naturalWeeksStartMondayAcrossYearsAndNewestOrdersComeFirst() {
        val sunday = record(1).copy(createdAt = at("2024-12-29T23:59:59+08:00[Asia/Shanghai]"))
        val monday = record(2).copy(createdAt = at("2024-12-30T00:00:00+08:00[Asia/Shanghai]"))
        val newYear = record(3).copy(createdAt = at("2025-01-01T09:00:00+08:00[Asia/Shanghai]"))
        val groups = OrderHistory.weeklyGroups(listOf(sunday, monday, newYear), shanghai, LocalDate.of(2025, 1, 1))
        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2024, 12, 30), groups[0].start)
        assertEquals(LocalDate.of(2025, 1, 5), groups[0].end)
        assertEquals("本周 · 2024-12-30 至 2025-01-05", groups[0].title)
        assertEquals(listOf(newYear, monday), groups[0].orders)
        assertEquals("上周 · 2024-12-23 至 2024-12-29", groups[1].title)
        assertEquals(listOf(sunday), groups[1].orders)
    }

    @Test fun groupingUsesTheGivenLocalTimezoneAtTheMondayBoundary() {
        val boundary = record(1).copy(createdAt = at("2026-11-08T16:30:00Z[UTC]"))
        val local = OrderHistory.weeklyGroups(listOf(boundary), shanghai, LocalDate.of(2026, 11, 9)).single()
        val utc = OrderHistory.weeklyGroups(listOf(boundary), ZoneId.of("UTC"), LocalDate.of(2026, 11, 9)).single()
        assertEquals(LocalDate.of(2026, 11, 9), local.start)
        assertTrue(local.title.startsWith("本周"))
        assertEquals(LocalDate.of(2026, 11, 2), utc.start)
        assertTrue(utc.title.startsWith("上周"))
    }
}
