package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 输入框里敲 `/` 时弹出的命令面板：严格对齐官方 Pi SDK 的四组顺序
 * （内置 → 模板 → 扩展 → 技能），不包含桌面键盘快捷键。
 */
@Composable
fun SlashPalette(
    commands: List<PiSlashCommand>,
    query: String,
    onPick: (PiSlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = remember(commands, query) { filterPalette(commands, query) }
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PiTheme.palette.cardBg,
        shape = PiShapes.card,
        border = BorderStroke(PiSpacing.hairlineThin, PiTheme.palette.borderMuted.copy(alpha = 0.60f)),
    ) {
        if (matches.isEmpty()) {
            Text(
                text = if (query.isBlank()) tr("没有可用命令", "No commands available") else tr("没有匹配「$query」的命令", "No commands matching \"$query\""),
                modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 14.dp),
                style = PiTheme.text.prose,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Surface
        }
        LazyColumn(
            modifier = Modifier
                .heightIn(max = SLASH_PALETTE_MAX_HEIGHT)
                .padding(vertical = 4.dp),
        ) {
            var previous: PiCommandSource? = null
            matches.forEachIndexed { idx, command ->
                if (command.source != previous) {
                    val group = command.source
                    val count = matches.count { it.source == group }
                    val isFirstGroup = previous == null
                    item(key = "group:${group.name}") {
                        SlashPaletteGroupHeader(group, count, showTopDivider = !isFirstGroup)
                    }
                    previous = group
                }
                item(key = "${command.source.name}:${command.name}:$idx") {
                    SlashPaletteRow(
                        command = command,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(command)
                        },
                    )
                }
            }
        }
    }
}

/** 分组小标题：「内置 · 20」。 */
@Composable
private fun SlashPaletteGroupHeader(source: PiCommandSource, count: Int, showTopDivider: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (showTopDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
                thickness = PiSpacing.hairlineThin,
                color = PiTheme.palette.borderMuted.copy(alpha = 0.45f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSpacing.pageHorizontal,
                    end = PiSpacing.pageHorizontal,
                    top = if (showTopDivider) 4.dp else 8.dp,
                    bottom = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                source.label,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                color = PiTheme.palette.accent,
            )
            Text(
                "$count",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                style = PiTheme.text.monoSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一条命令：第一行 `/名字` + 官方 `argumentHint` 胶囊，右端为来源标签（用户 / 项目 / npm / git）；
 * 第二行是精炼说明。
 */
@Composable
private fun SlashPaletteRow(command: PiSlashCommand, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val invocation = command.invocation
                val slash = invocation.startsWith("/")
                Text(
                    text = buildAnnotatedString {
                        if (slash) {
                            withStyle(SpanStyle(color = PiTheme.palette.accent.copy(alpha = 0.85f))) { append("/") }
                        }
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(if (slash) invocation.drop(1) else invocation)
                        }
                    },
                    style = PiTheme.text.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                command.argumentHint?.let { hint ->
                    Text(
                        text = hint,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(PiShapes.badge)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = PiTheme.text.monoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            command.sourceTag?.let { tag ->
                Text(
                    text = sourceTagLabelOf(tag),
                    modifier = Modifier
                        .clip(PiShapes.badge)
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        val subtitle = command.description.orEmpty()
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 面板高度封顶：在软键盘展开时也不裁切顶部分组标题，同时给输入框留足空间。 */
private val SLASH_PALETTE_MAX_HEIGHT = 260.dp

/**
 * What the composer should do with the text the user submitted.
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

    /** Blank input (or bare `!`/`!!` with no command); the caller should ignore it. */
    data object Empty : ComposerRoute
}

/**
 * Route composer text, in pi's order: bash `!`/`!!`, slash command `/name`, or normal message.
 */
fun routeComposerText(text: String, commands: List<PiSlashCommand>): ComposerRoute {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ComposerRoute.Empty

    // pi: `isBashMode = text.trimStart().startsWith("!")`; bare `!` or `!!` must not fall through as a model prompt.
    if (trimmed.startsWith("!")) {
        val excluded = trimmed.startsWith("!!")
        val command = trimmed.removePrefix(if (excluded) "!!" else "!").trim()
        return if (command.isNotEmpty()) ComposerRoute.Bash(command, excluded) else ComposerRoute.Empty
    }

    if (!trimmed.startsWith("/")) return ComposerRoute.Message(trimmed)

    val body = trimmed.removePrefix("/")
    val sep = body.indexOfFirst { it.isWhitespace() }
    val name = (if (sep < 0) body else body.substring(0, sep)).trim()
    val args = (if (sep < 0) "" else body.substring(sep + 1)).trim()
    if (name.isEmpty()) return ComposerRoute.Empty

    val command = commands.firstOrNull { it.name == name }
        ?: return ComposerRoute.Unknown(name)

    return ComposerRoute.Command(command, args)
}
