package kr.acda.arcshop.gui

import kr.acda.arcshop.core.model.Category
import kr.acda.arcshop.core.model.PriceComponent
import kr.acda.arcshop.core.model.Product
import kr.acda.arcshop.item.BukkitItemSignatures
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

// 카테고리/상품 개수가 54칸(6줄)을 넘는 경우의 페이지네이션은 기획 요구사항에 없어 구현하지 않는다
// (ponytail: 필요해지면 이 홀더에 페이지 인덱스만 추가하면 됨).

class CategoryMenuHolder(val categories: List<Category>) : InventoryHolder {
    lateinit var inv: Inventory
    override fun getInventory(): Inventory = inv
}

class ProductMenuHolder(val category: Category, val products: List<Product>) : InventoryHolder {
    lateinit var inv: Inventory
    override fun getInventory(): Inventory = inv
}

object ShopMenuFactory {

    fun categoryMenu(categories: List<Category>): Inventory {
        val holder = CategoryMenuHolder(categories)
        val size = ((categories.size + 8) / 9 * 9).coerceIn(9, 54)
        val inv = Bukkit.createInventory(holder, size, "ARCShop")
        categories.forEachIndexed { i, category ->
            inv.setItem(i, icon(Material.CHEST, category.displayName, emptyList()))
        }
        holder.inv = inv
        return inv
    }

    fun productMenu(category: Category, products: List<Product>): Inventory {
        val holder = ProductMenuHolder(category, products)
        val size = ((products.size + 8) / 9 * 9).coerceIn(9, 54)
        val inv = Bukkit.createInventory(holder, size, "ARCShop - ${category.displayName}")
        products.forEachIndexed { i, product ->
            val icon = BukkitItemSignatures.toItemStack(product.resultItem, 1)
            applyLore(icon, product)
            inv.setItem(i, icon)
        }
        holder.inv = inv
        return inv
    }

    private fun icon(material: Material, name: String, lore: List<String>): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { it.setDisplayName(name); it.lore = lore }
        return stack
    }

    private fun applyLore(stack: ItemStack, product: Product) {
        stack.editMeta { meta: ItemMeta ->
            meta.setDisplayName(product.displayName)
            val lore = mutableListOf<String>()
            lore += "§7가격:"
            product.priceComponents.forEach { lore += "§8- ${describe(it)}" }
            lore += ""
            lore += if (product.buyable) "§a좌클릭: 1개 구매 / Shift+좌클릭: 64개 구매" else "§c구매 불가"
            lore += if (product.sellable) "§e우클릭: 1개 판매 / Shift+우클릭: 전체 판매" else "§c판매 불가"
            if (product.personalPurchaseLimit != null) lore += "§7개인 구매 한도: ${product.personalPurchaseLimit}"
            meta.lore = lore
        }
    }

    private fun describe(component: PriceComponent): String = when (component) {
        is PriceComponent.EconomyPrice -> "${component.amount}"
        is PriceComponent.CashPrice -> "${component.amount} 캐시"
        is PriceComponent.ItemPrice -> "아이템 x${component.amount}"
    }
}
