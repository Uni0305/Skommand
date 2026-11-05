package com.github.uni0305.skommand.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand

/** The behaviour a script gives to a [ScriptBukkitCommand]. */
interface CommandHandler {
    /**
     * Runs the command for text typed after its label.
     * @return false if the text does not match the command's usage.
     */
    fun execute(sender: CommandSender, label: String, input: String): Boolean

    fun sendUsage(sender: CommandSender)

    fun suggest(sender: CommandSender, label: String, args: List<String>): List<String>
}

/** Limits which senders can run a command. */
class SenderRestriction(val message: String, val allows: (CommandSender) -> Boolean)

class CommandSettings(
    val name: String,
    val description: String = "",
    val usage: String = "/$name",
    val aliases: List<String> = emptyList(),
    val permission: String? = null,
    val permissionMessage: Component? = null,
    val restriction: SenderRestriction? = null,
)

/** A command declared by a script, registered in the server's command map. */
class ScriptBukkitCommand(
    settings: CommandSettings,
    private val handler: CommandHandler,
) : BukkitCommand(settings.name, settings.description, settings.usage, settings.aliases) {

    private val restriction = settings.restriction

    init {
        permission = settings.permission
        permissionMessage(settings.permissionMessage)
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        if (!testPermission(sender)) return true
        if (restriction != null && !restriction.allows(sender)) {
            sender.sendMessage(Component.text(restriction.message, NamedTextColor.RED))
            return true
        }
        if (!handler.execute(sender, commandLabel, args.joinToString(" "))) {
            handler.sendUsage(sender)
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
        if (!testPermissionSilent(sender)) return emptyList()
        if (restriction != null && !restriction.allows(sender)) return emptyList()
        return handler.suggest(sender, alias, args.toList())
    }
}
