package app.pi.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.render.rich.FenceInfo
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiTheme
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import kotlinx.coroutines.delay

/**
 * 代码块增强：文件名/语言、复制反馈、行号、折叠、自动换行。
 *
 * 这里是纯展示层，不解析代码、不改高亮、不改复制内容。高亮结果由
 * [PiHighlightedCode] 传进来，所以增加这些控件不会让高亮请求多一次。
 *
 * ## 为什么要独立的 state
 *
 * 三个开关是“这一块代码”的本地 UI 状态，不是全局设置；`remember(code)` 让同一段
 * 代码在重组/滚动回来时保持用户选过的状态，代码变了就回到默认值。默认值刻意
 * 保守：不显示行号、横向滚动、长块折叠——不改变现有代码块的观感。
 *
 * ## 行号与换行不能同时开
 *
 * 开了自动换行后，一行代码可能占多行，固定的行号列会和正文错位。这里直接禁用
 * 行号（并把它关掉），而不是画一套对不齐的行号。
 */
@Composable
internal fun PiRichCodeBlock(
    code: String,
    info: FenceInfo,
    highlighted: PiHighlightedCode,
    style: TextStyle,
    palette: PiPalette,
    classic: Boolean,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // 这三个是**读者**的状态（行号 / 自动换行 / 展开全部），不是由 `code` 派生的值：
    // 1. 不能以 `code` 为 key —— 流式中的未闭合围栏每来一个 token `code` 就变一次，key 一变状态
    //    立刻被重置，读者刚点开「自动换行」下一个 token 就弹回去，菜单也被收起；
    // 2. 必须 `rememberSaveable`（`BlockChrome.kt` 里同一条规则）：行滚出屏幕再回来是一次全新
    //    的组合，普通 `remember` 会让展开的块回到折叠，行高突变，列表位置跟着跳。
    var showLineNumbers by rememberSaveable { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val allLines = remember(code) { code.split('\n') }
    val lineCount = allLines.size
    val truncated = lineCount > MAX_COLLAPSED_LINES && !expanded
    val shownLineCount = if (truncated) MAX_COLLAPSED_LINES else lineCount
    val shownCode = remember(code, truncated) {
        if (!truncated) code else code.lineSequence().take(MAX_COLLAPSED_LINES).joinToString("\n")
    }
    val shownHighlighted = remember(highlighted.text, truncated, shownCode) {
        if (!truncated) highlighted.text else highlighted.text.subSequence(0, shownCode.length.coerceAtMost(highlighted.text.length))
    }

    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    MarkdownCodeBackground(
        color = palette.mdCodeBlockBorder.copy(alpha = 0.06f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            palette.mdCodeBlockBorder.copy(alpha = 0.28f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = if (classic) 8.dp else 0.dp),
        showHeader = false,
        language = info.language,
        code = code,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.title ?: info.language,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.mdCodeBlockBorder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.title != null && info.language.isNotEmpty()) {
                        Text(
                            text = info.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.mdCodeBlockBorder,
                    modifier = Modifier
                        .clickable(onClickLabel = "复制代码") {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                )
                Box {
                    Text(
                        text = "⋯",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.mdCodeBlockBorder,
                        modifier = Modifier
                            .clickable(onClickLabel = "代码块显示选项") { menuOpen = true }
                            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (showLineNumbers) "隐藏行号" else "显示行号") },
                            enabled = !wrap,
                            onClick = {
                                showLineNumbers = !showLineNumbers
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (wrap) "横向滚动" else "自动换行") },
                            onClick = {
                                wrap = !wrap
                                if (wrap) showLineNumbers = false
                                menuOpen = false
                            },
                        )
                        if (lineCount > MAX_COLLAPSED_LINES) {
                            DropdownMenuItem(
                                text = { Text(if (expanded) "收起" else "展开全部（$lineCount 行）") },
                                onClick = {
                                    expanded = !expanded
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            val codeColor = if (highlighted.languageKnown) palette.text else palette.mdCodeBlock
            if (wrap) {
                Text(
                    text = shownHighlighted,
                    style = style.copy(color = codeColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (showLineNumbers) {
                        Text(
                            text = (1..shownLineCount).joinToString("\n"),
                            style = style.copy(color = palette.muted, textAlign = TextAlign.End),
                            modifier = Modifier
                                .width((shownLineCount.coerceAtLeast(1).toString().length * 8 + 9).dp)
                                .padding(end = 8.dp),
                        )
                    }
                    Text(
                        text = shownHighlighted,
                        style = style.copy(color = codeColor),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }

            if (lineCount > MAX_COLLAPSED_LINES) {
                Text(
                    text = if (expanded) "收起" else "展开全部（$lineCount 行）",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.mdCodeBlockBorder,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable { expanded = !expanded }
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/**
 * 长代码块默认展示的行数。24 行在手机上一屏左右，足够看清一段函数；更长的块
 * 由用户主动展开，避免一条回复里几千行代码把列表撑爆。这个数只影响展示，
 * 复制始终是全文。
 */
private const val MAX_COLLAPSED_LINES = 24

/**
 * JSON / XML 树视图的外框：与 [PiRichCodeBlock] 同一套卡片（12 dp 圆角、28% 边线、6% 淡底）
 * 加一行标题（语言名或围栏 title）和「复制」（复制**整段源码**）。
 *
 * 树视图本身（`PiJsonTreeView` / `PiXmlTreeView`）只有「源码 / 树」工具栏和树行，没有任何外框：
 * 直接放进 `PiCodeSurface` 时，一个 JSON 块在页面上是光秃秃的一排字，与同页其他代码块
 * （有边框、有语言名、能复制）完全不是一个东西，整段 JSON 也没有一键复制。外框放在这里而不是
 * 树视图里，是因为树视图文档写明它「不拥有真正的代码块」。
 */
@Composable
internal fun PiRichCardFrame(
    code: String,
    info: FenceInfo,
    palette: PiPalette,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    val classic = PiTheme.typography.isClassic
    MarkdownCodeBackground(
        color = palette.mdCodeBlockBorder.copy(alpha = 0.06f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.mdCodeBlockBorder.copy(alpha = 0.28f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = if (classic) 8.dp else 0.dp),
        showHeader = false,
        language = info.language,
        code = code,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = info.title ?: info.language,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.mdCodeBlockBorder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.mdCodeBlockBorder,
                    modifier = Modifier
                        .clickable(onClickLabel = "复制代码") {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            content()
        }
    }
}
