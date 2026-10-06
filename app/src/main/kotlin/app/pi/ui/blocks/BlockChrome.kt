package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared chrome for the 14 conversation blocks (docs/pi-android-ui-spec.md §7.4).
 *
 * The page margin and the block rhythm belong to the `LazyColumn` that renders
 * the stream (spec §7.4: `assistant-text` 左右内边距 0): it supplies both the
 * horizontal content padding and the vertical arrangement, so a block that
 * padded itself as well doubled the margin (F11 in `docs/rendering-review.md`).
 * Colour always comes from [PiTheme.palette].
 *
 * v2's rhythm is **8**, not 16 (`06 §2`「块间距 8」, decision D1), and that number
 * lives at the one call site that owns it — the `LazyColumn`'s `spacedBy`
 * (`screens/ChatScreen.kt`). [BlockColumn]'s own `Arrangement` is the *inside* of
 * one block and stays on `PiSpacing.gutter` (6dp), exactly as spec §7.4 has it.
 */

/** The wrapper every block uses. Margins come from the list, not from here (F11). */
@Composable
internal fun BlockColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** Machine output: always monospace (design rule 7 — "two voices"). */
@Composable
internal fun MonoText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = PiTheme.text.mono,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Human prose: always the system font, at v2's chat-text step (14/23). */
@Composable
internal fun ProseText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        // M3's `bodyLarge` is 15 sp — which is v2's *row-title* step, not its chat-text
        // step (`06 §2` 字号 5 档: 14 = 正文、聊天文本、按钮). [app.pi.ui.theme.PiTextStyles]
        // carries the right one.
        style = PiTheme.text.prose,
        color = color ?: MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}


/** One action of a block's long-press menu. */
internal data class BlockAction(val label: String, val onSelect: () -> Unit)

/**
 * Makes everything below it a **system text-selection scope**.
 *
 * ## Why this exists, and why it is here
 *
 * Until this was added the app had no `SelectionContainer` at all
 * (`grep -rn "SelectionContainer" --include=*.kt app/src/main/kotlin` was 0 hits),
 * so *no* text on *any* screen could be selected: a long press anywhere in the
 * transcript opened the block-action menu and nothing else. The user's report was
 * exact — 「长按只能出现一个特别丑的框，是复制全部，没法用系统的自由复制呀」.
 *
 * The gesture cannot be shared. Compose detects selection inside the **text node**
 * (each `BasicText` below the registrar this container provides attaches its own
 * long-press/drag recogniser and consumes the pointer), while the block menu is a
 * `combinedClickable` on the *ancestor* box. Pointer events reach the deepest hit
 * node first, so text wins a long press that lands on text, and the ancestor still
 * wins one that lands on the card's own chrome. That split is not a workaround: it
 * is the split [BlockActionMenu]'s own design note already intended ("text keeps
 * the platform's selection … the *card* … carries the block actions") — it had
 * simply never been wired up.
 *
 * ## The trade-offs, stated rather than discovered later
 *
 *  - **Selection is per container.** Dragging a handle across two *different*
 *    scopes does not extend one selection into the other; the second scope starts
 *    its own. That is why the scope sits at the **block** rather than around each
 *    `Text`: one card is one scope, so a tool card's command line, its body and its
 *    footer can be selected in a single gesture. Cross-*row* selection (from one
 *    transcript item into the next) stays impossible — that is the `LazyColumn`'s
 *    per-item composition limit, and moving the scope would not fix it.
 *  - **A long press on selectable text does not open the block menu.** The two live on
 *    different targets and that is final: text keeps the platform's selection, and the
 *    card's own chrome (padding, rail, margins) keeps the block menu. There used to be a
 *    ⋮ button beside every scoped block so the menu could not be missed; it is gone — see
 *    [BlockActionMenu] for the reason (it cost 32 dp of width on every row, which on a
 *    phone is width the transcript does not have).
 *  - **No nesting.** The scope is applied once per block, at the highest point that
 *    still excludes the other blocks, and [MonoText] / [ProseText] deliberately do
 *    *not* wrap themselves: an inner registrar silently shadows the outer one for
 *    its own node, which would fragment one card into several scopes.
 */
@Composable
internal fun SelectableContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SelectionContainer(modifier = modifier, content = content)
}

