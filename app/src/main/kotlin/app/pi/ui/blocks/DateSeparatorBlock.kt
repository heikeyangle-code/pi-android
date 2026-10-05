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
            // add another `horizontal = PiSpacing.pageHorizontal` on top of it, which is
            // what left this kind at 32 dp while every `BlockColumn` block moved
            // to 16 dp. Its own 9 dp above and below stays — a date rule wants more air than
            // a paragraph does.
            .padding(vertical = PiSpacing.unit / 2)
            // …but the **row gap is still the row's own** (补 2 in
            // `/root/ui-redesign/施工补充.md`): every transcript row gives its own bottom air,
            // and this is the one row that does not go through `BlockColumn`, so it has to read
            // the same value out of `LocalRowGap` itself. Without this line the separator kept
            // 13 dp above (the previous row's own gap + 9) and only 9 below — this block used to
            // lean on the list's `Arrangement.spacedBy`, which no longer exists.
            .padding(bottom = LocalRowGap.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = PiSpacing.hairline,
            color = palette.borderMuted.copy(alpha = 0.5f),
        )
        Text(
            text = item.label.ifEmpty { formatClock(item.ts) },
            modifier = Modifier.padding(horizontal = PiSpacing.inner),
            style = PiTheme.text.meta,
            color = palette.metaOnCanvas,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = PiSpacing.hairline,
            color = palette.borderMuted.copy(alpha = 0.5f),
        )
    }
}
