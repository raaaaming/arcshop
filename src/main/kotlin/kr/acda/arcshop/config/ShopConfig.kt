package kr.acda.arcshop.config

import kr.acda.arcshop.core.model.Category
import kr.acda.arcshop.core.model.ItemSignature
import kr.acda.arcshop.core.model.PriceComponent
import kr.acda.arcshop.core.model.Product
import kr.acda.arcshop.db.SharedDatabaseConfig
import org.bukkit.configuration.file.YamlConfiguration
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
        val resultKey = section.getString("result-item") ?: return null
        val price = (section.getMapList("price")).mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val entry = raw as? Map<String, Any?> ?: return@mapNotNull null
            when (entry["type"]) {
                "economy" -> PriceComponent.EconomyPrice((entry["amount"] as? Number)?.toDouble() ?: return@mapNotNull null)
                "cash" -> PriceComponent.CashPrice((entry["amount"] as? Number)?.toLong() ?: return@mapNotNull null)
                "item" -> {
                    val key = entry["item"] as? String ?: return@mapNotNull null
                    val amount = (entry["amount"] as? Number)?.toInt() ?: return@mapNotNull null
                    PriceComponent.ItemPrice(ItemSignature(key), amount)
                }
                else -> null
            }
        }
        if (price.isEmpty()) return null

        return Product(
            id = productId,
            categoryId = categoryId,
            displayName = section.getString("display-name", productId) ?: productId,
            resultItem = ItemSignature(resultKey),
            priceComponents = price,
            buyable = section.getBoolean("buyable", true),
            sellable = section.getBoolean("sellable", true),
            personalPurchaseLimit = if (section.contains("personal-limit")) section.getInt("personal-limit") else null,
        )
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
        # ARCShop 상점 정의. 아이템 값(result-item, price[].item)은 손으로 쓰지 말고
        # 아이템을 손에 든 채 `/arcshop capture` 로 뽑은 값을 붙여넣으세요.
        categories: {}
    """.trimIndent() + "\n"
}
