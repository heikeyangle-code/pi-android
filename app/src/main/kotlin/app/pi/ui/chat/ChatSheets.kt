package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.PiResponses
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSheetChromeMetrics
import app.pi.ui.components.PiSpinner
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn

/** 模型选择器：带搜索胶囊、默认开关胶囊、供应商分组计数、克制中性能力/价格标签卡与官方轮换范围入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<PiResponses.ModelInfo>,
    current: PiResponses.ModelInfo?,
    busy: Boolean,
    onPick: (PiResponses.ModelInfo, persist: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    initialFilter: String = "",
    onManageProviders: (() -> Unit)? = null,
    scopedModelCount: Int = 0,
    onManageScopedModels: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    var filter by remember(initialFilter) { mutableStateOf(initialFilter) }
    var persistAsDefault by remember { mutableStateOf(false) }
    val tokens = remember(filter) {
        filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val query = filter.trim().lowercase()
    val shown = remember(tokens, models) {
        if (tokens.isEmpty()) {
            models
        } else {
            models.filter { m ->
                val hay = "${m.provider.orEmpty()} ${m.id} ${m.name}".lowercase()
                tokens.all { tok -> hay.contains(tok) }
            }
        }
    }
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    tr("选择模型", "Select Model"),
                    modifier = Modifier.weight(1f),
                    style = PiType.title,
                    color = palette.text,
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PiSheetChromeMetrics.sheetControlBg)
                        .border(0.5.dp, palette.borderMuted.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy, onClickLabel = tr("刷新模型目录", "Refresh models"), onClick = onRefresh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        PiSpinner(
                            size = 15.dp,
                            strokeWidth = 1.6.dp,
                            color = palette.accent,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = tr("刷新模型目录", "Refresh models"),
                            tint = palette.muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                PersistTogglePill(
                    persistAsDefault = persistAsDefault,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        persistAsDefault = !persistAsDefault
                    },
                )
            }

            // 现代圆角搜索框（替换 M3 OutlinedTextField）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PiSheetChromeMetrics.sheetCardBg)
                    .border(
                        width = if (filter.isNotEmpty()) 1.dp else 0.5.dp,
                        color = if (filter.isNotEmpty()) palette.border else palette.borderMuted.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = PiTheme.text.prose.copy(color = palette.text),
                    cursorBrush = SolidColor(palette.accent),
                    decorationBox = { inner ->
                        Box {
                            if (filter.isEmpty()) {
                                Text(
                                    tr("按供应商或模型名筛选…", "Filter by provider or model…"),
                                    style = PiTheme.text.prose,
                                    color = palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (filter.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(PiSheetChromeMetrics.sheetControlBg)
                            .clickable { filter = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = tr("清空", "Clear"),
                            tint = palette.muted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }

            if (shown.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = PiSheetChromeMetrics.sheetCardBg,
                    border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (busy) {
                                tr("正在读取模型列表…", "Loading models…")
                            } else if (query.isNotEmpty()) {
                                tr("没有匹配「$filter」的可用模型。", "No models matching \"$filter\".")
                            } else {
                                tr(
                                    "还没有可用模型。先登录订阅账号或配置 API Key，连接后立即生效，无需重启引擎。",
                                    "No available models yet. Sign in to a subscription or configure an API key — changes take effect immediately.",
                                )
                            },
                            style = PiTheme.text.prose,
                            color = palette.muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onManageProviders != null) {
                                SheetActionPill(
                                    text = tr("连接供应商与订阅", "Connect Providers"),
                                    primary = true,
                                    onClick = {
                                        onDismiss()
                                        onManageProviders()
                                    },
                                )
                            }
                            SheetActionPill(text = tr("刷新列表", "Refresh"), primary = false, onClick = onRefresh)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val groups = shown.groupBy { it.provider.orEmpty() }
                    groups.forEach { (provider, inGroup) ->
                        if (groups.size > 1) {
                            item(key = "hdr:$provider") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp, bottom = 2.dp, start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        provider.ifEmpty { tr("其他", "Other") },
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.muted,
                                    )
                                    Text(
                                        "${inGroup.size}",
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PiSheetChromeMetrics.sheetControlBg)
                                            .border(0.5.dp, palette.borderMuted.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                        style = PiTheme.text.monoSmall,
                                        color = palette.dim,
                                    )
                                }
                            }
                        }
                        items(inGroup.size, key = { idx -> "$provider:$idx:${inGroup[idx].id}" }) { idx ->
                            val model = inGroup[idx]
                            ModelRow(
                                model = model,
                                selected = model.id == current?.id && model.provider == current.provider,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPick(model, persistAsDefault)
                                },
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (persistAsDefault) {
                        tr("选中后同步设为新会话默认模型", "Will also be saved as the default for new sessions")
                    } else {
                        tr("仅切换当前会话；点右上角可设为默认", "Current session only; tap top-right to save as default")
                    },
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
                if (shown.isNotEmpty() && (onManageProviders != null || onManageScopedModels != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onManageScopedModels != null) {
                            SheetActionPill(
                                text = if (scopedModelCount > 0) {
                                    tr("轮换范围 ($scopedModelCount)", "Scoped ($scopedModelCount)")
                                } else {
                                    tr("轮换范围", "Scoped Models")
                                },
                                primary = false,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                    onManageScopedModels()
                                },
                            )
                        }
                        if (onManageProviders != null) {
                            SheetActionPill(
                                text = tr("管理供应商", "Providers"),
                                primary = false,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                    onManageProviders()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: PiResponses.ModelInfo, selected: Boolean, onClick: () -> Unit) {
    val palette = PiTheme.palette
    val baseBg = PiSheetChromeMetrics.sheetCardBg
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) lerp(baseBg, palette.accent, 0.055f) else baseBg,
        border = BorderStroke(
            width = if (selected) 1.dp else 0.5.dp,
            color = if (selected) palette.accent.copy(alpha = 0.45f) else palette.borderMuted.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    model.name,
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        model.provider?.let { append(it).append("/") }
                        append(model.id)
                    },
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    model.contextWindow?.let { NeutralMetaBadge(text = formatTokens(it)) }
                    if (model.reasoning) NeutralMetaBadge(text = tr("推理", "Reasoning"))
                    if (model.acceptsImages) NeutralMetaBadge(text = tr("视觉", "Vision"))
                    model.inputCost?.let { cost ->
                        val price = "$${trimCost(cost)}" + (model.outputCost?.let { "/$${trimCost(it)}" } ?: "")
                        NeutralMetaBadge(text = "$price/M")
                    }
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(palette.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = palette.contrastOn(palette.accent),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NeutralMetaBadge(text: String) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = text,
        modifier = Modifier
            .clip(shape)
            .background(palette.borderMuted.copy(alpha = 0.16f))
            .border(0.5.dp, palette.borderMuted.copy(alpha = 0.35f), shape)
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
        style = PiTheme.text.monoSmall,
        color = palette.muted,
        maxLines = 1,
    )
}

/** 思考等级选择器：按当前模型真实支持的档位渲染克制触感卡片，保持正文高可读性。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingPickerSheet(
    levels: List<String>,
    current: String,
    onPick: (String, persist: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    var persistAsDefault by remember { mutableStateOf(false) }
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("思考深度", "Thinking Level"), style = PiType.title, color = palette.text)
                    Text(
                        tr("可选档位由当前模型决定", "Available levels depend on the active model"),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
                PersistTogglePill(
                    persistAsDefault = persistAsDefault,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        persistAsDefault = !persistAsDefault
                    },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (levels.isEmpty()) {
                    Text(
                        tr("当前模型不支持切换思考等级。", "The current model does not support thinking levels."),
                        style = PiTheme.text.prose,
                        color = palette.muted,
                    )
                }
                val baseBg = PiSheetChromeMetrics.sheetCardBg
                levels.forEach { level ->
                    val selected = level == current
                    val isOff = level.equals("off", ignoreCase = true)
                    val levelColor = palette.thinking(level)
                    val desc = when (level.lowercase()) {
                        "off" -> tr("关闭推理，最快响应", "No reasoning, fastest response")
                        "minimal" -> tr("极简思考，超低延迟", "Minimal thinking, lowest latency")
                        "low" -> tr("轻量推理，适合简单任务", "Light reasoning for straightforward tasks")
                        "medium" -> tr("均衡思考，日常编码推荐", "Balanced reasoning for everyday coding")
                        "high" -> tr("深度推理，适合复杂架构与排错", "Deep reasoning for architecture & debugging")
                        "xhigh" -> tr("超深推理，攻克高难度问题", "Extra-high reasoning for hard problems")
                        "max" -> tr("最大思考预算，全面深度分析", "Maximum thinking budget for thorough analysis")
                        else -> null
                    }
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(level, persistAsDefault)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        color = if (selected) lerp(baseBg, levelColor, 0.06f) else baseBg,
                        border = BorderStroke(
                            width = if (selected) 1.dp else 0.5.dp,
                            color = if (selected) levelColor.copy(alpha = 0.50f) else palette.borderMuted.copy(alpha = 0.55f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Canvas(modifier = Modifier.size(10.dp)) {
                                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                                if (isOff) {
                                    val strokePx = 1.5.dp.toPx()
                                    drawCircle(
                                        color = palette.muted.copy(alpha = 0.72f),
                                        radius = (size.minDimension - strokePx) * 0.46f,
                                        center = center,
                                        style = Stroke(width = strokePx),
                                    )
                                } else {
                                    drawCircle(
                                        color = levelColor,
                                        radius = size.minDimension * 0.46f,
                                        center = center,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    thinkingLabelOf(level),
                                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                )
                                if (desc != null) {
                                    Text(
                                        desc,
                                        style = PiTheme.text.meta,
                                        color = palette.muted,
                                    )
                                }
                            }
                            if (selected) {
                                val checkBg = if (isOff) palette.text else levelColor
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(checkBg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = palette.contrastOn(checkBg),
                                        modifier = Modifier.size(13.dp),
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

// ------------------------------------------------------------------- shared pieces

@Composable
private fun PersistTogglePill(persistAsDefault: Boolean, onToggle: () -> Unit) {
    val palette = PiTheme.palette
    val baseBg = PiSheetChromeMetrics.sheetControlBg
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(8.dp),
        color = if (persistAsDefault) lerp(baseBg, palette.accent, 0.10f) else baseBg,
        border = BorderStroke(
            width = if (persistAsDefault) 0.75.dp else 0.5.dp,
            color = if (persistAsDefault) palette.accent.copy(alpha = 0.45f) else palette.borderMuted.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (persistAsDefault) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = palette.accent,
                )
            }
            Text(
                if (persistAsDefault) tr("设为默认", "Set Default") else tr("仅本次会话", "This Session"),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = if (persistAsDefault) palette.text else palette.muted,
            )
        }
    }
}

@Composable
internal fun SheetCardSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = PiTheme.palette.muted,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = PiSheetChromeMetrics.sheetCardBg,
            border = BorderStroke(0.5.dp, PiTheme.palette.borderMuted.copy(alpha = 0.55f)),
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
internal fun SheetActionPill(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = if (primary) palette.accent else PiSheetChromeMetrics.sheetControlBg,
        border = if (primary) null else BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.55f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                color = if (primary) palette.contrastOn(palette.accent) else palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun MiniBadge(text: String, tone: Color) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tone.copy(alpha = 0.13f))
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
        color = tone,
        maxLines = 1,
    )
}

fun thinkingLabelOf(level: String): String = when (level.lowercase()) {
    "off", "minimal", "low", "medium", "high", "xhigh", "max" -> level.lowercase()
    else -> level
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}k"
    else -> tokens.toString()
}

private fun trimCost(value: Double): String {
    val rounded = kotlin.math.round(value * 100) / 100
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
