package com.github.uni0305.skommand.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.command.defaults.BukkitCommand
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

class ScriptBukkitCommandTest {

    private lateinit var server: ServerMock
    private lateinit var registry: CommandRegistry
    private lateinit var player: PlayerMock

    private class RecordingHandler(private val valid: Boolean = true) : CommandHandler {
        val executions = mutableListOf<String>()
        var usageSent = 0

        override fun execute(sender: CommandSender, label: String, input: String): Boolean {
            executions += "$label:$input"
            return valid
        }

        override fun sendUsage(sender: CommandSender) {
            usageSent++
        }

        override fun suggest(sender: CommandSender, label: String, args: List<String>) = listOf("suggested")
    }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        registry = CommandRegistry(MockBukkit.createMockPlugin())
        player = server.addPlayer()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    private fun declare(settings: CommandSettings, handler: CommandHandler = RecordingHandler()): ScriptBukkitCommand {
        val command = ScriptBukkitCommand(settings, handler)
        assertTrue(registry.claim(command))
        registry.register(command, "skript")
        return command
    }

    private fun PlayerMock.nextText(): String? =
        nextComponentMessage()?.let(PlainTextComponentSerializer.plainText()::serialize)

    @Test
    fun `is a BukkitCommand reachable by name prefix and alias`() {
        val command = declare(CommandSettings("foo", aliases = listOf("bar")))

        assertTrue((command as Any) is BukkitCommand)
        assertSame(command, server.commandMap.getCommand("foo"))
        assertSame(command, server.commandMap.getCommand("skript:foo"))
        assertSame(command, server.commandMap.getCommand("bar"))
    }

    @Test
    fun `dispatch passes the label and joined arguments`() {
        val handler = RecordingHandler()
        declare(CommandSettings("foo", aliases = listOf("bar")), handler)

        server.dispatchCommand(player, "foo one  two")
        server.dispatchCommand(player, "bar three")

        assertEquals("foo:one  two", handler.executions[0])
        assertEquals("bar:three", handler.executions[1])
    }

    @Test
    fun `invalid input sends usage`() {
        val handler = RecordingHandler(valid = false)
        declare(CommandSettings("foo"), handler)

        server.dispatchCommand(player, "foo nope")

        assertEquals(1, handler.usageSent)
    }

    @Test
    fun `missing permission stops execution and sends the custom message`() {
        val handler = RecordingHandler()
        declare(
            CommandSettings("foo", permission = "test.foo", permissionMessage = Component.text("denied")),
            handler,
        )

        server.dispatchCommand(player, "foo")

        assertTrue(handler.executions.isEmpty())
        assertEquals("denied", player.nextText())

        player.addAttachment(MockBukkit.createMockPlugin(), "test.foo", true)
        server.dispatchCommand(player, "foo")
        assertEquals(1, handler.executions.size)
    }

    @Test
    fun `sender restriction stops execution with its message`() {
        val handler = RecordingHandler()
        declare(
            CommandSettings("foo", restriction = SenderRestriction("console only") { it !is Player }),
            handler,
        )

        server.dispatchCommand(player, "foo")
        assertTrue(handler.executions.isEmpty())
        assertEquals("console only", player.nextText())

        server.dispatchCommand(server.consoleSender, "foo")
        assertEquals(1, handler.executions.size)
    }

    @Test
    fun `tab completion is hidden from senders who cannot use the command`() {
        val open = declare(CommandSettings("open"))
        val locked = declare(CommandSettings("locked", permission = "test.locked"))
        val restricted = declare(
            CommandSettings("restricted", restriction = SenderRestriction("no") { it !is Player }),
        )

        assertEquals(listOf("suggested"), open.tabComplete(player, "open", arrayOf("")))
        assertEquals(emptyList<String>(), locked.tabComplete(player, "locked", arrayOf("")))
        assertEquals(emptyList<String>(), restricted.tabComplete(player, "restricted", arrayOf("")))
    }

    @Test
    fun `a taken name keeps the existing command and leaves the prefixed one`() {
        val other = object : BukkitCommand("taken") {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
        }
        server.commandMap.register("other", other)

        val command = declare(CommandSettings("taken"))

        assertSame(other, server.commandMap.getCommand("taken"))
        assertSame(command, server.commandMap.getCommand("skript:taken"))
    }

    @Test
    fun `a command known under a different label is not replaced`() {
        // Paper's Brigadier commands, like /version, have a label that differs from their known name.
        val other = object : BukkitCommand("wrapped") {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
        }
        server.commandMap.knownCommands["version"] = other

        val command = declare(CommandSettings("version", aliases = listOf("ver")))

        assertSame(other, server.commandMap.getCommand("version"))
        assertSame(command, server.commandMap.getCommand("skript:version"))
        assertSame(command, server.commandMap.getCommand("ver"))
        assertSame(command, server.commandMap.getCommand("skript:ver"))

        registry.unregister(command)

        assertSame(other, server.commandMap.getCommand("version"))
        assertNull(server.commandMap.getCommand("skript:version"))
        assertNull(server.commandMap.getCommand("ver"))
    }

    @Test
    fun `a taken alias is dropped and the existing command keeps it`() {
        val other = object : BukkitCommand("other") {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
        }
        server.commandMap.knownCommands["taken"] = other

        val command = declare(CommandSettings("foo", aliases = listOf("taken", "free")))

        assertSame(other, server.commandMap.getCommand("taken"))
        assertSame(command, server.commandMap.getCommand("foo"))
        assertSame(command, server.commandMap.getCommand("free"))
        assertEquals(listOf("free"), command.aliases)
    }

    @Test
    fun `claims reject a second command with the same name in any case`() {
        val first = ScriptBukkitCommand(CommandSettings("foo"), RecordingHandler())
        val second = ScriptBukkitCommand(CommandSettings("FOO"), RecordingHandler())

        assertTrue(registry.claim(first))
        assertFalse(registry.claim(second))

        registry.release(first)
        assertTrue(registry.claim(second))
    }

    @Test
    fun `unregister removes every label of this command and nothing else`() {
        val other = object : BukkitCommand("kept") {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
        }
        server.commandMap.register("other", other)
        val command = declare(CommandSettings("foo", aliases = listOf("bar")))

        registry.unregister(command)

        assertNull(server.commandMap.getCommand("foo"))
        assertNull(server.commandMap.getCommand("skript:foo"))
        assertNull(server.commandMap.getCommand("bar"))
        assertSame(other, server.commandMap.getCommand("kept"))
        assertTrue(registry.registeredCommands.isEmpty())
        assertTrue(registry.claim(ScriptBukkitCommand(CommandSettings("foo"), RecordingHandler())))
    }
}
