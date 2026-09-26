package kr.acda.arcshop.core.service

import kr.acda.arcshop.core.model.ItemSignature
import kr.acda.arcshop.core.model.PriceComponent
import kr.acda.arcshop.core.model.Product
import kr.acda.arcshop.core.model.TxFailureReason
import kr.acda.arcshop.core.model.TxResult
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransactionServiceTest {

    private val player = UUID.randomUUID()
    private val ore = ItemSignature("minecraft:diamond|meta=none")
    private val pickaxe = ItemSignature("minecraft:diamond_pickaxe|meta=magic-shard-enchant")

    private fun newService(
        economy: FakeEconomyPort = FakeEconomyPort(),
        cash: FakeCashPort = FakeCashPort(),
        items: FakeItemStockPort = FakeItemStockPort(),
        limits: FakePurchaseLimitPort = FakePurchaseLimitPort(),
    ) = TransactionService(economy, cash, items, limits) to Fixtures(economy, cash, items, limits)

    private data class Fixtures(
        val economy: FakeEconomyPort,
        val cash: FakeCashPort,
        val items: FakeItemStockPort,
        val limits: FakePurchaseLimitPort,
    )

    private fun simpleProduct(
        price: List<PriceComponent> = listOf(PriceComponent.EconomyPrice(100.0)),
        buyable: Boolean = true,
        sellable: Boolean = true,
        limit: Int? = null,
    ) = Product(
        id = "test-product",
        categoryId = "cat",
        displayName = "Test",
        resultItem = pickaxe,
        priceComponents = price,
        buyable = buyable,
        sellable = sellable,
        personalPurchaseLimit = limit,
    )

    @Test
    fun `purchase succeeds and deducts all price components`() {
        val (service, fx) = newService(economy = FakeEconomyPort(1000.0))
        fx.items.setCount(ore, 10)
        val product = simpleProduct(price = listOf(PriceComponent.EconomyPrice(100.0), PriceComponent.ItemPrice(ore, 2)))

        val result = service.purchase(player, product, 1)

        assertEquals(TxResult.Success, result)
        assertEquals(900.0, fx.economy.balance)
        assertEquals(8, fx.items.count(player, ore))
        assertEquals(1, fx.items.count(player, pickaxe))
    }

    @Test
    fun `purchase scales price and result by quantity`() {
        val (service, fx) = newService(economy = FakeEconomyPort(1000.0))
        val product = simpleProduct(price = listOf(PriceComponent.EconomyPrice(100.0)))

        val result = service.purchase(player, product, 3)

        assertEquals(TxResult.Success, result)
        assertEquals(700.0, fx.economy.balance)
        assertEquals(3, fx.items.count(player, pickaxe))
    }

    @Test
    fun `purchase fails when any single price component is missing, no partial deduction`() {
        val (service, fx) = newService(economy = FakeEconomyPort(1000.0))
        // has economy but not enough item
        fx.items.setCount(ore, 1)
        val product = simpleProduct(price = listOf(PriceComponent.EconomyPrice(100.0), PriceComponent.ItemPrice(ore, 2)))

        val result = service.purchase(player, product, 1)

        assertIs<TxResult.Failure>(result)
        assertEquals(TxFailureReason.INSUFFICIENT_ITEMS, result.reason)
        // nothing was deducted
        assertEquals(1000.0, fx.economy.balance)
        assertEquals(1, fx.items.count(player, ore))
        assertEquals(0, fx.items.count(player, pickaxe))
    }

    @Test
    fun `purchase not buyable fails immediately`() {
        val (service, _) = newService(economy = FakeEconomyPort(1000.0))
        val product = simpleProduct(buyable = false)

        val result = service.purchase(player, product, 1)

        assertIs<TxResult.Failure>(result)
        assertEquals(TxFailureReason.NOT_BUYABLE, result.reason)
    }

    @Test
    fun `purchase rolls back already-withdrawn components when a later withdraw fails under a race`() {
        val economy = FakeEconomyPort(1000.0)
        val (service, fx) = newService(economy = economy)
        fx.items.setCount(ore, 5)
        // Passes the pre-check (hasAll), but the item withdraw itself fails — simulates a concurrent
        // depletion between the check and the withdraw.
        fx.items.failRemoveOnce = true
        val product = simpleProduct(price = listOf(PriceComponent.EconomyPrice(100.0), PriceComponent.ItemPrice(ore, 2)))

        val result = service.purchase(player, product, 1)

        assertIs<TxResult.Failure>(result)
        // economy was withdrawn first, then item withdraw failed -> economy must be refunded
        assertEquals(1000.0, economy.balance)
        assertEquals(5, fx.items.count(player, ore))
        assertEquals(0, fx.items.count(player, pickaxe))
    }

    @Test
    fun `purchase rolls back all withdrawals when final give fails`() {
        val economy = FakeEconomyPort(1000.0)
        val (service, fx) = newService(economy = economy)
        fx.items.setCount(ore, 5)
        fx.items.failGiveOnce = true
        val product = simpleProduct(price = listOf(PriceComponent.EconomyPrice(100.0), PriceComponent.ItemPrice(ore, 2)))

        val result = service.purchase(player, product, 1)

        assertIs<TxResult.Failure>(result)
        assertEquals(TxFailureReason.INTERNAL_ERROR, result.reason)
        assertEquals(1000.0, economy.balance)
        assertEquals(5, fx.items.count(player, ore))
    }

    @Test
    fun `sell requires holding the product item and pays back price`() {
        val (service, fx) = newService(economy = FakeEconomyPort(0.0))
        fx.items.setCount(pickaxe, 1)
        val product = simpleProduct(price = listOf(PriceComponent.EconomyPrice(100.0)))

        val result = service.sell(player, product, 1)

        assertEquals(TxResult.Success, result)
        assertEquals(100.0, fx.economy.balance)
        assertEquals(0, fx.items.count(player, pickaxe))
    }

    @Test
    fun `sell fails when product not owned`() {
        val (service, _) = newService()
        val product = simpleProduct()

        val result = service.sell(player, product, 1)

        assertIs<TxResult.Failure>(result)
        assertEquals(TxFailureReason.NOT_OWNED, result.reason)
    }

    @Test
    fun `sell not sellable fails immediately`() {
        val (service, fx) = newService()
        fx.items.setCount(pickaxe, 1)
        val product = simpleProduct(sellable = false)

        val result = service.sell(player, product, 1)

        assertIs<TxResult.Failure>(result)
        assertEquals(TxFailureReason.NOT_SELLABLE, result.reason)
    }

    @Test
    fun `personal purchase limit blocks buying beyond the cap`() {
        val (service, _) = newService(economy = FakeEconomyPort(10_000.0))
        val product = simpleProduct(limit = 5)

        val first = service.purchase(player, product, 5)
        val second = service.purchase(player, product, 1)

        assertEquals(TxResult.Success, first)
        assertIs<TxResult.Failure>(second)
        assertEquals(TxFailureReason.LIMIT_EXCEEDED, second.reason)
    }

    @Test
    fun `selling does not free up the personal purchase limit`() {
        val (service, fx) = newService(economy = FakeEconomyPort(10_000.0))
        val product = simpleProduct(limit = 1)

        assertEquals(TxResult.Success, service.purchase(player, product, 1))
        assertEquals(TxResult.Success, service.sell(player, product, 1))
        val third = service.purchase(player, product, 1)

        assertIs<TxResult.Failure>(third)
        assertEquals(TxFailureReason.LIMIT_EXCEEDED, third.reason)
    }

    @Test
    fun `reservation is released when funds are insufficient so the limit is not consumed`() {
        val (service, _) = newService(economy = FakeEconomyPort(0.0))
        val product = simpleProduct(limit = 5)

        val failed = service.purchase(player, product, 5)
        assertIs<TxResult.Failure>(failed)

        // limit should not have been consumed by the failed attempt
        val fx = FakeEconomyPort(10_000.0)
        val service2 = TransactionService(fx, FakeCashPort(), FakeItemStockPort(), FakePurchaseLimitPort())
        assertTrue(service2.purchase(player, product, 5) is TxResult.Success)
    }

    @Test
    fun `distinct item metadata are not treated as the same currency or product`() {
        val plainSword = ItemSignature("minecraft:diamond_sword|meta=none")
        val enchantedSword = ItemSignature("minecraft:diamond_sword|meta=sharpness5")
        assertTrue(plainSword != enchantedSword)

        val (service, fx) = newService(economy = FakeEconomyPort(0.0))
        fx.items.setCount(plainSword, 3)
        val product = simpleProduct(price = listOf(PriceComponent.ItemPrice(enchantedSword, 1)))

        val result = service.purchase(player, product, 1)

        assertIs<TxResult.Failure>(result)
        assertEquals(TxFailureReason.INSUFFICIENT_ITEMS, result.reason)
    }
}
