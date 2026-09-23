package app.pi.ui.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.rpc.Ansi
import app.pi.ui.blocks.AccentStripe
import app.pi.ui.blocks.BlockCard
import app.pi.ui.blocks.BlockCardRowPadding
import app.pi.ui.blocks.ExpandLabel
import app.pi.ui.blocks.toggleContent
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `setWidget` panels: one card per key, in registration order.
 *
 * This is the **one** drawing site for every extension widget line, and it is why
 * the rules in `ExtensionWidgetLines.kt` are generic rather than a fix for one
 * extension: whatever any extension puts in `widgetLines`, it lands here.
 *
 * ## What it borrows, and the two places it deliberately differs
 *
 * The card is the transcript's own furniture — [BlockCard] (radius 10, 1 px
 * `hairline`), [BlockCardRowPadding] (`7px 10px` rows), [AccentStripe],
 * [ExpandLabel], and [toggleContent] for the tap. So a widget reads as a sibling of
 * the tool card rather than as chrome from another app.
 *
 * It differs in exactly two ways, both because a widget is not a tool call:
 *
 *  1. **The container stays `cardBg` and only the border takes the state colour.**
 *     The tool card is one call with one state, so it tints the whole container
 *     (`toolPendingBg` / `toolSuccessBg` / `toolErrorBg`). One widget holds *many*
 *     jobs at once — three running and one failed is the normal case — and a
 *     container tint would have to lie about which. The border carries the most
 *     severe state, and a failure or a pause also gets the 3 dp
 *     [AccentStripe] the titled cards use (`error`, `custom`), which the tool card
 *     never shows. The stripe is the family marker: extension output, not a call.
 *  2. **The title row is a label plus the counts, not a command.** A tool card
 *     names the tool and its arguments; a widget has no command, so the label is
 *     `子代理` (the host's word), then the job count, then the state counts in
 *     their own colours, then `详情` / `收起`.
 *
 * ## The row budget, and what an unopened card costs
 *
 * The row cap and its truncation row are pi's ([boundedWidgetLines],
 * `MAX_WIDGET_LINES = 10`, `interactive-mode.ts:2276`): a widget is an extension's
 * own layout, and pi caps it so one panel cannot push the conversation off screen.
 * The cap is applied here rather than when the state is built, so
 * [ExtensionWidget.lines] stays exactly what pi sent.
 *
 * Everything a payload needs is on the **closed** card: one row, no timestamps, and
 * nothing that changes when the payload is re-sent (see `widgetCardTone` and the
 * harness's byte-identity check). Opening it is the user's move, and only then are
 * the per-job rows and the raw payload laid out.
 */
@Composable
fun ExtensionWidgetStack(
    widgets: List<ExtensionWidget>,
    modifier: Modifier = Modifier,
) {
    if (widgets.isEmpty()) return
    val palette = PiTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
        // `06 §2`: 块间距 8.
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        widgets.forEach { widget ->
            // The cap and the classification are pure functions of the list, and the list only
            // changes when the extension pushes a widget: without the `remember` this rebuilt
            // both on every recomposition of this stack.
            val rows = remember(widget.lines) { widgetRows(boundedWidgetLines(widget.lines)) }
            if (rows.isEmpty()) return@forEach
            val tone = remember(rows) { widgetCardTone(rows) }
            val stateColor = widgetToneColor(tone, palette)
            // Closed by default: the whole point of the fold is that an extension's payload
            // costs one row until the user asks for more.
            val expanded = remember(widget.key) { mutableStateOf(false) }
            BlockCard(
                color = palette.cardBg,
                modifier = Modifier.toggleContent(expanded.value, { expanded.value = !expanded.value }),
                borderColor = (stateColor ?: palette.borderMuted).copy(alpha = 0.35f),
                padding = BlockCardRowPadding,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The stripe only for the two states a reader must not miss. `06 §2` draws
                    // it on the titled cards; a running widget is not news and does not get one.
                    if (tone == WidgetTone.Error || tone == WidgetTone.Warning) {
                        AccentStripe(stateColor ?: palette.error, PiSpacing.accentStripe)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        rows.forEach { row -> WidgetRowView(row, expanded.value) }
                    }
                }
            }
        }
    }
}

