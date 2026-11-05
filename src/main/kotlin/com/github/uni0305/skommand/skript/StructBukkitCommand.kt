package com.github.uni0305.skommand.skript

import ch.njol.skript.Skript
import ch.njol.skript.config.Node
import ch.njol.skript.config.SectionNode
import ch.njol.skript.ScriptLoader
import ch.njol.skript.lang.Literal
import ch.njol.skript.lang.SkriptParser.ParseResult
import ch.njol.skript.lang.Trigger
import ch.njol.skript.lang.Variable
import ch.njol.skript.lang.VariableString
import ch.njol.skript.lang.parser.ParserInstance
import ch.njol.skript.util.StringMode
import ch.njol.skript.util.Timespan
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.event.Event
import com.github.uni0305.skommand.command.CommandRegistry
import com.github.uni0305.skommand.command.CommandSettings
import com.github.uni0305.skommand.command.ScriptBukkitCommand
import com.github.uni0305.skommand.command.SenderRestriction
import com.github.uni0305.skommand.command.pattern.ArgumentSpec
import com.github.uni0305.skommand.command.pattern.CommandPattern
import com.github.uni0305.skommand.command.pattern.CommandPatternException
import org.skriptlang.skript.bukkit.command.custom.ArgumentData
import org.skriptlang.skript.bukkit.command.custom.CommandParsingData
import org.skriptlang.skript.bukkit.command.custom.CommandParsingData.ExecutorData
import org.skriptlang.skript.bukkit.command.custom.CooldownManager
import org.skriptlang.skript.bukkit.command.custom.ExecutableBy
import org.skriptlang.skript.bukkit.command.custom.ScriptCommandExecutionEvent
import org.skriptlang.skript.bukkit.command.custom.ScriptCommandRegistrar
import org.skriptlang.skript.bukkit.command.elements.structures.util.CommandSuggestionEvent
import org.skriptlang.skript.bukkit.command.elements.structures.util.SuggestingArgumentData
import org.skriptlang.skript.bukkit.text.TextComponentParser
import org.skriptlang.skript.bukkit.text.TextComponentUtils
import org.skriptlang.skript.lang.entry.EntryContainer
import org.skriptlang.skript.lang.entry.EntryValidator
import org.skriptlang.skript.lang.entry.KeyValueEntryData
import org.skriptlang.skript.lang.entry.util.LiteralEntryData
import org.skriptlang.skript.lang.entry.util.TriggerEntryData
import org.skriptlang.skript.lang.entry.util.VariableStringEntryData
import org.skriptlang.skript.lang.structure.Structure
import org.skriptlang.skript.log.runtime.ErrorSource
import org.skriptlang.skript.log.runtime.RuntimeErrorProducer
import org.skriptlang.skript.registration.DefaultSyntaxInfos
import org.skriptlang.skript.registration.SyntaxRegistry
import java.util.EnumSet

/**
 * ```
 * bukkit command /name <arguments>:
 *     aliases: /other
 *     description: ...
 *     usage: ...
 *     prefix: ...
 *     permission: ...
 *     permission message: ...
 *     executable by: players and console
 *     cooldown: 5 seconds
 *     cooldown message: ...
 *     cooldown bypass: ...
 *     cooldown storage: {cooldown::%player%}
 *     suggestions:
 *         ...
 *     trigger:
 *         ...
 * ```
 */
class StructBukkitCommand : Structure() {

    private lateinit var rawPattern: String
    private lateinit var rootNode: SectionNode
    private var command: ScriptBukkitCommand? = null
    private var commandPrefix = DEFAULT_PREFIX

    override fun init(
        args: Array<out Literal<*>>,
        matchedPattern: Int,
        parseResult: ParseResult,
        entryContainer: EntryContainer?,
    ): Boolean {
        rawPattern = parseResult.regexes.first().group()
        rootNode = entryContainer!!.source
        return true
    }

    override fun load(): Boolean {
        val pattern = try {
            CommandPattern.parse(rawPattern)
        } catch (exception: CommandPatternException) {
            Skript.error(exception.message)
            return false
        }

        val existing = ScriptCommandRegistrar.getCommand(pattern.name)
        if (existing != null) {
            Skript.error(
                "A command with the name /${pattern.name} is already defined in the script " +
                    "'${existing.script().nameAndPath()}.sk'",
            )
            return false
        }

        val entries = VALIDATOR.validate(rootNode) ?: return false
        val built = build(pattern, entries) ?: return false
        if (!registry.claim(built)) {
            Skript.error("A bukkit command with the name /${pattern.name} is already defined.")
            return false
        }
        command = built
        return true
    }

