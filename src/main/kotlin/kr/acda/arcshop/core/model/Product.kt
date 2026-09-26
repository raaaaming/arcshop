package kr.acda.arcshop.core.model

/**
 * 상점 상품 하나.
 *
 * - [priceComponents]: 구매에 필요한 모든 화폐/아이템 (전부 보유해야 구매 가능, 일부 보유는 불가).
 * - [resultItem]: 구매 시 지급되고, 판매 시 소지해야 하는 상품 자체 아이템.
 * - [personalPurchaseLimit]: 플레이어 1인당 최대 누적 구매 수량. null = 무제한.
 */
data class Product(
    val id: String,
    val categoryId: String,
    val displayName: String,
    val resultItem: ItemSignature,
    val priceComponents: List<PriceComponent>,
    val buyable: Boolean,
    val sellable: Boolean,
    val personalPurchaseLimit: Int? = null,
) {
    init {
        require(priceComponents.isNotEmpty()) { "product '$id' must have at least one price component" }
        require(personalPurchaseLimit == null || personalPurchaseLimit > 0) {
            "personalPurchaseLimit must be > 0 or null"
        }
    }
}
