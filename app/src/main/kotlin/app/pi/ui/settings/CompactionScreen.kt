package app.pi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.PiLocale
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiFontFeatures
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * 现代「压缩与上下文窗口」专页（100% 对齐 Pi 1.6.0 `AgentSession` 压缩与分支摘要）：
 * - 顶部实时上下文占用状态卡（直读 SDK `session.getContextUsage()`）+ 一键「立即压缩当前会话」；
 * - 自动压缩策略卡：总开关 + `reserveTokens` / `keepRecentTokens` 1-tap 快捷胶囊（仍可点自定义输入任意值）
 *   + `compaction.modelOverrides` 逐模型结构化覆盖；
 * - 分支摘要卡：`branchSummary.skipPrompt` + `branchSummary.reserveTokens` 快捷胶囊。
 */
@Composable
internal fun CompactionScreen(
    store: PiSettingsStore,
    freshness: Int,
    contentPadding: PaddingValues,
    onSettingWritten: (String) -> Unit,
    onBack: () -> Unit,
    contextUsage: PiResponses.ContextUsage? = null,
    activeModelLabel: String? = null,
    isCompacting: Boolean = false,
    onRunCompaction: (() -> Unit)? = null,
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
    highlightKey: String? = null,
) {
    val palette = PiTheme.palette
    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    var editing by remember { mutableStateOf<PiSetting?>(null) }
    var confirmCompact by remember { mutableStateOf(false) }

    val byKey = remember(freshness, PiLocale.isZh) { PiSettingsCatalog.byKey }
    val enabledSetting = byKey["compaction.enabled"]
    val reserveSetting = byKey["compaction.reserveTokens"]
    val keepSetting = byKey["compaction.keepRecentTokens"]
    val overridesSetting = byKey["compaction.modelOverrides"]
    val branchReserveSetting = byKey["branchSummary.reserveTokens"]
    val branchSkipSetting = byKey["branchSummary.skipPrompt"]

    val autoCompact = remember(store, freshness) { enabledSetting?.boolIn(store, true) ?: true }
    val reserveTokens = remember(store, freshness) { reserveSetting?.intIn(store, 16384) ?: 16384 }
    val keepRecentTokens = remember(store, freshness) { keepSetting?.intIn(store, 20000) ?: 20000 }
    val branchReserveTokens = remember(store, freshness) { branchReserveSetting?.intIn(store, 16384) ?: 16384 }
    val branchSkipPrompt = remember(store, freshness) { branchSkipSetting?.boolIn(store, false) ?: false }
    val overrideModels = remember(store, freshness) {
        (store.read("compaction.modelOverrides") as? JsonObject)?.keys?.toList().orEmpty()
    }

    LaunchedEffect(highlightKey) {
        val targetIndex = when (highlightKey) {
            "app.compaction.runNow" -> 0
            "compaction.enabled", "compaction.reserveTokens", "compaction.keepRecentTokens", "compaction.modelOverrides" -> 1
            "branchSummary.reserveTokens", "branchSummary.skipPrompt" -> 2
            else -> -1
        }
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }

    fun writeBool(key: String, value: Boolean) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    fun writeInt(key: String, value: Int) {
        store.write(key, JsonPrimitive(value))
        onSettingWritten(key)
    }

    val reservePresets = remember(PiLocale.isZh) {
        listOf(
            0 to tr("0 (上限)", "0 (limit)"),
            8_192 to "8K",
            16_384 to "16K",
            24_576 to "24K",
            32_768 to "32K",
        )
    }
    val keepPresets = remember {
        listOf(
            8_000 to "8K",
            12_000 to "12K",
            20_000 to "20K",
            32_000 to "32K",
            50_000 to "50K",
        )
    }
    val branchReservePresets = remember {
        listOf(
            8_192 to "8K",
            16_384 to "16K",
            24_576 to "24K",
            32_768 to "32K",
        )
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("压缩与上下文窗口", "Compaction & Context"),
            onBack = onBack,
            scrolled = scrolled,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. 当前会话上下文窗口状态 + 立即压缩
            item(key = "live-context") {
                LiveContextGaugeCard(
                    contextUsage = contextUsage,
                    activeModelLabel = activeModelLabel,
                    reserveTokens = reserveTokens,
                    autoCompact = autoCompact,
                    isCompacting = isCompacting,
                    canCompact = onRunCompaction != null && !isCompacting,
                    onCompactClick = { confirmCompact = true },
                )
            }

            // 2. 自动压缩策略
            item(key = "auto-compaction") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("自动压缩", "Auto-Compaction"))
                    PiSettingsCard {
                        if (enabledSetting != null) {
                            PiSettingRow(
                                setting = enabledSetting,
                                valueText = enabledSetting.display(enabledSetting.current(store)),
                                checked = autoCompact,
                                highlighted = highlightKey == "compaction.enabled",
                                onToggle = { writeBool(enabledSetting.key, it) },
                                onOpen = {},
                                onExplainEffect = {},
                            )
                        }
                        AnimatedVisibility(visible = autoCompact) {
                            Column {
                                PiSettingsHairline()
                                PiInlinePresetRow(
                                    title = reserveSetting?.title ?: tr("压缩触发余量", "Compaction Reserve Tokens"),
                                    description = tr(
                                        "剩余上下文低于此值时自动压缩（0 = 用到上限才压缩）；摘要长度由模型输出上限自适应。",
                                        "Trigger compaction when remaining context drops below this value (0 = at limit); summary length adapts to model output cap.",
                                    ),
                                    valueText = formatTokenCount(reserveTokens),
                                    presets = reservePresets,
                                    selected = reserveTokens,
                                    onSelect = { writeInt("compaction.reserveTokens", it) },
                                    onCustomClick = reserveSetting?.let { s -> { editing = s } },
                                    highlighted = highlightKey == "compaction.reserveTokens",
                                )
                                PiSettingsHairline()
                                PiInlinePresetRow(
                                    title = keepSetting?.title ?: tr("保留最近 token", "Keep Recent Tokens"),
                                    description = tr(
                                        "压缩时最近多少 token 原样保留在对话尾部，不参与摘要。",
                                        "Recent tail tokens kept verbatim during compaction instead of being summarized.",
                                    ),
                                    valueText = formatTokenCount(keepRecentTokens),
                                    presets = keepPresets,
                                    selected = keepRecentTokens,
                                    onSelect = { writeInt("compaction.keepRecentTokens", it) },
                                    onCustomClick = keepSetting?.let { s -> { editing = s } },
                                    highlighted = highlightKey == "compaction.keepRecentTokens",
                                )
                            }
                        }
                        if (overridesSetting != null) {
                            PiSettingsHairline()
                            val overrideSupporting = if (overrideModels.isEmpty()) {
                                tr(
                                    "按厂商/模型覆盖触发余量与保留长度，适合混合大小窗口模型。",
                                    "Override reserve/keep tokens per provider/modelId for mixed context windows.",
                                )
                            } else {
                                val preview = overrideModels.take(2).joinToString(" · ") +
                                    if (overrideModels.size > 2) " +${overrideModels.size - 2}" else ""
                                tr("已覆盖 ${overrideModels.size} 个模型：$preview", "${overrideModels.size} overrides: $preview")
                            }
                            PiSettingsEntryRow(
                                title = overridesSetting.title,
                                supporting = overrideSupporting,
                                onClick = { editing = overridesSetting },
                                trailingBadge = if (overrideModels.isNotEmpty()) {
                                    { PiSettingsBadge(label = "${overrideModels.size}", tone = palette.accent) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // 3. 分支摘要（/tree 跳转分支）
            item(key = "branch-summary") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("分支摘要", "Branch Summary"))
                    PiSettingsCard {
                        if (branchSkipSetting != null) {
                            PiSettingRow(
                                setting = branchSkipSetting,
                                valueText = branchSkipSetting.display(branchSkipSetting.current(store)),
                                checked = branchSkipPrompt,
                                highlighted = highlightKey == "branchSummary.skipPrompt",
                                onToggle = { writeBool(branchSkipSetting.key, it) },
                                onOpen = {},
                                onExplainEffect = {},
                            )
                        }
                        PiSettingsHairline()
                        PiInlinePresetRow(
                            title = branchReserveSetting?.title ?: tr("分支摘要预留 token", "Branch Summary Reserve Tokens"),
                            description = tr(
                                "在 /tree 切换分支生成摘要时，为摘要提示词与模型回复预留的上下文余量。",
                                "Context headroom reserved for the branch summary prompt and model response when navigating /tree.",
                            ),
                            valueText = formatTokenCount(branchReserveTokens),
                            presets = branchReservePresets,
                            selected = branchReserveTokens,
                            onSelect = { writeInt("branchSummary.reserveTokens", it) },
                            onCustomClick = branchReserveSetting?.let { s -> { editing = s } },
                            highlighted = highlightKey == "branchSummary.reserveTokens",
                        )
                    }
                }
            }
        }
    }

    editing?.let { setting ->
        PiSettingEditorSheet(
            setting = setting,
            store = store,
            availableModels = availableModels,
            onLoadAvailableModels = onLoadAvailableModels,
            onDismiss = { editing = null },
            onWritten = { written -> onSettingWritten(written.key) },
        )
    }

    if (confirmCompact) {
        PiSettingsDialog(
            onDismissRequest = { confirmCompact = false },
            title = tr("立即压缩当前会话", "Compact Active Session"),
            body = tr(
                "会将较早的对话记录压缩为结构化摘要，并按设置原样保留最近约 ${formatTokenCount(keepRecentTokens)} token 的消息。",
                "Older messages will be summarized while keeping roughly the most recent ${formatTokenCount(keepRecentTokens)} tokens verbatim.",
            ),
            confirmationLabel = tr("立即压缩", "Compact"),
            onConfirm = {
                confirmCompact = false
                onRunCompaction?.invoke()
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmCompact = false },
        )
    }
}

