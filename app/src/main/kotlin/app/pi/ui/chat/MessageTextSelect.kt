package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.RowScope
import app.pi.i18n.tr
import app.pi.ui.PiSeg
import app.pi.ui.PiTopBar
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn

internal enum class SelectableBlockKind {
    Heading,
    Code,
    Quote,
    List,
    Paragraph,
}

internal data class SelectableBlock(
    val index: Int,
    val kind: SelectableBlockKind,
    val label: String?,
    val rawText: String,
    val plainText: String,
)

private val FENCE_OPEN_RE = Regex("""^[ \t]{0,3}(`{3,}|~{3,})[ \t]*([^\s`~]*).*$""")
private val ATX_HEADING_RE = Regex("""^[ \t]{0,3}#{1,6}[ \t]+(.+?)[ \t]*#*[ \t]*$""")
private val LIST_LINE_RE = Regex("""^[ \t]*(?:[-*+]|\d+[.)])[ \t]+.*$""")
private val HORIZONTAL_RULE_RE = Regex("""^[ \t]{0,3}(?:[-*_][ \t]*){3,}$""")
private val IMAGE_LINK_RE = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
private val MD_LINK_RE = Regex("""\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
private val INLINE_CODE_RE = Regex("""`([^`]+)`""")
private val BOLD_STAR_RE = Regex("""\*\*([^*]+)\*\*""")
private val BOLD_UNDER_RE = Regex("""__([^_]+)__""")
private val STRIKE_RE = Regex("""~~([^~]+)~~""")
private val ITALIC_STAR_RE = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""")

internal fun stripInlineMarkdown(line: String): String {
    var out = line
    out = IMAGE_LINK_RE.replace(out) { m ->
        val alt = m.groupValues[1].trim()
        val url = m.groupValues[2].trim()
        alt.ifEmpty { url }
    }
    out = MD_LINK_RE.replace(out) { m ->
        val label = m.groupValues[1].trim()
        val url = m.groupValues[2].trim()
        if (label.isEmpty() || label.equals(url, ignoreCase = true)) url else "$label ($url)"
    }
    out = INLINE_CODE_RE.replace(out, "$1")
    out = BOLD_STAR_RE.replace(out, "$1")
    out = BOLD_UNDER_RE.replace(out, "$1")
    out = STRIKE_RE.replace(out, "$1")
    out = ITALIC_STAR_RE.replace(out, "$1")
    return out
}

/**
 * Splits a message into logical selectable blocks (fenced code blocks, headings, quotes,
 * lists, and paragraphs) while preserving every non-blank line in exact original order.
 */
internal fun splitSelectableBlocks(source: String): List<SelectableBlock> {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    if (normalized.isBlank()) return emptyList()

    val lines = normalized.split('\n')
    val blocks = ArrayList<SelectableBlock>()
    var i = 0

    fun emit(kind: SelectableBlockKind, label: String?, raw: String, plain: String) {
        val trimmedRaw = raw.trimEnd()
        val trimmedPlain = plain.trimEnd()
        if (trimmedRaw.isBlank() && trimmedPlain.isBlank()) return
        blocks += SelectableBlock(
            index = blocks.size,
            kind = kind,
            label = label,
            rawText = trimmedRaw,
            plainText = trimmedPlain.ifBlank { trimmedRaw },
        )
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank() || HORIZONTAL_RULE_RE.matches(line)) {
            i++
            continue
        }

        // 1. Fenced code block (``` or ~~~)
        val fenceMatch = FENCE_OPEN_RE.matchEntire(line)
        if (fenceMatch != null) {
            val fence = fenceMatch.groupValues[1]
            val fenceChar = fence[0]
            val fenceLen = fence.length
            val lang = fenceMatch.groupValues[2].trim().takeIf { it.isNotEmpty() }
            val rawBuf = ArrayList<String>()
            val codeBuf = ArrayList<String>()
            rawBuf += line
            i++
            while (i < lines.size) {
                val cur = lines[i]
                rawBuf += cur
                val trimmed = cur.trim()
                val isClose = trimmed.length >= fenceLen && trimmed.all { it == fenceChar }
                i++
                if (isClose) break
                codeBuf += cur
            }
            emit(
                kind = SelectableBlockKind.Code,
                label = lang,
                raw = rawBuf.joinToString("\n"),
                plain = codeBuf.joinToString("\n"),
            )
            continue
        }

        // 2. ATX Heading (# ... ######)
        val headingMatch = ATX_HEADING_RE.matchEntire(line)
        if (headingMatch != null) {
            val headingPlain = stripInlineMarkdown(headingMatch.groupValues[1].trim())
            emit(
                kind = SelectableBlockKind.Heading,
                label = null,
                raw = line.trim(),
                plain = headingPlain,
            )
            i++
            continue
        }

        // 3. Blockquote (> ...)
        if (line.trimStart().startsWith(">")) {
            val rawBuf = ArrayList<String>()
            val plainBuf = ArrayList<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val cur = lines[i]
                rawBuf += cur
                val stripped = cur.trimStart().removePrefix(">").removePrefix(" ")
                plainBuf += stripInlineMarkdown(stripped)
                i++
            }
            emit(
                kind = SelectableBlockKind.Quote,
                label = null,
                raw = rawBuf.joinToString("\n"),
                plain = plainBuf.joinToString("\n"),
            )
            continue
        }

        // 4. Bullet / numbered list group
        if (LIST_LINE_RE.matches(line)) {
            val rawBuf = ArrayList<String>()
            val plainBuf = ArrayList<String>()
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.isBlank()) break
                if (FENCE_OPEN_RE.matches(cur) || ATX_HEADING_RE.matches(cur) || cur.trimStart().startsWith(">")) break
                val isListOrContinuation = LIST_LINE_RE.matches(cur) || cur.startsWith("  ") || cur.startsWith("\t")
                if (!isListOrContinuation) break
                rawBuf += cur
                plainBuf += stripInlineMarkdown(cur)
                i++
            }
            emit(
                kind = SelectableBlockKind.List,
                label = null,
                raw = rawBuf.joinToString("\n"),
                plain = plainBuf.joinToString("\n"),
            )
            continue
        }

        // 5. Regular paragraph until blank line or next structured block
        val rawBuf = ArrayList<String>()
        val plainBuf = ArrayList<String>()
        while (i < lines.size) {
            val cur = lines[i]
            if (cur.isBlank() || HORIZONTAL_RULE_RE.matches(cur)) break
            if (rawBuf.isNotEmpty() && (
                    FENCE_OPEN_RE.matches(cur) ||
                        ATX_HEADING_RE.matches(cur) ||
                        cur.trimStart().startsWith(">") ||
                        LIST_LINE_RE.matches(cur)
                    )
            ) {
                break
            }
            rawBuf += cur
            plainBuf += stripInlineMarkdown(cur)
            i++
        }
        emit(
            kind = SelectableBlockKind.Paragraph,
            label = null,
            raw = rawBuf.joinToString("\n"),
            plain = plainBuf.joinToString("\n"),
        )
    }

    return blocks
}