    private fun build(pattern: CommandPattern, entries: EntryContainer): ScriptBukkitCommand? {
        val parser = ParserInstance.get()
        val parsingData = parser.getData(CommandParsingData::class.java)

        parser.setCurrentEvent("command", ScriptCommandExecutionEvent::class.java)
        var eventActive = true
        try {
            val arguments = ArgumentBinder.compile(pattern.arguments) ?: return null
            val argumentList = arguments.values.toList<ArgumentData<*>>()

            @Suppress("UNCHECKED_CAST")
            val aliases = entries.getOptional("aliases", List::class.java, false) as List<String>?
            if (aliases != null && aliases.isEmpty()) return null

            val prefix = entries.getOptional("prefix", String::class.java, false)
            if (prefix != null && !isValidPrefix(prefix)) return null
            commandPrefix = prefix?.lowercase() ?: DEFAULT_PREFIX

            @Suppress("UNCHECKED_CAST")
            val executableBy = entries.getOptional("executable by", Set::class.java, false) as Set<ExecutableBy>?
            if (executableBy != null && executableBy.isEmpty()) return null

            val cooldown = parseCooldown(entries, parsingData)
            val permission = entries.getOptional("permission", String::class.java, false)

            parsingData.pushArguments(argumentList)
            parsingData.pushChoices(pattern.choices)
            parsingData.pushExecutorData(ExecutorData(false, permission, executableBy, cooldown))
            try {
                val trigger = parseTrigger(entries, parser, arguments) ?: return null
                parser.deleteCurrentEvent()
                eventActive = false

                val variableUsage = entries.getOptional("usage", VariableString::class.java, false)
                val (suggestions, suggesting) = parseSuggestions(entries, parser)

                val fallbackUsage = "/${rawPattern.trim().removePrefix("/").trimStart()}"
                val handler = SkriptCommandHandler(
                    pattern = pattern,
                    arguments = arguments,
                    trigger = trigger,
                    suggestionsTrigger = suggestions,
                    suggestingArguments = suggesting,
                    cooldown = cooldown,
                    usage = variableUsage?.let { TextComponentUtils.asComponentExpression(it) },
                    fallbackUsage = fallbackUsage,
                )
                val settings = CommandSettings(
                    name = pattern.name,
                    description = entries.getOptional("description", String::class.java, false).orEmpty(),
                    usage = variableUsage?.takeIf { it.isSimple }?.toString(null) ?: fallbackUsage,
                    aliases = aliases.orEmpty(),
                    permission = permission,
                    permissionMessage = entries.getOptional("permission message", String::class.java, false)
                        ?.let { TextComponentParser.instance().parse(it) },
                    restriction = executableBy?.let(::restrictionOf),
                )
                return ScriptBukkitCommand(settings, handler)
            } finally {
                parsingData.popExecutorData()
                parsingData.popChoices()
                parsingData.popArguments()
            }
        } finally {
            if (eventActive) parser.deleteCurrentEvent()
        }
    }

    private fun isValidPrefix(prefix: String): Boolean {
        if (prefix.any(Char::isWhitespace)) {
            Skript.error("Command prefixes must not have any whitespace.")
            return false
        }
        if ('§' in prefix) {
            Skript.error("Command prefixes must not have any section symbols.")
            return false
        }
        return true
    }

    private fun parseCooldown(entries: EntryContainer, parsingData: CommandParsingData): CooldownManager? {
        parsingData.isParsingCooldownEntry = true
        try {
            val cooldown = entries.getOptional("cooldown", Timespan::class.java, false)
            val message = entries.getOptional("cooldown message", VariableString::class.java, false)
            val bypass = entries.getOptional("cooldown bypass", String::class.java, false)
            val storage = entries.getOptional("cooldown storage", VariableString::class.java, false)
            if (cooldown == null) {
                if (message != null) Skript.warning("There is a cooldown message set, but not a cooldown")
                if (bypass != null) Skript.warning("There is a cooldown bypass set, but not a cooldown")
                if (storage != null) Skript.warning("There is a cooldown storage set, but not a cooldown")
                return null
            }
            val errorSource = ErrorSource.fromNodeAndElement(rootNode, this)
            return CooldownManager(
                cooldown,
                message?.getConvertedExpression(Component::class.java),
                bypass,
                storage,
                RuntimeErrorProducer { errorSource },
            )
        } finally {
            parsingData.isParsingCooldownEntry = false
        }
    }

    private fun parseTrigger(
        entries: EntryContainer,
        parser: ParserInstance,
        arguments: Map<ArgumentSpec, ArgumentData<Any>>,
    ): Trigger? {
        val hints = parser.hintManager
        hints.enterScope(false)
        try {
            for (argument in arguments.values) {
                if (argument.isAutomaticName) continue
                val name = if (argument.isSingle) argument.name() else argument.name() + Variable.SEPARATOR + "*"
                hints.set(name, argument.type().c)
            }
            return entries.getOptional("trigger", Trigger::class.java, false)
        } finally {
            hints.exitScope()
        }
    }

