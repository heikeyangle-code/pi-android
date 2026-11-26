package app.pi.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import app.pi.ui.components.PiSpinner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.HostAdvanced
import app.pi.host.HostAuth
import app.pi.i18n.tr
import app.pi.models.ProviderForm
import app.pi.protocol.PiResponses
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.settings.G_MODELS_SCREEN
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiInlineSegmentRow
import app.pi.ui.settings.PiSetting
import app.pi.ui.settings.PiSettingEditorSheet
import app.pi.ui.settings.PiSettingsCatalog
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsEntryRow
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsStore
import app.pi.ui.settings.PiSettingsToggleRow
import app.pi.ui.settings.boolIn
import app.pi.ui.settings.current
import app.pi.ui.settings.display
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.settings.textIn
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型、供应商与推理设置（100% 对齐 Pi 1.6.0 `ModelRuntime` 单一供应商注册表语义）：
 *  1. **当前会话模型与思考等级**：查看当前会话模型（能力标签/价格）、一键切换模型（只本次 / 设为默认），并直接切换思考深度；
 *  2. **统一供应商列表**：不再人为拆分成「订阅登录 / 已连接 API / 其他 API / 自定义供应商」多张割裂卡片，
 *     而是与 SDK `ModelRuntime.getProviders()` + `models.json` 一致整合为单一供应商列表（当前使用与已连接置顶，
 *     支持全部 / 已连接 / 账号登录 / 自定义筛选，行内清晰展示订阅、网页 OAuth、API Key、自定义端点与覆盖标识，
 *     底部无缝集成添加自定义端点与导入 `models.json`）；
 *  3. **推理、作用域与缓存**：作用域与循环模型、全局与逐模型思考等级、思考预算及缓存预热；
 *  4. **顶栏一键刷新目录**：按需向各供应商拉取最新模型目录并提示结果。
 */
