package app.pi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.ui.PiEngineStatusDot
import app.pi.ui.chat.ChatAction
import app.pi.ui.chat.activity.ActivityDisplayMode
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.engineStateTone
import app.pi.ui.theme.stateToneColor

/**
 * 悬浮双胶囊对话顶栏（与底部悬浮工作台上下镜像呼应，彻底释放无界阅读画布）：
 *  - 左侧合并胶囊：消除原先「☰ 按钮 + 会话标题」双入口重复，合并为一枚自撑宽精工药丸
 *    （极简自绘侧栏微标 + 引擎呼吸状态点 + 会话名/工作区 + 极细下箭头），一键拉出会话抽屉；
 *  - 右侧工具胶囊：把新建会话 `+`、终端 `>_`（含运行绿点）、项目文件 `📁`、（宽屏/查找态 `🔍`）
 *    与 `⋯` 命令中心收进一枚同高发丝描边胶囊，彻底杜绝零散悬浮球噪点。
 */
@Composable
internal fun ChatTopBar(
    engineLabel: String,
    sessionTitle: String?,
    workspaceName: String? = null,
    extensionTitle: String? = null,
    searchOpen: Boolean,
    scrolled: Boolean,
    toolsExpanded: Boolean,
    toolDisplayMode: String = ActivityDisplayMode.CARDS,
    terminalRunningCount: Int = 0,
    onOpenSessions: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenTerminal: () -> Unit,
    onToggleSearch: (clearOnClose: Boolean) -> Unit,
    onJumpQuestion: (forward: Boolean) -> Unit,
    onToggleTools: () -> Unit,
    onSelectToolDisplayMode: (String) -> Unit = {},
    onAction: (ChatAction) -> Unit,
) {
    var commandCenterOpen by remember { mutableStateOf(false) }
    val palette = PiTheme.palette
    val wideScreen = LocalConfiguration.current.screenWidthDp >= 520
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val hideIme = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val capsuleShape = RoundedCornerShape(20.dp)
    val capsuleBg = palette.cardBg.copy(alpha = 0.94f)
    val capsuleBorder = palette.borderMuted.copy(alpha = if (scrolled) 0.72f else 0.56f)
    val capsuleElevation = if (scrolled) 1.5.dp else 0.dp

    val subtitleText = remember(workspaceName, extensionTitle, sessionTitle) {
        val ws = workspaceName?.trim()?.takeIf { it.isNotEmpty() }
        val ext = extensionTitle?.trim()?.takeIf { it.isNotEmpty() && it != sessionTitle && it != ws }
        when {
            ws != null && ext != null -> "$ws · $ext"
            ext != null -> ext
            else -> ws
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧合并会话胶囊：内容紧凑时自动留出中间呼吸画布，长标题时平滑扩展至可用宽度
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(
                shape = capsuleShape,
                color = capsuleBg,
                border = BorderStroke(0.5.dp, capsuleBorder),
                shadowElevation = capsuleElevation,
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = palette.text),
                            role = Role.Button,
                            onClickLabel = tr("打开会话列表", "Open sessions"),
                        ) {
                            hideIme()
                            onOpenSessions()
                        }
                        .padding(start = 11.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PiSidebarBarsGlyph(color = palette.muted.copy(alpha = 0.85f))
                    PiEngineStatusDot(statusLabel = engineLabel)
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = sessionTitle ?: tr("新对话", "New Chat"),
                            style = PiTheme.text.meta.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitleText.isNullOrBlank()) {
                            Text(
                                text = subtitleText,
                                style = PiTheme.text.monoSmall.copy(
                                    fontSize = 10.5.sp,
                                    lineHeight = 12.sp,
                                ),
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 右侧聚合精工工具胶囊：新建 + 终端 + 项目文件 +（查找态/宽屏搜索）│ 更多
        Surface(
            shape = capsuleShape,
            color = capsuleBg,
            border = BorderStroke(0.5.dp, capsuleBorder),
            shadowElevation = capsuleElevation,
        ) {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                FloatingCapsuleIcon(
                    onClick = {
                        hideIme()
                        onAction(ChatAction.NewSession)
                    },
                    contentDescription = tr("新建会话", "New session"),
                    icon = Icons.Filled.Add,
                )
                Box {
                    FloatingCapsuleIcon(
                        onClick = {
                            hideIme()
                            onOpenTerminal()
                        },
                        contentDescription = if (terminalRunningCount > 0) {
                            tr("打开终端（$terminalRunningCount 个运行中）", "Open terminal ($terminalRunningCount running)")
                        } else {
                            tr("打开终端", "Open terminal")
                        },
                        icon = Icons.Filled.Terminal,
                        highlighted = terminalRunningCount > 0,
                    )
                    if (terminalRunningCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                                .size(5.5.dp)
                                .clip(CircleShape)
                                .background(palette.success),
                        )
                    }
                }
                FloatingCapsuleIcon(
                    onClick = {
                        hideIme()
                        onOpenWorkspace()
                    },
                    contentDescription = tr("项目与文件", "Project & Files"),
                    icon = Icons.Filled.FolderOpen,
                )
                if (wideScreen || searchOpen) {
                    FloatingCapsuleIcon(
                        onClick = { onToggleSearch(false) },
                        contentDescription = if (searchOpen) tr("关闭对话内查找", "Close search") else tr("在对话里查找", "Search in chat"),
                        icon = Icons.Filled.Search,
                        highlighted = searchOpen,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(0.5.dp)
                        .height(14.dp)
                        .background(palette.borderMuted.copy(alpha = 0.55f)),
                )
                FloatingCapsuleIcon(
                    onClick = {
                        hideIme()
                        commandCenterOpen = true
                    },
                    contentDescription = tr("更多操作", "More actions"),
                    icon = Icons.Filled.MoreHoriz,
                )
            }
        }
    }

    if (commandCenterOpen) {
        ChatCommandCenterSheet(
            engineLabel = engineLabel,
            sessionTitle = sessionTitle,
            workspaceName = workspaceName,
            searchOpen = searchOpen,
            toolsExpanded = toolsExpanded,
            toolDisplayMode = toolDisplayMode,
            onDismiss = { commandCenterOpen = false },
            onOpenWorkspace = {
                commandCenterOpen = false
                onOpenWorkspace()
            },
            onToggleSearch = {
                commandCenterOpen = false
                onToggleSearch(true)
            },
            onJumpQuestion = { forward ->
                // 连续点击上一问/下一问时不强关面板，让用户边看上方画布滚动边连续定位
                onJumpQuestion(forward)
            },
            onToggleTools = {
                commandCenterOpen = false
                onToggleTools()
            },
            onSelectToolDisplayMode = onSelectToolDisplayMode,
            onAction = { action ->
                commandCenterOpen = false
                onAction(action)
            },
        )
    }
}

@Composable
private fun FloatingCapsuleIcon(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    highlighted: Boolean = false,
) {
    val palette = PiTheme.palette
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = palette.text),
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.5.dp),
            tint = if (highlighted) palette.accent else palette.text.copy(alpha = 0.80f),
        )
    }
}

