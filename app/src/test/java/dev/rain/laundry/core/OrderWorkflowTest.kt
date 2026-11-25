package dev.rain.laundry.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrderWorkflowTest {
    private val device = Device("g1", "测试机", "1234", "WASH", "s1", "o1", 0, emptyList())
    private val mode = WashMode("s1", "标准洗", "4.00")
    private val accepted = Quote(device, mode, "4.00", "[]", 1)
    private val dryer = device.copy(category = "02")
    private val location = LocationFix(30.2, 120.1)
    private class Fake(var response: Quote) : PurchaseGateway {
        var creates = 0; var washQuotes = 0; var dryerQuotes = 0; var failure: Exception? = null
        var paymentIssue: ClientError? = null
        override suspend fun quote(device: Device, mode: WashMode): Quote {
            washQuotes++
            return response
        }
        override suspend fun quoteDryer(device: Device, mode: WashMode, duration: DryerDuration): Quote {
            dryerQuotes++
            check(device.isDryer && duration in mode.dryerDurations)
            return response
        }
        override suspend fun create(quote: Quote, fix: LocationFix): CreatedOrder {
            creates++; delay(10); failure?.let { throw it }; return CreatedOrder("id1", "trade1", paymentIssue)
        }
    }
    @Test fun priceChangeDoesNotCreateOrPersistAnOrder() = runTest {
        val fake = Fake(accepted.copy(amount = "5.00")); var saved: LocalOrder? = null
        val flow = OrderWorkflow(fake, { saved }, { saved = it })
        assertTrue(runCatching { flow.create(accepted, PayMethod.ALIPAY, location, "owner") }.exceptionOrNull() is QuoteChanged)
        assertNull(saved); assertEquals(0, fake.creates)
    }
    @Test fun createdOrderWithBlockedPaymentKeepsItsIdentityForRefreshOrCancellation() = runTest {
        val fake = Fake(accepted).apply { paymentIssue = PointsDeductionDetected() }
        var saved: LocalOrder? = null
        val flow = OrderWorkflow(fake, { saved }, { saved = it })
        assertTrue(runCatching { flow.create(accepted, PayMethod.ALIPAY, location, "owner") }.exceptionOrNull() is PointsDeductionDetected)
        assertEquals(OrderPhase.CREATED, saved?.phase)
        assertEquals("id1", saved?.orderId)
        assertEquals("trade1", saved?.orderNo)
        assertTrue(runCatching { flow.create(accepted, PayMethod.ALIPAY, location, "owner") }.isFailure)
        assertEquals(1, fake.creates)
    }
    @Test fun concurrentTapsCreateAtMostOneOrder() = runTest {
        val fake = Fake(accepted); var saved: LocalOrder? = null
        val flow = OrderWorkflow(fake, { saved }, { saved = it })
        val jobs = (1..2).map { async { runCatching { flow.create(accepted, PayMethod.ALIPAY, location, "owner") } } }
        val results = jobs.awaitAll()
        assertEquals(1, fake.creates); assertEquals(1, results.count { it.isSuccess })
        assertEquals(OrderPhase.CREATED, saved?.phase)
    }
    @Test fun requestFailurePersistsUnconfirmedAndBlocksRetryAfterRestart() = runTest {
        val fake = Fake(accepted).apply { failure = ClientError("network lost") }; var saved: LocalOrder? = null
        val phases = mutableListOf<OrderPhase>()
        val save: (LocalOrder) -> Unit = { saved = it; phases.add(it.phase) }
        runCatching { OrderWorkflow(fake, { saved }, save).create(accepted, PayMethod.ALIPAY, location, "owner") }
        assertEquals(listOf(OrderPhase.CREATING, OrderPhase.UNCONFIRMED), phases)
        val retry = runCatching { OrderWorkflow(fake, { saved }, save).create(accepted, PayMethod.ALIPAY, location, "owner") }
        assertTrue(retry.isFailure); assertEquals(1, fake.creates)
    }
    @Test fun cancellationAlsoPreservesAnUnconfirmedAttempt() = runTest {
        val fake = Fake(accepted).apply { failure = CancellationException() }; var saved: LocalOrder? = null
        runCatching { OrderWorkflow(fake, { saved }, { saved = it }).create(accepted, PayMethod.BALANCE, location, "owner") }
        assertEquals(OrderPhase.UNCONFIRMED, saved?.phase)
    }
    @Test fun explicitRejectionDoesNotPermanentlyLockNewOrders() = runTest {
        val fake = Fake(accepted).apply { failure = BusinessRejected(4001, "设备不可用") }; var saved: LocalOrder? = null
        val flow = OrderWorkflow(fake, { saved }, { saved = it })
        assertTrue(runCatching { flow.create(accepted, PayMethod.ALIPAY, location, "owner") }.exceptionOrNull() is BusinessRejected)
        assertEquals(OrderPhase.REJECTED, saved?.phase)
        fake.failure = null
        val created = flow.create(accepted, PayMethod.ALIPAY, location, "owner")
        assertEquals(OrderPhase.CREATED, created.phase); assertEquals(2, fake.creates)
    }
    @Test fun paymentRouteSurvivesOrderPersistence() = runTest {
        val fake = Fake(accepted); var saved: LocalOrder? = null
        val result = OrderWorkflow(fake, { saved }, { saved = it }).create(accepted, PayMethod.ALIPAY, location, "owner", PayRoute(PayMethod.ALIPAY, "route-1", "voucher-1"))
        assertEquals("route-1", result.thirdChannel); assertEquals("voucher-1", saved?.outVoucherId)
    }
    @Test fun confirmedClosedOrderDoesNotBlockANewExplicitlyConfirmedWash() = runTest {
        val fake = Fake(accepted)
        var saved: LocalOrder? = LocalOrder("owner", "old", "old", "1.00", PayMethod.ALIPAY,
            OrderPhase.CLOSED, orderId = "old-id", orderNo = "old-trade")
        val created = OrderWorkflow(fake, { saved }, { saved = it }).create(accepted, PayMethod.ALIPAY, location, "owner")
        assertEquals(1, fake.creates)
        assertEquals(OrderPhase.CREATED, created.phase)
        assertEquals("id1", created.orderId)
    }
    @Test fun dryerCannotFallBackToAWashQuoteWithoutADuration() = runTest {
        val fake = Fake(accepted.copy(device = dryer)); var saved: LocalOrder? = null
        val error = runCatching { OrderWorkflow(fake, { saved }, { saved = it }).create(
            accepted.copy(device = dryer), PayMethod.ALIPAY, location, "owner") }.exceptionOrNull()
        assertTrue(error is ClientError)
        assertEquals(0, fake.creates)
        assertEquals(0, fake.dryerQuotes)
        assertEquals(0, fake.washQuotes)
        assertNull(saved)
    }
    @Test fun dryerOrderRechecksTheSelectedDurationBeforeCreating() = runTest {
        val duration = DryerDuration(45, "3.00")
        val dryerMode = WashMode("dry", "烘干", "3.00", dryerDurations = listOf(duration))
        val selected = Quote(dryer, dryerMode, "3.00", "[{\"amount\":\"45\"}]", 1, duration)
        val fake = Fake(selected); var saved: LocalOrder? = null
        val created = OrderWorkflow(fake, { saved }, { saved = it }).create(selected, PayMethod.ALIPAY, location, "owner")
        assertEquals(1, fake.dryerQuotes)
        assertEquals(0, fake.washQuotes)
        assertEquals(1, fake.creates)
        assertEquals("02", created.category)
        assertEquals(OrderPhase.CREATED, created.phase)
    }
}
