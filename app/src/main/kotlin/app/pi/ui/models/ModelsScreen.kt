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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.HostAdvanced
import app.pi.host.HostAuth
import app.pi.models.ProviderForm
import app.pi.rpc.PiResponses
import app.pi.ui.PiTopBar
import app.pi.ui.settings.G_MODELS_SCREEN
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiRowKind
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
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型、订阅与推理设置（SDK 统一入口）。
 *
 * 取代了旧版独立的「模型与推理」设置分组页与冗余的「引擎」页模型段，将所有模型相关能力统一收拢：
 *  1. **当前模型与思考等级**：查看当前会话模型（能力标签/价格）、一键切换模型（只本次 / 设为默认），并直接切换当前思考等级；
 *  2. **订阅账号登录（统一置顶）**：所有支持订阅登录的供应商集中展示（已连接在前、未连接在后），登录前后不跳段、不重复；
 *  3. **已连接 / 其他 API 供应商**：按连接状态清晰分区；
 *  4. **自定义供应商（`models.json`）**：增改删与内置覆盖，由 SDK 校验落盘；
 *  5. **推理、作用域与缓存**：当前会话作用域模型、实时缓存预热状态与模式切换，以及 `settings.json` 中的推理/预算/循环模型规则；
 *  6. **模型目录刷新**：按需向各供应商拉取最新目录并报告失败明细。
 */
