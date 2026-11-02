package app.pi.ui.render.rich

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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

/** One leaf's display cap, in characters; "复制值" still copies the whole value. */
internal const val PI_TREE_MAX_SCALAR_CHARS = 400

/** Indentation per tree level, `dp`. */
internal const val PI_TREE_INDENT_DP = 14

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

/** The mode switch plus a one-line status (`已复制…`, node count, parse failure). */
@Composable
internal fun PiTreeToolbar(
    mode: PiTreeMode,
    treeAvailable: Boolean,
    status: String?,
    onSelect: (PiTreeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PiTreeModeLabel(
            label = "源码",
            selected = mode == PiTreeMode.Code,
            onClickLabel = "查看原始源码",
            onClick = { onSelect(PiTreeMode.Code) },
        )
        Spacer(Modifier.width(12.dp))
        if (treeAvailable) {
            PiTreeModeLabel(
                label = "树",
                selected = mode == PiTreeMode.Tree,
                onClickLabel = "查看树状视图",
                onClick = { onSelect(PiTreeMode.Tree) },
            )
        } else {
            Text(
                text = "树",
                style = PiTheme.text.monoSmall,
                color = palette.dim,
                maxLines = 1,
            )
        }
        if (!status.isNullOrEmpty()) {
            Text(
                text = status,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun PiTreeModeLabel(
    label: String,
    selected: Boolean,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    Text(
        text = label,
        style = PiTheme.text.monoSmall,
        color = if (selected) palette.accent else palette.muted,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
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
            color = PiTheme.palette.dim,
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
