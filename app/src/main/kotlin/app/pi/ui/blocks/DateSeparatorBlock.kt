package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.protocol.DateSeparator
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/** 日期分隔条：柔和细线夹着一枚微描边居中日期胶囊。 */
@Composable
fun DateSeparatorBlock(
    item: DateSeparator,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.5.dp,
            color = palette.borderMuted.copy(alpha = 0.45f),
        )
        Surface(
            modifier = Modifier.padding(horizontal = 10.dp),
            shape = RoundedCornerShape(999.dp),
            color = androidx.compose.ui.graphics.lerp(palette.pageBg, palette.cardBg, 0.72f),
            border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.55f)),
        ) {
            Text(
                text = item.label.ifEmpty { formatClock(item.ts) },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = palette.metaOnCanvas,
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.5.dp,
            color = palette.borderMuted.copy(alpha = 0.45f),
        )
    }
}
