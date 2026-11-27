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
import kotlinx.coroutines.launch
import app.pi.ui.render.PiMarkdownText
import app.pi.host.HostCommands
import app.pi.host.HostAuth
import androidx.compose.runtime.rememberCoroutineScope
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

/** 队列与自动容错策略面板：不再与右上角命令中心重复堆叠会话操作，专注 Steering / Follow-up 队列策略与自动压缩/重试开关。 */
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
    onOpenSettings: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tr("队列与自动容错策略", "Queue & Auto-Policy"),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    tr("控制模型运行期间发送的新消息如何插队或排队，以及上下文与重试自动化。", "Configure how messages sent mid-turn are delivered, plus auto-compaction and retry."),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }

            // 1. 队列投递策略卡
            SheetCardSection(title = tr("消息队列策略", "Message Queue")) {
                QueueModeRow(
                    title = tr("引导消息（Steering）", "Steering Messages"),
                    supporting = tr("本回合工具跑完后、下次回答前插队投递", "Delivered after current tools finish, before the next reply"),
                    current = state.meta.steeringMode,
                    tone = palette.warning,
                    onPick = onSteeringMode,
                )
                HorizontalDivider(thickness = PiSpacing.hairlineThin, color = palette.borderMuted.copy(alpha = 0.7f))
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
                HorizontalDivider(thickness = PiSpacing.hairlineThin, color = palette.borderMuted.copy(alpha = 0.7f))
                PiSwitchRow(
                    title = tr("自动退避重试", "Auto-Retry"),
                    supporting = tr("遇到限流或网络抖动时自动重试", "Retry automatically on rate limits or transient errors"),
                    checked = state.meta.autoRetry,
                    onCheckedChange = onAutoRetry,
                )
                HorizontalDivider(thickness = PiSpacing.hairlineThin, color = palette.borderMuted.copy(alpha = 0.7f))
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

            // 3. 直达新版细粒度设置页（与设置中「对话与消息 / 压缩与窗口 / 网络与重试」互通）
            if (onOpenSettings != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val links = listOf(
                        "steeringMode" to tr("对话与队列设置 →", "Chat & Queue →"),
                        "compaction.enabled" to tr("压缩与窗口设置 →", "Compaction →"),
                        "retry.enabled" to tr("网络与重试设置 →", "Network & Retry →"),
                    )
                    links.forEach { (key, label) ->
                        Surface(
                            onClick = {
                                onDismiss()
                                onOpenSettings(key)
                            },
                            shape = RoundedCornerShape(9.dp),
                            color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
                            border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f)),
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueModeRow(
    title: String,
    supporting: String,
    current: QueueMode,
    @Suppress("UNUSED_PARAMETER") tone: Color,
    onPick: (QueueMode) -> Unit,
) {
    val isDark = PiTheme.isDark
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val trackBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    val activeBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
    val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f)
    val activeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.75f else 0.90f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                supporting,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
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
                    QueueMode.OneAtATime to tr("逐条投递", "One at a time"),
                    QueueMode.All to tr("合并全部", "All at once"),
                )
                options.forEach { (mode, label) ->
                    val active = current == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (active) activeBg else Color.Transparent)
                            .border(
                                width = if (active) PiSpacing.hairlineThin else 0.dp,
                                color = if (active) activeBorder else Color.Transparent,
                                shape = RoundedCornerShape(7.dp),
                            )
                            .clickable {
                                if (!active) {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    onPick(mode)
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = PiTheme.text.meta.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
                    color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f)),
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
                            shape = RoundedCornerShape(11.dp),
                            color = palette.cardBg,
                            border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
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
                .clip(RoundedCornerShape(10.dp))
                .background(palette.cardBg)
                .border(1.dp, palette.accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        val trimmed = name.trim()
                        val hadInitial = !initial.isNullOrBlank()
                        if (trimmed.isNotEmpty() || hadInitial) onConfirm(trimmed)
                    },
                ),
                decorationBox = { inner ->
                    Box {
                        if (name.isEmpty()) {
                            Text(
                                text = if (!initial.isNullOrBlank()) {
                                    tr("留空可清除自定义名称…", "Leave blank to clear name…")
                                } else {
                                    tr("输入新名称…", "Enter a new name…")
                                },
                                style = PiTheme.text.prose,
                                color = palette.muted,
                            )
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
        val canSubmit = name.isNotBlank() || !initial.isNullOrBlank()
        val clearing = name.isBlank() && !initial.isNullOrBlank()
        PiDialogActions {
            PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = onDismiss)
            PiDialogAction(
                label = if (clearing) tr("清除名称", "Clear name") else tr("保存", "Save"),
                primary = true,
                enabled = canSubmit,
                onClick = { onConfirm(name.trim()) },
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
            "project_context" -> {
                val count = PROJECT_INSTRUCTIONS_REGEX.findAll(body).count()
                if (count > 0) tr("项目上下文 ($count)", "Context ($count)") else tr("项目上下文", "Context")
            }
            "skills" -> tr("技能", "Skills")
            "cwd" -> "CWD"
            else -> tag
        }
        sections += PromptSectionSlice(tag, label, body)
    }
    return sections
}

private val PROJECT_INSTRUCTIONS_REGEX = Regex("""<project_instructions\s+path="[^"]+">""")

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
    val isZh = app.pi.i18n.PiLocale.isZh
    val sections = remember(raw, isZh) { parseSystemPromptSections(raw) }
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
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) palette.accent else palette.cardBg,
                            border = if (active) null else BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
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
                color = palette.cardBg,
                border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.65f)),
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
                    else -> androidx.compose.foundation.text.selection.SelectionContainer {
                        Column(
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
}


/**
 * `/changelog`：直读官方 Pi SDK `CHANGELOG.md` 与 `VERSION` 并用应用内 Markdown 管线渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangelogSheet(
    session: PiSessionViewModel,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<HostCommands.ChangelogResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        loading = true
        error = null
        val cmds = session.hostCommands()
        if (cmds == null) {
            error = tr(
                "引擎未运行：启动后即可读取当前内置 Pi SDK 的官方更新日志。",
                "Engine is not running. Start it to read the bundled Pi SDK changelog.",
            )
        } else {
            runCatching { cmds.changelog() }
                .onSuccess { raw ->
                    val transformed = if (raw.markdown.isNotBlank()) {
                        session.markdownTransform.transform(raw.markdown, "changelog", "changelog")
                    } else {
                        raw.markdown
                    }
                    result = if (transformed == raw.markdown) raw else raw.copy(markdown = transformed)
                }
                .onFailure { error = it.message ?: tr("读取更新日志失败", "Failed to read changelog") }
        }
        loading = false
    }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = tr("Pi 更新日志", "Pi Changelog"),
                        style = PiType.title,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val ver = result?.version.orEmpty()
                    if (ver.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniBadge("Pi SDK v$ver", palette.accent)
                            if (result?.truncated == true) {
                                MiniBadge(tr("已展示最新版本", "Latest entries"), palette.muted)
                            }
                        }
                    }
                }
                SheetActionPill(
                    text = tr("关闭", "Close"),
                    primary = false,
                    onClick = onDismiss,
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = palette.cardBg,
                border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
            ) {
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr("正在读取官方更新日志…", "Loading official changelog…"),
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
                    result?.markdown.isNullOrBlank() -> Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr("当前内置 SDK 包未附带 CHANGELOG.md。", "No CHANGELOG.md found in the bundled SDK package."),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                    }
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        PiMarkdownText(
                            markdown = result?.markdown.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * `/logout`：就地列出当前已配置登录凭据的服务商，支持一键退出，无需跳转离屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogoutPickerSheet(
    session: PiSessionViewModel,
    onManageProviders: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var providers by remember { mutableStateOf<List<HostAuth.Provider>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingLogout by remember { mutableStateOf<HostAuth.Provider?>(null) }

    LaunchedEffect(session) {
        loading = true
        error = null
        val auth = session.hostAuth()
        if (auth == null) {
            error = tr("引擎未运行，暂时无法读取登录状态。", "Engine is not running; cannot read provider login status.")
        } else {
            runCatching { auth.providers().filter { it.configured } }
                .onSuccess { providers = it }
                .onFailure { error = it.message ?: tr("读取服务商列表失败", "Failed to load providers") }
        }
        loading = false
    }

    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = tr("退出服务商登录", "Log Out Provider"),
                        style = PiType.title,
                        color = palette.text,
                    )
                    Text(
                        text = tr("点按即可移除本地保存的 OAuth 令牌或 API Key", "Tap to remove locally stored OAuth tokens or API keys"),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
                SheetActionPill(
                    text = tr("供应商管理", "Providers"),
                    primary = false,
                    onClick = {
                        onDismiss()
                        onManageProviders()
                    },
                )
            }

            when {
                loading -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
                ) {
                    Text(
                        text = tr("正在读取已登录的服务商…", "Loading signed-in providers…"),
                        modifier = Modifier.padding(16.dp),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
                error != null -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
                ) {
                    Text(
                        text = error.orEmpty(),
                        modifier = Modifier.padding(16.dp),
                        style = PiTheme.text.meta,
                        color = palette.warning,
                    )
                }
                providers.isEmpty() -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
                ) {
                    Text(
                        text = tr("当前没有已保存登录凭据的服务商。", "No providers currently have saved login credentials."),
                        modifier = Modifier.padding(16.dp),
                        style = PiTheme.text.prose,
                        color = palette.muted,
                    )
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { provider ->
                        val signingOut = busyId == provider.id
                        val badgeLabel = when {
                            provider.usingSubscription -> tr("订阅", "Subscription")
                            provider.usingOAuth -> "OAuth"
                            else -> "API Key"
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            color = palette.cardBg,
                            border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = provider.name,
                                            style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                            color = palette.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        MiniBadge(badgeLabel, palette.accent)
                                    }
                                    Text(
                                        text = provider.id,
                                        style = PiTheme.text.monoSmall,
                                        color = palette.dim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Surface(
                                    onClick = {
                                        if (busyId != null) return@Surface
                                        confirmingLogout = provider
                                    },
                                    enabled = busyId == null,
                                    shape = RoundedCornerShape(8.dp),
                                    color = palette.error.copy(alpha = 0.12f),
                                    border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.40f)),
                                ) {
                                    Text(
                                        text = if (signingOut) tr("退出中…", "Signing out…") else tr("退出登录", "Log Out"),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmingLogout?.let { target ->
        app.pi.ui.components.PiDialog(onDismissRequest = { confirmingLogout = null }) {
            app.pi.ui.components.PiDialogTitle(
                title = tr("退出服务商登录？", "Log Out Provider?"),
                glyph = "!",
                glyphTone = palette.error,
            )
            app.pi.ui.components.PiDialogBody(
                tr(
                    "将移除本地保存的「${target.name}」登录凭据，之后需要重新登录或配置密钥。",
                    "Locally stored credentials for \"${target.name}\" will be removed. You will need to sign in again to use its models.",
                ),
            )
            app.pi.ui.components.PiDialogActions {
                app.pi.ui.components.PiDialogAction(
                    label = tr("取消", "Cancel"),
                    primary = false,
                    onClick = { confirmingLogout = null },
                )
                app.pi.ui.components.PiDialogAction(
                    label = tr("退出登录", "Log Out"),
                    primary = true,
                    tone = palette.error,
                    onClick = {
                        confirmingLogout = null
                        val auth = session.hostAuth() ?: return@PiDialogAction
                        busyId = target.id
                        scope.launch {
                            runCatching { auth.logout(target.id) }
                                .onSuccess {
                                    providers = providers.filterNot { it.id == target.id }
                                    session.refreshModels()
                                    session.notifyUser(
                                        tr("已退出「${target.name}」的登录凭据。", "Logged out of \"${target.name}\"."),
                                    )
                                }
                                .onFailure { err ->
                                    error = err.message ?: tr("退出登录失败", "Failed to log out")
                                }
                            busyId = null
                        }
                    },
                )
            }
        }
    }
}
