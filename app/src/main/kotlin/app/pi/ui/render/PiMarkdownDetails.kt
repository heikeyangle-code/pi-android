package app.pi.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.blocks.LocalTranscriptRowAnchor
import app.pi.ui.theme.PiContrast
import app.pi.ui.theme.PiTheme
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * Grouped block representation emitted by [groupMarkdownBlocks].
 *
 * CommonMark §4.6 terminates type-6 HTML blocks at the first blank line, which splits standard
 * GitHub Markdown `<details><summary>...</summary>\n\n1. item\n\n</details>` into an empty
 * unclosed `<details>` HTML block, top-level Markdown blocks, and an orphan `</details>` block.
 * Grouping siblings reassembles `<details>` containers (including arbitrary nesting and full GFM
 * Markdown bodies) while quietly dropping inert structural wrapper tags (`</div>`, `</details>`,
 * `<br>`, etc.).
 */
internal sealed interface PiGroupedBlock {
    val stableKey: Int

    data class Single(
        val node: ASTNode,
        override val stableKey: Int,
    ) : PiGroupedBlock

    data class RawHtml(
        val html: String,
        override val stableKey: Int,
    ) : PiGroupedBlock

    data class Details(
        val open: Boolean,
        val summary: String?,
        val leadingHtml: String?,
        val bodyNodes: List<ASTNode>,
        val bodyMarkdown: String?,
        val trailingHtml: String?,
        override val stableKey: Int,
        val leadingMarkdown: String? = null,
    ) : PiGroupedBlock {
        val hasBody: Boolean
            get() = !leadingMarkdown.isNullOrBlank() ||
                !leadingHtml.isNullOrBlank() ||
                bodyNodes.isNotEmpty() ||
                !bodyMarkdown.isNullOrBlank() ||
                !trailingHtml.isNullOrBlank()
    }
}

private val DETAILS_TAG_REGEX = Regex("""<(/?)\s*details(?:\s+[^>]*)?\s*/?>""", RegexOption.IGNORE_CASE)
private val DETAILS_OPEN_ATTR_REGEX = Regex("""\bopen(?:\s*=|\s|>|/)""", RegexOption.IGNORE_CASE)
private val SUMMARY_BLOCK_REGEX = Regex("""<summary(?:\s+[^>]*)?>([\s\S]*?)</summary\s*>""", RegexOption.IGNORE_CASE)
private val HTML_TAG_TOKEN_REGEX = Regex("""<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9:-]*)(?:\s+[^>]*)?\s*/?>""")
private val BLOCK_HTML_TAG_REGEX = Regex(
    """<\s*/?\s*(?:p|div|ul|ol|li|table|thead|tbody|tr|td|th|h[1-6]|pre|blockquote|figure|figcaption|section|article|aside|details|summary|hr|img|svg|dl|dt|dd)\b""",
    RegexOption.IGNORE_CASE,
)

private val INERT_WRAPPER_TAGS = setOf(
    "div", "span", "p", "section", "article", "aside",
    "header", "footer", "main", "nav", "center",
    "figure", "figcaption", "blockquote", "br",
)

// Closing `</details>` / `</summary>` with no surrounding content are stray wrappers;
// opening `<details>` / `<summary>` are NEVER inert because they open a collapsible card.
private val INERT_CLOSING_ONLY_TAGS = setOf("details", "summary")

