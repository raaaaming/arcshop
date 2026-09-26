package kr.acda.arcshop.db

import kr.acda.arcshop.core.port.PurchaseLimitPort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * [PurchaseLimitPort]를 공유 DB(Exposed) 위에서 구현한다. `tryReserve`는 같은 행을
 * `SELECT ... FOR UPDATE`(forUpdate)로 잠근 뒤 확인-증가를 한 트랜잭션 안에서 수행해, 여러 서버가
 * 동시에 같은 (player, product) 조합을 구매해도 한도를 넘기지 않는다.
 *
 * ponytail: forUpdate는 대상 DB가 실제 행 잠금을 지원할 때(MySQL/MariaDB InnoDB 등)만 완전히
 * 안전하다. 로컬 SQLite 폴백에서는 단일 프로세스 안에서만 의미가 있다 — 진짜 다중 서버 분산 락이
 * 필요해지면 그때 별도 락 서비스로 승격.
 */
class JdbcPurchaseLimitPort(private val db: Database) : PurchaseLimitPort {

    init {
        transaction(db) { SchemaUtils.create(PurchaseCounters) }
    }

    override fun purchasedCount(playerId: UUID, productId: String): Int = transaction(db) {
        val key = counterKey(playerId.toString(), productId)
        PurchaseCounters.selectAll().where { PurchaseCounters.key eq key }.firstOrNull()?.get(PurchaseCounters.purchased) ?: 0
    }

    override fun tryReserve(playerId: UUID, productId: String, limit: Int, quantity: Int): Boolean = transaction(db) {
        val key = counterKey(playerId.toString(), productId)
        val row = PurchaseCounters.selectAll().where { PurchaseCounters.key eq key }.forUpdate().firstOrNull()
        val current = row?.get(PurchaseCounters.purchased) ?: 0
        if (current + quantity > limit) return@transaction false

        if (row == null) {
            PurchaseCounters.insert {
                it[PurchaseCounters.key] = key
                it[purchased] = quantity
            }
        } else {
            PurchaseCounters.update({ PurchaseCounters.key eq key }) { it[purchased] = current + quantity }
        }
        true
    }

    override fun release(playerId: UUID, productId: String, quantity: Int): Unit = transaction(db) {
        val key = counterKey(playerId.toString(), productId)
        val row = PurchaseCounters.selectAll().where { PurchaseCounters.key eq key }.forUpdate().firstOrNull()
        val current = row?.get(PurchaseCounters.purchased) ?: 0
        val next = (current - quantity).coerceAtLeast(0)
        if (row != null) {
            PurchaseCounters.update({ PurchaseCounters.key eq key }) { it[purchased] = next }
        }
    }
}
