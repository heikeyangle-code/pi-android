package app.pi.ui.blocks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.Notice
import app.pi.ui.extension.ExtensionSpans
import app.pi.ui.extension.chromeSpans
import app.pi.ui.extension.chromeText
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

private const val COLLAPSE_LINE_LIMIT = 4
private const val COLLAPSE_CHAR_LIMIT = 180

/**
 * 聊天流内的状态/通知块（对齐官方 Pi `chatContainer` 中的 `showStatus` / `showWarning` / `showError`）：
 * - 短通知保持极轻单行样式；
 * - 超过 4 行或 180 字的长通知自动折叠为前 4 行，点击原地展开/收起；
 * - 完整保留扩展 ANSI 样式，支持一键复制与长按复制纯文本（自动去 ANSI 码）。
 */
@Composable
fun NoticeBlock(
    item: Notice,
    modifier: Modifier = Modifier,
    showTimestamps: Boolean = false,
) {
    val palette = PiTheme.palette
    val clipboard = LocalClipboardManager.current
    val displayRaw = item.text.ifEmpty { tr("（无内容）", "(Empty)") }
    val spans = remember(displayRaw) { chromeSpans(displayRaw) }
    val plainText = remember(item.text) { chromeText(item.text) }
    val lineCount = remember(plainText) {
        if (plainText.isEmpty()) 1 else plainText.count { it == '\n' } + 1
    }
    val collapsible = lineCount > COLLAPSE_LINE_LIMIT || plainText.length > COLLAPSE_CHAR_LIMIT
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    var copied by remember(item.key) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200L)
            copied = false
        }
    }

    val copyPlainText: () -> Unit = {
        if (plainText.isNotEmpty()) {
            clipboard.setText(AnnotatedString(plainText))
            copied = true
        }
    }

    val iconColor = when (item.tone) {
        Notice.Tone.Info -> palette.muted
        Notice.Tone.Warning -> palette.warning
        Notice.Tone.Error -> palette.error
    }
    val textColor = when (item.tone) {
        Notice.Tone.Info -> if (collapsible) palette.text else palette.muted
        Notice.Tone.Warning, Notice.Tone.Error -> palette.text
    }
    val framed = item.tone != Notice.Tone.Info || collapsible
    val bg = when {
        item.tone == Notice.Tone.Warning -> lerp(palette.cardBg, palette.warning, 0.045f)
        item.tone == Notice.Tone.Error -> lerp(palette.cardBg, palette.error, 0.045f)
        collapsible -> lerp(palette.cardBg, palette.accent, 0.025f)
        else -> Color.Transparent
    }
    val border = when {
        item.tone == Notice.Tone.Warning -> BorderStroke(0.5.dp, palette.warning.copy(alpha = 0.28f))
        item.tone == Notice.Tone.Error -> BorderStroke(0.5.dp, palette.error.copy(alpha = 0.28f))
        collapsible -> BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.45f))
        else -> null
    }

    val actions = remember(plainText, item.key) {
        if (plainText.isEmpty()) {
            emptyList()
        } else {
            listOf(BlockAction(tr("复制", "Copy"), copyPlainText))
        }
    }

    BlockActionMenu(actions = actions) {
        BlockColumn(modifier) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = if (expanded) tr("收起通知", "Collapse notice") else tr("展开通知", "Expand notice"),
                            ) { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    ),
                shape = RoundedCornerShape(10.dp),
                color = bg,
                border = border,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (framed) 10.dp else 2.dp,
                            vertical = if (framed) 8.dp else 2.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = if (collapsible || lineCount > 1) Alignment.Top else Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = if (collapsible || lineCount > 1) 1.dp else 0.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(iconColor.copy(alpha = if (item.tone == Notice.Tone.Info) 0.12f else 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(10.dp)) {
                                val stroke = 1.5.dp.toPx()
                                when (item.tone) {
                                    Notice.Tone.Info -> {
                                        drawCircle(color = iconColor, radius = 2.dp.toPx())
                                    }
                                    Notice.Tone.Warning -> {
                                        val cx = size.width / 2f
                                        drawLine(
                                            color = iconColor,
                                            start = Offset(cx, size.height * 0.14f),
                                            end = Offset(cx, size.height * 0.62f),
                                            strokeWidth = stroke,
                                            cap = StrokeCap.Round,
                                        )
                                        drawCircle(
                                            color = iconColor,
                                            radius = stroke * 0.58f,
                                            center = Offset(cx, size.height * 0.84f),
                                        )
                                    }
                                    Notice.Tone.Error -> {
                                        val pad = size.width * 0.22f
                                        drawLine(
                                            color = iconColor,
                                            start = Offset(pad, pad),
                                            end = Offset(size.width - pad, size.height - pad),
                                            strokeWidth = stroke,
                                            cap = StrokeCap.Round,
                                        )
                                        drawLine(
                                            color = iconColor,
                                            start = Offset(size.width - pad, pad),
                                            end = Offset(pad, size.height - pad),
                                            strokeWidth = stroke,
                                            cap = StrokeCap.Round,
                                        )
                                    }
                                }
                            }
                        }

                        ExtensionSpans(
                            spans = spans,
                            defaultColor = textColor,
                            style = PiTheme.text.meta,
                            modifier = Modifier.weight(1f),
                            maxLines = if (collapsible && !expanded) COLLAPSE_LINE_LIMIT else Int.MAX_VALUE,
                            overflow = if (collapsible && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                            richStyle = true,
                        )

                        if (plainText.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = tr("复制通知", "Copy notice"),
                                        onClick = copyPlainText,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                    contentDescription = tr("复制", "Copy"),
                                    tint = if (copied) palette.success else palette.muted.copy(alpha = 0.68f),
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }

                        if (showTimestamps) {
                            Text(
                                text = formatClock(item.ts),
                                style = PiTheme.text.monoSmall,
                                color = palette.metaOnCanvas,
                            )
                        }
                    }

                    if (collapsible) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 26.dp),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (expanded) {
                                    tr("收起", "Collapse")
                                } else if (lineCount > COLLAPSE_LINE_LIMIT) {
                                    tr("展开全部（共 $lineCount 行）", "Expand all ($lineCount lines)")
                                } else {
                                    tr("展开全部", "Expand all")
                                },
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
