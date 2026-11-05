package com.github.uni0305.skommand.command.pattern

/** The arguments and literals of the sequence that matched an input. */
class CommandMatch<V : Any>(val values: Map<ArgumentSpec, V>, val literals: List<String>)

/** Something that can be typed at the position being tab completed. */
sealed interface CompletionTarget {
    data class Literal(val text: String) : CompletionTarget

    /** @property previous Raw text of the arguments typed before this one. */
    data class Argument(val spec: ArgumentSpec, val previous: Map<ArgumentSpec, String>) : CompletionTarget
}

/** Matches typed input against the sequences of a [CommandPattern]. */
class CommandMatcher(private val pattern: CommandPattern) {

    /**
     * Finds the first sequence that accepts all of [input].
     *
     * The last element of a sequence receives all remaining input when it is an argument.
     * Earlier arguments receive one word or a double-quoted string.
     *
     * @param convert Converts raw text to a value, or returns null if the text is invalid.
     */
    fun <V : Any> match(input: String, convert: (ArgumentSpec, String) -> V?): CommandMatch<V>? {
        for (sequence in pattern.sequences) {
            matchSequence(sequence, input, convert)?.let { return it }
        }
        return null
    }

    private fun <V : Any> matchSequence(
        sequence: List<PatternElement>,
        input: String,
        convert: (ArgumentSpec, String) -> V?,
    ): CommandMatch<V>? {
        val reader = CommandInput(input)
        val values = linkedMapOf<ArgumentSpec, V>()
        val literals = mutableListOf<String>()
        sequence.forEachIndexed { index, element ->
            when (element) {
                is PatternElement.Literal -> {
                    if (reader.readToken() != element.text) return null
                    literals += element.text
                }
                is PatternElement.Argument -> {
                    val raw = (if (index == sequence.lastIndex) reader.readRemaining() else reader.readToken())
                        ?: return null
                    values[element.spec] = convert(element.spec, raw) ?: return null
                }
            }
        }
        return if (reader.isAtEnd) CommandMatch(values, literals) else null
    }

    /**
     * Lists what can be typed for the last entry of [args], given the entries before it.
     * Nothing is offered past the first word of an argument that consumes the remaining input.
     */
    fun complete(args: List<String>): List<CompletionTarget> {
        val typed = args.dropLast(1).joinToString(" ")
        val targets = linkedSetOf<CompletionTarget>()
        for (sequence in pattern.sequences) {
            completeSequence(sequence, typed)?.let { targets += it }
        }
        return targets.toList()
    }

    private fun completeSequence(sequence: List<PatternElement>, typed: String): CompletionTarget? {
        val reader = CommandInput(typed)
        val previous = linkedMapOf<ArgumentSpec, String>()
        for (element in sequence) {
            if (reader.isAtEnd) {
                return when (element) {
                    is PatternElement.Literal -> CompletionTarget.Literal(element.text)
                    is PatternElement.Argument -> CompletionTarget.Argument(element.spec, previous)
                }
            }
            val token = reader.readToken() ?: return null
            when (element) {
                is PatternElement.Literal -> if (token != element.text) return null
                is PatternElement.Argument -> previous[element.spec] = token
            }
        }
        return null
    }
}