private val SUMMARY_STRONG_OPEN_REGEX = Regex("""<\s*(?:strong|b)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val SUMMARY_STRONG_CLOSE_REGEX = Regex("""</\s*(?:strong|b)\s*>""", RegexOption.IGNORE_CASE)
private val SUMMARY_EM_OPEN_REGEX = Regex("""<\s*(?:em|i)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val SUMMARY_EM_CLOSE_REGEX = Regex("""</\s*(?:em|i)\s*>""", RegexOption.IGNORE_CASE)
private val SUMMARY_CODE_OPEN_REGEX = Regex("""<\s*code\b[^>]*>""", RegexOption.IGNORE_CASE)
private val SUMMARY_CODE_CLOSE_REGEX = Regex("""</\s*code\s*>""", RegexOption.IGNORE_CASE)
private val SUMMARY_STRIP_TAG_REGEX = Regex("""<[^>]+>""")
private val SUMMARY_WHITESPACE_REGEX = Regex("""\s+""")

private val DETAILS_CARD_SHAPE = RoundedCornerShape(12.dp)
private val DETAILS_CHEVRON_SHAPE = RoundedCornerShape(6.dp)

private val LEADING_DISCLOSURE_GLYPHS = setOf(
    '▶', '▸', '►', '▷', '▻', '▼', '▾', '▽', '❯', '›', '»', '‣',
)

/**
 * Strips redundant author-written disclosure triangles (`▶ `, `▸ `, `▼ `) from a `<summary>` label
 * because [PiDetailsCardShell] already renders a native vector chevron. Keeps the original text if
 * stripping would leave nothing visible.
 */
internal fun stripLeadingDisclosurePrefix(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    var i = 0
    var stripped = false
    while (i < trimmed.length) {
        val ch = trimmed[i]
        when {
            ch in LEADING_DISCLOSURE_GLYPHS -> {
                stripped = true
                i++
                while (i < trimmed.length && (trimmed[i] == '\uFE0E' || trimmed[i] == '\uFE0F')) i++
                while (i < trimmed.length && trimmed[i].isWhitespace()) i++
            }
            ch.isWhitespace() -> i++
            else -> break
        }
    }
    if (!stripped) return trimmed
    val rest = trimmed.substring(i).trim()
    return rest.ifEmpty { trimmed }
}

/**
 * True when a bare Markdown `HTML_BLOCK` contains ONLY comments, whitespace, and benign structural
 * open/close wrapper tags (e.g. `</details>`, `</div>`, `<div align="center">`, `<br>`), with zero
 * visible text and zero unsafe/media tags.
 */
internal fun isInertHtmlWrapperBlock(raw: String): Boolean {
    val cleaned = PiHtml.stripHtmlComments(raw).trim()
    if (cleaned.isEmpty()) return true
    var pos = 0
    var sawTag = false
    for (match in HTML_TAG_TOKEN_REGEX.findAll(cleaned)) {
        if (cleaned.substring(pos, match.range.first).isNotBlank()) return false
        val isClosing = match.groupValues[1].isNotEmpty()
        val tagName = match.groupValues[2].lowercase()
        val allowed = tagName in INERT_WRAPPER_TAGS || (isClosing && tagName in INERT_CLOSING_ONLY_TAGS)
        if (!allowed) return false
        sawTag = true
        pos = match.range.last + 1
    }
    if (pos < cleaned.length && cleaned.substring(pos).isNotBlank()) return false
    return sawTag
}

/**
 * Groups sibling AST block nodes, reassembling multi-block `<details>...</details>` containers
 * (and single-block `<details>` with plain Markdown bodies) while dropping inert wrapper-only HTML
 * blocks like stray `</details>` or `</div>`.
 */
internal fun groupMarkdownBlocks(
    content: String,
    children: List<ASTNode>,
    stripQuotePrefix: Boolean = false,
): List<PiGroupedBlock> {
    val visible = ArrayList<ASTNode>(children.size)
    for (child in children) {
        when (child.type) {
            MarkdownTokenTypes.EOL,
            MarkdownTokenTypes.WHITE_SPACE,
            MarkdownElementTypes.LINK_DEFINITION,
            MarkdownTokenTypes.BLOCK_QUOTE -> continue
            else -> visible.add(child)
        }
    }
    if (visible.isEmpty()) return emptyList()

    val out = ArrayList<PiGroupedBlock>(visible.size)
    var i = 0
    var pendingHtml: String? = null
    var pendingOffset = 0
    while (pendingHtml != null || i < visible.size) {
        val currentNode: ASTNode?
        val rawHtml: String
        val baseOffset: Int
        val nextIndex: Int

        if (pendingHtml != null) {
            currentNode = null
            rawHtml = pendingHtml
            baseOffset = pendingOffset
            pendingHtml = null
            nextIndex = i
        } else {
            val node = visible[i]
            if (node.type != MarkdownElementTypes.HTML_BLOCK) {
                out.add(PiGroupedBlock.Single(node, stableKey = node.startOffset))
                i++
                continue
            }
            currentNode = node
            rawHtml = extractHtmlBlockText(content, node, stripQuotePrefix)
            baseOffset = node.startOffset
            nextIndex = i + 1
        }

        if (rawHtml.isEmpty()) {
            i = nextIndex
            continue
        }

        val tags = DETAILS_TAG_REGEX.findAll(rawHtml).toList()
        if (tags.isEmpty()) {
            if (!isInertHtmlWrapperBlock(rawHtml)) {
                if (currentNode != null) {
                    out.add(PiGroupedBlock.Single(currentNode, stableKey = baseOffset))
                } else {
                    out.add(PiGroupedBlock.RawHtml(rawHtml, stableKey = baseOffset))
                }
            }
            i = nextIndex
            continue
        }

        val firstOpenIdx = tags.indexOfFirst { it.groupValues[1].isEmpty() }
        var matchedCloseIdx = -1
        var unclosedDepth = 0
        if (firstOpenIdx >= 0) {
            var depth = 0
            for (tIdx in firstOpenIdx until tags.size) {
                if (tags[tIdx].groupValues[1].isEmpty()) {
                    depth++
                } else {
                    depth--
                    if (depth == 0) {
                        matchedCloseIdx = tIdx
                        break
                    }
                }
            }
            unclosedDepth = depth
        }

        // First top-level `<details>...</details>` closes within this HTML block (including
        // consecutive single-block `<details>` OR a closed `<details>` immediately followed by an
        // unclosed multi-block `<details>` without a blank line): peel off the closed pair and
        // feed any remaining trailing HTML into the next loop iteration via `pendingHtml`.
        if (firstOpenIdx >= 0 && matchedCloseIdx > firstOpenIdx) {
            val openTag = tags[firstOpenIdx]
            val closeTag = tags[matchedCloseIdx]
            val before = rawHtml.substring(0, openTag.range.first).trim()
            val inner = rawHtml.substring(openTag.range.last + 1, closeTag.range.first)
            val after = rawHtml.substring(closeTag.range.last + 1).trim()
            val parsedInner = parseDetailsHeaderAndRest(openTag.value, inner)
            val bodyCandidate = parsedInner.rest.trim()
            val hasNestedDetailsInInner = matchedCloseIdx > firstOpenIdx + 1
            val useNativeMarkdown = !hasNestedDetailsInInner &&
                (bodyCandidate.isEmpty() || !BLOCK_HTML_TAG_REGEX.containsMatchIn(bodyCandidate))

            if (useNativeMarkdown || after.isNotEmpty() || before.isNotEmpty()) {
                if (before.isNotEmpty() && !isInertHtmlWrapperBlock(before)) {
                    out.add(PiGroupedBlock.RawHtml(before, stableKey = baseOffset))
                }
                if (useNativeMarkdown) {
                    out.add(
                        PiGroupedBlock.Details(
                            open = parsedInner.open,
                            summary = parsedInner.summary,
                            leadingHtml = null,
                            bodyNodes = emptyList(),
                            bodyMarkdown = bodyCandidate.ifEmpty { null },
                            trailingHtml = null,
                            stableKey = baseOffset + openTag.range.first,
                        ),
                    )
                } else {
                    val detailsSlice = rawHtml.substring(openTag.range.first, closeTag.range.last + 1).trim()
                    if (detailsSlice.isNotEmpty()) {
                        out.add(PiGroupedBlock.RawHtml(detailsSlice, stableKey = baseOffset + openTag.range.first))
                    }
                }
                if (after.isNotEmpty() && !isInertHtmlWrapperBlock(after)) {
                    if (DETAILS_TAG_REGEX.containsMatchIn(after)) {
                        pendingHtml = after
                        pendingOffset = baseOffset + closeTag.range.last + 1
                    } else {
                        out.add(PiGroupedBlock.RawHtml(after, stableKey = baseOffset + closeTag.range.last + 1))
                    }
                }
                i = nextIndex
                continue
            }
            if (currentNode != null) {
                out.add(PiGroupedBlock.Single(currentNode, stableKey = baseOffset))
            } else {
                out.add(PiGroupedBlock.RawHtml(rawHtml, stableKey = baseOffset))
            }
            i = nextIndex
            continue
        }

        // Unclosed `<details>` opening block (split from its body & `</details>` by blank lines):
        if (firstOpenIdx >= 0 && unclosedDepth > 0) {
            val firstOpen = tags[firstOpenIdx]
            val before = rawHtml.substring(0, firstOpen.range.first).trim()
            if (before.isNotEmpty() && !isInertHtmlWrapperBlock(before)) {
                out.add(PiGroupedBlock.RawHtml(before, stableKey = baseOffset))
            }

            val afterOpenTag = rawHtml.substring(firstOpen.range.last + 1)
            val header = parseDetailsHeaderAndRest(firstOpen.value, afterOpenTag)
            var summary = header.summary
            val leadCandidate = header.rest.trim().takeIf { it.isNotEmpty() && !isInertHtmlWrapperBlock(it) }
            val leadingMarkdown = leadCandidate?.takeIf { !HTML_TAG_TOKEN_REGEX.containsMatchIn(it) }
            val leadingHtml = if (leadingMarkdown == null) leadCandidate else null
            val bodyNodes = ArrayList<ASTNode>()
            var trailingHtml: String? = null
            var afterCloseHtml: String? = null
            var afterCloseOffset = baseOffset + rawHtml.length

            var depth = unclosedDepth
            var j = nextIndex
            while (j < visible.size && depth > 0) {
                val sibling = visible[j]
                if (sibling.type == MarkdownElementTypes.HTML_BLOCK) {
                    val sibHtml = extractHtmlBlockText(content, sibling, stripQuotePrefix)
                    // Standalone `<summary>...</summary>` block right after `<details>` + blank line.
                    if (summary == null && leadingHtml == null && leadingMarkdown == null && bodyNodes.isEmpty()) {
                        val standaloneSummary = SUMMARY_BLOCK_REGEX.matchEntire(sibHtml.trim())
                        if (standaloneSummary != null) {
                            summary = decodeSummaryText(standaloneSummary.groupValues[1])
                            j++
                            continue
                        }
                    }

                    val sibTags = DETAILS_TAG_REGEX.findAll(sibHtml).toList()
                    var matchedClose: MatchResult? = null
                    var runningDepth = depth
                    for (t in sibTags) {
                        if (t.groupValues[1].isEmpty()) {
                            runningDepth++
                        } else {
                            runningDepth--
                            if (runningDepth == 0) {
                                matchedClose = t
                                break
                            }
                        }
                    }
                    if (matchedClose != null) {
                        val beforeClose = sibHtml.substring(0, matchedClose.range.first).trim()
                        if (beforeClose.isNotEmpty() && !isInertHtmlWrapperBlock(beforeClose)) {
                            trailingHtml = beforeClose
                        }
                        val afterClose = sibHtml.substring(matchedClose.range.last + 1).trim()
                        if (afterClose.isNotEmpty() && !isInertHtmlWrapperBlock(afterClose)) {
                            afterCloseHtml = afterClose
                            afterCloseOffset = sibling.startOffset + matchedClose.range.last + 1
                        }
                        depth = 0
                        j++
                        break
                    } else {
                        depth = runningDepth
                        if (sibTags.isNotEmpty() || !isInertHtmlWrapperBlock(sibHtml)) {
                            bodyNodes.add(sibling)
                        }
                        j++
                    }
                } else {
                    bodyNodes.add(sibling)
                    j++
                }
            }

            out.add(
                PiGroupedBlock.Details(
                    open = header.open,
                    summary = summary,
                    leadingHtml = leadingHtml,
                    bodyNodes = bodyNodes,
                    bodyMarkdown = null,
                    trailingHtml = trailingHtml,
                    stableKey = baseOffset + firstOpen.range.first,
                    leadingMarkdown = leadingMarkdown,
                ),
            )
            if (afterCloseHtml != null) {
                if (DETAILS_TAG_REGEX.containsMatchIn(afterCloseHtml)) {
                    pendingHtml = afterCloseHtml
                    pendingOffset = afterCloseOffset
                } else {
                    out.add(PiGroupedBlock.RawHtml(afterCloseHtml, stableKey = afterCloseOffset))
                }
            }
            i = j
            continue
        }

        // Unmatched closing `</details>` with other non-inert text attached: strip the stray closing tag.
        val strippedOrphan = DETAILS_TAG_REGEX.replace(rawHtml, "").trim()
        if (strippedOrphan.isNotEmpty() && !isInertHtmlWrapperBlock(strippedOrphan)) {
            out.add(PiGroupedBlock.RawHtml(strippedOrphan, stableKey = baseOffset))
        }
        i = nextIndex
    }
    return out
}

private data class ParsedDetailsHeader(
    val open: Boolean,
    val summary: String?,
    val rest: String,
)

private fun parseDetailsHeaderAndRest(openTag: String, afterOpenTag: String): ParsedDetailsHeader {
    val open = DETAILS_OPEN_ATTR_REGEX.containsMatchIn(openTag)
    val summaryMatch = SUMMARY_BLOCK_REGEX.find(afterOpenTag)
    if (summaryMatch != null && afterOpenTag.substring(0, summaryMatch.range.first).isBlank()) {
        val summary = decodeSummaryText(summaryMatch.groupValues[1])
        val rest = afterOpenTag.substring(summaryMatch.range.last + 1)
        return ParsedDetailsHeader(open = open, summary = summary, rest = rest)
    }
    return ParsedDetailsHeader(open = open, summary = null, rest = afterOpenTag)
}

private fun decodeSummaryText(rawInner: String): String {
    val noComments = PiHtml.stripHtmlComments(rawInner)
    // Convert common inline HTML emphasis tags to Markdown equivalents so summary stays rich & crisp.
    val mdFriendly = noComments
        .replace(SUMMARY_STRONG_OPEN_REGEX, "**")
        .replace(SUMMARY_STRONG_CLOSE_REGEX, "**")
        .replace(SUMMARY_EM_OPEN_REGEX, "*")
        .replace(SUMMARY_EM_CLOSE_REGEX, "*")
        .replace(SUMMARY_CODE_OPEN_REGEX, "`")
        .replace(SUMMARY_CODE_CLOSE_REGEX, "`")
        .replace(SUMMARY_STRIP_TAG_REGEX, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(SUMMARY_WHITESPACE_REGEX, " ")
        .trim()
    return stripLeadingDisclosurePrefix(mdFriendly)
}

private fun extractHtmlBlockText(content: String, node: ASTNode, stripQuotePrefix: Boolean): String {
    val raw = PiHtml.blockTextAt(content, node.startOffset, node.endOffset)
    if (!stripQuotePrefix || !raw.contains('>')) return raw
    return raw.lineSequence().joinToString("\n") { line ->
        var idx = 0
        while (idx < line.length && line[idx] == ' ') idx++
        if (idx < line.length && line[idx] == '>') {
            idx++
            if (idx < line.length && line[idx] == ' ') idx++
            line.substring(idx)
        } else {
            line
        }
    }.trim()
}

/**
 * Shared flagship collapsible `<details>` card chrome used by both Markdown-body `<details>`
 * ([PiMarkdownComponents]) and pure HTML `<details>` ([PiHtmlRenderer]):
 *  - Calm `12.dp` hairline container matching Pi's neutral Material 3 card language;
 *  - Tactile `22.dp` chevron well (no double arrows when authors include `▶`/`▸`);
 *  - Haptic feedback + [LocalTranscriptRowAnchor.onBeforeNestedToggle] so toggling never jumps scroll;
 *  - Zero `animateContentSize` inside `LazyColumn` (strict scroll-stability compliance).
 */
@Composable
internal fun PiDetailsCardShell(
    saveKey: Any,
    initialOpen: Boolean,
    hasBody: Boolean,
    modifier: Modifier = Modifier,
    summaryContent: @Composable (expanded: Boolean) -> Unit,
    bodyContent: @Composable () -> Unit,
) {
    val palette = PiTheme.palette
    val darkTheme = PiContrast.luminance(palette.pageBg) < 0.5
    val haptic = LocalHapticFeedback.current
    val rowAnchor = LocalTranscriptRowAnchor.current
    val shape = DETAILS_CARD_SHAPE
    val chevronShape = DETAILS_CHEVRON_SHAPE

    var expanded by rememberSaveable(saveKey) { mutableStateOf(initialOpen) }

    val cardBg = remember(palette, darkTheme) {
        lerp(palette.cardBg, palette.pageBg, if (darkTheme) 0.14f else 0.18f)
    }
    val headerExpandedBg = remember(palette, darkTheme) {
        lerp(palette.cardBg, palette.text, if (darkTheme) 0.048f else 0.032f)
    }
    val borderColor = palette.borderMuted.copy(alpha = if (darkTheme) 0.46f else 0.54f)
    val dividerColor = palette.borderMuted.copy(alpha = if (darkTheme) 0.34f else 0.38f)
    val chevronWellBg = palette.text.copy(
        alpha = when {
            !hasBody -> 0.03f
            expanded -> if (darkTheme) 0.11f else 0.075f
            else -> if (darkTheme) 0.07f else 0.048f
        },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBg)
            .border(0.5.dp, borderColor, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(if (expanded && hasBody) headerExpandedBg else Color.Transparent)
                .then(
                    if (hasBody) {
                        Modifier.clickable(
                            onClickLabel = if (expanded) tr("收起详情", "Collapse details") else tr("展开详情", "Expand details"),
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val next = !expanded
                            rowAnchor?.onBeforeNestedToggle()
                            expanded = next
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(chevronShape)
                    .background(chevronWellBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (expanded && hasBody) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = if (expanded && hasBody) palette.text else palette.muted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.size(9.dp))
            Box(modifier = Modifier.weight(1f)) {
                summaryContent(expanded && hasBody)
            }
        }
        if (expanded && hasBody) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(dividerColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                bodyContent()
            }
        }
    }
}
