package app.pi.ui.extension

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.rpc.Ansi
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * The fire-and-forget half of pi's extension UI, as chrome.
 *
 * Nothing here is interactive: pi explicitly does not expect a reply for these
 * (`docs/rpc.md` §"Extension UI Protocol": "The client can display the
 * information or ignore it"), so they are rendered as passive rows rather than
 * dialogs or toasts. The one exception is `set_editor_text`, which is not chrome
 * at all and fills the composer in ChatScreen.
 *
 * pi puts the widget next to the editor (`docs/tui.md` §"Widgets above/below
 * editor"), and the app hangs [ExtensionWidgetStack] around its composer.
 *
 * **The status row is gone.** pi draws `setStatus` in its terminal footer
 * (`interactive-mode.ts:2090`) and the app used to draw a scrolling row of
 * counters under its AppBar; the user's adjudication
 * (`design/ui-refactor/11-designer-adjudication.md` D-3 — v2's dialogue shell has
 * no such line) deleted that composable, `ExtensionStatusRow`. The *data* is
 * untouched: `UiState.extensionStatuses` is still collected and `setStatus` is
 * still routed (`PiSessionViewModel.setExtensionStatus`), so showing it again —
 * in the 「会话与队列」 sheet the adjudication names — is a call site, and nothing
 * here would have to change.
 */

/**
 * One extension-supplied string, drawn with the colours pi asked for.
 *
 * `Ansi.Span.foreground` is an RGB value, not a token name, so the span is
 * matched back onto the palette by [tokenColorFor] — and when no token is close
 * enough, [defaultColor] is used instead. That fallback is the common case in
 * practice for text without colour at all: [Ansi.parse] returns a single span
 * with a null foreground for it, so an uncoloured string costs one `Text` and
 * nothing else.
 *
 * Typography (bold/italic/underline) and `dim` are not carried over, and a
 * span's background is ignored — see the KDoc on [tokenColorFor].
 */
@Composable
fun ExtensionSpans(
    spans: List<Ansi.Span>,
    defaultColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val palette = PiTheme.palette
    // One candidate array per palette, not one per span: `toArgb()` is not free
    // and a widget can carry dozens of spans.
    val tokens = remember(palette) { palette.themeTokenArgb() }
    // Keyed on the spans themselves: `Ansi.Span` is a data class, so an unchanged
    // string re-renders nothing when the caller recomposes.
    val text: AnnotatedString = remember(spans, tokens, defaultColor) {
        buildAnnotatedString {
            spans.forEach { span ->
                withStyle(SpanStyle(color = tokenColorFor(span, tokens) ?: defaultColor)) {
                    append(span.text)
                }
            }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * `setWidget` panels, in registration order.
 *
 * Lines are rendered verbatim as monospace: `docs/rpc.md` restricts RPC widgets
 * to `widgetLines: string[]` (component factories are ignored in RPC mode), so
 * there is nothing to interpret — drawing them as anything richer would invent
 * structure pi never sent.
 *
 * Row budget and truncation row are pi's ([boundedWidgetLines], `MAX_WIDGET_LINES`):
 * a widget is an extension's own layout, and pi caps it at ten rows so one panel
 * cannot push the conversation off screen (`interactive-mode.ts:2276`). Applying
 * the cap here rather than when the state is built keeps [ExtensionWidget.lines]
 * equal to the wire payload.
 *
 * The panel is a v2 card: radius 10 (`06 §2` gives every content card that one
 * radius) and a 1 px `borderMuted` at `35 %` — the retired spec's `cardInner`
 * radius (12) and `40 %` alpha are neither, and `06 §2`'s alpha set is
 * `.35/.45/.55/.5/.08/.32`.
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        widgets.forEach { widget ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(WidgetCardRadius),
                color = palette.cardBg,
                border = BorderStroke(PiSpacing.hairline, palette.borderMuted.copy(alpha = 0.35f)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // The cap is a pure function of the list, and the list only changes when the
                    // extension pushes a widget: without the `remember` this rebuilt it on every
                    // recomposition of this stack.
                    val shown = remember(widget.lines) { boundedWidgetLines(widget.lines) }
                    shown.forEachIndexed { index, line ->
                        WidgetLineRow(line = line, index = index)
                    }
                }
            }
        }
    }
}

/** The `setTitle` value, or the screen's own fallback when pi set none. */
fun windowTitleOf(title: String?, fallback: String): String = title?.takeIf { it.isNotBlank() } ?: fallback

/** `06 §2`: the one corner radius v2 gives a content card. */
private val WidgetCardRadius = 10.dp

/** How much of a folded payload's raw text an opened row shows before it scrolls. */
private val WidgetRawMaxHeight = 200.dp

/**
 * One `setWidget` line, drawn under the two rules in [widgetRow].
 *
 * `maxLines = 1` is not a style choice: pi's own terminal panel truncates a widget
 * line to the panel width and never wraps it (`truncLine`, `tui/render.js`), so
 * wrapping was already a divergence from the original — and that divergence is
 * what let one 32 KB payload line become twenty-five rows of the conversation.
 *
 * The classification is remembered per line, for the same reason the spans are:
 * this stack recomposes on every 200 ms publication while anything streams, and a
 * widget that did not change should cost neither a regex nor a parse. It also puts
 * a ceiling on `Ansi.parse`'s input, which is the other half of the same problem.
 *
 * A line whose row is `null` is a consumed data channel (see
 * `ExtensionWidgetLines.HIDDEN_PAYLOAD_PREFIXES`) and draws nothing.
 */
@Composable
private fun WidgetLineRow(line: String, index: Int) {
    val row = remember(line) { widgetRow(line) } ?: return
    when (row) {
        is WidgetRow.Text -> {
            val spans = remember(row.text) { chromeSpans(row.text) }
            ExtensionSpans(
                spans = if (spans.all { it.text.isEmpty() }) listOf(Ansi.Span(" ")) else spans,
                defaultColor = PiTheme.palette.muted,
                style = PiTheme.text.monoSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is WidgetRow.Folded -> WidgetFoldedRow(label = row.label, raw = row.raw, index = index)

        is WidgetRow.Summary ->
            WidgetFoldedRow(label = row.headline, raw = row.raw, index = index, details = row.details)
    }
}

/**
 * A folded payload: one stable row, with the raw text one tap away.
 *
 * The label is the entire visible surface, which is what makes the flicker
 * impossible rather than merely smaller: it comes from the prefix or from the
 * summary's counts, never from the payload's volatile fields (`generatedAt`,
 * `updatedAt`), so an extension that re-sends its payload on every status tick
 * re-renders the same string.
 *
 * The raw text is plain [Text] and **not** [ExtensionSpans]: that is the point of
 * folding — `Ansi.parse` never sees the 32 KB — and it scrolls inside a bounded
 * height instead of becoming the panel's own layout.
 */
@Composable
private fun WidgetFoldedRow(
    label: String,
    raw: String,
    index: Int,
    details: List<String> = emptyList(),
) {
    val palette = PiTheme.palette
    // Keyed by row position and not by the line: keying by the line would collapse
    // the viewer every time the payload the user is reading gets refreshed.
    val open = remember(index) { mutableStateOf(false) }
    // The row is 44dp tall because it is the only tap target an extension payload gets, and a
    // 12sp line of text is not one. V2's rows are all at least this tall; the number is the
    // Android minimum touch target rather than a taste call.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { open.value = !open.value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (open.value) "▾" else "▸",
            color = palette.dim,
            style = PiTheme.text.monoSmall,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = palette.accent,
            style = PiTheme.text.monoSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (open.value) "收起" else "点开",
            color = palette.dim,
            style = PiTheme.text.monoSmall,
        )
    }
    if (!open.value) return
    details.forEach { detail ->
        val spans = remember(detail) { chromeSpans(detail) }
        ExtensionSpans(
            spans = spans,
            defaultColor = palette.muted,
            style = PiTheme.text.monoSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        text = raw,
        color = palette.muted,
        style = PiTheme.text.monoSmall,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = WidgetRawMaxHeight)
            .verticalScroll(rememberScrollState()),
    )
}
