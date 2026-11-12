package app.pi.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.pi.host.HostAdvanced
import app.pi.host.HostAuth
import app.pi.models.ProviderForm
import app.pi.rpc.PiResponses
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsCard
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsEntryRow
import app.pi.ui.settings.PiSettingsHairline
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsToggleRow
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
 * 没有任何手写的文件扫描、预设表、合并或「写完要重启」：写入立刻生效，目录由 SDK 刷新。
 */
@Composable
fun ModelsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    host: () -> ModelsHost?,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<Data?>(null) }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var openProvider by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<ProviderForm?>(null) }
    var formIsNew by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        val h = host()
        data = if (h == null) null else {
            val providers = runCatching { h.auth.providers() }
            notice = providers.exceptionOrNull()?.let { "读取供应商失败：${it.message}" } ?: notice
            Data(
                providers = providers.getOrDefault(emptyList()),
                models = runCatching { h.models() }.getOrDefault(emptyList()),
                custom = runCatching { h.advanced.modelsConfig() }.getOrNull(),
                current = h.currentModel(),
            )
        }
    }

    fun act(label: String, block: suspend (ModelsHost) -> Unit) {
        val h = host() ?: run { notice = "引擎没在运行：$label 没有执行。"; return }
        scope.launch {
            busy = label
            runCatching { block(h) }.onFailure { notice = "$label 失败：${it.message}" }
            busy = null
            reload++
        }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "模型与供应商", onBack = onBack)
        val d = data
        if (d == null) {
            PiInfoNote("引擎没有在运行，这一页读不到模型与供应商。启动后再来。")
            return@Column
        }
        val q = query.trim()
        val matches = { p: HostAuth.Provider -> q.isEmpty() || p.name.contains(q, true) || p.id.contains(q, true) }
        val configured = d.providers.filter { it.configured && matches(it) }
        val others = d.providers.filter { !it.configured && matches(it) }
        val customIds = d.custom?.providers?.keys.orEmpty()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = PiSpacing.small * 2, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            notice?.let { n -> item(key = "notice") { PiSettingsCard { PiSettingsEntryRow(title = n, supporting = "点一下关掉", onClick = { notice = null }) } } }
            busy?.let { b -> item(key = "busy") { SectionLabel("$b…") } }

            item(key = "current") {
                PiSettingsCard {
                    val cur = d.current
                    PiSettingsEntryRow(
                        title = cur?.let { (p, id) -> d.models.firstOrNull { it.provider == p && it.id == id }?.name?.ifBlank { id } ?: id } ?: "没有选择模型",
                        supporting = cur?.first?.let { "当前模型 · $it" } ?: "在下面的供应商里配置凭据，然后选一个模型",
                    )
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

            if (configured.isNotEmpty()) {
                item(key = "l:conf") { SectionLabel("已配置（${configured.size}）") }
                item(key = "c:conf") { ProviderCard(configured, d, customIds) { openProvider = it } }
            }
            if (others.isNotEmpty()) {
                item(key = "l:other") { SectionLabel("其他供应商（${others.size}）") }
                item(key = "c:other") { ProviderCard(others, d, customIds) { openProvider = it } }
            }
            if (configured.isEmpty() && others.isEmpty()) {
                item(key = "none") { PiSettingsCard { PiSettingsEntryRow(title = "没有匹配的供应商", supporting = "换个词，或添加自定义供应商。") } }
            }

            item(key = "l:custom") { SectionLabel("自定义供应商（models.json）") }
            item(key = "c:custom") {
                PiSettingsCard {
                    d.custom?.providers.orEmpty().entries.forEachIndexed { i, (id, json) ->
                        if (i > 0) PiSettingsHairline()
                        PiSettingsEntryRow(
                            title = id,
                            supporting = (json["baseUrl"] as? JsonPrimitive)?.content ?: "编辑",
                            onClick = { form = ProviderForm.from(id, json); formIsNew = false },
                        )
                    }
                    if (!d.custom?.providers.isNullOrEmpty()) PiSettingsHairline()
                    PiSettingsEntryRow(title = "添加自定义供应商…", supporting = "OpenAI 兼容、Anthropic 兼容等任意端点", onClick = { form = ProviderForm(api = "openai-completions"); formIsNew = true })
                    d.custom?.error?.let { e -> PiSettingsHairline(); PiSettingsEntryRow(title = "models.json 有问题", supporting = e) }
                }
            }

            item(key = "l:ops") { SectionLabel("目录") }
            item(key = "c:ops") {
                PiSettingsCard {
                    PiSettingsEntryRow(title = "刷新模型目录", supporting = "重新向各供应商拉取模型列表（需要网络）", onClick = { act("刷新模型目录") { it.auth.refreshModels() } })
                }
            }
        }

        openProvider?.let { id ->
            val p = d.providers.firstOrNull { it.id == id }
            if (p == null) openProvider = null else ProviderSheet(
                host = host,
                provider = p,
                models = d.models.filter { it.provider == p.id },
                current = d.current,
                custom = d.custom?.providers?.get(p.id),
                onDismiss = { openProvider = null; reload++ },
                onEditCustom = { json -> openProvider = null; form = ProviderForm.from(p.id, json); formIsNew = false },
            )
        }

        form?.let { f ->
            FormSheet(
                initial = f,
                isNew = formIsNew,
                existingIds = customIds + d.providers.map { it.id },
                host = host,
                onDismiss = { form = null },
                onDone = { form = null; reload++ },
            )
        }
    }
}

