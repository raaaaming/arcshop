package kr.acda.arcshop.command

import kr.acda.arccore.api.command.ARCCommand
import kr.acda.arccore.api.command.CommandContext
import kr.acda.arccore.api.command.CommandMetadata
import kr.acda.arccore.api.command.CommandResult
import kr.acda.arcshop.gui.ShopGuiListener
import kr.acda.arcshop.item.BukkitItemSignatures
import org.bukkit.Bukkit

/** `/shop [카테고리]` — 플레이어용 상점 오픈. */
class ShopCommand(private val gui: ShopGuiListener) : ARCCommand {
    override val metadata = CommandMetadata(
        name = "shop",
        description = "상점을 엽니다.",
        usage = "/shop [카테고리]",
    )

    override fun execute(context: CommandContext): CommandResult {
        val player = Bukkit.getPlayerExact(context.sender.name)
            ?: return CommandResult.Failure("§c플레이어만 사용할 수 있습니다.")
        gui.openCategoryMenu(player)
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
