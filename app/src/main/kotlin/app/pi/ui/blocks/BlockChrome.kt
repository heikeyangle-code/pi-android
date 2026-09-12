package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import app.pi.ui.theme.PiShapes
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
 * the stream (spec §7.4: 块间距 16dp, `assistant-text` 左右内边距 0): it supplies both
 * the horizontal content padding and the vertical arrangement, so a block that
 * padded itself as well doubled the margin (F11 in `docs/rendering-review.md`).
 * Colour always comes from [PiTheme.palette].
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

/** Human prose: always the system font. */
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
        style = MaterialTheme.typography.bodyLarge,
        color = color ?: MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}


/** One action of a block's long-press menu. */
internal data class BlockAction(val label: String, val onSelect: () -> Unit)

/**
 * The card-level actions of `docs/pi-android-ui-spec.md` §4.8.
 *
 * Why a card long press rather than the spec's "long press on any text selects and
 * copies": on Android, long press *inside* selectable text already belongs to the
 * system selection handles + the floating Copy/Share bar, and stacking our own
 * recogniser on the same gesture makes both unreliable. So the two are split by
 * target: text keeps the platform's selection (which is "select to copy"), and the
 * *card* — the surface around the text — carries the block actions. Nothing wraps
 * a selectable text node, so no gesture is claimed twice.
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

/** A tonal container card: 16dp radius, no elevation, palette colour. */
@Composable
internal fun BlockCard(
    color: Color,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PiShapes.card,
        color = color,
        border = borderColor?.let { BorderStroke(PiSpacing.hairline, it) },
    ) {
        Column(
            modifier = Modifier.padding(PiSpacing.card),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
            content = content,
        )
    }
}

/**
 * The 3dp state stripe. Height is explicit on purpose: filling a Row's height
 * would need intrinsic measurement, and a fixed bar is enough of an accent.
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
 */
@Composable
internal fun ExpandLabel(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    expandText: String = "展开",
    collapseText: String = "收起",
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (expanded) collapseText else expandText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(PiSpacing.tiny))
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

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

/** Character counts for the system-prompt line. */
internal fun formatChars(count: Int): String = when {
    count >= 10_000 -> "${count / 1_000}k 字符"
    count >= 1_000 -> String.format(Locale.US, "%.1fk 字符", count / 1_000f)
    else -> "$count 字符"
}

/** Line count of a machine-output blob, for the tool footer. */
internal fun lineCount(text: String): Int =
    if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

/** First [max] lines of a machine-output blob (the collapse rule). */
internal fun headLines(text: String, max: Int): String {
    if (max <= 0) return ""
    val lines = text.split('\n')
    return if (lines.size <= max) text else lines.take(max).joinToString("\n")
}
