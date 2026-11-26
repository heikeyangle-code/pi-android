package app.pi.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiSheetChromeMetrics
import app.pi.ui.components.PiType
import app.pi.ui.settings.McpSegmentedTrack
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiFieldPaddingBlock
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiTextFieldMinHeight
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 懒加载列表卡片切片：首/末项圆角 + 统一 surfaceContainerLow 底色 + 内部发丝分隔。 */
@Composable
private fun LazyCardSlice(
    isFirst: Boolean,
    isLast: Boolean,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = if (isFirst) PiSettingsMetrics.cardRadius else 0.dp,
        topEnd = if (isFirst) PiSettingsMetrics.cardRadius else 0.dp,
        bottomStart = if (isLast) PiSettingsMetrics.cardRadius else 0.dp,
        bottomEnd = if (isLast) PiSettingsMetrics.cardRadius else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (!isFirst) ModelsDivider()
        content()
    }
}

@Composable
private fun SheetSmallIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(8.dp)
    val tint = if (destructive) palette.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(30.dp)
            .alpha(if (enabled) 1f else 0.32f)
            .clip(shape)
            .background(if (destructive) palette.error.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                PiSpacing.hairlineThin,
                if (destructive) palette.error.copy(alpha = 0.28f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape,
            )
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ------------------------------------------------------------------ 跨供应商选模型

@Composable
internal fun ModelPickerSheet(
    host: () -> ModelsHost?,
    models: List<ModelFacts>,
    providerNames: Map<String, String>,
    current: Pair<String, String>?,
    onDismiss: () -> Unit,
    onSwitched: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ModelFacts?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val groups = groupModels(searchModels(models, providerNames, query), providerNames, current?.first)

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Text(
            tr("选择模型", "Select Model"),
            style = PiType.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
        )
        ModelsSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = tr("搜索模型或供应商", "Search models or providers"),
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSpacing.small * 2),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 14.dp),
        ) {
            groups.forEach { g ->
                item(key = "h:${g.providerId}") {
                    ModelsSectionLabel("${g.providerName} · ${g.models.size}", Modifier.padding(top = 12.dp, bottom = 6.dp))
                }
                itemsIndexed(g.models, key = { _, m -> "${m.provider}/${m.id}" }) { idx, m ->
                    LazyCardSlice(isFirst = idx == 0, isLast = idx == g.models.lastIndex) {
                        ModelRow(
                            model = m,
                            current = current?.let { m.sameAs(it.first, it.second) } == true,
                            selected = picked?.let { it.provider == m.provider && it.id == m.id } == true,
                            onClick = { picked = m; error = null },
                        )
                    }
                }
            }
            if (groups.isEmpty()) {
                item(key = "none") {
                    Text(
                        if (models.isEmpty()) tr("还没有可用的模型——先连接一个供应商。", "No models available yet — connect a provider first.")
                        else tr("没有匹配「${query.trim()}」的模型。", "No models matching \"${query.trim()}\"."),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 10.dp),
                    )
                }
            }
        }
        picked?.let { m ->
            UseBar(
                title = m.title,
                working = working,
                error = error,
                onUse = { use ->
                    val h = host() ?: run { error = tr("引擎没在运行。", "Engine is not running."); return@UseBar }
                    scope.launch {
                        working = true
                        runCatching { h.selectModel(m.provider, m.id, use.persist) }
                            .onSuccess {
                                onSwitched(
                                    if (use.persist) {
                                        tr("已设为默认并切换到 ${m.title}。", "Set as default and switched to ${m.title}.")
                                    } else {
                                        tr("已切换到 ${m.title}。", "Switched to ${m.title}.")
                                    },
                                )
                                onDismiss()
                            }
                            .onFailure { error = tr("切换失败：${it.message}", "Switch failed: ${it.message}"); working = false }
                    }
                },
                onCancel = { picked = null; error = null },
            )
        }
    }
}

