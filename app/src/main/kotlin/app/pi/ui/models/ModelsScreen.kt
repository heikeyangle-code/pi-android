package app.pi.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.HostAdvanced
import app.pi.host.HostAuth
import app.pi.models.ProviderForm
import app.pi.rpc.PiResponses
import app.pi.ui.PiTopBar
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 模型与供应商（SDK 版）。
 *
 * 数据只有三个来源，都是宿主命令、背后是 SDK 的 `ModelRuntime`：
 *  - `auth.providers`：全部供应商（内置 + 扩展注册 + 自定义）及各自的认证方式与状态；
 *  - `model.list`：此刻可用的模型；
 *  - `models.config.*`：自定义供应商（`models.json`），保存时由 SDK 当校验器，非法回滚并带回原话。
 *
 * 页面只负责把 [ModelsPresentation] 算好的东西画出来：分段、排序、状态词、价格与能力标签、刷新结果
 * 那句话，都是那边的纯函数，各有 harness 钉着。写入立刻生效，目录由 SDK 刷新，没有「写完要重启」。
 */
@Composable
fun ModelsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    host: () -> ModelsHost?,
    /** 打开「默认模型与推理」（默认模型 / 默认思考等级 / 循环模型 / 思考预算……settings.json 里那组行）。 */
    onOpenDefaults: () -> Unit = {},
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
        )
    }

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

            // ---- 当前模型：整页最重的一块 ----
            item(key = "current") {
                ModelsCard(accent = current != null) {
                    Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前模型", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        if (current != null) {
                            Text(current.title, style = PiTheme.text.prose, color = PiTheme.palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(names[current.provider]?.ifBlank { null } ?: current.provider, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                            }
                            TagRow(capabilityTags(current) + listOfNotNull(priceLabel(current.inputCost, current.outputCost)?.let { "$it /M" }))
                        } else {
                            Text("还没有选择模型", style = PiTheme.text.prose, color = PiTheme.palette.text)
                            Text("先连接一个供应商，再选一个模型。", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PrimaryAction(if (current == null) "选择模型" else "更换模型", enabled = d.models.isNotEmpty(), onClick = { picker = true })
                            PiSettingsChip(text = "默认与推理", onClick = onOpenDefaults)
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

            if (sections.subscriptionOffers.isNotEmpty()) {
                item(key = "l:sub") { ModelsSectionLabel("用订阅账号登录") }
                item(key = "c:sub") { ProviderCard(sections.subscriptionOffers) { openProvider = it } }
            }
            if (sections.connected.isNotEmpty()) {
                item(key = "l:conn") { ModelsSectionLabel("已连接（${sections.connected.size}）") }
                item(key = "c:conn") { ProviderCard(sections.connected) { openProvider = it } }
            }
            if (sections.others.isNotEmpty()) {
                val open = showOthers || searching
                item(key = "l:other") {
                    ModelsSectionLabel(
                        "其他供应商（${sections.others.size}）",
                        trailing = if (searching) null else ({
                            PiSettingsChip(text = if (open) "收起" else "浏览全部", onClick = { showOthers = !showOthers })
                        }),
                    )
                }
                if (open) item(key = "c:other") { ProviderCard(sections.others) { openProvider = it } }
            }
            if (sections.connected.isEmpty() && sections.subscriptionOffers.isEmpty() && sections.others.isEmpty()) {
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
    }
}

private class FormRequest(val form: ProviderForm, val isNew: Boolean)

private class Data(
    val providers: List<HostAuth.Provider>,
    val models: List<PiResponses.ModelInfo>,
    val custom: HostAdvanced.ModelsConfig?,
    val current: Pair<String, String>?,
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
        Text("›", style = PiTheme.text.prose, color = PiTheme.palette.dim)
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
