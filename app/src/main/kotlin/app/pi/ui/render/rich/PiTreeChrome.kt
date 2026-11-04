package app.pi.ui.render.rich

import app.pi.ui.render.LocalPiCardMenuSlot
import app.pi.ui.render.PiCardMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme

/**
 * The two ways an explicitly structured fence can be shown (`§七.4` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The plan's rule is "树状视图不能取代原始源码视图。默认可以显示代码视图，用户主动切换"
 * — so the default is [Code] and the tree is one explicit tap away. A failed parse
 * never removes [Code]: it only removes [Tree].
 */
enum class PiTreeMode {
    /** The raw fence body, as a code block. */
    Code,

    /** The parsed tree. */
    Tree,
}

/**
 * Display-only caps shared by the tree views. They are **not** parse limits: the
 * data model keeps everything the plan allows (20,000 nodes), and copying/source
 * view still see the full document. They exist so composition cannot be made to
 * lay out a legal-but-enormous document: a container draws at most this many
 * children before a stated "还有 N 项" row, and one leaf shows at most
 * [PI_TREE_MAX_SCALAR_CHARS] characters with `…`.
 */
internal const val PI_TREE_MAX_VISIBLE_CHILDREN = 200

/** 一个容器首次最多排版这么多子节点；其余用「显示更多」每次再加一页，避免一次组合出 200 行。 */
internal const val PI_TREE_PAGE = 50

/** One leaf's display cap, in characters; "复制值" still copies the whole value. */
internal const val PI_TREE_MAX_SCALAR_CHARS = 400

/** Indentation per tree level, `dp`. */
internal const val PI_TREE_INDENT_DP = 14

/**
 * 第 [depth] 层的左缩进（dp）。前 6 层每层 14 dp；更深的每层只加 5 dp，总量封顶 140 dp，
 * 这样 64 层的极端文档也始终给内容留得出宽度（不做横向滚动：会破坏标签行的自动换行）。
 */
internal fun piTreeIndentDp(depth: Int): Int =
    (PI_TREE_INDENT_DP * minOf(depth, 6) + 5 * maxOf(depth - 6, 0)).coerceAtMost(140)

/**
 * One-line, escaped, length-capped preview of a string leaf.
 *
 * Escapes newlines/tabs/quotes/backslashes so a leaf cannot add rows to the tree,
 * and appends `…` when it was cut. Display only: the copy action uses the model's
 * full literal, never this string.
 */
internal fun piTreePreview(
    text: String,
    maxChars: Int = PI_TREE_MAX_SCALAR_CHARS,
): String {
    val truncated = text.length > maxChars
    val limit = if (truncated) maxChars else text.length
    val out = StringBuilder(limit + 1)
    for (index in 0 until limit) {
        when (val ch = text[index]) {
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            else -> out.append(if (ch < ' ') ' ' else ch)
        }
    }
    if (truncated) out.append('…')
    return out.toString()
}

/** Key/index label with the same one-line guarantee, independent of the value cap. */
internal fun piTreeNodeLabel(text: String, maxChars: Int = 80): String = piTreePreview(text, maxChars)

/**
 * Chrome shared by the JSON and XML tree panels.
 *
 * Only chrome lives here — the mode switch, the raw-source fallback, the small
 * per-row action menu. The two tree views own their own node rendering and never
 * re-implement colours or type roles: every colour is [PiTheme.palette], every text
 * style comes from [PiTheme.typography] or [PiTheme.text] (the project's rule that
 * machine output is the mono face, UI labels the sans face).
 */

