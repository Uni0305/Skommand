package com.github.uni0305.skommand.skript

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.ParsedCommandNode
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.tree.RootCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.CommandSender
import org.bukkit.command.ProxiedCommandSender
import org.bukkit.entity.Entity
import org.skriptlang.skript.bukkit.command.custom.ArgumentData
import org.skriptlang.skript.bukkit.command.custom.ScriptCommandExecutionEvent
import org.skriptlang.skript.bukkit.command.custom.ScriptCommandExecutor
import org.skriptlang.skript.bukkit.command.elements.structures.util.CommandSuggestionEvent

/*
 * Skript's command syntax (arg-1, sender, suggestions of ...) only works inside its own events.
 * These subclasses make them work without Brigadier: everything they would read from the
 * command source is overridden, so the context below never needs a real source.
 */

private fun executorOf(sender: CommandSender): Entity? = when (sender) {
    is ProxiedCommandSender -> sender.callee as? Entity
    else -> sender as? Entity
}

private fun locationOf(sender: CommandSender): Location = when {
    executorOf(sender) != null -> executorOf(sender)!!.location
    sender is BlockCommandSender -> sender.block.location
    else -> Bukkit.getWorlds().first().spawnLocation
}

/** A context holding only the label and matched literals, which is all that Skript reads from it here. */
private fun contextOf(label: String, input: String, literals: List<String>): CommandContext<CommandSourceStack> {
    val nodes = (listOf(label) + literals).map {
        ParsedCommandNode(LiteralArgumentBuilder.literal<CommandSourceStack>(it).build(), StringRange.at(0))
    }
    return CommandContext(
        null, input, emptyMap(), null, RootCommandNode(), nodes, StringRange.at(0), null, null, false,
    )
}

internal class BukkitCommandExecutionEvent(
    label: String,
    rawInput: String,
    literals: List<String>,
    executor: ScriptCommandExecutor,
    private val commandSender: CommandSender,
) : ScriptCommandExecutionEvent(label, rawInput, executor, contextOf(label, rawInput, literals)) {

    /** Argument values by name. Skript's own map cannot be written from outside its package. */
    val values = HashMap<String, Any>()

    override fun getSender(): CommandSender = commandSender
    override fun getExecutor(): Entity? = executorOf(commandSender)
    override fun getLocation(): Location = locationOf(commandSender)
    override fun getArguments(): Map<String, Any> = values
    override fun getArgument(name: String): Any? = values[name]
}

internal class BukkitCommandSuggestionEvent(
    label: String,
    fullInput: String,
    input: String,
    previousArguments: List<ArgumentData<*>>,
    currentArgument: ArgumentData<*>,
    private val commandSender: CommandSender,
    private val previousValues: Map<ArgumentData<*>, Any>,
) : CommandSuggestionEvent(
    contextOf(label, fullInput, emptyList()),
    previousArguments,
    currentArgument,
    fullInput,
    input,
    fullInput.length - input.length,
) {
    override fun getSender(): CommandSender = commandSender
    override fun getExecutor(): Entity? = executorOf(commandSender)
    override fun getLocation(): Location = locationOf(commandSender)
    override fun getPreviousArgument(argument: ArgumentData<*>): Any? = previousValues[argument]
}