@Composable
fun ModelsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    host: () -> ModelsHost?,
    store: PiSettingsStore,
    freshness: Int = 0,
    onSettingWritten: (String) -> Unit = {},
    availableModels: List<PiResponses.ModelInfo> = emptyList(),
    onLoadAvailableModels: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<Data?>(null) }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf<RefreshSummary?>(null) }
    var showOthers by remember { mutableStateOf(false) }
    var openProvider by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<FormRequest?>(null) }
    var editingSetting by remember { mutableStateOf<PiSetting?>(null) }
    var pickingScoped by remember { mutableStateOf(false) }
    var pickingModelThinking by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        val h = host()
        if (h == null) {
            data = null
            return@LaunchedEffect
        }
        val providers = runCatching { h.auth.providers() }
        providers.exceptionOrNull()?.let { notice = "读取供应商失败：${it.message}" }
        data = Data(
            providers = providers.getOrDefault(emptyList()),
            models = runCatching { h.models() }.getOrDefault(emptyList()),
            custom = runCatching { h.advanced.modelsConfig() }.getOrNull(),
            current = h.currentModel(),
            thinkingLevel = h.currentThinking(),
            thinkingLevels = runCatching { h.thinkingLevels() }.getOrDefault(emptyList()),
            scoped = runCatching { h.advanced.scopedModels() }.getOrDefault(emptyList()),
            cache = runCatching { h.advanced.cacheStatus() }.getOrNull(),
        )
    }

    val modelSettings = remember { PiSettingsCatalog.settingsIn(G_MODELS_SCREEN) }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "模型与供应商", onBack = onBack)
        val d = data
        if (d == null) {
            PiInfoNote("引擎没有在运行，这一页读不到模型与供应商。启动后再来。")
            return@Column
        }

        val customIds = d.custom?.providers?.keys.orEmpty()
        val facts = d.facts(customIds)
        val names = d.providers.associate { it.id to it.name }
        val sections = sectionProviders(facts, query)
        val searching = query.isNotBlank()
        val current = d.currentFacts()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = PiSpacing.small * 2, bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            notice?.let { n ->
                item(key = "notice") {
                    ModelsCard(onClick = { notice = null }) {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n, style = PiTheme.text.prose, color = PiTheme.palette.warning)
                            Text("点一下关掉", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                    }
                }
            }

            // ---- 当前模型与即时思考等级 ----
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
                                    if (current != null) "当前会话模型" else "尚未选择模型",
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
                                        "连接供应商后即可选择模型开始对话",
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
                                Text("思考深度", style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.muted)
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
                                                        .onFailure { notice = "切换思考等级失败：${it.message}" }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PrimaryAction(
                                text = when {
                                    d.models.isEmpty() -> "先连接供应商"
                                    current == null -> "选择模型（${d.models.size} 可用）"
                                    else -> "更换模型（${d.models.size} 可用）"
                                },
                                enabled = d.models.isNotEmpty(),
                                onClick = { picker = true },
                            )
                        }
                    }
                }
            }

            item(key = "search") {
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    placeholder = "搜索供应商",
                    height = PiSettingsMetrics.searchFieldHeight,
                )
            }

            // ---- 订阅账号登录：所有支持订阅的供应商统一置顶（已连接在前、未连接在后） ----
            if (sections.subscriptionProviders.isNotEmpty()) {
                val connectedSubCount = sections.subscriptionProviders.count { it.configured }
                item(key = "l:sub") {
                    ModelsSectionLabel(
                        if (connectedSubCount > 0) {
                            "订阅账号（$connectedSubCount/${sections.subscriptionProviders.size} 已连接）"
                        } else {
                            "订阅账号登录（${sections.subscriptionProviders.size}）"
                        },
                    )
                }
                item(key = "c:sub") { ProviderCard(sections.subscriptionProviders) { openProvider = it } }
            }

            // ---- 已连接的 API / 网页登录供应商 ----
            if (sections.connectedApi.isNotEmpty()) {
                item(key = "l:conn") { ModelsSectionLabel("已连接 API（${sections.connectedApi.size}）") }
                item(key = "c:conn") { ProviderCard(sections.connectedApi) { openProvider = it } }
            }

            // ---- 其余未连接的 API 供应商 ----
            if (sections.otherApi.isNotEmpty()) {
                val open = showOthers || searching
                item(key = "l:other") {
                    ModelsSectionLabel(
                        "其他 API 供应商（${sections.otherApi.size}）",
                        trailing = if (searching) null else ({
                            PiSettingsChip(text = if (open) "收起" else "浏览全部", onClick = { showOthers = !showOthers })
                        }),
                    )
                }
                if (open) item(key = "c:other") { ProviderCard(sections.otherApi) { openProvider = it } }
            }

            if (sections.subscriptionProviders.isEmpty() && sections.connectedApi.isEmpty() && sections.otherApi.isEmpty()) {
                item(key = "none") {
                    ModelsCard {
                        Column(Modifier.padding(ModelsCardPadding)) {
                            Text("没有匹配的供应商", style = PiTheme.text.prose, color = PiTheme.palette.text)
                            Text("换个词，或在下面添加自定义供应商。", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                    }
                }
            }

            // ---- 自定义（models.json） ----
            item(key = "l:custom") { ModelsSectionLabel("自定义供应商") }
            item(key = "c:custom") {
                ModelsCard {
                    Column {
                        val custom = d.custom?.providers.orEmpty()
                        custom.entries.forEach { (id, json) ->
                            val builtin = d.providers.any { it.id == id }
                            CustomRow(
                                id = id,
                                name = names[id]?.takeIf { it.isNotBlank() && it != id },
                                detail = (json["baseUrl"] as? JsonPrimitive)?.content,
                                overrides = builtin,
                                onClick = { form = FormRequest(ProviderForm.from(id, json), isNew = false) },
                            )
                            ModelsDivider()
                        }
                        ActionRow(
                            title = "添加自定义供应商",
                            detail = "OpenAI 兼容、Anthropic 兼容等任意端点，也可以只改某个内置供应商的地址",
                            onClick = { form = FormRequest(ProviderForm(api = "openai-completions"), isNew = true) },
                        )
                        d.custom?.error?.let { e ->
                            ModelsDivider()
                            Column(Modifier.padding(ModelsCardPadding)) {
                                Text("models.json 有问题", style = PiTheme.text.prose, color = PiTheme.palette.error)
                                Text(e, style = PiTheme.text.meta, color = PiTheme.palette.muted)
                            }
                        }
                    }
                }
            }

            // ---- 推理、作用域与缓存（100% 对齐 Pi SDK，不再混用旧版纯文本编辑器或重复行） ----
            item(key = "l:reasoning") { ModelsSectionLabel("推理、作用域与缓存") }
            item(key = "c:reasoning") {
                val settingByKey = remember(modelSettings) { modelSettings.associateBy { it.key } }
                val defaultThinkingSetting = settingByKey["defaultThinkingLevel"]
                val budgetsSetting = settingByKey["thinkingBudgets"]
                val cacheWarmingSetting = settingByKey["cacheWarming"]
                val cacheMissSetting = settingByKey["showCacheMissNotices"]

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
                        // 1. 统一「作用域与循环模型」（合并原重复的「当前会话作用域模型」+「循环模型」纯文本项）
                        val scopedSummary = when {
                            d.scoped.isNotEmpty() -> "当前会话 ${d.scoped.size} 个模型" +
                                if (persistedEnabledModels.isNotEmpty()) " · 默认规则 ${persistedEnabledModels.size} 项" else ""
                            persistedEnabledModels.isNotEmpty() -> "默认规则 ${persistedEnabledModels.size} 项（新会话生效）"
                            else -> "未限制（循环全部可用模型）"
                        }
                        PiSettingsEntryRow(
                            title = "作用域与循环模型",
                            supporting = scopedSummary,
                            onClick = { onLoadAvailableModels(); pickingScoped = true },
                        )

                        // 2. 全局默认思考等级（新会话默认）
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

                        // 3. 逐模型思考等级（官方两步选择器：真实模型列表 → 该模型真实支持的思考等级，直走 SDK setter）
                        ModelsDivider()
                        PiSettingsEntryRow(
                            title = "逐模型思考等级",
                            supporting = if (perModelOverrides.isEmpty()) {
                                "未覆盖 · 按模型真实支持的档位覆盖新会话默认等级"
                            } else {
                                "已单独覆盖 ${perModelOverrides.size} 个模型 · 点击管理"
                            },
                            onClick = { onLoadAvailableModels(); pickingModelThinking = true },
                        )

                        // 4. 思考预算（结构化四档 token 预算编辑器）
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

                        // 5. 实时缓存预热（直走 SDK cache.setMode，无实时状态时退回设置选择器）
                        if (d.cache != null) {
                            ModelsDivider()
                            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("实时缓存预热", style = PiTheme.text.prose, color = PiTheme.palette.text)
                                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                                    for ((mode, label) in listOf("off" to "关", "streaming" to "流式时", "idle" to "空闲时")) {
                                        PiSettingsChip(
                                            text = label,
                                            active = d.cache.mode == mode,
                                            onClick = {
                                                val h = host() ?: return@PiSettingsChip
                                                scope.launch {
                                                    runCatching { h.advanced.setCacheMode(mode) }
                                                        .onSuccess {
                                                            store.write("cacheWarming", JsonPrimitive(mode))
                                                            onSettingWritten("cacheWarming")
                                                            reload++
                                                        }
                                                        .onFailure { notice = "设置缓存预热失败：${it.message}" }
                                                }
                                            },
                                        )
                                    }
                                }
                                val statusLine = listOfNotNull(
                                    d.cache.state?.let { "状态：$it" },
                                    d.cache.reason,
                                    d.cache.nextWarmAt?.let { "下次预热 $it" },
                                ).joinToString(" · ")
                                if (statusLine.isNotEmpty()) {
                                    Text(statusLine, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                                }
                            }
                        } else if (cacheWarmingSetting != null) {
                            ModelsDivider()
                            val summary = remember(store, cacheWarmingSetting.key, freshness, reload) {
                                cacheWarmingSetting.display(cacheWarmingSetting.current(store))
                            }
                            PiSettingsEntryRow(
                                title = cacheWarmingSetting.title,
                                supporting = "$summary · ${cacheWarmingSetting.description}",
                                onClick = { editingSetting = cacheWarmingSetting },
                            )
                        }

                        // 6. 缓存未命中提示（直走 SDK setShowCacheMissNotices）
                        cacheMissSetting?.let { setting ->
                            ModelsDivider()
                            val checked = remember(store, setting.key, freshness, reload) { setting.boolIn(store) }
                            PiSettingsToggleRow(
                                title = setting.title,
                                supporting = setting.description,
                                checked = checked,
                                onCheckedChange = { on ->
                                    val h = host()
                                    if (h != null) {
                                        scope.launch {
                                            runCatching { h.setShowCacheMissNotices(on) }
                                                .onSuccess {
                                                    onSettingWritten(setting.key)
                                                    reload++
                                                }
                                                .onFailure {
                                                    store.write(setting.key, JsonPrimitive(on))
                                                    onSettingWritten(setting.key)
                                                    reload++
                                                }
                                        }
                                    } else {
                                        store.write(setting.key, JsonPrimitive(on))
                                        onSettingWritten(setting.key)
                                        reload++
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ---- 目录 ----
            item(key = "l:ops") { ModelsSectionLabel("目录") }
            item(key = "c:ops") {
                ModelsCard {
                    Column {
                        ActionRow(
                            title = if (busy) "正在刷新…" else "刷新模型目录",
                            detail = "重新向各供应商拉取模型列表（需要网络）",
                            enabled = !busy,
                            onClick = {
                                val h = host() ?: run { notice = "引擎没在运行：刷新没有执行。"; return@ActionRow }
                                scope.launch {
                                    busy = true
                                    runCatching { h.auth.refreshModels() }
                                        .onSuccess { refresh = refreshSummary(it) }
                                        .onFailure { notice = "刷新失败：${it.message}" }
                                    busy = false
                                    reload++
                                }
                            },
                        )
                        refresh?.let { r ->
                            ModelsDivider()
                            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    r.sentence(names),
                                    style = PiTheme.text.meta,
                                    color = if (r.ok) PiTheme.palette.success else PiTheme.palette.warning,
                                )
                                r.detail(names).forEach {
                                    Text(it, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                                }
                            }
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
                custom = d.custom?.providers?.get(p.id),
                onChanged = { reload++ },
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
                knownIds = d.providers.map { it.id }.toSet(),
                host = host,
                onDismiss = { form = null },
                onDone = { form = null; reload++ },
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
                    onSettingWritten("enabledModels")
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
                    onSettingWritten("modelThinkingLevels")
                    reload++
                },
            )
        }
    }
}

private fun thinkingLabel(level: String): String = when (level) {
    "off" -> "关"
    "minimal" -> "极简"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    "max" -> "最高"
    else -> level
}

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
    fun facts(customIds: Set<String>): List<ProviderFacts> = providers.map { p ->
        ProviderFacts(
            id = p.id,
            name = p.name,
            configured = p.configured,
            usingOAuth = p.usingOAuth,
            usingSubscription = p.usingSubscription,
            source = p.statusSource,
            offersSubscription = p.isSubscription,
            modelCount = models.count { it.provider == p.id },
            custom = p.id in customIds,
            offersWebLogin = p.isWebLogin,
        )
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
private fun ProviderCard(list: List<ProviderFacts>, onOpen: (String) -> Unit) {
    ModelsCard {
        Column {
            list.forEachIndexed { i, p ->
                if (i > 0) ModelsDivider()
                ProviderRow(p, onClick = { onOpen(p.id) })
            }
        }
    }
}

@Composable
private fun CustomRow(id: String, name: String?, detail: String?, overrides: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderAvatar(id, name ?: id)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name ?: id, style = PiTheme.text.prose, color = PiTheme.palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (overrides) "覆盖内置" else null, detail).joinToString(" · ").ifEmpty { "编辑" },
                style = PiTheme.text.meta,
                color = PiTheme.palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PiTheme.palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ActionRow(title: String, detail: String, onClick: () -> Unit, enabled: Boolean = true) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = PiTheme.text.prose, color = if (enabled) PiTheme.palette.accent else PiTheme.palette.muted)
        Text(detail, style = PiTheme.text.meta, color = PiTheme.palette.dim)
    }
}
