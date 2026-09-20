package app.pi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.PiResponses
import app.pi.rpc.QueueMode
import app.pi.rpc.TokenUsage
import app.pi.ui.PiSessionViewModel
import app.pi.ui.extension.ExtensionSpans
import app.pi.ui.extension.ExtensionStatus
import app.pi.ui.extension.chromeSpans
import app.pi.ui.components.PiContextRing
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.components.piFormatTokens
import app.pi.ui.components.PiSwitchRow
import app.pi.ui.components.PiValueRow
import app.pi.ui.components.contextProgressColor
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import java.util.Locale
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
    // Remembered on the two inputs it reads: `list()` can be hundreds of entries (a
    // provider like OpenRouter publishes its whole catalogue), and the filter lowercases
    // three fields per model, so recomputing it per composition made every keystroke
    // allocate a few hundred strings for an answer that only changes when the query or
    // the list does.
    val shown = remember(query, models) {
        if (query.isEmpty()) {
            models
        } else {
            models.filter {
                it.id.lowercase().contains(query) ||
                    it.name.lowercase().contains(query) ||
                    it.provider.orEmpty().lowercase().contains(query)
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal)) {
            Text("选择模型", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
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
                    if (busy) {
                        "正在读取模型列表…"
                    } else {
                        // The list is pi's availability snapshot and nothing else, so "just
                        // configured a provider and it is not here" has exactly one cause:
                        // the running engine has not re-read `models.json`/`auth.json`.
                        // Saying that here is the difference between a dead end and one
                        // tap, because this sheet is where the user notices.
                        "没有可用模型。刚配置好的厂商要重启引擎后才会出现在这里；" +
                            "已经保存的模型可以在 设置 → 模型 里看到它们的状态。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PiSpacing.unit))
                TextButton(onClick = onRefresh) { Text("重新读取") }
            } else {
                // Lazy, so only the rows on screen are composed: the list can be a
                // provider's whole catalogue (hundreds of entries), and a plain `Column`
                // built every one of them when the sheet opened. `heightIn(max = …)` is
                // what bounds the viewport — the same shape the `/` palette already uses
                // (`SlashPalette.kt`), where a `LazyColumn` under a max-height constraint
                // still wraps its content, so three models do not leave a 420 dp empty
                // sheet. No `key`: these rows never reorder and a duplicate `id` across
                // two providers would collide a key and crash the list.
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                ) {
                    items(shown) { model ->
                        ModelRow(
                            model = model,
                            selected = model.id == current?.id && model.provider == current.provider,
                            onClick = { onPick(model) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(PiSpacing.unit))
            // One line under the list, for the case the empty state cannot cover: the
            // list is not empty, but the model the user just imported is not in it.
            Text(
                "这个列表来自运行中的引擎。导入过的模型在 设置 → 模型 里能看到它们的状态。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal)) {
            Text("思考等级", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "可选等级由当前模型决定。",
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
                modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.unit))

            PiSectionHeader("队列模式")
            // 插话 / 排队 are the composer's two delivery chips' words, used here for
            // the same two modes: one name per concept, or the sheet reads as if it
            // were describing something else. The English names in the parentheses
            // are pi's own (`steer` / `follow_up`).
            QueueModeRow(
                title = "插话消息（steer）",
                supporting = "本回合进行中插入，下一次回答之前生效",
                current = state.meta.steeringMode,
                onPick = onSteeringMode,
            )
            QueueModeRow(
                title = "排队消息（follow up）",
                supporting = "整个回合结束后才投递",
                current = state.meta.followUpMode,
                onPick = onFollowUpMode,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PiSectionHeader("上下文与重试")
            PiSwitchRow(
                title = "自动压缩",
                supporting = "接近上下文上限时自动摘要",
                checked = state.meta.autoCompaction,
                onCheckedChange = onAutoCompaction,
            )
            PiSwitchRow(
                title = "自动重试",
                supporting = "可重试的模型错误按退避自动重试",
                checked = state.meta.autoRetry,
                onCheckedChange = onAutoRetry,
            )
            PiValueRow(
                title = "取消重试",
                supporting = "结束正在等待的退避延迟",
                value = "立即",
                onClick = onAbortRetry,
            )
            PiValueRow(
                title = "压缩上下文",
                supporting = "先中止当前回合，再生成一次摘要",
                value = "执行",
                onClick = onCompact,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PiSectionHeader("会话")
            PiValueRow(
                // Not 「与统计」 any more: the sheet behind this row is metadata
                // (name / id / file / message and tool counts); the figures moved to
                // 上下文与用量, which the composer's ring opens.
                title = "会话信息",
                supporting = "会话名、会话 ID、文件、消息与工具计数",
                value = "查看",
                onClick = onStats,
            )
            PiValueRow(
                title = "会话树",
                supporting = "分支结构与扩展写入的条目",
                value = "打开",
                onClick = onTree,
            )
            PiValueRow(
                title = "从历史消息分支",
                supporting = null,
                value = "选择",
                onClick = onFork,
            )
            PiValueRow(
                title = "复制当前会话",
                supporting = "复制出一个新的会话",
                value = "执行",
                onClick = onClone,
            )
            PiValueRow(
                title = "重命名",
                supporting = null,
                value = state.meta.sessionName ?: "未命名",
                onClick = onRename,
            )
            PiValueRow(
                title = "导出会话（按扩展名）",
                supporting = "导出到工作区，包含扩展生成的渲染结果",
                value = "导出",
                onClick = onExport,
            )
            PiValueRow(
                title = "复制最后一条回复",
                supporting = null,
                value = "复制",
                onClick = onCopyLast,
            )

            // The one thing the GUI cannot render at all. pi gives the client no
            // signal when an extension takes these paths (`custom()` returns
            // `undefined` silently, `setFooter` is a no-op:
            // `modes/rpc/rpc-mode.ts:189`, `:229-231`), so the honest move is to name
            // the extensions whose interface this app cannot show — and to say that
            // nothing here will display them. It used to end with "use them in the
            // original TUI"; the terminal is not a usable surface, so that sentence
            // named an action the user could not complete. What is left is the fact
            // the user can act on: which installed extension will look incomplete.
            if (state.tuiOnlyExtensions.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PiSectionHeader("本应用显示不了的扩展（${state.tuiOnlyExtensions.size}）")
                Text(
                    "下面这些扩展用到了本应用不支持的自绘界面，它们在对话页不会显示内容。",
                    modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.tuiOnlyExtensions.forEach { extension ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal, vertical = 6.dp)) {
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

            // D-3's landing for extension status (see [ExtensionStatusLine]).
            // Last section of this sheet, and **absent entirely** when there is
            // nothing to show: no empty shell, no `（0）`.
            if (state.extensionStatuses.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PiSectionHeader("扩展状态（${state.extensionStatuses.size}）")
                Text(
                    "扩展通过 setStatus 交给客户端的字符串，按扩展自己的颜色显示。",
                    modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.extensionStatuses.forEach { status ->
                    ExtensionStatusLine(status)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal, vertical = 8.dp)) {
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
 * **上下文与用量** — the sheet the composer's context ring opens.
 *
 * A sheet of its own rather than the top of 会话信息, by the user's ruling
 * (「不要复用会话信息那个，直接重绘一个」): the metadata sheet identifies a session and
 * this one measures it. Keeping them apart is also what makes each figure appear
 * exactly once in the app, which is how the two sheets stopped being able to
 * disagree.
 *
 * Two blocks and nothing else:
 *
 *  - [ContextBlock] — the ring, pi's own percentage, the window, and the two facts
 *    pi does not report (the percentage is an estimate; there is no breakdown);
 *  - [UsageBlock] — **本轮** and **本会话累计** side by side, five rows each, with the
 *    two hit rates under them and labels long enough to tell apart.
 *
 * The states where a figure is missing belong to the blocks: no percentage yet is
 * [ContextBlock]'s empty ring with a `?`, and no usage at all is [UsageBlock]'s `—`.
 * Neither ever becomes a row of zeros.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(
    state: PiSessionViewModel.UiState,
    onDismiss: () -> Unit,
) {
    val stats = state.stats
    // 外壳按 `design-demos/context-gauge-options.html` 的 `.sheet` 一条条对齐：
    // `--surf-high` 底 + 1px `borderMuted` 描边 + 圆角 14 + 最大宽 430dp、内容裁切；
    // 标题栏 11/14 + 下边一条 1px 分隔线；内容区 12/14。
    //
    // 为什么用 `MaterialTheme.colorScheme.outline` 当那个描边：稿子的 `--border-muted`
    // 就是本项目 `outline` 的那一档（`SessionsScreen` 的行内分隔线已经在用同一条对照，
    // 那里写着「`.div` 是 borderMuted 不透明，所以这里是 outline」）。颜色一个 hex 都没写死。
    val shape = RoundedCornerShape(14.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .widthIn(max = 430.dp)
                .clip(shape)
                .border(PiSpacing.hairline, MaterialTheme.colorScheme.outline, shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Text(
                "上下文与用量",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HorizontalDivider(
                thickness = PiSpacing.hairline,
                color = MaterialTheme.colorScheme.outline,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (stats == null) {
                    Text(
                        "正在读取…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ContextBlock(stats = stats, autoCompaction = state.meta.autoCompaction)
                    UsageBlock(
                        turn = state.turnUsage,
                        session = stats.tokens?.let { totals ->
                            TokenUsage(
                                input = totals.input,
                                output = totals.output,
                                cacheRead = totals.cacheRead,
                                cacheWrite = totals.cacheWrite,
                                totalTokens = totals.total,
                                cost = stats.cost,
                            )
                        },
                        lastMessage = state.lastUsage,
                    )
                }
            }
        }
    }
}

/**
 * Session metadata, straight out of `get_session_stats` and `get_state`.
 *
 * `get_state` already gives the session file, so it is shown here too — it is the one
 * fact that lets a user find the JSONL by hand. Field set follows `SessionStats` in
 * `agent-session.ts`, minus everything that measures the session: the counts, the
 * token totals, the context percentage and the cost are in [ContextSheet].
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
                horizontal = PiSpacing.pageHorizontal,
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
                // **Metadata only.** This sheet used to be the app's number dump —
                // token totals, the context percentage and the session cost — and the
                // user's ruling was blunt: 「把会话信息里那些上下文的那些信息删干净，
                // 那个地方太丑了」. Every figure now lives in exactly one place, the
                // 上下文与用量 sheet the composer's ring opens ([ContextSheet]); what
                // is left here identifies the session rather than measuring it.
                StatLine("会话名称", state.meta.sessionName ?: "未命名")
                StatLine("会话 ID", stats.sessionId ?: state.meta.sessionId ?: "—")
                StatLine("会话文件", stats.sessionFile ?: state.meta.sessionFile ?: "（尚未落盘）")
                StatLine("消息", "${stats.userMessages} 用户 · ${stats.assistantMessages} 模型 · ${stats.totalMessages} 总计")
                StatLine("工具调用", "${stats.toolCalls} 次调用 · ${stats.toolResults} 条结果")
            }
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

/**
 * One extension `setStatus` entry: its key, then its text **in the extension's own
 * colours**.
 *
 * ## Why this lives in a sheet instead of above the transcript
 *
 * Adjudication **D-3** (`design/ui-refactor/11-designer-adjudication.md`): extension
 * status is not a permanent band. An extension hands the client an arbitrary string —
 * often a counter or a progress fragment — and a line of them under the AppBar is
 * chrome the user did not ask for and cannot act on. So the *component* that drew
 * that row (`ExtensionStatusRow`) was deleted, while the **data** was deliberately
 * kept (`UiState.extensionStatuses`, fed by `PiSessionViewModel.setExtensionStatus`).
 * This is the "on demand" half of that ruling: open 会话与队列 and the statuses are
 * here. Nothing renders them anywhere else, and nothing should — a second permanent
 * copy is exactly what D-3 removed.
 *
 * ## The colours are the extension's, through the one existing channel
 *
 * [ExtensionSpans] + [chromeSpans] are the same pair the extension widgets use
 * (`ui/extension/ExtensionChrome.kt`): pi sends SGR bytes inside an ordinary string,
 * `chromeSpans` parses them and the renderer matches each foreground back onto a pi
 * palette token. Nothing here re-implements any of that — an extension that colours
 * its status gets the colour it asked for, and one that does not gets the default.
 *
 * The text **wraps and is not capped**: a status an extension wrote in full is worth
 * reading in full when the user has explicitly opened a sheet to read it.
 */
@Composable
private fun ExtensionStatusLine(status: ExtensionStatus) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = status.key,
            modifier = Modifier.padding(end = PiSpacing.inner),
            style = PiTheme.text.monoSmall,
            color = palette.muted,
            maxLines = 1,
        )
        ExtensionSpans(
            spans = remember(status.text) { chromeSpans(status.text) },
            defaultColor = MaterialTheme.colorScheme.onSurface,
            style = PiTheme.text.monoSmall,
            modifier = Modifier.weight(1f),
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * The sheet's 上下文 block: the ring, the percentage, the window, and the two things
 * pi does *not* say.
 *
 * The percentage is pi's own `getContextUsage().percent`
 * (`core/agent-session.ts:3446-3450`), one decimal — the same figure the composer's
 * ring draws, in words. The two notes under it exist because the number invites two
 * questions the data cannot answer:
 *
 *  - it is an **estimate**: the last real usage plus `ceil(chars / 4)` per message
 *    since (`core/compaction.ts:270-274`);
 *  - pi has **no breakdown** — system prompt, tool definitions and messages are not
 *    itemised anywhere in its protocol, only the total.
 *
 * `percent == null` is pi's own state after a compaction and before the next reply
 * (`footer.ts:110` prints `?` for exactly this window): the ring draws its empty
 * track with a `?`, the used count is `—`, and the window is still shown, because the
 * window did not change.
 *
 * ## The shape is the design file's, feature by feature
 *
 * `design-demos/context-gauge-options.html`（形态 1 的视觉语言）: 56dp 环（描边 4）、右列三行
 * —— 等宽 17sp 的百分比（阈值色）、等宽 13sp 的 `641k / 1.0M · 自动压缩开`（pi 自己的 k/M
 * 缩写）、12sp 的估算说明；`?` 的样式由调用方给（等宽 12sp，`PiContextRing` 负责 `bodyOnTool`）。
 * 颜色全部走 `PiTheme` 令牌，一个 hex 都没写死。
 */
@Composable
private fun ContextBlock(stats: PiResponses.SessionStats, autoCompaction: Boolean) {
    val palette = PiTheme.palette
    val usage = stats.contextUsage
    val percent = usage?.percent
    // 稿子形态 1 的顶部那一块：左 56dp 环（半径 26、描边 4），右竖排三行 ——
    // 等宽 17sp 的百分比（阈值色）、等宽 13sp 的窗口行、12sp 的估算说明。
    Row(verticalAlignment = Alignment.CenterVertically) {
        PiContextRing(
            percent = percent,
            diameter = SHEET_RING_DIAMETER,
            stroke = SHEET_RING_STROKE,
            // 稿子 `.ringbtn .q{font-family:mono;font-size:12px}`：`?` 是等宽 12sp。
            placeholderStyle = PiTheme.text.monoSmall,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = percent?.let { String.format(Locale.US, "%.1f", it) + "%" } ?: "—",
                style = PiTheme.text.mono.copy(fontSize = 17.sp),
                color = percent?.let { contextProgressColor(it, palette) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = contextWindowLine(usage, autoCompaction),
                modifier = Modifier.padding(top = 2.dp),
                // 稿子：窗口行是等宽 13sp、正文色 —— 用的是 pi 自己的 k/M 缩写
                // （`641k / 1.0M`），因为这一行要和底栏/环的口径一致；精确整数只出现在
                // 下面的两列对比里（稿子 §5 写明这个分工）。
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurface,
            )
            NoteText("这是 pi 的估算：最后一次真实用量 + 之后每条消息按字符数 ÷ 4。")
        }
    }
    // `percent == null` 是 pi 自己的一个窗口（压缩后到下次回复前），不是「还没读到」：
    // 上面那句 `?` 的行与这一句都只在那个窗口里出现。
    if (percent == null) {
        NoteText("压缩后 pi 还没有报占用，等下一次回复。")
    }
    NoteText("分类明细 pi 不提供（系统提示词 / 工具定义 / 对话消息这类拆项它没有给，只有总数）。")
}

/**
 * The window line: `641k / 1.0M · 自动压缩开`, pi's own compact spelling
 * ([piFormatTokens], `footer.ts:24-30`), with `—` in place of the used count while pi
 * reports no percentage (the window itself is still known — `agent-session.ts:3441-3445`
 * nulls `tokens`/`percent` but keeps `contextWindow`).
 *
 * A window pi does not report either leaves the same `—`, so the line never invents a
 * number: the shape is `used / window · auto-state` and any part of it that is unknown
 * says so.
 */
private fun contextWindowLine(usage: PiResponses.ContextUsage?, autoCompaction: Boolean): String {
    val used = usage?.tokens?.let { piFormatTokens(it) } ?: "—"
    val window = usage?.contextWindow?.takeIf { it > 0L }?.let { piFormatTokens(it) } ?: "—"
    val auto = if (autoCompaction) "自动压缩开" else "自动压缩关"
    return "$used / $window · $auto"
}

/**
 * The sheet's 用量 block: **two columns, side by side** — 本轮 and 本会话累计.
 *
 * The two are separate columns on purpose, and the parent's ruling is why: the user
 * once read a session total as the turn's, and a single column with two values would
 * reproduce exactly that misreading. Five rows in both, in pi's own field order
 * (`components/footer.ts:106-146`).
 *
 * **Counts are exact integers with thousands separators** — `96,400`, never `96k`.
 * The transcript's compact forms (`96k`) belong to a one-line reading; a detail sheet
 * exists to be quoted, and `96k` cannot say whether it was 96,400 or 96,900.
 *
 * **Both hit rates, one row each, outside both columns.** pi defines the first on a single
 * message — `cacheRead ÷ (input + cacheRead + cacheWrite)` (`footer.ts:95-98`) — and there is
 * no turn-level equivalent: applying the same formula to a sum would produce a *third*
 * percentage that looks like the others and means something else, which is precisely the
 * confusion this sheet was reported for. So each row says which number it is about.
 *
 * **Neither row may disappear.** They used to `return` when the ratio was null, so a reader
 * saw one, then the other, then neither and reported it as 「感觉只有一个总的」. "This
 * reading has no figure" and "this reading does not exist" are different statements
 * ([HitRate]): the row stays, the value is `—`, and the reason is that branch's own sentence.
 */
@Composable
private fun UsageBlock(turn: TokenUsage?, session: TokenUsage?, lastMessage: TokenUsage?) {
    // 稿子的 `.cmp`：两列并排，中间一条 1px `borderMuted` 竖分隔线，两侧各 12dp。
    // `IntrinsicSize.Min` 是那条竖线能 `fillMaxHeight` 的前提（画到较高那一列的下沿）。
    SectionTitle("用量对比", trailing = "（服务商回报的真值 · 千分位是精确整数）")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        UsageColumn("本轮", turn, Modifier.weight(1f), cumulative = false)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .width(PiSpacing.hairline)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(12.dp))
        UsageColumn("本会话累计", session, Modifier.weight(1f), cumulative = true)
    }
    NoteText(
        "「本轮」＝ 从本回合开始、模型每一次回复的用量之和，正在流式的这一条要等它结束才计入；" +
            "「本会话累计」＝ 整个会话，含压缩 / 摘要那次调用。",
    )
    // Two hit rates, and the labels are long on purpose: the user has already read
    // one of these as the other. They are different kinds of number —
    //
    //   命中率（最后一条回复）  is **pi's own field** for one message
    //                            (`components/footer.ts:95-98`), and it is what pi's
    //                            footer prints;
    //   命中率（本会话累计）    is **derived here** from the four cumulative counters
    //                            `get_session_stats` reports — the same counters pi
    //                            sums itself, so the arithmetic is pi's, but the ratio
    //                            is this app's and pi has no field for it.
    //
    // There is deliberately **no 本轮 hit rate**: a third percentage of the same shape
    // would put three near-synonyms on one screen, which the designer ruled out (and a
    // ratio of sums is not the sum of ratios in any case).
    //
    // **Both rows are always drawn.** They used to `return` when the ratio was null, so
    // a reader saw one row, then the other, then neither — and reported it as 「感觉只有
    // 一个总的」. This project's rule is that "this reading has no figure" and "this
    // reading does not exist" may not look the same: the row stays, the value is `—`, and
    // the reason is the branch's own sentence ([HitRate.none]).
    HitRateRow("命中率（最后一条回复）", lastMessageHitRate(lastMessage))
    HitRateRow("命中率（本会话累计）", cumulativeHitRate(session))
}

/**
 * One hit-rate reading: a figure, or **why there is none**.
 *
 * The two are separate cases on purpose. A single nullable `String?` is what made the
 * row disappear, and a row that disappears cannot say which of the four causes it was
 * (no usage reported yet / a message whose three counters are all zero / the cumulative
 * counters not read yet / a session with no counters at all).
 */
private sealed interface HitRate {
    data class Value(val percent: String) : HitRate
    data class None(val reason: String) : HitRate
}

/**
 * pi's cache-hit rate for **one message**, or the reason it has none.
 *
 * `cacheRead ÷ (input + cacheRead + cacheWrite)` (`components/footer.ts:95-98`), one
 * decimal — pi's own `toFixed(1)`, and pi's own definition of the number: it is a
 * per-message field, not a session one.
 */
private fun lastMessageHitRate(usage: TokenUsage?): HitRate {
    if (usage == null) return HitRate.None("本条还没有服务商回报的用量")
    val read = usage.cacheRead ?: 0L
    val denominator = (usage.input ?: 0L) + read + (usage.cacheWrite ?: 0L)
    if (denominator <= 0L) return HitRate.None("本条的三项计数都是 0，算不出比例")
    return HitRate.Value(String.format(Locale.US, "%.1f", read * 100.0 / denominator) + "%")
}

/**
 * The same ratio over the **whole session**: `ΣcacheRead ÷ (Σinput + ΣcacheRead +
 * ΣcacheWrite)`.
 *
 * A **derived** figure (D30): its inputs are `get_session_stats`' own cumulative
 * counters, so the arithmetic is four operations on pi's truth, but the ratio itself is
 * this app's and pi has no field for it. The reason it has no figure says which of the
 * two it is — the counters have not been read, or the session has none.
 */
private fun cumulativeHitRate(usage: TokenUsage?): HitRate {
    if (usage == null) return HitRate.None("累计计数还没读到")
    val read = usage.cacheRead ?: 0L
    val denominator = (usage.input ?: 0L) + read + (usage.cacheWrite ?: 0L)
    if (denominator <= 0L) return HitRate.None("本会话还没有服务商的累计计数")
    return HitRate.Value(String.format(Locale.US, "%.1f", read * 100.0 / denominator) + "%")
}

/**
 * One hit-rate line in the sheet's `kv` shape, and it is **always drawn**.
 *
 * The value is the figure or `—`; the note beside it is the branch's own reason when
 * there is no figure, so the two are never the same blank and never the same sentence.
 */
@Composable
private fun HitRateRow(label: String, rate: HitRate) {
    KvRow(
        key = label,
        value = when (rate) {
            is HitRate.Value -> rate.percent
            is HitRate.None -> "—"
        },
        note = (rate as? HitRate.None)?.reason,
        // 稿子这一行是 `.kv.auto`：标签是中文长句，固定 72dp 装不下，所以键宽自动。
        keyWidth = null,
    )
}

/**
 * One usage column: its title, then the five readings in pi's own field order
 * (`components/footer.ts:106-146`).
 *
 * The titles are deliberately different sizes and colours (稿子 §5：左「本轮」13/600 正文色、
 * 右「本会话累计」12/500 muted) — that structural difference is what keeps the two
 * columns from being read as one, which is the misreading D30 exists for.
 */
@Composable
private fun UsageColumn(title: String, usage: TokenUsage?, modifier: Modifier = Modifier, cumulative: Boolean) {
    Column(modifier) {
        Text(
            text = title,
            // 稿子的 `.cmp .ch` **没有** font-family 覆盖 ⇒ 它是无衬线（13/600 正文色；
            // 右列 `.ch.dim` 是 12/500 muted）。等宽只给数字，中文标题不走机器面。
            style = if (cumulative) {
                PiTheme.text.meta.copy(fontWeight = FontWeight.Medium)
            } else {
                PiTheme.text.meta.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            },
            color = if (cumulative) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        if (usage == null) {
            // "No figures", not "zero": a turn in which pi reported no usage has no
            // row of zeros to show (`TranscriptReducer.turnUsage`).
            KvRow(key = "", value = "—", note = null, keyWidth = null)
            return@Column
        }
        // A field pi did not report stays `—`, never `0`: "no figure" and "zero" are
        // different readings and this sheet may not turn one into the other.
        KvRow("输入", usage.input?.let { exactCount(it) } ?: "—")
        KvRow("输出", usage.output?.let { exactCount(it) } ?: "—")
        KvRow("缓存读", usage.cacheRead?.let { exactCount(it) } ?: "—")
        KvRow("缓存写", usage.cacheWrite?.let { exactCount(it) } ?: "—")
        KvRow("费用", usage.cost?.let { "$" + String.format(Locale.US, "%.3f", it) } ?: "—")
    }
}

/**
 * One `kv` line, the sheet's only row shape: `align-items: baseline`、间距 8、上下内边距 3；
 * 键 12sp muted（[keyWidth] 给固定宽时就是稿子的 72dp）、值等宽 13sp 正文色、tabular。
 *
 * @param keyWidth null means "as wide as the label needs" — the design's `.kv.auto`, used
 *   by the two hit-rate rows whose labels are sentences.
 * @param note an optional muted sentence after the value (a hit-rate row's reason).
 */
@Composable
private fun KvRow(key: String, value: String, note: String? = null, keyWidth: Dp? = 72.dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = key,
            modifier = if (keyWidth != null) Modifier.width(keyWidth) else Modifier,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            modifier = Modifier.alignByBaseline(),
            style = PiTheme.text.mono,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (note != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = note,
                modifier = Modifier.alignByBaseline().weight(1f),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A section title inside the sheet: 12sp/500 in the body colour, with an optional muted
 * parenthetical at 400 (稿子 `.sheet .sec` + 「（…）」那一半), 上距 12 下距 6.
 */
@Composable
private fun SectionTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
        Text(
            text = text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The two honest sentences and the no-reading state share this style: 12sp / line-height 1.6
 * in the muted colour — the design file's `.note`（`font-size:12px;line-height:1.6`）。
 * `PiTheme.text.meta` is 12/18 (1.5); the 19.2sp below is that 1.6 written out, because these
 * are the sentences a reader has to get through, not a label.
 */
@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 2.dp),
        style = PiTheme.text.meta.copy(lineHeight = 19.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `96,400` — an exact count, for a sheet someone quotes. */
private fun exactCount(value: Long): String = String.format(Locale.US, "%,d", value)

/**
 * The sheet's ring: 稿子形态 1 的 56dp / 描边 4（半径 26，与 `.sheet .ringbtn` 的 svg 同值）。
 * 「板子没给测量」那条理由在 D27 之后不再成立 —— 现在给了，就是稿子这一处。
 */
private val SHEET_RING_DIAMETER = 56.dp
private val SHEET_RING_STROKE = 4.dp

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
 *
 * **The order is pi's and the ordinal is shown.** Several prompts look alike in a
 * three-line preview, so the user's request was 「后面写个 123 也行」: each row now
 * carries its 1-based position in a monospaced gutter. The number is the row's
 * index in the list pi sent — `messages` is rendered with `forEachIndexed` and
 * never re-sorted, filtered or deduplicated here, because the whole value of the
 * number is that "第 3 条" in this sheet and the third entry of
 * `getUserMessagesForForking()` are the same message. Re-ordering would silently
 * break exactly the promise the number makes.
 *
 * The blurb states pi's real semantics, which the previous copy got wrong:
 * `fork` defaults to `position: "before"` (`agent-session-runtime.ts:264-287`), so
 * the new session ends *before* the chosen message — that message is not carried
 * over; its text is handed back instead and the block menu's 编辑并从此分叉 puts it
 * in the composer (`interactive-mode.ts:5157-5165`), which is what the picker
 * opened from the ⋮ menu will do too once the fork lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForkPickerSheet(
    messages: List<PiResponses.ForkMessage>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal)) {
            Text("从哪条消息分支", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "会在「这条消息之前」分出新的会话（这条消息本身不会带过去），" +
                    "并把它放回输入框，可以改完再发。编号就是 pi 给的可分叉消息顺序。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))
            if (messages.isEmpty()) {
                Text(
                    // pi's own wording for this state is `No messages to fork from`
                    // (`interactive-mode.ts:5149-5155`); saying what to do about it is
                    // the app's addition, because a sheet that says only "没有" reads
                    // as a broken feature rather than as an empty session.
                    "pi 说这个会话还没有可分叉的用户消息（No messages to fork from）。" +
                        "先发一条消息，等 pi 把它写进会话文件后再回来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Lazy for the same reason as the model list above: a long session's fork
                // points are hundreds of rows, and this sheet is opened on a tap.
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(messages) { index, message ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(message.entryId)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            // The gutter: a monospaced ordinal, wide enough for three
                            // digits so the text column cannot shift between rows.
                            Text(
                                text = "${index + 1}",
                                modifier = Modifier.width(28.dp),
                                style = PiTheme.text.mono,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
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
                    "会话列表里显示的名称。",
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

/**
 * pi's own thinking-level identifier — **not** a translation of it.
 *
 * The engine and the TUI spell these exactly as pi's identifier does: the TUI's
 * footer concatenates the raw level (`modes/interactive/footer.ts:185-187`) and its
 * selector builds each option's label from the identifier itself
 * (`thinking-selector.ts:64-66`); `cli/args.ts:60` validates the same seven words.
 * A translated label would be a name the engine never uses, on the one surface
 * whose whole point is to report what the engine is set to — translation belongs
 * somewhere else, and this is not that place.
 *
 * Which of the seven a model can actually be set to is pi's own
 * `getSupportedThinkingLevels(model)`, so on a phone the list is often only three
 * or four of them; the fallback keeps a newer pi's level readable rather than blank.
 */
fun thinkingLabelOf(level: String): String = when (level.lowercase()) {
    "off", "minimal", "low", "medium", "high", "xhigh", "max" -> level.lowercase()
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
