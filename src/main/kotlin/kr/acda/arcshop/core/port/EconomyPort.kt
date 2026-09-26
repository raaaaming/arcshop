package kr.acda.arcshop.core.port

import java.util.UUID

/**
 * arceconomy 화폐 접근 포트. 순수 트랜잭션 로직(core)이 Bukkit/arceconomy에 직접 의존하지 않도록
 * 분리하는 경계다. 구현은 동기적이어야 하며(스레드 안전은 구현체 책임), 메인 스레드 논블로킹은
 * 이 포트를 호출하는 모듈 계층(스케줄러)에서 보장한다.
 */
interface EconomyPort {
    fun has(playerId: UUID, amount: Double): Boolean
    fun withdraw(playerId: UUID, amount: Double): Boolean
    fun deposit(playerId: UUID, amount: Double): Boolean
}
