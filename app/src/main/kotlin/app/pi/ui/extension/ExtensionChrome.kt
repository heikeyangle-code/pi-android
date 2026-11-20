package app.pi.ui.extension

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
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
 * setStatus 是一行可横滑的小标签（`ExtensionStatusStrip`，在 ExtensionPanels.kt）；数据由宿主的 `ui.status` 事件进 `UiState.ext.statuses`。
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
    /** 为 TUI 表面 / 面板启用完整 SGR 样式（背景高亮、反显光标、粗体、斜体、下划线、删除线、dim）。 */
    richStyle: Boolean = false,
) {
    val palette = PiTheme.palette
    // One candidate array per palette, not one per span: `toArgb()` is not free
    // and a widget can carry dozens of spans.
    val tokens = remember(palette) { palette.themeTokenArgb() }
    val surfaceBg = palette.cardBg
    // Keyed on the spans themselves: `Ansi.Span` is a data class, so an unchanged
    // string re-renders nothing when the caller recomposes.
    val text: AnnotatedString = remember(spans, tokens, defaultColor, surfaceBg, richStyle) {
        buildAnnotatedString {
            spans.forEach { span ->
                val resolvedFg = tokenColorFor(span, tokens) ?: if (span.link != null) palette.mdLink else defaultColor
                val safeUrl = span.link?.trim()?.takeIf {
                    it.startsWith("https://", ignoreCase = true) ||
                        it.startsWith("http://", ignoreCase = true) ||
                        it.startsWith("mailto:", ignoreCase = true)
                }
                val spanStyle = if (!richStyle) {
                    SpanStyle(
                        color = resolvedFg,
                        textDecoration = if (span.link != null) TextDecoration.Underline else null,
                    )
                } else {
                    val resolvedBg = backgroundColorFor(span, tokens)
                    val fg = if (span.inverse) (resolvedBg ?: surfaceBg) else resolvedFg
                    val bg = if (span.inverse) resolvedFg else (resolvedBg ?: Color.Unspecified)
                    val finalFg = if (span.dim) fg.copy(alpha = 0.65f) else fg
                    val wantUnderline = span.underline || span.link != null
                    val decoration = when {
                        wantUnderline && span.strikethrough -> TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                        )
                        wantUnderline -> TextDecoration.Underline
                        span.strikethrough -> TextDecoration.LineThrough
                        else -> null
                    }
                    SpanStyle(
                        color = finalFg,
                        background = bg,
                        fontWeight = if (span.bold) FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        textDecoration = decoration,
                    )
                }
                if (safeUrl != null) {
                    withLink(
                        LinkAnnotation.Url(
                            url = safeUrl,
                            styles = TextLinkStyles(style = spanStyle),
                        ),
                    ) {
                        append(span.text)
                    }
                } else {
                    withStyle(spanStyle) {
                        append(span.text)
                    }
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
 * `setWidget` panels live in `ExtensionPanels.kt` now, as a sibling of the tool
 * card: the same `app.pi.ui.blocks` furniture, its own title row and stripe. The
 * rules that decide *what* a widget line is are in `ExtensionPanelRules.kt`.
 */

/** The `setTitle` value, or the screen's own fallback when pi set none. */
fun windowTitleOf(title: String?, fallback: String): String = title?.takeIf { it.isNotBlank() } ?: fallback
