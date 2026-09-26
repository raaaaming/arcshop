package kr.acda.arcshop.core.model

/** 상품 가격을 구성하는 한 요소. 하나의 상품은 여러 개를 동시에 가질 수 있다 (전부 AND 조건). */
sealed interface PriceComponent {
    data class EconomyPrice(val amount: Double) : PriceComponent {
        init { require(amount > 0) { "amount must be > 0" } }
    }

    data class CashPrice(val amount: Long) : PriceComponent {
        init { require(amount > 0) { "amount must be > 0" } }
    }

    data class ItemPrice(val item: ItemSignature, val amount: Int) : PriceComponent {
        init { require(amount > 0) { "amount must be > 0" } }
    }
}
