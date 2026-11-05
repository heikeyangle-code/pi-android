package app.pi.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    extraMenu: List<PiCardMenuItem> = emptyList(),
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

    // diff / patch：整行底色（加 = 绿、删 = 红、@@ = 强调色），不只是字符着色。
    val isDiff = info.language == "diff" || info.language == "patch"
    val displayText = remember(shownHighlighted, isDiff, palette) {
        if (!isDiff) shownHighlighted else piDiffTinted(shownHighlighted, palette)
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
            PiCardHeader(
                language = info.language,
                title = info.title,
                palette = palette,
                copied = copied,
                onCopy = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                menu = {
                    PiCardMenu(
                        palette = palette,
                        items = buildList {
                            addAll(extraMenu)
                            add(
                                PiCardMenuItem(
                                    label = if (showLineNumbers) "隐藏行号" else "显示行号",
                                    enabled = !wrap,
                                ) { showLineNumbers = !showLineNumbers },
                            )
                            add(
                                PiCardMenuItem(label = if (wrap) "横向滚动" else "自动换行") {
                                    wrap = !wrap
                                    if (wrap) showLineNumbers = false
                                },
                            )
                            if (lineCount > MAX_COLLAPSED_LINES) {
                                add(
                                    PiCardMenuItem(
                                        label = if (expanded) "收起" else "展开全部（$lineCount 行）",
                                    ) { expanded = !expanded },
                                )
                            }
                        },
                    )
                },
            )
            PiCardDivider(palette)

            val codeColor = if (highlighted.languageKnown) palette.text else palette.mdCodeBlock
            if (wrap) {
                Text(
                    text = displayText,
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
                        text = displayText,
                        style = style.copy(color = codeColor),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }

            if (lineCount > MAX_COLLAPSED_LINES) {
                PiCardDivider(palette)
                Text(
                    text = if (expanded) "收起 ▴" else "展开全部（$lineCount 行）▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 10.dp),
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

/** 给 diff 的每一行铺底色：`+`（不含 `+++`）绿、`-`（不含 `---`）红、`@@` 强调色。 */
private fun piDiffTinted(text: AnnotatedString, palette: PiPalette): AnnotatedString {
    val raw = text.text
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        var start = 0
        while (start <= raw.length) {
            var end = raw.indexOf('\n', start)
            if (end < 0) end = raw.length
            if (end > start) {
                val tint = when {
                    raw.startsWith("+++", start) || raw.startsWith("---", start) -> null
                    raw[start] == '+' -> palette.toolDiffAdded.copy(alpha = 0.18f)
                    raw[start] == '-' -> palette.toolDiffRemoved.copy(alpha = 0.18f)
                    raw.startsWith("@@", start) -> palette.accent.copy(alpha = 0.12f)
                    else -> null
                }
                if (tint != null) addStyle(androidx.compose.ui.text.SpanStyle(background = tint), start, end)
            }
            start = end + 1
        }
    }
}

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
    status: String? = null,
    menu: List<PiCardMenuItem> = emptyList(),
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
    val slot = remember { PiCardMenuSlot() }
    val allMenu = menu + slot.items
    val shownStatus = status ?: slot.status
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
            PiCardHeader(
                language = info.language,
                title = info.title,
                palette = palette,
                copied = copied,
                onCopy = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                status = shownStatus,
                menu = if (allMenu.isEmpty()) null else ({ PiCardMenu(palette, allMenu) }),
            )
            PiCardDivider(palette)
            androidx.compose.runtime.CompositionLocalProvider(LocalPiCardMenuSlot provides slot) {
                content()
            }
        }
    }
}

/**
 * 代码块与 JSON / XML 树共用的卡片头：左侧语言胶囊 + 可选标题，右侧「复制」和可选菜单。
 * 三者共用一个头，才不会同一页里出现三种长相的「代码卡片」。
 */
@Composable
internal fun PiCardHeader(
    language: String,
    title: String?,
    palette: PiPalette,
    copied: Boolean,
    onCopy: () -> Unit,
    status: String? = null,
    menu: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (language.isNotEmpty()) {
            Text(
                text = language,
                style = PiTheme.text.monoSmall,
                color = palette.accent,
                maxLines = 1,
                modifier = Modifier
                    .background(palette.accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (!title.isNullOrEmpty()) {
            if (language.isNotEmpty()) Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        if (!status.isNullOrEmpty()) {
            Text(
                text = status,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        PiCopyIcon(palette = palette, copied = copied, onCopy = onCopy)
        menu?.invoke()
    }
}

/**
 * 所有卡片共用的复制按钮：一个 18 dp 的小图标（复制后 1.2 s 变成对勾），触控区 40 dp。
 * 不再占一个「复制」字的宽度，窄屏上语言胶囊、标题、状态都有更多位置。
 */
@Composable
internal fun PiCopyIcon(palette: PiPalette, copied: Boolean, onCopy: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(role = Role.Button, onClickLabel = "复制源码", onClick = onCopy)
            .semantics { contentDescription = if (copied) "已复制" else "复制" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
            contentDescription = null,
            tint = if (copied) palette.accent else palette.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 卡片头的「菜单槽」。[PiRichCardFrame] 建一个并通过 [LocalPiCardMenuSlot] 提供；卡片里的内容
 * （树 / CSV 的「源码 ↔ 树」切换及其状态文字）把自己的动作登记进来，由卡片头的 ⋮ 菜单和状态位
 * 呈现，内容区里就不再需要自己的工具栏。没有框的场合（槽为 `null`）内容自己画工具栏。
 */
internal class PiCardMenuSlot {
    var items by mutableStateOf<List<PiCardMenuItem>>(emptyList())
        private set
    var status by mutableStateOf<String?>(null)
        private set
    private var signature: Any? = null

    /** 只在 [sig] 变了才写：内容每次重组都调用也不会反复触发卡片头重组。 */
    fun publish(sig: Any, items: List<PiCardMenuItem>, status: String?) {
        if (signature == sig) return
        signature = sig
        this.items = items
        this.status = status
    }
}

internal val LocalPiCardMenuSlot = androidx.compose.runtime.compositionLocalOf<PiCardMenuSlot?> { null }

internal class PiCardMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** 卡片头最右边的「⋮」菜单：源码/图切换、适应窗口、行号、换行等低频动作都收在这里。 */
@Composable
internal fun PiCardMenu(palette: PiPalette, items: List<PiCardMenuItem>) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(role = Role.Button, onClickLabel = "更多选项") { open = true }
                .semantics { contentDescription = "更多选项" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/** 卡片内的发丝分隔线（头 / 正文 / 折叠条之间）。 */
@Composable
internal fun PiCardDivider(palette: PiPalette) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.mdCodeBlockBorder.copy(alpha = 0.16f)),
    )
}
