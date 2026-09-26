package kr.acda.arcshop.item

import kr.acda.arcshop.core.model.ItemSignature
import kr.acda.arcshop.core.port.ItemStockPort
import org.bukkit.Bukkit
import java.util.UUID

/** [ItemStockPort]를 온라인 플레이어의 인벤토리 위에서 구현한다. 플레이어가 오프라인이면 항상 실패한다. */
class PlayerInventoryStockPort : ItemStockPort {

    override fun count(playerId: UUID, item: ItemSignature): Int {
        val player = Bukkit.getPlayer(playerId) ?: return 0
        return player.inventory.contents
            .filterNotNull()
            .filter { BukkitItemSignatures.signatureOf(it) == item }
            .sumOf { it.amount }
    }

    override fun remove(playerId: UUID, item: ItemSignature, amount: Int): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        val inv = player.inventory
        if (count(playerId, item) < amount) return false

        var remaining = amount
        val contents = inv.contents
        for (slot in contents.indices) {
            if (remaining <= 0) break
            val stack = contents[slot] ?: continue
            if (BukkitItemSignatures.signatureOf(stack) != item) continue
            val take = minOf(stack.amount, remaining)
            stack.amount -= take
            remaining -= take
            inv.setItem(slot, if (stack.amount <= 0) null else stack)
        }
        return remaining == 0
    }

    override fun give(playerId: UUID, item: ItemSignature, amount: Int): Boolean {
        val player = Bukkit.getPlayer(playerId) ?: return false
        var remaining = amount
        // 64개 초과 지급(예: Shift+좌클릭으로 여러 개 구매) 시 스택 한도를 넘지 않도록 나눠 지급한다.
        while (remaining > 0) {
            val batch = minOf(remaining, 64)
            val stack = BukkitItemSignatures.toItemStack(item, batch)
            val leftover = player.inventory.addItem(stack)
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            remaining -= batch
        }
        return true
    }
}
