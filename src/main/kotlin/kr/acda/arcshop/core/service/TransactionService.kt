package kr.acda.arcshop.core.service

import kr.acda.arcshop.core.model.PriceComponent
import kr.acda.arcshop.core.model.Product
import kr.acda.arcshop.core.model.TxFailureReason
import kr.acda.arcshop.core.model.TxResult
import kr.acda.arcshop.core.port.CashPort
import kr.acda.arcshop.core.port.EconomyPort
import kr.acda.arcshop.core.port.ItemStockPort
import kr.acda.arcshop.core.port.PurchaseLimitPort
import java.util.UUID

/**
 * 상점 거래(구매/판매)의 트랜잭션 무결성을 책임지는 순수 로직 계층.
 * Bukkit/ARCCore/arceconomy 어디에도 직접 의존하지 않고 [core.port] 포트로만 상호작용하므로
 * 서버 부팅 없이 단위 테스트 가능하다.
 *
 * 순서: 검증(가능여부/재고제한/보유량) → 차감 → 지급/재고반영. 중간 실패 시 이미 차감된 항목을
 * 되돌려(rollback) 이중 지급/이중 차감을 막는다.
 */
class TransactionService(
    private val economy: EconomyPort,
    private val cash: CashPort,
    private val items: ItemStockPort,
    private val purchaseLimit: PurchaseLimitPort,
) {

    fun purchase(playerId: UUID, product: Product, quantity: Int): TxResult {
        require(quantity > 0) { "quantity must be > 0" }
        if (!product.buyable) return TxResult.Failure(TxFailureReason.NOT_BUYABLE)

        val limit = product.personalPurchaseLimit
        val reserved = if (limit != null) {
            if (!purchaseLimit.tryReserve(playerId, product.id, limit, quantity)) {
                return TxResult.Failure(TxFailureReason.LIMIT_EXCEEDED)
            }
            true
        } else false

        val scaled = scale(product.priceComponents, quantity)
        if (!hasAll(playerId, scaled)) {
            if (reserved) purchaseLimit.release(playerId, product.id, quantity)
            return TxResult.Failure(insufficientReason(scaled, playerId))
        }

        val withdrawn = mutableListOf<PriceComponent>()
        for (component in scaled) {
            val ok = withdrawOne(playerId, component)
            if (!ok) {
                // 방금 전 확인(hasAll)과 경합이 생겨 실패한 경우. 지금까지 차감분을 롤백한다.
                withdrawn.forEach { depositOne(playerId, it) }
                if (reserved) purchaseLimit.release(playerId, product.id, quantity)
                return TxResult.Failure(insufficientReason(listOf(component), playerId))
            }
            withdrawn += component
        }

        if (!items.give(playerId, product.resultItem, quantity)) {
            withdrawn.forEach { depositOne(playerId, it) }
            if (reserved) purchaseLimit.release(playerId, product.id, quantity)
            return TxResult.Failure(TxFailureReason.INTERNAL_ERROR, "인벤토리에 상품을 지급할 수 없습니다.")
        }

        return TxResult.Success
    }

    fun sell(playerId: UUID, product: Product, quantity: Int): TxResult {
        require(quantity > 0) { "quantity must be > 0" }
        if (!product.sellable) return TxResult.Failure(TxFailureReason.NOT_SELLABLE)

        if (items.count(playerId, product.resultItem) < quantity) {
            return TxResult.Failure(TxFailureReason.NOT_OWNED)
        }
        if (!items.remove(playerId, product.resultItem, quantity)) {
            return TxResult.Failure(TxFailureReason.NOT_OWNED)
        }

        val scaled = scale(product.priceComponents, quantity)
        val paid = mutableListOf<PriceComponent>()
        for (component in scaled) {
            val ok = depositOne(playerId, component)
            if (!ok) {
                paid.forEach { withdrawOne(playerId, it) }
                items.give(playerId, product.resultItem, quantity)
                return TxResult.Failure(TxFailureReason.INTERNAL_ERROR, "판매 대금을 지급할 수 없습니다.")
            }
            paid += component
        }

        // 판매는 개인 구매 누적 한도를 되돌리지 않는다 (구매 후 판매 반복으로 한도를 무력화하는 것을 방지).
        return TxResult.Success
    }

    private fun scale(components: List<PriceComponent>, quantity: Int): List<PriceComponent> = components.map {
        when (it) {
            is PriceComponent.EconomyPrice -> it.copy(amount = it.amount * quantity)
            is PriceComponent.CashPrice -> it.copy(amount = it.amount * quantity)
            is PriceComponent.ItemPrice -> it.copy(amount = it.amount * quantity)
        }
    }

    private fun hasAll(playerId: UUID, components: List<PriceComponent>): Boolean = components.all {
        when (it) {
            is PriceComponent.EconomyPrice -> economy.has(playerId, it.amount)
            is PriceComponent.CashPrice -> cash.has(playerId, it.amount)
            is PriceComponent.ItemPrice -> items.count(playerId, it.item) >= it.amount
        }
    }

    private fun insufficientReason(components: List<PriceComponent>, playerId: UUID): TxFailureReason =
        if (components.any { it is PriceComponent.ItemPrice }) TxFailureReason.INSUFFICIENT_ITEMS
        else TxFailureReason.INSUFFICIENT_FUNDS

    private fun withdrawOne(playerId: UUID, component: PriceComponent): Boolean = when (component) {
        is PriceComponent.EconomyPrice -> economy.withdraw(playerId, component.amount)
        is PriceComponent.CashPrice -> cash.withdraw(playerId, component.amount)
        is PriceComponent.ItemPrice -> items.remove(playerId, component.item, component.amount)
    }

    private fun depositOne(playerId: UUID, component: PriceComponent): Boolean = when (component) {
        is PriceComponent.EconomyPrice -> economy.deposit(playerId, component.amount)
        is PriceComponent.CashPrice -> cash.deposit(playerId, component.amount)
        is PriceComponent.ItemPrice -> items.give(playerId, component.item, component.amount)
    }
}