/**
 * Strips Markdown delimiters into clean human-readable plain text while leaving
 * fenced code contents verbatim.
 */
internal fun stripMarkdownForPlainCopy(source: String): String {
    val blocks = splitSelectableBlocks(source)
    if (blocks.isEmpty()) return source.trim()
    return blocks.joinToString("\n\n") { it.plainText }
}

internal fun hasMarkdownFormatting(source: String): Boolean {
    val trimmed = source.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (trimmed.isEmpty()) return false
    return stripMarkdownForPlainCopy(trimmed).trim() != trimmed
}

/**
 * Dedicated full-screen text selection & copy studio launched from a message's long-press sheet.
 *
 * Replaces the old cramped `SelectionContainer` inside `ModalBottomSheet` (which suffered from
 * tiny height limits, nested scroll fights, and swipe-to-dismiss stealing drag handles) with:
 *  - Full-screen edge-to-edge layout with zero bottom-sheet gesture interference;
 *  - **Free select** (`自由划选`): read-only `BasicTextField` with controlled selection range,
 *    native word double-tap / drag handles, and live selected char count + bottom action dock;
 *  - **Pick blocks** (`按段点选`): 1-tap paragraph / heading / list / code-block selection cards
 *    with per-card instant copy and ordered multi-block joining;
 *  - Smart **Plain / Raw** toggle whenever the message contains Markdown syntax.
 */