private class Data(
    val providers: List<HostAuth.Provider>,
    val models: List<PiResponses.ModelInfo>,
    val custom: HostAdvanced.ModelsConfig?,
    val current: Pair<String, String>?,
)

@Composable
private fun ProviderCard(
    list: List<HostAuth.Provider>,
    d: Data,
    customIds: Set<String>,
    onOpen: (String) -> Unit,
) {
    PiSettingsCard {
        list.forEachIndexed { i, p ->
            if (i > 0) PiSettingsHairline()
            val count = d.models.count { it.provider == p.id }
            val state = when {
                p.usingOAuth -> "订阅登录"
                p.configured -> "已配置"
                else -> "未配置"
            }
            PiSettingsEntryRow(
                title = p.name,
                supporting = listOfNotNull(
                    state,
                    if (count > 0) "$count 个模型" else null,
                    if (p.id in customIds) "自定义" else null,
                    if (p.isSubscription && !p.configured) "支持订阅账号" else null,
                ).joinToString(" · "),
                onClick = { onOpen(p.id) },
            )
        }
    }
}

// ------------------------------------------------------------------ 供应商面板 + 登录

private class Login(
    val loginId: String,
    val lines: List<String> = emptyList(),
    val url: String? = null,
    val code: String? = null,
    val prompt: HostAuth.AuthPrompt? = null,
    val error: String? = null,
)

