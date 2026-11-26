package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.Notice
import app.pi.ui.theme.PiTheme

/** 单行状态提示（自动重试、扩展提示、系统告警）：轻量微色带胶囊。 */
@Composable
fun NoticeBlock(
    item: Notice,
    modifier: Modifier = Modifier,
    showTimestamps: Boolean = false,
) {
    val palette = PiTheme.palette
    val color = when (item.tone) {
        Notice.Tone.Info -> palette.dim
        Notice.Tone.Warning -> palette.warning
        Notice.Tone.Error -> palette.error
    }
    val bg = when (item.tone) {
        Notice.Tone.Info -> Color.Transparent
        Notice.Tone.Warning -> palette.warning.copy(alpha = 0.08f)
        Notice.Tone.Error -> palette.error.copy(alpha = 0.09f)
    }
    val border = when (item.tone) {
        Notice.Tone.Info -> null
        Notice.Tone.Warning -> BorderStroke(0.5.dp, palette.warning.copy(alpha = 0.28f))
        Notice.Tone.Error -> BorderStroke(0.5.dp, palette.error.copy(alpha = 0.32f))
    }

    BlockColumn(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = bg,
            border = border,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (item.tone == Notice.Tone.Info) 2.dp else 10.dp,
                        vertical = if (item.tone == Notice.Tone.Info) 2.dp else 7.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                        val stroke = 1.5.dp.toPx()
                        when (item.tone) {
                            Notice.Tone.Info -> {
                                drawCircle(color = color, radius = 2.dp.toPx())
                            }
                            Notice.Tone.Warning -> {
                                val cx = size.width / 2f
                                drawLine(
                                    color = color,
                                    start = androidx.compose.ui.geometry.Offset(cx, size.height * 0.14f),
                                    end = androidx.compose.ui.geometry.Offset(cx, size.height * 0.62f),
                                    strokeWidth = stroke,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                                drawCircle(
                                    color = color,
                                    radius = stroke * 0.58f,
                                    center = androidx.compose.ui.geometry.Offset(cx, size.height * 0.84f),
                                )
                            }
                            Notice.Tone.Error -> {
                                val pad = size.width * 0.22f
                                drawLine(
                                    color = color,
                                    start = androidx.compose.ui.geometry.Offset(pad, pad),
                                    end = androidx.compose.ui.geometry.Offset(size.width - pad, size.height - pad),
                                    strokeWidth = stroke,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                                drawLine(
                                    color = color,
                                    start = androidx.compose.ui.geometry.Offset(size.width - pad, pad),
                                    end = androidx.compose.ui.geometry.Offset(pad, size.height - pad),
                                    strokeWidth = stroke,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = item.text.ifEmpty { tr("（无内容）", "(Empty)") },
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta,
                    color = color,
                )
                if (showTimestamps) {
                    Text(
                        text = formatClock(item.ts),
                        style = PiTheme.text.monoSmall,
                        color = palette.metaOnCanvas,
                    )
                }
            }
        }
    }
}
