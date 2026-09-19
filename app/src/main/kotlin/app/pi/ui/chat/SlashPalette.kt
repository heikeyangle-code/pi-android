package app.pi.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * The `/` palette: every command the user can invoke, filtered by what they have
 * typed after the slash.
 *
 * It replaces pi's autocomplete popup (`CombinedAutocompleteProvider` over
 * `[...slashCommands, ...templateCommands, ...extensionCommands,
 * ...skillCommandList]`, `interactive-mode.ts:728`) on a touch screen. Two
 * behaviours are taken from that provider rather than invented:
 *
 *  - **filtering is on the invocation name**, case-insensitively, prefix first —
 *    the same thing typing in pi's editor does.
 *
 * Every row here is runnable: the built-ins whose only outcome was a notice
 * ("no entry in this app" or "go to Settings") were **deleted**, not listed, so
 * there is no longer a state to explain. `PiSlashCommands.PI_BUILTIN_SLASH_COMMANDS`
 * records the twelve built-ins that are not here and why.
 *
 * The list is capped in height so it never covers the transcript: on a phone the
 * composer must stay reachable while the palette is open.
 */
@Composable
fun SlashPalette(
    commands: List<PiSlashCommand>,
    query: String,
    onPick: (PiSlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Remembered on the two inputs the ranking reads. The filter lowercases every
    // command's name twice (once per pass) and allocates two lists per call, and this
    // composable is on screen exactly while the user types a `/` name — which is also
    // when the chat screen below it recomposes once per streamed token. Same answer,
    // computed when the query or the command list moves rather than on every frame.
    val matches = remember(commands, query) { filterPalette(commands, query) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = PiShapes.card,
            ) {
        if (matches.isEmpty()) {
            Text(
                text = if (query.isBlank()) "没有可用命令" else "没有匹配「$query」的命令",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Surface
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 260.dp).padding(vertical = 4.dp),
        ) {
            items(matches, key = { it.source.name + ":" + it.name }) { command ->
                SlashPaletteRow(command, onClick = { onPick(command) })
            }
        }
    }
}

@Composable
private fun SlashPaletteRow(command: PiSlashCommand, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    command.invocation,
                    style = PiTheme.text.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                command.argumentHint?.let { hint ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        hint,
                        style = PiTheme.text.monoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Just the description. A row used to append a provenance note for
            // built-ins the app could not run (「仅终端」, then 「本应用：<设置路径>」);
            // every one of those rows was deleted instead, so there is nothing left
            // to annotate. Re-adding a note here means re-adding a row that cannot
            // be run — see `PiSlashCommands.PI_BUILTIN_SLASH_COMMANDS`.
            val subtitle = command.description.orEmpty()
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        SourceBadge(command)
    }
}

/**
 * The provenance chip: the source word (内置/扩展/模板/技能), then the scope, then
 * the package origin when there is one.
 *
 * pi prefixes the same information to the description as a bracketed tag
 * (`interactive-mode.ts` `prefixAutocompleteDescription`, `:612-617`:
 * `[u:npm:…] <description>`). The relationship between the two halves is what
 * matters and is unchanged: the **source word** says what kind of thing the
 * command is, the **scope** says whose it is (a `项目` command comes from the
 * project being worked on, which is a different trust question from `用户`), and
 * the trailing `npm:`/`git:` part says which package it arrived in.
 *
 * Only the presentation of the scope differs from pi: pi writes the single
 * letters `u`/`p`/`t`, and this badge writes 用户/项目/临时 — see
 * [sourceTagLabelOf], which is where that ruling and its reason live.
 */
@Composable
private fun SourceBadge(command: PiSlashCommand) {
    val color = when (command.source) {
        PiCommandSource.Builtin -> MaterialTheme.colorScheme.primary
        PiCommandSource.Extension -> MaterialTheme.colorScheme.tertiary
        PiCommandSource.Prompt -> MaterialTheme.colorScheme.secondary
        PiCommandSource.Skill -> MaterialTheme.colorScheme.secondary
    }
    val label = command.sourceTag
        ?.let { "${command.source.label}·${sourceTagLabelOf(it)}" }
        ?: command.source.label
    Surface(shape = PiShapes.badge, color = color.copy(alpha = 0.16f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * Rank palette rows against what was typed after the `/`.
 *
 * Prefix matches come first, then substring matches, then everything (so an
 * empty query shows the full list). Ordering inside a group is the input order,
 * and the input order is pi's TUI autocomplete sequence — built-ins, templates,
 * extensions, skills (`interactive-mode.ts:727`:
 * `[...slashCommands, ...templateCommands, ...extensionCommands,
 * ...skillCommandList]`). This function does not sort by group; it relies on
 * `piCommandPalette` having already put the list in that order, because
 * `get_commands` itself sends extension → template → skill
 * (`rpc-mode.ts:684-708`) and copying the wire order would put extensions ahead
 * of templates where the TUI shows the opposite.
 */
fun filterPalette(commands: List<PiSlashCommand>, query: String): List<PiSlashCommand> {
    val needle = query.trim().removePrefix("/").lowercase()
    if (needle.isEmpty()) return commands
    val prefix = commands.filter { it.name.lowercase().startsWith(needle) }
    val contains = commands.filter {
        !it.name.lowercase().startsWith(needle) && it.name.lowercase().contains(needle)
    }
    return prefix + contains
}

/**
 * What the composer should do with the text the user submitted.
 *
 * Kept as a pure function with no Compose or ViewModel dependency: the three
 * branches encode pi's own precedence order in `setupEditorSubmitHandler`
 * (`interactive-mode.ts:2967`) — built-in command, then `!` bash mode, then an
 * ordinary prompt — and a pure function is the only way to pin that order
 * without a device.
 */
sealed interface ComposerRoute {
    /** Send as a normal prompt (`session.send`). */
    data class Message(val text: String) : ComposerRoute

    /** Run `command` through `bash`; `!!` sets [excludeFromContext]. */
    data class Bash(val command: String, val excludeFromContext: Boolean) : ComposerRoute

    /** A known command. The screen runs [command] with [args]. */
    data class Command(val command: PiSlashCommand, val args: String) : ComposerRoute

    /** A `/`-prefixed string that is not a command at all. */
    data class Unknown(val name: String) : ComposerRoute

    /** Blank input; the caller should ignore it. */
    data object Empty : ComposerRoute
}

/**
 * Route composer text, in pi's order.
 *
 * The `/` branch deliberately does **not** fall through to a prompt for an
 * unrecognised name. pi's TUI does (`session.prompt(text)` at the end of the
 * chain), which is why typing `/reload` there costs a real model call when the
 * command is a built-in it cannot dispatch (audit §5.5); reproducing that would
 * reproduce a bug. A known command is dispatched with its arguments, and anything
 * else is refused with an explanation while the draft stays in the composer.
 *
 * There is no longer a third outcome. `ComposerRoute.Unreachable` carried the
 * built-ins that only pi's TUI could run; every one of those rows was deleted
 * from the palette (`PiSlashCommands.PI_BUILTIN_SLASH_COMMANDS`) rather than
 * listed as a dead end, so every command in [commands] has a real action and a
 * typed `/name` reaches it exactly like a tap does.
 */
fun routeComposerText(text: String, commands: List<PiSlashCommand>): ComposerRoute {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ComposerRoute.Empty

    // pi: `isBashMode = text.trimStart().startsWith("!")`, and `!!` alone is not
    // a command (the command after the prefix must be non-empty).
    if (trimmed.startsWith("!")) {
        val excluded = trimmed.startsWith("!!")
        val command = trimmed.removePrefix(if (excluded) "!!" else "!").trim()
        if (command.isNotEmpty()) return ComposerRoute.Bash(command, excluded)
    }

    if (!trimmed.startsWith("/")) return ComposerRoute.Message(trimmed)

    val body = trimmed.removePrefix("/")
    val name = body.substringBefore(' ').trim()
    val args = body.substringAfter(' ', "").trim()
    if (name.isEmpty()) return ComposerRoute.Message(trimmed)

    val command = commands.firstOrNull { it.name == name }
        ?: return ComposerRoute.Unknown(name)

    return ComposerRoute.Command(command, args)
}
