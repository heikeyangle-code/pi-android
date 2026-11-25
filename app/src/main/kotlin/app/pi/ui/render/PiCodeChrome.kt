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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import app.pi.ui.theme.PiContrast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import app.pi.i18n.tr
import app.pi.ui.render.rich.FenceInfo
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiTheme
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

    val lineCount = remember(code) { countCodeLines(code) }
    val truncated = lineCount > MAX_COLLAPSED_LINES && !expanded
    val shownLineCount = if (truncated) MAX_COLLAPSED_LINES else lineCount
    val shownCharEnd = remember(code, truncated) {
        if (!truncated) code.length else indexAfterNthCodeLine(code, MAX_COLLAPSED_LINES)
    }
    val shownHighlighted = remember(highlighted.text, truncated, shownCharEnd) {
        if (!truncated) {
            highlighted.text
        } else {
            highlighted.text.subSequence(0, shownCharEnd.coerceAtMost(highlighted.text.length))
        }
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

    val darkTheme = remember(palette) { PiContrast.luminance(palette.pageBg) < 0.5 }
    val cardSurface = palette.cardBg
    val footerTint = remember(palette, darkTheme) {
        lerp(palette.cardBg, palette.text, if (darkTheme) 0.055f else 0.038f)
    }

    Surface(
        color = cardSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            0.5.dp,
            palette.borderMuted.copy(alpha = 0.58f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        Column {
            // 「横向滚动 / 自动换行」「显示行号」「展开/收起」统一收进 ⋮ 菜单，保持代码块头部极简克制。
            PiCardHeader(
                language = info.language,
                title = info.title,
                palette = palette,
                copied = copied,
                onCopy = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                status = if (lineCount > 1) tr("$lineCount 行", "$lineCount lines") else null,
                menu = {
                    PiCardMenu(
                        palette = palette,
                        items = buildList {
                            addAll(extraMenu)
                            add(
                                PiCardMenuItem(
                                    label = if (showLineNumbers) tr("隐藏行号", "Hide line numbers") else tr("显示行号", "Show line numbers"),
                                    enabled = !wrap,
                                ) { showLineNumbers = !showLineNumbers },
                            )
                            add(
                                PiCardMenuItem(label = if (wrap) tr("切回横向滚动", "Horizontal scroll") else tr("自动换行", "Word wrap")) {
                                    wrap = !wrap
                                    if (wrap) showLineNumbers = false
                                },
                            )
                            if (lineCount > MAX_COLLAPSED_LINES) {
                                add(
                                    PiCardMenuItem(
                                        label = if (expanded) tr("收起", "Collapse") else tr("展开全部（$lineCount 行）", "Expand all ($lineCount lines)"),
                                    ) { expanded = !expanded },
                                )
                            }
                        },
                    )
                },
            )
            PiCardDivider(palette)

            val codeColor = if (highlighted.languageKnown) palette.text else palette.mdCodeBlock
            val codeScrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                if (wrap) {
                    Text(
                        text = displayText,
                        style = style.copy(color = codeColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        if (showLineNumbers) {
                            val gutterColor = palette.borderMuted.copy(alpha = 0.35f)
                            val fontSp = style.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 13f
                            val digits = shownLineCount.coerceAtLeast(1).toString().length
                            val gutterWidthDp = (digits * fontSp * 0.66f + 8f).dp
                            val lineNumbersText = remember(shownLineCount) {
                                buildString(shownLineCount * (digits + 1)) {
                                    for (ln in 1..shownLineCount) {
                                        if (ln > 1) append('\n')
                                        append(ln)
                                    }
                                }
                            }
                            Text(
                                text = lineNumbersText,
                                style = style.copy(
                                    color = palette.dim.copy(alpha = 0.78f),
                                    textAlign = TextAlign.End,
                                    fontFeatureSettings = app.pi.ui.theme.PiFontFeatures.CODE_LIGATURES_OFF,
                                ),
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .drawBehind {
                                        val x = size.width + 4.dp.toPx()
                                        drawLine(gutterColor, Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx())
                                    }
                                    .width(gutterWidthDp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    val fadePx = 18.dp.toPx().coerceAtMost(size.width * 0.25f)
                                    if (fadePx > 1f && codeScrollState.value > 0) {
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(Color.Transparent, Color.Black),
                                                startX = 0f,
                                                endX = fadePx,
                                            ),
                                            topLeft = Offset.Zero,
                                            size = Size(fadePx, size.height),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                    if (fadePx > 1f && codeScrollState.value < codeScrollState.maxValue) {
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startX = size.width - fadePx,
                                                endX = size.width,
                                            ),
                                            topLeft = Offset(size.width - fadePx, 0f),
                                            size = Size(fadePx, size.height),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                },
                        ) {
                            Text(
                                text = displayText,
                                style = style.copy(color = codeColor),
                                modifier = Modifier.horizontalScroll(codeScrollState),
                            )
                        }
                    }
                }

                if (truncated) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, cardSurface.copy(alpha = 0.92f)),
                                ),
                            ),
                    )
                }
            }

            if (lineCount > MAX_COLLAPSED_LINES) {
                PiCardDivider(palette)
                Text(
                    text = if (expanded) tr("收起代码 ▴", "Collapse code ▴") else tr("展开全部 $lineCount 行 ▾", "Expand all $lineCount lines ▾"),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(footerTint)
                        .clickable { expanded = !expanded }
                        .padding(vertical = 8.dp),
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

private fun countCodeLines(code: String): Int {
    if (code.isEmpty()) return 1
    var lines = 1
    var i = code.indexOf('\n')
    while (i >= 0) {
        lines++
        i = code.indexOf('\n', i + 1)
    }
    return lines
}

private fun indexAfterNthCodeLine(code: String, maxLines: Int): Int {
    if (maxLines <= 0) return 0
    var seen = 0
    var i = 0
    while (i < code.length) {
        val nl = code.indexOf('\n', i)
        if (nl < 0) return code.length
        seen++
        if (seen >= maxLines) return nl
        i = nl + 1
    }
    return code.length
}

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
    val slot = remember { PiCardMenuSlot() }
    val allMenu = menu + slot.items
    val shownStatus = status ?: slot.status
    Surface(
        color = palette.cardBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.58f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
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
    val darkTheme = remember(palette) { PiContrast.luminance(palette.pageBg) < 0.5 }
    val headerBg = remember(palette, darkTheme) {
        lerp(palette.cardBg, palette.text, if (darkTheme) 0.055f else 0.038f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(headerBg)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (language.isNotEmpty()) {
            Text(
                text = language,
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                color = palette.muted,
                maxLines = 1,
            )
        }
        if (!title.isNullOrEmpty()) {
            if (language.isNotEmpty()) {
                Text(
                    text = "·",
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = palette.text.copy(alpha = 0.86f),
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
                color = palette.dim,
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
 * 所有卡片共用的复制按钮：16 dp 精致图标（复制后 1.2 s 变成对勾），触控区 36 dp。
 */
@Composable
internal fun PiCopyIcon(palette: PiPalette, copied: Boolean, onCopy: () -> Unit) {
    val copyDesc = if (copied) tr("已复制", "Copied") else tr("复制", "Copy")
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(role = Role.Button, onClickLabel = tr("复制源码", "Copy source"), onClick = onCopy)
            .semantics { contentDescription = copyDesc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
            contentDescription = null,
            tint = if (copied) palette.success else palette.muted,
            modifier = Modifier.size(16.dp),
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
        val moreLabel = tr("更多选项", "More options")
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(role = Role.Button, onClickLabel = moreLabel) { open = true }
                .semantics { contentDescription = moreLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp),
            )
        }
        PiMenu(
            expanded = open,
            onDismiss = { open = false },
            placement = PiMenuPlacement.Below,
            alignEnd = true,
            items = items.map { item ->
                PiMenuItem(
                    label = item.label,
                    enabled = item.enabled,
                    onSelect = item.onClick,
                )
            },
        )
    }
}

/** 卡片内的极细发丝分隔线（头 / 正文 / 折叠条之间）。 */
@Composable
internal fun PiCardDivider(palette: PiPalette) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(palette.borderMuted.copy(alpha = 0.42f)),
    )
}
