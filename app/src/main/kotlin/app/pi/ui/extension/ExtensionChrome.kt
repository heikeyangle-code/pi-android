package app.pi.ui.extension

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
import app.pi.rpc.Ansi
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
 * `setWidget` panels live in `ExtensionWidgetCard.kt` now, as a sibling of the tool
 * card: the same `app.pi.ui.blocks` furniture, its own title row and stripe. The
 * rules that decide *what* a widget line is are in `ExtensionWidgetLines.kt`.
 */

/** The `setTitle` value, or the screen's own fallback when pi set none. */
fun windowTitleOf(title: String?, fallback: String): String = title?.takeIf { it.isNotBlank() } ?: fallback
