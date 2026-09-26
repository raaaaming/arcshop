package kr.acda.arcshop

import kr.acda.arccore.api.module.ModuleSpec
import kr.acda.arccore.core.ArcCorePlugin
import kr.acda.arccore.runtime.context.BaseRuntimeModule
import kr.acda.arcshop.command.ArcShopAdminCommand
import kr.acda.arcshop.command.ShopCommand
import kr.acda.arcshop.config.ShopConfigLoader
import kr.acda.arcshop.core.service.TransactionService
import kr.acda.arcshop.db.JdbcCashPort
import kr.acda.arcshop.db.JdbcPurchaseLimitPort
import kr.acda.arcshop.db.SharedDatabaseConfig
import kr.acda.arcshop.db.SharedDatabaseFactory
import kr.acda.arcshop.economy.ArcEconomyPort
import kr.acda.arcshop.gui.ShopCatalog
import kr.acda.arcshop.gui.ShopGuiListener
import kr.acda.arcshop.item.PlayerInventoryStockPort
import kr.acda.economy.api.EconomyService

/**
 * ARCShop — Economy/캐시/아이템 3종 상점 모듈. arceconomy 위에서 동작하며, 캐시 잔액과 개인 구매
 * 한도는 프록시 환경에서도 공유되는 중앙 DB에 저장한다 (DECISIONS.md #1).
 */
@ModuleSpec(
    id = "arcshop",
    name = "ARCShop",
    version = "1.0.0",
    description = "Economy/Cash/Item 3-way shop plugin",
    authors = ["raaaaming"],
    dependencies = ["arceconomy"],
    libraries = ["org.mariadb.jdbc:mariadb-java-client:3.4.1"],
)
class ARCShopModule : BaseRuntimeModule() {

    private var gui: ShopGuiListener? = null

    override fun onEnable() {
        super.onEnable()

        val config = ShopConfigLoader.load(dataFolder)
        val dbConfig = config.database ?: run {
            logger.warn(
                "arcshop.yml 에 database.jdbc-url 이 설정되지 않아 로컬 SQLite로 폴백합니다 — " +
                    "프록시(다중 서버) 환경에서는 캐시 잔액/개인 구매 한도가 서버 간 공유되지 않습니다."
            )
            SharedDatabaseConfig.localSqliteFallback(dataFolder)
        }
        val db = SharedDatabaseFactory.connect(dbConfig)

        val economy = services.require(EconomyService::class)
        val transactionService = TransactionService(
            economy = ArcEconomyPort(economy),
            cash = JdbcCashPort(db),
            items = PlayerInventoryStockPort(),
            purchaseLimit = JdbcPurchaseLimitPort(db),
        )

        var catalog = ShopCatalog(config.categories.values, config.products)
        val guiListener = ShopGuiListener(
            transactions = transactionService,
            items = PlayerInventoryStockPort(),
            scheduler = scheduler,
            catalog = { catalog },
        )
        gui = guiListener
        listeners.register(guiListener, ArcCorePlugin.instance)

        commands.register(ShopCommand(guiListener))
        commands.register(ArcShopAdminCommand(reload = {
            val reloaded = ShopConfigLoader.load(dataFolder)
            catalog = ShopCatalog(reloaded.categories.values, reloaded.products)
        }))

        logger.info("ARCShop enabled — ${config.categories.size}개 카테고리, ${config.products.size}개 상품 로드됨")
    }

    override fun onDisable() {
        gui = null
        super.onDisable()
    }
}