/** 分段控件（源码 | 树）+ 一行状态（已复制 / 节点数 / 失败原因），下方一条发丝线。 */
@Composable
internal fun PiTreeToolbar(
    mode: PiTreeMode,
    treeAvailable: Boolean,
    status: String?,
    onSelect: (PiTreeMode) -> Unit,
    modifier: Modifier = Modifier,
    treeLabel: String = "树",
) {
    val palette = PiTheme.palette
    val slot = LocalPiCardMenuSlot.current
    if (slot != null) {
        // 在卡片里：切换动作与状态文字交给卡片头（⋮ 菜单 + 状态位），这里不画工具栏。
        val select = androidx.compose.runtime.rememberUpdatedState(onSelect)
        androidx.compose.runtime.SideEffect {
            val toTree = mode == PiTreeMode.Code
            slot.publish(
                sig = Triple(mode, treeAvailable, status),
                items = listOf(
                    PiCardMenuItem(
                        label = if (toTree) "查看$treeLabel" else "查看源码",
                        enabled = !toTree || treeAvailable,
                    ) { select.value(if (toTree) PiTreeMode.Tree else PiTreeMode.Code) },
                ),
                status = status,
            )
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(palette.mdCodeBlockBorder.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(2.dp),
        ) {
            PiTreeModeLabel(
                label = "源码",
                selected = mode == PiTreeMode.Code,
                enabled = true,
                onClickLabel = "查看原始源码",
                onClick = { onSelect(PiTreeMode.Code) },
            )
            PiTreeModeLabel(
                label = treeLabel,
                selected = mode == PiTreeMode.Tree,
                enabled = treeAvailable,
                onClickLabel = "查看树状视图",
                onClick = { onSelect(PiTreeMode.Tree) },
            )
        }
        Text(
            text = status.orEmpty(),
            style = PiTheme.text.monoSmall,
            color = palette.muted,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun PiTreeModeLabel(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    Text(
        text = label,
        style = PiTheme.text.monoSmall,
        color = when {
            selected -> palette.accent
            enabled -> palette.muted
            else -> palette.dim
        },
        maxLines = 1,
        modifier = Modifier
            .background(
                if (selected) palette.accent.copy(alpha = 0.14f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .then(if (enabled) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(vertical = 5.dp, horizontal = 12.dp),
    )
}

/** 每个树行左侧的缩进参考线：每一层一条，行与行首尾相接，连成连续竖线。 */
internal fun Modifier.piTreeGuides(depth: Int, color: Color): Modifier =
    if (depth <= 0) this else this.drawBehind {
        val stroke = 1.dp.toPx()
        for (level in 1..depth) {
            val x = piTreeIndentDp(level).dp.toPx() - 5.dp.toPx()
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
        }
    }

/** 「显示更多（还剩 N）」行：点一下再多排一页。 */
@Composable
internal fun PiTreeMoreRow(depth: Int, remaining: Int, onMore: () -> Unit) {
    Text(
        text = "显示更多（还有 $remaining 项）",
        style = PiTheme.text.monoSmall,
        color = PiTheme.palette.accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "显示更多", onClick = onMore)
            .padding(start = (piTreeIndentDp(depth) + 15).dp, top = 6.dp, bottom = 6.dp),
    )
}

/**
 * The fallback view: the fence body verbatim, plus an optional one-line reason.
 *
 * This is what a caller sees when the tree cannot be shown — a parse failure, a
 * limit, a streaming fence, or no safe parser (YAML). It is deliberately plain: the
 * parent (`PiCodeSurface`) owns the real code block, and this component must never
 * pretend to be it. Copying always yields the original source, never a parsed
 * rendering (`§二` "复制操作默认复制原始源码").
 */
@Composable
internal fun PiTreeRawSource(
    source: String,
    notice: String?,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Column(modifier.fillMaxWidth()) {
        if (!notice.isNullOrEmpty()) {
            Text(
                text = notice,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
            )
        }
        Text(
            text = source,
            style = PiTheme.typography.styles.code.copy(color = palette.text),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        )
    }
}

/**
 * The per-row `⋯` menu: "复制路径", "复制值", … for one node.
 *
 * A plain drop-down, no animation or scrim, matching the transcript's existing
 * block menus. [actions] are label → action pairs; the caller closes over the node
 * it is rendering, so no path is reconstructed here.
 */
@Composable
internal fun PiTreeRowMenu(actions: List<Pair<String, () -> Unit>>) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "⋯",
            style = PiTheme.text.monoSmall,
            color = PiTheme.palette.dim.copy(alpha = 0.7f),
            modifier = Modifier
                .clickable(onClickLabel = "更多操作") { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/** 工具栏与树/源码之间的发丝线，和 `PiCardDivider` 同色同粗。 */
@Composable
internal fun PiCardHairline() {
    // 在卡片里时，卡片头下面已经有一条分隔线。
    if (LocalPiCardMenuSlot.current != null) return
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PiTheme.palette.mdCodeBlockBorder.copy(alpha = 0.16f)),
    )
}
