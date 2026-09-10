package app.pi.ui.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.rpc.Notice
import app.pi.ui.theme.PiTheme

/**
 * Single-line status rows (auto-retry, extension failures) and the fallback for
 * an item kind this build does not know. Not one of pi's 14 content blocks —
 * these are App chrome, so they stay deliberately quiet.
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
    BlockColumn(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "·",
                style = PiTheme.text.mono,
                color = color,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.text.ifEmpty { "（无内容）" },
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = color,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatClock(item.ts),
                style = PiTheme.text.meta,
                color = palette.dim,
            )
        }
    }
}
