package app.pi.ui.blocks

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.rpc.ThinkingBlock
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel
import app.pi.ui.theme.numeric

/** 扩展给折叠思考块定的标题（已去掉颜色码）；null = 用 App 自己的措辞。 */
val LocalThinkingLabel = staticCompositionLocalOf<String?> { null }

/**
 * 现代思考过程卡片：柔和圆角容器 + 思考等级色点（思考中平滑呼吸）+ 耗时与等级胶囊 +
 * 平滑旋转箭头与内容展开动画。
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
    val headline = custom ?: if (item.streaming) "正在深度思考…" else "思考过程"
    val headlineDuration = if (item.streaming) null else item.elapsedMs?.let { formatDuration(it) }

    val pulse: State<Float>? = if (item.streaming) {
        rememberInfiniteTransition(label = "think-pulse").animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "think-pulse-alpha",
        )
    } else {
        null
    }
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "think-chevron")

    BlockColumn(modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(180)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                width = 1.dp,
                color = pen.copy(alpha = if (item.streaming) 0.45f else 0.22f),
            ),
        ) {
            ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .graphicsLayer { alpha = pulse?.value ?: 1f }
                                .clip(CircleShape)
                                .background(pen),
                        )
                        Text(
                            text = headline,
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.thinkingBodyOnCanvas,
                        )
                        if (levelLabel != null) {
                            Text(
                                text = levelLabel,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(pen.copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                                style = PiTheme.text.monoSmall,
                                color = pen,
                            )
                        }
                        if (headlineDuration != null) {
                            Text(
                                text = headlineDuration,
                                style = PiTheme.text.monoSmall,
                                color = palette.muted,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(turn),
                            tint = palette.muted,
                        )
                    }
                    if (expanded) {
                        SelectableContent {
                            Text(
                                text = item.text.ifEmpty { "（无思考内容）" },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .drawBehind {
                                        drawRoundRect(
                                            color = pen.copy(alpha = 0.42f),
                                            topLeft = Offset(0f, 0f),
                                            size = Size(THINK_STRIPE_WIDTH.toPx(), size.height),
                                            cornerRadius = CornerRadius(THINK_STRIPE_WIDTH.toPx() / 2f),
                                        )
                                    }
                                    .padding(start = THINK_BODY_INDENT),
                                style = PiTheme.text.prose.let {
                                    it.copy(fontSize = it.fontSize * 0.92f, lineHeight = it.lineHeight * 0.96f)
                                },
                                color = palette.thinkingBodyOnCanvas,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val THINK_STRIPE_WIDTH = 2.5.dp

internal val THINK_BODY_INDENT = 11.dp
