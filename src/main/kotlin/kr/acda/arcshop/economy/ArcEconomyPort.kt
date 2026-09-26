package kr.acda.arcshop.economy

import kr.acda.arcshop.core.port.EconomyPort
import kr.acda.economy.api.EconomyService
import java.util.UUID

/** [EconomyPort]를 arceconomy의 [EconomyService]에 위임한다. */
class ArcEconomyPort(private val economy: EconomyService) : EconomyPort {
    override fun has(playerId: UUID, amount: Double): Boolean = economy.has(playerId, amount)
    override fun withdraw(playerId: UUID, amount: Double): Boolean = economy.withdraw(playerId, amount).success
    override fun deposit(playerId: UUID, amount: Double): Boolean = economy.deposit(playerId, amount).success
}
