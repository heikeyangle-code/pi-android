package app.pi.ui.screens

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.AssistantText
import app.pi.protocol.BranchSummary
import app.pi.protocol.CompactionMarker
import app.pi.protocol.DateSeparator
import app.pi.protocol.ErrorText
import app.pi.protocol.HookMessage
import app.pi.protocol.ModelChange
import app.pi.protocol.Notice
import app.pi.protocol.SkillInvocation
import app.pi.protocol.ThinkingBlock
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolDiff
import app.pi.protocol.TranscriptItem
import app.pi.protocol.UserMessage
import app.pi.ui.PiSessionViewModel
import app.pi.ui.chat.mergeRestoredQueue
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/** 对话内查找：命中扫描与查找条。从 ChatScreen.kt 拆出。 */


/**
 * The transcript's search results for one query.
 *
 * Two shapes of the same answer, because the two readers want different operations:
 * [ordered] is the navigation order (`cursor / total` and the reveal), and [present] is
 * the per-row membership test the transcript runs once per rendered row per frame.
 *
 * A `List<Int>` served both before this, and the membership half was a linear scan of
 * every match in the session — a session with a common word in it has thousands of them,
 * and the transcript asks per visible row. Both halves are built in one pass over the
 * transcript (see `ChatBody`'s match scan).
 */
internal data class SearchHits(val ordered: List<Int>, val present: Set<Int>) {
    companion object {
        /** A blank query, or one that matched nothing. */
        val None = SearchHits(emptyList(), emptySet())
    }
}


/**
 * Whether [item] is a search result for [query] — the same coverage the old
 * `searchTextOf` had, asked as a predicate instead of built as a string.
 *
 * (`mergeRestoredQueue` — pi's queue/editor merge rule, shared by Stop and the
 * queue row's dequeue — lives in `ui/chat/QueueRestore.kt`: both actions must merge
 * identically, and a pure function is the only form
 * `tools/run-app-pure-checks.sh` can execute, because this file imports Compose.)
 *
 * Every rendered block kind is covered, because a search that silently skips a
 * kind would report "no matches" for text the user can see. Kinds with no text of
 * their own (a date separator) contribute nothing.
 *
 * **Why a predicate and not a string.** The old form joined each row's parts and then
 * ran `contains` on the result, which copied a tool's whole output — the largest thing
 * in a transcript — once per row per scan (measured: 0.86 ms per 20 KB row on a desktop
 * JVM). Where a row's parts were joined with a newline the two forms are *identical*,
 * because the query cannot contain one: the search field is `singleLine`
 * (`ChatScreen.kt`'s `SearchBar`), so a match could never have straddled the separator.
 * The joins that remain are the tiny ones, kept as they were so the behaviour of a `/`
 * or newline inside a query is unchanged rather than accidentally "cleaned up".
 */
internal fun searchHits(item: TranscriptItem, query: String): Boolean = when (item) {
    is UserMessage -> item.text.hits(query)
    is AssistantText -> item.text.hits(query)
    is ThinkingBlock -> item.text.hits(query)
    is ToolCall -> item.toolName.hits(query) || item.argsSummary.hits(query) || item.output.hits(query) || (item.diffText?.hits(query) == true)
    is ToolDiff -> item.path.hits(query) || item.diffText.hits(query)
    is CompactionMarker -> item.summary.hits(query)
    is BranchSummary -> item.summary.hits(query)
    is HookMessage -> item.markdown.hits(query)
    is app.pi.protocol.ExtensionEntry -> false
    is ModelChange -> listOfNotNull(item.provider, item.modelId).joinToString("/").hits(query)
    is SkillInvocation -> listOf(item.skillName, item.body).joinToString("\n").hits(query)
    is ErrorText -> listOfNotNull(item.message, item.detail).joinToString("\n").hits(query)
    is Notice -> item.text.hits(query)
    is DateSeparator -> false
}


/** pi's `searchMatch` is a case-insensitive substring test over the row's text. */
internal fun String.hits(query: String): Boolean = contains(query, ignoreCase = true)


/**
 * One full pass of the search over [items]: the ordered hits and the membership set the
 * transcript reads per rendered row.
 *
 * Extracted so it can be run **off the frame thread** — the caller dispatches it to
 * `Dispatchers.Default` and publishes the result (see `ChatBody`'s scan effect). It is a
 * pure function of its three arguments, over an immutable list, so nothing is read that
 * the dispatcher change could make stale.
 *
 * A block the user asked to hide cannot be a search result: the row is not on screen to
 * scroll to.
 */
internal fun scanSearchHits(
    items: List<TranscriptItem>,
    query: String,
    hideThinking: Boolean,
): SearchHits {
    val ordered = ArrayList<Int>()
    val present = HashSet<Int>()
    items.forEachIndexed { index, item ->
        if (hideThinking && item is ThinkingBlock) return@forEachIndexed
        if (searchHits(item, query)) {
            ordered += index
            present += index
        }
    }
    return SearchHits(ordered, present)
}

