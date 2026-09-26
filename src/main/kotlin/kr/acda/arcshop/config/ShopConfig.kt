package kr.acda.arcshop.config

import kr.acda.arcshop.core.model.Category
import kr.acda.arcshop.core.model.ItemSignature
import kr.acda.arcshop.core.model.PriceComponent
import kr.acda.arcshop.core.model.Product
import kr.acda.arcshop.db.SharedDatabaseConfig
import kr.acda.arcshop.item.BukkitItemSignatures
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** `arcshop.yml`(운영 설정) + `shop.yml`(카테고리/상품 정의) 로드 결과. */
data class ShopConfig(
    val database: SharedDatabaseConfig?,
    val categories: Map<String, Category>,
    val products: Map<String, Product>,
)

/**
 * 상품의 `result-item`/`price[type=item].item` 값은 아이템 완전 일치 키(Base64, DECISIONS.md #3)를
 * 그대로 저장한다. 관리자가 직접 손으로 만들 수 없으므로 `/arcshop capture` 명령으로 손에 든 아이템의
 * 키를 채팅으로 뽑아 붙여넣는 방식을 쓴다 — 별도의 아이템 편집 GUI는 기획 범위 밖이라 만들지 않는다.
 */
object ShopConfigLoader {

    fun load(dataFolder: Path): ShopConfig {
        val arcshopYml = dataFolder.resolve("arcshop.yml")
        val shopYml = dataFolder.resolve("shop.yml")
        ensureDefault(arcshopYml, DEFAULT_ARCSHOP_YML)
        ensureDefault(shopYml, DEFAULT_SHOP_YML)

        val opsConfig = YamlConfiguration.loadConfiguration(arcshopYml.toFile())
        val database = loadDatabaseConfig(opsConfig)

        val shopConfig = YamlConfiguration.loadConfiguration(shopYml.toFile())
        val categories = mutableMapOf<String, Category>()
        val products = mutableMapOf<String, Product>()

        val categoriesSection = shopConfig.getConfigurationSection("categories")
        for (categoryId in categoriesSection?.getKeys(false) ?: emptySet()) {
            val catSection = categoriesSection!!.getConfigurationSection(categoryId) ?: continue
            val productIds = mutableListOf<String>()
            val productsSection = catSection.getConfigurationSection("products")
            for (productId in productsSection?.getKeys(false) ?: emptySet()) {
                val p = productsSection!!.getConfigurationSection(productId) ?: continue
                val product = parseProduct(productId, categoryId, p) ?: continue
                products[productId] = product
                productIds += productId
            }
            categories[categoryId] = Category(
                id = categoryId,
                displayName = catSection.getString("display-name", categoryId) ?: categoryId,
                productIds = productIds,
            )
        }

        return ShopConfig(database, categories, products)
    }

    private fun loadDatabaseConfig(opsConfig: YamlConfiguration): SharedDatabaseConfig? {
        val url = opsConfig.getString("database.jdbc-url")?.takeIf { it.isNotBlank() } ?: return null
        return SharedDatabaseConfig(
            jdbcUrl = url,
            driver = opsConfig.getString("database.driver", "org.mariadb.jdbc.Driver") ?: "org.mariadb.jdbc.Driver",
            user = opsConfig.getString("database.user", "") ?: "",
            password = opsConfig.getString("database.password", "") ?: "",
        )
    }

    private fun parseProduct(
        productId: String,
        categoryId: String,
        section: org.bukkit.configuration.ConfigurationSection,
    ): Product? {
        val resultItem = resolveSignature(section.getString("result-item"), section.getString("result-material"))
            ?: return null
        val price = (section.getMapList("price")).mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val entry = raw as? Map<String, Any?> ?: return@mapNotNull null
            when (entry["type"]) {
                "economy" -> PriceComponent.EconomyPrice((entry["amount"] as? Number)?.toDouble() ?: return@mapNotNull null)
                "cash" -> PriceComponent.CashPrice((entry["amount"] as? Number)?.toLong() ?: return@mapNotNull null)
                "item" -> {
                    val signature = resolveSignature(entry["item"] as? String, entry["material"] as? String)
                        ?: return@mapNotNull null
                    val amount = (entry["amount"] as? Number)?.toInt() ?: return@mapNotNull null
                    PriceComponent.ItemPrice(signature, amount)
                }
                else -> null
            }
        }
        if (price.isEmpty()) return null

