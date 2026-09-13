package app.pi.ui.extension

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * pi's TUI puts the status line in the footer and the widget next to the editor
 * (`docs/tui.md` §"Status indicators" / §"Widgets above/below editor"); the app
 * moved its footer to the top of the transcript so the keyboard cannot cover it
 * (docs/pi-android-ui-spec.md §4.1), so [ExtensionStatusRow] belongs under the
 * AppBar and [ExtensionWidgetStack] around the composer.
 */

/**
 * `setStatus` entries, one row, horizontally scrollable.
 *
 * pi's status is a *footer*: dim chrome, one line, as many keys as extensions
 * registered. Overflow scrolls instead of wrapping because a second line would
 * push the transcript down every time an extension updated a counter.
 *
 * Its inline margin is v2's page margin (`PiSpacing.pageHorizontal`, 14, `D1`): this
 * row sits directly under the AppBar, one line below the app's own status row, so
 * the two have to share a left edge. The `·` between
 * two entries is `muted`, not `dim`: v2 never draws `--dim` (its punctuation is
 * `--muted`), and `dim` on the canvas sits under the 3:1 the palette keeps for
 * meta text.
 */
@Composable
fun ExtensionStatusRow(
    statuses: List<ExtensionStatus>,
    modifier: Modifier = Modifier,
) {
    if (statuses.isEmpty()) return
    val palette = PiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PiSpacing.statusRow)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PiSpacing.pageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        statuses.forEachIndexed { index, status ->
            if (index > 0) {
                Text(
                    text = "·",
                    modifier = Modifier.padding(horizontal = 6.dp),
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                )
            }
            Text(
                text = status.text,
                style = PiTheme.text.monoSmall,
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `setWidget` panels, in registration order.
 *
 * Lines are rendered verbatim as monospace: `docs/rpc.md` restricts RPC widgets
 * to `widgetLines: string[]` (component factories are ignored in RPC mode), so
 * there is nothing to interpret — drawing them as anything richer would invent
 * structure pi never sent.
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
                    widget.lines.forEach { line ->
                        Text(
                            // A blank line is meaningful spacing in a text widget,
                            // which a Text("") would collapse to zero height.
                            text = line.ifEmpty { " " },
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
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
