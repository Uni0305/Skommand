package com.github.uni0305.skommand.command.pattern

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandMatcherTest {

    private fun convert(spec: ArgumentSpec, raw: String): Any? = when (spec.rawType) {
        "number" -> raw.trim().toIntOrNull()
        else -> raw
    }

    private fun match(pattern: String, input: String): Map<String, Any>? =
        CommandMatcher(CommandPattern.parse(pattern)).match(input, ::convert)
            ?.values?.mapKeys { it.key.name ?: it.key.rawType }

    @Test
    fun `last text takes all remaining input`() {
        assertEquals(mapOf("text" to "hello big  world"), match("/foo <text>", "hello big  world"))
    }

    @Test
    fun `middle text takes a word or a quoted string`() {
        assertEquals(
            mapOf("a" to "one", "number" to 2),
            match("/foo <a: text> <number>", "one 2"),
        )
        assertEquals(
            mapOf("a" to "one two", "number" to 2),
            match("/foo <a: text> <number>", "\"one two\" 2"),
        )
        assertNull(match("/foo <a: text> <number>", "one two 2"))
    }

    @Test
    fun `falls back when an optional argument does not convert`() {
        val pattern = "/foo <text> [<number>]"
        assertEquals(mapOf("text" to "hi", "number" to 5), match(pattern, "hi 5"))
        assertEquals(mapOf("text" to "hi there"), match(pattern, "hi there"))
    }

    @Test
    fun `literals are matched exactly and recorded`() {
        val matcher = CommandMatcher(CommandPattern.parse("/foo (set|get) <text>"))
        assertEquals(listOf("get"), matcher.match("get x", ::convert)?.literals)
        assertNull(matcher.match("GET x", ::convert))
        assertNull(matcher.match("other x", ::convert))
    }

    @Test
    fun `missing and extra input do not match`() {
        assertNull(match("/foo <number>", ""))
        assertNull(match("/foo <number>", "1 2"))
        assertNull(match("/foo set", "set extra"))
        assertNotNull(match("/foo", ""))
        assertNotNull(match("/foo set", "set "))
    }

    private fun complete(pattern: String, vararg args: String) =
        CommandMatcher(CommandPattern.parse(pattern)).complete(args.toList()).map {
            when (it) {
                is CompletionTarget.Literal -> it.text
                is CompletionTarget.Argument -> "<${it.spec.rawType}>"
            }
        }

    @Test
    fun `completes the element at the cursor`() {
        assertEquals(listOf("set", "get"), complete("/foo (set|get) <text>", ""))
        assertEquals(listOf("<text>"), complete("/foo (set|get) <text>", "set", ""))
        assertEquals(emptyList<String>(), complete("/foo (set|get) <text>", "other", ""))
        assertEquals(listOf("<text>", "<number>"), complete("/foo [<text>] <number>", ""))
    }

    @Test
    fun `stops completing past the end of the pattern`() {
        assertEquals(emptyList<String>(), complete("/foo set", "set", ""))
    }

    @Test
    fun `reports previous raw arguments`() {
        val target = CommandMatcher(CommandPattern.parse("/foo <a: text> <b: text>"))
            .complete(listOf("first", "")).single() as CompletionTarget.Argument
        assertEquals("b", target.spec.name)
        assertEquals(listOf("first"), target.previous.values.toList())
    }
}
