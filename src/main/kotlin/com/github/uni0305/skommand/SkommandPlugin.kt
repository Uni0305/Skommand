package com.github.uni0305.skommand

import ch.njol.skript.Skript
import org.bukkit.plugin.java.JavaPlugin
import com.github.uni0305.skommand.command.CommandRegistry
import com.github.uni0305.skommand.skript.CommandConflictMonitor
import com.github.uni0305.skommand.skript.StructBukkitCommand

class SkommandPlugin : JavaPlugin() {

    override fun onEnable() {
        // The addon reuses Skript's internal command classes, which can change in any minor version.
        val version = Skript.getVersion()
        if (version.major != SUPPORTED_MAJOR || version.minor != SUPPORTED_MINOR) {
            logger.severe("This addon only supports Skript $SUPPORTED_MAJOR.$SUPPORTED_MINOR.x, but found $version.")
            server.pluginManager.disablePlugin(this)
            return
        }

        val addon = Skript.instance().registerAddon(SkommandPlugin::class.java, name)
        val registry = CommandRegistry(this)
        StructBukkitCommand.register(addon.syntaxRegistry(), registry)
        CommandConflictMonitor(registry, logger).start()
    }

    private companion object {
        const val SUPPORTED_MAJOR = 2
        const val SUPPORTED_MINOR = 17
    }
}
