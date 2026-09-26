package kr.acda.arcshop.gui

import kr.acda.arccore.runtime.context.scheduler.ModuleScheduler
import kr.acda.arcshop.core.model.Category
import kr.acda.arcshop.core.model.Product
import kr.acda.arcshop.core.model.TxResult
import kr.acda.arcshop.core.port.ItemStockPort
import kr.acda.arcshop.core.service.TransactionService
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * 클릭 인터랙션 (기획서 보완 문서 기준):
 * 좌클릭=1개 구매, Shift+좌클릭=64개 구매, 우클릭=1개 판매, Shift+우클릭=소지 전체 판매.
 *
 * 거래는 [scheduler]로 비동기 실행해 메인 스레드를 블로킹하지 않고, 결과 메시지 전송만 동기로
 * 되돌린다 (CLAUDE.md 아키텍처 원칙 3).
 */
class ShopGuiListener(
    private val transactions: TransactionService,
    private val items: ItemStockPort,
    private val scheduler: ModuleScheduler,
    private val catalog: () -> ShopCatalog,
) : Listener {

    fun openCategoryMenu(player: Player) {
        player.openInventory(ShopMenuFactory.categoryMenu(catalog().categories))
    }

    fun openProductMenu(player: Player, category: Category) {
        player.openInventory(ShopMenuFactory.productMenu(category, catalog().productsOf(category)))
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        when (val holder = event.inventory.holder) {
            is CategoryMenuHolder -> {
                event.isCancelled = true
                val category = holder.categories.getOrNull(event.slot) ?: return
                val player = event.whoClicked as? Player ?: return
                openProductMenu(player, category)
            }

            is ProductMenuHolder -> {
                event.isCancelled = true
                val product = holder.products.getOrNull(event.slot) ?: return
                val player = event.whoClicked as? Player ?: return
                handleProductClick(player, product, event.click)
            }
        }
    }

    private fun handleProductClick(player: Player, product: Product, click: ClickType) {
        val isSell = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT
        val quantity = when (click) {
            ClickType.LEFT -> 1
            ClickType.SHIFT_LEFT -> 64
            ClickType.RIGHT -> 1
            ClickType.SHIFT_RIGHT -> items.count(player.uniqueId, product.resultItem)
            else -> return
        }
        if (quantity <= 0) {
            player.sendMessage("§c판매할 수량이 없습니다.")
            return
        }

        scheduler.runAsync {
            val result = if (isSell) transactions.sell(player.uniqueId, product, quantity)
            else transactions.purchase(player.uniqueId, product, quantity)
            scheduler.runSync { notify(player, product, isSell, quantity, result) }
        }
    }

    private fun notify(player: Player, product: Product, isSell: Boolean, quantity: Int, result: TxResult) {
        when (result) {
            TxResult.Success -> {
                val verb = if (isSell) "판매" else "구매"
                player.sendMessage("§a${product.displayName} ${quantity}개를 $verb 했습니다.")
            }
            is TxResult.Failure -> player.sendMessage("§c거래 실패: ${result.reason} ${result.message}".trim())
        }
    }
}
