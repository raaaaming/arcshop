package kr.acda.arcshop

import kr.acda.arccore.api.module.ModuleSpec
import kr.acda.arccore.core.ArcCorePlugin
import kr.acda.arccore.runtime.context.BaseRuntimeModule
import kr.acda.arclink.ArcLinkService
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
import kr.acda.arcshop.web.ShopWebController
import kr.acda.economy.api.EconomyService
import java.io.File

/**
 * ARCShop — Economy/캐시/아이템 3종 상점 모듈. arceconomy 위에서 동작하며, 캐시 잔액과 개인 구매
 * 한도는 프록시 환경에서도 공유되는 중앙 DB에 저장한다 (DECISIONS.md #1).
 *
 * ARCLink는 **선택적** 연동이다(`dependencies = [.., "arclink?"]` — `?`는 ARCCore의 선택적
 * 의존성 표시, DECISIONS.md #4/#5 참고: arcshop은 ARCLink 없이도 완전히 동작해야 한다).
 * 서버에 ARCLink가 있고 플레이어의 클라이언트 모드가 실제로 연결돼 있으면 `/shop`이 MCEF
 * 브라우저 상점 UI를 열고, 그렇지 않으면(플러그인이 아예 없거나, 그 플레이어만 모드가 없거나)
 * 기존 인벤토리 GUI로 자동 대체(fallback)된다.
 */
@ModuleSpec(
    id = "arcshop",
    name = "ARCShop",
    version = "1.0.0",
    description = "Economy/Cash/Item 3-way shop plugin",
    authors = ["raaaaming"],
    // "arclink?" — 선택적(soft) 의존성. ARCCore의 `@ModuleSpec.dependencies`는 `?` 접미사로
    // 선택적 의존성을 표시하며(ARCCore DECISIONS.md #1 참고), 이 모듈이 설치돼 있으면
    // (1) 로드 순서상 arclink가 arcshop보다 먼저 활성화되고 (2) arcshop의 ModuleClassLoader가
    // arclink가 exports 한 `kr.acda.arclink` 패키지를 볼 수 있게 클래스로더가 연결된다.
    // 없으면 그냥 무시되고(WARNING만) arcshop은 정상적으로 로드/활성화된다 — 하드 의존성이
    // 아니므로 arcshop만이 아니라 서버의 다른 ARCCore 모듈이 실패하는 일도 없다.
    dependencies = ["arceconomy", "arclink?"],
    // gson — ShopWebController/ArcLinkService의 `com.google.gson.JsonObject`를 직접 참조하므로
    // 필요하다(arceconomy처럼 Bukkit/서버가 내부적으로 쓰긴 하지만, ARCCore의 모듈 클래스로더
    // 격리는 명시적으로 선언하지 않으면 이를 가려버린다 — 실서버 크래시로 확인:
    // NoClassDefFoundError: com/google/gson/JsonObject, "hidden by module visibility policy").
    // ArcLinkModule의 동일한 gson 선언과 정확히 같은 이유(ARClinkPlugin DECISIONS.md 참고)이고,
    // 서버가 이미 Gson을 제공하므로(Paper 내부 사용) ARCCore가 사설 사본을 만들지 않고 parent로
    // 위임해 공유한다 — arclink가 노출하는 JsonObject와 타입 동일성도 그래서 보장된다.
    libraries = ["org.mariadb.jdbc:mariadb-java-client:3.4.1", "com.google.code.gson:gson:2.11.0"],
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
        val itemStock = PlayerInventoryStockPort()
        val purchaseLimitPort = JdbcPurchaseLimitPort(db)
        val transactionService = TransactionService(
            economy = ArcEconomyPort(economy),
            cash = JdbcCashPort(db),
            items = itemStock,
            purchaseLimit = purchaseLimitPort,
        )

        var catalog = ShopCatalog(config.categories.values, config.products)
        val guiListener = ShopGuiListener(
            transactions = transactionService,
            items = itemStock,
            scheduler = scheduler,
            catalog = { catalog },
        )
        gui = guiListener
        listeners.register(guiListener, ArcCorePlugin.instance)

        // ARCLink는 "arclink?"(선택적 의존성, @ModuleSpec 참고)로 선언돼 있어, 설치돼 있다면
        // ARCCore의 활성화 순서 보장(DependencyGraph — 모든 dependencies는 optional 여부와
        // 무관하게 그래프 엣지가 되어 위상정렬된다)에 따라 arclink의 onEnable()이 이미 끝난
        // 뒤에 arcshop의 onEnable()이 실행된다. 따라서 여기서 바로(스케줄러로 미룰 필요 없이)
        // 조회해도 안전하다.
        //
        // 그럼에도 두 겹으로 방어한다 — 둘 다 "ARCLink 없음/아직 없음"으로 취급해 인벤토리 GUI로
        // 자연스럽게 폴백한다:
        //   - Exception (예: ServiceNotFoundException) — arclink 모듈 자체가 없어 서비스가
        //     등록되지 않은 경우.
        //   - LinkageError (예: NoClassDefFoundError) — 위상정렬이 순환 의존성 등으로 원래
        //     순서로 폴백하는 극히 드문 경우, arclink가 아직 클래스로더에 연결되기 전일 수
        //     있다(ARCCore DefaultModuleLifecycleManager.enableAll의 CircularDependencyException
        //     폴백 분기 참고).
        val arcLink: ArcLinkService? = try {
            services.require(ArcLinkService::class)
        } catch (e: LinkageError) {
            null
        } catch (e: Exception) {
            null
        }

        var web: ShopWebController? = null
        if (arcLink != null) {
            val shopFilesDir = File(arcLink.fileBaseDir(), "arcshop").toPath()
            val controller = ShopWebController(
                arcLink = arcLink,
                filesDir = shopFilesDir,
                transactions = transactionService,
                items = itemStock,
                purchaseLimit = purchaseLimitPort,
                scheduler = scheduler,
                catalog = { catalog },
            )
            controller.register()
            web = controller
            logger.info("ARCShop: ARCLink 감지됨 — 연결된 클라이언트에는 MCEF 브라우저 상점 UI를 사용합니다.")
        }

        commands.register(ShopCommand(guiListener) { web })
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
