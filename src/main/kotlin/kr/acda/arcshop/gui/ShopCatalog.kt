package kr.acda.arcshop.gui

import kr.acda.arcshop.core.model.Category
import kr.acda.arcshop.core.model.Product

/** 현재 로드된 상점 정의의 스냅샷. `/arcshop reload` 시 통째로 교체된다. */
class ShopCatalog(categories: Collection<Category>, products: Map<String, Product>) {
    val categories: List<Category> = categories.toList()
    private val productsByCategory: Map<String, List<Product>> =
        categories.associate { cat -> cat.id to cat.productIds.mapNotNull { products[it] } }

    fun productsOf(category: Category): List<Product> = productsByCategory[category.id] ?: emptyList()
}