        return Product(
            id = productId,
            categoryId = categoryId,
            displayName = section.getString("display-name", productId) ?: productId,
            resultItem = resultItem,
            priceComponents = price,
            buyable = section.getBoolean("buyable", true),
            sellable = section.getBoolean("sellable", true),
            personalPurchaseLimit = if (section.contains("personal-limit")) section.getInt("personal-limit") else null,
        )
    }

    /**
     * 아이템 키를 두 방식으로 받는다: 캡처된 완전 일치 키(`item`/`result-item`, 이름/인챈트 등 커스텀
     * 메타가 있는 아이템용, `/arcshop capture` 로 채집) 또는 순정 바닐라 아이템의 `material`
     * 이름(메타 없는 흔한 재화용, 손으로 바로 쓸 수 있게 하는 지름길).
     */
    private fun resolveSignature(rawKey: String?, rawMaterial: String?): ItemSignature? {
        if (rawKey != null) return ItemSignature(rawKey)
        val material = rawMaterial?.let { Material.matchMaterial(it) } ?: return null
        return BukkitItemSignatures.signatureOf(ItemStack(material))
    }

    private fun ensureDefault(path: Path, content: String) {
        if (!path.exists()) {
            path.parent.toFile().mkdirs()
            path.writeText(content)
        }
    }

    private val DEFAULT_ARCSHOP_YML = """
        # ARCShop 운영 설정
        # database.jdbc-url 을 비워두면 로컬 SQLite로 폴백합니다 — 프록시(다중 서버) 환경에서는
        # 반드시 모든 서버가 접근 가능한 공유 MySQL/MariaDB를 지정하세요 (DECISIONS.md #1 참고).
        database:
          jdbc-url: ""
          driver: "org.mariadb.jdbc.Driver"
          user: ""
          password: ""
    """.trimIndent() + "\n"

    private val DEFAULT_SHOP_YML = """
        # ARCShop 상점 정의 (첫 설치 시 예시 상품으로 채워집니다 — 자유롭게 수정/삭제하세요).
        #
        # 순정 바닐라 아이템(이름/인챈트 등 커스텀 메타 없음)은 result-material / price[].material 에
        # Material 이름만 적으면 됩니다. 이름/설명/인챈트가 붙은 커스텀 아이템은 그 아이템을 손에 든 채
        # `/arcshop capture` 로 뽑은 완전 일치 키를 result-item / price[].item 에 붙여넣으세요.
        categories:
          ore_shop:
            display-name: "&b광물 상점"
            products:
              diamond:
                display-name: "다이아몬드"
                result-material: DIAMOND
                buyable: true
                sellable: true
                price:
                  - type: economy
                    amount: 500
              iron_ingot:
                display-name: "철 주괴"
                result-material: IRON_INGOT
                buyable: true
                sellable: true
                price:
                  - type: economy
                    amount: 50
              emerald:
                display-name: "에메랄드"
                result-material: EMERALD
                buyable: true
                sellable: true
                personal-limit: 64
                price:
                  - type: economy
                    amount: 300
          gear_shop:
            display-name: "&a장비 상점"
            products:
              netherite_ingot:
                display-name: "네더라이트 주괴"
                result-material: NETHERITE_INGOT
                buyable: true
                sellable: false
                price:
                  - type: economy
                    amount: 20000
                  - type: item
                    material: DIAMOND
                    amount: 8
              golden_apple:
                display-name: "황금 사과"
                result-material: GOLDEN_APPLE
                buyable: true
                sellable: true
                personal-limit: 10
                price:
                  - type: cash
                    amount: 50
    """.trimIndent() + "\n"
}
