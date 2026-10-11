package kr.acda.arcshop.command

import kr.acda.arccore.api.command.ARCCommand
import kr.acda.arccore.api.command.CommandContext
import kr.acda.arccore.api.command.CommandMetadata
import kr.acda.arccore.api.command.CommandResult
import kr.acda.arcshop.gui.ShopGuiListener
import kr.acda.arcshop.item.BukkitItemSignatures
import kr.acda.arcshop.web.ShopWebController
import org.bukkit.Bukkit
import java.util.logging.Logger

/**
 * `/shop [카테고리]` — 플레이어용 상점 오픈. ARCLink 클라이언트가 연결된 플레이어에게는 MCEF
 * 브라우저 상점 UI를, 그렇지 않으면(모드 없음/미연결/ARCLink 플러그인 자체가 없음) 기존 인벤토리
 * GUI를 연다 — [web]이 null이거나 [ShopWebController.tryOpen]이 false를 반환하는 모든 경우가
 * 자동으로 인벤토리 GUI로 폴백된다.
 *
 * 진단용 로그(DECISIONS.md #12 참고): "/shop 쳤는데 서버 로그도 없고 화면도 안 뜬다"는 증상은
 * 커맨드가 execute()까지 도달했는지조차 알 수 없게 만든다. 아래 log.info 라인들은 정확히 어느
 * 분기에서 멈췄는지(플레이어 조회 실패 / web 널 / tryOpen 실패 / GUI 폴백 시도) 서버 콘솔에
 * 남기기 위한 임시 계측이다 — 원인이 확정되면 제거해도 된다.
 */
class ShopCommand(
    private val gui: ShopGuiListener,
    private val web: () -> ShopWebController?,
) : ARCCommand {
    private val log = Logger.getLogger("ARCShop")

    override val metadata = CommandMetadata(
        name = "shop",
        description = "상점을 엽니다.",
        usage = "/shop [카테고리]",
    )

    override fun execute(context: CommandContext): CommandResult {
        log.info("[ARCShop] /shop 실행: sender=${context.sender.name}")
        val player = Bukkit.getPlayerExact(context.sender.name)
        if (player == null) {
            log.warning("[ARCShop] /shop: Bukkit.getPlayerExact(\"${context.sender.name}\")가 null을 반환함 — 플레이어를 찾지 못함")
            return CommandResult.Failure("§c플레이어만 사용할 수 있습니다.")
        }
        val controller = web()
        if (controller == null) {
            log.info("[ARCShop] /shop: ShopWebController가 null임 (ARCLink 모듈 미탐지) — 인벤토리 GUI로 폴백")
        }
        val opened = controller?.tryOpen(player) ?: false
        log.info("[ARCShop] /shop: tryOpen 결과=$opened (player=${player.name})")
        if (!opened) {
            log.info("[ARCShop] /shop: 인벤토리 GUI 폴백 호출 (openCategoryMenu)")
            gui.openCategoryMenu(player)
        }
        return CommandResult.Success
    }
}

/** `/arcshop reload|capture` — 관리자 명령. */
class ArcShopAdminCommand(
    private val reload: () -> Unit,
) : ARCCommand {
    override val metadata = CommandMetadata(
        name = "arcshop",
        permission = "arcshop.admin",
        description = "ARCShop 관리 명령.",
        usage = "/arcshop <reload|capture>",
    )

    override fun execute(context: CommandContext): CommandResult {
        return when (context.subCommand) {
            "reload" -> {
                reload()
                context.sender.sendMessage("§aARCShop 설정을 다시 불러왔습니다.")
                CommandResult.Success
            }
            "capture" -> capture(context)
            else -> CommandResult.InvalidUsage(metadata.usage)
        }
    }

    private fun capture(context: CommandContext): CommandResult {
        val player = Bukkit.getPlayerExact(context.sender.name)
            ?: return CommandResult.Failure("§c플레이어만 사용할 수 있습니다.")
        val stack = player.inventory.itemInMainHand
        if (stack.type.isAir) return CommandResult.Failure("§c손에 아이템을 들고 사용하세요.")
        val signature = BukkitItemSignatures.signatureOf(stack)
        player.sendMessage("§7아이템 키(shop.yml에 붙여넣기): §f${signature.key}")
        return CommandResult.Success
    }

    override fun onTabComplete(context: CommandContext): List<String> =
        if (context.args.size <= 1) listOf("reload", "capture").filter { it.startsWith(context.args.getOrElse(0) { "" }, true) }
        else emptyList()
}
