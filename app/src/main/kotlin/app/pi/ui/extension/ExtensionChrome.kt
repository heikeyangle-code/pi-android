package app.pi.ui.extension

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
                    boundedWidgetLines(widget.lines).forEach { line ->
                        // One parse per line, not two. `chromeText(line).isEmpty()` used to
                        // strip the whole line (allocating a second copy of it) just to
                        // answer a yes/no question, and the answer is already in the spans:
                        // `Ansi.parse` returns one span per styled run, so "paints no
                        // glyphs" is "every run is empty". Reading the blank test off the
                        // spans also means the test and the drawing cannot disagree about a
                        // line made only of colour escapes — they were two parses of the
                        // same string.
                        //
                        // A blank line is meaningful spacing in a text widget, which a
                        // Text("") would collapse to zero height. A line that is nothing
                        // but colour escapes is blank in the same sense — it paints no
                        // glyphs — so it takes the same path.
                        val spans = chromeSpans(line)
                        ExtensionSpans(
                            spans = if (spans.all { it.text.isEmpty() }) listOf(Ansi.Span(" ")) else spans,
                            defaultColor = palette.muted,
                            style = PiTheme.text.monoSmall,
                        )
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
