package app.pi.ui.chat

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import app.pi.protocol.QueueMode
import app.pi.ui.PiSessionViewModel
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSwitchRow
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn

/** 会话控制中心：现代队列切换卡、自动化开关卡 + 2 列快捷操作网格。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionToolsSheet(
    state: PiSessionViewModel.UiState,
    onSteeringMode: (QueueMode) -> Unit,
    onFollowUpMode: (QueueMode) -> Unit,
    onAutoCompaction: (Boolean) -> Unit,
    onAutoRetry: (Boolean) -> Unit,
    onAbortRetry: () -> Unit,
    onCompact: () -> Unit,
    onStats: () -> Unit,
    onTree: () -> Unit,
    onFork: () -> Unit,
    onClone: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onCopyLast: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                tr("会话控制中心", "Session Controls"),
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 1. 队列投递策略卡
            SheetCardSection(title = tr("消息队列策略", "Message Queue")) {
                QueueModeRow(
                    title = tr("引导消息（Steering）", "Steering Messages"),
                    supporting = tr("本回合工具跑完后、下次回答前插队投递", "Delivered after current tools finish, before the next reply"),
                    current = state.meta.steeringMode,
                    tone = palette.warning,
                    onPick = onSteeringMode,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                QueueModeRow(
                    title = tr("后续消息（Follow-up）", "Follow-up Messages"),
                    supporting = tr("整轮回复结束后顺序投递", "Delivered in order after the current turn completes"),
                    current = state.meta.followUpMode,
                    tone = palette.accent,
                    onPick = onFollowUpMode,
                )
            }

            // 2. 自动压缩与重试卡
            SheetCardSection(title = tr("上下文与容错", "Context & Reliability")) {
                PiSwitchRow(
                    title = tr("自动压缩上下文", "Auto-Compaction"),
                    supporting = tr("接近窗口上限时自动生成摘要", "Automatically summarize when nearing context limit"),
                    checked = state.meta.autoCompaction,
                    onCheckedChange = onAutoCompaction,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                PiSwitchRow(
                    title = tr("自动退避重试", "Auto-Retry"),
                    supporting = tr("遇到限流或网络抖动时自动重试", "Retry automatically on rate limits or transient errors"),
                    checked = state.meta.autoRetry,
                    onCheckedChange = onAutoRetry,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SheetActionPill(
                        text = tr("立即压缩上下文", "Compact Now"),
                        primary = true,
                        onClick = onCompact,
                        modifier = Modifier.weight(1f),
                    )
                    SheetActionPill(
                        text = tr("取消等待重试", "Abort Retry"),
                        primary = false,
                        onClick = onAbortRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 3. 会话快捷操作 2 列网格
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("会话操作", "Session Actions"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.muted,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionGridTile(
                        icon = Icons.Filled.AccountTree,
                        tint = palette.accent,
                        title = tr("会话树", "Session Tree"),
                        subtitle = tr("查看分支结构", "Navigate branches"),
                        onClick = onTree,
                        modifier = Modifier.weight(1f),
                    )
                    SessionGridTile(
                        icon = Icons.Filled.CallSplit,
                        tint = palette.warning,
                        title = tr("历史分叉", "Fork"),
                        subtitle = tr("从指定消息分支", "Branch from message"),
                        onClick = onFork,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionGridTile(
                        icon = Icons.Filled.FileCopy,
                        tint = palette.success,
                        title = tr("复制会话", "Clone"),
                        subtitle = tr("克隆独立副本", "Duplicate session"),
                        onClick = onClone,
                        modifier = Modifier.weight(1f),
                    )
                    SessionGridTile(
                        icon = Icons.Filled.DriveFileRenameOutline,
                        tint = palette.borderAccent,
                        title = tr("重命名", "Rename"),
                        subtitle = state.meta.sessionName ?: tr("未命名", "Untitled"),
                        onClick = onRename,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionGridTile(
                        icon = Icons.Filled.IosShare,
                        tint = palette.accent,
                        title = tr("导出会话", "Export"),
                        subtitle = tr("生成完整 HTML", "Save HTML & JSONL"),
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                    )
                    SessionGridTile(
                        icon = Icons.Filled.ContentCopy,
                        tint = palette.muted,
                        title = tr("复制末条回复", "Copy Last Reply"),
                        subtitle = tr("直接进剪贴板", "Copy to clipboard"),
                        onClick = onCopyLast,
                        modifier = Modifier.weight(1f),
                    )
                }
                SessionGridTile(
                    icon = Icons.Filled.Info,
                    tint = palette.muted,
                    title = tr("会话元数据信息", "Session Info & Stats"),
                    subtitle = tr("ID、文件路径、消息与工具计数", "ID, file path, message & tool counts"),
                    onClick = onStats,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun QueueModeRow(
    title: String,
    supporting: String,
    current: QueueMode,
    tone: Color,
    onPick: (QueueMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = PiType.rowTitle.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            supporting,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val options = listOf(
                QueueMode.OneAtATime to tr("逐条投递", "One at a time"),
                QueueMode.All to tr("合并全部", "All at once"),
            )
            options.forEach { (mode, label) ->
                val active = current == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) tone.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onPick(mode) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = PiTheme.text.meta.copy(
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (active) tone else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionGridTile(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    title,
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 分叉选择器：编号卡片列表，严格保留 pi 可分叉消息顺序。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForkPickerSheet(
    messages: List<PiResponses.ForkMessage>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tr("从历史消息分叉", "Fork from Message"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    tr(
                        "将在所选消息之前分出新会话，并把该消息放回输入框便于修改重发，原会话保持不变。",
                        "Creates a new session before the selected message and restores it to the composer. The original session is untouched.",
                    ),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
            if (messages.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        tr(
                            "当前会话还没有可分叉的用户消息。先发送一条消息落盘后再试。",
                            "No forkable user messages yet. Send a message first.",
                        ),
                        modifier = Modifier.padding(14.dp),
                        style = PiTheme.text.prose,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(messages, key = { _, m -> m.entryId }) { index, message ->
                        Surface(
                            onClick = {
                                onPick(message.entryId)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.accent.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.accent,
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        message.text.ifBlank { tr("（空消息）", "(empty message)") },
                                        style = PiTheme.text.prose,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        message.entryId,
                                        style = PiTheme.text.monoSmall,
                                        color = palette.dim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 会话重命名弹窗：统一至 PiDialog 家族。 */
