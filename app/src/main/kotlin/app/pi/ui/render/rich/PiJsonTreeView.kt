package app.pi.ui.render.rich

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * The Compose **JSON tree** (`§七.1` of `docs/plans/rich-rendering-upgrade.md`).
 *
 * The model, parser and every bound live in `PiJsonTree.kt`; this file only draws
 * [JsonTreeNode]s. It is separate from the model because a Compose import cannot be
 * compiled by the bare-JVM harnesses (`PiJsonTreeCheck.kt` tests the model).
 *
 * ## Interaction
 *
 * - Objects and arrays fold: the `▸`/`▾` glyph or a tap on the summary line.
 *   The root starts expanded and every nested container starts folded. That shows
 *   a small document's top level immediately while keeping first paint bounded to
 *   the root plus at most [PI_TREE_MAX_VISIBLE_CHILDREN] rows — expanding a deep
 *   tree is always a user action, never something a 20,000-node payload can force
 *   during streaming.
 * - Every row has a `⋯` menu with **复制路径** (`$.user.name`, `$.items[2]`,
 *   `$['a.b']`) and, for leaves, **复制值** (the JSON token, e.g. `"text"` / `42` /
 *   `true` / `null`).
 * - The toolbar switches between the tree and the original source. The source is
 *   the default ([PiTreeMode.Code]); a parse failure, a limit, or a streaming fence
 *   keeps it visible with a one-line reason.
 *
 * ## Bounds at display time
 *
 * The parse limits are the model's (256 KiB, depth 64, 20,000 nodes). Drawing is
 * further capped so a legal-but-huge document cannot freeze composition:
 * - a container shows at most [PI_TREE_MAX_VISIBLE_CHILDREN] children, then a
 *   stated "还有 N 项" row (the data is not truncated anywhere — copy/source view
 *   still have it);
 * - a string leaf shows at most [PI_TREE_MAX_SCALAR_CHARS] characters, escaped
 *   onto one line, with `…`; "复制值" copies the full literal.
 *
 * Colours are [PiTheme.palette] tokens only (string → `syntaxString`, number →
 * `syntaxNumber`, boolean → `syntaxKeyword`, null → `muted`, keys →
 * `syntaxType`, punctuation → `syntaxPunctuation`); the body is
 * [PiTheme.typography]'s code face.
 */
@Composable
fun PiJsonTreeView(
    source: String,
    settled: Boolean = true,
    initialMode: PiTreeMode = PiTreeMode.Code,
    modifier: Modifier = Modifier,
) {
    val limits = remember { JsonTreeLimits() }
    val result = remember(source, settled) { PiJsonTree.parse(source, settled, limits) }
    val tree = result as? JsonTreeResult.Tree
    var mode by remember(source, initialMode) { mutableStateOf(initialMode) }

    val clipboard = LocalClipboardManager.current
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1500)
            feedback = null
        }
    }

    // A delegated `var` cannot be smart-cast, so take the value once.
    val currentFeedback: String? = feedback
    val status = when {
        currentFeedback != null -> currentFeedback
        tree != null -> "${tree.nodeCount} 个节点"
        mode == PiTreeMode.Tree && result is JsonTreeResult.Failure ->
            jsonFailureNotice(result.reason, limits)
        else -> null
    }

    Column(modifier.fillMaxWidth()) {
        PiTreeToolbar(
            mode = mode,
            treeAvailable = tree != null,
            status = status,
            onSelect = { mode = it },
        )
        if (mode == PiTreeMode.Tree && tree != null) {
            PiJsonTreeBody(
                root = tree.root,
                onCopy = { text, label ->
                    clipboard.setText(AnnotatedString(text))
                    feedback = "已复制$label"
                },
            )
        } else {
            PiTreeRawSource(
                source = source,
                notice = if (mode == PiTreeMode.Tree && result is JsonTreeResult.Failure) {
                    jsonFailureNotice(result.reason, limits)
                } else {
                    null
                },
            )
        }
    }
}

/**
 * The tree itself, without the toolbar/fallback chrome.
 *
 * Public so a parent that owns its own chrome (the future `PiCodeSurface` header)
 * can draw just the tree; [onCopy] receives `(text, label)` so feedback stays with
 * the caller's snackbar/status.
 */
@Composable
fun PiJsonTreeBody(
    root: JsonTreeNode,
    onCopy: (text: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        JsonNodeRow(node = root, label = null, isArrayIndex = false, onCopy = onCopy)
    }
}