/**
 * The card-level actions of `docs/pi-android-ui-spec.md` §4.8.
 *
 * Why a card long press rather than the spec's "long press on any text selects and
 * copies": on Android, long press *inside* selectable text already belongs to the
 * system selection handles + the floating Copy/Share bar, and stacking our own
 * recogniser on the same gesture makes both unreliable. So the two are split by
 * target: text keeps the platform's selection (now actually wired up, see
 * [SelectableContent]), and whatever is left of the *card* carries the block
 * actions.
 *
 * ## Why there is no ⋮ trigger
 *
 * There used to be one: because text takes the long press once a scope is in play, a
 * scoped block also drew a 32 dp ⋮ beside its content so the menu could not be missed.
 * It was removed on the user's own report — 「那三个点好像占了我的显示面积了…彻底删掉，
 * 恢复原来的显示面积。那些功能我试了，长按都能出来。大不了就是点空白的地方呗」 — and
 * that report was right on both counts: the button was a `Row { Box(weight 1f); button }`,
 * so **every** user message, assistant paragraph and tool card lost 32 dp of width, and
 * the long press on the card's chrome does still open the same menu (the user verified it
 * on the device). On a phone the width is worth more than the discoverability.
 *
 * So a block's actions are reachable exactly two ways: long-press the card's chrome, or
 * long-press a block that owns no selectable content (those never had a button).
 *
 * The menu is a plain drop-down: no animation, no scrim, no ripple, per the
 * project's no-decoration rule.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BlockActionMenu(
    actions: List<BlockAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        content()
        return
    }
    var open by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(
            onClickLabel = null,
            onLongClickLabel = "更多操作",
            onLongClick = { open = true },
            onClick = {},
        ),
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        open = false
                        action.onSelect()
                    },
                )
            }
        }
    }
}

/**
 * The transcript card's corner radius: `06 §2`「工具卡：圆角 10」, and the same
 * radius on the error card, the diff card and the three custom cards v2 draws
 * (`06 §3` 构件 9：自定义消息卡 10).
 *
 * It lives here rather than in `PiShapes` (`theme/PiTheme.kt:107-141`) because that
 * object's `card` row is the *spec's* 16 dp radius and this batch does not own
 * `PiTheme.kt`. The two are not the same component: `PiShapes.card` is still the
 * user-message bubble's radius, and `05 §3.3` decided the bubble keeps 16 dp while
 * the transcript's cards tighten to v2's 10.
 */
internal val BlockCardShape = RoundedCornerShape(10.dp)

/**
 * A tonal container card: v2's 10dp radius, no elevation, palette colour.
 *
 * **The card body is one text-selection scope** ([SelectableContent]). Every card
 * block — tool cards, the diff card, the error card, compaction, branch summaries,
 * hook messages, skills, the path list — gets selection from this one place, which
 * is also why the scope is here and not in [MonoText]/[ProseText]: one card must
 * stay one scope, or a drag that starts on the command line could not reach the
 * body below it. The card's own tap gesture (`Modifier.toggleContent`) is on the
 * `Surface` *outside* the scope and is unaffected — a tap is not consumed by a
 * selection, only a long press is.
 */
@Composable
internal fun BlockCard(
    color: Color,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    /**
     * The card's interior padding. The default is `06 §2`「卡片内 12px」; the tool
     * card and the diff card pass their own `PaddingValues(horizontal = 10.dp)`
     * because v2 gives their rows `padding:7px 10px` / `0 10px 8px` — a 10 dp
     * horizontal inset, not the default card's 12.
     */
    padding: PaddingValues = PaddingValues(PiSpacing.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BlockCardShape,
        color = color,
        border = borderColor?.let { BorderStroke(PiSpacing.hairline, it) },
    ) {
        SelectableContent {
            Column(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
                content = content,
            )
        }
    }
}

/** `06 §2` 工具卡 / diff 卡: their rows inset 10 horizontally (`padding:7px 10px`). */
internal val BlockCardRowPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

/**
 * The 3dp state stripe. Height is explicit on purpose: filling a Row's height
 * would need intrinsic measurement, and a fixed bar is enough of an accent.
 *
 * `06 §2`'s stripe is `3px` wide with a `2px` radius — the values this already had,
 * which is why v2 changed nothing here beyond naming it.
 */
