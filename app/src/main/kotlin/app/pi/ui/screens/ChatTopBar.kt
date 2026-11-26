package app.pi.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.PiEngineStatusDot
import app.pi.ui.PiTopBarIcon
import app.pi.ui.chat.ChatAction
import app.pi.ui.chat.activity.ActivityDisplayMode
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.engineStateTone
import app.pi.ui.theme.stateToneColor

/**
 * 统一官方 Pi 移动端对话顶栏 + 分类命令中心底部面板：
 *  - 左侧：标准 44dp 会话侧栏图标（与会话页返回键同位对齐）+ 引擎呼吸状态点 + 会话标题 + 项目副标；
 *  - 右侧：+ 新建会话、搜索、项目与文件、⋯ 更多（44dp/22dp，与全 App [PiTopBarIcon] 统一规格）。
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

    val hairlineColor = palette.borderMuted.copy(alpha = 0.55f)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .drawBehind {
                if (scrolled) {
                    val strokePx = PiSpacing.hairlineThin.toPx()
                    drawLine(
                        color = hairlineColor,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height - strokePx / 2f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height - strokePx / 2f),
                        strokeWidth = strokePx,
                    )
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PiSpacing.chatTopBarHeight)
                .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PiTopBarIcon(
                onClick = {
                    hideIme()
                    onOpenSessions()
                },
                contentDescription = tr("打开会话列表", "Open sessions"),
                icon = Icons.Filled.Menu,
            )

            // 标题区：呼吸状态点 + 会话名 + 项目名/扩展标题（点击同样平滑滑入会话抽屉）
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
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = tr("打开会话列表", "Open sessions")) {
                        hideIme()
                        onOpenSessions()
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiEngineStatusDot(statusLabel = engineLabel)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = sessionTitle ?: tr("新对话", "New Chat"),
                        style = PiType.topBarTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitleText.isNullOrBlank()) {
                        Text(
                            text = subtitleText,
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 右侧高频直达图标：新建对话 + 终端（带后台存活状态点）+ 项目与文件 +（查找态/宽屏搜索）+ 更多
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PiTopBarIcon(
                    onClick = {
                        hideIme()
                        onAction(ChatAction.NewSession)
                    },
                    contentDescription = tr("新建会话", "New session"),
                    icon = Icons.Filled.Add,
                )
                Box {
                    PiTopBarIcon(
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
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(palette.success),
                        )
                    }
                }
                PiTopBarIcon(
                    onClick = {
                        hideIme()
                        onOpenWorkspace()
                    },
                    contentDescription = tr("项目与文件", "Project & Files"),
                    icon = Icons.Filled.FolderOpen,
                )
                if (wideScreen || searchOpen) {
                    PiTopBarIcon(
                        onClick = { onToggleSearch(false) },
                        contentDescription = if (searchOpen) tr("关闭对话内查找", "Close search") else tr("在对话里查找", "Search in chat"),
                        icon = Icons.Filled.Search,
                        highlighted = searchOpen,
                    )
                }
                PiTopBarIcon(
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
private fun ChatCommandCenterSheet(
    engineLabel: String,
    sessionTitle: String?,
    workspaceName: String?,
    searchOpen: Boolean,
    toolsExpanded: Boolean,
    toolDisplayMode: String,
    onDismiss: () -> Unit,
    onToggleSearch: () -> Unit,
    onJumpQuestion: (forward: Boolean) -> Unit,
    onToggleTools: () -> Unit,
    onSelectToolDisplayMode: (String) -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    val palette = PiTheme.palette
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = sessionTitle ?: tr("当前对话", "Current Chat"),
                        style = PiType.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!workspaceName.isNullOrBlank()) {
                        Text(
                            text = workspaceName,
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = engineLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg)
                        .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    style = PiTheme.text.meta,
                    color = stateToneColor(engineStateTone(engineLabel), palette),
                )
            }

            // 顶部 2×3 快捷磁贴网格（高频核心入口，零重复）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickTile(
                        icon = Icons.Filled.AccountTree,
                        label = tr("会话树", "Session Tree"),
                        tint = palette.accent,
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.OpenTree) },
                    )
                    QuickTile(
                        icon = Icons.AutoMirrored.Filled.Article,
                        label = tr("系统提示词", "System Prompt"),
                        tint = palette.borderAccent,
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.ViewSystemPrompt) },
                    )
                    QuickTile(
                        icon = Icons.Filled.Refresh,
                        label = tr("重载扩展", "Reload"),
                        tint = palette.success,
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
                        tint = palette.mdLink,
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.ExportSession) },
                    )
                    QuickTile(
                        icon = Icons.Filled.Info,
                        label = tr("会话统计", "Session Stats"),
                        tint = palette.warning,
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.SessionStats) },
                    )
                    QuickTile(
                        icon = Icons.Filled.Settings,
                        label = tr("设置", "Settings"),
                        tint = palette.muted,
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(ChatAction.OpenSettings) },
                    )
                }
            }

            // 显示与导航组
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
                    onClick = onToggleSearch,
                )
                SheetHairline()
                // 并排双按钮连续跳提问，点击不强关面板，带触感反馈
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg)
                            .clickable {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onJumpQuestion(false)
                            }
                            .padding(vertical = 8.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(17.dp), tint = palette.accent)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("上一条提问", "Prev prompt"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg)
                            .clickable {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onJumpQuestion(true)
                            }
                            .padding(vertical = 8.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(17.dp), tint = palette.accent)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("下一条提问", "Next prompt"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
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

            // 会话与策略组
            ActionGroupCard(title = tr("当前会话与策略", "Session & Policy")) {
                SheetActionRow(
                    icon = Icons.Filled.Edit,
                    label = tr("重命名会话", "Rename session"),
                    onClick = { onAction(ChatAction.RenameSession) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.AccountTree,
                    label = tr("从历史消息分叉", "Fork from message"),
                    subtitle = tr("回到某条用户消息创建独立分支会话", "Branch into a new session from an earlier prompt"),
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
                    onClick = { onAction(ChatAction.ToolsSheet) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.FileUpload,
                    label = tr("导入 JSONL 会话文件", "Import JSONL session"),
                    onClick = { onAction(ChatAction.ImportSession) },
                )
            }
        }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
            .border(PiSpacing.hairlineThin, PiTheme.palette.borderMuted.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tint,
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
    val shape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = PiTheme.palette.muted,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
                .border(PiSpacing.hairlineThin, PiTheme.palette.borderMuted.copy(alpha = 0.45f), shape),
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
        Column(modifier = Modifier.weight(1f)) {
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
                    .border(PiSpacing.hairlineThin, PiTheme.palette.borderMuted.copy(alpha = 0.42f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
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
    val normalized = ActivityDisplayMode.normalize(selectedMode)
    val isCompact = normalized == ActivityDisplayMode.COMPACT
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
            Column(modifier = Modifier.weight(1f)) {
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
        val itemShape = RoundedCornerShape(8.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(trackShape)
                .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val options = listOf(
                ActivityDisplayMode.CARDS to tr("Pi 原版", "Pi Original"),
                ActivityDisplayMode.COMPACT to tr("专注折叠", "Compact"),
            )
            for ((value, label) in options) {
                val selected = normalized == value
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(itemShape)
                        .then(
                            if (selected) {
                                Modifier
                                    .background(palette.accent.copy(alpha = 0.14f))
                                    .border(0.5.dp, palette.accent.copy(alpha = 0.42f), itemShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable(role = Role.RadioButton, onClick = { onSelect(value) })
                        .padding(vertical = 7.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(palette.accent),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = label,
                        style = PiTheme.text.meta.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) palette.accent else palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