/** Search scan over projected [app.pi.ui.chat.activity.ChatRow]s (supports both Classic and Compact Trace). */
internal fun scanChatRowSearchHits(
    rows: List<app.pi.ui.chat.activity.ChatRow>,
    query: String,
    hideThinking: Boolean,
): SearchHits {
    val ordered = ArrayList<Int>()
    val present = HashSet<Int>()
    rows.forEachIndexed { index, row ->
        val matched = when (row) {
            is app.pi.ui.chat.activity.ChatRow.Single -> {
                if (hideThinking && row.item is ThinkingBlock) false else searchHits(row.item, query)
            }
            is app.pi.ui.chat.activity.ChatRow.ActivityGroup -> {
                row.items.any { item ->
                    if (hideThinking && item is ThinkingBlock) false else searchHits(item, query)
                }
            }
        }
        if (matched) {
            ordered += index
            present += index
        }
    }
    return SearchHits(ordered, present)
}


/**
 * The transcript's search bar, transcribed from v2's `SearchState` bar
 * (`direction-b-v2.html:2755-2767`, i.e. phone14).
 *
 * v2 draws **one strip** under the AppBar:
 *
 * ```
 * 容器      padding:8px 14px, border-bottom:1px borderMuted, background:surf-dim
 * 输入条    height:36（本页取 40，与设置页搜索框同高，见 SEARCH_BAR_HEIGHT）,
 *           border:1px borderAccent, radius:9, background:surf-low,
 *           padding:0 10px, gap:8
 *   ·       search 字形 15 muted
 *   ·       查询文本 mono t13 text
 *   ·       `3 / 8` mono t12 muted
 *   ·       「关闭查找」 t12 muted
 * 动作行    margin-top:8, gap:8: `↑ 上一个匹配` / `↓ 下一个匹配` chips + 右侧说明 t12 muted
 * ```
 *
 * The app used to draw an `OutlinedTextField` plus three Material `IconButton`s —
 * two controls' worth of chrome, an M3 field outline the board does not have, and two
 * icon buttons whose only label was a content description. The **mechanism** is
 * unchanged and is still pi's: the query is the same string, the two arrows move
 * `searchCursor`, and the count keeps pi's `cursor / total` reading.
 *
 * Hit rows are filled with pi's `searchMatchBg`, and the active match carries a
 * `borderAccent` ring (`ui/screens/ChatScreen.kt`) so it stays clearly legible even on
 * themes whose `searchMatchText` foreground token resolves to low-contrast `muted`.
 *
 * The trailing sentence is v2's, verbatim (`direction-b-v2.html:2766`).
 */
@Composable
internal fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    cursor: Int,
    hasPrompts: Boolean = false,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = PiTheme.palette
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    app.pi.ui.components.PiAutoFocus(focusRequester)
    val promptNavMode = query.isBlank() && hasPrompts
    val countLabel = if (matchCount == 0) {
        if (query.isBlank()) "" else "0 / 0"
    } else {
        "${cursor.coerceIn(0, matchCount - 1) + 1} / $matchCount"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = palette.cardBg.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(
            width = if (query.isNotEmpty()) 1.dp else 0.5.dp,
            color = if (query.isNotEmpty()) palette.border else palette.borderMuted.copy(alpha = 0.60f),
        ),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = palette.muted,
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = if (hasPrompts) {
                                    tr("搜索对话，或点 ↑↓ 跳提问…", "Search chat, or ↑↓ for prompts…")
                                } else {
                                    tr("搜索对话内容…", "Search conversation…")
                                },
                                style = PiTheme.text.prose,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (countLabel.isNotEmpty()) {
                Text(
                    text = countLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (matchCount > 0) palette.accent.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                    color = if (matchCount > 0) palette.accent else palette.muted,
                    maxLines = 1,
                )
            }
            SearchNavButton(
                icon = Icons.Filled.KeyboardArrowUp,
                label = if (promptNavMode) tr("上一条提问", "Previous prompt") else tr("上一个匹配", "Previous match"),
                enabled = matchCount > 0 || promptNavMode,
                onClick = onPrevious,
            )
            SearchNavButton(
                icon = Icons.Filled.KeyboardArrowDown,
                label = if (promptNavMode) tr("下一条提问", "Next prompt") else tr("下一个匹配", "Next match"),
                enabled = matchCount > 0 || promptNavMode,
                onClick = onNext,
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClickLabel = tr("关闭查找", "Close search"), onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = tr("关闭查找", "Close search"),
                    tint = palette.muted,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
            )
            .then(if (enabled) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else palette.dim.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}


/**
 * How often the transcript's search may re-scan while a turn is streaming.
 *
 * The same number, and the same **shape** of argument, as the transcript's tool-output
 * throttle (`protocol/.../Transcript.kt` `TOOL_UPDATE_THROTTLE_MS`): a costly per-token
 * operation is bound to a repaint-sized window instead of being paid per event. It is
 * deliberately **not** that constant though — this number is sized by the scan below
 * (110 ms for 800 rows), not by the tool cadence, so sharing the value must not make one
 * move when the other is retuned. `PiSessionViewModel`'s bash panel is the one that had a
 * second spelling of the tool throttle and now imports it; this is a separate knob.
 *
 * pi publishes once per streamed event, and a search over the whole session is expensive
 * enough (110 ms for 800 rows of realistic size, measured on a desktop JVM) that paying it
 * per token is a frozen screen. A search is a reading of the transcript, not a live feed —
 * a match list that is 200 ms behind the newest text is imperceptible, and the turn's last
 * publication is always scanned because the scan is repeated once the transcript stops
 * streaming.
 *
 * The residual cost is bounded by what the scan has to read, which is the session's own
 * text: the follow-up (if this is ever still felt) is to cache each row's search text in
 * the reducer, not to lengthen this window.
 */
internal const val SEARCH_RESCAN_MS: Long = 200L
