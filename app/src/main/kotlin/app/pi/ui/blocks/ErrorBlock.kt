package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.ErrorText
import app.pi.ui.chat.LocalMessageActions
import app.pi.ui.theme.PiTheme

/**
 * 分级状态/错误块：
 * - [ErrorText.Kind.Aborted]（用户主动点停止）：绝不渲染刺眼红卡，保持对齐官方 Pi 记录语义的平静单行微条 + 可选轻重试；
 * - [ErrorText.Kind.Truncated]（达到输出上限）：温和琥珀细边提示条；
 * - [ErrorText.Kind.Error]（真实请求/扩展/重试失败）：克制编辑感报错卡（中性底 + 左侧 2.5dp 语义红边 + 高对比正文 + 细节折叠区）。
 */
@Composable
fun ErrorBlock(
    item: ErrorText,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val actions = LocalMessageActions.current
    val canRetry = actions != null && actions.retryKey == item.key

    when (item.kind) {
        ErrorText.Kind.Aborted -> {
            BlockColumn(modifier) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PiAbortedGlyph(color = palette.muted.copy(alpha = 0.78f))
                    Text(
                        text = item.message.ifBlank { tr("已停止生成", "Generation stopped") },
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                    if (canRetry) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(
                                    onClickLabel = tr("重新生成", "Regenerate"),
                                    onClick = actions!!.retry,
                                )
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PiRetryArrowGlyph(color = palette.muted)
                            Text(
                                text = tr("重新生成", "Regenerate"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            return
        }

        ErrorText.Kind.Truncated -> {
            val tone = palette.warning
            val cardBg = lerp(palette.cardBg, tone, 0.045f)
            val shape = RoundedCornerShape(11.dp)
            BlockColumn(modifier) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    color = cardBg,
                    border = BorderStroke(0.5.dp, tone.copy(alpha = 0.30f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawRect(
                                    color = tone.copy(alpha = 0.85f),
                                    topLeft = Offset.Zero,
                                    size = Size(2.5.dp.toPx(), size.height),
                                )
                            }
                            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PiAlertCircleGlyph(color = tone)
                        Text(
                            text = item.message.ifBlank { tr("回复已达输出上限", "Response truncated at token limit") },
                            modifier = Modifier.weight(1f),
                            style = PiTheme.text.meta,
                            color = palette.text,
                        )
                        if (canRetry) {
                            RestrainedRetryChip(
                                label = tr("重试", "Retry"),
                                onClick = actions!!.retry,
                            )
                        }
                    }
                }
            }
            return
        }

        ErrorText.Kind.Error -> Unit
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val detail = item.detail
    val expandable = !detail.isNullOrBlank() || item.message.length > ERROR_PREVIEW_CHARS ||
        item.message.count { it == '\n' } >= ERROR_PREVIEW_LINES

    val copyText = remember(item.message, detail) {
        if (detail.isNullOrBlank()) item.message else "${item.message}\n\n$detail"
    }
    val tone = palette.error
    val cardBg = lerp(palette.cardBg, tone, 0.045f)
    val shape = RoundedCornerShape(12.dp)

    ToolActionMenu(command = null, output = copyText, fullOutputPath = null) {
        BlockColumn(modifier) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = cardBg,
                border = BorderStroke(0.5.dp, tone.copy(alpha = 0.28f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawRect(
                                color = tone.copy(alpha = 0.85f),
                                topLeft = Offset.Zero,
                                size = Size(2.5.dp.toPx(), size.height),
                            )
                        }
                        .padding(start = 13.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }, enabled = expandable) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                PiAlertCircleGlyph(color = tone)
                                Text(
                                    text = tr("请求未完成", "Request failed"),
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                    color = tone,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.weight(1f))
                                if (expandable) {
                                    ExpandLabel(expanded, color = palette.muted)
                                }
                            }

                            if (item.message.isNotBlank()) {
                                ProseText(
                                    text = item.message,
                                    color = palette.text,
                                    maxLines = if (expanded) Int.MAX_VALUE else ERROR_PREVIEW_LINES,
                                )
                            }

                            if (expanded && !detail.isNullOrBlank()) {
                                val detailShape = RoundedCornerShape(8.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(detailShape)
                                        .background(lerp(palette.pageBg, palette.cardBg, 0.35f))
                                        .border(0.5.dp, palette.borderMuted.copy(alpha = 0.5f), detailShape)
                                        .padding(horizontal = 9.dp, vertical = 7.dp),
                                ) {
                                    MonoText(
                                        text = detail,
                                        color = palette.muted,
                                    )
                                }
                            }
                        }
                    }

                    if (canRetry) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RestrainedRetryChip(
                                label = tr("重试上一条消息", "Retry last message"),
                                onClick = actions!!.retry,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestrainedRetryChip(
    label: String,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(lerp(palette.pageBg, palette.cardBg, 0.35f))
            .border(0.5.dp, palette.borderMuted.copy(alpha = 0.65f), shape)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PiRetryArrowGlyph(color = palette.text)
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = palette.text,
            maxLines = 1,
        )
    }
}

@Composable
private fun PiAbortedGlyph(color: Color) {
    Canvas(modifier = Modifier.size(11.dp)) {
        val stroke = 1.25.dp.toPx()
        val inset = stroke * 0.7f
        val side = size.minDimension - inset * 2f
        val r = 2.2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(side, side),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun PiAlertCircleGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 1.35.dp.toPx()
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val r = (size.minDimension - stroke) * 0.48f
        drawCircle(
            color = color,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(cx, size.height * 0.28f),
            end = Offset(cx, size.height * 0.55f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color,
            radius = stroke * 0.58f,
            center = Offset(cx, size.height * 0.72f),
        )
    }
}

@Composable
private fun PiRetryArrowGlyph(color: Color) {
    Canvas(modifier = Modifier.size(12.dp)) {
        val stroke = 1.35.dp.toPx()
        val inset = 1.6.dp.toPx()
        val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val tipX = size.width - inset * 0.9f
        val tipY = inset + arcSize.height * 0.24f
        val head = 2.7.dp.toPx()
        val arrow = Path().apply {
            moveTo(tipX - head, tipY - head * 0.15f)
            lineTo(tipX, tipY)
            lineTo(tipX - head * 0.15f, tipY - head)
        }
        drawPath(
            path = arrow,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private const val ERROR_PREVIEW_LINES = 3
private const val ERROR_PREVIEW_CHARS = 140
