package kr.acda.arcshop.item

import kr.acda.arcshop.core.model.ItemSignature
import org.bukkit.inventory.ItemStack
import java.util.Base64

/**
 * [ItemSignature] ↔ [ItemStack] 변환. 수량을 1로 정규화한 뒤 Paper의 `serializeAsBytes()`로 얻은
 * 바이트(타입 + ItemMeta 전체)를 Base64 키로 쓴다. 이 값이 같다는 것은 곧 `ItemStack.isSimilar()`가
 * true(수량 제외 완전 동일)라는 것과 동치이므로, 이름/설명/인챈트 등을 비교하는 NBT 코드를 직접 짤
 * 필요가 없다 (DECISIONS.md #3).
 */
object BukkitItemSignatures {

    fun signatureOf(stack: ItemStack): ItemSignature {
        val normalized = stack.clone().also { it.amount = 1 }
        return ItemSignature(Base64.getEncoder().encodeToString(normalized.serializeAsBytes()))
    }

    /** [signature]가 표현하는 아이템을 [amount]개 담은 새 ItemStack을 만든다. */
    fun toItemStack(signature: ItemSignature, amount: Int): ItemStack {
        val bytes = Base64.getDecoder().decode(signature.key)
        return ItemStack.deserializeBytes(bytes).also { it.amount = amount }
    }
}
