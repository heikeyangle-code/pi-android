package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.ModelChange
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.numeric

/**
 * `model-change` (docs/pi-android-ui-spec.md §7.4): one 32dp muted line —
 * 模型切换 → the new model in `borderAccent`. It is a boundary in the stream, not
 * a message, so it carries no container; tapping it opens the model picker.
 */
@Composable
fun ModelChangeBlock(
    item: ModelChange,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val name = item.modelId.ifEmpty { item.provider ?: "未知模型" }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // F11 (`docs/rendering-review.md`): the page margin is the
            // `LazyColumn`'s `contentPadding`, not the block's; this row used to
            // add another `horizontal = PiSpacing.pageHorizontal` on top of it, which is
            // what left this kind at 32 dp while every `BlockColumn` block moved
            // to 16 dp. The vertical half stays: this block is outside the list's
            // block spacing by design.
            .padding(vertical = PiSpacing.unit / 2)
            .height(PiSpacing.statusRow)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "模型切换 →",
            style = PiTheme.text.meta,
            color = palette.metaOnCanvas,
        )
        Spacer(Modifier.width(PiSpacing.inline))
        Text(
            text = name,
            // `06 §3` 构件 10「模型切换行：单行「模型切换 →」+ 模型 id（borderAccent 色）」
            // and `06 §2`'s machine layer: a model id is machine language, and v2 sets it
            // `mono t13` (`direction-b-v2.html:2601`). It used to be `labelLarge` (14 sp,
            // sans), which broke the "two voices" rule on the one row that is *only* an id.
            style = PiTheme.text.mono,
            color = palette.borderAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val provider = item.provider
        if (!provider.isNullOrBlank()) {
            Spacer(Modifier.width(PiSpacing.inline))
            Text(
                text = provider,
                style = PiTheme.text.meta,
                color = palette.metaOnCanvas,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        // `06 §3` 构件 10's last part: the row's own time, at the far right in the machine
        // face (`mono t12 c-muted`, v2 `direction-b-v2.html:2604`). `item.ts` is pi's own
        // entry timestamp, the same field every other timestamped row prints.
        Text(
            text = formatClock(item.ts),
            style = PiTheme.text.numeric,
            color = palette.metaOnCanvas,
            maxLines = 1,
        )
    }
}
