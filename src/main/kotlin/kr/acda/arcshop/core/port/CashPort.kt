package kr.acda.arcshop.core.port

import java.util.UUID

/** ARCShop 자체 소유 캐시 잔액 포트. 프록시 환경에서도 서버 간 공유되는 중앙 저장소 위에 구현되어야 한다. */
interface CashPort {
    fun has(playerId: UUID, amount: Long): Boolean
    fun withdraw(playerId: UUID, amount: Long): Boolean
    fun deposit(playerId: UUID, amount: Long): Boolean
}
