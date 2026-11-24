package app.pi.ui.screens

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
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
internal data class ComposerExtensionChipState(
    val label: String,
    val count: Int,
    val hasInteractive: Boolean,
)

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
    extensionChip: ComposerExtensionChipState? = null,
    onOpenExtensions: (() -> Unit)? = null,
    onQuickInteractExtension: (() -> Unit)? = null,
    selection: TextRange = TextRange(draft.length),
    selectionRevision: Int = 0,
    onSelectionChange: (TextRange) -> Unit = {},
    onTerminalInputKey: ((String) -> Unit)? = null,
    onTerminalInputText: ((data: String, onResult: (consumed: Boolean, rewritten: String) -> Unit) -> Unit)? = null,
    hasPromptHistory: Boolean = false,
    historyActive: Boolean = false,
    onRecallLastPrompt: (() -> Unit)? = null,
    onOpenPromptHistory: (() -> Unit)? = null,
    onHistoryUp: (() -> Unit)? = null,
    onHistoryDown: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val isBash = bashModeOf(draft)
    val isBashExcluded = bashExcludedOf(draft)

    val inputShape = RoundedCornerShape(PiSpacing.composerRadius)
    val composerProse = PiTheme.text.prose
    val composerLineHeight = (composerProse.fontSize.value * 1.52f).sp
    val composerTextStyle = composerProse.copy(
        fontWeight = FontWeight.Normal,
        lineHeight = composerLineHeight,
    )
    val lineHeightDp = with(LocalDensity.current) { composerLineHeight.toDp() }
    val fieldMaxHeight = lineHeightDp * COMPOSER_MAX_LINES

    var focused by remember { mutableStateOf(false) }
    val activeChrome = focused || isBash || streaming
    val targetBorderColor = when {
        isBash -> palette.bashMode
        activeChrome -> palette.thinking(thinkingLevel)
        else -> palette.borderMuted
    }
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "composerBorderColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused || isBash) 1.5.dp else 0.75.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "composerBorderWidth",
    )

    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    // 仅在流式输出且未聚焦时启动呼吸动画，且只传 State 到绘制阶段读取：空闲零开销、流式零重组。
    val pulseState: androidx.compose.runtime.State<Float>? = if (streaming && !focused && animationsEnabled) {
        rememberInfiniteTransition(label = "composerPulse").animateFloat(
            initialValue = 1f,
            targetValue = COMPOSER_PULSE_MIN,
            animationSpec = infiniteRepeatable(
                animation = tween(COMPOSER_PULSE_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "composerPulseAlpha",
        )
    } else {
        null
    }
    val idleAlpha = if (activeChrome) 0.85f else 0.52f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(inputShape)
            .background(MaterialTheme.colorScheme.surfaceContainer, inputShape)
            .drawWithContent {
                drawContent()
                val factor = pulseState?.value ?: 1f
                val strokePx = borderWidth.toPx()
                val half = strokePx / 2f
                val radiusPx = (PiSpacing.composerRadius.toPx() - half).coerceAtLeast(0f)
                val alpha = (borderColor.alpha * idleAlpha * factor).coerceIn(0.15f, 1f)
                drawRoundRect(
                    color = borderColor.copy(alpha = alpha),
                    topLeft = Offset(half, half),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = strokePx),
                )
            },
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
                    text = if (isBashExcluded) {
                        tr("终端命令 · 仅本地执行（不进上下文）", "Bash · local only (excluded from context)")
                    } else {
                        tr("终端命令 · 输出将加入上下文", "Bash · output added to context")
                    },
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.bashMode,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isBashExcluded) tr("改进上下文", "Add to context") else tr("改仅本地", "Local only"),
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
            if (extensionChip != null && onOpenExtensions != null) {
                ComposerExtensionChip(
                    chip = extensionChip,
                    onClick = onOpenExtensions,
                    onLongClick = onQuickInteractExtension,
                )
            }
        }

        // 3) 文本输入区 + 右侧快捷清空
        // 严格保护中文/日文 IME 组合态（composition）：用户正常输入期间绝不覆盖 fieldState；
        // 仅在外部程序修改 draft 或显式推进 selectionRevision（如 pasteToEditor / @ 提及 / 自动补全 / 历史召回）时同步光标。
        val clampedExternalSel = TextRange(
            selection.start.coerceIn(0, draft.length),
            selection.end.coerceIn(0, draft.length),
        )
        var fieldState by remember {
            mutableStateOf(TextFieldValue(text = draft, selection = clampedExternalSel))
        }
        var syncedRevision by remember { mutableIntStateOf(selectionRevision) }
        LaunchedEffect(draft, selectionRevision) {
            if (fieldState.text != draft || syncedRevision != selectionRevision) {
                syncedRevision = selectionRevision
                fieldState = TextFieldValue(
                    text = draft,
                    selection = TextRange(
                        selection.start.coerceIn(0, draft.length),
                        selection.end.coerceIn(0, draft.length),
                    ),
                )
            }
        }
        val activeValue = if (fieldState.text == draft && syncedRevision == selectionRevision) {
            fieldState
        } else {
            TextFieldValue(text = draft, selection = clampedExternalSel)
        }
        val latestDraft by rememberUpdatedState(draft)
        val latestOnDraftChange by rememberUpdatedState(onDraftChange)
        val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BasicTextField(
                value = activeValue,
                onValueChange = { updated ->
                    val prev = activeValue
                    val textChanged = updated.text != prev.text
                    val selectionChanged = updated.selection != prev.selection
                    fieldState = updated
                    if (selectionChanged) onSelectionChange(updated.selection)
                    if (textChanged) onDraftChange(updated.text)
                    // 软键盘输入法桥接扩展 `ctx.ui.onTerminalInput`：仅在非 IME 拼音组合态且发生单步文本变更时派发，
                    // 若扩展拦截（consume）或重写（data），在草稿未被后续输入覆盖的前提下回滚或原地替换。
                    if (
                        textChanged &&
                        onTerminalInputText != null &&
                        prev.composition == null &&
                        updated.composition == null
                    ) {
                        val edit = detectSingleTextEdit(prev.text, updated.text)
                        if (edit != null) {
                            val expectedText = updated.text
                            val prevText = prev.text
                            val prevSel = prev.selection
                            onTerminalInputText(edit.sequence) { consumed, rewritten ->
                                if (latestDraft != expectedText) return@onTerminalInputText
                                if (consumed) {
                                    val restoredSel = TextRange(
                                        prevSel.start.coerceIn(0, prevText.length),
                                        prevSel.end.coerceIn(0, prevText.length),
                                    )
                                    fieldState = TextFieldValue(text = prevText, selection = restoredSel)
                                    latestOnSelectionChange(restoredSel)
                                    latestOnDraftChange(prevText)
                                } else if (rewritten != edit.sequence) {
                                    val safeRewritten = rewritten.filter { ch ->
                                        ch == '\n' || ch == '\t' || (!ch.isISOControl() && ch != '\u007f')
                                    }
                                    val prefix = expectedText.substring(0, edit.prefixLen)
                                    val suffix = expectedText.substring(expectedText.length - edit.suffixLen)
                                    val nextText = prefix + safeRewritten + suffix
                                    val nextCursor = (edit.prefixLen + safeRewritten.length).coerceIn(0, nextText.length)
                                    val nextSel = TextRange(nextCursor, nextCursor)
                                    fieldState = TextFieldValue(text = nextText, selection = nextSel)
                                    latestOnSelectionChange(nextSel)
                                    latestOnDraftChange(nextText)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = lineHeightDp, max = fieldMaxHeight)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when {
                                // 官方 Pi 快捷键：流式输出时 Esc 立即中止
                                event.key == Key.Escape && streaming -> {
                                    onStop()
                                    return@onPreviewKeyEvent true
                                }
                                // 官方 Pi 快捷键：Shift+Tab 轮换思考等级
                                event.key == Key.Tab && event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed -> {
                                    onCycleThinking()
                                    return@onPreviewKeyEvent true
                                }
                                // 外接键盘 Ctrl+Enter 直接发送（流式时优先引导）
                                event.key == Key.Enter && event.isCtrlPressed && !event.isAltPressed -> {
                                    if (streaming && canSteer) {
                                        onSteer()
                                        return@onPreviewKeyEvent true
                                    } else if (!streaming) {
                                        onSend()
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                // 官方 Pi 输入历史：空草稿或浏览历史时 ↑ / ↓ 翻阅上一条/下一条消息
                                event.key == Key.DirectionUp && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed &&
                                    onHistoryUp != null && (draft.isEmpty() || historyActive) -> {
                                    onHistoryUp()
                                    return@onPreviewKeyEvent true
                                }
                                event.key == Key.DirectionDown && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed &&
                                    onHistoryDown != null && historyActive -> {
                                    onHistoryDown()
                                    return@onPreviewKeyEvent true
                                }
                            }
                            if (onTerminalInputKey != null) {
                                val seq = terminalSequenceOf(event)
                                if (seq != null) onTerminalInputKey(seq)
                            }
                        }
                        false
                    },
                textStyle = composerTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                maxLines = COMPOSER_MAX_LINES,
                cursorBrush = SolidColor(if (isBash) palette.bashMode else palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                text = tr("发消息给 pi，/ 命令 · @ 文件 · ! 终端", "Message pi · / commands · @ files · ! bash"),
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
                        .clickable(onClickLabel = tr("清空草稿", "Clear draft"), onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("清空", "Clear"),
                        modifier = Modifier.size(14.dp),
                        tint = palette.muted,
                    )
                }
            }
        }

        // 4) 底部操作工具栏：左侧高频直达键（/、@、!、📎、历史召回），右侧流式控制或主发送按钮
        val showDeliveryPills = streaming && (canSteer || canFollowUp)
        val activeCaret = activeValue.selection.end.coerceIn(0, draft.length)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 流式输入草稿时优先给「引导 / 后续 / 停止」留足宽度，避免 360dp 窄屏裁切按钮文案。
            if (!showDeliveryPills) {
                ComposerQuickKey(
                    label = "/",
                    contentDescription = tr("命令面板", "Command palette"),
                    active = draft.trimStart().startsWith("/"),
                    activeColor = palette.accent,
                    onClick = onOpenPalette,
                )
                ComposerQuickKey(
                    label = "@",
                    contentDescription = tr("提及工作区文件", "Mention workspace file"),
                    active = app.pi.ui.chat.PiFileMentions.prefixOf(draft.substring(0, activeCaret)) != null,
                    activeColor = palette.accent,
                    onClick = onOpenMention,
                )
                ComposerQuickKey(
                    label = "!",
                    contentDescription = tr("终端命令（长按不进上下文）", "Bash command (long-press for local only)"),
                    active = isBash,
                    activeColor = palette.bashMode,
                    onClick = onOpenBash,
                    onLongClick = onOpenBashExcluded,
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = tr("添加图片或文件", "Attach image or file"), onClick = onPickAttachment),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = tr("附件", "Attach"),
                        modifier = Modifier.size(19.dp),
                        tint = palette.muted,
                    )
                }
                if (draft.isEmpty() && !streaming && hasPromptHistory && onRecallLastPrompt != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClickLabel = tr("召回上一条消息（长按查看历史）", "Recall last prompt (long-press for history)"),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRecallLastPrompt()
                                },
                                onLongClickLabel = if (onOpenPromptHistory != null) tr("打开历史消息", "Open prompt history") else null,
                                onLongClick = onOpenPromptHistory?.let { open ->
                                    {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        open()
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = tr("历史消息", "Prompt history"),
                            modifier = Modifier.size(19.dp),
                            tint = palette.muted,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (streaming) {
                if (showDeliveryPills) {
                    DeliveryActionPill(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        label = tr("引导", "Steer"),
                        tint = palette.warning,
                        enabled = canSteer,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSteer()
                        },
                    )
                    DeliveryActionPill(
                        icon = Icons.Filled.ArrowDownward,
                        label = tr("后续", "Follow-up"),
                        tint = palette.accent,
                        enabled = canFollowUp,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onFollowUp()
                        },
                    )
                }
                // 流式停止按钮：高对比紧凑停止钮，带触觉确认反馈
                val showStopLabel = !showDeliveryPills
                val stopInteraction = remember { MutableInteractionSource() }
                val stopPressed by stopInteraction.collectIsPressedAsState()
                val stopScale by animateFloatAsState(
                    targetValue = if (stopPressed) 0.93f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
                    label = "stopScale",
                )
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .graphicsLayer {
                            scaleX = stopScale
                            scaleY = stopScale
                        }
                        .clip(CircleShape)
                        .background(palette.error)
                        .clickable(
                            interactionSource = stopInteraction,
                            indication = ripple(color = palette.contrastOn(palette.error)),
                            role = Role.Button,
                            onClickLabel = tr("停止生成", "Stop generating"),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                            },
                        )
                        .padding(horizontal = if (showStopLabel) 13.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val stopFg = palette.contrastOn(palette.error)
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = tr("停止", "Stop"),
                        modifier = Modifier.size(16.dp),
                        tint = stopFg,
                    )
                    if (showStopLabel) {
                        Text(
                            text = tr("停止", "Stop"),
                            style = PiType.chipLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = stopFg,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                val bashCommandText = remember(draft, isBash) {
                    if (!isBash) "" else draft.trimStart().let { t ->
                        if (t.startsWith("!!")) t.removePrefix("!!") else t.removePrefix("!")
                    }.trim()
                }
                val isBashReady = isBash && bashCommandText.isNotEmpty()
                val hasPayload = if (isBash) isBashReady else (draft.isNotBlank() || canClear)
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val sendScale by animateFloatAsState(
                    targetValue = when {
                        sendPressed && hasPayload -> 0.9f
                        hasPayload -> 1f
                        else -> 0.95f
                    },
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                    label = "sendScale",
                )
                val sendBg by animateColorAsState(
                    targetValue = when {
                        isBashReady -> palette.bashMode
                        hasPayload -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                    label = "sendBtnBg",
                )
                val sendFg = when {
                    isBashReady -> palette.contrastOn(palette.bashMode)
                    hasPayload -> MaterialTheme.colorScheme.surface
                    else -> palette.dim
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
                        .clip(CircleShape)
                        .background(sendBg)
                        .clickable(
                            interactionSource = sendInteraction,
                            indication = ripple(color = sendFg),
                            enabled = hasPayload,
                            role = Role.Button,
                            onClickLabel = if (isBash) tr("运行命令", "Run command") else tr("发送", "Send"),
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSend()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isBash) Icons.Filled.PlayArrow else Icons.Filled.ArrowUpward,
                        contentDescription = if (isBash) tr("运行", "Run") else tr("发送", "Send"),
                        modifier = Modifier.size(if (isBash) 20.dp else 19.dp),
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
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(28.dp)
            .widthIn(max = 176.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.onSurface),
                onClickLabel = tr("选择模型", "Select model"),
                onClick = onClick,
                onLongClickLabel = tr("切换到下一个模型", "Cycle to next model"),
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(start = 8.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label ?: tr("选择模型", "Select model"),
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
    val haptic = LocalHapticFeedback.current
    val dotColor = palette.thinking(level)
    val active = !level.equals("off", ignoreCase = true)
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(
                if (active) dotColor.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            )
            .combinedClickable(
                onClickLabel = tr("切换思考等级：${thinkingLabelOf(level)}", "Cycle thinking level: ${thinkingLabelOf(level)}"),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClickLabel = tr("选择思考等级", "Select thinking level"),
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
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
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
            .clickable(onClickLabel = tr("查看上下文与用量", "View context and usage"), onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PiContextRing(
            percent = percent,
            diameter = 16.dp,
            stroke = 1.8.dp,
            placeholderStyle = PiTheme.text.monoSmall,
        )
        if (percent != null) {
            Text(
                text = String.format(Locale.US, "%.0f%%", percent),
                style = PiTheme.text.monoSmall.copy(fontFeatureSettings = "tnum"),
                color = tone,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerExtensionChip(
    chip: ComposerExtensionChipState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(28.dp)
            .widthIn(max = 152.dp)
            .clip(shape)
            .background(
                if (chip.hasInteractive) palette.accent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = palette.accent),
                onClickLabel = tr("打开扩展中心", "Open extensions"),
                onClick = onClick,
                onLongClickLabel = if (onLongClick != null) tr("直接操控扩展面板", "Interact with extension panel") else null,
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
                .background(palette.accent),
        )
        Text(
            text = chip.label,
            modifier = Modifier.weight(1f, fill = false),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (chip.count > 1) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.accent.copy(alpha = 0.22f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = chip.count.toString(),
                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                    color = palette.accent,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 将外接实体键盘的非剪贴板控制键映射为终端 ANSI 序列，供扩展 `ctx.ui.onTerminalInput` 监听（绝不干扰软键盘与复制粘贴）。 */
private fun terminalSequenceOf(event: KeyEvent): String? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.key == Key.Escape) return "\u001b"
    if (event.key == Key.Tab) return "\t"
    val cp = event.utf16CodePoint
    if (event.isCtrlPressed && !event.isAltPressed && cp in 32..126) {
        val lower = cp.toChar().lowercaseChar()
        if (lower in 'a'..'z' && lower !in charArrayOf('a', 'c', 'v', 'x', 'z')) {
            return (lower.code - 'a'.code + 1).toChar().toString()
        }
    }
    if (event.isAltPressed && !event.isCtrlPressed && cp in 32..126) {
        return "\u001b" + cp.toChar()
    }
    return null
}

private data class SingleTextEdit(
    val prefixLen: Int,
    val suffixLen: Int,
    val sequence: String,
)

/**
 * 识别软键盘提交的单步纯插入（≤16 字符）或单字符退格，映射为 `ctx.ui.onTerminalInput` 序列。
 * 整段粘贴或大范围替换返回 null，避免误拦剪贴板粘贴。
 */
private fun detectSingleTextEdit(oldText: String, newText: String): SingleTextEdit? {
    if (oldText == newText) return null
    val minLen = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (
        suffix < minLen - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }
    val deletedLen = oldText.length - prefix - suffix
    val insertedLen = newText.length - prefix - suffix
    return when {
        deletedLen == 0 && insertedLen in 1..16 -> SingleTextEdit(
            prefixLen = prefix,
            suffixLen = suffix,
            sequence = newText.substring(prefix, newText.length - suffix),
        )
        insertedLen == 0 && deletedLen == 1 -> SingleTextEdit(
            prefixLen = prefix,
            suffixLen = suffix,
            sequence = "\u007f",
        )
        else -> null
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (enabled) tint.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (enabled) tint else palette.muted,
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
