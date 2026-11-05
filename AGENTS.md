# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

Skommand is a Skript addon (Kotlin, Paper plugin) that adds one structure, `bukkit command`, which registers script commands as `BukkitCommand`s in the CommandMap instead of through Brigadier. [`docs/bukkit-command.md`](docs/bukkit-command.md) is the user-facing spec: read it before changing pattern, matching, completion or conflict behaviour.

## Commands

```bash
./gradlew test shadowJar   # What CI runs
./gradlew test --tests 'com.github.uni0305.skommand.command.pattern.CommandMatcherTest'   # One class; append .methodName for one test
./gradlew runServer        # Local Paper server with Skript and Skommand; long-running, and needs eula=true in run/eula.txt
```

No linter or formatter task exists; `gradle.properties` sets `kotlin.code.style=official`.

## Architecture

Two layers, split by what they may depend on:

- `command/` and `command/pattern/` import nothing from Skript. Pattern compilation (`CommandPattern`), input reading (`CommandInput`), matching and completion (`CommandMatcher`), and CommandMap bookkeeping (`CommandRegistry`, `ScriptBukkitCommand`) live here. `CommandHandler` is the seam to the layer below.
- `skript/` binds that core to Skript: `StructBukkitCommand` is the structure, `SkriptCommandHandler` implements `CommandHandler`, `ArgumentBinder` resolves types and converts typed text, `CommandConflictMonitor` warns about later Skript `command` collisions.

Skript is `compileOnly`, so it is absent from the test classpath and `skript/` has no unit tests. Keep logic that can be tested in `command/`. Verify `skript/` changes on a live server with the smoke scripts: copy [`src/test/resources/smoke/skommand-smoke.sk`](src/test/resources/smoke/skommand-smoke.sk) to `run/plugins/Skript/scripts/` and run the inputs listed above each command. `skommand-smoke-errors.sk` holds commands that must fail to load.

### Command lifecycle

`StructBukkitCommand` splits registration across Skript's load phases:

1. `load()`: compiles the pattern, rejects a name held by a Skript `command`, builds the command, and claims the name in `CommandRegistry`. The CommandMap is untouched.
2. `postLoad()`: re-checks Skript's registrar, since a Skript `command` in the same batch may have loaded after step 1, then registers in the CommandMap.
3. `unload()`: unregisters.

Scripts can load off the main thread, so `CommandRegistry` moves every CommandMap change onto it. Tab completion can also arrive off the main thread, where `SkriptCommandHandler` skips the `suggestions` trigger and falls back to player names.

`CommandRegistry.register` deliberately avoids relying on `CommandMap.register` for name conflicts: Paper's implementation replaces commands whose label differs from their known name (such as `/version`). Existing commands must always keep their names, leaving this one reachable as `/prefix:name`.

### Reuse of Skript internals

Skript's command syntax (`arg-1`, `sender`, `suggestions of ...`) works only inside Skript's own parser state and events, so the addon borrows internal classes from `org.skriptlang.skript.bukkit.command.*`:

- Parsing: `StructBukkitCommand.build` pushes argument, choice and executor data onto `CommandParsingData` and sets the current event while parsing `trigger`. `suggestions` is parsed afterwards under `CommandSuggestionEvent`.
- Execution: `BukkitCommandEvents.kt` subclasses Skript's events over a fake `CommandContext` with a null source. Each subclass must override everything Skript would read from that source.

## Version lockstep

Skript internals can change in any minor version, so `SkommandPlugin` disables itself on any other Skript minor. Dependabot ignores Skript minor and major bumps for the same reason; moving to one is manual and touches:

- Skript: `SUPPORTED_MINOR` in `SkommandPlugin.kt`, `skript` in `gradle/libs.versions.toml`, the version in `README.md` and `docs/bukkit-command.md`.
- Minecraft: `minecraft`, the `mockbukkit-v26.3` module and the `paper-api` version in `gradle/libs.versions.toml`, the version in `README.md` and `docs/bukkit-command.md`.

## Conventions

- Keep user-visible strings in sync with the docs: the Troubleshooting tables in `docs/bukkit-command.md` quote load errors and warnings verbatim.
- `paper-plugin.yml` is generated from the `paperPluginYaml` block in `build.gradle.kts`.
- Pushing a `v*` tag publishes a GitHub release. The workflow does not derive the version from the tag, so set `version` in `build.gradle.kts` first to give the jar the right name.
