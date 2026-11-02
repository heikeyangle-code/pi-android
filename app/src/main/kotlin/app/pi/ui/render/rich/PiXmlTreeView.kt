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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * The Compose **XML tree** (`§七.2` of `docs/plans/rich-rendering-upgrade.md`).
 *
 * The model, the well-formedness rules, the depth/node bounds and the
 * `DOCTYPE`/`ENTITY` gate live in `PiXmlTree.kt`; the Android `XmlPullParser`
 * adapter is `PiXmlTreeParser.kt`. This file draws [XmlNode]s and never parses.
 *
 * ## What a row is
 *
 * One element per row, as `<name attribute="value">` (or `<name />` when the
 * element has neither children nor text). Direct text is shown on the same line,
 * escaped and length-capped. Children are indented below their parent; the closing
 * tag is not drawn — this is a tree, and the fold state already says whether the
 * element still has hidden children.
 *
 * - `▸` / `▾` folds the element's children. The root starts expanded and every
 *   nested element starts folded, so first paint is bounded to the root plus at
 *   most [PI_TREE_MAX_VISIBLE_CHILDREN] rows and expanding is always a user action.
 * - The `⋯` menu copies the element's **path** (`/root/item[2]`) and, when it has
 *   direct text, that **text**.
 * - An attribute is a tap target itself: tapping `name="value"` copies the
 *   attribute's value (with the onClick label 「复制属性值」).
 * - The toolbar switches to the original XML; failures and limits fall back to it
 *   with a one-line reason.
 *
 * ## Bounds at display time
 *
 * Same policy as the JSON view: at most [PI_TREE_MAX_VISIBLE_CHILDREN] child
 * elements and 24 attributes per row are drawn, each with a stated "还有 N 项"
 * marker rather than silent loss; the model still holds everything within
 * [XmlTreeLimits]. All colours are [PiTheme.palette] tokens and the body uses
 * [PiTheme.typography]'s code face.
 */
@Composable
fun PiXmlTreeView(
    source: String,
    settled: Boolean = true,
    initialMode: PiTreeMode = PiTreeMode.Code,
    modifier: Modifier = Modifier,
) {
    val limits = remember { XmlTreeLimits() }
    val result = rememberTreeParse("xml", source, settled) { PiXmlTreeParser.parse(source, settled, limits) }
    val tree = result as? XmlTreeResult.Tree
    var mode by rememberSaveable(source, initialMode) { mutableStateOf(initialMode) }

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
        tree != null -> "${tree.nodeCount} 个元素"
        result == null -> "解析中…"
        mode == PiTreeMode.Tree && result is XmlTreeResult.Failure ->
            xmlFailureNotice(result.reason, limits)
        else -> null
    }

    Column(modifier.fillMaxWidth()) {
        PiTreeToolbar(
            mode = mode,
            treeAvailable = tree != null,
            status = status,
            onSelect = { mode = it },
        )
        PiCardHairline()
        if (mode == PiTreeMode.Tree && tree != null) {
            PiXmlTreeBody(
                root = tree.root,
                onCopy = { text, label ->
                    clipboard.setText(AnnotatedString(text))
                    feedback = "已复制$label"
                },
            )
        } else {
            PiTreeRawSource(
                source = source,
                notice = if (mode == PiTreeMode.Tree && result is XmlTreeResult.Failure) {
                    xmlFailureNotice(result.reason, limits)
                } else {
                    null
                },
            )
        }
    }
}

/**
 * The tree itself, without the toolbar/fallback chrome. [onCopy] receives
 * `(text, label)` so feedback stays with the caller's status line.
 */
@Composable
fun PiXmlTreeBody(
    root: XmlNode,
    onCopy: (text: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        XmlNodeRow(node = root, onCopy = onCopy)
    }
}

