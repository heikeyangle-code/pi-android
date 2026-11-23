package app.pi.ui.blocks

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.ThinkingBlock
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel

/** 扩展给折叠思考块定的标题（已去掉颜色码）；null = 用 App 自己的措辞。 */
val LocalThinkingLabel = staticCompositionLocalOf<String?> { null }

/**
 * 思考块：一行低调的灰色元信息，不是卡片。
 *
 * 没有底色、没有描边，只有一个小色点（等级色，思考中呼吸）+「思考中… / 已思考 12s」+ 等级 + 箭头，
 * 全部用 muted 色，读正文时几乎感觉不到它。展开后正文缩进一条细竖线，字号略小。
 *
 * 性能：展开/收起用 [AnimatedVisibility]，只在切换的那一下做高度动画；流式期间正文增长
 * **不**触发动画（以前整卡挂 `animateContentSize`，每个 token 都会启动一次 160ms 尺寸动画，
 * 并和列表的贴底跟随互相拉扯）。呼吸点的透明度只在 `graphicsLayer` 里读，不触发重组。
 */
@Composable
fun ThinkingBlockBlock(
    item: ThinkingBlock,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    var expanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }
    val pen = palette.thinking(item.level ?: "medium")
    val levelLabel = item.level?.takeIf { it.isNotBlank() }?.let { PiThinkingLevel.fromWire(it).label }
    val custom = LocalThinkingLabel.current
    val duration = if (item.streaming) null else item.elapsedMs?.let { formatDuration(it) }
    val headline = custom ?: when {
        item.streaming -> tr("思考中…", "Thinking…")
        duration != null -> tr("已思考 $duration", "Thought for $duration")
        else -> tr("已思考", "Thought")
    }

    val pulse: State<Float>? = if (item.streaming) {
        rememberInfiniteTransition(label = "think-pulse").animateFloat(
            initialValue = 0.25f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "think-pulse-alpha",
        )
    } else {
        null
    }
    val turn by animateFloatAsState(if (expanded) 90f else 0f, tween(160), label = "think-chevron")
    val muted = palette.muted

    BlockColumn(modifier) {
        ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .graphicsLayer { alpha = pulse?.value ?: 0.6f }
                            .clip(CircleShape)
                            .background(pen),
                    )
                    Text(
                        text = headline,
                        style = PiTheme.text.meta,
                        color = muted,
                        maxLines = 1,
                    )
                    if (levelLabel != null) {
                        Text(
                            text = levelLabel,
                            style = PiTheme.text.monoSmall,
                            color = muted.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) tr("收起思考", "Collapse thinking") else tr("展开思考", "Expand thinking"),
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { rotationZ = turn },
                        tint = muted.copy(alpha = 0.7f),
                    )
                }
                // LazyColumn 内不做多帧高度缩展动画（与 ToolCard 一致）：
                // 避免 150ms shrinkVertically 与收起锚点（TranscriptRowAnchor.onCollapse）及
                // 触底跟随（TailFollow）互相拉扯，确保展开/收起与流式增长同时进行时零跳动、零闪烁。
                if (expanded) {
                    val bodyText = item.text.ifEmpty {
                        if (item.streaming) tr("正在思考…", "Thinking…") else tr("（无思考内容）", "(No thinking content)")
                    }
                    val bodyContent: @Composable () -> Unit = {
                        Text(
                            text = bodyText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 2.dp)
                                .drawBehind {
                                    drawRoundRect(
                                        color = muted.copy(alpha = 0.28f),
                                        topLeft = Offset(0f, 0f),
                                        size = Size(THINK_STRIPE_WIDTH.toPx(), size.height),
                                        cornerRadius = CornerRadius(THINK_STRIPE_WIDTH.toPx() / 2f),
                                    )
                                }
                                .padding(start = THINK_BODY_INDENT),
                            style = PiTheme.text.prose.let {
                                it.copy(fontSize = it.fontSize * 0.9f, lineHeight = it.lineHeight * 0.95f)
                            },
                            color = palette.thinkingBodyOnCanvas.copy(alpha = 0.82f),
                        )
                    }
                    // 流式期间不包裹 SelectionContainer，避免每个思考 token 都触发选择注册表重算与闪烁。
                    if (item.streaming) {
                        bodyContent()
                    } else {
                        SelectableContent { bodyContent() }
                    }
                }
            }
        }
    }
}

private val THINK_STRIPE_WIDTH = 2.dp

internal val THINK_BODY_INDENT = 11.dp
