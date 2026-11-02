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
 * The Compose **YAML tree** (`§七.3` of `docs/plans/rich-rendering-upgrade.md`).
 *
 * The model, the SnakeYAML safety configuration and every bound live in
 * `PiYamlTree.kt`; this file only draws [YamlTreeNode]s. It is separate from the
 * model because a Compose import cannot be compiled by the bare-JVM harnesses
 * (`PiYamlTreeCheck.kt` tests the model).
 *
 * ## Interaction
 *
 * - Mappings and sequences fold: the `▸`/`▾` glyph or a tap on the summary line.
 *   The root starts expanded and every nested container starts folded. That shows a
 *   small document's top level immediately while keeping first paint bounded to the
 *   root plus at most [PI_TREE_MAX_VISIBLE_CHILDREN] rows — expanding a deep tree is
 *   always a user action, never something a 20,000-node payload can force during
 *   streaming.
 * - Every row has a `⋯` menu with **复制路径** (`$`, `$.server.port`,
 *   `$['a.b']`, `$.items[2]`) and, for leaves, **复制值**. YAML leaves copy their
 *   resolved text (a string without added quotes), because a YAML value is what the
 *   user would paste back; the source view remains the place that preserves the
 *   original quoting/spelling.
 * - The toolbar switches between the tree and the original source. The source is
 *   the default ([PiTreeMode.Code]); a parse failure, a limit, a streaming fence or
 *   a construct the model cannot represent keeps it visible with a one-line reason.
 *
 * ## Bounds at display time
 *
 * The parse limits are the model's (256 KiB, depth 64, 20,000 nodes, the SnakeYAML
 * alias cap). Drawing is further capped so a legal-but-huge document cannot freeze
 * composition:
 * - a container shows at most [PI_TREE_MAX_VISIBLE_CHILDREN] children, then a
 *   stated "还有 N 项" row (the data is not truncated anywhere — copy/source view
 *   still have it);
 * - a string leaf shows at most [PI_TREE_MAX_SCALAR_CHARS] characters, escaped
 *   onto one line, with `…`; "复制值" copies the full value.
 *
 * Colours are [PiTheme.palette] tokens only (string → `syntaxString`, number →
 * `syntaxNumber`, boolean → `syntaxKeyword`, null → `muted`, keys →
 * `syntaxType`, punctuation → `syntaxPunctuation`); the body is
 * [PiTheme.typography]'s code face.
 */
@Composable
fun PiYamlTreeView(
    source: String,
    settled: Boolean = true,
    initialMode: PiTreeMode = PiTreeMode.Code,
    modifier: Modifier = Modifier,
) {
    val limits = remember { YamlTreeLimits() }
    val result = remember(source, settled) { PiYamlTree.parse(source, settled, limits) }
    val tree = result as? YamlTreeResult.Tree
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
        mode == PiTreeMode.Tree && result is YamlTreeResult.Failure ->
            yamlFailureNotice(result.reason, limits)
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
            PiYamlTreeBody(
                root = tree.root,
                onCopy = { text, label ->
                    clipboard.setText(AnnotatedString(text))
                    feedback = "已复制$label"
                },
            )
        } else {
            PiTreeRawSource(
                source = source,
                notice = if (mode == PiTreeMode.Tree && result is YamlTreeResult.Failure) {
                    yamlFailureNotice(result.reason, limits)
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
fun PiYamlTreeBody(
    root: YamlTreeNode,
    onCopy: (text: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        YamlNodeRow(node = root, label = null, isSequenceIndex = false, onCopy = onCopy)
    }
}

@Composable
private fun YamlNodeRow(
    node: YamlTreeNode,
    label: String?,
    isSequenceIndex: Boolean,
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
                        text = if (isSequenceIndex) label else "$label: ",
                        style = body.copy(
                            color = if (isSequenceIndex) {
                                palette.syntaxPunctuation
                            } else {
                                palette.syntaxType
                            },
                        ),
                    )
                }

                when (node) {
                    is YamlMapNode -> Text(
                        text = "{ ${node.entries.size} 个键 }",
                        style = body.copy(color = palette.muted),
                    )
                    is YamlListNode -> Text(
                        text = "[ ${node.items.size} 项 ]",
                        style = body.copy(color = palette.muted),
                    )
                    is YamlScalarNode -> YamlScalarValue(node, body = body)
                }
            }

            Spacer(Modifier.width(4.dp))
            PiTreeRowMenu(actions = yamlRowActions(node, onCopy))
        }

        if (expanded) {
            when (node) {
                is YamlMapNode -> {
                    val shown = node.entries.take(PI_TREE_MAX_VISIBLE_CHILDREN)
                    shown.forEach { entry ->
                        YamlNodeRow(
                            node = entry.value,
                            label = piTreeNodeLabel(entry.key),
                            isSequenceIndex = false,
                            onCopy = onCopy,
                        )
                    }
                    val hidden = node.entries.size - shown.size
                    if (hidden > 0) {
                        YamlHiddenChildrenRow(depth = node.depth + 1, hidden = hidden)
                    }
                }
                is YamlListNode -> {
                    val shown = node.items.take(PI_TREE_MAX_VISIBLE_CHILDREN)
                    shown.forEach { item ->
                        YamlNodeRow(
                            node = item.value,
                            label = "[${item.index}]",
                            isSequenceIndex = true,
                            onCopy = onCopy,
                        )
                    }
                    val hidden = node.items.size - shown.size
                    if (hidden > 0) {
                        YamlHiddenChildrenRow(depth = node.depth + 1, hidden = hidden)
                    }
                }
                is YamlScalarNode -> Unit
            }
        }
    }
}

