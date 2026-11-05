package com.github.uni0305.skommand.command.pattern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommandPatternTest {

    private fun shapes(pattern: CommandPattern) = pattern.sequences.map { sequence ->
        sequence.joinToString(" ") {
            when (it) {
                is PatternElement.Literal -> it.text
                is PatternElement.Argument -> "<${it.spec.rawType}>"
            }
        }
    }

    @Test
    fun `reads the name with or without a slash`() {
        assertEquals("foo", CommandPattern.parse("/foo").name)
        assertEquals("foo", CommandPattern.parse("  / foo").name)
        assertEquals("foo", CommandPattern.parse("foo").name)
    }

    @Test
    fun `reads argument names types defaults and ranges`() {
        val args = CommandPattern.parse(
            """/foo <text> <name: text> <number from 1 to 10> <greeting: text = "a: b"> <item = minecraft:stone>""",
        ).arguments

        assertEquals(ArgumentSpec(0, null, "text", null, null, null), args[0])
        assertEquals(ArgumentSpec(1, "name", "text", null, null, null), args[1])
        assertEquals(ArgumentSpec(2, null, "number", "1", "10", null), args[2])
        assertEquals(ArgumentSpec(3, "greeting", "text", null, null, "\"a: b\""), args[3])
        assertEquals(ArgumentSpec(4, null, "item", null, null, "minecraft:stone"), args[4])
    }

    @Test
    fun `expands optionals most specific first`() {
        assertEquals(
            listOf("<text> <number>", "<text>"),
            shapes(CommandPattern.parse("/foo <text> [<number>]")),
        )
    }

    @Test
    fun `expands choices and joins literals that touch`() {
        assertEquals(listOf("set <text>", "get <text>"), shapes(CommandPattern.parse("/foo (set|get) <text>")))
        assertEquals(listOf("xa", "xb"), shapes(CommandPattern.parse("/foo x(a|b)")))
        assertEquals(listOf("ab", "b"), shapes(CommandPattern.parse("/foo [a]b")))
    }

    @Test
    fun `collects choice groups in declaration order`() {
        assertEquals(
            listOf(setOf("a", "b"), setOf("x"), setOf("c", "d")),
            CommandPattern.parse("/foo (a|b) [x] (c|d)").choices,
        )
        assertEquals(emptyList<Set<String>>(), CommandPattern.parse("/foo (ab) <text>").choices)
    }

    @Test
    fun `treats escaped characters as literals`() {
        assertEquals(listOf("[a]"), shapes(CommandPattern.parse("""/foo \[a\]""")))
    }

    @Test
    fun `shares one argument declaration between sequences`() {
        val pattern = CommandPattern.parse("/foo [a] <text>")
        assertEquals(1, pattern.arguments.size)
        val specs = pattern.sequences.map { (it.last() as PatternElement.Argument).spec }
        assertEquals(1, specs.distinct().size)
    }

    @Test
    fun `rejects malformed patterns`() {
        listOf(
            "",
            "/",
            "<text>",
            "/foo [a",
            "/foo (a",
            "/foo <text",
            "/foo lit<text>",
            "/foo lit[<text>]",
            "[foo] bar",
            "/foo|/bar",
        ).forEach { pattern ->
            assertThrows<CommandPatternException>("should reject '$pattern'") { CommandPattern.parse(pattern) }
        }
    }

    @Test
    fun `rejects patterns that expand too far`() {
        val optionals = (1..11).joinToString(" ") { "[o$it]" }
        assertThrows<CommandPatternException> { CommandPattern.parse("/foo $optionals") }
    }

    @Test
    fun `no default is reported as null`() {
        assertNull(CommandPattern.parse("/foo <text>").arguments.single().rawDefault)
    }
}
