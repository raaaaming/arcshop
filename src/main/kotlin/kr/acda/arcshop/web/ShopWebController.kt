package kr.acda.arcshop.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kr.acda.arccore.runtime.context.scheduler.ModuleScheduler
import kr.acda.arclink.ArcLinkService
import kr.acda.arcshop.core.model.ItemSignature
import kr.acda.arcshop.core.model.PriceComponent
import kr.acda.arcshop.core.model.Product
import kr.acda.arcshop.core.model.TxFailureReason
import kr.acda.arcshop.core.model.TxResult
import kr.acda.arcshop.core.port.ItemStockPort
import kr.acda.arcshop.core.port.PurchaseLimitPort
import kr.acda.arcshop.core.service.TransactionService
import kr.acda.arcshop.gui.ShopCatalog
import kr.acda.arcshop.item.BukkitItemSignatures
import org.bukkit.entity.Player
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * MCEF 브라우저 상점 UI의 서버측 절반. [tryOpen]은 ARCLink에 연결된 플레이어에게
 * `open_browser` 신호로 상점 페이지를 띄우고, [handleAction]은 그 페이지의 JS가 `fetch()`로
 * 직접 호출하는 `/a/arcshop` 액션(상태 조회/구매/판매)을 처리한다 — 클라이언트 모드 쪽에는
 * 손대지 않는다(ARClink `TODO.md`/DECISIONS.md 참고: 이미 있는 `open_browser` + 서명된 정적
 * 파일 서빙만으로 페이지를 띄우고, 페이지 자체가 순수 HTTP `fetch()`로 서버와 통신하므로 MCEF의
 * JS↔네이티브 브리지(cefQuery 등)가 전혀 필요 없다).
 *
 * 거래(구매/판매) 처리는 기존 인벤토리 GUI([kr.acda.arcshop.gui.ShopGuiListener])와 정확히 같은
 * 스레딩 패턴(비동기 스케줄러에서 [TransactionService] 호출)을 그대로 재사용한다 — Bukkit
 * 인벤토리 접근이 메인 스레드가 아닌 곳에서 일어나는 것은 기존 GUI 경로에 이미 있던 동작이며,
 * 이 기능이 새로 만든 문제가 아니다(일관성 유지가 목적, ARCShop DECISIONS.md 참고).
 */
