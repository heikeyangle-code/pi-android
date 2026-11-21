package app.pi.ui.blocks

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.ErrorText
import app.pi.ui.chat.LocalMessageActions
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 现代错误卡片：柔和错误底色 + 1px 警示描边 + 圆形错误徽章 + 醒目胶囊重试按钮。
 */
@Composable
fun ErrorBlock(
    item: ErrorText,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    var expanded by rememberSaveable { mutableStateOf(false) }
    val detail = item.detail
    val expandable = !detail.isNullOrBlank() || item.message.length > ERROR_PREVIEW_CHARS ||
        item.message.count { it == '\n' } >= ERROR_PREVIEW_LINES

    BlockColumn(modifier) {
        BlockCard(
            color = palette.toolErrorBg,
            borderColor = palette.error.copy(alpha = 0.26f),
            padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }, enabled = expandable) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(palette.error.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✗",
                                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Bold),
                                color = palette.error,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = tr("执行出错", "Execution Error"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.error,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        if (expandable) {
                            ExpandLabel(expanded, color = palette.error)
                        }
                    }

                    if (item.message.isNotBlank()) {
                        ProseText(
                            text = item.message,
                            color = palette.bodyOnTool,
                            maxLines = if (expanded) Int.MAX_VALUE else ERROR_PREVIEW_LINES,
                        )
                    }

                    val actions = LocalMessageActions.current
                    if (actions != null && actions.retryKey == item.key) {
                        Row(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(palette.accent.copy(alpha = 0.16f))
                                .clickable(onClickLabel = tr("重发上一条消息", "Resend last message"), onClick = actions.retry)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = palette.accent,
                            )
                            Text(
                                text = tr("重试上一条消息", "Retry last message"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.accent,
                            )
                        }
                    }

                    if (expanded && !detail.isNullOrBlank()) {
                        MonoText(
                            text = detail,
                            color = palette.bodyOnTool,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                    }
                }
            }
        }
    }
}

private const val ERROR_PREVIEW_LINES = 3
private const val ERROR_PREVIEW_CHARS = 140
