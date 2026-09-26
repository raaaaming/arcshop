package kr.acda.arcshop.db

import kr.acda.arcshop.core.port.CashPort
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [CashPort]를 공유 DB(Exposed) 위에서 구현한다 (DECISIONS.md #1). 계정 단위 락으로 단일 JVM 내
 * lost-update 를 막는다 — 여러 서버가 동시에 같은 계정을 건드리는 경합까지 막으려면 향후
 * `SELECT ... FOR UPDATE` 로 승격할 수 있다.
 */
class JdbcCashPort(private val db: Database) : CashPort {

    private val locks = ConcurrentHashMap<UUID, ReentrantLock>()
    private fun lockFor(id: UUID) = locks.computeIfAbsent(id) { ReentrantLock() }

    init {
        transaction(db) { SchemaUtils.create(CashBalances) }
    }

    private fun readOrCreate(playerId: UUID): Long = transaction(db) {
        val id = playerId.toString()
        CashBalances.selectAll().where { CashBalances.playerId eq id }.firstOrNull()?.get(CashBalances.balance)
            ?: run {
                CashBalances.insert {
                    it[CashBalances.playerId] = id
                    it[balance] = 0L
                }
                0L
            }
    }

    private fun writeBalance(playerId: UUID, value: Long) = transaction(db) {
        val id = playerId.toString()
        CashBalances.update({ CashBalances.playerId eq id }) { it[balance] = value }
    }

    override fun has(playerId: UUID, amount: Long): Boolean = readOrCreate(playerId) >= amount

    override fun withdraw(playerId: UUID, amount: Long): Boolean = lockFor(playerId).withLock {
        val current = readOrCreate(playerId)
        if (current < amount) return@withLock false
        writeBalance(playerId, current - amount)
        true
    }

    override fun deposit(playerId: UUID, amount: Long): Boolean = lockFor(playerId).withLock {
        writeBalance(playerId, readOrCreate(playerId) + amount)
        true
    }
}