class ShopWebController(
	private val arcLink: ArcLinkService,
	private val filesDir: Path,
	private val transactions: TransactionService,
	private val items: ItemStockPort,
	private val purchaseLimit: PurchaseLimitPort,
	private val scheduler: ModuleScheduler,
	private val catalog: () -> ShopCatalog,
) {
	companion object {
		const val ACTION_NAME = "arcshop"
		private const val ASSET_TTL_SECONDS = 600L
		private const val PAGE_TTL_SECONDS = 600L
		private const val ACTION_TTL_SECONDS = 600L
		private const val AWAIT_SECONDS = 10L
	}

	init {
		ShopWebAssets.writeSharedAssets(filesDir)
	}

	/** Registers with [arcLink] — call once, after construction. */
	fun register() {
		arcLink.onHttpAction(ACTION_NAME) { playerId, query -> handleAction(playerId, query) }
	}

	/** @return true if a browser UI was opened (caller should skip the inventory GUI fallback). */
	fun tryOpen(player: Player): Boolean {
		val playerId = player.uniqueId
		if (!arcLink.isConnected(playerId)) return false

		val cssUrl = arcLink.serveFile(playerId, filesDir.resolve("style.css").toFile(), ASSET_TTL_SECONDS) ?: return false
		val jsUrl = arcLink.serveFile(playerId, filesDir.resolve("app.js").toFile(), ASSET_TTL_SECONDS) ?: return false
		val actionUrl = arcLink.signAction(playerId, ACTION_NAME, ACTION_TTL_SECONDS) ?: return false

		val html = ShopWebAssets.renderIndexHtml(cssUrl, jsUrl, actionUrl)
		val htmlFile = filesDir.resolve("index_$playerId.html")
		Files.writeString(htmlFile, html)

		val pageUrl = arcLink.serveFile(playerId, htmlFile.toFile(), PAGE_TTL_SECONDS) ?: return false
		arcLink.send(playerId, "open_browser", JsonObject().apply { addProperty("path", pageUrl) })
		return true
	}

	/** [ArcLinkService.onHttpAction] handler for `arcshop` — runs on an HTTP worker thread. */
	fun handleAction(playerId: UUID, query: Map<String, String>): JsonObject = when (query["op"]) {
		"buy" -> trade(playerId, query, isSell = false)
		"sell" -> trade(playerId, query, isSell = true)
		"state", null -> stateJson(playerId).apply { addProperty("ok", true) }
		else -> errorResponse("BAD_REQUEST", "알 수 없는 요청입니다.")
	}

	private fun trade(playerId: UUID, query: Map<String, String>, isSell: Boolean): JsonObject {
		val product = query["productId"]?.let(::findProduct)
			?: return errorResponse("BAD_REQUEST", "상품을 찾을 수 없습니다.")
		val qty = query["qty"]?.toIntOrNull()
		if (qty == null || qty <= 0) return errorResponse("BAD_REQUEST", "수량이 올바르지 않습니다.")

		val result = runTransaction(playerId, product, qty, isSell)
		val response = stateJson(playerId)
		return when (result) {
			TxResult.Success -> response.apply {
				addProperty("ok", true)
				val verb = if (isSell) "판매" else "구매"
				addProperty("message", "${product.displayName} ${qty}개를 ${verb}했습니다.")
			}
			is TxResult.Failure -> response.apply {
				addProperty("ok", false)
				addProperty("reason", result.reason.name)
				addProperty("message", result.message.ifBlank { failureMessage(result.reason) })
			}
			null -> response.apply {
				addProperty("ok", false)
				addProperty("reason", "INTERNAL_ERROR")
				addProperty("message", "거래 처리 중 오류가 발생했습니다.")
			}
		}
	}

	private fun findProduct(id: String): Product? {
		val cat = catalog()
		return cat.categories.flatMap { cat.productsOf(it) }.find { it.id == id }
	}

	private fun runTransaction(playerId: UUID, product: Product, qty: Int, isSell: Boolean): TxResult? {
		val future = CompletableFuture<TxResult>()
		scheduler.runAsync {
			val result = if (isSell) transactions.sell(playerId, product, qty) else transactions.purchase(playerId, product, qty)
			future.complete(result)
		}
		return try {
			future.get(AWAIT_SECONDS, TimeUnit.SECONDS)
		} catch (e: Exception) {
			null
		}
	}

	private fun stateJson(playerId: UUID): JsonObject {
		val future = CompletableFuture<JsonObject>()
		scheduler.runAsync { future.complete(computeState(playerId)) }
		return try {
			future.get(AWAIT_SECONDS, TimeUnit.SECONDS)
		} catch (e: Exception) {
			JsonObject().apply { add("categories", JsonArray()) }
		}
	}

	private fun computeState(playerId: UUID): JsonObject {
		val root = JsonObject()
		val categoriesArray = JsonArray()
		val cat = catalog()
		for (category in cat.categories) {
			val catObj = JsonObject()
			catObj.addProperty("id", category.id)
			catObj.addProperty("displayName", category.displayName)
			val productsArray = JsonArray()
			for (product in cat.productsOf(category)) {
				productsArray.add(productJson(playerId, product))
			}
			catObj.add("products", productsArray)
			categoriesArray.add(catObj)
		}
		root.add("categories", categoriesArray)
		return root
	}

	private fun productJson(playerId: UUID, product: Product): JsonObject {
		val obj = JsonObject()
		obj.addProperty("id", product.id)
		obj.addProperty("displayName", product.displayName)
		obj.addProperty("buyable", product.buyable)
		obj.addProperty("sellable", product.sellable)
		val priceArray = JsonArray()
		for (component in product.priceComponents) priceArray.add(priceJson(component))
		obj.add("price", priceArray)
		obj.addProperty("personalLimit", product.personalPurchaseLimit)
		obj.addProperty(
			"purchased",
			if (product.personalPurchaseLimit != null) purchaseLimit.purchasedCount(playerId, product.id) else 0,
		)
		obj.addProperty("owned", items.count(playerId, product.resultItem))
		return obj
	}

	private fun priceJson(component: PriceComponent): JsonObject = JsonObject().apply {
		when (component) {
			is PriceComponent.EconomyPrice -> {
				addProperty("type", "economy")
				addProperty("amount", component.amount)
			}
			is PriceComponent.CashPrice -> {
				addProperty("type", "cash")
				addProperty("amount", component.amount)
			}
			is PriceComponent.ItemPrice -> {
				addProperty("type", "item")
				addProperty("amount", component.amount)
				addProperty("label", itemLabel(component.item))
			}
		}
	}

	private fun itemLabel(signature: ItemSignature): String {
		val stack = BukkitItemSignatures.toItemStack(signature, 1)
		val meta = stack.itemMeta
		return if (meta != null && meta.hasDisplayName()) meta.displayName else stack.type.name
	}

	private fun failureMessage(reason: TxFailureReason): String = when (reason) {
		TxFailureReason.NOT_BUYABLE -> "구매할 수 없는 상품입니다."
		TxFailureReason.NOT_SELLABLE -> "판매할 수 없는 상품입니다."
		TxFailureReason.INSUFFICIENT_FUNDS -> "가격을 지불할 재화가 부족합니다."
		TxFailureReason.INSUFFICIENT_ITEMS -> "가격으로 필요한 아이템이 부족합니다."
		TxFailureReason.LIMIT_EXCEEDED -> "개인 구매 한도를 초과했습니다."
		TxFailureReason.NOT_OWNED -> "판매할 수량을 소지하고 있지 않습니다."
		TxFailureReason.INTERNAL_ERROR -> "내부 오류가 발생했습니다."
	}

	private fun errorResponse(reason: String, message: String): JsonObject = JsonObject().apply {
		addProperty("ok", false)
		addProperty("reason", reason)
		addProperty("message", message)
	}
}