@Composable
private fun ProviderSheet(
    host: () -> ModelsHost?,
    provider: HostAuth.Provider,
    models: List<PiResponses.ModelInfo>,
    current: Pair<String, String>?,
    custom: kotlinx.serialization.json.JsonObject?,
    onDismiss: () -> Unit,
    onEditCustom: (kotlinx.serialization.json.JsonObject) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var login by remember { mutableStateOf<Login?>(null) }
    var promptText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var configured by remember { mutableStateOf(provider.configured) }
    var showAll by remember { mutableStateOf(false) }

    // 登录期间的事件与提问：只收属于这次 loginId 的。
    val active = login?.loginId
    LaunchedEffect(active) {
        val h = host() ?: return@LaunchedEffect
        if (active == null) return@LaunchedEffect
        launch {
            h.events.collect { e ->
                val ev = HostAuth.authEvent(e)?.takeIf { it.loginId == active } ?: return@collect
                val cur = login ?: return@collect
                login = Login(
                    loginId = cur.loginId,
                    lines = (cur.lines + listOfNotNull(ev.message)).takeLast(6),
                    url = ev.url ?: cur.url,
                    code = ev.code ?: cur.code,
                    prompt = cur.prompt,
                    error = cur.error,
                )
            }
        }
        h.requests.collect { r ->
            val pr = HostAuth.authPrompt(r)?.takeIf { it.loginId == active } ?: return@collect
            val cur = login ?: return@collect
            promptText = ""
            login = Login(cur.loginId, cur.lines, cur.url, cur.code, pr, cur.error)
        }
    }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
            Text(provider.name, style = PiTheme.text.prose, color = PiTheme.palette.text)
            Text(provider.id + if (configured) " · 已配置" else " · 未配置", style = PiTheme.text.meta, color = PiTheme.palette.muted)
            note?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning, modifier = Modifier.padding(top = PiSpacing.small)) }

            if (provider.supportsApiKey) {
                Text("API Key", style = PiTheme.text.meta, color = PiTheme.palette.muted, modifier = Modifier.padding(top = PiSpacing.gutter))
                PiEditorField(value = key, onValueChange = { key = it }, placeholder = if (configured) "已保存；输入新 Key 覆盖" else "粘贴 API Key")
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small), modifier = Modifier.padding(top = PiSpacing.small)) {
                    PiSettingsChip(text = "保存", enabled = key.isNotBlank(), onClick = {
                        val h = host() ?: run { note = "引擎没在运行。"; return@PiSettingsChip }
                        scope.launch {
                            runCatching { h.auth.setApiKey(provider.id, key.trim()) }
                                .onSuccess { configured = true; key = ""; note = "已保存。" }
                                .onFailure { note = "保存失败：${it.message}" }
                        }
                    })
                }
            }

            provider.oauth?.let { oauth ->
                val l = login
                Text(oauth.name, style = PiTheme.text.meta, color = PiTheme.palette.muted, modifier = Modifier.padding(top = PiSpacing.gutter))
                if (l == null) {
                    PiSettingsChip(text = oauth.loginLabel ?: (if (oauth.isSubscription) "用订阅账号登录" else "登录"), onClick = {
                        val h = host() ?: run { note = "引擎没在运行。"; return@PiSettingsChip }
                        val id = "l" + System.currentTimeMillis().toString(36)
                        login = Login(id)
                        scope.launch {
                            runCatching { h.auth.login(provider.id, "oauth", id) }
                                .onSuccess { configured = true; login = null; note = "登录成功。" }
                                .onFailure { login = Login(id, error = it.message ?: "登录失败") }
                        }
                    })
                } else {
                    l.lines.forEach { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.text) }
                    l.url?.let { url ->
                        Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small), modifier = Modifier.padding(top = PiSpacing.small)) {
                            PiSettingsChip(text = "打开浏览器", onClick = { runCatching { uri.openUri(url) } })
                            PiSettingsChip(text = "复制链接", onClick = { clipboard.setText(AnnotatedString(url)) })
                        }
                    }
                    l.code?.let { code ->
                        Text(code, style = PiTheme.text.monoSmall, color = PiTheme.palette.accent, modifier = Modifier.padding(top = PiSpacing.small))
                        PiSettingsChip(text = "复制验证码", onClick = { clipboard.setText(AnnotatedString(code)) })
                    }
                    l.prompt?.let { pr ->
                        Text(pr.message, style = PiTheme.text.prose, color = PiTheme.palette.text, modifier = Modifier.padding(top = PiSpacing.small))
                        PiEditorField(value = promptText, onValueChange = { promptText = it }, placeholder = pr.placeholder)
                        PiSettingsChip(text = "提交", onClick = {
                            val h = host() ?: return@PiSettingsChip
                            scope.launch {
                                runCatching { h.answer(pr.requestId, promptText, false) }
                                login = Login(l.loginId, l.lines, l.url, l.code, null, l.error)
                            }
                        })
                    }
                    l.error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error, modifier = Modifier.padding(top = PiSpacing.small)) }
                    PiSettingsChip(text = if (l.error == null) "取消登录" else "关闭", onClick = {
                        val h = host()
                        if (l.error == null && h != null) scope.launch { runCatching { h.auth.cancel(l.loginId) } }
                        login = null
                    })
                }
            }

            if (configured) {
                PiSettingsChip(
                    text = "退出登录 / 删除凭据",
                    onClick = {
                        val h = host() ?: return@PiSettingsChip
                        scope.launch {
                            runCatching { h.auth.logout(provider.id) }
                                .onSuccess { configured = false; note = "已删除凭据。" }
                                .onFailure { note = "失败：${it.message}" }
                        }
                    },
                )
            }
            custom?.let { c -> PiSettingsChip(text = "编辑自定义供应商…", onClick = { onEditCustom(c) }) }

            Text("模型（${models.size}）", style = PiTheme.text.meta, color = PiTheme.palette.muted, modifier = Modifier.padding(top = PiSpacing.gutter))
            if (models.isEmpty()) {
                Text("这个供应商现在没有可用模型——先配置凭据，或刷新目录。", style = PiTheme.text.meta, color = PiTheme.palette.dim)
            }
            val shown = if (showAll) models else models.take(MODEL_PREVIEW)
            shown.forEach { m ->
                val isCurrent = current?.let { it.first == m.provider && it.second == m.id } == true
                PiSettingsEntryRow(
                    title = m.name.ifBlank { m.id },
                    supporting = listOfNotNull(
                        if (isCurrent) "当前" else null,
                        if (m.reasoning) "推理" else null,
                        if (m.acceptsImages) "看图" else null,
                        m.contextWindow?.let { "${it / 1000}K 上下文" },
                        m.inputCost?.let { c -> m.outputCost?.let { o -> "$$c / $$o 每百万 token" } },
                    ).joinToString(" · ").ifEmpty { null },
                    onClick = {
                        val h = host() ?: return@PiSettingsEntryRow
                        scope.launch {
                            runCatching { h.selectModel(m.provider.orEmpty(), m.id) }
                                .onSuccess { note = "已切换到 ${m.name.ifBlank { m.id }}。" }
                                .onFailure { note = "切换失败：${it.message}" }
                        }
                    },
                )
            }
            if (!showAll && models.size > MODEL_PREVIEW) {
                PiSettingsChip(text = "显示全部 ${models.size} 个", onClick = { showAll = true })
            }
        }
    }
}

