package com.github.uni0305.skommand.command.pattern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandInputTest {

    @Test
    fun `reads words and quoted strings`() {
        val input = CommandInput("""one  "two words" "a \"b\" \\c" end""")
        assertEquals("one", input.readToken())
        assertEquals("two words", input.readToken())
        assertEquals("""a "b" \c""", input.readToken())
        assertEquals("end", input.readToken())
        assertNull(input.readToken())
    }

    @Test
    fun `rejects broken quotes`() {
        assertNull(CommandInput(""""never closed""").readToken())
        assertNull(CommandInput(""""a"b""").readToken())
    }

    @Test
    fun `remaining input keeps inner spaces`() {
        val input = CommandInput("first  second   third")
        input.readToken()
        assertFalse(input.isAtEnd)
        assertEquals("second   third", input.readRemaining())
        assertTrue(input.isAtEnd)
        assertNull(input.readRemaining())
    }
}
