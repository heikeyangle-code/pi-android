package app.pi.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.PiResponses
import app.pi.rpc.QueueMode
import app.pi.ui.PiSessionViewModel
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.components.PiSwitchRow
import app.pi.ui.components.PiValueRow
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * The model picker.
 *
 * The list is exactly `get_available_models` — pi's own availability snapshot
 * (`rpc-mode.ts:490-493` → `modelRuntime.getAvailableSnapshot()`), which is also
 * what makes extension-registered providers reachable
 * (`model-runtime.ts:766-771`; audit §1.1 `registerProvider`). Nothing is
 * filtered or re-ordered here, and the current model is marked rather than moved
 * to the top, so the order is pi's and the list does not jump when the selection
 * changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<PiResponses.ModelInfo>,
    current: PiResponses.ModelInfo?,
    busy: Boolean,
    onPick: (PiResponses.ModelInfo) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val query = filter.trim().lowercase()
    val shown = if (query.isEmpty()) {
        models
    } else {
        models.filter {
            it.id.lowercase().contains(query) ||
                it.name.lowercase().contains(query) ||
                it.provider.orEmpty().lowercase().contains(query)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen)) {
            Text("选择模型", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "来自 pi 的可用模型快照（get_available_models），包含扩展注册的 provider。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("按 provider / 模型名筛选") },
                shape = PiShapes.input,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            if (shown.isEmpty()) {
                Text(
                    if (busy) "正在读取模型列表…" else "没有可用模型。pi 需要至少一个已配置认证的 provider。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PiSpacing.unit))
                TextButton(onClick = onRefresh) { Text("重新读取") }
            } else {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    shown.forEach { model ->
                        ModelRow(
                            model = model,
                            selected = model.id == current?.id && model.provider == current.provider,
                            onClick = { onPick(model) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

@Composable
private fun ModelRow(model: PiResponses.ModelInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                buildString {
                    model.provider?.let { append(it).append("/") }
                    append(model.id)
                    model.contextWindow?.let { append(" · ").append(formatTokens(it)).append(" 上下文") }
                    if (model.reasoning) append(" · 思考")
                    if (model.acceptsImages) append(" · 图片")
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            model.inputCost?.let { cost ->
                Text(
                    "$${trimCost(cost)} / M 输入" + (model.outputCost?.let { " · $${trimCost(it)} / M 输出" } ?: ""),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The thinking-level picker.
 *
 * The set comes from `get_available_thinking_levels`, which pi derives from the
 * selected model (`agent-session.ts` `getAvailableThinkingLevels`), and the
 * labels are pi's own level names — a level this build does not know is shown as
 * pi's raw string rather than mapped onto a familiar one, because pi adds levels
 * in minor versions and renaming one would misreport what the user selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingPickerSheet(
    levels: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen)) {
            Text("思考等级", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "可选等级由当前模型决定（get_available_thinking_levels）；pi 会把请求的等级夹到模型支持的范围内。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (levels.isEmpty()) {
                    Text(
                        "当前模型不支持思考等级。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                levels.forEach { level ->
                    val selected = level == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(level)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = PiShapes.badge,
                            color = PiTheme.palette.thinking(level).copy(alpha = 0.18f),
                        ) {
                            Text(
                                "◐",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = PiTheme.palette.thinking(level),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            thinkingLabelOf(level),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (selected) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

/**
 * The session tools sheet: the switches and one-shot actions that pi's RPC
 * surface exposes but that have no home in the transcript.
 *
 * Every row names the command behind it, because several of them persist to pi's
 * own `settings.json` (`set_steering_mode`, `set_follow_up_mode`,
 * `set_auto_retry` do; `set_auto_compaction` is session state) and a user is
 * entitled to know which of their desktop settings just changed.
 */
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                "会话与队列",
                modifier = Modifier.padding(horizontal = PiSpacing.screen),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "这些开关对应 pi 的 RPC 命令；队列模式与自动重试会写进 pi 的 settings.json。",
                modifier = Modifier.padding(horizontal = PiSpacing.screen),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))

            PiSectionHeader("队列模式")
            QueueModeRow(
                title = "穿插消息（steer）",
                supporting = "set_steering_mode：本回合工具调用之后、下一次模型调用之前投递",
                current = state.meta.steeringMode,
                onPick = onSteeringMode,
            )
            QueueModeRow(
                title = "后续消息（follow up）",
                supporting = "set_follow_up_mode：整个回合结束后才投递",
                current = state.meta.followUpMode,
                onPick = onFollowUpMode,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PiSectionHeader("上下文与重试")
            PiSwitchRow(
                title = "自动压缩",
                supporting = "set_auto_compaction：接近上下文上限时由 pi 自动摘要",
                checked = state.meta.autoCompaction,
                onCheckedChange = onAutoCompaction,
            )
            PiSwitchRow(
                title = "自动重试",
                supporting = "set_auto_retry：可重试的模型错误按退避自动重试",
                checked = state.meta.autoRetry,
                onCheckedChange = onAutoRetry,
            )
            PiValueRow(
                title = "取消重试",
                supporting = "abort_retry：结束正在等待的退避延迟",
                value = "立即",
                onClick = onAbortRetry,
            )
            PiValueRow(
                title = "压缩上下文",
                supporting = "compact：先中止当前回合，然后跑一次摘要调用",
                value = "执行",
                onClick = onCompact,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PiSectionHeader("会话")
            PiValueRow(
                title = "会话信息与统计",
                supporting = "get_session_stats：消息数、token、费用、上下文占用",
                value = "查看",
                onClick = onStats,
            )
            PiValueRow(
                title = "会话树",
                supporting = "get_tree / get_entries：分支结构与扩展写入的条目",
                value = "打开",
                onClick = onTree,
            )
            PiValueRow(
                title = "从历史消息分支",
                supporting = "get_fork_messages → fork",
                value = "选择",
                onClick = onFork,
            )
            PiValueRow(
                title = "复制当前会话",
                supporting = "clone：在当前节点复制出新的会话文件",
                value = "执行",
                onClick = onClone,
            )
            PiValueRow(
                title = "重命名",
                supporting = "set_session_name",
                value = state.meta.sessionName ?: "未命名",
                onClick = onRename,
            )
            PiValueRow(
                title = "导出会话（按扩展名）",
                supporting = "export_html：写入工作区，包含扩展的 tool 渲染结果",
                value = "导出",
                onClick = onExport,
            )
            PiValueRow(
                title = "复制最后一条回复",
                supporting = "get_last_assistant_text",
                value = "复制",
                onClick = onCopyLast,
            )

            // The one thing the GUI cannot render at all. pi gives the client no
            // signal when an extension takes these paths (`custom()` returns
            // `undefined` silently, `setFooter` is a no-op), so the honest move is
            // to name the extensions that need the original TUI and point at it.
            if (state.tuiOnlyExtensions.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PiSectionHeader("仅终端可用的扩展（${state.tuiOnlyExtensions.size}）")
                Text(
                    "这些扩展使用了 RPC 模式没有实现的界面接口，在对话页不会有任何显示；" +
                        "请到 工作区 → pi TUI（原版）里使用。",
                    modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.tuiOnlyExtensions.forEach { extension ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 6.dp)) {
                        Text(
                            "${extension.name}（${extension.scope}）",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            extension.markers.joinToString(" · "),
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

@Composable
private fun QueueModeRow(
    title: String,
    supporting: String,
    current: QueueMode,
    onPick: (QueueMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            supporting,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(
                // pi's wire values, verbatim (`settings-manager.ts:758-774`).
                QueueMode.OneAtATime to "逐条",
                QueueMode.All to "全部",
            )
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = current == mode,
                    onClick = { onPick(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
    }
}

/**
 * Session stats, straight out of `get_session_stats`.
 *
 * `get_state` already gives the session file, so it is shown here too — it is the
 * one fact that lets a user find the JSONL by hand. Field set follows
 * `SessionStats` in `agent-session.ts`; `cost` is USD as pi computes it, and
 * `contextUsage.percent` is pi's own estimate, not a tokenizer count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStatsSheet(
    state: PiSessionViewModel.UiState,
    onDismiss: () -> Unit,
) {
    val stats = state.stats
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(
                horizontal = PiSpacing.screen,
            ),
        ) {
            Text(
                "会话信息",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            if (stats == null) {
                Text(
                    "正在读取…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StatLine("会话名称", state.meta.sessionName ?: "未命名")
                StatLine("会话 ID", stats.sessionId ?: state.meta.sessionId ?: "—")
                StatLine("会话文件", stats.sessionFile ?: state.meta.sessionFile ?: "（尚未落盘）")
                StatLine("消息", "${stats.userMessages} 用户 · ${stats.assistantMessages} 模型 · ${stats.totalMessages} 总计")
                StatLine("工具调用", "${stats.toolCalls} 次调用 · ${stats.toolResults} 条结果")
                stats.tokens?.let { tokens ->
                    StatLine(
                        "Token",
                        "输入 ${tokens.input} · 输出 ${tokens.output} · " +
                            "缓存读 ${tokens.cacheRead} · 缓存写 ${tokens.cacheWrite} · 合计 ${tokens.total}",
                    )
                }
                stats.contextUsage?.let { usage ->
                    val percent = usage.percent?.let { "${(it * 100).toInt()}%" } ?: "—"
                    StatLine(
                        "上下文占用",
                        "$percent（${usage.tokens ?: 0} / ${usage.contextWindow ?: 0}）",
                    )
                }
                stats.cost?.let { StatLine("累计费用", "$${trimCost(it)}") }
                if (stats.contextUsage == null && stats.tokens == null) {
                    Text(
                        "pi 还没有统计信息（通常是本次会话尚未产生模型调用）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            value,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The fork picker: pi's `/fork` selector.
 *
 * `get_fork_messages` returns exactly the user messages pi considers forkable
 * (`session.getUserMessagesForForking()`, `rpc-mode.ts:633-636`), so no filtering
 * happens here — pi decides what a valid fork point is, and `fork(entryId)`
 * re-validates it on the other side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForkPickerSheet(
    messages: List<PiResponses.ForkMessage>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen)) {
            Text("从哪条消息分支", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "pi 会在选中的用户消息处创建一个新会话（fork）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            if (messages.isEmpty()) {
                Text(
                    "没有可分叉的用户消息。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    messages.forEach { message ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(message.entryId)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                message.text.ifBlank { "（空消息）" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                message.entryId,
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

/**
 * Session rename.
 *
 * pi trims and rejects an empty name (`rpc-mode.ts:661-665`), so the field is
 * validated the same way here to save a round trip — the error path still exists
 * for anything else pi refuses.
 */
@Composable
fun RenameSessionDialog(
    initial: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话名称") },
        text = {
            Column {
                Text(
                    "对应 pi 的 set_session_name；会话列表与会话文件头都会用它。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PiSpacing.unit))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("名称") },
                    shape = PiShapes.input,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ------------------------------------------------------------------- helpers

/** pi's level name, or the raw wire value when this build does not know it. */
fun thinkingLabelOf(level: String): String = when (level.lowercase()) {
    "off" -> "关闭"
    "minimal" -> "极简"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "很高"
    "max" -> "最高"
    else -> level
}

/** 200000 → `200k`, 1000000 → `1M`. pi shows raw numbers; this is a phone label. */
private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}k"
    else -> tokens.toString()
}

/** Two decimals, no trailing zeros: cost strings are read, not computed on. */
private fun trimCost(value: Double): String {
    val rounded = kotlin.math.round(value * 100) / 100
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