    /** Returns the suggestions trigger, and the arguments its code sets suggestions for. */
    private fun parseSuggestions(
        entries: EntryContainer,
        parser: ParserInstance,
    ): Pair<Trigger?, Set<ArgumentData<*>>> {
        val data = parser.getData(SuggestingArgumentData::class.java)
        parser.setCurrentEvent("command suggestions", CommandSuggestionEvent::class.java)
        try {
            val trigger = entries.getOptional("suggestions", Trigger::class.java, false)
            return trigger to data.arguments.toSet()
        } finally {
            parser.deleteCurrentEvent()
            data.arguments.clear()
        }
    }

    private fun restrictionOf(executableBy: Set<ExecutableBy>): SenderRestriction {
        val allowed = executableBy.joinToString(" and ") { it.toString() }
        return SenderRestriction("This command can only be used by $allowed.") { sender: CommandSender ->
            executableBy.any { it.predicate().test(sender) }
        }
    }

    override fun postLoad(): Boolean {
        val declared = command ?: return true
        // A Skript command in the same batch may have loaded after this structure's load().
        if (ScriptCommandRegistrar.getCommand(declared.name) != null) {
            Skript.error("A command with the name /${declared.name} is already defined by Skript's 'command' structure.")
            registry.release(declared)
            command = null
            return false
        }
        registry.register(declared, commandPrefix)
        return true
    }

    override fun unload() {
        command?.let(registry::unregister)
        command = null
    }

    override fun toString(event: Event?, debug: Boolean): String = "bukkit command"

    companion object {
        private const val DEFAULT_PREFIX = "skript"
        private val LIST_SEPARATOR = Regex("\\s*,(?:\\s+(?:and|or)\\s+)?\\s*|\\s+(?:and|or)\\s+")

        private lateinit var registry: CommandRegistry

        private val VALIDATOR: EntryValidator = EntryValidator.builder()
            .addEntryData(object : KeyValueEntryData<List<String>>("aliases", null, true) {
                override fun getValue(value: String): List<String> {
                    val aliases = value.split(LIST_SEPARATOR).map { it.removePrefix("/") }
                    if (aliases.any(String::isEmpty)) {
                        Skript.error("Invalid aliases list: '$value'. Aliases should be separated by commas.")
                        return emptyList()
                    }
                    return aliases
                }
            })
            .addEntry("description", null, true)
            .addEntryData(VariableStringEntryData("usage", null, true))
            .addEntry("prefix", null, true)
            .addEntry("permission", null, true)
            .addEntry("permission message", null, true)
            .addEntryData(object : KeyValueEntryData<Set<ExecutableBy>>("executable by", null, true) {
                override fun getValue(value: String): Set<ExecutableBy> {
                    val executableBy = EnumSet.noneOf(ExecutableBy::class.java)
                    for (type in value.split(LIST_SEPARATOR)) {
                        executableBy += when (type.lowercase()) {
                            "players", "player" -> ExecutableBy.PLAYERS
                            "operators", "ops" -> ExecutableBy.OPERATORS
                            "console", "the console" -> ExecutableBy.CONSOLE
                            "blocks" -> ExecutableBy.BLOCKS
                            else -> {
                                Skript.error("Invalid command sender type: $type")
                                return emptySet()
                            }
                        }
                    }
                    return executableBy
                }
            })
            .addEntryData(LiteralEntryData("cooldown", null, true, Timespan::class.java))
            .addEntryData(VariableStringEntryData("cooldown message", null, true))
            .addEntry("cooldown bypass", null, true)
            .addEntryData(VariableStringEntryData("cooldown storage", null, true, StringMode.VARIABLE_NAME))
            .addEntryData(object : TriggerEntryData("suggestions", null, true) {
                override fun canCreateWith(node: Node): Boolean {
                    if (node !is SectionNode) return false
                    val key = ScriptLoader.replaceOptions(node.key ?: return false)
                    return key.equals("suggestions", ignoreCase = true) ||
                        key.equals("tab completions", ignoreCase = true) ||
                        key.equals("tab complete", ignoreCase = true)
                }
            })
            .addEntryData(TriggerEntryData("trigger", null, false))
            .build()

        fun register(syntaxRegistry: SyntaxRegistry, commandRegistry: CommandRegistry) {
            registry = commandRegistry
            syntaxRegistry.register(
                SyntaxRegistry.STRUCTURE,
                DefaultSyntaxInfos.Structure.builder(StructBukkitCommand::class.java)
                    .supplier(::StructBukkitCommand)
                    .addPattern("bukkit command <.+>")
                    .build(),
            )
        }
    }
}
