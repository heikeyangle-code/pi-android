package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.PiResponses
import app.pi.rpc.QueueMode
import app.pi.rpc.TokenUsage
import app.pi.ui.PiSessionViewModel
import app.pi.ui.components.PiContextRing
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSwitchRow
import app.pi.ui.components.PiType
import app.pi.ui.components.contextProgressColor
import app.pi.ui.components.piFormatTokens
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import java.util.Locale

/** 模型选择器：带搜索胶囊、默认开关胶囊、供应商分组计数、能力/价格标签卡。 */
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
) {
    val palette = PiTheme.palette
    var filter by remember { mutableStateOf(initialFilter) }
    var persistAsDefault by remember { mutableStateOf(false) }
    val query = filter.trim().lowercase()
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
                    "选择模型",
                    modifier = Modifier.weight(1f),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PersistTogglePill(
                    persistAsDefault = persistAsDefault,
                    onToggle = { persistAsDefault = !persistAsDefault },
                )
            }

            // 现代圆角搜索框（替换 M3 OutlinedTextField）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 1.dp,
                        color = if (filter.isNotEmpty()) palette.borderAccent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (filter.isNotEmpty()) palette.accent else palette.muted,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(palette.accent),
                    decorationBox = { inner ->
                        Box {
                            if (filter.isEmpty()) {
                                Text(
                                    "按供应商或模型名筛选…",
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
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { filter = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清空",
                            tint = palette.muted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }

            if (shown.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (busy) {
                                "正在读取模型列表…"
                            } else if (query.isNotEmpty()) {
                                "没有匹配「$filter」的可用模型。"
                            } else {
                                "还没有可用模型。先登录订阅账号或配置 API Key，连接后立即生效，无需重启引擎。"
                            },
                            style = PiTheme.text.prose,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onManageProviders != null) {
                                SheetActionPill(
                                    text = "连接供应商与订阅",
                                    primary = true,
                                    onClick = {
                                        onDismiss()
                                        onManageProviders()
                                    },
                                )
                            }
                            SheetActionPill(text = "刷新列表", primary = false, onClick = onRefresh)
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
                                        provider.ifEmpty { "其他" },
                                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.muted,
                                    )
                                    Text(
                                        "${inGroup.size}",
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                        style = PiTheme.text.monoSmall,
                                        color = palette.dim,
                                    )
                                }
                            }
                        }
                        items(inGroup, key = { "${it.provider}/${it.id}" }) { model ->
                            ModelRow(
                                model = model,
                                selected = model.id == current?.id && model.provider == current.provider,
                                onClick = { onPick(model, persistAsDefault) },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (persistAsDefault) "选中后同步设为新会话默认模型" else "仅切换当前会话；可点右上角设为默认",
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
                if (onManageProviders != null && shown.isNotEmpty()) {
                    SheetActionPill(
                        text = "管理供应商",
                        primary = false,
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

@Composable
private fun ModelRow(model: PiResponses.ModelInfo, selected: Boolean, onClick: () -> Unit) {
    val palette = PiTheme.palette
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) palette.accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) palette.accent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        model.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                        color = if (selected) palette.accent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        MiniBadge(text = "当前", tone = palette.accent)
                    }
                }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    model.contextWindow?.let { MiniBadge(text = formatTokens(it), tone = palette.muted) }
                    if (model.reasoning) MiniBadge(text = "推理", tone = palette.accent)
                    if (model.acceptsImages) MiniBadge(text = "视觉", tone = palette.success)
                    model.inputCost?.let { cost ->
                        val price = "$${trimCost(cost)}" + (model.outputCost?.let { "/$${trimCost(it)}" } ?: "")
                        MiniBadge(text = "$price/M", tone = palette.muted)
                    }
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(palette.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = palette.contrastOn(palette.accent),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 思考等级选择器：按当前模型真实支持的档位渲染彩色触感卡片。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingPickerSheet(
    levels: List<String>,
    current: String,
    onPick: (String, persist: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
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
                    Text("思考深度", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "可选档位由当前模型决定",
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
                PersistTogglePill(
                    persistAsDefault = persistAsDefault,
                    onToggle = { persistAsDefault = !persistAsDefault },
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
                        "当前模型不支持切换思考等级。",
                        style = PiTheme.text.prose,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                levels.forEach { level ->
                    val selected = level == current
                    val levelColor = palette.thinking(level)
                    val desc = when (level.lowercase()) {
                        "off" -> "关闭推理，最快响应"
                        "minimal" -> "极简思考，超低延迟"
                        "low" -> "轻量推理，适合简单任务"
                        "medium" -> "均衡思考，日常编码推荐"
                        "high" -> "深度推理，适合复杂架构与排错"
                        "xhigh" -> "超深推理，攻克高难度问题"
                        "max" -> "最大思考预算，全面深度分析"
                        else -> null
                    }
                    Surface(
                        onClick = {
                            onPick(level, persistAsDefault)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp),
                        color = if (selected) levelColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) levelColor else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(levelColor),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    thinkingLabelOf(level),
                                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (selected) levelColor else MaterialTheme.colorScheme.onSurface,
                                )
                                if (desc != null) {
                                    Text(
                                        desc,
                                        style = PiTheme.text.meta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(levelColor),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = palette.contrastOn(levelColor),
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
                "会话控制中心",
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // 1. 队列投递策略卡
            SheetCardSection(title = "消息队列策略") {
                QueueModeRow(
                    title = "引导消息（Steering）",
                    supporting = "本回合工具跑完后、下次回答前插队投递",
                    current = state.meta.steeringMode,
                    tone = palette.warning,
                    onPick = onSteeringMode,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                QueueModeRow(
                    title = "后续消息（Follow-up）",
                    supporting = "整轮回复结束后顺序投递",
                    current = state.meta.followUpMode,
                    tone = palette.accent,
                    onPick = onFollowUpMode,
                )
            }

            // 2. 自动压缩与重试卡
            SheetCardSection(title = "上下文与容错") {
                PiSwitchRow(
                    title = "自动压缩上下文",
                    supporting = "接近窗口上限时自动生成摘要",
                    checked = state.meta.autoCompaction,
                    onCheckedChange = onAutoCompaction,
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                PiSwitchRow(
                    title = "自动退避重试",
                    supporting = "遇到限流或网络抖动时自动重试",
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
                        text = "立即压缩上下文",
                        primary = true,
                        onClick = onCompact,
                        modifier = Modifier.weight(1f),
                    )
                    SheetActionPill(
                        text = "取消等待重试",
                        primary = false,
                        onClick = onAbortRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 3. 会话快捷操作 2 列网格
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "会话操作",
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.muted,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionGridTile(
                        icon = Icons.Filled.AccountTree,
                        tint = palette.accent,
                        title = "会话树",
                        subtitle = "查看分支结构",
                        onClick = onTree,
                        modifier = Modifier.weight(1f),
                    )
                    SessionGridTile(
                        icon = Icons.Filled.CallSplit,
                        tint = palette.warning,
                        title = "历史分叉",
                        subtitle = "从指定消息分支",
                        onClick = onFork,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionGridTile(
                        icon = Icons.Filled.FileCopy,
                        tint = palette.success,
                        title = "复制会话",
                        subtitle = "克隆独立副本",
                        onClick = onClone,
                        modifier = Modifier.weight(1f),
                    )
                    SessionGridTile(
                        icon = Icons.Filled.DriveFileRenameOutline,
                        tint = palette.borderAccent,
                        title = "重命名",
                        subtitle = state.meta.sessionName ?: "未命名",
                        onClick = onRename,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionGridTile(
                        icon = Icons.Filled.IosShare,
                        tint = palette.accent,
                        title = "导出会话",
                        subtitle = "生成完整 HTML",
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                    )
                    SessionGridTile(
                        icon = Icons.Filled.ContentCopy,
                        tint = palette.muted,
                        title = "复制末条回复",
                        subtitle = "直接进剪贴板",
                        onClick = onCopyLast,
                        modifier = Modifier.weight(1f),
                    )
                }
                SessionGridTile(
                    icon = Icons.Filled.Info,
                    tint = palette.muted,
                    title = "会话元数据信息",
                    subtitle = "ID、文件路径、消息与工具计数",
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
            val options = listOf(QueueMode.OneAtATime to "逐条投递", QueueMode.All to "合并全部")
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

/** 上下文与用量仪表盘：环形占比主卡 + 本轮/累计双卡对比 + 缓存命中率 + 一键压缩。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(
    state: PiSessionViewModel.UiState,
    onDismiss: () -> Unit,
    onCompact: (() -> Unit)? = null,
) {
    val stats = state.stats
    val palette = PiTheme.palette
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "上下文与用量",
                    modifier = Modifier.weight(1f),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (onCompact != null) {
                    Surface(
                        onClick = {
                            onDismiss()
                            onCompact()
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
                                text = "压缩上下文",
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
                        "正在读取会话统计…",
                        style = PiTheme.text.prose,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ContextBlock(
                        stats = stats,
                        autoCompaction = state.meta.autoCompaction,
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
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 会话元数据卡：清晰展示名称、ID、文件路径、消息与工具调用计数。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStatsSheet(
    state: PiSessionViewModel.UiState,
    onDismiss: () -> Unit,
) {
    val stats = state.stats
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "会话元数据",
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (stats == null) {
                Text(
                    "正在读取…",
                    style = PiTheme.text.prose,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        StatLine("会话名称", state.meta.sessionName ?: "未命名")
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        StatLine("会话 ID", stats.sessionId ?: state.meta.sessionId ?: "—", mono = true)
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        StatLine("会话文件", stats.sessionFile ?: state.meta.sessionFile ?: "（尚未落盘）", mono = true)
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        StatLine("消息统计", "${stats.userMessages} 用户 · ${stats.assistantMessages} 模型 · ${stats.totalMessages} 总计")
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        StatLine("工具调用", "${stats.toolCalls} 次调用 · ${stats.toolResults} 条结果")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ContextBlock(
    stats: PiResponses.SessionStats,
    autoCompaction: Boolean,
    modelContextWindow: Long?,
) {
    val palette = PiTheme.palette
    val usage = stats.contextUsage
    val percent = usage?.percent
    val ringColor = percent?.let { contextProgressColor(it, palette) } ?: palette.muted

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, ringColor.copy(alpha = 0.30f)),
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = percent?.let { String.format(Locale.US, "%.1f", it) + "%" } ?: "—",
                        style = PiTheme.text.mono.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        color = ringColor,
                    )
                    MiniBadge(
                        text = if (autoCompaction) "自动压缩开" else "自动压缩关",
                        tone = if (autoCompaction) palette.success else palette.muted,
                    )
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
    return "已用 $used / 窗口 ${window?.let { piFormatTokens(it) } ?: "—"}"
}

@Composable
private fun UsageBlock(turn: TokenUsage?, session: TokenUsage?, lastMessage: TokenUsage?) {
    val palette = PiTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Token 与费用对比",
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = palette.muted,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(12.dp),
            ) {
                UsageColumn("本轮", turn, Modifier.weight(1f), cumulative = false)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(Modifier.width(10.dp))
                UsageColumn("本会话累计", session, Modifier.weight(1f), cumulative = true)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HitRateRow("缓存命中率（最后一条回复）", lastMessageHitRate(lastMessage))
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                HitRateRow("缓存命中率（本会话累计）", cumulativeHitRate(session))
            }
        }
    }
}

private sealed interface HitRate {
    data class Value(val percent: String) : HitRate
    data class None(val reason: String) : HitRate
}

private fun lastMessageHitRate(usage: TokenUsage?): HitRate {
    if (usage == null) return HitRate.None("本条还没有服务商回报的用量")
    val read = usage.cacheRead ?: 0L
    val denominator = (usage.input ?: 0L) + read + (usage.cacheWrite ?: 0L)
    if (denominator <= 0L) return HitRate.None("本条三项计数都是 0")
    return HitRate.Value(String.format(Locale.US, "%.1f", read * 100.0 / denominator) + "%")
}

private fun cumulativeHitRate(usage: TokenUsage?): HitRate {
    if (usage == null) return HitRate.None("累计计数还没读到")
    val read = usage.cacheRead ?: 0L
    val denominator = (usage.input ?: 0L) + read + (usage.cacheWrite ?: 0L)
    if (denominator <= 0L) return HitRate.None("本会话还没有累计计数")
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
        KvRow("输入", usage.input?.let { exactCount(it) } ?: "—")
        KvRow("输出", usage.output?.let { exactCount(it) } ?: "—")
        KvRow("缓存读", usage.cacheRead?.let { exactCount(it) } ?: "—")
        KvRow("缓存写", usage.cacheWrite?.let { exactCount(it) } ?: "—")
        KvRow("费用", usage.cost?.let { "$" + String.format(Locale.US, "%.3f", it) } ?: "—")
    }
}

@Composable
private fun KvRow(key: String, value: String, keyWidth: Dp = 52.dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key,
            modifier = Modifier.width(keyWidth),
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
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
                Text("从历史消息分叉", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "将在所选消息之前分出新会话，并把该消息放回输入框便于修改重发，原会话保持不变。",
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
                        "当前会话还没有可分叉的用户消息。先发送一条消息落盘后再试。",
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
                                        message.text.ifBlank { "（空消息）" },
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
    PiDialog(onDismissRequest = onDismiss) {
        PiDialogTitle("重命名会话")
        PiDialogBody("修改后将立即同步到会话列表。")
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, palette.borderAccent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (name.isEmpty()) {
                            Text("输入新名称…", style = PiTheme.text.prose, color = palette.muted)
                        }
                        inner()
                    }
                },
            )
            if (name.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清空",
                    tint = palette.muted,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { name = "" },
                )
            }
        }
        PiDialogActions {
            PiDialogAction(label = "取消", primary = false, onClick = onDismiss)
            PiDialogAction(
                label = "保存",
                primary = true,
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            )
        }
    }
}

// ------------------------------------------------------------------- shared pieces

@Composable
private fun PersistTogglePill(persistAsDefault: Boolean, onToggle: () -> Unit) {
    val palette = PiTheme.palette
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(999.dp),
        color = if (persistAsDefault) palette.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = 1.dp,
            color = if (persistAsDefault) palette.accent.copy(alpha = 0.55f) else Color.Transparent,
        ),
    ) {
        Text(
            if (persistAsDefault) "✓ 设为默认" else "仅本次会话",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = if (persistAsDefault) palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetCardSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
            color = PiTheme.palette.muted,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun SheetActionPill(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (primary) palette.accent else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                color = if (primary) palette.contrastOn(palette.accent) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniBadge(text: String, tone: Color) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
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
