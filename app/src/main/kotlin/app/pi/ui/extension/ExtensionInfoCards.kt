package app.pi.ui.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.ui.blocks.BlockCard
import app.pi.ui.blocks.BlockCardRowPadding
import app.pi.ui.blocks.ExpandLabel
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
 */
@Composable
private fun ExtensionStatusCard(statuses: List<ExtensionStatus>) {
    val rows = remember(statuses) { statusRows(statuses.map { it.key to it.text }) }
    if (rows.isEmpty()) return
    InfoCard(label = "扩展状态", badge = rows.size.toString()) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.key,
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(StatusKeyColumn),
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

/** The key column: pi's footer has no such column, but two rows must line up. */
private val StatusKeyColumn = 72.dp

/**
 * The extensions that use a surface `--mode rpc` cannot carry.
 *
 * pi reports **nothing** when an extension takes one of these paths — `custom()`
 * resolves `undefined`, the setters are literal no-ops (`rpc-mode.ts:179-311`) — so
 * the App cannot know a feature is missing unless it looks at the extension's own
 * source. `TuiOnlyScan` does that scan; this card is where its answer becomes
 * visible, one row per extension, so "this extension's picker does not exist here"
 * is a sentence the user can read instead of a bug they have to guess at.
 */
@Composable
private fun ExtensionTuiOnlyCard(extensions: List<TuiOnlyExtension>) {
    if (extensions.isEmpty()) return
    InfoCard(label = "本应用显示不了的扩展", badge = extensions.size.toString()) {
        extensions.forEach { extension ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

/**
 * The shell both cards share: the tool card's furniture, one folded header row, and
 * the body only when opened.
 *
 * Deliberately the same shape as [ExtensionWidgetStack]'s cards — `BlockCard`, the
 * neutral container with a `borderMuted` border, the label-plus-count row, and
 * [ExpandLabel] — because these three surfaces are one family to the user: "things an
 * extension asked this screen to show".
 */
@Composable
private fun InfoCard(
    label: String,
    badge: String,
    content: @Composable () -> Unit,
) {
    val expanded = remember(label) { mutableStateOf(false) }
    BlockCard(
        color = PiTheme.palette.cardBg,
        modifier = Modifier.toggleContent(expanded.value, { expanded.value = !expanded.value }),
        borderColor = PiTheme.palette.borderMuted.copy(alpha = 0.35f),
        padding = BlockCardRowPadding,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(PiSpacing.gutter))
            Text(
                text = badge,
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.dim,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(PiSpacing.gutter))
            ExpandLabel(expanded = expanded.value, expandText = "详情", collapseText = "收起")
        }
        if (expanded.value) content()
    }
}

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
