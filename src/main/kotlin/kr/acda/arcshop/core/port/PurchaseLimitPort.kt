package kr.acda.arcshop.core.port

import java.util.UUID

/**
 * 플레이어별 상품 누적 구매 수량(개인 재고 제한) 포트. 프록시 환경에서 서버 간 공유되어야 하므로
 * 중앙 저장소(DB) 위에 구현되어야 하며, 로컬 메모리/파일에 저장하면 안 된다.
 */
interface PurchaseLimitPort {
    /** 현재까지 누적 구매 수량. */
    fun purchasedCount(playerId: UUID, productId: String): Int

    /**
     * [limit] 을 넘지 않는 선에서 [quantity] 만큼 원자적으로 예약(증가)한다.
     * `purchased + quantity <= limit` 인 경우에만 증가시키고 true, 아니면 아무것도 바꾸지 않고 false.
     * (check-then-increment 를 하나의 원자적 연산으로 묶어 동시 구매 경합을 방지한다.)
     */
    fun tryReserve(playerId: UUID, productId: String, limit: Int, quantity: Int): Boolean

    /** [tryReserve] 이후 트랜잭션의 나머지 단계가 실패했을 때 예약을 되돌린다. */
    fun release(playerId: UUID, productId: String, quantity: Int)
}