@Composable
private fun YamlScalarValue(node: YamlScalarNode, body: androidx.compose.ui.text.TextStyle) {
    val palette = PiTheme.palette
    val color = when (node.kind) {
        YamlScalarKind.String -> palette.syntaxString
        YamlScalarKind.Number -> palette.syntaxNumber
        YamlScalarKind.Boolean -> palette.syntaxKeyword
        YamlScalarKind.Null -> palette.muted
    }
    val text = when (node.kind) {
        YamlScalarKind.String -> "\"${piTreePreview(node.text)}\""
        else -> node.text
    }
    Text(text = text, style = body.copy(color = color))
}

@Composable
private fun YamlHiddenChildrenRow(depth: Int, hidden: Int) {
    Text(
        text = "… 还有 $hidden 项，切换源码或复制完整 YAML 查看",
        style = PiTheme.text.monoSmall,
        color = PiTheme.palette.muted,
        modifier = Modifier.padding(
            start = (depth * PI_TREE_INDENT_DP).dp,
            top = 2.dp,
            bottom = 2.dp,
        ),
    )
}

private fun yamlRowActions(
    node: YamlTreeNode,
    onCopy: (text: String, label: String) -> Unit,
): List<Pair<String, () -> Unit>> {
    val actions = ArrayList<Pair<String, () -> Unit>>(2)
    actions += "复制路径" to { onCopy(node.path, "路径") }
    if (node is YamlScalarNode) {
        actions += "复制值" to { onCopy(node.text, "值") }
    }
    return actions
}

/**
 * One line's reason, built from the same numbers [PiYamlTree.parse] enforces so the
 * sentence cannot drift from the gate.
 */
private fun yamlFailureNotice(reason: YamlTreeFailure, limits: YamlTreeLimits): String = when (reason) {
    YamlTreeFailure.NotSettled -> "YAML 还在生成，先显示源码"
    YamlTreeFailure.Empty -> "YAML 为空，先显示源码"
    YamlTreeFailure.TooLarge -> "YAML 超过 ${limits.maxChars / 1024} KiB，已回退源码"
    YamlTreeFailure.TooDeep -> "YAML 嵌套超过 ${limits.maxDepth} 层，已回退源码"
    YamlTreeFailure.TooManyNodes -> "YAML 超过 ${limits.maxNodes} 个节点，已回退源码"
    YamlTreeFailure.Unsupported -> "YAML 含树视图不支持的写法，已回退源码"
    YamlTreeFailure.Malformed -> "YAML 解析失败，已回退源码"
}