@Composable
internal fun MessageTextSelectDialog(
    role: MessageRole,
    text: String,
    actions: MessageActions?,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    val blocks = remember(text) { splitSelectableBlocks(text) }
    val hasMarkdown = remember(text) { hasMarkdownFormatting(text) }
    val plainFullText = remember(text) { stripMarkdownForPlainCopy(text) }

    // 0 = Free select (自由划选), 1 = Pick blocks (按段点选)
    var selectTab by remember { mutableIntStateOf(0) }
    var plainMode by remember { mutableStateOf(false) }

    val displayText = if (plainMode && hasMarkdown) plainFullText else text
    var tfv by remember(displayText) { mutableStateOf(TextFieldValue(displayText)) }
    val freeFocusRequester = remember { FocusRequester() }
    var selectedBlockIndices by remember(text) { mutableStateOf<Set<Int>>(emptySet()) }

    val selStart = tfv.selection.min.coerceIn(0, displayText.length)
    val selEnd = tfv.selection.max.coerceIn(selStart, displayText.length)
    val freeSelectedText = if (selEnd > selStart) displayText.substring(selStart, selEnd) else ""

    val orderedSelectedBlocks = remember(blocks, selectedBlockIndices, plainMode) {
        blocks.filter { it.index in selectedBlockIndices }
    }
    val blocksSelectedText = remember(orderedSelectedBlocks, plainMode) {
        orderedSelectedBlocks.joinToString("\n\n") { block ->
            if (plainMode) block.plainText else block.rawText
        }
    }

    val activeSelectionText = if (selectTab == 0) freeSelectedText else blocksSelectedText
    val hasActiveSelection = activeSelectionText.isNotEmpty()
    val isAllSelected = if (selectTab == 0) {
        displayText.isNotEmpty() && selStart == 0 && selEnd == displayText.length
    } else {
        blocks.isNotEmpty() && selectedBlockIndices.size == blocks.size
    }

    fun copyAndClose(content: String) {
        if (content.isEmpty()) return
        clipboard.setText(AnnotatedString(content))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onDismiss()
    }

    fun quoteAndClose(content: String) {
        if (content.isEmpty() || actions == null) return
        actions.quote(quoted(content))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                PiTopBar(
                    title = tr("选择复制", "Select & copy"),
                    subtitle = role.title,
                    onBack = onDismiss,
                    navigationIcon = Icons.Filled.Close,
                    navigationContentDescription = tr("关闭", "Close"),
                    scrolled = true,
                    actions = if (hasMarkdown) {
                        {
                            val chipShape = RoundedCornerShape(999.dp)
                            val activeBg = if (plainMode) palette.accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh
                            val activeFg = if (plainMode) palette.accent else palette.muted
                            Text(
                                text = if (plainMode) tr("纯文本", "Plain") else tr("原文", "Raw"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = activeFg,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(chipShape)
                                    .background(activeBg)
                                    .border(
                                        PiSpacing.hairlineThin,
                                        if (plainMode) palette.accent.copy(alpha = 0.45f) else palette.borderMuted.copy(alpha = 0.5f),
                                        chipShape,
                                    )
                                    .clickable(role = Role.Button) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        plainMode = !plainMode
                                    }
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        null
                    },
                )

                // 模式切换条：自由划选 / 按段点选
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSpacing.pageHorizontal, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PiSeg(
                        options = listOf(
                            tr("自由划选", "Free select"),
                            tr("按段点选", "Pick blocks"),
                        ),
                        selectedIndex = selectTab,
                        onSelect = { selectTab = it },
                        disabledIndices = if (blocks.isEmpty()) setOf(1) else emptySet(),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (isAllSelected) tr("取消全选", "Clear") else tr("全选", "Select all"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (selectTab == 0) {
                                    if (isAllSelected) {
                                        tfv = tfv.copy(selection = TextRange(0))
                                    } else {
                                        tfv = tfv.copy(selection = TextRange(0, displayText.length))
                                        runCatching { freeFocusRequester.requestFocus() }
                                    }
                                } else {
                                    selectedBlockIndices = if (isAllSelected) {
                                        emptySet()
                                    } else {
                                        blocks.mapTo(LinkedHashSet()) { it.index }
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }

                // 主阅读 / 选区画布（全屏单层滚动，彻底告别弹层抢手势与嵌套滚动卡顿）
                if (selectTab == 0) {
                    val freeScroll = rememberScrollState()
                    val selectionColors = remember(palette.accent) {
                        TextSelectionColors(
                            handleColor = palette.accent,
                            backgroundColor = palette.accent.copy(alpha = 0.24f),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(freeScroll)
                            .padding(horizontal = PiSpacing.pageHorizontal)
                            .padding(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!hasActiveSelection) {
                            Text(
                                text = tr(
                                    "双击或长按任意词语即可拖动水滴选区；若要整段或代码块复制，点上方「按段点选」更省事。",
                                    "Double-tap or long-press any word to drag selection handles, or switch to Pick blocks for 1-tap paragraphs & code.",
                                ),
                                style = PiTheme.text.meta,
                                color = palette.muted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                            BasicTextField(
                                value = tfv,
                                onValueChange = { next ->
                                    if (next.text == displayText) {
                                        tfv = next
                                    }
                                },
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(freeFocusRequester),
                                textStyle = PiTheme.text.prose.copy(
                                    fontFamily = PiTheme.typography.proseFontFamily(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(Color.Transparent),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = PiSpacing.pageHorizontal,
                            end = PiSpacing.pageHorizontal,
                            top = 2.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(blocks, key = { it.index }) { block ->
                            val selected = block.index in selectedBlockIndices
                            val blockText = if (plainMode) block.plainText else block.rawText
                            val quickCopyText = if (block.kind == SelectableBlockKind.Code && !plainMode) {
                                block.plainText
                            } else {
                                blockText
                            }
                            SelectableBlockCard(
                                block = block,
                                displayText = blockText,
                                selected = selected,
                                onToggle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedBlockIndices = if (selected) {
                                        selectedBlockIndices - block.index
                                    } else {
                                        selectedBlockIndices + block.index
                                    }
                                },
                                onQuickCopy = {
                                    copyAndClose(quickCopyText)
                                },
                            )
                        }
                    }
                }

                // 吸底操作栏：实时字数/段数反馈 + 引用 + 复制所选 / 复制全部
                HorizontalDivider(
                    thickness = PiSpacing.hairlineThin,
                    color = palette.borderMuted.copy(alpha = 0.55f),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = PiSpacing.pageHorizontal, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val statusLine = when {
                        selectTab == 0 && hasActiveSelection ->
                            tr("已选 ${freeSelectedText.length} 字", "${freeSelectedText.length} chars selected")
                        selectTab == 0 ->
                            tr("共 ${displayText.length} 字 · 未选区时可直接复制全部", "${displayText.length} chars · copies all if none selected")
                        selectedBlockIndices.isNotEmpty() ->
                            tr(
                                "已选 ${selectedBlockIndices.size} / ${blocks.size} 段 · ${blocksSelectedText.length} 字",
                                "${selectedBlockIndices.size} / ${blocks.size} blocks · ${blocksSelectedText.length} chars",
                            )
                        else ->
                            tr("轻点卡片勾选段落或代码块，也可点右上角单段复制", "Tap cards to pick blocks, or tap copy on any single card")
                    }
                    Text(
                        text = statusLine,
                        style = PiTheme.text.meta,
                        color = if (hasActiveSelection) palette.accent else palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val effectiveContent = if (hasActiveSelection) activeSelectionText else displayText
                        if (actions != null) {
                            SelectDockButton(
                                icon = Icons.Filled.FormatQuote,
                                label = if (hasActiveSelection) tr("引用所选", "Quote selected") else tr("引用全部", "Quote all"),
                                primary = false,
                                modifier = Modifier.weight(1f),
                                onClick = { quoteAndClose(effectiveContent) },
                            )
                        }
                        SelectDockButton(
                            icon = if (hasActiveSelection) Icons.Filled.ContentCopy else Icons.Filled.SelectAll,
                            label = if (hasActiveSelection) tr("复制所选", "Copy selected") else tr("复制全部", "Copy all"),
                            primary = true,
                            modifier = Modifier.weight(1.25f),
                            onClick = { copyAndClose(effectiveContent) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableBlockCard(
    block: SelectableBlock,
    displayText: String,
    selected: Boolean,
    onToggle: () -> Unit,
    onQuickCopy: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(14.dp)
    val bg = if (selected) palette.accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLow
    val borderColor = if (selected) palette.accent else palette.borderMuted.copy(alpha = 0.45f)
    val borderWidth = if (selected) 1.5.dp else PiSpacing.hairlineThin

    val kindLabel = when (block.kind) {
        SelectableBlockKind.Code -> {
            val lang = block.label?.takeIf { it.isNotBlank() }
            if (lang != null) tr("代码块 · $lang", "Code · $lang") else tr("代码块", "Code")
        }
        SelectableBlockKind.Heading -> tr("标题", "Heading")
        SelectableBlockKind.Quote -> tr("引用", "Quote")
        SelectableBlockKind.List -> tr("列表", "List")
        SelectableBlockKind.Paragraph -> tr("段落 ${block.index + 1}", "Block ${block.index + 1}")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) palette.accent else Color.Transparent)
                    .border(
                        width = if (selected) 0.dp else 1.5.dp,
                        color = if (selected) Color.Transparent else palette.borderMuted,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = palette.contrastOn(palette.accent),
                    )
                }
            }
            Text(
                text = kindLabel,
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                color = if (selected) palette.accent else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(role = Role.Button, onClick = onQuickCopy)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = tr("复制此段", "Copy block"),
                    modifier = Modifier.size(13.dp),
                    tint = palette.muted,
                )
                Text(
                    text = if (block.kind == SelectableBlockKind.Code) tr("复制代码", "Copy code") else tr("复制", "Copy"),
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }

        if (block.kind == SelectableBlockKind.Code) {
            Text(
                text = block.plainText,
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        } else {
            val baseStyle = PiTheme.text.prose.copy(
                fontFamily = PiTheme.typography.proseFontFamily(),
                fontWeight = if (block.kind == SelectableBlockKind.Heading) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = displayText,
                style = baseStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SelectDockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val bg = if (primary) palette.accent else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (primary) palette.contrastOn(palette.accent) else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = if (primary) null else BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = fg,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
