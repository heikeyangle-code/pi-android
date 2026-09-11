package app.pi.ui.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.rpc.DateSeparator
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `date-separator` (docs/pi-android-ui-spec.md §7.4): a centred hairline with the
 * day label. The reducer inserts one whenever the stream crosses a day boundary,
 * so timestamps do not have to repeat on every block.
 */
@Composable
fun DateSeparatorBlock(
    item: DateSeparator,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            // F11 (`docs/rendering-review.md`): the page margin is the
            // `LazyColumn`'s `contentPadding`, not the block's; this row used to
            // add another `horizontal = PiSpacing.screen` on top of it, which is
            // what left this kind at 32 dp while every `BlockColumn` block moved
            // to 16 dp. Vertical rhythm stays here: this block is outside the
            // list's block spacing by design.
            .padding(vertical = PiSpacing.unit / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = palette.borderMuted.copy(alpha = 0.5f),
        )
        Text(
            text = item.label.ifEmpty { formatClock(item.ts) },
            modifier = Modifier.padding(horizontal = 10.dp),
            style = PiTheme.text.meta,
            color = palette.dim,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = palette.borderMuted.copy(alpha = 0.5f),
        )
    }
}