@Composable
private fun PiSidebarBarsGlyph(color: Color) {
    Canvas(modifier = Modifier.size(width = 12.dp, height = 10.dp)) {
        val stroke = 1.35.dp.toPx()
        val inset = stroke * 0.5f
        val topY = inset + 0.5.dp.toPx()
        val midY = size.height * 0.5f
        val botY = size.height - inset - 0.5.dp.toPx()
        drawLine(color, Offset(inset, topY), Offset(size.width - inset, topY), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, midY), Offset(size.width * 0.78f, midY), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, botY), Offset(size.width * 0.56f, botY), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ChatCommandCenterSheet(
    engineLabel: String,
    sessionTitle: String?,
    workspaceName: String?,
    searchOpen: Boolean,
    toolsExpanded: Boolean,
    toolDisplayMode: String,
    onDismiss: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onToggleSearch: () -> Unit,
    onJumpQuestion: (forward: Boolean) -> Unit,
    onToggleTools: () -> Unit,
    onSelectToolDisplayMode: (String) -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val statusColor = stateToneColor(engineStateTone(engineLabel), palette)
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 顶部会话抬头 + 可点工作区入口 + 静谧状态胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = sessionTitle ?: tr("当前对话", "Current Chat"),
                        style = PiType.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!workspaceName.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(
                                    onClickLabel = tr("打开项目与文件", "Open project & files"),
                                    onClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        onOpenWorkspace()
                                    },
                                )
                                .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = palette.muted,
                            )
                            Text(
                                text = workspaceName,
                                style = PiTheme.text.monoSmall,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = palette.dim,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg)
                        .border(
                            PiSpacing.hairlineThin,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f),
                            RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Text(
                        text = engineLabel,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
            }

            // 顶部 2×3 极简单色高频入口（与设置页视觉语言 100% 统一，零六色彩块噪音）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickTile(
                        icon = Icons.Filled.AccountTree,
                        label = tr("会话树", "Session Tree"),
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.OpenTree) },
                    )
                    QuickTile(
                        icon = Icons.AutoMirrored.Filled.Article,
                        label = tr("系统提示词", "System Prompt"),
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.ViewSystemPrompt) },
                    )
                    QuickTile(
                        icon = Icons.Filled.Refresh,
                        label = tr("重载扩展", "Reload"),
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.ReloadResources) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickTile(
                        icon = Icons.Filled.Share,
                        label = tr("导出会话", "Export"),
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.ExportSession) },
                    )
                    QuickTile(
                        icon = Icons.Filled.Info,
                        label = tr("会话统计", "Session Stats"),
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.SessionStats) },
                    )
                    QuickTile(
                        icon = Icons.Filled.Settings,
                        label = tr("设置", "Settings"),
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.OpenSettings) },
                    )
                }
            }

            // 视图与定位组
            val isCompactMode = ActivityDisplayMode.isCompact(toolDisplayMode)
            ActionGroupCard(title = tr("视图与定位", "View & Navigation")) {
                ToolDisplayModeSwitchRow(
                    selectedMode = toolDisplayMode,
                    onSelect = { mode ->
                        if (ActivityDisplayMode.normalize(toolDisplayMode) != mode) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onSelectToolDisplayMode(mode)
                        }
                    },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.Search,
                    label = if (searchOpen) tr("关闭对话内查找", "Close search") else tr("查找内容 / 快速定位提问", "Search chat / jump prompts"),
                    showChevron = !searchOpen,
                    onClick = onToggleSearch,
                )
                SheetHairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PromptJumpButton(
                        icon = Icons.Filled.KeyboardArrowUp,
                        label = tr("上一条提问", "Prev prompt"),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onJumpQuestion(false)
                        },
                    )
                    PromptJumpButton(
                        icon = Icons.Filled.KeyboardArrowDown,
                        label = tr("下一条提问", "Next prompt"),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            onJumpQuestion(true)
                        },
                    )
                }
                SheetHairline()
                SheetActionRow(
                    icon = if (toolsExpanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                    label = when {
                        isCompactMode && toolsExpanded -> tr("收起全部执行过程", "Collapse all traces")
                        isCompactMode -> tr("展开全部执行过程", "Expand all traces")
                        toolsExpanded -> tr("收起全部工具输出", "Collapse all tool outputs")
                        else -> tr("展开全部工具输出", "Expand all tool outputs")
                    },
                    trailingBadge = if (toolsExpanded) tr("已展开", "Expanded") else tr("已收起", "Collapsed"),
                    onClick = onToggleTools,
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = tr("复制最后一条回复", "Copy last response"),
                    onClick = { onAction(ChatAction.CopyLastAssistant) },
                )
            }

            // 当前会话与策略组
            ActionGroupCard(title = tr("当前会话与策略", "Session & Policy")) {
                SheetActionRow(
                    icon = Icons.Filled.Edit,
                    label = tr("重命名会话", "Rename session"),
                    showChevron = true,
                    onClick = { onAction(ChatAction.RenameSession) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.AccountTree,
                    label = tr("从历史消息分叉", "Fork from message"),
                    subtitle = tr("回到某条用户消息创建独立分支会话", "Branch into a new session from an earlier prompt"),
                    showChevron = true,
                    onClick = { onAction(ChatAction.PickFork) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = tr("克隆当前会话", "Clone session"),
                    onClick = { onAction(ChatAction.CloneSession) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.Compress,
                    label = tr("立即压缩上下文", "Compact context now"),
                    subtitle = tr("总结较早轮次释放上下文窗口", "Summarize earlier turns to free context window"),
                    onClick = { onAction(ChatAction.Compact) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.Tune,
                    label = tr("队列与自动容错策略", "Queue & Auto-Policy"),
                    subtitle = tr("引导/后续投递方式、自动压缩与自动重试开关", "Steering/follow-up mode, auto-compaction & auto-retry"),
                    showChevron = true,
                    onClick = { onAction(ChatAction.ToolsSheet) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.FileUpload,
                    label = tr("导入 JSONL 会话文件", "Import JSONL session"),
                    showChevron = true,
                    onClick = { onAction(ChatAction.ImportSession) },
                )
            }
        }
    }
}

@Composable
private fun PromptJumpButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isDark = PiTheme.isDark
    val shape = RoundedCornerShape(10.dp)
    val bg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.80f)
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(PiSpacing.hairlineThin, borderColor, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isDark = PiTheme.isDark
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = RoundedCornerShape(14.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.50f else 0.72f)
    Column(
        modifier = modifier
            .clip(shape)
            .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
            .border(PiSpacing.hairlineThin, borderColor, shape)
            .clickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
            )
        }
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionGroupCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val isDark = PiTheme.isDark
    val shape = RoundedCornerShape(14.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.50f else 0.72f)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 6.dp),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = PiTheme.palette.muted,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
                .border(PiSpacing.hairlineThin, borderColor, shape),
        ) {
            content()
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    trailingBadge: String? = null,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = PiTheme.palette.muted,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingBadge != null) {
            Text(
                text = trailingBadge,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg)
                    .border(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
            )
        } else if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = PiTheme.palette.dim,
            )
        }
    }
}

