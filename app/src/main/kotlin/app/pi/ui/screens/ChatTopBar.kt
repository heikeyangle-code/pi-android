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
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.chat.ChatAction
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import app.pi.ui.theme.engineStateTone
import app.pi.ui.theme.stateToneColor

/**
 * 全新现代对话顶栏 + 分类命令中心底部面板：
 *  - 左侧：动态呼吸引擎状态点 + 会话标题 + 工作区微标签 + 下拉箭头（一键打开会话列表/会话树）；
 *  - 右侧：+ 新建会话（一键直达！）、搜索、工作区、⋯ 更多（打开现代网格+分组底部命令面板，彻底取代旧版 19 行细长弹窗）。
 */
@Composable
internal fun ChatTopBar(
    engineLabel: String,
    sessionTitle: String?,
    workspaceName: String? = null,
    searchOpen: Boolean,
    scrolled: Boolean,
    toolsExpanded: Boolean,
    onOpenSessions: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onToggleSearch: (clearOnClose: Boolean) -> Unit,
    onJumpQuestion: (forward: Boolean) -> Unit,
    onToggleTools: () -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    var commandCenterOpen by remember { mutableStateOf(false) }
    val palette = PiTheme.palette
    val tone = engineStateTone(engineLabel)
    val engineDotColor = stateToneColor(tone, palette)
    val isActiveWork = tone == StateTone.Warning
    // State 本身往下传，只在 graphicsLayer 里读：以前在组合里读 `.value`，引擎工作期间整条顶栏
    // 每帧重组一次（60 次/秒），现在只重绘那一个点。
    val dotAlpha: androidx.compose.runtime.State<Float>? = if (isActiveWork) {
        rememberInfiniteTransition(label = "engine-dot").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
            label = "engine-dot-alpha",
        )
    } else {
        null
    }
    val widePhone = LocalConfiguration.current.screenWidthDp >= 360

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PiSpacing.chatTopBarHeight)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 左侧：会话入口胶囊（状态点 + 会话名 + 工作区副标 + ▾）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = tr("打开会话列表", "Open sessions"), onClick = onOpenSessions)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = dotAlpha?.value ?: 1f }
                        .clip(CircleShape)
                        .background(engineDotColor)
                        .semantics { contentDescription = tr("引擎：$engineLabel", "Engine: $engineLabel") },
                )
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sessionTitle ?: tr("新对话", "New Chat"),
                            modifier = Modifier.weight(1f, fill = false),
                            style = PiType.topBarTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = palette.muted,
                        )
                    }
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
            }

            // 右侧高频直达图标：新建对话 + 搜索 + 工作区 + 更多
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatTopBarAction(
                    onClick = { onAction(ChatAction.NewSession) },
                    contentDescription = tr("新建会话", "New session"),
                    icon = Icons.Filled.Add,
                )
                if (widePhone) {
                    ChatTopBarAction(
                        onClick = { onToggleSearch(false) },
                        contentDescription = if (searchOpen) tr("关闭对话内查找", "Close search") else tr("在对话里查找", "Search in chat"),
                        icon = Icons.Filled.Search,
                        highlighted = searchOpen,
                    )
                }
                ChatTopBarAction(
                    onClick = onOpenWorkspace,
                    contentDescription = tr("项目与文件", "Project & Files"),
                    icon = Icons.Filled.FolderOpen,
                )
                ChatTopBarAction(
                    onClick = { commandCenterOpen = true },
                    contentDescription = tr("更多操作", "More actions"),
                    icon = Icons.Filled.MoreHoriz,
                )
            }
        }
        if (scrolled) {
            HorizontalDivider(
                thickness = PiSpacing.hairlineThin,
                color = palette.borderMuted.copy(alpha = 0.55f),
            )
        }
    }

    if (commandCenterOpen) {
        ChatCommandCenterSheet(
            engineLabel = engineLabel,
            searchOpen = searchOpen,
            toolsExpanded = toolsExpanded,
            onDismiss = { commandCenterOpen = false },
            onToggleSearch = {
                commandCenterOpen = false
                onToggleSearch(true)
            },
            onJumpQuestion = { forward ->
                commandCenterOpen = false
                onJumpQuestion(forward)
            },
            onToggleTools = {
                commandCenterOpen = false
                onToggleTools()
            },
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
    searchOpen: Boolean,
    toolsExpanded: Boolean,
    onDismiss: () -> Unit,
    onToggleSearch: () -> Unit,
    onJumpQuestion: (forward: Boolean) -> Unit,
    onToggleTools: () -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    val palette = PiTheme.palette
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
            ) {
                Text(
                    text = tr("对话操作", "Chat Actions"),
                    modifier = Modifier.weight(1f),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = engineLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    style = PiTheme.text.meta,
                    color = stateToneColor(engineStateTone(engineLabel), palette),
                )
            }

            // 顶部 2×3 快捷磁贴网格（高频核心入口，不再与顶栏「+ 新建会话」重复）
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
                        icon = Icons.Filled.Article,
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
                        label = tr("会话信息", "Session Info"),
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
            ActionGroupCard(title = tr("显示与导航", "View & Navigation")) {
                SheetActionRow(
                    icon = if (toolsExpanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                    label = if (toolsExpanded) tr("收起全部工具输出", "Collapse all tool outputs") else tr("展开全部工具输出", "Expand all tool outputs"),
                    trailingBadge = if (toolsExpanded) tr("已展开", "Expanded") else tr("已收起", "Collapsed"),
                    onClick = onToggleTools,
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.Search,
                    label = if (searchOpen) tr("关闭对话内查找", "Close search") else tr("在对话里查找", "Search in chat"),
                    onClick = onToggleSearch,
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.KeyboardArrowUp,
                    label = tr("跳到上一条提问", "Previous prompt"),
                    onClick = { onJumpQuestion(false) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.KeyboardArrowDown,
                    label = tr("跳到下一条提问", "Next prompt"),
                    onClick = { onJumpQuestion(true) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = tr("复制最后一条回复", "Copy last response"),
                    onClick = { onAction(ChatAction.CopyLastAssistant) },
                )
            }

            // 会话管理组
            ActionGroupCard(title = tr("当前会话", "Current Session")) {
                SheetActionRow(
                    icon = Icons.Filled.Edit,
                    label = tr("重命名会话", "Rename session"),
                    onClick = { onAction(ChatAction.RenameSession) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.AccountTree,
                    label = tr("从历史消息分叉", "Fork from message"),
                    subtitle = tr("回到某条消息创建新分支会话", "Create a new session branched from an earlier message"),
                    onClick = { onAction(ChatAction.PickFork) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = tr("复制当前会话", "Clone session"),
                    onClick = { onAction(ChatAction.CloneSession) },
                )
                SheetHairline()
                SheetActionRow(
                    icon = Icons.Filled.Tune,
                    label = tr("会话与队列设置", "Session & queue settings"),
                    subtitle = tr("引导/后续模式、自动压缩、重试", "Steer/follow-up mode, auto-compaction, retry"),
                    onClick = { onAction(ChatAction.ToolsSheet) },
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
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, PiTheme.palette.borderMuted.copy(alpha = 0.45f), shape)
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
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, PiTheme.palette.borderMuted.copy(alpha = 0.4f), shape),
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
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
            )
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

@Composable
private fun ChatTopBarAction(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    highlighted: Boolean = false,
) {
    val accent = PiTheme.palette.accent
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (highlighted) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(21.dp),
            tint = if (highlighted) accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}
