package com.github.uni0305.skommand.skript

import ch.njol.skript.ScriptLoader
import com.github.uni0305.skommand.command.CommandRegistry
import org.skriptlang.skript.bukkit.command.custom.ScriptCommandRegistrar
import java.util.logging.Logger

/**
 * Warns when Skript's `command` takes the name of a loaded `bukkit command`.
 * Skript does not know about these commands, so its load cannot be failed. This is only a warning.
 */
internal class CommandConflictMonitor(
    private val registry: CommandRegistry,
    private val logger: Logger,
) {
    private val reported = HashSet<String>()

    fun start() {
        ScriptLoader.eventRegistry().register(
            ScriptLoader.ScriptLoadEvent::class.java,
            ScriptLoader.ScriptLoadEvent { _, _ -> check() },
        )
    }

    @Synchronized
    private fun check() {
        val conflicts = registry.registeredCommands
            .map { it.name }
            .filter { ScriptCommandRegistrar.getCommand(it) != null }
            .toSet()
        (conflicts - reported).forEach { name ->
            logger.warning(
                "/$name is declared by both 'bukkit command' and Skript's 'command' structure. " +
                    "Rename one of them, because the command that runs is not guaranteed.",
            )
        }
        reported.clear()
        reported += conflicts
    }
}
