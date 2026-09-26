package kr.acda.arcshop.core.service

import kr.acda.arcshop.core.model.ItemSignature
import kr.acda.arcshop.core.port.CashPort
import kr.acda.arcshop.core.port.EconomyPort
import kr.acda.arcshop.core.port.ItemStockPort
import kr.acda.arcshop.core.port.PurchaseLimitPort
import java.util.UUID

/** Mock 화폐/재고 저장소 — 실제 DB/Bukkit 없이 트랜잭션 로직을 검증하기 위한 인메모리 구현. */
class FakeEconomyPort(initial: Double = 0.0) : EconomyPort {
    var balance: Double = initial
    var failWithdrawOnce: Boolean = false
    var failDepositOnce: Boolean = false

    override fun has(playerId: UUID, amount: Double) = balance >= amount

    override fun withdraw(playerId: UUID, amount: Double): Boolean {
        if (failWithdrawOnce) {
            failWithdrawOnce = false
            return false
        }
        if (balance < amount) return false
        balance -= amount
        return true
    }

    override fun deposit(playerId: UUID, amount: Double): Boolean {
        if (failDepositOnce) {
            failDepositOnce = false
            return false
        }
        balance += amount
        return true
    }
}

class FakeCashPort(initial: Long = 0L) : CashPort {
    var balance: Long = initial

    override fun has(playerId: UUID, amount: Long) = balance >= amount

    override fun withdraw(playerId: UUID, amount: Long): Boolean {
        if (balance < amount) return false
        balance -= amount
        return true
    }

    override fun deposit(playerId: UUID, amount: Long): Boolean {
        balance += amount
        return true
    }
}

class FakeItemStockPort : ItemStockPort {
    private val stacks = mutableMapOf<ItemSignature, Int>()
    var failRemoveOnce: Boolean = false
    var failGiveOnce: Boolean = false

    fun setCount(item: ItemSignature, amount: Int) {
        stacks[item] = amount
    }

    override fun count(playerId: UUID, item: ItemSignature): Int = stacks[item] ?: 0

    override fun remove(playerId: UUID, item: ItemSignature, amount: Int): Boolean {
        if (failRemoveOnce) {
            failRemoveOnce = false
            return false
        }
        val current = stacks[item] ?: 0
        if (current < amount) return false
        stacks[item] = current - amount
        return true
    }

    override fun give(playerId: UUID, item: ItemSignature, amount: Int): Boolean {
        if (failGiveOnce) {
            failGiveOnce = false
            return false
        }
        stacks[item] = (stacks[item] ?: 0) + amount
        return true
    }
}

class FakePurchaseLimitPort : PurchaseLimitPort {
    private val purchased = mutableMapOf<Pair<UUID, String>, Int>()

    override fun purchasedCount(playerId: UUID, productId: String): Int = purchased[playerId to productId] ?: 0

    override fun tryReserve(playerId: UUID, productId: String, limit: Int, quantity: Int): Boolean {
        val key = playerId to productId
        val current = purchased[key] ?: 0
        if (current + quantity > limit) return false
        purchased[key] = current + quantity
        return true
    }

    override fun release(playerId: UUID, productId: String, quantity: Int) {
        val key = playerId to productId
        purchased[key] = ((purchased[key] ?: 0) - quantity).coerceAtLeast(0)
    }
}
