package kr.acda.arcshop.db

import org.jetbrains.exposed.v1.core.Table

/** 캐시(Cash) 잔액. 플레이어 UUID 당 한 행. */
internal object CashBalances : Table("arcshop_cash_balances") {
    val playerId = varchar("player_id", 36)
    val balance = long("balance")
    override val primaryKey = PrimaryKey(playerId)
}

/**
 * 플레이어별 상품 누적 구매 수량. `key = "<playerId>:<productId>"` 단일 컬럼을 기본키로 써서
 * 복합 WHERE(AND) 조합 없이 단일 `eq` 비교만으로 원자적 조회/갱신이 가능하게 한다.
 */
internal object PurchaseCounters : Table("arcshop_purchase_counters") {
    val key = varchar("key", 100)
    val purchased = integer("purchased")
    override val primaryKey = PrimaryKey(key)
}

internal fun counterKey(playerId: String, productId: String) = "$playerId:$productId"