@Composable
private fun ToolDisplayModeSwitchRow(
    selectedMode: String,
    onSelect: (String) -> Unit,
) {
    val palette = PiTheme.palette
    val isDark = PiTheme.isDark
    val normalized = ActivityDisplayMode.normalize(selectedMode)
    val isCompact = normalized == ActivityDisplayMode.COMPACT
    val trackBg = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val activeBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
    val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f)
    val activeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.75f else 0.90f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = palette.muted,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tr("工具与过程展示", "Tool & Process Display"),
                    style = PiType.rowTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isCompact) {
                        tr("思考与工具收成单行摘要，正文更突出", "Folds trace into a calm bar so prose stands out")
                    } else {
                        tr("逐条展示工具卡片，对齐官方 Pi", "Shows each tool card individually, official Pi")
                    },
                    style = PiTheme.text.meta,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val trackShape = RoundedCornerShape(10.dp)
        val itemShape = RoundedCornerShape(7.dp)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = trackShape,
            color = trackBg,
            border = BorderStroke(PiSpacing.hairlineThin, trackBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val options = listOf(
                    ActivityDisplayMode.CARDS to tr("Pi 原版", "Pi Original"),
                    ActivityDisplayMode.COMPACT to tr("专注折叠", "Compact"),
                )
                for ((value, label) in options) {
                    val selected = normalized == value
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(itemShape)
                            .background(if (selected) activeBg else Color.Transparent)
                            .border(
                                width = if (selected) PiSpacing.hairlineThin else 0.dp,
                                color = if (selected) activeBorder else Color.Transparent,
                                shape = itemShape,
                            )
                            .clickable(role = Role.RadioButton, onClick = { onSelect(value) })
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = PiTheme.text.meta.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHairline() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 45.dp),
        thickness = PiSpacing.hairlineThin,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}
