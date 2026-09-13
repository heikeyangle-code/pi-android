package app.pi.ui.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pi.rpc.Notice
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing

/**
 * Single-line status rows (auto-retry, extension failures) and the fallback for
 * an item kind this build does not know. Not one of pi's 14 content blocks —
 * these are App chrome, so they stay deliberately quiet.
 *
 * The prefix is the row's **symbol channel**, and it carries the tone like every
 * other state in the app: `·` for an ordinary note, `!` for a warning, `✗` for an
 * error (`05 §3.3`; the same three glyphs `06 §4` gives the Snackbar, which is the
 * same shape on a different surface). The words are the notice's own text and the
 * colour is the third channel (`muted` / `warning` / `error`), so a greyscale
 * screenshot still separates the three. The clock stays in the metadata colour
 * rather than the tone: it is a reading, not a state.
 */
@Composable
fun NoticeBlock(
    item: Notice,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val color = when (item.tone) {
        Notice.Tone.Info -> palette.muted
        Notice.Tone.Warning -> palette.warning
        Notice.Tone.Error -> palette.error
    }
    val glyph = when (item.tone) {
        Notice.Tone.Info -> "·"
        Notice.Tone.Warning -> "!"
        Notice.Tone.Error -> "✗"
    }
    BlockColumn(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = glyph,
                style = PiTheme.text.mono,
                color = color,
            )
            Spacer(Modifier.width(PiSpacing.inline))
            Text(
                text = item.text.ifEmpty { "（无内容）" },
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = color,
            )
            Spacer(Modifier.width(PiSpacing.inline))
            Text(
                text = formatClock(item.ts),
                style = PiTheme.text.meta,
                color = palette.metaOnCanvas,
            )
        }
    }
}
