package app.pi.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch

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
            color = PiTheme.palette.text,
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
        )
        ModelsSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = tr("搜索模型或供应商", "Search models or providers"),
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSpacing.small * 2),
        )
        LazyColumn(Modifier.weight(1f)) {
            groups.forEach { g ->
                item(key = "h:${g.providerId}") {
                    ModelsSectionLabel("${g.providerName} · ${g.models.size}", Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                items(g.models, key = { "${it.provider}/${it.id}" }) { m ->
                    ModelRow(
                        model = m,
                        current = current?.let { m.sameAs(it.first, it.second) } == true,
                        selected = picked?.let { it.provider == m.provider && it.id == m.id } == true,
                        onClick = { picked = m; error = null },
                    )
                }
            }
            if (groups.isEmpty()) {
                item(key = "none") {
                    Text(
                        if (models.isEmpty()) tr("还没有可用的模型——先连接一个供应商。", "No models available yet — connect a provider first.")
                        else tr("没有匹配「${query.trim()}」的模型。", "No models matching \"${query.trim()}\"."),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.dim,
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, PiTheme.palette.accent.copy(alpha = 0.40f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                color = PiTheme.palette.text,
                maxLines = 1,
            )
            error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction(UseScope.Session.label, enabled = !working, onClick = { onUse(UseScope.Session) })
                SecondaryAction(UseScope.Default.label, enabled = !working, onClick = { onUse(UseScope.Default) })
                SecondaryAction(tr("取消", "Cancel"), onClick = onCancel)
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
    var selectedKeys by remember(initialScoped, persistedPatterns, models) {
        val seed = if (initialScoped.isNotEmpty()) {
            initialScoped.map { "${it.provider}/${it.id}" }
        } else {
            persistedPatterns.filter { it in modelByKey }
        }
        mutableStateOf(seed.distinct())
    }
    val selectedSet = remember(selectedKeys) { selectedKeys.toSet() }

    val filtered = remember(models, providerNames, query) {
        val facts = models.map { it.facts() }
        val matched = searchModels(facts, providerNames, query)
        groupModels(matched, providerNames, currentProvider = null)
    }

    fun toggleKey(key: String) {
        selectedKeys = if (key in selectedSet) selectedKeys.filterNot { it == key } else selectedKeys + key
    }

    fun moveSelected(index: Int, delta: Int) {
        val target = index + delta
        if (target !in selectedKeys.indices) return
        val mutable = selectedKeys.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        selectedKeys = mutable
    }

    fun toggleProvider(providerModelKeys: List<String>) {
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
            val refs = selectedKeys.mapNotNull { key ->
                val m = modelByKey[key]
                val p = m?.provider ?: key.substringBefore('/', "")
                val id = m?.id ?: key.substringAfter('/', key)
                if (p.isBlank() || id.isBlank()) null else app.pi.host.HostAdvanced.ModelRef(p, id, m?.name ?: id)
            }
            val result = runCatching {
                h.advanced.setScopedModels(refs)
                if (persist) {
                    h.setEnabledModels(selectedKeys.takeIf { it.isNotEmpty() })
                }
            }
            result.onSuccess {
                val msg = when {
                    refs.isEmpty() && persist -> tr("已清空作用域与默认循环模型（将循环全部可用模型）。", "Cleared scoped and default cycling models (will cycle all available models).")
                    refs.isEmpty() -> tr("已清空当前会话作用域模型。", "Cleared current session scoped models.")
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
            Text(tr("作用域与循环模型", "Scoped & Cycling Models"), style = PiType.title, color = PiTheme.palette.text)
            Text(
                if (selectedKeys.isEmpty()) tr("未选中任何模型 = 循环切换全部可用模型", "None selected = cycle through all available models")
                else tr("已选 ${selectedKeys.size} 个模型（按顺序循环）", "${selectedKeys.size} models selected (cycled in order)"),
                style = PiTheme.text.meta,
                color = PiTheme.palette.dim,
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiSettingsChip(
                text = tr("全选可见", "Select Visible"),
                onClick = {
                    val visibleKeys = filtered.flatMap { g -> g.models.map { "${it.provider}/${it.id}" } }
                    selectedKeys = (selectedKeys + visibleKeys).distinct()
                },
            )
            PiSettingsChip(
                text = tr("清空", "Clear"),
                enabled = selectedKeys.isNotEmpty(),
                onClick = { selectedKeys = emptyList() },
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            if (selectedKeys.isNotEmpty() && query.isBlank()) {
                item(key = "selected-head") {
                    ModelsSectionLabel(tr("已选顺序（${selectedKeys.size}）", "Selected Order (${selectedKeys.size})"), Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                items(selectedKeys.size, key = { idx -> "sel:${selectedKeys[idx]}" }) { idx ->
                    val key = selectedKeys[idx]
                    val info = modelByKey[key]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${idx + 1}. ${info?.name?.ifBlank { null } ?: key.substringAfter('/')}",
                                style = PiTheme.text.prose,
                                color = PiTheme.palette.text,
                                maxLines = 1,
                            )
                            Text(key, style = PiTheme.text.monoSmall, color = PiTheme.palette.dim, maxLines = 1)
                        }
                        PiSettingsChip(text = "↑", enabled = idx > 0, onClick = { moveSelected(idx, -1) })
                        PiSettingsChip(text = "↓", enabled = idx < selectedKeys.lastIndex, onClick = { moveSelected(idx, 1) })
                        PiSettingsChip(text = tr("移除", "Remove"), onClick = { toggleKey(key) })
                    }
                }
            }

            filtered.forEach { g ->
                val providerKeys = g.models.map { "${it.provider}/${it.id}" }
                val allIn = providerKeys.isNotEmpty() && providerKeys.all { it in selectedSet }
                item(key = "grp:${g.providerId}") {
                    ModelsSectionLabel(
                        "${g.providerName} · ${g.models.size}",
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        trailing = {
                            PiSettingsChip(
                                text = if (allIn) tr("取消全选", "Deselect All") else tr("全选该供应商", "Select Provider"),
                                active = allIn,
                                onClick = { toggleProvider(providerKeys) },
                            )
                        },
                    )
                }
                items(g.models, key = { "m:${it.provider}/${it.id}" }) { m ->
                    val key = "${m.provider}/${m.id}"
                    val checked = key in selectedSet
                    ModelRow(
                        model = m,
                        current = false,
                        selected = checked,
                        onClick = { toggleKey(key) },
                    )
                }
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (models.isEmpty()) tr("还没有可用模型——先连接一个供应商。", "No models available yet — connect a provider first.")
                        else tr("没有匹配「${query.trim()}」的模型。", "No models matching \"${query.trim()}\"."),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.dim,
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 12.dp),
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction(tr("仅本会话应用", "Session Only"), enabled = !working, onClick = { applySelection(persist = false) })
                SecondaryAction(tr("应用并设为默认", "Apply & Set Default"), enabled = !working, onClick = { applySelection(persist = true) })
                SecondaryAction(tr("取消", "Cancel"), onClick = onDismiss)
            }
        }
    }
}

// ------------------------------------------------------------------ 官方两步「逐模型思考等级」选择器

private fun officialThinkingMeta(level: String): Pair<String, String>? = when (level) {
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
        liveOverrides.filterKeys { it !in modelKeys }
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
                Text(tr("逐模型思考等级", "Per-Model Thinking Level"), style = PiType.title, color = PiTheme.palette.text)
                Text(
                    tr(
                        "全局默认：${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel} · 已单独覆盖 ${liveOverrides.size} 个模型",
                        "Global default: ${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel} · ${liveOverrides.size} models overridden",
                    ),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.dim,
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
                    color = PiTheme.palette.error,
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                if (orphanOverrides.isNotEmpty() && query.isBlank()) {
                    item(key = "orphan-head") {
                        ModelsSectionLabel(tr("其他已配置覆盖（未连接供应商）", "Other Configured Overrides (Disconnected Providers)"), Modifier.padding(top = 6.dp, bottom = 4.dp))
                    }
                    orphanOverrides.entries.forEach { (key, lvl) ->
                        item(key = "orphan:$key") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(key, style = PiTheme.text.monoSmall, color = PiTheme.palette.text)
                                    Text(
                                        tr("当前覆盖：${officialThinkingMeta(lvl)?.first ?: lvl}", "Override: ${officialThinkingMeta(lvl)?.first ?: lvl}"),
                                        style = PiTheme.text.meta,
                                        color = PiTheme.palette.accent,
                                    )
                                }
                                PiSettingsChip(
                                    text = tr("移除", "Remove"),
                                    enabled = !busy,
                                    onClick = {
                                        val h = host() ?: return@PiSettingsChip
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

                items(reasoningModels, key = { "${it.provider.orEmpty()}/${it.id}" }) { m ->
                    val key = "${m.provider.orEmpty()}/${m.id}"
                    val currentLvl = liveOverrides[key]
                    val pLabel = providerNames[m.provider.orEmpty()] ?: m.provider.orEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedModel = m
                                error = null
                            }
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.name.ifBlank { m.id }, style = PiTheme.text.prose, color = PiTheme.palette.text, maxLines = 1)
                            Text(
                                buildString {
                                    append(pLabel)
                                    if (m.supportedThinkingLevels.isNotEmpty()) {
                                        append(tr(" · 支持 ", " · Supports "))
                                        append(m.supportedThinkingLevels.joinToString("/") { officialThinkingMeta(it)?.first ?: it })
                                    }
                                },
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.dim,
                                maxLines = 1,
                            )
                        }
                        Text(
                            currentLvl?.let { officialThinkingMeta(it)?.first ?: it } ?: tr("跟随默认", "Default"),
                            style = PiTheme.text.meta,
                            color = if (currentLvl != null) PiTheme.palette.accent else PiTheme.palette.muted,
                        )
                        Text("›", style = PiTheme.text.prose, color = PiTheme.palette.dim)
                    }
                }
                if (reasoningModels.isEmpty()) {
                    item(key = "no-reasoning") {
                        Text(
                            if (models.isEmpty()) tr("还没有可用模型——先连接一个供应商。", "No models available yet — connect a provider first.")
                            else tr("没有找到支持推理的模型。", "No reasoning models found."),
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.dim,
                            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 12.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                SecondaryAction(text = tr("完成", "Done"), onClick = onDismiss)
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
                Text(active.name.ifBlank { active.id }, style = PiType.title, color = PiTheme.palette.text)
                Text(
                    tr("$key · 选择该模型新会话启动时的默认思考等级", "$key · Choose the default thinking level for new sessions with this model"),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.dim,
                )
            }
            error?.let {
                Text(
                    it,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.error,
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small, vertical = 4.dp),
                )
            }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                item(key = "follow-default") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 4.dp)
                            .clickable(enabled = !busy) {
                                val h = host() ?: run { error = tr("引擎没在运行。", "Engine is not running."); return@clickable }
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
                        shape = PiShapes.card,
                        color = if (currentLvl == null) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            if (currentLvl == null) 1.dp else 0.5.dp,
                            if (currentLvl == null) PiTheme.palette.accent else PiTheme.palette.borderMuted.copy(alpha = 0.55f),
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                (if (currentLvl == null) "✓ " else "") + tr("跟随全局默认（${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel}）", "Follow Global Default (${officialThinkingMeta(defaultLevel)?.first ?: defaultLevel})"),
                                style = PiTheme.text.prose,
                                color = if (currentLvl == null) PiTheme.palette.accent else PiTheme.palette.text,
                            )
                            Text(tr("不为此模型单独覆盖，使用全局默认思考等级。", "Do not override for this model; use the global default thinking level."), style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                    }
                }
                items(levels, key = { it }) { lvl ->
                    val meta = officialThinkingMeta(lvl)
                    val activeLevel = currentLvl == lvl
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 4.dp)
                            .clickable(enabled = !busy) {
                                val h = host() ?: run { error = tr("引擎没在运行。", "Engine is not running."); return@clickable }
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
                        shape = PiShapes.card,
                        color = if (activeLevel) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            if (activeLevel) 1.dp else 0.5.dp,
                            if (activeLevel) PiTheme.palette.accent else PiTheme.palette.borderMuted.copy(alpha = 0.55f),
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                (if (activeLevel) "✓ " else "") + (meta?.first ?: lvl) + " ($lvl)",
                                style = PiTheme.text.prose,
                                color = if (activeLevel) PiTheme.palette.accent else PiTheme.palette.text,
                            )
                            meta?.second?.let { desc ->
                                Text(desc, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryAction(text = tr("← 返回模型列表", "← Back to Models"), enabled = !busy, onClick = { selectedModel = null; error = null })
            }
        }
    }
}
