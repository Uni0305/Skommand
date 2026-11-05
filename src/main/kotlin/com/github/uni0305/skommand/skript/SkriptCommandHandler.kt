package com.github.uni0305.skommand.skript

import ch.njol.skript.lang.Expression
import ch.njol.skript.lang.Trigger
import ch.njol.skript.lang.util.ContextlessEvent
import ch.njol.skript.variables.Variables
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import com.github.uni0305.skommand.command.CommandHandler
import com.github.uni0305.skommand.command.pattern.ArgumentSpec
import com.github.uni0305.skommand.command.pattern.CommandMatcher
import com.github.uni0305.skommand.command.pattern.CommandPattern
import com.github.uni0305.skommand.command.pattern.CompletionTarget
import org.skriptlang.skript.bukkit.command.custom.ArgumentData
import org.skriptlang.skript.bukkit.command.custom.CooldownManager
import org.skriptlang.skript.bukkit.command.custom.ScriptCommandExecutor
import org.skriptlang.skript.bukkit.command.elements.structures.util.ScriptSuggestionProvider.FilteringMode

/** Runs a script's `trigger` and `suggestions` for a [CommandPattern]. */
internal class SkriptCommandHandler(
    pattern: CommandPattern,
    private val arguments: Map<ArgumentSpec, ArgumentData<Any>>,
    private val trigger: Trigger,
    private val suggestionsTrigger: Trigger?,
    private val suggestingArguments: Set<ArgumentData<*>>,
    private val cooldown: CooldownManager?,
    private val usage: Expression<out Component>?,
    private val fallbackUsage: String,
) : CommandHandler {

    private val matcher = CommandMatcher(pattern)
    private val argumentList: List<ArgumentData<*>> = arguments.values.toList()
    private val executor = ScriptCommandExecutor(trigger, argumentList, cooldown)

    override fun execute(sender: CommandSender, label: String, input: String): Boolean {
        val match = matcher.match<Any>(input) { spec, raw ->
            ArgumentBinder.convert(arguments.getValue(spec), raw, sender)
        } ?: return false

        val rawInput = if (input.isEmpty()) label else "$label $input"
        val event = BukkitCommandExecutionEvent(label, rawInput, match.literals, executor, sender)
        // Checked only now so that a mistyped command does not use up the cooldown.
        if (cooldown != null && !cooldown.checkExecution(event, sender)) return true

        for ((spec, argument) in arguments) {
            val value = match.values[spec] ?: defaultValue(argument, event) ?: continue
            event.values[argument.name()] = value
            if (!argument.isAutomaticName) storeVariable(argument, value, event)
        }

        trigger.execute(event)
        event.failureMessage?.let { sender.sendMessage(it.colorIfAbsent(NamedTextColor.RED)) }
        return true
    }

    private fun defaultValue(argument: ArgumentData<Any>, event: Event): Any? {
        val default = argument.defaultValue() ?: return null
        return if (argument.isSingle) default.getSingle(event) else default.getArray(event)
    }

    private fun storeVariable(argument: ArgumentData<Any>, value: Any, event: Event) {
        if (argument.isSingle) {
            Variables.setVariable(argument.name(), value, event, true)
        } else {
            (value as Array<*>).forEachIndexed { index, element ->
                Variables.setVariable("${argument.name()}::${index + 1}", element, event, true)
            }
        }
    }

    override fun sendUsage(sender: CommandSender) {
        val message = usage?.getSingle(ContextlessEvent.get())
            ?: Component.text("Usage: $fallbackUsage", NamedTextColor.RED)
        sender.sendMessage(message)
    }

    override fun suggest(sender: CommandSender, label: String, args: List<String>): List<String> {
        val current = args.lastOrNull().orEmpty()
        val suggestions = linkedSetOf<String>()
        for (target in matcher.complete(args)) {
            when (target) {
                is CompletionTarget.Literal ->
                    if (target.text.startsWith(current, ignoreCase = true)) suggestions += target.text
                is CompletionTarget.Argument ->
                    suggestions += scriptSuggestions(target, sender, label, args)
                        ?: defaultSuggestions(arguments.getValue(target.spec), sender, current)
            }
        }
        return suggestions.toList()
    }

    /** Null if the script has nothing to say about this argument. */
    private fun scriptSuggestions(
        target: CompletionTarget.Argument,
        sender: CommandSender,
        label: String,
        args: List<String>,
    ): List<String>? {
        val argument = arguments.getValue(target.spec)
        val provider = suggestionsTrigger ?: return null
        // Triggers are not safe to run off the main thread.
        if (argument !in suggestingArguments || !Bukkit.isPrimaryThread()) return null

        val previousValues = target.previous.entries.mapNotNull { (spec, raw) ->
            val previous = arguments.getValue(spec)
            ArgumentBinder.convert(previous, raw, sender)?.let { previous to it }
        }.toMap<ArgumentData<*>, Any>()

        val current = args.last()
        val event = BukkitCommandSuggestionEvent(
            label = label,
            fullInput = "$label ${args.joinToString(" ")}",
            input = current,
            previousArguments = argumentList.subList(0, argumentList.indexOf(argument)),
            currentArgument = argument,
            commandSender = sender,
            previousValues = previousValues,
        )
        provider.execute(event)

        val suggested = event.suggestions[argument.name()] ?: return null
        return suggested.map { it.suggestion() }.filter { matches(it, current, event.filteringMode) }
    }

    private fun matches(suggestion: String, input: String, mode: FilteringMode): Boolean = when (mode) {
        FilteringMode.NONE -> true
        FilteringMode.STARTS_WITH -> suggestion.startsWith(input, ignoreCase = true)
        FilteringMode.CONTAINS -> suggestion.contains(input, ignoreCase = true)
    }

    private fun defaultSuggestions(argument: ArgumentData<Any>, sender: CommandSender, current: String): List<String> {
        val type = argument.type().c
        val namesPlayers = type == OfflinePlayer::class.java || Entity::class.java.isAssignableFrom(type) &&
            type.isAssignableFrom(Player::class.java)
        if (!namesPlayers) return emptyList()
        return Bukkit.getOnlinePlayers()
            .filter { sender !is Player || sender.canSee(it) }
            .map { it.name }
            .filter { it.startsWith(current, ignoreCase = true) }
    }
}
