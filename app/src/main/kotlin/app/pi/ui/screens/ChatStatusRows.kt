package app.pi.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.QueueMode
import app.pi.ui.ExportedSession
import app.pi.ui.components.PiSpinner
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn

/** 输入框上方的统一精工停靠条：压缩/重试状态条、待发队列托盘、导出交付条。 */

private const val PENDING_MESSAGE_LINES = 3

/** 输入框上方的一行运行状态条（压缩上下文 / 自动重试）：自绘转环 + 清晰文案 + 克制细框中止钮。 */
@Composable
internal fun StatusActionRow(text: String, actionLabel: String, onAction: () -> Unit) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(13.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = 5.dp),
        shape = shape,
        color = palette.cardBg,
        border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.60f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            PiSpinner(
                size = 13.dp,
                strokeWidth = 1.5.dp,
                color = palette.muted,
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val btnShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .clip(btnShape)
                    .background(lerp(palette.pageBg, palette.cardBg, 0.35f))
                    .border(0.5.dp, palette.borderMuted.copy(alpha = 0.60f), btnShape)
                    .clickable(onClickLabel = actionLabel) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAction()
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 待发队列托盘（完全重做，对齐官方 Pi `steering` / `followUp` 队列与 `clearQueue` / 投递策略）：
 * - 单层轻薄卡片（与下方输入框共享 `palette.cardBg` 与 `0.5dp` 细边框，零套娃内盒、零彩色药丸堆砌）；
 * - 顶栏：紧凑方向摘要 + 可直达官方 `steeringMode`/`followUpMode` 设置的投递模式微标 + 极简「收回编辑」文字微钮；
 * - 列表：发丝分隔单行呈现，仅以自绘 10dp 极细方向箭头区分「引导（琥珀右箭）」与「后续（中性下箭）」。
 */
@Composable
internal fun QueueRow(
    steering: Int,
    followUp: Int,
    steeringTexts: List<String>,
    followUpTexts: List<String>,
    onRestore: () -> Unit,
    steeringMode: QueueMode = QueueMode.OneAtATime,
    followUpMode: QueueMode = QueueMode.OneAtATime,
    onOpenQueuePolicy: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val pendingItems = buildList {
        steeringTexts.filter { it.isNotBlank() }.forEach { add(QueuedPreviewRow(isSteer = true, text = it)) }
        followUpTexts.filter { it.isNotBlank() }.forEach { add(QueuedPreviewRow(isSteer = false, text = it)) }
    }
    val visibleItems = pendingItems.take(PENDING_MESSAGE_LINES)
    val overflow = (pendingItems.size - visibleItems.size).coerceAtLeast(0)

    val summaryParts = buildList {
        if (steering > 0) add(tr("引导 $steering", "Steer $steering"))
        if (followUp > 0) add(tr("后续 $followUp", "Follow-up $followUp"))
    }
    val activeMode = when {
        steering > 0 && followUp == 0 -> steeringMode
        followUp > 0 && steering == 0 -> followUpMode
        steeringMode == followUpMode -> steeringMode
        else -> null
    }
    val modeLabel = when (activeMode) {
        QueueMode.OneAtATime -> tr("逐条", "1-by-1")
        QueueMode.All -> tr("合并", "All")
        null -> tr("混合", "Mixed")
    }

    val shape = RoundedCornerShape(13.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = 5.dp),
        shape = shape,
        color = palette.cardBg,
        border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.60f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 极简顶栏：排队计数 + 投递模式直达微标 + 收回编辑
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 11.dp, end = 8.dp, top = 6.dp, bottom = if (visibleItems.isEmpty()) 6.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                QueueDirectionGlyph(
                    isSteer = steering > 0,
                    color = if (steering > 0) palette.warning else palette.muted,
                )
                Text(
                    text = summaryParts.joinToString(" · "),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onOpenQueuePolicy != null) {
                    val tagShape = RoundedCornerShape(6.dp)
                    Box(
                        modifier = Modifier
                            .clip(tagShape)
                            .background(lerp(palette.pageBg, palette.cardBg, 0.35f))
                            .border(0.5.dp, palette.borderMuted.copy(alpha = 0.50f), tagShape)
                            .clickable(onClickLabel = tr("切换队列投递方式", "Configure queue delivery mode")) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenQueuePolicy()
                            }
                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                    ) {
                        Text(
                            text = modeLabel,
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(onClickLabel = tr("收回排队消息到输入框编辑", "Restore queued messages to composer")) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRestore()
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    QueueRestoreGlyph(color = palette.muted)
                    Text(
                        text = tr("收回编辑", "Restore"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }

            if (visibleItems.isNotEmpty()) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = palette.borderMuted.copy(alpha = 0.40f),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    visibleItems.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            QueueDirectionGlyph(
                                isSteer = entry.isSteer,
                                color = if (entry.isSteer) palette.warning else palette.muted.copy(alpha = 0.78f),
                            )
                            Text(
                                text = entry.text.replace('\n', ' '),
                                modifier = Modifier.weight(1f),
                                style = PiTheme.text.meta,
                                color = palette.text.copy(alpha = 0.86f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (overflow > 0) {
                        Text(
                            text = tr("还有 $overflow 条排队中…", "+$overflow more queued…"),
                            style = PiTheme.text.monoSmall,
                            color = palette.dim,
                            modifier = Modifier.padding(start = 17.dp, top = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class QueuedPreviewRow(
    val isSteer: Boolean,
    val text: String,
)

@Composable
private fun QueueDirectionGlyph(
    isSteer: Boolean,
    color: Color,
) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val stroke = 1.35.dp.toPx()
        val pad = 1.4.dp.toPx()
        val midY = size.height * 0.5f
        val midX = size.width * 0.5f
        if (isSteer) {
            // 引导：简洁右指插队箭
            drawLine(
                color = color,
                start = Offset(pad, midY),
                end = Offset(size.width - pad, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            val head = 2.8.dp.toPx()
            val path = Path().apply {
                moveTo(size.width - pad - head, midY - head)
                lineTo(size.width - pad, midY)
                lineTo(size.width - pad - head, midY + head)
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            // 后续：简洁下指排队箭
            drawLine(
                color = color,
                start = Offset(midX, pad),
                end = Offset(midX, size.height - pad),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            val head = 2.8.dp.toPx()
            val path = Path().apply {
                moveTo(midX - head, size.height - pad - head)
                lineTo(midX, size.height - pad)
                lineTo(midX + head, size.height - pad - head)
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun QueueRestoreGlyph(color: Color) {
    Canvas(modifier = Modifier.size(11.dp)) {
        val stroke = 1.3.dp.toPx()
        val pad = 1.5.dp.toPx()
        val topY = size.height * 0.36f
        val botY = size.height * 0.78f
        val path = Path().apply {
            moveTo(pad + 2.4.dp.toPx(), topY - 2.2.dp.toPx())
            lineTo(pad, topY)
            lineTo(pad + 2.4.dp.toPx(), topY + 2.2.dp.toPx())
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val stem = Path().apply {
            moveTo(pad, topY)
            lineTo(size.width - pad - 2.2.dp.toPx(), topY)
            quadraticTo(size.width - pad, topY, size.width - pad, topY + 2.2.dp.toPx())
            lineTo(size.width - pad, botY)
        }
        drawPath(stem, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** `/export` 导出交付条：与输入框同源的克制单卡。 */
@Composable
internal fun ExportDeliveryRow(
    exported: ExportedSession,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(13.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = 5.dp),
        shape = shape,
        color = palette.cardBg,
        border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.60f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Canvas(modifier = Modifier.size(16.dp)) {
                    val stroke = 1.5.dp.toPx()
                    drawCircle(
                        color = palette.success.copy(alpha = 0.16f),
                        radius = size.minDimension * 0.5f,
                    )
                    val check = Path().apply {
                        moveTo(size.width * 0.27f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.68f)
                        lineTo(size.width * 0.74f, size.height * 0.34f)
                    }
                    drawPath(
                        path = check,
                        color = palette.success,
                        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        tr("会话已导出", "Session exported"),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.text,
                    )
                    Text(
                        exported.name,
                        style = PiTheme.text.monoSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = tr("关闭", "Dismiss"), onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭", "Dismiss"),
                        tint = palette.muted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val actionShape = RoundedCornerShape(8.dp)
                Surface(
                    onClick = onSave,
                    shape = actionShape,
                    color = palette.text,
                ) {
                    Text(
                        tr("保存到 Download", "Save to Downloads"),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.contrastOn(palette.text),
                    )
                }
                Surface(
                    onClick = onShare,
                    shape = actionShape,
                    color = lerp(palette.pageBg, palette.cardBg, 0.35f),
                    border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.60f)),
                ) {
                    Text(
                        tr("分享文件", "Share file"),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = palette.text,
                    )
                }
            }
        }
    }
}
