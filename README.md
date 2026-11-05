<div align="center">

# Skommand

**A Skript addon that registers script commands as Paper `BukkitCommand`s.**

[![Paper 26.3](https://img.shields.io/badge/Paper-26.3-blue)](https://papermc.io/)
[![Skript 2.17.x](https://img.shields.io/badge/Skript-2.17.x-orange)](https://github.com/SkriptLang/Skript)
![Java 25](https://img.shields.io/badge/Java-25-red)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-green)](LICENSE.md)

[Features](#features) · [Installation](#installation) · [Usage](#usage) · [Documentation](docs/bukkit-command.md) · [Development](#development)

</div>

Skommand adds one structure to Skript: `bukkit command`. It declares a command the same way Skript's `command` does, but registers it as a `BukkitCommand` in the server's CommandMap instead of as a Brigadier command. Both structures can be used in the same server and the same script.

## Features

- **Familiar syntax:** `arg-1`, `{_name}`, `choice argument`, `sender` and the rest of Skript's command syntax work inside `trigger`.
- **Expressive patterns:** literals, `(a|b)` choices, nested `[optional]` groups, and typed arguments with ranges and defaults.
- **Standard entries:** aliases, usage, prefix, permissions, sender restrictions and cooldowns.
- **Tab completion:** literals and player names are offered automatically, and a `suggestions` section handles the rest.
- **Entity selectors:** entity arguments accept selectors such as `@e[type=minecraft:armor_stand]`.
- **Respects existing commands:** a name already taken by another plugin or the server stays with its owner, and your command remains reachable as `/prefix:name`.

## Installation

| Dependency | Version |
| --- | --- |
| [Paper](https://papermc.io/) | 26.3 |
| [Skript](https://github.com/SkriptLang/Skript) | 2.17.x |
| Java | 25 |

1. Download the jar from [Releases](../../releases), or [build it from source](#development).
2. Put it in your server's `plugins/` folder, next to Skript.
3. Restart the server.

> [!IMPORTANT]
> Skommand depends on Skript's internal command classes. It disables itself on any other Skript minor version and logs the version it found.

## Usage

Declare a command in any script:

```skript
bukkit command /announce <message: text>:
    description: Broadcasts a message to everyone.
    aliases: /ann, /notice
    permission: example.announce
    trigger:
        broadcast "<yellow>[Notice]</yellow> %{_message}%"
```

Then run it by its name, an alias, or its prefixed name:

```text
/announce Server restart in 5 minutes
/ann Server restart in 5 minutes
/skript:announce Server restart in 5 minutes
```

Patterns can branch with choices, take optional arguments with defaults, and offer their own completions:

```skript
bukkit command /warp (set|delete) <name: text>:
    trigger:
        send "%choice argument% warp %{_name}%" to sender

bukkit command /roll [<sides: integer from 2 to 100 = 6>]:
    usage: <red>Usage: /roll [sides]
    trigger:
        send "Rolled %random integer between 1 and {_sides}%" to sender

bukkit command /shop <category: text> <item: text>:
    suggestions:
        set suggestions of arg-1 to "tools" and "food"
        if arg-1 is "tools":
            set suggestions of arg-2 to "pickaxe" and "shovel"
        else if arg-1 is "food":
            set suggestions of arg-2 to "bread" and "apple"
    trigger:
        send "%{_category}%: %{_item}%" to sender
```

> [!NOTE]
> `subcommand` entries are not supported. Use `(a|b)` groups in the pattern instead.

## Documentation

The [`bukkit command` reference](docs/bukkit-command.md) covers:

- [Command patterns](docs/bukkit-command.md#command-pattern) and [entries](docs/bukkit-command.md#entries)
- [Reading arguments in the trigger](docs/bukkit-command.md#reading-arguments-in-the-trigger)
- [How input is matched](docs/bukkit-command.md#how-input-is-matched) and [tab completion](docs/bukkit-command.md#tab-completion)
- [Name conflicts](docs/bukkit-command.md#name-conflicts) and [differences from Skript's `command`](docs/bukkit-command.md#differences-from-skripts-command)
- [Troubleshooting](docs/bukkit-command.md#troubleshooting)

## Development

You need a JDK to run Gradle, which downloads the JDK 25 toolchain if it's missing.

```bash
./gradlew test        # Run the unit tests
./gradlew shadowJar   # Build the plugin jar into build/libs/
./gradlew runServer   # Start a local Paper server with Skript and Skommand
```

> [!NOTE]
> The first `runServer` stops until you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) by setting `eula=true` in `run/eula.txt`.

To try the commands on a live server, copy [`skommand-smoke.sk`](src/test/resources/smoke/skommand-smoke.sk) to `run/plugins/Skript/scripts/`. The file lists the inputs to try above each command. [`skommand-smoke-errors.sk`](src/test/resources/smoke/skommand-smoke-errors.sk) contains commands that must fail to load with a clear error.
