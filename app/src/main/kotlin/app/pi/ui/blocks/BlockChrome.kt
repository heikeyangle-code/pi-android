package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Column(
            modifier = Modifier.padding(PiSpacing.card),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
            .width(3.dp)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/**
 * The one expand affordance: a word plus a state glyph. Text is required —
 * colour alone never carries the state (docs/pi-android-ui-spec.md §9).
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
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A whole-row toggle used by the collapsible blocks. */
@Composable
internal fun ToggleRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "收起" else "展开") { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
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
