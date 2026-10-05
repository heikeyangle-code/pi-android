package app.pi.ui.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.blocks.BlockCard
import app.pi.ui.blocks.BlockCardRowPadding
import app.pi.ui.blocks.DisclosureChevron
import app.pi.ui.blocks.toggleContent
import app.pi.ui.chat.TuiOnlyExtension
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * The two non-widget extension surfaces, as cards.
 *
 * Both are **folded to one row by default** and both draw nothing at all when they
 * have nothing to say — which is what a persistent host surface owes the composer:
 * the old status row was deleted because it was always there and always the same
 * size, not because status has no value. One row, and only while an extension is
 * actually reporting something, is the difference.
 *
 * ## The three cards on this shelf are one family, and they now measure alike
 *
 * `子代理` (the widget card), `扩展状态` and `本应用显示不了的扩展` sit stacked above the
 * composer, and a reader compares them without deciding to. So all three share a **24 dp
 * header module** (the folded tool row's height — the transcript's own row height), their
 * readings end on **one right edge**, and every disclosure is the same `详情` / `收起` pair with
 * the tool row's 5 dp chevron instead of the 13 dp icon. The icon is right for a
 * `展开 / 收起` label that owns its own row; these headers carry a label, a count and a word.
 *
 * They are mounted above the editor only. A widget's own `widgetPlacement` decides
 * which side *it* goes on; these two have no placement of their own, and drawing them
 * twice would be two copies of the same list.
 */

/**
 * `setStatus` entries — the extension's own text, in the extension's own colour.
 *
 * pi draws them in its terminal footer; the app's footer is at the top, and v2's
 * dialogue shell has no such line (adjudication D-3), so they live here instead: the
 * sheet's full-text view is still reachable, but the live signal is one row next to
 * the composer rather than nothing at all.
 *
 * The body is a **two-column table**: the key, then the extension's own sentence, with a 1 px
 * `borderMuted @35%` rule between rows (the card's own ring colour). The key column is measured
 * from **this card's** keys ([measuredKeyColumn]) rather than pinned to a constant: pi's keys are
 * extension names, they are as long as they are, and the old fixed 72 dp column put every longer
 * one through the same ellipsis — a table whose first column cannot be read is not a table. The
 * value column is not truncated at all: an extension wrote that sentence, so it wraps.
 */
@Composable
private fun ExtensionStatusCard(statuses: List<ExtensionStatus>) {
    val rows = remember(statuses) { statusRows(statuses.map { it.key to it.text }) }
    if (rows.isEmpty()) return
    val keyColumn = measuredKeyColumn(rows.map { it.key })
    InfoCard(label = "扩展状态", badge = rows.size.toString()) {
        // One child, so the rows' own spacing is the section's business: `BlockCard` spaces its
        // children by the block gap, which would put 6 dp of air on each side of every 1 px
        // separator and turn the table back into a list of paragraphs.
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, row ->
                if (index > 0) InfoRowRule()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = INFO_ROW_MIN_HEIGHT)
                        // The row's own air, inside its 24 dp floor: the design's
                        // `.kv{padding:3px 10px;min-height:24px}` — the 10 horizontal is the
                        // card's (`BlockCardRowPadding`) and the 6 between the two cells is
                        // `PiSpacing.gutter`.
                        .padding(vertical = INFO_ROW_PADDING),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = row.key,
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.muted,
                        // No ellipsis: the column *is* this card's longest key, so every key fits
                        // by construction. A cap here would be the fixed 72 dp column again, one
                        // measurement error away.
                        maxLines = 1,
                        modifier = Modifier.width(keyColumn),
                    )
                    Spacer(Modifier.width(PiSpacing.gutter))
                    // The extension's own colour, through the same ANSI → token path every
                    // other extension string uses. The host adds no colour of its own here:
                    // it did not write this text.
                    val spans = remember(row.text) { chromeSpans(row.text) }
                    ExtensionSpans(
                        spans = spans,
                        defaultColor = PiTheme.palette.muted,
                        style = PiTheme.text.monoSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The two-column table's key column, in dp: **this card's longest key, measured with the very
 * style that will draw it**.
 *
 * It replaces a 72 dp constant, which was a guess at one extension's key and truncated every
 * longer one. Measuring the longest is not another magic number either — it is the rule the tool
 * row already uses for its reading column, applied to text with no fixed width: the column is as
 * wide as its widest content and not one dp wider, so the sentence beside it gets everything
 * else. `null`-safe by construction (`maxOfOrNull`), and it is measured once per card, not per
 * row.
 *
 * `rememberTextMeasurer` is the same pre-measure `ShellBlock` uses to find a line boundary: the
 * `Text` below is laid out with this exact style and density, so the width is the width.
 */
@Composable
private fun measuredKeyColumn(keys: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = PiTheme.text.monoSmall
    val density = LocalDensity.current
    return remember(keys, style, measurer, density) {
        val widest = keys.maxOfOrNull { key -> measurer.measure(text = key, style = style).size.width } ?: 0
        with(density) { widest.toDp() }
    }
}

/** One 1 px `borderMuted @35%` rule between two rows of a card's body — the card's ring colour. */
@Composable
private fun InfoRowRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PiSpacing.hairline)
            .background(PiTheme.palette.borderMuted.copy(alpha = 0.35f)),
    )
}