@Composable
fun RenameSessionDialog(
    initial: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    var name by remember { mutableStateOf(initial.orEmpty()) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    PiDialog(onDismissRequest = onDismiss) {
        app.pi.ui.components.PiAutoFocus(focus)
        PiDialogTitle(tr("重命名会话", "Rename Session"))
        PiDialogBody(tr("修改后将立即同步到会话列表。", "Changes sync to the session list immediately."))
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, palette.borderAccent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (name.isEmpty()) {
                            Text(tr("输入新名称…", "Enter a new name…"), style = PiTheme.text.prose, color = palette.muted)
                        }
                        inner()
                    }
                },
            )
            if (name.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = tr("清空", "Clear")) { name = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("清空", "Clear"),
                        tint = palette.muted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        PiDialogActions {
            PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = onDismiss)
            PiDialogAction(
                label = tr("保存", "Save"),
                primary = true,
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            )
        }
    }
}

// ------------------------------------------------------------------ 当前生效的系统提示词

private data class PromptSectionSlice(
    val id: String,
    val label: String,
    val content: String,
)

private fun parseSystemPromptSections(raw: String): List<PromptSectionSlice> {
    if (raw.isBlank()) return emptyList()
    val tagRegex = Regex("<([a-z][a-z0-9_-]*)>\\n([\\s\\S]*?)\\n</\\1>")
    val matches = tagRegex.findAll(raw).toList()
    if (matches.isEmpty()) return emptyList()
    val sections = ArrayList<PromptSectionSlice>()
    val preamble = raw.substring(0, matches.first().range.first).trim()
    if (preamble.isNotEmpty()) {
        sections += PromptSectionSlice("preamble", tr("前言", "Preamble"), preamble)
    }
    for (m in matches) {
        val tag = m.groupValues[1]
        val body = m.groupValues[2].trim()
        val label = when (tag) {
            "tools" -> tr("工具清单", "Tools")
            "rules" -> tr("规则", "Rules")
            "docs" -> tr("文档", "Docs")
            "addendum" -> tr("追加指令", "Addendum")
            "project_context" -> tr("项目上下文", "Context")
            "skills" -> tr("技能", "Skills")
            "cwd" -> "CWD"
            else -> tag
        }
        sections += PromptSectionSlice(tag, label, body)
    }
    return sections
}

