package kr.acda.arcshop.core.model

/**
 * 아이템의 완전한 동일성 판정 키. Material + 수량만으로 비교하지 않고, ItemMeta(이름/설명/인챈트 등)
 * 까지 포함한 정규화된 값이어야 한다 (수량은 제외 — 수량은 [PriceComponent.ItemPrice.amount] 로 별도 관리).
 *
 * 실제 값은 Bukkit 계층([kr.acda.arcshop.item] 패키지)에서 `ItemStack`을 정규화해 생성한다.
 */
@JvmInline
value class ItemSignature(val key: String)