@Composable
internal fun AccentStripe(
    color: Color,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(PiSpacing.stripe)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/**
 * The one expand affordance: a word plus a state glyph. Text is required —
 * colour alone never carries the state (docs/pi-android-ui-spec.md §9).
 *
 * This is a **label**, not a hit target: pi's toggle is the content region (see
 * [ToggleContent]), so leaving this inert keeps one gesture per block instead of
 * a label-sized second one.
 *
 * v2 draws every `展开 / 收起` label in `muted` (`06 §3`: the thinking row, the diff
 * card, the custom card's head), not in the accent — the accent is not a
 * decoration, and an affordance that is always on screen should not compete with the
 * card's state colour. [color] exists for the one card whose label belongs to its
 * own tone: the error card's `详情`, which v2 paints `error`.
 *
 * @param color the label and chevron colour; `muted` by default, as v2 draws it.
 */
@Composable
internal fun ExpandLabel(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    expandText: String = "展开",
    collapseText: String = "收起",
    color: Color? = null,
) {
    val tint = color ?: PiTheme.palette.muted
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (expanded) collapseText else expandText,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
        Spacer(Modifier.width(PiSpacing.tiny))
        // v2 draws every `展开 / 收起` chevron the same way round: **down while
        // expanded, right while collapsed** (`direction-b-v2.html:911`, the `ThinkRow`;
        // the same pair in this half's `ToolBlockChrome.kt` header and `DiffBlock.kt`
        // header). This used to be up-when-expanded / down-when-collapsed, i.e. the
        // collapsed state showed a downward chevron that pointed at nothing.
        Icon(
            imageVector = if (expanded) {
                Icons.Filled.KeyboardArrowDown
            } else {
                Icons.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            // 13, not 14: v2 sizes *this* chevron — the one on the `展开 / 收起` label — at
            // `s={13}` on the thinking row (`direction-b-v2.html:911`) and on the custom card's
            // head (`:951`). The 14 dp in `06 §2` is the **tool card's** title-row chevron, which
            // is a separate inline `Icon` (`ToolBlockChrome.kt`'s `ToolHeader`, `DiffBlock.kt`'s
            // header) and does not go through this composable.
            modifier = Modifier.size(EXPAND_CHEVRON_SIZE),
            tint = tint,
        )
    }
}

/** v2's `展开 / 收起` chevron: `s={13}` (`direction-b-v2.html:911`, `:951`). */
private val EXPAND_CHEVRON_SIZE = 13.dp

/**
 * The single expand/collapse gesture: **the content is the hit target**.
 *
 * `ToggleRow` — a header-row hit target — was the app's other pattern. It is
 * gone (F28 in `docs/rendering-review.md`), because pi has exactly one and this
 * is it. Every pi component that expands wraps *the thing that expands* in a
 * mouse region and toggles from there:
 *
 *  - a tool card: `modes/interactive/components/tool-execution.ts:172-178`
 *    (`createResultRegion`), applied to the call line and the result alike
 *    (`:315-321`, `:333-336`, `:347-353`);
 *  - a thinking block: `components/assistant-message.ts:160-166` wraps the whole
 *    thinking component in a `MouseRegion` that flips a visibility override;
 *  - a custom entry: `components/custom-entry.ts:59-60` adds the renderer's own
 *    component as the child, region and all.
 *
 * Nothing in pi makes a header row — rather than the content it heads — the
 * target, which is why this wrapper is a `Column` and a block hands it its whole
 * body: the header row *and* the part that appears on expand.
 *
 * The disclosure itself stays whatever the call site already used
 * (`AnimatedVisibility`, or the `if (expanded)` it had); no blur, no glow, no
 * pulse, and §11's "at most two sustained animations on a screen" is respected
 * because this adds no animation of its own.
 *
 * @param enabled false for a block with nothing to disclose (a short hook
 *   message, say): the region then takes no clicks at all rather than toggling
 *   into an empty state.
 */
// Callers: pass the lambda parenthesised — `Modifier.toggleContent(expanded, { … })`.
// Kotlin binds a *trailing* lambda to the **last** parameter, which here is
// `enabled`, so `toggleContent(expanded) { … }` does not compile.
internal fun Modifier.toggleContent(
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
): Modifier = then(
    if (enabled) {
        Modifier.clickable(onClickLabel = if (expanded) "收起" else "展开") { onToggle() }
    } else {
        Modifier
    },
)

/**
 * [Modifier.toggleContent] for a block whose content is a column: the column —
 * header row and disclosed body alike — becomes the hit target.
 *
 * Both shapes exist because a card's content region is sometimes the card's own
 * `Surface` modifier (a tool card, a diff card) and sometimes a `Column` inside
 * it (a thinking block, a hook card). They are one gesture: the `clickable` lives
 * only in [Modifier.toggleContent].
 */
@Composable
internal fun ToggleContent(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().toggleContent(expanded, onToggle, enabled),
        content = content,
    )
}