@Composable
private fun LiveContextGaugeCard(
    contextUsage: PiResponses.ContextUsage?,
    activeModelLabel: String?,
    reserveTokens: Int,
    autoCompact: Boolean,
    isCompacting: Boolean,
    canCompact: Boolean,
    onCompactClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val window = contextUsage?.contextWindow?.takeIf { it > 0L }
    val used = contextUsage?.tokens?.takeIf { it >= 0L }
    val pct = contextUsage?.percent?.coerceIn(0.0, 100.0)
        ?: if (used != null && window != null) (used.toDouble() / window.toDouble() * 100.0).coerceIn(0.0, 100.0) else null
    val progress = ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val gaugeTone = when {
        pct == null -> palette.muted
        pct >= 85.0 -> palette.error
        pct >= 68.0 -> palette.warning
        else -> palette.accent
    }
    val triggerTokens = if (window != null && autoCompact) (window - reserveTokens).coerceAtLeast(0L) else null

    PiSettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PiSettingsMetrics.cardPaddingLoose),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = tr("当前会话上下文", "Active Session Context"),
                        style = PiType.rowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!activeModelLabel.isNullOrBlank()) {
                        Text(
                            text = activeModelLabel,
                            style = PiTheme.text.monoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = gaugeTone.copy(alpha = 0.13f),
                    border = BorderStroke(PiSpacing.hairlineThin, gaugeTone.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = when {
                            isCompacting -> tr("压缩中…", "Compacting…")
                            pct != null -> "${pct.roundToInt()}%"
                            else -> tr("等待会话", "Idle")
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = PiTheme.text.monoSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = PiFontFeatures.TABULAR,
                        ),
                        color = gaugeTone,
                    )
                }
            }

            // Progress bar track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(gaugeTone),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val usageText = when {
                    used != null && window != null -> "${formatTokenCountLong(used)} / ${formatTokenCountLong(window)}"
                    window != null -> tr("窗口 ${formatTokenCountLong(window)}", "Window ${formatTokenCountLong(window)}")
                    else -> tr("发送消息后显示实时占用", "Live usage appears after first turn")
                }
                Text(
                    text = usageText,
                    style = PiTheme.text.monoSmall.copy(fontFeatureSettings = PiFontFeatures.TABULAR),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (triggerTokens != null) {
                    Text(
                        text = tr("触发线 ${formatTokenCountLong(triggerTokens)}", "Triggers at ${formatTokenCountLong(triggerTokens)}"),
                        style = PiTheme.text.monoSmall.copy(fontFeatureSettings = PiFontFeatures.TABULAR),
                        color = PiTheme.palette.muted,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            PiPrimaryButton(
                label = if (isCompacting) tr("正在压缩上下文…", "Compacting Context…") else tr("立即压缩当前会话", "Compact Active Session"),
                enabled = canCompact,
                onClick = onCompactClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatTokenCountLong(tokens: Long): String = when {
    tokens <= 0L -> "0"
    tokens >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> {
        val k = tokens / 1_000.0
        if (tokens % 1_000L == 0L) "${tokens / 1_000L}K" else String.format(java.util.Locale.US, "%.1fK", k)
    }
    else -> tokens.toString()
}
