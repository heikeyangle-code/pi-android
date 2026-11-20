package app.pi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 输入框里敲 `/` 时弹出的命令面板：pi 终端自动补全弹窗（`CombinedAutocompleteProvider` over
 * `[...slashCommands, ...templateCommands, ...extensionCommands, ...skillCommandList]`，
 * `interactive-mode.js` `setupAutocompleteProvider`）在触屏上的样子。顺序和分组就是官方的：
 * 内置 → 模板 → 扩展 → 技能；每组一个小标题，行里不再重复写「来源」，只有带来源标签
 * （用户 / 项目 / npm / git，官方 `getAutocompleteSourceTag`）的行才多一枚标签。
 *
 * 过滤：命令名前缀优先，其次名字包含，最后说明里出现 —— 官方只按名字过滤，手机上输入慢，
 * 多一层说明匹配能少敲几个字（排在名字命中之后，不改变官方的优先次序）。
 *
 * 这里的行都能执行。pi 有、手机做不了的内建命令（`UNSUPPORTED_BUILTIN_REASONS`）不占列表行，
 * 敲全名时才出现并说明原因；pi 新增而我们还没映射的命令照常列出（点一下说明「还没有对应做法」），
 * 不会悄悄缺一个。
 *
 * 高度封顶：命令面板打开时输入框必须还够得着。
 */
@Composable
fun SlashPalette(
    commands: List<PiSlashCommand>,
    query: String,
    onPick: (PiSlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 随查询词 / 命令表变化才重算：流式输出时对话页每个 token 重组一次，面板开着的那几秒不该每帧过滤。
    val matches = remember(commands, query) { filterPalette(commands, query) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = PiShapes.card,
    ) {
        if (matches.isEmpty()) {
            Text(
                text = if (query.isBlank()) "没有可用命令" else "没有匹配「$query」的命令",
                modifier = Modifier.padding(PiSpacing.pageHorizontal),
                style = PiTheme.text.prose,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Surface
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = SLASH_PALETTE_MAX_HEIGHT).padding(vertical = PiSpacing.tiny),
        ) {
            var previous: PiCommandSource? = null
            matches.forEach { command ->
                if (command.source != previous) {
                    val group = command.source
                    val count = matches.count { it.source == group }
                    item(key = "group:" + group.name) { SlashPaletteGroupHeader(group, count) }
                    previous = group
                }
                item(key = command.source.name + ":" + command.name) {
                    SlashPaletteRow(command, onClick = { onPick(command) })
                }
            }
        }
    }
}

/** 分组小标题：「内置 · 18」。 */
@Composable
private fun SlashPaletteGroupHeader(source: PiCommandSource, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PiSpacing.pageHorizontal, end = PiSpacing.pageHorizontal, top = PiSpacing.blockGap, bottom = PiSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
    ) {
        Text(
            source.label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "$count",
            style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一条命令：第一行 `/名字`（斜杠退一档用强调色，名字用正文色加粗：先读到「是哪条命令」）+ 参数提示
 * （等宽、胶囊底），右端是来源标签（仅用户 / 项目 / npm / git 这类有出处的行）；第二行是说明，两行封顶。
 */
@Composable
private fun SlashPaletteRow(command: PiSlashCommand, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = PiSpacing.inline),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PiSpacing.gutter)) {
            val invocation = command.invocation
            val slash = invocation.startsWith("/")
            Text(
                text = buildAnnotatedString {
                    if (slash) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))) { append("/") }
                    }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(if (slash) invocation.drop(1) else invocation)
                    }
                },
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            command.argumentHint?.let { hint ->
                Text(
                    hint,
                    modifier = Modifier
                        .clip(PiShapes.badge)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = PiSpacing.gutter, vertical = PiSpacing.hairline),
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            command.sourceTag?.let { tag ->
                Text(
                    sourceTagLabelOf(tag),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val subtitle = command.description.orEmpty()
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = PiTheme.text.prose,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 面板最高占多少：给输入框和最近的对话留出位置。 */
private val SLASH_PALETTE_MAX_HEIGHT = 320.dp

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
 * from the palette (`PiSlashCommands.CLIENT_BUILTINS`) rather than
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
