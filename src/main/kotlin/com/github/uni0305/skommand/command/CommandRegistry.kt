package com.github.uni0305.skommand.command

import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks the commands declared by scripts and keeps the server's command map up to date.
 * Scripts can load off the main thread, so command map changes are always moved onto it.
 */
class CommandRegistry(private val plugin: Plugin) {
    private val commands = ConcurrentHashMap<String, ScriptBukkitCommand>()
    private val syncPending = AtomicBoolean()

    val registeredCommands: Collection<ScriptBukkitCommand>
        get() = commands.values

    /** Reserves the command's name. Returns false if another declared command already has it. */
    fun claim(command: ScriptBukkitCommand): Boolean =
        commands.putIfAbsent(command.name.lowercase(), command) == null

    /** Gives up the command's name without touching the command map. */
    fun release(command: ScriptBukkitCommand) {
        commands.remove(command.name.lowercase(), command)
    }

    /**
     * Adds a claimed command to the command map. An existing command always keeps its name or alias,
     * and only `/prefix:name` reaches this one.
     *
     * [org.bukkit.command.CommandMap.register] alone does not guarantee this: on Paper it replaces
     * commands whose label differs from the name they are known by, such as `/version`.
     */
    fun register(command: ScriptBukkitCommand, prefix: String) {
        onMainThread {
            val map = plugin.server.commandMap
            val known = map.knownCommands
            val namespace = prefix.lowercase()
            val name = command.name.lowercase()

            val (takenAliases, freeAliases) = command.aliases.partition { it.lowercase() in known }
            if (takenAliases.isNotEmpty()) {
                command.aliases = freeAliases
                plugin.logger.warning(
                    "/$name: these aliases are already used by other commands: ${takenAliases.joinToString(", ")}",
                )
            }

            if (name !in known) {
                map.register(command.name, prefix, command)
            } else {
                plugin.logger.warning(
                    "/$name is already used by another command. This command is only available as /$namespace:$name.",
                )
                command.setLabel("$namespace:$name")
                command.register(map)
                known["$namespace:$name"] = command
                for (alias in freeAliases.map(String::lowercase)) {
                    known[alias] = command
                    known["$namespace:$alias"] = command
                }
            }
            scheduleSync()
        }
    }

    /** Removes a command from the command map, leaving every other command in place. */
    fun unregister(command: ScriptBukkitCommand) {
        release(command)
        onMainThread {
            val map = plugin.server.commandMap
            command.unregister(map)
            val known = map.knownCommands
            known.entries.filter { it.value === command }.map { it.key }.forEach(known::remove)
            scheduleSync()
        }
    }

    private fun onMainThread(action: () -> Unit) {
        val server = plugin.server
        if (server.isPrimaryThread || !plugin.isEnabled) {
            action()
        } else {
            server.scheduler.runTask(plugin, Runnable(action))
        }
    }

    /** Sends the new command list to players once, however many commands changed in this tick. */
    private fun scheduleSync() {
        if (!plugin.isEnabled || !syncPending.compareAndSet(false, true)) return
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                syncPending.set(false)
                plugin.server.onlinePlayers.forEach { it.updateCommands() }
            },
        )
    }
}