/**
 * The extensions that use a surface `--mode rpc` cannot carry.
 *
 * pi reports **nothing** when an extension takes one of these paths — `custom()`
 * resolves `undefined`, the setters are literal no-ops (`rpc-mode.ts:179-311`) — so
 * the App cannot know a feature is missing unless it looks at the extension's own
 * source. `TuiOnlyScan` does that scan; this card is where its answer becomes
 * visible, one row per extension, so "this extension's picker does not exist here"
 * is a sentence the user can read instead of a bug they have to guess at.
 *
 * Its body keeps the shape it had — the extension's name with its markers at the row's other
 * edge, then the attribution line (`scope · path`) under it — because the second line is not a
 * *value* for the first: it is longer than the name, so a two-column table would size the name's
 * column to the path. What it takes from the status card is the chrome (the 24 dp module, the
 * count's right edge, the 1 px rule between entries, the 5 dp chevron), which is what makes the
 * two read as one shelf.
 */
@Composable
private fun ExtensionTuiOnlyCard(extensions: List<TuiOnlyExtension>) {
    if (extensions.isEmpty()) return
    InfoCard(label = "本应用显示不了的扩展", badge = extensions.size.toString()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            extensions.forEachIndexed { index, extension ->
                if (index > 0) InfoRowRule()
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.tiny)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = extension.name,
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.text,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(PiSpacing.gutter))
                        Text(
                            text = extension.markers.joinToString(" "),
                            style = PiTheme.text.monoSmall,
                            color = PiTheme.palette.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The same attribution rule the extension-error path uses: name the
                    // extension by the path it was loaded from, never by a bare label the
                    // source could have lied about.
                    Text(
                        text = "${extension.scope} · ${extension.path}",
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The shell both cards share: the tool card's furniture, one folded header row, and
 * the body only when opened.
 *
 * Deliberately the same shape as [ExtensionWidgetStack]'s cards — `BlockCard`, the
 * neutral container with a `borderMuted` border, the label-plus-count row, and the
 * same `详情` / `收起` pair — because these three surfaces are one family to the user:
 * "things an extension asked this screen to show".
 */
@Composable
private fun InfoCard(
    label: String,
    badge: String,
    content: @Composable () -> Unit,
) {
    val expanded = remember(label) { mutableStateOf(false) }
    val palette = PiTheme.palette
    BlockCard(
        color = palette.cardBg,
        modifier = Modifier.toggleContent(expanded.value, { expanded.value = !expanded.value }),
        borderColor = palette.borderMuted.copy(alpha = 0.35f),
        padding = BlockCardRowPadding,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The 24 dp module the folded tool row uses (`TOOL_ROW_MIN_HEIGHT`): these cards
                // stand beside the transcript's own rows, and each used to land a couple of dp
                // off that height. A **minimum**, not a height — the system font scale has to be
                // able to grow a label.
                .heightIn(min = INFO_HEADER_MIN_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = PiTheme.text.meta,
                color = palette.muted,
                maxLines = 1,
            )
            // **The count ends the row; it does not follow the label.** The two cards' numbers
            // are the same kind of reading, and beside the label each landed wherever that
            // label's own width happened to stop — two different x for two numbers a reader
            // compares. The flexible space is a box that ends where `详情` begins, so the widget
            // card's state summary, these counts and the `详情` beside them all sit on one right
            // edge down the shelf.
            //
            // A **box** rather than a `Spacer(weight)` + a bare `Text`: the unweighted children
            // (the label, `详情` and the chevron) are measured first and keep their space, so the
            // affordance can never be squeezed off the row by a long label.
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = badge,
                    // The machine face, which is also this app's `tabular-nums`:
                    // `PiTextStyles.numeric` is `mono` for exactly this reason
                    // (`FontFeatureSetting("tnum")` is not available on every API level this app
                    // supports through `FontFamily.Default`), and the design asks for `mono` on
                    // this number. At v2's label step, not the body one.
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(PiSpacing.gutter))
            InfoDisclosureLabel(expanded.value)
        }
        if (expanded.value) content()
    }
}

/**
 * The `详情` / `收起` pair: the two words every card in the tree uses, and the **tool row's**
 * 5 dp geometry rather than the 13 dp icon `ExpandLabel` draws for a `展开 / 收起` label
 * ([DisclosureChevron] carries that argument). One affordance, one shape — the folded tool row
 * and all three extension cards.
 */
@Composable
private fun InfoDisclosureLabel(expanded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (expanded) "收起" else "详情",
            style = MaterialTheme.typography.labelMedium,
            color = PiTheme.palette.muted,
        )
        Spacer(Modifier.width(PiSpacing.tiny))
        DisclosureChevron(expanded = expanded, tint = PiTheme.palette.muted)
    }
}

/**
 * The header row's height — the folded tool row's 24 dp, the transcript's own row module. A
 * minimum, for the reason given at its use.
 */
private val INFO_HEADER_MIN_HEIGHT = 24.dp

/** One table row's floor: the design's `.kv{min-height:24px}`. */
private val INFO_ROW_MIN_HEIGHT = 24.dp

/** One table row's own vertical air inside that floor: the design's `.kv{padding:3px …}`. */
private val INFO_ROW_PADDING = 3.dp

/** The stack the composer mounts: the two folded cards, side by side with the widgets. */
@Composable
fun ExtensionInfoCards(
    statuses: List<ExtensionStatus>,
    tuiOnly: List<TuiOnlyExtension>,
    modifier: Modifier = Modifier,
) {
    if (statuses.isEmpty() && tuiOnly.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
        // `06 §2`: 块间距 8.
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExtensionStatusCard(statuses)
        ExtensionTuiOnlyCard(tuiOnly)
    }
}