/**
 * One hoisted formatter (F30 in `docs/rendering-review.md`).
 *
 * [formatClock] runs for every timestamped row, and those rows recompose per
 * streamed chunk (F7/F8), so the old `SimpleDateFormat("HH:mm", …)` built inside
 * the function re-parsed a pattern and a locale on each call. `DateTimeFormatter`
 * is immutable and thread-safe (hence no `ThreadLocal`, and nothing to leak), and
 * the zone is still resolved per call, so a device timezone change is reflected
 * immediately exactly as the per-call version did. The one behavioural
 * difference: the pattern's locale is captured once for the process instead of
 * per call — irrelevant for `HH:mm` in this app's locales, but recorded rather
 * than discovered later.
 */
private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

/** Clock shown once per turn (docs/pi-android-ui-spec.md §4.6). */
internal fun formatClock(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(CLOCK_FORMAT)

/** Durations the way pi prints them: ms under a second, then s, then m s. */
internal fun formatDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    return when {
        safe < 1_000 -> "${safe}ms"
        safe < 60_000 -> "${safe / 1_000}s"
        else -> "${safe / 60_000}m${(safe % 60_000) / 1_000}s"
    }
}

/** Token counts stay compact in an 11.5sp meta line: 42000 becomes 42k. */
internal fun formatTokens(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}k"
    else -> count.toString()
}

/** Line count of a machine-output blob, for the tool footer. */
internal fun lineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

/**
 * Whether [text] needs more than [maxBytes] bytes once encoded as UTF-8 —
 * `text.toByteArray(Charsets.UTF_8).size > maxBytes`, without the copy.
 *
 * The allocation is the whole point of this function. [ToolCallBlock] asks that
 * question about a tool result on every composition whose `output` changed, and a
 * `bash` row changes every 200 ms while it streams (`rpc/.../Transcript.kt:618`);
 * the old spelling therefore encoded the whole result — up to pi's own 50 KiB /
 * 2000-line cap, and up to our 200 KiB rendering budget — into a throwaway byte
 * array on the frame thread, once per publication, to answer a boolean.
 *
 * Two steps, both exact:
 *
 *  - `length > maxBytes` is already an answer: every UTF-16 character costs at
 *    least one byte, so a longer string cannot fit. That settles ordinary ASCII
 *    machine output with a field read.
 *  - otherwise walk the characters. The widths are UTF-8's own; a well-formed
 *    surrogate **pair** is four bytes counted once, at its high half, and an
 *    unpaired surrogate counts **one**, which is what `String.getBytes(UTF_8)`
 *    writes for it (Java's encoder replaces a malformed surrogate with a single
 *    `?`). Getting that last case wrong would make this predicate disagree with
 *    the expression it replaces, and the caller uses the answer to decide whether
 *    to paint a body at all.
 *
 * Pure, allocation-free, and it stops as soon as the running total passes
 * [maxBytes]. Pinned against the byte-array spelling (see
 * `docs/scroll-perf-items.md` for the probe and its output).
 */
internal fun utf8ByteSizeExceeds(text: String, maxBytes: Int): Boolean {
    if (maxBytes <= 0) return text.isNotEmpty()
    // One byte per character at the very least, so this settles every large body
    // without reading a character.
    if (text.length > maxBytes) return true
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val code = text[index].code
        val width = when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            code in 0xD800..0xDBFF -> {
                val low = if (index + 1 < text.length) text[index + 1].code else 0
                if (low in 0xDC00..0xDFFF) {
                    // The pair is four bytes; count them here and skip the low half.
                    index++
                    4
                } else {
                    1
                }
            }
            // A lone low surrogate is malformed input and encodes as one byte too.
            code in 0xDC00..0xDFFF -> 1
            else -> 3
        }
        bytes += width
        if (bytes > maxBytes) return true
        index++
    }
    return false
}

/** First [max] lines of a machine-output blob (the collapse rule). */
internal fun headLines(text: String, max: Int): String {
    if (max <= 0) return ""
    val lines = text.split('\n')
    return if (lines.size <= max) text else lines.take(max).joinToString("\n")
}