/** One classified line, drawn the way its row asks for. */
@Composable
private fun WidgetRowView(row: WidgetRow, expanded: Boolean) {
    when (row) {
        is WidgetRow.Text -> {
            // Text lines keep the extension's own colours: it wrote them with `theme.fg(…)`,
            // and `Ansi.parse` → `tokenColorFor` is how the terminal's SGR bytes become palette
            // tokens. `maxLines = 1` because pi's own panel truncates a widget line to the panel
            // width and never wraps it (`truncLine`, `tui/render.js`) — wrapping is what let one
            // long line become twenty-five rows of the conversation.
            val spans = remember(row.text) { chromeSpans(row.text) }
            ExtensionSpans(
                spans = if (spans.all { it.text.isEmpty() }) listOf(Ansi.Span(" ")) else spans,
                defaultColor = PiTheme.palette.muted,
                style = PiTheme.text.monoSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is WidgetRow.Folded -> WidgetDisclosure(
            label = row.label,
            badge = null,
            headline = emptyList(),
            details = emptyList(),
            raw = row.raw,
            expanded = expanded,
        )

        is WidgetRow.Summary -> WidgetDisclosure(
            label = row.label,
            badge = row.badge,
            headline = row.headline,
            details = row.details,
            raw = row.raw,
            expanded = expanded,
        )
    }
}

/**
 * A folded payload: the host's label, the counts, and — once opened — the detail
 * rows and the raw payload.
 *
 * The summary's visible bytes are the whole closed card, which is what makes the
 * flicker impossible rather than merely smaller: they come from the label and the
 * counts, never from the payload's volatile fields (`generatedAt`, `updatedAt`), so
 * an extension that re-sends its payload on every status tick rebuilds the same
 * string. The opened viewer is plain [Text] and **not** [ExtensionSpans]: that is
 * the other half of folding — `Ansi.parse` never sees the payload.
 */
@Composable
private fun WidgetDisclosure(
    label: String,
    badge: String?,
    headline: List<WidgetSpan>,
    details: List<List<WidgetSpan>>,
    raw: String,
    expanded: Boolean,
) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = PiTheme.text.meta,
            color = palette.muted,
            maxLines = 1,
        )
        if (badge != null) {
            Spacer(Modifier.width(PiSpacing.gutter))
            Text(
                text = badge,
                style = PiTheme.text.monoSmall,
                color = palette.dim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(PiSpacing.gutter))
        if (headline.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            WidgetSpans(spans = headline, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.width(PiSpacing.gutter))
        // A label, not a second hit target: the card is the toggle (`toggleContent`), which is
        // the one expand idiom the tree has (`ExpandLabel`'s KDoc).
        ExpandLabel(expanded = expanded, expandText = "详情", collapseText = "收起")
    }
    if (!expanded) return
    details.forEach { line ->
        // The name inside a detail row is the one run a reader scans for, so it is bolded —
        // which is what the extension itself does (`themeBold` in its own renderer).
        WidgetSpans(spans = line, modifier = Modifier.fillMaxWidth(), boldText = true)
    }
    WidgetRawPayload(raw)
}

/** The payload itself, one tap deeper and bounded: the viewer, never the card's layout. */
@Composable
private fun WidgetRawPayload(raw: String) {
    val palette = PiTheme.palette
    val open = remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open.value = !open.value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "原始数据 · ${raw.length} 字符",
            style = PiTheme.text.monoSmall,
            color = palette.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ExpandLabel(expanded = open.value, expandText = "查看", collapseText = "收起")
    }
    if (!open.value) return
    Text(
        text = raw,
        style = PiTheme.text.monoSmall,
        color = palette.muted,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
    )
}

/** A tone-tagged line. `Text` is the readable run — the one weight is spent there. */
@Composable
private fun WidgetSpans(
    spans: List<WidgetSpan>,
    modifier: Modifier = Modifier,
    boldText: Boolean = false,
) {
    val palette = PiTheme.palette
    val text = remember(spans, palette, boldText) {
        buildAnnotatedString {
            spans.forEach { span ->
                withStyle(
                    SpanStyle(
                        color = widgetToneColor(span.tone, palette) ?: palette.muted,
                        fontWeight = if (boldText && span.tone == WidgetTone.Text) FontWeight.Bold else null,
                    ),
                ) {
                    append(span.text)
                }
            }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = PiTheme.text.monoSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The token a widget tone means, as a palette value — always a palette value, so a
 * state colour can never drift from the user's theme.
 *
 * `null` in, `null` out: a widget with no state of its own keeps the neutral
 * border and draws no stripe.
 */
internal fun widgetToneColor(tone: WidgetTone?, palette: PiPalette): Color? = when (tone) {
    null -> null
    WidgetTone.Text -> palette.text
    WidgetTone.Muted -> palette.muted
    WidgetTone.Dim -> palette.dim
    WidgetTone.Accent -> palette.accent
    WidgetTone.Success -> palette.success
    WidgetTone.Warning -> palette.warning
    WidgetTone.Error -> palette.error
}