// ------------------------------------------------------------------ 自定义供应商表单

@Composable
private fun FormSheet(
    initial: ProviderForm,
    isNew: Boolean,
    existingIds: Set<String>,
    host: () -> ModelsHost?,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var f by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PiSettingsMetrics.pageHorizontal)) {
            Text(if (isNew) "添加自定义供应商" else "编辑 ${initial.id}", style = PiTheme.text.prose, color = PiTheme.palette.text)
            if (isNew) {
                Field("名称（ID）", f.id, "例如 my-proxy") { f = f.copy(id = it) }
            }
            Field("Base URL", f.baseUrl, "https://example.com/v1") { f = f.copy(baseUrl = it) }
            Field("协议（api）", f.api, "openai-completions") { f = f.copy(api = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                for (api in API_SUGGESTIONS) PiSettingsChip(text = api.substringBefore('-'), active = f.api == api, onClick = { f = f.copy(api = api) })
            }
            Field("API Key", f.apiKey, "密钥，或环境变量名，或 !命令") { f = f.copy(apiKey = it) }
            Field("请求头（每行 名称: 值）", f.headersText, "X-Org: abc", singleLine = false) { f = f.copy(headersText = it) }

            Text("模型", style = PiTheme.text.meta, color = PiTheme.palette.muted, modifier = Modifier.padding(top = PiSpacing.gutter))
            f.models.forEachIndexed { i, m ->
                PiSettingsCard {
                    Column(Modifier.padding(PiSettingsMetrics.pageHorizontal)) {
                        Field("模型 ID", m.id, "gpt-x") { v -> f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) x.copy(id = v) else x }) }
                        Field("显示名（可空）", m.name, "") { v -> f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) x.copy(name = v) else x }) }
                        PiSettingsToggleRow("推理模型", null, m.reasoning, { v -> f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) x.copy(reasoning = v) else x }) })
                        PiSettingsToggleRow("接受图片", null, m.acceptsImages, { v -> f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) x.copy(acceptsImages = v) else x }) })
                        Field("上下文窗口（token，可空）", m.contextWindow, "128000") { v -> f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) x.copy(contextWindow = v) else x }) }
                        Field("最大输出（token，可空）", m.maxTokens, "8192") { v -> f = f.copy(models = f.models.mapIndexed { j, x -> if (j == i) x.copy(maxTokens = v) else x }) }
                        PiSettingsChip(text = "删除这个模型", onClick = { f = f.copy(models = f.models.filterIndexed { j, _ -> j != i }) })
                    }
                }
            }
            PiSettingsChip(text = "添加模型", onClick = { f = f.copy(models = f.models + ProviderForm.ModelForm()) })

            error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error, modifier = Modifier.padding(top = PiSpacing.gutter)) }
            Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small), modifier = Modifier.padding(top = PiSpacing.gutter, bottom = PiSpacing.gutter)) {
                PiSettingsChip(text = if (saving) "保存中…" else "保存", enabled = !saving, onClick = {
                    val id = f.id.trim()
                    if (id.isEmpty()) { error = "名称不能为空。"; return@PiSettingsChip }
                    if (isNew && id in existingIds) { error = "已经有叫「$id」的供应商。"; return@PiSettingsChip }
                    val h = host() ?: run { error = "引擎没在运行。"; return@PiSettingsChip }
                    when (val r = f.toJson()) {
                        is ProviderForm.Result.Invalid -> error = r.message
                        is ProviderForm.Result.Ok -> {
                            saving = true
                            scope.launch {
                                // 校验是 SDK 的事：非法会回滚，并带回字段精确的原话。
                                runCatching { h.advanced.setProvider(id, r.json) }
                                    .onSuccess { onDone() }
                                    .onFailure { error = it.message; saving = false }
                            }
                        }
                    }
                })
                if (!isNew) {
                    PiSettingsChip(text = "删除供应商", onClick = {
                        val h = host() ?: return@PiSettingsChip
                        scope.launch {
                            runCatching { h.advanced.removeProvider(initial.id) }
                                .onSuccess { onDone() }
                                .onFailure { error = it.message }
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    Text(label, style = PiTheme.text.meta, color = PiTheme.palette.muted, modifier = Modifier.padding(top = PiSpacing.small))
    PiEditorField(value = value, onValueChange = onChange, placeholder = placeholder, singleLine = singleLine, minHeight = if (singleLine) null else 72.dp)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = PiSettingsMetrics.pageHorizontal + PiSpacing.small, end = PiSettingsMetrics.pageHorizontal),
        style = PiTheme.text.meta,
        color = PiTheme.palette.muted,
    )
}

private const val MODEL_PREVIEW = 12
private val API_SUGGESTIONS = listOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai")
