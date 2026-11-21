package app.pi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.ExportedSession
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn

/** 输入框上方的状态行：压缩中止、队列排队卡、导出交付卡。 */

private const val PENDING_MESSAGE_LINES = 3

/** 输入框上方的一行状态卡：状态点 + 文案 + 胶囊动作按钮。 */
@Composable
internal fun StatusActionRow(text: String, actionLabel: String, onAction: () -> Unit) {
    val palette = PiTheme.palette
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(999.dp),
                color = palette.accent.copy(alpha = 0.14f),
            ) {
                Text(
                    text = actionLabel,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.accent,
                )
            }
        }
    }
}

/** 排队中的引导 / 后续消息悬浮卡：清晰区分引导（黄）与后续（蓝），一键全部收回编辑。 */
@Composable
internal fun QueueRow(
    steering: Int,
    followUp: Int,
    steeringTexts: List<String>,
    followUpTexts: List<String>,
    onRestore: () -> Unit,
) {
    val palette = PiTheme.palette
    val pendingItems = buildList {
        steeringTexts.filter { it.isNotBlank() }.forEach { add(Triple(tr("引导", "Steer"), palette.warning, it)) }
        followUpTexts.filter { it.isNotBlank() }.forEach { add(Triple(tr("后续", "Follow-up"), palette.accent, it)) }
    }
    val visibleItems = pendingItems.take(PENDING_MESSAGE_LINES)
    val overflow = (pendingItems.size - visibleItems.size).coerceAtLeast(0)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin)
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (steering > 0) palette.warning.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (steering > 0) {
                    QueueCountPill(glyph = "⇢", label = tr("引导 $steering", "Steer $steering"), tone = palette.warning)
                }
                if (followUp > 0) {
                    QueueCountPill(glyph = "⇣", label = tr("后续 $followUp", "Follow-up $followUp"), tone = palette.accent)
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = onRestore,
                    shape = RoundedCornerShape(999.dp),
                    color = palette.accent.copy(alpha = 0.14f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = tr("收回并编辑", "Restore & edit"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.accent,
                        )
                    }
                }
            }
            if (visibleItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    visibleItems.forEach { (tag, tone, body) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(tone),
                            )
                            Text(
                                text = tag,
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = tone,
                                maxLines = 1,
                            )
                            Text(
                                text = body,
                                modifier = Modifier.weight(1f),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (overflow > 0) {
                        Text(
                            text = tr("还有 $overflow 条排队中…", "$overflow more queued…"),
                            style = PiTheme.text.meta,
                            color = palette.dim,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueCountPill(glyph: String, label: String, tone: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Bold),
            color = tone,
        )
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = tone,
            maxLines = 1,
        )
    }
}

/** `/export` 导出交付卡：一键存至 Download、分享或关闭。 */
@Composable
internal fun ExportDeliveryRow(
    exported: ExportedSession,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, palette.success.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.success.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = palette.success,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        tr("会话已导出", "Session exported"),
                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        exported.name,
                        style = PiTheme.text.monoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = tr("关闭", "Dismiss"), onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("关闭", "Dismiss"),
                        tint = palette.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = onSave,
                    shape = RoundedCornerShape(999.dp),
                    color = palette.accent,
                ) {
                    Text(
                        tr("保存到 Download", "Save to Downloads"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.contrastOn(palette.accent),
                    )
                }
                Surface(
                    onClick = onShare,
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        tr("分享文件", "Share file"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
