package com.github.uni0305.skommand.command.pattern

/** Thrown when a command pattern cannot be compiled. The message is shown to script authors. */
class CommandPatternException(message: String) : Exception(message)

/**
 * An argument as written in a pattern, before its type is resolved.
 * The same declaration appears in every expanded sequence that includes it.
 *
 * @property index Position among all arguments of the pattern, in declaration order.
 */
data class ArgumentSpec(
    val index: Int,
    val name: String?,
    val rawType: String,
    val rawMin: String?,
    val rawMax: String?,
    val rawDefault: String?,
)

sealed interface PatternElement {
    data class Literal(val text: String) : PatternElement
    data class Argument(val spec: ArgumentSpec) : PatternElement
}

/**
 * A compiled command pattern such as `/foo (set|get) <name: text> [<number = 1>]`.
 *
 * @property name The command name (the leading literal).
 * @property arguments Every declared argument, in declaration order.
 * @property choices Literal choice groups, in declaration order, as used by `choice argument`.
 * @property sequences Every way the arguments after the name can be written, most specific first.
 */
class CommandPattern private constructor(
    val name: String,
    val arguments: List<ArgumentSpec>,
    val choices: List<Set<String>>,
    val sequences: List<List<PatternElement>>,
) {

    companion object {
        private const val MAX_SEQUENCES = 1024

        // Same shape as Skript's CommandCompiler, except that a name cannot contain '=' or quotes,
        // which lets defaults such as <item = minecraft:stone> parse correctly.
        private const val WILDCARD = "[^\"]*?(?:\"[^\"]*?\"[^\"]*?)*?"
        private val ARGUMENT_PATTERN =
            Regex("^\\s*(?:([^>=\":]+?)\\s*:\\s*)?(.+?)\\s*(?:=\\s*($WILDCARD))?\\s*$")
        private val TYPE_PATTERN = Regex(
            "^(.+?)\\s*(?: (?:from|above|greater than|>|between) (.+?))?" +
                "(?:(?: (?:to|(?:and )?(?:below|less than|<)|and) (.+?))?)?$",
        )

        /** @throws CommandPatternException if [pattern] is malformed. */
        fun parse(pattern: String): CommandPattern {
            val source = pattern.trim().removePrefix("/").trimStart()
            if (source.isEmpty()) throw CommandPatternException("A command must have a name.")

            val parser = Parser(source)
            val root = parser.parseRoot()

            val choices = mutableListOf<Set<String>>()
            collectChoices(root, optional = false, out = choices)

            val sequences = expand(root).map(::normalize)
            val names = sequences.map { (it.firstOrNull() as? PatternElement.Literal)?.text }.distinct()
            val name = names.singleOrNull() ?: throw CommandPatternException("A command must have a name.")

            val tails = sequences
                .map { it.drop(1) }
                .distinct()
                .sortedWith(
                    compareByDescending<List<PatternElement>> { it.size }
                        .thenByDescending { tail -> tail.count { it is PatternElement.Literal } },
                )
            return CommandPattern(name, parser.arguments, choices, tails)
        }

        private fun collectChoices(group: Node.Group, optional: Boolean, out: MutableList<Set<String>>) {
            if (group.alternatives.size > 1) {
                val literals = group.alternatives.flatMap(::leadingLiterals).toSet()
                if (literals.isNotEmpty()) out += literals
            } else if (optional) {
                // [x] counts as a choice even though it only has one literal
                val only = group.alternatives.single().filterNot { it is Node.Space }.singleOrNull()
                if (only is Node.Text) out += setOf(only.text)
            }
            for (alternative in group.alternatives) {
                for (node in alternative) {
                    if (node is Node.Group) collectChoices(node, node.optional, out)
                }
            }
        }

        private fun leadingLiterals(alternative: List<Node>): Set<String> =
            when (val first = alternative.firstOrNull { it !is Node.Space }) {
                is Node.Text -> setOf(first.text)
                is Node.Group -> first.alternatives.flatMap(::leadingLiterals).toSet()
                else -> emptySet()
            }

        private fun expand(group: Node.Group): List<List<Node>> {
            val result = mutableListOf<List<Node>>()
            for (alternative in group.alternatives) {
                var expanded = listOf(emptyList<Node>())
                for (node in alternative) {
                    val options = if (node is Node.Group) expand(node) else listOf(listOf(node))
                    if (expanded.size.toLong() * options.size > MAX_SEQUENCES) throw tooComplex()
                    expanded = expanded.flatMap { prefix -> options.map { prefix + it } }
                }
                result += expanded
            }
            if (group.optional) result += emptyList<Node>()
            if (result.size > MAX_SEQUENCES) throw tooComplex()
            return result
        }

        private fun tooComplex() =
            CommandPatternException("This command pattern has too many optional parts and choices.")

        private fun normalize(nodes: List<Node>): List<PatternElement> {
            val elements = mutableListOf<PatternElement>()
            val literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) {
                    elements += PatternElement.Literal(literal.toString())
                    literal.clear()
                }
            }
            for (node in nodes) {
                when (node) {
                    is Node.Text -> literal.append(node.text)
                    is Node.Space -> flush()
                    is Node.Arg -> {
                        if (literal.isNotEmpty()) {
                            throw CommandPatternException(
                                "Literals cannot be placed directly next to arguments. Separate them with a space.",
                            )
                        }
                        elements += PatternElement.Argument(node.spec)
                    }
                    is Node.Group -> error("Groups are removed by expansion")
                }
            }
            flush()
            return elements
        }

        private fun parseArgument(index: Int, raw: String): ArgumentSpec {
            val argument = ARGUMENT_PATTERN.find(raw)
                ?: throw CommandPatternException("'$raw' is not a properly formatted argument.")
            val type = TYPE_PATTERN.find(argument.groupValues[2])
                ?: throw CommandPatternException("'${argument.groupValues[2]}' is not a known type.")
            return ArgumentSpec(
                index = index,
                name = argument.groups[1]?.value,
                rawType = type.groupValues[1],
                rawMin = type.groups[2]?.value,
                rawMax = type.groups[3]?.value,
                rawDefault = argument.groups[3]?.value,
            )
        }
    }

    private sealed interface Node {
        data class Text(val text: String) : Node
        data object Space : Node
        data class Arg(val spec: ArgumentSpec) : Node
        data class Group(val alternatives: List<List<Node>>, val optional: Boolean) : Node
    }

    private class Parser(private val source: String) {
        val arguments = mutableListOf<ArgumentSpec>()
        private var cursor = 0

        fun parseRoot(): Node.Group = Node.Group(parseAlternatives(closer = null), optional = false)

        private fun parseAlternatives(closer: Char?, start: Int = 0): List<List<Node>> {
            val alternatives = mutableListOf<List<Node>>()
            var current = mutableListOf<Node>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    current += Node.Text(text.toString())
                    text.clear()
                }
            }
            while (cursor < source.length) {
                val c = source[cursor]
                cursor++
                when {
                    c == closer -> {
                        flush()
                        alternatives += current
                        return alternatives
                    }
                    c == '[' || c == '(' -> {
                        flush()
                        val optional = c == '['
                        val inner = parseAlternatives(if (optional) ']' else ')', cursor - 1)
                        current += Node.Group(inner, optional)
                    }
                    c == '|' -> {
                        flush()
                        alternatives += current
                        current = mutableListOf()
                    }
                    c == '<' -> {
                        flush()
                        current += Node.Arg(readArgument())
                    }
                    c == '\\' && cursor < source.length -> {
                        text.append(source[cursor])
                        cursor++
                    }
                    c == ' ' -> {
                        flush()
                        current += Node.Space
                    }
                    else -> text.append(c)
                }
            }
            if (closer != null) {
                throw CommandPatternException(
                    "Missing expected closing bracket '$closer' starting from input: ${source.substring(start)}",
                )
            }
            flush()
            alternatives += current
            return alternatives
        }

        private fun readArgument(): ArgumentSpec {
            val start = cursor
            var depth = 1
            var quoted = false
            while (cursor < source.length) {
                val c = source[cursor]
                when {
                    c == '\\' -> cursor++
                    c == '"' -> quoted = !quoted
                    quoted -> Unit
                    c == '<' -> depth++
                    c == '>' -> {
                        depth--
                        if (depth == 0) {
                            cursor++
                            val spec = parseArgument(arguments.size, source.substring(start, cursor - 1))
                            arguments += spec
                            return spec
                        }
                    }
                }
                cursor++
            }
            throw CommandPatternException(
                "Missing expected closing bracket '>' starting from input: ${source.substring(start - 1)}",
            )
        }
    }
}
