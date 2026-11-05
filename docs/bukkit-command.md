# The `bukkit command` structure

Skommand adds one structure to Skript: `bukkit command`. It declares a command the same way Skript's `command` does, but registers it as a Paper `BukkitCommand` in the server's CommandMap instead of as a Brigadier command. Both structures can be used in the same server and the same script.

This document assumes you already know how to write Skript scripts.

## Contents

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Command pattern](#command-pattern)
- [Entries](#entries)
- [Reading arguments in the trigger](#reading-arguments-in-the-trigger)
- [Execution order](#execution-order)
- [How input is matched](#how-input-is-matched)
- [Tab completion](#tab-completion)
- [Name conflicts](#name-conflicts)
- [Differences from Skript's `command`](#differences-from-skripts-command)
- [Troubleshooting](#troubleshooting)

## Requirements

| Dependency | Version |
| --- | --- |
| Paper | 26.3 |
| Skript | 2.17.x |
| Java | 25 |

Skommand depends on Skript's internal command classes, so it disables itself on any other Skript minor version and logs `This addon only supports Skript 2.17.x, but found <version>.`

## Quick start

```skript
bukkit command /announce <message: text>:
    description: Broadcasts a message to everyone.
    aliases: /ann, /notice
    permission: example.announce
    trigger:
        broadcast "<yellow>[Notice]</yellow> %{_message}%"
```

```text
/announce Server restart in 5 minutes
/ann Server restart in 5 minutes
/skript:announce Server restart in 5 minutes
```

## Command pattern

The text after `bukkit command` is the pattern. It starts with the command name, and the leading `/` is optional.

```text
bukkit command /name <elements>:
```

| Element | Syntax | Meaning |
| --- | --- | --- |
| Literal | `set` | The word must be typed exactly, including its case. |
| Choice | `(set\|delete)` | Exactly one of the alternatives. An alternative can hold several elements. |
| Optional | `[loud]`, `[<n: number>]` | The content can be left out. Groups can be nested. |
| Argument | `<name: type>` | A typed value. See [Arguments](#arguments). |
| Escape | `\(`, `\[`, `\<`, `\\` | Treats the next character as literal text. |

```skript
bukkit command /warp (set|delete) <name: text>:
    trigger:
        send "%choice argument% warp %{_name}%" to sender

bukkit command /shout [loud] <message: text>:
    trigger:
        send "choice=[%choice argument%] message=[%{_message}%]" to sender

bukkit command /mode (on|off|set <level: number>):
    trigger:
        send "choice=[%choice argument%] level=[%{_level}%]" to sender

bukkit command /move <x: number> [<y: number> [<z: number>]]:
    trigger:
        send "%{_x}% %{_y}% %{_z}%" to sender
```

Pattern rules:

- The name must be a single word that every alternative shares, so `/(a|b)` is rejected. Use `aliases` for extra names.
- A literal cannot touch an argument. Write `lit <text>`, not `lit<text>`.
- Literal text without a space joins together: `foo[bar]` accepts `foo` and `foobar`.
- A pattern can expand to at most 1024 forms. Each optional group doubles the count, so eleven optional groups in a row are rejected.

### Arguments

```text
<[name:] type [range] [= default]>
```

| Part | Example | Notes |
| --- | --- | --- |
| Name | `<target: player>` | Optional. A named argument also fills the local variable `{_target}`. Names must be unique within the command. |
| Type | `<number>`, `<entities>` | Any Skript type that can be parsed from a command. A plural type accepts a list. |
| Range | `<integer from 2 to 100>` | Optional. Only for types that can be ordered. |
| Default | `<count: integer = 1>` | Optional. Used when the argument is left out, so it only matters inside `[...]`. |

**Range.** Write the lower bound after `from`, `above`, `greater than` or `between`, and the upper bound after `to`, `below`, `less than` or `and`. Either bound can be left out.

- Both bounds are inclusive whichever word you use: `<number above 5>` accepts `5`.
- Avoid the `>` and `<` forms that Skript's own syntax allows. The pattern reads the first `>` as the end of the argument.

**Default.** The value can take three forms:

| Form | Example |
| --- | --- |
| Literal | `<count: integer = 1>`, `<values: numbers = 1 and 2>` |
| Expression between `%` | `<target: player = %player%>` |
| Quoted string, for `text` | `<reason: text = "No reason given">` |

An expression default that evaluates to nothing leaves the argument unset. For example, `%player%` is unset when the console runs the command, and the trigger still runs, so check the value before using it.

A default that cannot be understood is logged as an error, but the command still loads without that default.

```skript
bukkit command /roll [<sides: integer from 2 to 100 = 6>]:
    usage: <red>Usage: /roll [sides]
    trigger:
        send "Rolled %random integer between 1 and {_sides}%" to sender

bukkit command /reason <target: text> [<reason: text = "No reason given">]:
    trigger:
        send "%{_target}%: %{_reason}%" to sender

bukkit command /heal-self [<target: player = %player%>]:
    trigger:
        if {_target} is not set:
            send "Name a player." to sender
            stop
        heal {_target}

bukkit command /sum <values: numbers>:
    trigger:
        send "%sum({_values::*})%" to sender
```

## Entries

Only `trigger` is required.

| Entry | Value | Default | Behavior |
| --- | --- | --- | --- |
| `aliases` | Names separated by `,`, `and` or `or`. A leading `/` is optional. | None | Extra names. A name that is already taken is dropped with a warning. |
| `description` | Plain text | Empty | The description stored on the command. |
| `usage` | Text, can contain expressions and formatting | `Usage: /<pattern>` in red | Sent when the input does not match the pattern. Expressions run without a command context, so `sender` and arguments are not available. |
| `prefix` | One word, without spaces or `§`. Converted to lower case. | `skript` | The namespace in `/prefix:name`. |
| `permission` | Permission node | None | Required to run the command and to get tab completions. |
| `permission message` | Text with formatting, without expressions | Server default | Sent when the permission check fails. |
| `executable by` | `players`, `ops`, `console`, `blocks`, combined with `,`, `and` or `or` | Anyone | Other senders get `This command can only be used by <senders>.` and no tab completions. `player`, `operators` and `the console` are accepted spellings. |
| `cooldown` | Timespan | None | Minimum time between runs for each player. |
| `cooldown message` | Text, can contain expressions and formatting | Skript's default message | Sent to a player who is on cooldown. |
| `cooldown bypass` | Permission node | None | Players with this permission skip the cooldown. |
| `cooldown storage` | Variable name | In memory | Keeps the cooldown in this variable. The in-memory default does not survive a restart. |
| `suggestions` | Section. `tab completions` and `tab complete` are the same entry. | None | See [Tab completion](#tab-completion). |
| `trigger` | Section | Required | The code to run. |

The three `cooldown ...` companions need `cooldown`. Without it, each one logs a warning and is ignored. Arguments are not available in these entries.

Cooldowns apply only to players. The console and command blocks never have one.

```skript
bukkit command /daily:
    permission: example.daily
    permission message: <red>You cannot claim this.
    executable by: players
    cooldown: 1 day
    cooldown message: <red>Wait %remaining time%.
    cooldown bypass: example.daily.bypass
    cooldown storage: {cooldown::daily::%player's uuid%}
    trigger:
        send "Claimed." to sender
```

## Reading arguments in the trigger

Inside `trigger`, Skript's command syntax works the same as in `command`.

| Syntax | Result |
| --- | --- |
| `arg-1`, `argument 2`, `last argument` | An argument by position. Literals do not count, but optional arguments do. |
| `text-argument`, `number-argument`, `text-argument-2` | An argument by type, with a number when the type repeats. |
| `{_name}` | A named single argument. Unnamed arguments such as `<text>` do not set a variable. |
| `{_name::*}` | A named plural argument. |
| `choice argument`, `choice argument 2` | The literal chosen in a `(a\|b)` or `[a]` group, numbered from left to right. It is unset when an optional group is left out. |
| `sender`, `player`, `executor` | Who ran the command. `player` is unset for the console. |
| `command` | The label that was typed, such as an alias. |
| `full command` | The label and everything typed after it. |
| `cooldown time`, `remaining time`, `elapsed time`, `cooldown bypass permission` | Cooldown details. Only for commands with a `cooldown`. |

Two effects are also available:

| Effect | Behavior |
| --- | --- |
| `fail command execution with error "..."` | Stops the trigger and sends the message to the sender, in red unless the message sets a color. |
| `cancel the cooldown` | Gives the sender this use back. Only for commands with a `cooldown`. |

```skript
bukkit command /payment <text> <text> <number>:
    trigger:
        if number-argument is less than 1:
            fail command execution with error "The amount must be positive."
        send "%text-argument-1% -> %text-argument-2%: %arg-3%" to sender
```

## Execution order

1. `permission`
2. `executable by`
3. Matching the input and converting arguments. If this fails, the sender gets the `usage` message.
4. `cooldown`
5. `trigger`

A mistyped command therefore does not use up the cooldown.

## How input is matched

Skommand tries each form of the pattern, longest first, and runs the first one that accepts the whole input.

**Word boundaries.** An argument that ends a form receives all remaining input. Any other argument receives one word, or one double-quoted string with `\"` and `\\` as escapes.

| Input for `/rename <id: text> <name: text>` | `id` | `name` |
| --- | --- | --- |
| `/rename a b c` | `a` | `b c` |
| `/rename "a b" c d` | `a b` | `c d` |
| `/rename a "b c"` | `a` | `"b c"` (quotes kept) |
| `/rename "a b` | Usage message | |

**Spaces.** A run of spaces reaches the command as a single space, even inside quotes: `/rename "a   b" c` gives `a b` as the `id`.

**Optional arguments can change how the input splits.** When a longer form rejects the input, a shorter form gets to try it, and an argument that was one word in the longer form can become the last one.

| Input for `/find <query: text> [<limit: integer = 10>]` | `query` | `limit` |
| --- | --- | --- |
| `/find foo 3` | `foo` | `3` |
| `/find foo bar` | `foo bar` | `10` |
| `/find "foo bar" 3` | `foo bar` | `3` |
| `/find "foo bar"` | `"foo bar"` (quotes kept) | `10` |

**Literals** are case-sensitive: `/warp SET home` does not match `(set|delete)`.

**Lists.** A plural argument takes a Skript list. For `<values: numbers>`, `/sum 1, 2 and 3` gives three values and `/sum 1 2` is rejected.

**Entity selectors.** An argument of an entity type accepts a selector starting with `@`. Selected entities that are not of the argument's type are ignored.

| Argument | Selector finds | Result |
| --- | --- | --- |
| `<entity>` | One entity | That entity |
| `<entity>` | Several entities | Usage message |
| `<entities>` | One or more entities | All of them |
| Either | No entity, or an invalid selector | Usage message |

```skript
bukkit command /smite <targets: entities>:
    trigger:
        send "Selected %size of {_targets::*}%" to sender
```

## Tab completion

The completions depend on what comes next in the pattern:

- **Literal:** offered automatically, without regard to case.
- **Argument with script suggestions:** whatever the `suggestions` section sets for it.
- **Other arguments:** online player names if the type is a player, offline player or entity type that can hold a player. Otherwise nothing.

Nothing is offered after the first word of an argument that takes the remaining input.

Set suggestions with `suggestions of <argument>`. Earlier arguments can be read, and an argument that failed to convert is unset.

```skript
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

Suggestions are filtered by what has been typed, using "starts with" by default and ignoring case. Change it with one of these effects:

```skript
bukkit command /kit <name: text>:
    suggestions:
        set suggestions of arg-1 to "starter" and "vip"
        use contains filtering for suggestions of arg-1
    trigger:
        send "Kit %{_name}%" to sender

bukkit command /kit-all <name: text>:
    tab completions:
        set suggestions of arg-1 to "starter" and "vip"
        disable filtering for suggestions of arg-1
    trigger:
        send "Kit %{_name}%" to sender
```

The `suggestions` section runs only on the main thread. On any other thread, the argument falls back to the player-name behavior above.

## Name conflicts

**A command from another plugin or the server keeps its name.** Your command is then reachable only as `/prefix:name`, and a warning is logged:

```text
/list is already used by another command. This command is only available as /example:list.
```

Aliases work the same way. A taken alias is removed from your command with a warning, and the free ones stay usable.

```text
/mycommand: these aliases are already used by other commands: gamemode
```

**Another `bukkit command` or a Skript `command` with the same name is an error** when the two load together, and the `bukkit command` does not load. See [Troubleshooting](#troubleshooting).

**A Skript `command` that loads later** cannot be blocked, for example after `/sk reload`. Skommand only logs a warning, and the command that runs is not guaranteed. Disabling one of the two scripts can also leave the name unavailable until the other script is reloaded, so give the commands different names.

```text
/name is declared by both 'bukkit command' and Skript's 'command' structure. Rename one of them, because the command that runs is not guaranteed.
```

## Differences from Skript's `command`

| | `bukkit command` | `command` |
| --- | --- | --- |
| Registered as | `BukkitCommand` in the CommandMap | Brigadier command |
| Argument check | When the command runs. A failure sends `usage`. | Through Brigadier parsing |
| `subcommand`, `subcommands` | Not supported. Use `(a\|b)` groups in the pattern. | Supported |
| `trigger` | Required | Optional when subcommands are present |
| `return` in `trigger` | Not allowed | Allowed |
| `permission message` | Works | Deprecated |

Replacing subcommands with choices:

```skript
bukkit command /clan (create|delete) <name: text>:
    trigger:
        if choice argument is "create":
            send "Created %{_name}%" to sender
        else:
            send "Deleted %{_name}%" to sender
```

## Troubleshooting

### Load errors

Each of these stops the command from loading, unless noted otherwise.

| Message | Cause and fix |
| --- | --- |
| `Required entry 'trigger' is missing` | Add a `trigger` section. |
| `Unexpected entry 'subcommand foo'. Check whether it's spelled correctly or remove it` | The entry does not exist here. Subcommands need `(a\|b)` groups. |
| `The return statement cannot be used here` | `return` is not available in `trigger`. Use `stop`. |
| `A command must have a name.` | The pattern starts with a group, or is empty. Use `aliases` for extra names. |
| `Literals cannot be placed directly next to arguments. Separate them with a space.` | Write `lit <text>`. |
| `Invalid amount or placement of brackets. ...` | An unclosed `(` or `[`. Skript reports this before Skommand reads the pattern. |
| `Missing expected closing bracket '>' starting from input: ...` | An unclosed `<`. |
| `This command pattern has too many optional parts and choices.` | The pattern expands to more than 1024 forms. Split it into several commands. |
| `'<type>' is not a known type.` | Check the type's spelling. |
| `The type '<type>' cannot be used as a command argument.` | The type cannot be parsed from text. Take a different type and convert it in the trigger. |
| `Invalid minimum range: ...`, `Invalid maximum range: ...` | The bound is not a value of the argument's type. |
| `The minimum value must be less than or equal to the maximum value.` | Swap the bounds. |
| `<type> arguments do not support minimum or maximum values.` | The type cannot be ordered. Remove the range. |
| `The argument name '<name>' was already used.` | Rename one of the arguments. |
| `Command prefixes must not have any whitespace.`, `Command prefixes must not have any section symbols.` | Fix the `prefix` value. |
| `Invalid command sender type: <type>` | Use `players`, `ops`, `console` or `blocks`. |
| `Invalid aliases list: '...'. Aliases should be separated by commas.` | Remove the empty name from `aliases`. |
| `This command doesn't have any arguments` | The trigger uses `arg-1`, but the pattern has no arguments. |
| `A bukkit command with the name /<name> is already defined.` | Another `bukkit command` has this name. |
| `A command with the name /<name> is already defined in the script '<script>.sk'` | A Skript `command` has this name. |
| `A command with the name /<name> is already defined by Skript's 'command' structure.` | A Skript `command` loaded together with this one has this name. |
| `Can't understand this expression: '...'. The default value will be ignored for this argument.` | The default is invalid. The command still loads, without the default. |
| `Expected a single value but got many: ...` | A single argument has a list as its default. The command still loads, without the default. |
| `There is a cooldown message set, but not a cooldown` (also `bypass`, `storage`) | A warning. Add `cooldown` or remove the entry. |

### Runtime behavior

| Symptom | Cause |
| --- | --- |
| A valid-looking command only prints the usage message | The input did not match. Check the case of literals, the argument types, ranges, and whether a selector found exactly one entity. |
| `Unknown or incomplete command` for `/name` | The script failed to load, or the name is shared with a Skript `command` that was disabled. Check the console and reload the script. |
| A different command runs instead of yours | The name was already taken. Use `/prefix:name`, or look for the warning in the console. |
| A text argument contains quotes | It is the last argument of the form that matched, which keeps its input unchanged. See [How input is matched](#how-input-is-matched). |
| The console never hits a cooldown | Cooldowns apply only to players. |