/** 底部的「用在哪」条：模型名 + 两条路（只这次 / 设为默认）+ 取消。 */
@Composable
internal fun UseBar(
    title: String,
    working: Boolean,
    error: String?,
    onUse: (UseScope) -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = PiSheetChromeMetrics.sheetCardBg,
        border = BorderStroke(PiSpacing.hairlineThin, PiTheme.palette.accent.copy(alpha = 0.42f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(PiSheetChromeMetrics.sheetControlBg)
                        .clickable(onClickLabel = tr("取消", "Cancel"), onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = tr("取消", "Cancel"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiPrimaryButton(
                    label = UseScope.Session.label,
                    enabled = !working,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onUse(UseScope.Session) },
                )
                PiSecondaryButton(
                    label = UseScope.Default.label,
                    enabled = !working,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onUse(UseScope.Default) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 统一作用域 / 循环模型选择器（对齐官方 ScopedModelsSelectorComponent）

@Composable
internal fun ScopedModelsSheet(
    host: () -> ModelsHost?,
    models: List<app.pi.protocol.PiResponses.ModelInfo>,
    providerNames: Map<String, String>,
    initialScoped: List<app.pi.host.HostAdvanced.ModelRef>,
    persistedPatterns: List<String>,
    onDismiss: () -> Unit,
    onApplied: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val modelByKey = remember(models) {
        models.associateBy { "${it.provider.orEmpty()}/${it.id}" }
    }
    var userEdited by remember { mutableStateOf(false) }
    var selectedKeys by remember {
        val seed = if (initialScoped.isNotEmpty()) {
            initialScoped.map { "${it.provider}/${it.id}" }
        } else {
            persistedPatterns.filter { it in modelByKey }
        }
        mutableStateOf(seed.distinct())
    }
    androidx.compose.runtime.LaunchedEffect(initialScoped, persistedPatterns, models) {
        if (!userEdited) {
            val seed = if (initialScoped.isNotEmpty()) {
                initialScoped.map { "${it.provider}/${it.id}" }
            } else {
                persistedPatterns.filter { it in modelByKey }
            }
            selectedKeys = seed.distinct()
        }
    }
    val selectedSet = remember(selectedKeys) { selectedKeys.toSet() }

    val filtered = remember(models, providerNames, query) {
        val facts = models.map { it.facts() }
        val matched = searchModels(facts, providerNames, query)
        groupModels(matched, providerNames, currentProvider = null)
    }

    fun toggleKey(key: String) {
        userEdited = true
        selectedKeys = if (key in selectedSet) selectedKeys.filterNot { it == key } else selectedKeys + key
    }

    fun moveSelected(index: Int, delta: Int) {
        val target = index + delta
        if (target !in selectedKeys.indices) return
        userEdited = true
        val mutable = selectedKeys.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        selectedKeys = mutable
    }

    fun toggleProvider(providerModelKeys: List<String>) {
        userEdited = true
        val allSelected = providerModelKeys.isNotEmpty() && providerModelKeys.all { it in selectedSet }
        selectedKeys = if (allSelected) {
            selectedKeys.filterNot { it in providerModelKeys }
        } else {
            (selectedKeys + providerModelKeys).distinct()
        }
    }

    fun applySelection(persist: Boolean) {
        val h = host() ?: run { error = tr("引擎没在运行。", "Engine is not running."); return }
        scope.launch {
            working = true
            error = null
            val allModelsSelected = models.isNotEmpty() &&
                selectedKeys.size >= models.size &&
                models.all { "${it.provider.orEmpty()}/${it.id}" in selectedSet }
            val effectiveKeys = if (allModelsSelected) emptyList() else selectedKeys
            val refs = effectiveKeys.mapNotNull { key ->
                val m = modelByKey[key]
                val p = m?.provider ?: key.substringBefore('/', "")
                val id = m?.id ?: key.substringAfter('/', key)
                if (p.isBlank() || id.isBlank()) null else app.pi.host.HostAdvanced.ModelRef(p, id, m?.name ?: id)
            }
            val result = runCatching {
                h.advanced.setScopedModels(refs)
                if (persist) {
                    h.setEnabledModels(effectiveKeys.takeIf { it.isNotEmpty() })
                }
            }
            result.onSuccess {
                val msg = when {
                    refs.isEmpty() && persist -> tr("已设为循环全部可用模型。", "Saved to cycle through all available models.")
                    refs.isEmpty() -> tr("已清空当前会话作用域模型（循环全部可用模型）。", "Cleared current session scoped models (cycling all available).")
                    persist -> tr("已应用并设为默认循环模型（${refs.size} 个）。", "Applied and saved as default cycling models (${refs.size}).")
                    else -> tr("已应用到当前会话作用域模型（${refs.size} 个）。", "Applied to current session scoped models (${refs.size}).")
                }
                onApplied(msg)
                onDismiss()
            }.onFailure {
                error = tr("应用失败：${it.message}", "Failed to apply: ${it.message}")
                working = false
            }
        }
    }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(tr("作用域与循环模型", "Scoped & Cycling Models"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (selectedKeys.isEmpty()) tr("未选中任何模型 = 循环切换全部可用模型", "None selected = cycle through all available models")
                else tr("已选 ${selectedKeys.size} 个模型（按顺序循环）", "${selectedKeys.size} models selected (cycled in order)"),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (persistedPatterns.any { it.contains('*') }) {
                Text(
                    tr(
                        "当前配置含通配符：${persistedPatterns.joinToString(", ")}；点「应用并设为默认」将按所选模型覆盖。",
                        "Current config contains wildcards: ${persistedPatterns.joinToString(", ")}; \"Apply & Set Default\" will overwrite with the selected models.",
                    ),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.warning,
                )
            }
        }
        ModelsSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = tr("搜索模型或供应商", "Search models or providers"),
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSpacing.small * 2),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiSettingsChip(
                text = tr("全选可见", "Select Visible"),
                onClick = {
                    userEdited = true
                    val visibleKeys = filtered.flatMap { g -> g.models.map { "${it.provider}/${it.id}" } }
                    selectedKeys = (selectedKeys + visibleKeys).distinct()
                },
            )
            PiSettingsChip(
                text = tr("清空", "Clear"),
                enabled = selectedKeys.isNotEmpty(),
                onClick = {
                    userEdited = true
                    selectedKeys = emptyList()
                },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 14.dp),
        ) {
            if (selectedKeys.isNotEmpty() && query.isBlank()) {
                item(key = "selected-head") {
                    ModelsSectionLabel(
                        tr("已选顺序（${selectedKeys.size}）", "Selected Order (${selectedKeys.size})"),
                        Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                }
                items(selectedKeys.size, key = { idx -> "sel:${selectedKeys[idx]}" }) { idx ->
                    val key = selectedKeys[idx]
                    val info = modelByKey[key]
                    LazyCardSlice(isFirst = idx == 0, isLast = idx == selectedKeys.lastIndex) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ModelsCardPadding, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${idx + 1}. ${info?.name?.ifBlank { null } ?: key.substringAfter('/')}",
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    key,
                                    style = PiTheme.text.monoSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            SheetSmallIconButton(
                                icon = Icons.Filled.KeyboardArrowUp,
                                contentDescription = tr("上移", "Move up"),
                                enabled = idx > 0,
                                onClick = { moveSelected(idx, -1) },
                            )
                            SheetSmallIconButton(
                                icon = Icons.Filled.KeyboardArrowDown,
                                contentDescription = tr("下移", "Move down"),
                                enabled = idx < selectedKeys.lastIndex,
                                onClick = { moveSelected(idx, 1) },
                            )
                            SheetSmallIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = tr("移除", "Remove"),
                                destructive = true,
                                onClick = { toggleKey(key) },
                            )
                        }
                    }
                }
            }

            filtered.forEach { g ->
                val providerKeys = g.models.map { "${it.provider}/${it.id}" }
                val allIn = providerKeys.isNotEmpty() && providerKeys.all { it in selectedSet }
                item(key = "grp:${g.providerId}") {
                    ModelsSectionLabel(
                        "${g.providerName} · ${g.models.size}",
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                        trailing = {
                            PiSettingsChip(
                                text = if (allIn) tr("取消全选", "Deselect All") else tr("全选该供应商", "Select Provider"),
                                active = allIn,
                                onClick = { toggleProvider(providerKeys) },
                            )
                        },
                    )
                }
                itemsIndexed(g.models, key = { _, m -> "m:${m.provider}/${m.id}" }) { idx, m ->
                    val key = "${m.provider}/${m.id}"
                    val checked = key in selectedSet
                    LazyCardSlice(isFirst = idx == 0, isLast = idx == g.models.lastIndex) {
                        ModelRow(
                            model = m,
                            current = false,
                            selected = checked,
                            onClick = { toggleKey(key) },
                        )
                    }
                }
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (models.isEmpty()) tr("还没有可用模型——先连接一个供应商。", "No models available yet — connect a provider first.")
                        else tr("没有匹配「${query.trim()}」的模型。", "No models matching \"${query.trim()}\"."),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 12.dp),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PiSecondaryButton(
                        label = tr("取消", "Cancel"),
                        compact = true,
                        onClick = onDismiss,
                    )
                    PiSecondaryButton(
                        label = tr("设为默认", "Set Default"),
                        enabled = !working,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { applySelection(persist = true) },
                    )
                    PiPrimaryButton(
                        label = tr("本会话应用", "Apply Session"),
                        enabled = !working,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { applySelection(persist = false) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 官方两步「逐模型思考等级」选择器

private fun officialThinkingMeta(level: String): Pair<String, String>? = when (level) {
    "off" -> tr("关闭", "Off") to tr("关闭思考，直接回答", "Disable reasoning and respond directly")
    "minimal" -> tr("极简", "Minimal") to tr("最少推理（约 1k token 预算）", "Minimal reasoning (~1k token budget)")
    "low" -> tr("低", "Low") to tr("轻量推理（约 2k token 预算）", "Light reasoning (~2k token budget)")
    "medium" -> tr("中", "Medium") to tr("均衡推理（约 8k token 预算）", "Balanced reasoning (~8k token budget)")
    "high" -> tr("高", "High") to tr("深度推理（约 16k token 预算）", "Deep reasoning (~16k token budget)")
    "xhigh" -> tr("超高", "Extra High") to tr("极深推理（约 32k token 预算）", "Very deep reasoning (~32k token budget)")
    "max" -> tr("最高", "Max") to tr("最大推理深度", "Maximum reasoning depth")
    else -> null
}

@Composable
internal fun ModelThinkingLevelsSheet(
    host: () -> ModelsHost?,
    models: List<app.pi.protocol.PiResponses.ModelInfo>,
    providerNames: Map<String, String>,
    overrides: Map<String, String>,
    defaultLevel: String,
    onDismiss: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<app.pi.protocol.PiResponses.ModelInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var liveOverrides by remember(overrides) { mutableStateOf(overrides) }
    val palette = PiTheme.palette

    val reasoningModels = remember(models, liveOverrides, query, providerNames) {
        val pool = models.filter { it.reasoning || it.supportedThinkingLevels.isNotEmpty() || "${it.provider.orEmpty()}/${it.id}" in liveOverrides }
        val q = query.trim()
        val hit = if (q.isEmpty()) {
            pool
        } else {
            pool.filter { m ->
                val pName = providerNames[m.provider.orEmpty()].orEmpty()
                matches(q, m.name, m.id, m.provider.orEmpty(), pName)
            }
        }
        hit.sortedWith(
            compareByDescending<app.pi.protocol.PiResponses.ModelInfo> { "${it.provider.orEmpty()}/${it.id}" in liveOverrides }
                .thenBy { (providerNames[it.provider.orEmpty()] ?: it.provider.orEmpty()).lowercase() }
                .thenBy { it.name.lowercase() },
        )
    }

    val modelKeys = remember(models) { models.map { "${it.provider.orEmpty()}/${it.id}" }.toSet() }
    val orphanOverrides = remember(liveOverrides, modelKeys) {
        liveOverrides.filterKeys { it !in modelKeys }.entries.toList()
    }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        val active = selectedModel
        if (active == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(tr("逐模型思考等级", "Per-Model Thinking Level"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    tr(
                        "全局默认：${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel} · 已单独覆盖 ${liveOverrides.size} 个模型",
                        "Global default: ${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel} · ${liveOverrides.size} models overridden",
                    ),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelsSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = tr("搜索推理模型", "Search reasoning models"),
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSpacing.small * 2),
            )
            error?.let {
                Text(
                    it,
                    style = PiTheme.text.meta,
                    color = palette.error,
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 2.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 14.dp),
            ) {
                if (orphanOverrides.isNotEmpty() && query.isBlank()) {
                    item(key = "orphan-head") {
                        ModelsSectionLabel(
                            tr("其他已配置覆盖（未连接供应商）", "Other Configured Overrides (Disconnected Providers)"),
                            Modifier.padding(top = 6.dp, bottom = 6.dp),
                        )
                    }
                    itemsIndexed(orphanOverrides, key = { _, e -> "orphan:${e.key}" }) { idx, (key, lvl) ->
                        LazyCardSlice(isFirst = idx == 0, isLast = idx == orphanOverrides.lastIndex) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ModelsCardPadding, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(key, style = PiTheme.text.monoSmall, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        tr("当前覆盖：${officialThinkingMeta(lvl)?.first ?: lvl}", "Override: ${officialThinkingMeta(lvl)?.first ?: lvl}"),
                                        style = PiTheme.text.meta,
                                        color = palette.accent,
                                    )
                                }
                                SheetSmallIconButton(
                                    icon = Icons.Filled.Close,
                                    contentDescription = tr("移除", "Remove"),
                                    enabled = !busy,
                                    destructive = true,
                                    onClick = {
                                        val h = host() ?: return@SheetSmallIconButton
                                        val p = key.substringBefore('/')
                                        val id = key.substringAfter('/')
                                        scope.launch {
                                            busy = true
                                            runCatching { h.removeModelThinkingLevel(p, id) }
                                                .onSuccess {
                                                    liveOverrides = liveOverrides - key
                                                    onChanged(tr("已移除 $key 的思考等级覆盖。", "Removed thinking level override for $key."))
                                                }
                                                .onFailure { error = tr("移除失败：${it.message}", "Failed to remove: ${it.message}") }
                                            busy = false
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (reasoningModels.isNotEmpty()) {
                    item(key = "reasoning-head") {
                        ModelsSectionLabel(
                            tr("支持推理的模型（${reasoningModels.size}）", "Reasoning Models (${reasoningModels.size})"),
                            Modifier.padding(top = 10.dp, bottom = 6.dp),
                        )
                    }
                }

                itemsIndexed(reasoningModels, key = { _, m -> "${m.provider.orEmpty()}/${m.id}" }) { idx, m ->
                    val key = "${m.provider.orEmpty()}/${m.id}"
                    val currentLvl = liveOverrides[key]
                    val pLabel = providerNames[m.provider.orEmpty()] ?: m.provider.orEmpty()
                    LazyCardSlice(isFirst = idx == 0, isLast = idx == reasoningModels.lastIndex) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedModel = m
                                    error = null
                                }
                                .padding(horizontal = ModelsCardPadding, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    m.name.ifBlank { m.id },
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append(pLabel)
                                        if (m.supportedThinkingLevels.isNotEmpty()) {
                                            append(tr(" · 支持 ", " · Supports "))
                                            append(m.supportedThinkingLevels.joinToString("/") { officialThinkingMeta(it)?.first ?: it })
                                        }
                                    },
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (currentLvl != null) {
                                TagPill(officialThinkingMeta(currentLvl)?.first ?: currentLvl, tone = palette.accent)
                            } else {
                                Text(
                                    tr("跟随默认", "Default"),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (reasoningModels.isEmpty()) {
                    item(key = "no-reasoning") {
                        Text(
                            if (models.isEmpty()) tr("还没有可用模型——先连接一个供应商。", "No models available yet — connect a provider first.")
                            else tr("没有找到支持推理的模型。", "No reasoning models found."),
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 12.dp),
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PiSecondaryButton(label = tr("完成", "Done"), onClick = onDismiss)
                }
            }
        } else {
            val key = "${active.provider.orEmpty()}/${active.id}"
            val currentLvl = liveOverrides[key]
            val levels = active.supportedThinkingLevels.ifEmpty { listOf("minimal", "low", "medium", "high") }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(active.name.ifBlank { active.id }, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    tr("$key · 选择该模型新会话启动时的默认思考等级", "$key · Choose the default thinking level for new sessions with this model"),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let {
                Text(
                    it,
                    style = PiTheme.text.meta,
                    color = palette.error,
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp),
                contentPadding = PaddingValues(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "follow-default") {
                    val isSelected = currentLvl == null
                    Surface(
                        onClick = {
                            if (busy) return@Surface
                            val h = host() ?: run { error = tr("引擎没在运行。", "Engine is not running."); return@Surface }
                            scope.launch {
                                busy = true
                                error = null
                                runCatching { h.removeModelThinkingLevel(active.provider.orEmpty(), active.id) }
                                    .onSuccess {
                                        liveOverrides = liveOverrides - key
                                        val displayName = active.name.ifBlank { active.id }
                                        onChanged(tr("$displayName 已恢复跟随全局默认。", "$displayName reset to global default."))
                                        selectedModel = null
                                    }
                                    .onFailure { error = tr("保存失败：${it.message}", "Failed to save: ${it.message}") }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = PiShapes.card,
                        color = if (isSelected) palette.accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            if (isSelected) 1.dp else PiSpacing.hairlineThin,
                            if (isSelected) palette.accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tr("跟随全局默认（${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel}）", "Follow Global Default (${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel})"),
                                    style = PiTheme.text.prose.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                                    color = if (isSelected) palette.accent else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    tr("不为此模型单独覆盖，使用全局默认思考等级。", "Do not override for this model; use the global default thinking level."),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                itemsIndexed(levels, key = { _, lvl -> lvl }) { _, lvl ->
                    val meta = officialThinkingMeta(lvl)
                    val activeLevel = currentLvl == lvl
                    Surface(
                        onClick = {
                            if (busy) return@Surface
                            val h = host() ?: run { error = tr("引擎没在运行。", "Engine is not running."); return@Surface }
                            scope.launch {
                                busy = true
                                error = null
                                runCatching { h.setModelThinkingLevel(active.provider.orEmpty(), active.id, lvl) }
                                    .onSuccess {
                                        liveOverrides = liveOverrides + (key to lvl)
                                        val displayName = active.name.ifBlank { active.id }
                                        onChanged(tr("$displayName 思考等级已设为 ${meta?.first ?: lvl}。", "$displayName thinking level set to ${meta?.first ?: lvl}."))
                                        selectedModel = null
                                    }
                                    .onFailure { error = tr("保存失败：${it.message}", "Failed to save: ${it.message}") }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = PiShapes.card,
                        color = if (activeLevel) palette.accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            if (activeLevel) 1.dp else PiSpacing.hairlineThin,
                            if (activeLevel) palette.accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${meta?.first ?: lvl} ($lvl)",
                                    style = PiTheme.text.prose.copy(fontWeight = if (activeLevel) FontWeight.SemiBold else FontWeight.Medium),
                                    color = if (activeLevel) palette.accent else MaterialTheme.colorScheme.onSurface,
                                )
                                meta?.second?.let { desc ->
                                    Text(desc, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (activeLevel) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PiSecondaryButton(
                        label = tr("返回模型列表", "Back to Models"),
                        leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        enabled = !busy,
                        onClick = { selectedModel = null; error = null },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 官方 models.json 导入 / 编辑 / 修复

private val prettyModelsJson = Json { prettyPrint = true }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelsJsonImportSheet(
    host: () -> ModelsHost?,
    existingProviders: Map<String, JsonObject>,
    corruptError: String?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette

    val initialText = remember(existingProviders, corruptError) {
        if (existingProviders.isEmpty()) {
            ""
        } else {
            val root = JsonObject(mapOf("providers" to JsonObject(existingProviders)))
            runCatching { prettyModelsJson.encodeToString(JsonObject.serializer(), root) }.getOrDefault("")
        }
    }
    var text by remember(initialText) { mutableStateOf(initialText) }
    var mergeExisting by remember(existingProviders, corruptError) {
        mutableStateOf(existingProviders.isNotEmpty() && corruptError == null && initialText.isEmpty())
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content.isNullOrBlank()) {
                error = tr("所选文件为空", "Selected file is empty")
            } else {
                error = null
                text = content
                if (existingProviders.isNotEmpty() && corruptError == null) mergeExisting = true
            }
        }.onFailure { err ->
            error = err.message ?: tr("读取文件失败", "Failed to read file")
        }
    }

    val parsed = remember(text) {
        val raw = text.trim()
        if (raw.isEmpty()) null to null
        else runCatching {
            val el = Json.parseToJsonElement(raw) as? JsonObject
                ?: return@runCatching null to tr("根元素必须是 JSON 对象", "Root must be a JSON object")
            val providers = el["providers"] as? JsonObject
                ?: return@runCatching null to tr("缺少 providers 对象（官方 models.json 格式：{ \"providers\": { … } }）", "Missing \"providers\" object (official models.json format: { \"providers\": { … } })")
            providers to null
        }.getOrElse { null to tr("JSON 语法有误", "Invalid JSON syntax") }
    }
    val detectedProviders = parsed.first
    val parseError = parsed.second

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    tr("导入 / 编辑 models.json", "Import / Edit models.json"),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    tr(
                        "完全遵循官方 ~/.pi/agent/models.json 结构，保存时经官方 Pi SDK 校验生效；支持批量导入自定义端点（Ollama / vLLM / DeepSeek / 中转等）或修复损坏配置。",
                        "Follows official ~/.pi/agent/models.json schema and validates via Pi SDK on save. Bulk-import custom endpoints or repair invalid JSON.",
                    ),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            corruptError?.let { cErr ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.error.copy(alpha = 0.08f),
                    border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
                ) {
                    Text(
                        tr("当前文件错误：$cErr", "Current file error: $cErr"),
                        style = PiTheme.text.meta,
                        color = palette.error,
                        modifier = Modifier.padding(11.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiSecondaryButton(
                    label = tr("选择 .json 文件", "Pick .json"),
                    leadingIcon = Icons.Filled.FolderOpen,
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
                PiSecondaryButton(
                    label = tr("粘贴剪贴板", "Paste"),
                    leadingIcon = Icons.Filled.ContentPaste,
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) {
                            error = null
                            text = pasted
                        }
                    },
                )
            }
            if (existingProviders.isNotEmpty() && corruptError == null) {
                McpSegmentedTrack(
                    options = listOf(tr("合并到现有配置", "Merge with Existing"), tr("整份覆盖", "Replace Entire File")),
                    selectedIndex = if (mergeExisting) 0 else 1,
                    onSelect = { idx -> mergeExisting = idx == 0 },
                )
            }
            PiEditorField(
                value = text,
                onValueChange = {
                    error = null
                    text = it
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "{\n  \"providers\": {\n    \"my-openai\": {\n      \"baseUrl\": \"https://…/v1\",\n      \"api\": \"openai-completions\",\n      \"apiKey\": \"\${MY_API_KEY}\",\n      \"models\": [{ \"id\": \"…\" }]\n    }\n  }\n}",
                singleLine = false,
                maxLines = 16,
                minHeight = PiTextFieldMinHeight * 2,
                contentPadding = PiFieldPaddingBlock,
                container = MaterialTheme.colorScheme.surfaceContainerLowest,
                idleBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            )
            if (parseError != null) {
                Text(parseError, style = PiTheme.text.meta, color = palette.error)
            } else if (detectedProviders != null && detectedProviders.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.accent.copy(alpha = 0.35f)),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            tr("检测到 ${detectedProviders.size} 个供应商", "Detected ${detectedProviders.size} provider(s)"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.accent,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            detectedProviders.keys.forEach { key ->
                                TagPill(key)
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiSecondaryButton(
                    label = tr("取消", "Cancel"),
                    onClick = onDismiss,
                )
                PiPrimaryButton(
                    label = if (saving) tr("保存中…", "Saving…") else tr("校验并保存", "Validate & Save"),
                    enabled = !saving && detectedProviders != null && parseError == null,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val h = host() ?: run {
                            error = tr("引擎没在运行。", "Engine is not running.")
                            return@PiPrimaryButton
                        }
                        val incoming = detectedProviders ?: return@PiPrimaryButton
                        val finalProviders = if (mergeExisting && existingProviders.isNotEmpty()) {
                            LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(existingProviders).apply {
                                incoming.forEach { (k, v) -> put(k, v) }
                            }
                        } else {
                            incoming
                        }
                        val payload = JsonObject(mapOf("providers" to JsonObject(finalProviders)))
                        scope.launch {
                            saving = true
                            error = null
                            runCatching { h.advanced.setModelsConfig(payload) }
                                .onSuccess {
                                    onSaved(
                                        tr(
                                            "models.json 已保存（共 ${finalProviders.size} 个自定义供应商）。",
                                            "Saved models.json (${finalProviders.size} custom provider(s)).",
                                        ),
                                    )
                                    onDismiss()
                                }
                                .onFailure { err ->
                                    error = err.message ?: tr("保存失败", "Save failed")
                                    saving = false
                                }
                        }
                    },
                )
            }
        }
    }
}