@Composable
private fun XmlNodeRow(node: XmlNode, onCopy: (text: String, label: String) -> Unit) {
    val palette = PiTheme.palette
    val body = PiTheme.typography.styles.code
    val hasChildren = node.children.isNotEmpty()
    var expanded by rememberSaveable(node.path) { mutableStateOf(node.depth == 0) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 整行都是展开/收起的点击区（含缩进部分）：原来只有那个 9 dp 宽的三角能点。
                .then(
                    if (hasChildren) {
                        Modifier.clickable(onClickLabel = if (expanded) "收起" else "展开") { expanded = !expanded }
                    } else {
                        Modifier
                    },
                )
                .piTreeGuides(node.depth, palette.mdCodeBlockBorder.copy(alpha = 0.18f))
                .padding(
                    start = (node.depth * PI_TREE_INDENT_DP).dp,
                    top = 3.dp,
                    end = 4.dp,
                    bottom = 1.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            if (hasChildren) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = body.copy(color = palette.dim),
                    modifier = Modifier.padding(end = 3.dp),
                )
            } else {
                Spacer(Modifier.width(15.dp))
            }

            // 标签行是**一个** `AnnotatedString`，不是一排并列的 `Text`：`Row` 不会换行，带三四个
            // 属性的标签在手机上要么被截断、要么每个 `Text` 被挤成一两个字宽的细条。单个 `Text`
            // 按词换行，颜色靠 span。（原来点属性名复制属性值的小手势随之去掉；行右侧的 ⋯ 菜单仍
            // 可复制路径与文本。）
            val shownAttributes = node.attributes.take(MAX_VISIBLE_ATTRIBUTES)
            val hiddenAttributes = node.attributes.size - shownAttributes.size
            val tagLine = remember(node, palette) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = palette.syntaxPunctuation)) { append("<") }
                    withStyle(SpanStyle(color = palette.syntaxKeyword)) { append(node.name) }
                    shownAttributes.forEach { attribute ->
                        append(" ")
                        withStyle(SpanStyle(color = palette.syntaxVariable)) { append("${attribute.name}=") }
                        withStyle(SpanStyle(color = palette.syntaxString)) {
                            append("\"${piTreePreview(attribute.value, 120)}\"")
                        }
                    }
                    if (hiddenAttributes > 0) {
                        withStyle(SpanStyle(color = palette.muted)) { append(" … +$hiddenAttributes") }
                    }
                    withStyle(SpanStyle(color = palette.syntaxPunctuation)) {
                        append(if (node.isEmptyElement) " />" else ">")
                    }
                    if (node.text != null) {
                        append("  ")
                        withStyle(SpanStyle(color = palette.text)) { append(piTreePreview(node.text)) }
                    }
                }
            }
            Text(
                text = tagLine,
                style = body,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(4.dp))
            PiTreeRowMenu(actions = xmlRowActions(node, onCopy))
        }

        if (expanded) {
            var limit by rememberSaveable(node.path) { mutableStateOf(PI_TREE_PAGE) }
            val shown = node.children.take(minOf(limit, PI_TREE_MAX_VISIBLE_CHILDREN))
            shown.forEach { child -> XmlNodeRow(node = child, onCopy = onCopy) }
            val hidden = node.children.size - shown.size
            if (hidden > 0 && shown.size < PI_TREE_MAX_VISIBLE_CHILDREN) {
                PiTreeMoreRow(node.depth + 1, hidden) { limit += PI_TREE_PAGE }
            } else if (hidden > 0) {
                Text(
                    text = "… 还有 $hidden 个子元素，切换源码查看",
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(
                        start = ((node.depth + 1) * PI_TREE_INDENT_DP).dp,
                        top = 2.dp,
                        bottom = 2.dp,
                    ),
                )
            }
        }
    }
}

private fun xmlRowActions(
    node: XmlNode,
    onCopy: (text: String, label: String) -> Unit,
): List<Pair<String, () -> Unit>> {
    val actions = ArrayList<Pair<String, () -> Unit>>(2)
    actions += "复制路径" to { onCopy(node.path, "路径") }
    node.text?.let { text ->
        actions += "复制文本" to { onCopy(text, "文本") }
    }
    return actions
}

/** One row shows at most this many attributes; the rest are counted, not drawn. */
private const val MAX_VISIBLE_ATTRIBUTES = 24

/**
 * One line's reason, built from the same numbers [PiXmlTreeParser.parse] enforces
 * so the sentence cannot drift from the gate.
 */
private fun xmlFailureNotice(reason: XmlTreeFailure, limits: XmlTreeLimits): String = when (reason) {
    XmlTreeFailure.NotSettled -> "XML 还在生成，先显示源码"
    XmlTreeFailure.Empty -> "XML 为空，先显示源码"
    XmlTreeFailure.TooLarge -> "XML 超过 ${limits.maxChars / 1024} KiB，已回退源码"
    XmlTreeFailure.TooDeep -> "XML 嵌套超过 ${limits.maxDepth} 层，已回退源码"
    XmlTreeFailure.TooManyNodes -> "XML 超过 ${limits.maxNodes} 个元素，已回退源码"
    XmlTreeFailure.Dtd -> "XML 含 DOCTYPE/ENTITY 声明，出于安全考虑只显示源码"
    XmlTreeFailure.Malformed -> "XML 解析失败，已回退源码"
}