@Composable
private fun JsonNodeRow(
    node: JsonTreeNode,
    label: String?,
    isArrayIndex: Boolean,
    onCopy: (text: String, label: String) -> Unit,
) {
    val palette = PiTheme.palette
    val body = PiTheme.typography.styles.code
    var expanded by remember(node.path) { mutableStateOf(node.depth == 0) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = (node.depth * PI_TREE_INDENT_DP).dp,
                    top = 2.dp,
                    end = 4.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            if (node.isContainer) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = body.copy(color = palette.dim),
                    modifier = Modifier
                        .clickable(
                            onClickLabel = if (expanded) "收起" else "展开",
                        ) { expanded = !expanded }
                        .padding(end = 3.dp),
                )
            } else {
                Spacer(Modifier.width(15.dp))
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
            ) {
                if (label != null) {
                    Text(
                        text = if (isArrayIndex) label else "$label: ",
                        style = body.copy(
                            color = if (isArrayIndex) {
                                palette.syntaxPunctuation
                            } else {
                                palette.syntaxType
                            },
                        ),
                    )
                }

                when (node) {
                    is JsonObjectNode -> Text(
                        text = "{ ${node.entries.size} 个键 }",
                        style = body.copy(color = palette.muted),
                    )
                    is JsonArrayNode -> Text(
                        text = "[ ${node.items.size} 项 ]",
                        style = body.copy(color = palette.muted),
                    )
                    is JsonScalarNode -> JsonScalarValue(node, body = body)
                }
            }

            Spacer(Modifier.width(4.dp))
            PiTreeRowMenu(actions = jsonRowActions(node, onCopy))
        }

        if (expanded) {
            when (node) {
                is JsonObjectNode -> {
                    val shown = node.entries.take(PI_TREE_MAX_VISIBLE_CHILDREN)
                    shown.forEach { entry ->
                        JsonNodeRow(
                            node = entry.value,
                            label = piTreeNodeLabel(entry.key),
                            isArrayIndex = false,
                            onCopy = onCopy,
                        )
                    }
                    val hidden = node.entries.size - shown.size
                    if (hidden > 0) {
                        JsonHiddenChildrenRow(depth = node.depth + 1, hidden = hidden)
                    }
                }
                is JsonArrayNode -> {
                    val shown = node.items.take(PI_TREE_MAX_VISIBLE_CHILDREN)
                    shown.forEach { item ->
                        JsonNodeRow(
                            node = item.value,
                            label = "[${item.index}]",
                            isArrayIndex = true,
                            onCopy = onCopy,
                        )
                    }
                    val hidden = node.items.size - shown.size
                    if (hidden > 0) {
                        JsonHiddenChildrenRow(depth = node.depth + 1, hidden = hidden)
                    }
                }
                is JsonScalarNode -> Unit
            }
        }
    }
}

@Composable
private fun JsonScalarValue(node: JsonScalarNode, body: androidx.compose.ui.text.TextStyle) {
    val palette = PiTheme.palette
    val color = when (node.kind) {
        JsonScalarKind.String -> palette.syntaxString
        JsonScalarKind.Number -> palette.syntaxNumber
        JsonScalarKind.Boolean -> palette.syntaxKeyword
        JsonScalarKind.Null -> palette.muted
    }
    val text = when (node.kind) {
        JsonScalarKind.String -> "\"${piTreePreview(node.text)}\""
        else -> node.text
    }
    Text(text = text, style = body.copy(color = color))
}

@Composable
private fun JsonHiddenChildrenRow(depth: Int, hidden: Int) {
    Text(
        text = "… 还有 $hidden 项，切换源码或复制完整 JSON 查看",
        style = PiTheme.text.monoSmall,
        color = PiTheme.palette.muted,
        modifier = Modifier.padding(
            start = (depth * PI_TREE_INDENT_DP).dp,
            top = 2.dp,
            bottom = 2.dp,
        ),
    )
}

private fun jsonRowActions(
    node: JsonTreeNode,
    onCopy: (text: String, label: String) -> Unit,
): List<Pair<String, () -> Unit>> {
    val actions = ArrayList<Pair<String, () -> Unit>>(2)
    actions += "复制路径" to { onCopy(node.path, "路径") }
    if (node is JsonScalarNode) {
        actions += "复制值" to { onCopy(node.literal, "值") }
    }
    return actions
}

/**
 * One line's reason, built from the same numbers [PiJsonTree.parse] enforces so the
 * sentence cannot drift from the gate.
 */
private fun jsonFailureNotice(reason: JsonTreeFailure, limits: JsonTreeLimits): String = when (reason) {
    JsonTreeFailure.NotSettled -> "JSON 还在生成，先显示源码"
    JsonTreeFailure.Empty -> "JSON 为空，先显示源码"
    JsonTreeFailure.TooLarge -> "JSON 超过 ${limits.maxChars / 1024} KiB，已回退源码"
    JsonTreeFailure.TooDeep -> "JSON 嵌套超过 ${limits.maxDepth} 层，已回退源码"
    JsonTreeFailure.TooManyNodes -> "JSON 超过 ${limits.maxNodes} 个节点，已回退源码"
    JsonTreeFailure.Malformed -> "JSON 解析失败，已回退源码"
}