@Composable
fun ModelsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    host: () -> ModelsHost?,
    store: PiSettingsStore,
    freshness: Int = 0,
    onSettingWritten: (String) -> Unit = {},
    onExternalSettingsWrite: () -> Unit = {},
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
    initialProviderQuery: String? = null,
    initialOpenScoped: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<Data?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember(initialProviderQuery) { mutableStateOf(initialProviderQuery.orEmpty()) }
    var filter by remember { mutableStateOf(ProviderFilter.All) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf<RefreshSummary?>(null) }
    var showAllProviders by remember { mutableStateOf(false) }
    var openProvider by remember { mutableStateOf<String?>(null) }
    var initialHandled by remember(initialProviderQuery, initialOpenScoped) { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<FormRequest?>(null) }
    var editingSetting by remember { mutableStateOf<PiSetting?>(null) }
    var pickingScoped by remember { mutableStateOf(false) }
    var pickingModelThinking by remember { mutableStateOf(false) }
    var modelsJsonOpen by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        val h = host()
        if (h == null) {
            data = null
            loaded = true
            return@LaunchedEffect
        }
        val next = coroutineScope {
            val providersDef = async { runCatching { h.auth.providers() } }
            val modelsDef = async { runCatching { h.models() }.getOrDefault(emptyList()) }
            val customDef = async { runCatching { h.advanced.modelsConfig() }.getOrNull() }
            val levelsDef = async { runCatching { h.thinkingLevels() }.getOrDefault(emptyList()) }
            val scopedDef = async { runCatching { h.advanced.scopedModels() }.getOrDefault(emptyList()) }
            val cacheDef = async { runCatching { h.advanced.cacheStatus() }.getOrNull() }
            val providers = providersDef.await()
            providers.exceptionOrNull()?.let { notice = tr("读取供应商失败：${it.message}", "Failed to load providers: ${it.message}") }
            Data(
                providers = providers.getOrDefault(emptyList()),
                models = modelsDef.await(),
                custom = customDef.await(),
                current = h.currentModel(),
                thinkingLevel = h.currentThinking(),
                thinkingLevels = levelsDef.await(),
                scoped = scopedDef.await(),
                cache = cacheDef.await(),
            )
        }
        data = next
        loaded = true
        if (!initialHandled) {
            initialHandled = true
            if (initialOpenScoped) {
                onLoadAvailableModels()
                pickingScoped = true
            } else if (!initialProviderQuery.isNullOrBlank()) {
                val target = initialProviderQuery.trim()
                val exact = next.providers.firstOrNull {
                    it.id.equals(target, ignoreCase = true) || it.name.equals(target, ignoreCase = true)
                }
                if (exact != null) openProvider = exact.id
            }
        }
    }

    val modelSettings = remember { PiSettingsCatalog.settingsIn(G_MODELS_SCREEN) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }

    fun triggerCatalogRefresh() {
        if (busy) return
        val h = host() ?: run {
            notice = tr("引擎没在运行：刷新没有执行。", "Engine is not running: refresh did not run.")
            return
        }
        scope.launch {
            busy = true
            runCatching { h.auth.refreshModels() }
                .onSuccess { refresh = refreshSummary(it); onLoadAvailableModels() }
                .onFailure { notice = tr("刷新失败：${it.message}", "Refresh failed: ${it.message}") }
            busy = false
            reload++
        }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("模型与供应商", "Models & Providers"),
            onBack = onBack,
            scrolled = scrolled,
            actions = {
                if (data != null) {
                    if (busy) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            PiSpinner(
                                size = 18.dp,
                                strokeWidth = 1.8.dp,
                                color = PiTheme.palette.accent,
                            )
                        }
                    } else {
                        PiTopBarIcon(
                            onClick = ::triggerCatalogRefresh,
                            contentDescription = tr("刷新模型目录", "Refresh model catalog"),
                            icon = Icons.Filled.Refresh,
                        )
                    }
                }
            },
        )
        val d = data
        if (d == null) {
            if (loaded) {
                PiInfoNote(tr("引擎没有在运行，这一页读不到模型与供应商。启动后再来。", "Engine is not running, so models and providers cannot be loaded. Start the engine and try again."))
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    PiSpinner(
                        size = 26.dp,
                        strokeWidth = 2.2.dp,
                        color = PiTheme.palette.accent,
                    )
                }
            }
            return@Column
        }

        val customProviders = d.custom?.providers.orEmpty()
        val customIds = customProviders.keys
        val builtinIds = remember(d.providers) { d.providers.filter { it.builtin }.map { it.id }.toSet() }
        val facts = d.facts(customProviders)
        val names = remember(d.providers, facts) { facts.associate { it.id to it.title } }
        val unified = buildUnifiedProviders(
            all = facts,
            query = query,
            filter = filter,
            expanded = showAllProviders,
        )
        val current = d.currentFacts()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = PiSpacing.small * 2, bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
        ) {
            notice?.let { n ->
                item(key = "notice") {
                    ModelsCard(onClick = { notice = null }) {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n, style = PiTheme.text.prose, color = PiTheme.palette.warning)
                            Text(tr("点一下关掉", "Tap to dismiss"), style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                    }
                }
            }

            refresh?.let { r ->
                item(key = "refresh-banner") {
                    ModelsCard(onClick = { refresh = null }) {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                r.sentence(names),
                                style = PiTheme.text.prose,
                                color = if (r.ok) PiTheme.palette.success else PiTheme.palette.warning,
                            )
                            r.detail(names).forEach {
                                Text(it, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                            }
                            Text(tr("点一下关掉", "Tap to dismiss"), style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                    }
                }
            }

            // ---- 1. 当前模型与即时思考等级 ----
            item(key = "current") {
                val palette = PiTheme.palette
                ModelsCard(accent = current != null) {
                    Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(palette.accent.copy(alpha = if (current != null) 0.16f else 0.08f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (current != null) palette.accent else palette.muted,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (current != null) tr("当前会话模型", "Current Session Model") else tr("尚未选择模型", "No Model Selected"),
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (current != null) palette.accent else palette.muted,
                                )
                                if (current != null) {
                                    Text(
                                        current.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = palette.text,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    Text(
                                        tr("在下方连接任意供应商后即可选择模型", "Connect any provider below to select a model"),
                                        style = PiTheme.text.meta,
                                        color = palette.dim,
                                    )
                                }
                            }
                        }
                        if (current != null) {
                            val providerDisplay = names[current.provider]?.ifBlank { null } ?: current.provider
                            TagRow(
                                listOf(providerDisplay) +
                                    capabilityTags(current) +
                                    listOfNotNull(priceLabel(current.inputCost, current.outputCost)?.let { "$it /M" }),
                            )
                        }
                        if (d.thinkingLevels.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tr("思考深度", "Thinking Level"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.muted)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                                ) {
                                    d.thinkingLevels.forEach { level ->
                                        PiSettingsChip(
                                            text = thinkingLabel(level),
                                            active = d.thinkingLevel == level,
                                            onClick = {
                                                val h = host() ?: return@PiSettingsChip
                                                scope.launch {
                                                    runCatching { h.setThinking(level) }
                                                        .onSuccess { reload++ }
                                                        .onFailure { notice = tr("切换思考等级失败：${it.message}", "Failed to switch thinking level: ${it.message}") }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (d.models.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PrimaryAction(
                                    text = if (current == null) {
                                        tr("选择模型（${d.models.size} 可用）", "Select Model (${d.models.size} available)")
                                    } else {
                                        tr("更换模型（${d.models.size} 可用）", "Switch Model (${d.models.size} available)")
                                    },
                                    onClick = { picker = true },
                                )
                            }
                        }
                    }
                }
            }

            // ---- 2. 统一供应商（订阅账号 + OAuth 网页授权 + API Key + 云凭证 + 自定义 models.json 整合为一处） ----
            item(key = "providers-header") {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ModelsSectionLabel(
                        text = if (unified.counts.connected > 0) {
                            tr(
                                "供应商（已连接 ${unified.counts.connected} / ${unified.counts.all}）",
                                "Providers (${unified.counts.connected}/${unified.counts.all} connected)",
                            )
                        } else {
                            tr(
                                "供应商（${unified.counts.all}）",
                                "Providers (${unified.counts.all})",
                            )
                        },
                        trailing = {
                            PiSettingsChip(
                                text = tr("+ 自定义", "+ Custom"),
                                onClick = { form = FormRequest(ProviderForm(api = "openai-completions"), isNew = true) },
                            )
                        },
                    )
                    ModelsSearchBar(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = tr("搜索供应商（如 Anthropic、ChatGPT、OpenRouter、DeepSeek…）", "Search providers (Anthropic, ChatGPT, OpenRouter, DeepSeek…)"),
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProviderFilter.entries.forEach { f ->
                            val count = unified.counts.countOf(f)
                            if (f == ProviderFilter.All || count > 0 || filter == f) {
                                PiSettingsChip(
                                    text = "$count".let { "${f.label} ($it)" },
                                    active = filter == f,
                                    onClick = { filter = f },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "providers-card") {
                val palette = PiTheme.palette
                val canCollapse = filter == ProviderFilter.All && query.isBlank() &&
                    (unified.hiddenCount > 0 || (showAllProviders && unified.totalMatched > buildUnifiedProviders(facts, "", ProviderFilter.All, expanded = false).visible.size))

                ModelsCard {
                    Column {
                        d.custom?.error?.let { e ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(palette.error.copy(alpha = 0.08f))
                                    .clickable { modelsJsonOpen = true }
                                    .padding(ModelsCardPadding),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    tr("models.json 格式有误（点此修复）", "Invalid models.json (tap to repair)"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.error,
                                )
                                Text(e, style = PiTheme.text.meta, color = palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            ModelsDivider()
                        }

                        if (unified.visible.isEmpty()) {
                            Column(
                                Modifier.padding(ModelsCardPadding),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    tr("没有匹配的供应商", "No matching providers"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                    color = palette.text,
                                )
                                Text(
                                    when (filter) {
                                        ProviderFilter.Connected -> tr("当前还没有已连接的供应商。切回「全部」选择任意供应商登录或填入 API Key。", "No providers connected yet. Switch to \"All\" to sign in or paste an API key.")
                                        ProviderFilter.Custom -> tr("还没有自定义供应商。点下方「添加自定义供应商」或「导入 models.json」。", "No custom providers yet. Add one or import models.json below.")
                                        else -> tr("换个关键词，或在下方添加 OpenAI / Anthropic 兼容端点。", "Try another keyword, or add an OpenAI/Anthropic-compatible endpoint below.")
                                    },
                                    style = PiTheme.text.meta,
                                    color = palette.dim,
                                )
                            }
                        } else {
                            unified.visible.forEachIndexed { index, p ->
                                if (index > 0) ModelsDivider()
                                ProviderRow(
                                    facts = p,
                                    onClick = {
                                        if (p.customUnregistered) {
                                            val rawJson = customProviders[p.id]
                                            if (rawJson != null) {
                                                form = FormRequest(ProviderForm.from(p.id, rawJson), isNew = false)
                                            }
                                        } else {
                                            openProvider = p.id
                                        }
                                    },
                                )
                            }
                        }

                        if (canCollapse) {
                            ModelsDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAllProviders = !showAllProviders }
                                    .padding(horizontal = ModelsCardPadding, vertical = 11.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (showAllProviders) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = if (showAllProviders) {
                                        tr("收起更多未连接供应商", "Show fewer providers")
                                    } else {
                                        tr("展开其余 ${unified.hiddenCount} 个供应商", "Show ${unified.hiddenCount} more providers")
                                    },
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.accent,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }

                        ModelsDivider()
                        ActionRow(
                            title = tr("添加自定义供应商 / 代理端点", "Add Custom Provider / Proxy"),
                            detail = tr("接入任意 OpenAI / Anthropic 兼容接口，或覆盖内置供应商地址", "Connect any OpenAI/Anthropic-compatible endpoint or override a built-in URL"),
                            icon = Icons.Filled.Add,
                            onClick = { form = FormRequest(ProviderForm(api = "openai-completions"), isNew = true) },
                        )
                        ModelsDivider()
                        ActionRow(
                            title = tr("导入 / 编辑 models.json", "Import / Edit models.json"),
                            detail = tr("选择 .json 文件或粘贴官方 models.json 批量导入自定义端点与模型", "Pick a .json file or paste official models.json to bulk-import endpoints & models"),
                            icon = Icons.Filled.Code,
                            onClick = { modelsJsonOpen = true },
                        )
                    }
                }
            }

            // ---- 3. 推理、作用域与缓存（100% 对齐 Pi SDK） ----
            item(key = "l:reasoning") { ModelsSectionLabel(tr("推理、作用域与缓存", "Reasoning, Scoped Models & Caching"), Modifier.padding(top = 10.dp)) }
            item(key = "c:reasoning") {
                val settingByKey = remember(modelSettings) { modelSettings.associateBy { it.key } }
                val defaultThinkingSetting = settingByKey["defaultThinkingLevel"]
                val budgetsSetting = settingByKey["thinkingBudgets"]
                val cacheWarmingSetting = settingByKey["cacheWarming"]
                val noticeSettings = remember(settingByKey) {
                    listOfNotNull(
                        settingByKey["notices.cacheMisses"],
                        settingByKey["notices.cacheWarming"],
                        settingByKey["notices.compactionCosts"],
                        settingByKey["notices.reasoningDrops"],
                    )
                }
                val extraUsageWarningSetting = settingByKey["warnings.anthropicExtraUsage"]
                val cacheRetentionSetting = settingByKey["app.runtime.cacheRetention"]
                var noticesExpanded by rememberSaveable { mutableStateOf(false) }

                val persistedEnabledModels = remember(store, freshness, reload) {
                    (store.read("enabledModels") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        .orEmpty()
                }
                val perModelOverrides = remember(store, freshness, reload) {
                    (store.read("modelThinkingLevels") as? kotlinx.serialization.json.JsonObject)
                        ?.mapNotNull { (k, v) ->
                            val lvl = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                            if (lvl != null) k to lvl else null
                        }
                        ?.toMap()
                        .orEmpty()
                }

                ModelsCard {
                    Column {
                        val scopedSummary = when {
                            d.scoped.isNotEmpty() -> tr("当前会话 ${d.scoped.size} 个模型", "${d.scoped.size} in current session") +
                                if (persistedEnabledModels.isNotEmpty()) tr(" · 默认规则 ${persistedEnabledModels.size} 项", " · ${persistedEnabledModels.size} default rules") else ""
                            persistedEnabledModels.isNotEmpty() -> tr("默认规则 ${persistedEnabledModels.size} 项（新会话生效）", "${persistedEnabledModels.size} default rules (for new sessions)")
                            else -> tr("未限制（循环全部可用模型）", "Unrestricted (cycles all available models)")
                        }
                        PiSettingsEntryRow(
                            title = tr("作用域与循环模型", "Scoped & Cycling Models"),
                            supporting = scopedSummary,
                            onClick = { onLoadAvailableModels(); pickingScoped = true },
                        )

                        defaultThinkingSetting?.let { setting ->
                            ModelsDivider()
                            val summary = remember(store, setting.key, freshness, reload) {
                                setting.display(setting.current(store))
                            }
                            PiSettingsEntryRow(
                                title = setting.title,
                                supporting = "$summary · ${setting.description}",
                                onClick = { editingSetting = setting },
                            )
                        }

                        ModelsDivider()
                        PiSettingsEntryRow(
                            title = tr("逐模型思考等级", "Per-Model Thinking Level"),
                            supporting = if (perModelOverrides.isEmpty()) {
                                tr("未覆盖 · 按模型真实支持的档位覆盖新会话默认等级", "No overrides · Set per-model default thinking level for new sessions")
                            } else {
                                tr("已单独覆盖 ${perModelOverrides.size} 个模型 · 点击管理", "${perModelOverrides.size} models overridden · Tap to manage")
                            },
                            onClick = { onLoadAvailableModels(); pickingModelThinking = true },
                        )

                        budgetsSetting?.let { setting ->
                            ModelsDivider()
                            val summary = remember(store, setting.key, freshness, reload) {
                                setting.display(setting.current(store))
                            }
                            PiSettingsEntryRow(
                                title = setting.title,
                                supporting = "$summary · ${setting.description}",
                                onClick = { editingSetting = setting },
                            )
                        }

                        if (d.cache != null) {
                            ModelsDivider()
                            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tr("实时缓存预热", "Prompt Cache Warming"), style = PiTheme.text.prose, color = PiTheme.palette.text)
                                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                                    for ((mode, label) in listOf(
                                        "off" to tr("关", "Off"),
                                        "streaming" to tr("流式时", "Streaming"),
                                        "idle" to tr("空闲时", "Idle"),
                                    )) {
                                        PiSettingsChip(
                                            text = label,
                                            active = d.cache.mode == mode,
                                            onClick = {
                                                val h = host() ?: return@PiSettingsChip
                                                scope.launch {
                                                    runCatching { h.advanced.setCacheMode(mode) }
                                                        .onSuccess {
                                                            onExternalSettingsWrite()
                                                            reload++
                                                        }
                                                        .onFailure { notice = tr("设置缓存预热失败：${it.message}", "Failed to set cache warming: ${it.message}") }
                                                }
                                            },
                                        )
                                    }
                                }
                                val statusLine = listOfNotNull(
                                    d.cache.state?.let { tr("状态：$it", "State: $it") },
                                    d.cache.reason,
                                    d.cache.nextWarmAt?.let { tr("下次预热 $it", "Next warm $it") },
                                ).joinToString(" · ")
                                if (statusLine.isNotEmpty()) {
                                    Text(statusLine, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                                }
                            }
                        } else if (cacheWarmingSetting != null) {
                            ModelsDivider()
                            val mode = remember(store, cacheWarmingSetting.key, freshness, reload) {
                                cacheWarmingSetting.textIn(store, "idle")
                            }
                            PiInlineSegmentRow(
                                title = cacheWarmingSetting.title,
                                description = cacheWarmingSetting.description,
                                options = listOf(
                                    "off" to tr("关闭", "Off"),
                                    "streaming" to tr("仅流式", "Streaming"),
                                    "idle" to tr("含空闲", "Idle"),
                                ),
                                selected = mode,
                                onSelect = { next ->
                                    store.write(cacheWarmingSetting.key, JsonPrimitive(next))
                                    onSettingWritten(cacheWarmingSetting.key)
                                    reload++
                                },
                            )
                        }

                        if (noticeSettings.isNotEmpty()) {
                            ModelsDivider()
                            val legacyNoticesDefault = remember(store, freshness, reload) {
                                (store.read("showCacheMissNotices") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                            }
                            val enabledCount = remember(store, noticeSettings, legacyNoticesDefault, freshness, reload) {
                                noticeSettings.count { it.boolIn(store, legacyNoticesDefault) }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { noticesExpanded = !noticesExpanded }
                                    .padding(horizontal = ModelsCardPadding, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        tr("对话诊断提示", "Transcript Notices"),
                                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                        color = PiTheme.palette.text,
                                    )
                                    Text(
                                        tr(
                                            "已开启 $enabledCount / ${noticeSettings.size} 项（缓存未命中、预热、压缩计费、推理丢弃）",
                                            "$enabledCount of ${noticeSettings.size} enabled (cache misses, warming, compaction, reasoning)",
                                        ),
                                        style = PiTheme.text.meta,
                                        color = PiTheme.palette.muted,
                                    )
                                }
                                Icon(
                                    imageVector = if (noticesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = PiTheme.palette.muted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            if (noticesExpanded) {
                                noticeSettings.forEach { setting ->
                                    ModelsDivider()
                                    val checked = remember(store, setting.key, legacyNoticesDefault, freshness, reload) {
                                        setting.boolIn(store, legacyNoticesDefault)
                                    }
                                    PiSettingsToggleRow(
                                        title = setting.title,
                                        supporting = setting.description,
                                        checked = checked,
                                        onCheckedChange = { on ->
                                            store.write(setting.key, JsonPrimitive(on))
                                            onSettingWritten(setting.key)
                                            reload++
                                        },
                                    )
                                }
                            }
                        }

                        extraUsageWarningSetting?.let { setting ->
                            ModelsDivider()
                            val checked = remember(store, setting.key, freshness, reload) { setting.boolIn(store, true) }
                            PiSettingsToggleRow(
                                title = setting.title,
                                supporting = setting.description,
                                checked = checked,
                                onCheckedChange = { on ->
                                    store.write(setting.key, JsonPrimitive(on))
                                    onSettingWritten(setting.key)
                                    reload++
                                },
                            )
                        }

                        cacheRetentionSetting?.let { setting ->
                            ModelsDivider()
                            val retention = remember(store, setting.key, freshness, reload) {
                                setting.textIn(store, "short")
                            }
                            PiInlineSegmentRow(
                                title = setting.title,
                                description = setting.description,
                                options = listOf(
                                    "short" to tr("短期（~5 分钟）", "Short (~5 min)"),
                                    "long" to tr("长期（~1 小时）", "Long (~1 hr)"),
                                ),
                                selected = retention,
                                effective = setting.effective,
                                onExplainEffect = {
                                    notice = tr("提示缓存保留时长已保存，重启引擎后生效。", "Prompt cache retention saved; takes effect after restarting the engine.")
                                },
                                onSelect = { next ->
                                    store.write(setting.key, JsonPrimitive(next))
                                    onSettingWritten(setting.key)
                                    reload++
                                },
                            )
                        }
                    }
                }
            }
        }

        openProvider?.let { id ->
            val p = d.providers.firstOrNull { it.id == id }
            if (p == null) openProvider = null else ProviderSheet(
                host = host,
                provider = p,
                facts = facts.first { it.id == id },
                available = d.models.filter { it.provider == p.id },
                current = d.current,
                custom = customProviders[p.id],
                onChanged = { reload++; onLoadAvailableModels() },
                onDismiss = { openProvider = null; reload++ },
                onEditCustom = { json -> openProvider = null; form = FormRequest(ProviderForm.from(p.id, json), isNew = false) },
                onOverrideBuiltin = {
                    openProvider = null
                    form = FormRequest(ProviderForm(id = p.id, name = ""), isNew = true)
                },
            )
        }

        if (picker) {
            ModelPickerSheet(
                host = host,
                models = d.models.map { it.facts() },
                providerNames = names,
                current = d.current,
                onDismiss = { picker = false },
                onSwitched = { msg -> notice = msg; reload++ },
            )
        }

        form?.let { req ->
            ProviderFormSheet(
                initial = req.form,
                isNew = req.isNew,
                customIds = customIds,
                knownIds = builtinIds,
                host = host,
                onDismiss = { form = null },
                onDone = { form = null; reload++; onLoadAvailableModels() },
            )
        }

        if (modelsJsonOpen) {
            ModelsJsonImportSheet(
                host = host,
                existingProviders = customProviders,
                corruptError = d.custom?.error,
                onDismiss = { modelsJsonOpen = false },
                onSaved = { msg ->
                    notice = msg
                    reload++
                    onLoadAvailableModels()
                },
            )
        }

        editingSetting?.let { target ->
            PiSettingEditorSheet(
                setting = target,
                store = store,
                onDismiss = { editingSetting = null },
                onWritten = { written ->
                    onSettingWritten(written.key)
                    editingSetting = null
                    reload++
                },
            )
        }

        if (pickingScoped) {
            val pool = d.models.ifEmpty { availableModels }
            val persisted = (store.read("enabledModels") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .orEmpty()
            ScopedModelsSheet(
                host = host,
                models = pool,
                providerNames = names,
                initialScoped = d.scoped,
                persistedPatterns = persisted,
                onDismiss = { pickingScoped = false },
                onApplied = { msg ->
                    notice = msg
                    onExternalSettingsWrite()
                    reload++
                },
            )
        }

        if (pickingModelThinking) {
            val pool = d.models.ifEmpty { availableModels }
            val overrides = (store.read("modelThinkingLevels") as? kotlinx.serialization.json.JsonObject)
                ?.mapNotNull { (k, v) ->
                    val lvl = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (lvl != null) k to lvl else null
                }
                ?.toMap()
                .orEmpty()
            val defaultLvl = (store.read("defaultThinkingLevel") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "medium"
            ModelThinkingLevelsSheet(
                host = host,
                models = pool,
                providerNames = names,
                overrides = overrides,
                defaultLevel = defaultLvl,
                onDismiss = { pickingModelThinking = false },
                onChanged = { msg ->
                    notice = msg
                    onExternalSettingsWrite()
                    reload++
                },
            )
        }
    }
}

private fun thinkingLabel(level: String): String = app.pi.ui.chat.thinkingLabelOf(level)

private class FormRequest(val form: ProviderForm, val isNew: Boolean)

private class Data(
    val providers: List<HostAuth.Provider>,
    val models: List<PiResponses.ModelInfo>,
    val custom: HostAdvanced.ModelsConfig?,
    val current: Pair<String, String>?,
    val thinkingLevel: String,
    val thinkingLevels: List<String>,
    val scoped: List<HostAdvanced.ModelRef>,
    val cache: HostAdvanced.CacheStatus?,
) {
    fun facts(customMap: Map<String, JsonObject>): List<ProviderFacts> {
        val activeProviderId = current?.first
        val runtimeIds = HashSet<String>(providers.size)
        val fromRuntime = providers.map { p ->
            runtimeIds += p.id
            val customJson = customMap[p.id]
            val isCustom = customJson != null || !p.builtin
            ProviderFacts(
                id = p.id,
                name = p.name,
                configured = p.configured,
                usingOAuth = p.usingOAuth,
                usingSubscription = p.usingSubscription,
                source = p.statusSource,
                offersSubscription = p.isSubscription,
                modelCount = models.count { it.provider == p.id },
                custom = isCustom,
                offersWebLogin = p.isWebLogin,
                offersApiKey = p.supportsApiKeyInput,
                hasAmbientAuth = p.hasAmbientApiKey,
                overridesBuiltin = customJson != null && p.builtin,
                customBaseUrl = (customJson?.get("baseUrl") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                isActiveProvider = activeProviderId != null && activeProviderId == p.id,
            )
        }
        val unregisteredCustom = customMap.entries
            .filter { (id, _) -> id !in runtimeIds }
            .map { (id, json) ->
                val customName = (json["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                val baseUrl = (json["baseUrl"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ProviderFacts(
                    id = id,
                    name = customName ?: id,
                    configured = false,
                    usingOAuth = false,
                    usingSubscription = false,
                    source = null,
                    offersSubscription = false,
                    modelCount = 0,
                    custom = true,
                    overridesBuiltin = false,
                    customBaseUrl = baseUrl,
                    isActiveProvider = activeProviderId == id,
                    customUnregistered = true,
                )
            }
        return fromRuntime + unregisteredCustom
    }

    fun currentFacts(): ModelFacts? = current?.let { (p, id) ->
        models.firstOrNull { it.provider == p && it.id == id }?.facts()
            ?: ModelFacts(p, id, id, false, false, null, null, null)
    }
}

internal fun PiResponses.ModelInfo.facts() = ModelFacts(
    provider = provider.orEmpty(),
    id = id,
    name = name,
    reasoning = reasoning,
    images = acceptsImages,
    contextWindow = contextWindow,
    inputCost = inputCost,
    outputCost = outputCost,
)

@Composable
private fun ActionRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
) {
    val tint = if (enabled) PiTheme.palette.accent else PiTheme.palette.muted
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium), color = tint)
            Text(detail, style = PiTheme.text.meta, color = PiTheme.palette.dim)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PiTheme.palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}
