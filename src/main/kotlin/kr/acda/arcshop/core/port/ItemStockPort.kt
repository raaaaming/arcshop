package kr.acda.arcshop.core.port

import kr.acda.arcshop.core.model.ItemSignature
import java.util.UUID

/** 플레이어 인벤토리(아이템 화폐/상품) 접근 포트. 비교는 항상 [ItemSignature] 완전 일치 기준이다. */
interface ItemStockPort {
    fun count(playerId: UUID, item: ItemSignature): Int
    fun remove(playerId: UUID, item: ItemSignature, amount: Int): Boolean
    fun give(playerId: UUID, item: ItemSignature, amount: Int): Boolean
}