/**
 * 对话页右上角命令中心直达的「当前生效系统提示词」查阅弹层：
 * 与 `ChatCommandCenterSheet` 共用同一套现代卡片视觉，自动解析 Pi 1.6.0 `<tools>/<rules>/<project_context>/<skills>/<addendum>` 分段，
 * 支持分段快速筛选、一键复制、一键跳转编辑提示词与上下文文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveSystemPromptSheet(
    session: PiSessionViewModel,
    onEditPrompt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var loading by remember { mutableStateOf(true) }
    var promptText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember { mutableStateOf("all") }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        loading = true
        error = null
        val api = session.advanced()
        if (api == null) {
            error = tr(
                "引擎未运行：启动后即可读取此刻发给模型的完整系统提示词。",
                "Engine is not running. Start it to inspect the active full system prompt.",
            )
        } else {
            runCatching { api.systemPrompt() }
                .onSuccess { promptText = it }
                .onFailure { error = it.message ?: tr("读取系统提示词失败", "Failed to read system prompt") }
        }
        loading = false
    }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }

    val raw = promptText.orEmpty()
    val sections = remember(raw) { parseSystemPromptSections(raw) }
    val visibleText = remember(raw, sections, selectedSection) {
        if (selectedSection == "all") {
            raw
        } else {
            sections.firstOrNull { it.id == selectedSection }?.content ?: raw
        }
    }
    val estTokens = remember(visibleText) { (visibleText.length + 3) / 4 }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部标题 + 指标胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = tr("当前生效系统提示词", "Active System Prompt"),
                        style = PiType.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!loading && error == null && raw.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniBadge(tr("${visibleText.length} 字符", "${visibleText.length} chars"), palette.accent)
                            MiniBadge(tr("约 $estTokens token", "~$estTokens tok"), palette.muted)
                            if (sections.isNotEmpty()) {
                                MiniBadge(tr("${sections.size} 分段", "${sections.size} sections"), palette.success)
                            }
                        }
                    }
                }
                SheetActionPill(
                    text = if (copied) tr("已复制", "Copied") else tr("复制", "Copy"),
                    primary = false,
                    onClick = {
                        if (visibleText.isNotEmpty()) {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(visibleText))
                            copied = true
                        }
                    },
                )
                SheetActionPill(
                    text = tr("编辑", "Edit"),
                    primary = true,
                    onClick = {
                        onDismiss()
                        onEditPrompt()
                    },
                )
            }

            // 分段快速筛选条
            if (!loading && error == null && sections.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val chips = listOf("all" to tr("全部", "All")) + sections.map { it.id to it.label }
                    chips.forEach { (id, label) ->
                        val active = selectedSection == id
                        Surface(
                            onClick = { selectedSection = id },
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) palette.accent else MaterialTheme.colorScheme.surfaceContainer,
                            border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                style = PiTheme.text.meta.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                                color = if (active) palette.contrastOn(palette.accent) else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // 正文卡片
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr("正在向引擎读取当前系统提示词…", "Reading active system prompt from engine…"),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                    }
                    error != null -> Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text(
                            text = error.orEmpty(),
                            style = PiTheme.text.meta,
                            color = palette.warning,
                        )
                    }
                    visibleText.isBlank() -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr("（当前系统提示词为空）", "(System prompt is empty)"),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                    }
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                    ) {
                        Text(
                            text = visibleText,
                            style = PiTheme.text.monoSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
