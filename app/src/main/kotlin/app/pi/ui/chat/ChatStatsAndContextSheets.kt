package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import app.pi.protocol.TokenUsage
import app.pi.ui.PiSessionViewModel
import app.pi.ui.components.PiContextRing
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.components.contextProgressColor
import app.pi.ui.components.piFormatTokens
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import java.util.Locale

/** 上下文与用量仪表盘：环形占比主卡 + 本轮/累计双卡对比 + 缓存命中率 + 支持可选保留重点的一键压缩。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(
    state: PiSessionViewModel.UiState,
    onDismiss: () -> Unit,
    onCompact: ((instructions: String?) -> Unit)? = null,
    onOpenCompactionSettings: (() -> Unit)? = null,
) {
    val stats = state.stats
    val palette = PiTheme.palette
    var compactInstructions by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var showInstructionsInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    PiSheet(onDismiss = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .padding(horizontal = PiSpacing.pageHorizontal),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tr("上下文与用量", "Context & Usage"),
                        modifier = Modifier.weight(1f),
                        style = PiType.title,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (onCompact != null) {
                        Surface(
                            onClick = {
                                val note = compactInstructions.trim().takeIf { it.isNotEmpty() }
                                onDismiss()
                                onCompact(note)
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = palette.accent,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Compress,
                                    contentDescription = null,
                                    tint = palette.contrastOn(palette.accent),
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = tr("压缩上下文", "Compact"),
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.contrastOn(palette.accent),
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (stats == null) {
                        Text(
                            tr("正在读取会话统计…", "Loading session stats…"),
                            style = PiTheme.text.prose,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ContextBlock(
                            stats = stats,
                            autoCompaction = state.meta.autoCompaction,
                            modelContextWindow = state.meta.model?.contextWindow,
                        )
                        if (onCompact != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
                                border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.52f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = tr("压缩保留重点（可选，同 /compact 指令）", "Compaction focus (optional, same as /compact)"),
                                            style = PiTheme.text.meta,
                                            color = palette.muted,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (!showInstructionsInput && compactInstructions.isEmpty()) {
                                            Surface(
                                                onClick = { showInstructionsInput = true },
                                                shape = RoundedCornerShape(8.dp),
                                                color = app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg,
                                            ) {
                                                Text(
                                                    text = tr("填写", "Add"),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                                    color = palette.accent,
                                                )
                                            }
                                        }
                                    }
                                    if (showInstructionsInput || compactInstructions.isNotEmpty()) {
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = compactInstructions,
                                            onValueChange = { compactInstructions = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(palette.pageBg.copy(alpha = 0.65f), RoundedCornerShape(9.dp))
                                                .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.65f), RoundedCornerShape(9.dp))
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            textStyle = PiTheme.text.meta.copy(color = palette.text),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                                            maxLines = 3,
                                            decorationBox = { inner ->
                                                Box {
                                                    if (compactInstructions.isEmpty()) {
                                                        Text(
                                                            text = tr("例如：保留已确认的接口字段与未完成待办…", "e.g. Keep confirmed API schema and open TODOs…"),
                                                            style = PiTheme.text.meta,
                                                            color = palette.dim,
                                                        )
                                                    }
                                                    inner()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
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
                        if (onOpenCompactionSettings != null) {
                            Surface(
                                onClick = {
                                    onDismiss()
                                    onOpenCompactionSettings()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
                                border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.52f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = tr("调整自动压缩阈值与保留窗口", "Configure auto-compaction & reserve window"),
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                        color = palette.muted,
                                    )
                                    Text(
                                        text = "→",
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.accent,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 会话信息与统计（对齐官方 `/session`：名称、ID、文件路径、消息与工具计数、上下文与 Token 费用）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStatsSheet(
    state: PiSessionViewModel.UiState,
    onDismiss: () -> Unit,
) {
    val stats = state.stats
    PiSheet(onDismiss = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PiSpacing.pageHorizontal),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    tr("会话信息与统计", "Session Info & Stats"),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (stats == null) {
                    Text(
                        tr("正在读取…", "Loading…"),
                        style = PiTheme.text.prose,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ContextBlock(
                        stats = stats,
                        autoCompaction = state.meta.autoCompaction,
                        autoRetry = state.meta.autoRetry,
                        modelContextWindow = state.meta.model?.contextWindow,
                    )
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
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
                        border = BorderStroke(PiSpacing.hairlineThin, PiTheme.palette.borderMuted.copy(alpha = 0.50f)),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            StatLine(tr("会话名称", "Name"), state.meta.sessionName ?: tr("未命名", "Untitled"))
                            HorizontalDivider(thickness = 0.5.dp, color = PiTheme.palette.borderMuted.copy(alpha = 0.45f))
                            StatLine(tr("会话 ID", "Session ID"), stats.sessionId ?: state.meta.sessionId ?: "—", mono = true)
                            HorizontalDivider(thickness = 0.5.dp, color = PiTheme.palette.borderMuted.copy(alpha = 0.45f))
                            StatLine(tr("会话文件", "Session File"), stats.sessionFile ?: state.meta.sessionFile ?: tr("（尚未落盘）", "(not saved yet)"), mono = true)
                            val primaryModel = state.meta.model?.let { "${it.provider}/${it.id}" }
                            val routed = state.meta.routedModel?.takeIf { it.isNotBlank() && it != primaryModel }
                            if (routed != null) {
                                HorizontalDivider(thickness = 0.5.dp, color = PiTheme.palette.borderMuted.copy(alpha = 0.45f))
                                StatLine(tr("路由模型", "Routed Model"), routed, mono = true)
                            }
                            if (state.meta.scopedModelCount > 0) {
                                HorizontalDivider(thickness = 0.5.dp, color = PiTheme.palette.borderMuted.copy(alpha = 0.45f))
                                StatLine(
                                    tr("模型范围", "Scoped Models"),
                                    tr("${state.meta.scopedModelCount} 个已选模型", "${state.meta.scopedModelCount} scoped models"),
                                )
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = PiTheme.palette.borderMuted.copy(alpha = 0.45f))
                            StatLine(
                                tr("消息统计", "Messages"),
                                tr(
                                    "${stats.userMessages} 用户 · ${stats.assistantMessages} 模型 · ${stats.totalMessages} 总计",
                                    "${stats.userMessages} user · ${stats.assistantMessages} assistant · ${stats.totalMessages} total",
                                ),
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = PiTheme.palette.borderMuted.copy(alpha = 0.45f))
                            StatLine(
                                tr("工具调用", "Tool Calls"),
                                tr(
                                    "${stats.toolCalls} 次调用 · ${stats.toolResults} 条结果",
                                    "${stats.toolCalls} calls · ${stats.toolResults} results",
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ContextBlock(
    stats: PiResponses.SessionStats,
    autoCompaction: Boolean,
    modelContextWindow: Long?,
    autoRetry: Boolean? = null,
) {
    val palette = PiTheme.palette
    val usage = stats.contextUsage
    val percent = usage?.percent
    val ringColor = percent?.let { contextProgressColor(it, palette) } ?: palette.muted

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
        border = BorderStroke(PiSpacing.hairlineThin, ringColor.copy(alpha = 0.34f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PiContextRing(
                percent = percent,
                diameter = SHEET_RING_DIAMETER,
                stroke = SHEET_RING_STROKE,
                placeholderStyle = PiTheme.text.monoSmall,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = percent?.let { String.format(Locale.US, "%.1f", it) + "%" } ?: "—",
                    style = PiTheme.text.mono.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = ringColor,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MiniBadge(
                        text = if (autoCompaction) tr("自动压缩开", "Auto-compact ON") else tr("自动压缩关", "Auto-compact OFF"),
                        tone = if (autoCompaction) palette.success else palette.muted,
                    )
                    if (autoRetry != null) {
                        MiniBadge(
                            text = if (autoRetry) tr("自动重试开", "Auto-retry ON") else tr("自动重试关", "Auto-retry OFF"),
                            tone = if (autoRetry) palette.accent else palette.muted,
                        )
                    }
                }
                Text(
                    text = contextWindowLine(usage, modelContextWindow),
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun contextWindowLine(
    usage: PiResponses.ContextUsage?,
    modelContextWindow: Long?,
): String {
    val used = usage?.tokens?.let { piFormatTokens(it) } ?: "—"
    val window = usage?.contextWindow?.takeIf { it > 0L }
        ?: modelContextWindow?.takeIf { it > 0L }
    val windowText = window?.let { piFormatTokens(it) } ?: "—"
    return tr("已用 $used / 窗口 $windowText", "Used $used / Window $windowText")
}

@Composable
private fun UsageBlock(turn: TokenUsage?, session: TokenUsage?, lastMessage: TokenUsage?) {
    val palette = PiTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("Token 与费用对比", "Tokens & Cost"),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = palette.muted,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
            border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.50f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(12.dp),
            ) {
                UsageColumn(tr("本轮", "This Turn"), turn, Modifier.weight(1f), cumulative = false)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(palette.borderMuted.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.width(10.dp))
                UsageColumn(tr("本会话累计", "Session Total"), session, Modifier.weight(1f), cumulative = true)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg,
            border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.50f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HitRateRow(tr("缓存命中率（最后一条回复）", "Cache Hit Rate (Last Reply)"), lastMessageHitRate(lastMessage))
                HorizontalDivider(thickness = 0.5.dp, color = palette.borderMuted.copy(alpha = 0.45f))
                HitRateRow(tr("缓存命中率（本会话累计）", "Cache Hit Rate (Session Total)"), cumulativeHitRate(session))
            }
        }
    }
}

private sealed interface HitRate {
    data class Value(val percent: String) : HitRate
    data class None(val reason: String) : HitRate
}

private fun lastMessageHitRate(usage: TokenUsage?): HitRate {
    if (usage == null) return HitRate.None(tr("本条还没有服务商回报的用量", "No provider usage reported for this reply"))
    val read = usage.cacheRead ?: 0L
    val denominator = (usage.input ?: 0L) + read + (usage.cacheWrite ?: 0L)
    if (denominator <= 0L) return HitRate.None(tr("本条三项计数都是 0", "Input and cache counters are 0"))
    return HitRate.Value(String.format(Locale.US, "%.1f", read * 100.0 / denominator) + "%")
}

private fun cumulativeHitRate(usage: TokenUsage?): HitRate {
    if (usage == null) return HitRate.None(tr("累计计数还没读到", "Session totals not loaded yet"))
    val read = usage.cacheRead ?: 0L
    val denominator = (usage.input ?: 0L) + read + (usage.cacheWrite ?: 0L)
    if (denominator <= 0L) return HitRate.None(tr("本会话还没有累计计数", "No cumulative token counts yet"))
    return HitRate.Value(String.format(Locale.US, "%.1f", read * 100.0 / denominator) + "%")
}

@Composable
private fun HitRateRow(label: String, rate: HitRate) {
    val palette = PiTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (rate) {
                is HitRate.Value -> MiniBadge(text = rate.percent, tone = palette.success)
                is HitRate.None -> Text("—", style = PiTheme.text.mono, color = palette.dim)
            }
        }
        if (rate is HitRate.None) {
            Text(
                text = rate.reason,
                style = PiTheme.text.meta,
                color = palette.dim,
            )
        }
    }
}

@Composable
private fun UsageColumn(title: String, usage: TokenUsage?, modifier: Modifier = Modifier, cumulative: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = PiTheme.text.meta.copy(
                fontSize = if (cumulative) 12.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (cumulative) MaterialTheme.colorScheme.onSurfaceVariant else PiTheme.palette.accent,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        if (usage == null) {
            Text("—", style = PiTheme.text.mono, color = PiTheme.palette.dim)
            return@Column
        }
        KvRow(tr("输入", "Input"), usage.input?.let { exactCount(it) } ?: "—")
        KvRow(tr("输出", "Output"), usage.output?.let { exactCount(it) } ?: "—")
        KvRow(tr("缓存读", "Cache R"), usage.cacheRead?.let { exactCount(it) } ?: "—")
        KvRow(tr("缓存写", "Cache W"), usage.cacheWrite?.let { exactCount(it) } ?: "—")
        KvRow(tr("费用", "Cost"), usage.cost?.let { "$" + String.format(Locale.US, "%.3f", it) } ?: "—")
    }
}

@Composable
private fun KvRow(key: String, value: String, keyWidth: Dp = 52.dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = key,
            modifier = Modifier.width(keyWidth),
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun exactCount(value: Long): String = String.format(Locale.US, "%,d", value)

private val SHEET_RING_DIAMETER = 58.dp
private val SHEET_RING_STROKE = 4.5.dp

@Composable
private fun StatLine(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = PiTheme.palette.muted,
        )
        Text(
            value,
            style = if (mono) PiTheme.text.monoSmall else PiTheme.text.prose,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
