package app.pi.ui.screens

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.PiResponses
import app.pi.ui.chat.thinkingLabelOf
import app.pi.ui.components.PiContextRing
import app.pi.ui.components.PiType
import app.pi.ui.components.contextProgressColor
import app.pi.ui.theme.PiProseFamily
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import java.util.Locale

private const val COMPOSER_BORDER_MS = 160
private const val COMPOSER_FADE_MS = 160
private const val COMPOSER_PULSE_MS = 1500
private const val COMPOSER_PULSE_MIN = 0.42f
private const val COMPOSER_MAX_LINES = 7

/**
 * 全新两层现代输入工作台：
 *  - 顶部配置胶囊行：模型芯片（点选/长按轮换）、思考等级彩色指示胶囊（始终可见！点轮换/长按选档）、
 *    上下文环 + 实时百分比胶囊（点开用量与压缩面板）；
 *  - Bash 模式横幅：输入 `!` / `!!` 时自动亮起清晰模式指示，支持一键在「进上下文 / 仅本地」间切换或取消；
 *  - 中间输入区：1–7 行平滑伸缩，草稿非空时右侧给一键清空；
 *  - 底部操作行：左侧 `/` 命令 · `@` 文件 · `!` 终端（长按 `!!`）· 📎 附件，
 *    右侧：空闲时主发送圆钮（空草稿置淡态，Bash 模式切换终端绿箭头）；流式时「⇢ 引导」「⇣ 后续」清晰胶囊 + 醒目红色停止钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    thinkingLevel: String,
    streaming: Boolean,
    canFollowUp: Boolean,
    canSteer: Boolean,
    modelLabel: String?,
    onOpenModel: () -> Unit,
    onCycleModel: () -> Unit,
    onCycleThinking: () -> Unit,
    onPickThinking: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenBash: () -> Unit,
    onOpenBashExcluded: () -> Unit,
    onOpenMention: () -> Unit,
    onPickAttachment: () -> Unit,
    contextUsage: PiResponses.ContextUsage?,
    onOpenContext: () -> Unit,
    canClear: Boolean,
    onClear: () -> Unit,
    onSteer: () -> Unit,
    onFollowUp: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val palette = PiTheme.palette
    val isBash = bashModeOf(draft)
    val isBashExcluded = bashExcludedOf(draft)

    val targetBorderColor = if (isBash) {
        palette.bashMode
    } else {
        palette.thinking(thinkingLevel)
    }
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(COMPOSER_BORDER_MS),
        label = "composerBorderColor",
    )

    val inputShape = RoundedCornerShape(PiSpacing.composerRadius)
    val composerProse = PiTheme.text.prose
    val composerLineHeight = (composerProse.fontSize.value * 1.52f).sp
    val composerTextStyle = composerProse.copy(
        fontFamily = PiProseFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = composerLineHeight,
    )
    val lineHeightDp = with(LocalDensity.current) { composerLineHeight.toDp() }
    val fieldMaxHeight = lineHeightDp * COMPOSER_MAX_LINES

    var focused by remember { mutableStateOf(false) }
    val borderWidth by animateDpAsState(
        targetValue = if (focused || isBash) 1.75.dp else 1.dp,
        animationSpec = tween(COMPOSER_BORDER_MS),
        label = "composerBorderWidth",
    )

    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val pulse by rememberInfiniteTransition(label = "composerPulse").animateFloat(
        initialValue = 1f,
        targetValue = COMPOSER_PULSE_MIN,
        animationSpec = infiniteRepeatable(
            animation = tween(COMPOSER_PULSE_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "composerPulseAlpha",
    )
    val factor by animateFloatAsState(
        targetValue = if (streaming && !focused && animationsEnabled) pulse else 1f,
        animationSpec = tween(COMPOSER_FADE_MS),
        label = "composerPulseFactor",
    )
    val idleAlpha = if (focused || isBash || streaming) 0.92f else 0.55f
    val shownBorder = borderColor.copy(alpha = (borderColor.alpha * idleAlpha * factor).coerceIn(0.15f, 1f))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(inputShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, inputShape)
            .border(borderWidth, shownBorder, inputShape)
            .animateContentSize(animationSpec = tween(160)),
    ) {
        // 1) Bash 模式横幅（仅在 `!` / `!!` 开头时平滑滑出）
        AnimatedVisibility(
            visible = isBash,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bashMode.copy(alpha = 0.13f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (isBashExcluded) "!!" else "!",
                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Bold),
                    color = palette.bashMode,
                )
                Text(
                    text = if (isBashExcluded) "终端命令 · 仅本地执行（不进上下文）" else "终端命令 · 输出将加入上下文",
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.bashMode,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isBashExcluded) "改进上下文" else "改仅本地",
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.bashMode.copy(alpha = 0.18f))
                        .clickable {
                            val trimmed = draft.trimStart()
                            val rest = trimmed.removePrefix("!!").removePrefix("!")
                            onDraftChange((if (isBashExcluded) "!" else "!!") + rest)
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = PiTheme.text.meta,
                    color = palette.bashMode,
                )
            }
        }

        // 2) 顶部模型 / 思考等级 / 上下文胶囊条（始终完整展示，窄屏平滑横滑不隐藏任何项）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ComposerModelPill(
                label = modelLabel,
                onClick = onOpenModel,
                onLongClick = onCycleModel,
            )
            ThinkingLevelPill(
                level = thinkingLevel,
                onClick = onCycleThinking,
                onLongClick = onPickThinking,
            )
            ContextUsagePill(
                usage = contextUsage,
                onClick = onOpenContext,
            )
        }

        // 3) 文本输入区 + 右侧快捷清空
        val field = remember {
            mutableStateOf(TextFieldValue(text = draft, selection = TextRange(draft.length)))
        }
        if (field.value.text != draft) {
            field.value = TextFieldValue(text = draft, selection = TextRange(draft.length))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BasicTextField(
                value = field.value,
                onValueChange = { updated ->
                    val textChanged = updated.text != field.value.text
                    field.value = updated
                    if (textChanged) onDraftChange(updated.text)
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = lineHeightDp, max = fieldMaxHeight)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = composerTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                maxLines = COMPOSER_MAX_LINES,
                cursorBrush = SolidColor(if (isBash) palette.bashMode else palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                text = "发消息给 pi，/ 命令 · @ 文件 · ! 终端",
                                style = composerTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (canClear) {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f))
                        .clickable(onClickLabel = "清空草稿", onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清空",
                        modifier = Modifier.size(14.dp),
                        tint = palette.muted,
                    )
                }
            }
        }

        // 4) 底部操作工具栏：左侧高频直达键（/、@、!、📎），右侧流式控制或主发送按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ComposerQuickKey(
                label = "/",
                contentDescription = "命令面板",
                active = draft.trimStart().startsWith("/"),
                activeColor = palette.accent,
                onClick = onOpenPalette,
            )
            ComposerQuickKey(
                label = "@",
                contentDescription = "提及工作区文件",
                active = draft.contains("@"),
                activeColor = palette.accent,
                onClick = onOpenMention,
            )
            ComposerQuickKey(
                label = "!",
                contentDescription = "终端命令（长按不进上下文）",
                active = isBash,
                activeColor = palette.bashMode,
                onClick = onOpenBash,
                onLongClick = onOpenBashExcluded,
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "添加图片或文件", onClick = onPickAttachment),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "附件",
                    modifier = Modifier.size(19.dp),
                    tint = palette.muted,
                )
            }

            Spacer(Modifier.weight(1f))

            if (streaming) {
                if (canSteer || canFollowUp) {
                    DeliveryActionPill(
                        glyph = "⇢",
                        label = "引导",
                        tint = palette.warning,
                        enabled = canSteer,
                        onClick = onSteer,
                    )
                    DeliveryActionPill(
                        glyph = "⇣",
                        label = "后续",
                        tint = palette.accent,
                        enabled = canFollowUp,
                        onClick = onFollowUp,
                    )
                }
                // 流式停止按钮：醒目红色，草稿为空时展开成「■ 停止」胶囊
                val showStopLabel = !(canSteer || canFollowUp)
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(palette.error)
                        .clickable(role = Role.Button, onClickLabel = "停止生成", onClick = onStop)
                        .padding(horizontal = if (showStopLabel) 13.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val stopFg = palette.contrastOn(palette.error)
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止",
                        modifier = Modifier.size(16.dp),
                        tint = stopFg,
                    )
                    if (showStopLabel) {
                        Text(
                            text = "停止",
                            style = PiType.chipLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = stopFg,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                val hasPayload = draft.isNotBlank() || canClear
                val sendBg by animateColorAsState(
                    targetValue = when {
                        isBash && draft.trim().length > 1 -> palette.bashMode
                        hasPayload -> palette.accent
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = tween(150),
                    label = "sendBtnBg",
                )
                val sendFg = if (hasPayload) palette.contrastOn(sendBg) else palette.muted
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(sendBg)
                        .clickable(
                            enabled = hasPayload,
                            role = Role.Button,
                            onClickLabel = if (isBash) "运行命令" else "发送",
                            onClick = onSend,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isBash) Icons.Filled.PlayArrow else Icons.Filled.Send,
                        contentDescription = if (isBash) "运行" else "发送",
                        modifier = Modifier.size(if (isBash) 20.dp else 17.dp),
                        tint = sendFg,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerModelPill(
    label: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .height(26.dp)
            .widthIn(max = 176.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.onSurface),
                onClickLabel = "选择模型",
                onClick = onClick,
                onLongClickLabel = "切换到下一个模型",
                onLongClick = onLongClick,
            )
            .padding(start = 9.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label ?: "选择模型",
            modifier = Modifier.weight(1f, fill = false),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (label != null) palette.text else palette.accent,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = palette.muted,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThinkingLevelPill(
    level: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val dotColor = palette.thinking(level)
    val active = !level.equals("off", ignoreCase = true)
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(shape)
            .background(
                if (active) dotColor.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
            )
            .combinedClickable(
                onClickLabel = "切换思考等级：${thinkingLabelOf(level)}",
                onClick = onClick,
                onLongClickLabel = "选择思考等级",
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = thinkingLabelOf(level),
            style = PiTheme.text.monoSmall.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
            color = if (active) palette.text else palette.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun ContextUsagePill(
    usage: PiResponses.ContextUsage?,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val percent = usage?.percent
    val tone = percent?.let { contextProgressColor(it, palette) } ?: palette.muted
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .clickable(onClickLabel = "查看上下文与用量", onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PiContextRing(
            percent = percent,
            diameter = 15.dp,
            stroke = 1.8.dp,
            placeholderStyle = PiTheme.text.monoSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
        )
        if (percent != null) {
            Text(
                text = String.format(Locale.US, "%.0f%%", percent),
                style = PiTheme.text.monoSmall,
                color = tone,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerQuickKey(
    label: String,
    contentDescription: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(shape)
            .background(if (active) activeColor.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(
                onClickLabel = contentDescription,
                onClick = onClick,
                onLongClickLabel = if (onLongClick != null) contentDescription else null,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.mono.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
            color = if (active) activeColor else palette.muted,
        )
    }
}

@Composable
private fun DeliveryActionPill(
    glyph: String,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (enabled) tint.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = if (enabled) tint.copy(alpha = 0.35f) else Color.Transparent,
                shape = shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) tint else palette.muted,
        )
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) palette.text else palette.muted,
            maxLines = 1,
        )
    }
}

private fun bashModeOf(draft: String): Boolean = draft.trimStart().startsWith("!")

private fun bashExcludedOf(draft: String): Boolean = draft.trimStart().startsWith("!!")
