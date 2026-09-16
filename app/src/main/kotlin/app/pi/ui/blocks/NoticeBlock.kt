package app.pi.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * ## The key/value rows under a `custom` entry
 *
 * An extension writes whatever it likes through `ctx.ui.appendEntry(customType,
 * data)`, and until now this block drew that payload as one compacted JSON line —
 * the user could see that *something* was there and nothing about what. [Notice.rows]
 * carries the first level of that object as key/value pairs, and this block draws
 * them under the line: the key in the metadata colour, right-aligned, the value in
 * the body colour, wrapping rather than truncated.
 *
 * The **single line stays exactly as it was**, above the rows: it is the entry's own
 * text (and the transcript search index and the session export read it). The rows are
 * added *under* it, never instead of it, and a notice without rows renders exactly
 * what it rendered before.
 *
 * Everything here is read-only rendering. The keys are the extension's own spelling
 * and the values are JSON's own text (already flattened and length-capped in
 * `rpc/.../Transcript.kt`'s `entryDataRows`), so no meaning is invented for a field:
 * the RPC channel has no message that could carry an extension's own renderer, and
 * this is the honest half of that limit.
 */
@Composable
fun NoticeBlock(
    item: Notice,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    // pi's own informational status line is `dim`, and only its warning branch leaves that
    // token: `const color = status.type === "warning" ? "warning" : "dim";`
    // (`modes/interactive/interactive-mode.js:2866-2867`, `showManagedToolStatus`). The three
    // tones were `muted` / `warning` / `error`; `dim` is the one pi states for the Info case,
    // and the palette keeps the two tokens distinct for a hand-written theme.
    val color = when (item.tone) {
        Notice.Tone.Info -> palette.dim
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
        if (item.rows.isNotEmpty()) {
            // Indented past the symbol column so the rows read as the same entry's
            // contents rather than as new rows of the transcript.
            Column(modifier = Modifier.padding(start = PiSpacing.inner)) {
                item.rows.forEach { (key, value) ->
                    Row(modifier = Modifier.padding(top = PiSpacing.tiny)) {
                        Text(
                            text = key,
                            modifier = Modifier.weight(NOTICE_KEY_WEIGHT),
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(PiSpacing.inline))
                        Text(
                            text = value,
                            modifier = Modifier.weight(NOTICE_VALUE_WEIGHT),
                            style = PiTheme.text.monoSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            // No `maxLines`: a long value wraps. It is already capped
                            // in characters upstream, and clipping it here would hide
                            // the half a user is looking for.
                            softWrap = true,
                        )
                    }
                }
            }
        }
    }
}

/** The two columns of a [Notice.rows] line; the value gets the wider half. */
private const val NOTICE_KEY_WEIGHT = 0.4f
private const val NOTICE_VALUE_WEIGHT = 0.6f
