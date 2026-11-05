package com.github.uni0305.skommand.skript

import ch.njol.skript.Skript
import ch.njol.skript.classes.ClassInfo
import ch.njol.skript.lang.Expression
import ch.njol.skript.lang.Literal
import ch.njol.skript.lang.ParseContext
import ch.njol.skript.lang.SkriptParser
import ch.njol.skript.log.ParseLogHandler
import ch.njol.skript.log.RetainingLogHandler
import ch.njol.skript.registrations.Classes
import ch.njol.skript.util.Utils
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import com.github.uni0305.skommand.command.pattern.ArgumentSpec
import org.skriptlang.skript.bukkit.command.custom.ArgumentData
import org.skriptlang.skript.lang.comparator.Comparator
import org.skriptlang.skript.lang.comparator.Comparators
import org.skriptlang.skript.lang.comparator.Relation

/** Turns declared arguments into Skript's [ArgumentData], and typed text into argument values. */
internal object ArgumentBinder {

    /**
     * Resolves the types, ranges, names and defaults of [specs], in declaration order.
     * Problems are reported as script errors. Returns null if any argument is invalid.
     */
    fun compile(specs: List<ArgumentSpec>): Map<ArgumentSpec, ArgumentData<Any>>? {
        val compiled = linkedMapOf<ArgumentSpec, ArgumentData<Any>>()
        for (spec in specs) {
            compiled[spec] = compile(spec, compiled.values) ?: return null
        }
        return compiled
    }

    @Suppress("UNCHECKED_CAST")
    private fun compile(spec: ArgumentSpec, existing: Collection<ArgumentData<Any>>): ArgumentData<Any>? {
        val plural = Utils.isPlural(spec.rawType)
        val type = Classes.getClassInfoFromUserInput(plural.updated()) as ClassInfo<Any>?
        if (type == null) {
            Skript.error("'${spec.rawType}' is not a known type.")
            return null
        }
        val parser = type.parser
        if (parser == null || !parser.canParse(ParseContext.COMMAND)) {
            Skript.error("The type '${type.name.singular}' cannot be used as a command argument.")
            return null
        }

        val min = spec.rawMin?.let {
            parser.parse(it, ParseContext.COMMAND) ?: run {
                Skript.error("Invalid minimum range: $it")
                return null
            }
        }
        val max = spec.rawMax?.let {
            parser.parse(it, ParseContext.COMMAND) ?: run {
                Skript.error("Invalid maximum range: $it")
                return null
            }
        }
        if ((min != null || max != null) && !validateRange(type, plural.plural(), min, max)) return null

        val names = existing.map { it.name() }
        val isAutomaticName = spec.name == null
        val name = spec.name ?: automaticName(type.name.singular, names)
        if (!isAutomaticName && name in names) {
            Skript.error("The argument name '$name' was already used.")
            return null
        }

        val default = spec.rawDefault?.let { parseDefault(it, type, plural.plural()) }
        return ArgumentData(name, isAutomaticName, type, !plural.plural(), default, min, max)
    }

    private fun validateRange(type: ClassInfo<Any>, plural: Boolean, min: Any?, max: Any?): Boolean {
        val comparator = Comparators.getComparator(type.c, type.c)
        if (comparator == null || !comparator.supportsOrdering()) {
            val typeName = if (plural) type.name.plural else type.name.singular
            Skript.error("$typeName arguments do not support minimum or maximum values.")
            return false
        }
        if (min != null && max != null && comparator.compare(min, max) == Relation.GREATER) {
            Skript.error("The minimum value must be less than or equal to the maximum value.")
            return false
        }
        return true
    }

    private fun automaticName(base: String, used: List<String>): String {
        if (base !in used) return base
        var index = 2
        while ("$base$index" in used) index++
        return "$base$index"
    }

    /** An invalid default is reported and ignored, which is what Skript's own commands do. */
    @Suppress("UNCHECKED_CAST")
    private fun parseDefault(raw: String, type: ClassInfo<Any>, plural: Boolean): Expression<Any>? {
        var input = raw
        val parseType = when {
            raw.startsWith("%") && raw.endsWith("%") -> {
                input = raw.substring(1, raw.length - 1)
                SkriptParser.PARSE_EXPRESSIONS
            }
            type.c == String::class.java && raw.startsWith("\"") && raw.endsWith("\"") ->
                SkriptParser.PARSE_EXPRESSIONS
            else -> SkriptParser.PARSE_LITERALS
        }
        RetainingLogHandler().start().use { log ->
            val parsed = SkriptParser(input, parseType, ParseContext.COMMAND)
                .parseExpression(type.c) as Expression<Any>?
            return when {
                parsed == null -> {
                    log.printErrors(
                        "Can't understand this expression: '$input'. " +
                            "The default value will be ignored for this argument.",
                    )
                    null
                }
                !plural && !parsed.canBeSingle() -> {
                    log.printErrors("Expected a single value but got many: ${parsed.toString(null, false)}")
                    null
                }
                else -> {
                    log.printLog()
                    parsed
                }
            }
        }
    }

    /** Converts typed text to the argument's value, or returns null if the text is invalid. */
    fun convert(argument: ArgumentData<Any>, raw: String, sender: CommandSender): Any? {
        val type = argument.type().c
        if (type == String::class.java && argument.isSingle) {
            return raw.ifEmpty { null }
        }
        val input = raw.trim()
        if (input.isEmpty()) return null
        if (input.startsWith("@") && Entity::class.java.isAssignableFrom(type)) {
            return select(argument, input, sender)
        }
        val literal = parseLiteral(type, input) ?: return null
        if (argument.isSingle && !literal.canBeSingle()) return null
        if (!inRange(argument, literal)) return null
        return if (argument.isSingle) literal.single else literal.array
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLiteral(type: Class<Any>, input: String): Literal<Any>? =
        ParseLogHandler().start().use {
            SkriptParser(input, SkriptParser.PARSE_LITERALS, ParseContext.COMMAND)
                .parseExpression(type) as? Literal<Any>
        }

    @Suppress("UNCHECKED_CAST")
    private fun inRange(argument: ArgumentData<Any>, literal: Literal<Any>): Boolean {
        val min = argument.min()
        val max = argument.max()
        if (min == null && max == null) return true
        val type = argument.type().c
        val comparator = Comparators.getComparator(type, type) as Comparator<Any, Any>
        return literal.all.all { value ->
            (min == null || comparator.compare(value, min) != Relation.SMALLER) &&
                (max == null || comparator.compare(value, max) != Relation.GREATER)
        }
    }

    private fun select(argument: ArgumentData<Any>, selector: String, sender: CommandSender): Any? {
        val type = argument.type().c
        val entities = try {
            Bukkit.selectEntities(sender, selector)
        } catch (_: IllegalArgumentException) {
            return null
        }.filter(type::isInstance)
        if (entities.isEmpty()) return null
        if (argument.isSingle) return entities.singleOrNull()
        val values = java.lang.reflect.Array.newInstance(type, entities.size)
        entities.forEachIndexed { index, entity -> java.lang.reflect.Array.set(values, index, entity) }
        return values
    }
}
