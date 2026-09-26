package kr.acda.arcshop.core.model

/** 상점 카테고리 (예: 광물상점, 갑옷상점). 하위에 상품 목록을 가진다. */
data class Category(
    val id: String,
    val displayName: String,
    val productIds: List<String>,
)
