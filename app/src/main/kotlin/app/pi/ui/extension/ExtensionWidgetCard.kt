package app.pi.ui.extension

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.Ansi
import app.pi.ui.blocks.AccentStripe
import app.pi.ui.blocks.BlockCard
import app.pi.ui.blocks.BlockCardRowPadding
import app.pi.ui.blocks.DisclosureChevron
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
 * [DisclosureChevron], and [toggleContent] for the tap. So a widget reads as a sibling of
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
            // Closed by default: the whole point of the fold is that an extension's payload
            // costs one row until the user asks for more.
            val expanded = remember(widget.key) { mutableStateOf(false) }
            val expandedNow = expanded.value
            // The classification is a pure function of the list, and the list only changes when
            // the extension pushes a widget: without the `remember` this rebuilt it on every
            // recomposition of this stack. Collapsed, pi's row budget applies
            // (`boundedWidgetRows`); opened, it does not — the cap exists so one panel cannot
            // push the conversation off screen, and a user who tapped the card has already
            // decided to give it the room. Same split the tool card makes between its ten
            // collapsed lines and its full output.
            val rows = remember(widget.lines, expandedNow) {
                val all = widgetRows(boundedWidgetLines(widget.lines))
                if (expandedNow) all else boundedWidgetRows(all)
            }
            if (rows.isEmpty()) return@forEach
            val tone = remember(rows) { widgetCardTone(rows) }
            // Only a widget with no header of its own needs the cue: a payload row already
            // shows `详情`, and a text panel earns one only when a line was actually cut.
            val hint = remember(rows) {
                widgetNeedsDisclosure(rows) && rows.none { it is WidgetRow.Folded || it is WidgetRow.Summary }
            }
            val stateColor = widgetToneColor(tone, palette)
            // **A panel with no title of its own gets the widget's key.** Only `Summary`/`Folded`
            // rows carry a header; anything else is an extension's plain `string[]` (e.g.
            // `pi-web-access`' `setWidget("web-activity", lines)`), which used to arrive on the
            // phone as an unlabelled block of text. The key is the one identity the host always
            // has. See [widgetCaption] for why it is humanised and not translated.
            val caption = remember(widget.key, rows) {
                widgetCaption(widget.key)
                    ?.takeIf { rows.none { row -> row is WidgetRow.Summary || row is WidgetRow.Folded } }
            }
            BlockCard(
                color = palette.cardBg,
                modifier = Modifier
                    // Height changes are real events here (a job starts, a job ends, the card is
                    // opened). Animating them is what keeps the composer below from being yanked.
                    .animateContentSize()
                    .toggleContent(expanded.value, { expanded.value = !expanded.value }),
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
                        caption?.let { text ->
                            Text(
                                text = text,
                                style = PiTheme.text.monoSmall,
                                color = PiTheme.palette.dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        WidgetRows(rows = rows, expanded = expanded.value)
                        if (hint) {
                            // v2's 提示条 shape (`· 前缀 + 文本`, 12–13): a dim sentence rather
                            // than a button, because the whole card is the hit target.
                            Text(
                                text = if (expanded.value) "· 收起" else "· 展开看全文",
                                style = PiTheme.text.monoSmall,
                                color = PiTheme.palette.dim,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card's body rows, with the verbatim lines grouped into a **bounded, self-scrolling**
 * block.
 *
 * `rows` used to be drawn straight into the card's column, one line per element, so a plain
 * `string[]` widget (the real device's `pi-subagents` panel, all `::` / `∟` lines and the
 * extension's own colours) made the card as tall as pi's own 10-line cap allows — 220 dp of a
 * screen whose composer has to stay reachable. The cap and the inner scroll are the **host's**
 * addition here, exactly as [WidgetDetailsMaxHeight] is for the opened detail area; the lines
 * themselves are untouched — same text, same spans, same colours, same order, no re-wrap (the
 * opened preformatted ones scroll sideways, the rest still wrap).
 *
 * The grouping walks **runs of consecutive `Text` rows** rather than assuming "every row is
 * text": a payload row in the middle of prose keeps its place in the order, and a text widget
 * (the case this exists for) is one run.
 */
@Composable
private fun WidgetRows(rows: List<WidgetRow>, expanded: Boolean) {
    var index = 0
    while (index < rows.size) {
        val row = rows[index]
        if (row !is WidgetRow.Text) {
            WidgetRowView(row, expanded)
            index++
            continue
        }
        var end = index
        while (end + 1 < rows.size && rows[end + 1] is WidgetRow.Text) end++
        val run = rows.subList(index, end + 1)
        // `key` because each run owns a `rememberScrollState` and the list's length changes
        // with the payload: without it a removed run's scroll offset would be inherited by the
        // run below it, i.e. the block would appear to jump the moment a payload shrank.
        key(index) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = WidgetDetailsMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                run.forEach { line -> WidgetRowView(line, expanded) }
            }
        }
        index = end + 1
    }
}

/** One classified line, drawn the way its row asks for. */
@Composable
private fun WidgetRowView(row: WidgetRow, expanded: Boolean) {
    when (row) {
        is WidgetRow.Text -> {
            // Text lines keep the extension's own colours: it wrote them with `theme.fg(…)`,
            // and `Ansi.parse` → `tokenColorFor` is how the terminal's SGR bytes become palette
            // tokens. Folded: one row, cut (pi's own panel truncates a widget line to the panel
            // width and never wraps it — `truncLine`, `tui/render.js`).
            //
            // Opened, three shapes:
            //  - **prose** → the whole line, wrapped: sentences are read by wrapping;
            //  - **preformatted** (box art / space-aligned columns / past pi's own 80-column
            //    terminal width) → one line, no wrap, horizontal scroll: the column alignment
            //    *is* the content and wrapping shreds it. The predicate is the pure
            //    `isPreformatted` (harness `text-lines` pins the thresholds), so this site
            //    only changes the modifier — the rule itself cannot drift here.
            val spans = remember(row.text) { chromeSpans(row.text) }
            val preformatted = remember(row.text) { isPreformatted(row.text) }
            val sideScroll = rememberScrollState()
            ExtensionSpans(
                spans = if (spans.all { it.text.isEmpty() }) listOf(Ansi.Span(" ")) else spans,
                defaultColor = PiTheme.palette.muted,
                // 22, the design's own `.wl` line box — a plain-text widget panel is read as a
                // list of terminal rows, and 18 dp (the role's default leading) ran them
                // together. The **size** stays the role's; only the leading moves.
                style = PiTheme.text.monoSmall.copy(lineHeight = 22.sp),
                maxLines = if (expanded && !preformatted) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                // horizontalScroll hands the line unbounded width, so it cannot wrap; the
                // tail is reachable by panning instead of being lost to an ellipsis.
                modifier = if (expanded && preformatted) {
                    Modifier.horizontalScroll(sideScroll)
                } else {
                    Modifier
                },
            )
        }

        is WidgetRow.Folded -> WidgetDisclosure(
            label = row.label,
            badge = null,
            headline = emptyList(),
            details = emptyList(),
            jobs = emptyList(),
            raw = row.raw,
            expanded = expanded,
        )

        is WidgetRow.Summary -> WidgetDisclosure(
            label = row.label,
            badge = row.badge,
            headline = row.headline,
            details = row.details,
            jobs = row.jobs,
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
    jobs: List<WidgetJob>,
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
        // **The state summary ends the row, it does not fill it.** It used to take
        // `weight(1f)` on the `WidgetSpans` itself — the whole space between the badge and
        // `详情` — so `3 运行中` sat in the middle of the header and moved whenever the label or
        // the badge changed width; worse, the three extension cards beside it carried their
        // counts in three different places. The summary is a **reading**, and readings in this
        // card are right-aligned: the flexible space is a box that ends where `详情` begins, so
        // the summary, the info cards' counts and the `详情` beside them all sit on one right
        // edge down the stack.
        //
        // It is a **box** rather than a bare `Spacer`+`WidgetSpans` pair for the reason the
        // weighted cell exists at all: the unweighted children (the label, the badge, `详情` and
        // the chevron) are measured first and keep their space, so a headline that lists four
        // states cannot squeeze the affordance off the row — it ellipsises inside this box.
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (headline.isNotEmpty()) {
                WidgetSpans(spans = headline)
            }
        }
        Spacer(Modifier.width(PiSpacing.gutter))
        // A label, not a second hit target: the card is the toggle (`toggleContent`), which is
        // the one expand idiom the tree has (BlockChrome's `ExpandLabel` KDoc).
        WidgetDisclosureLabel(expanded)
    }
    if (!expanded) return
    WidgetDetails(details = details, jobs = jobs)
    WidgetRawPayload(raw)
}

/**
 * The card's disclosure pair: the words the tree already uses (`详情` / `收起`, and the raw
 * viewer's own `查看` / `收起`), plus the **tool row's** 5 dp chevron rather than the 13 dp icon
 * `ExpandLabel` draws for a `展开 / 收起` label ([DisclosureChevron] has the argument). It is
 * the same affordance on a row that carries four fields, so it is the same geometry the folded
 * tool row draws.
 */
@Composable
private fun WidgetDisclosureLabel(
    expanded: Boolean,
    expandText: String = "详情",
    collapseText: String = "收起",
) {
    val palette = PiTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (expanded) collapseText else expandText,
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted,
        )
        Spacer(Modifier.width(PiSpacing.tiny))
        DisclosureChevron(expanded = expanded, tint = palette.muted)
    }
}

/**
 * The opened card's body: **one row per drawn leaf** when the payload has a structured
 * projection ([WidgetRow.Summary.jobs]), which is the shape this card prefers — a subagent's
 * own name on the left, its live word and readings on the right.
 *
 * The raw span rows ([WidgetRow.Summary.details]) are the fallback: they are what the card
 * drew before the structured form existed, and a payload that produced no structured row still
 * gets them rather than an empty body. Both are the **same** walk of the payload (see
 * `jobRows`), so which leaves are shown and in what order never depends on which shape is
 * drawn.
 *
 * **A bounded area, like the raw payload below it.** The card sits above the composer, so every
 * row the details gain takes height away from the conversation and moves the composer: with
 * four parallel agents starting and finishing, that was the second half of the device report
 * ("来回跳"). Opening the card is the user asking to read it, not asking to push their draft off
 * screen.
 */
@Composable
private fun WidgetDetails(details: List<List<WidgetSpan>>, jobs: List<WidgetJob>) {
    if (jobs.isEmpty() && details.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = WidgetDetailsMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        if (jobs.isNotEmpty()) {
            jobs.forEach { job -> WidgetJobRow(job) }
        } else {
            details.forEach { line ->
                // The name inside a detail row is the one run a reader scans for, so it is
                // bolded — which is what the extension itself does (`themeBold` in its own
                // renderer).
                WidgetSpans(spans = line, modifier = Modifier.fillMaxWidth(), boldText = true)
            }
        }
    }
}

/**
 * One structured row: the extension's state glyph, its own name, and its readings at the row's
 * other edge.
 *
 * The two cells are the design's `.job` grid (`14px minmax(0,1fr) auto`): the name is the
 * **flexible** column (it takes whatever the readings do not), and the readings are the `auto`
 * one — measured first, at their own width, so they are never the cell that gets cut. The
 * readings are also the smaller claim: they are two counters, while the name is the thing the
 * reader is scanning the list for. A row whose glyph is empty is the host's own summary sentence
 * (`+N 个更多…`), not a leaf, and stays `dim` the way the raw form draws it.
 */
@Composable
private fun WidgetJobRow(job: WidgetJob) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (job.glyph.isNotEmpty()) {
            Text(
                text = job.glyph,
                style = PiTheme.text.monoSmall,
                color = widgetToneColor(job.tone, palette) ?: palette.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(PiSpacing.gutter))
        }
        Text(
            text = job.name,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.monoSmall,
            // The name is the run a reader scans for, exactly as it is in the raw form.
            color = if (job.glyph.isEmpty()) palette.dim else palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (job.readings.isNotEmpty()) {
            Spacer(Modifier.width(PiSpacing.gutter))
            Text(
                text = job.readings,
                style = PiTheme.text.monoSmall,
                color = palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** The payload itself, one tap deeper and bounded: the viewer, never the card's layout. */
@Composable
private fun WidgetRawPayload(raw: String) {
    val palette = PiTheme.palette
    val open = remember { mutableStateOf(false) }
    // The footer is its own **band**: a 1 px `borderMuted @35%` rule (the design's
    // `.foot{border-top:1px solid var(--ring)}`, and the same ring colour every card's outline
    // uses) separates it from the rows above, so `原始数据 · N 字符` reads as the card's floor
    // rather than as one more detail line. 1 px, and it is the app's `hairline` token — the
    // rule that all separators are 1 px (`06 §5`).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PiSpacing.hairline)
            .background(palette.borderMuted.copy(alpha = 0.35f)),
    )
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
        WidgetDisclosureLabel(open.value, expandText = "查看")
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

/**
 * The opened card's detail budget.
 *
 * The same number [WidgetRawPayload] gives the raw text, and for the same reason: the card
 * lives above the composer, so an unbounded detail area is an unbounded claim on the
 * conversation's height. The rows stay reachable by scrolling inside the block.
 */
private val WidgetDetailsMaxHeight = 200.dp
