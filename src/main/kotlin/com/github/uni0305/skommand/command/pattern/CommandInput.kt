package com.github.uni0305.skommand.command.pattern

/** Reads arguments from the text typed after a command's label. */
class CommandInput(private val text: String) {
    private var cursor = 0

    val isAtEnd: Boolean
        get() {
            skipSpaces()
            return cursor >= text.length
        }

    /** Reads one word, or a double-quoted string. Null if nothing is left or a quote is malformed. */
    fun readToken(): String? {
        skipSpaces()
        if (cursor >= text.length) return null
        return if (text[cursor] == '"') readQuoted() else readWord()
    }

    /** Reads everything that is left, keeping inner spaces. Null if nothing is left. */
    fun readRemaining(): String? {
        skipSpaces()
        if (cursor >= text.length) return null
        return text.substring(cursor).also { cursor = text.length }
    }

    private fun skipSpaces() {
        while (cursor < text.length && text[cursor] == ' ') cursor++
    }

    private fun readWord(): String {
        val start = cursor
        while (cursor < text.length && text[cursor] != ' ') cursor++
        return text.substring(start, cursor)
    }

    private fun readQuoted(): String? {
        cursor++
        val value = StringBuilder()
        while (cursor < text.length) {
            val c = text[cursor++]
            when {
                c == '\\' && cursor < text.length && text[cursor].let { it == '"' || it == '\\' } ->
                    value.append(text[cursor++])
                c == '"' -> return if (cursor >= text.length || text[cursor] == ' ') value.toString() else null
                else -> value.append(c)
            }
        }
        return null
    }
}
