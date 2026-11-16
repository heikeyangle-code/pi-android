package app.pi.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.pi.host.HostAuth
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// ------------------------------------------------------------------ 跨供应商选模型

/**
 * 从**所有已连接的供应商**里选一个模型。当前模型所在的供应商排第一，其余按名字；搜索是多词（「sonnet 4」
 * 命中 `claude-sonnet-4-5`），同时匹配模型名、id 和供应商名。选中后回答一个问题：只这次会话用，
 * 还是以后都默认用它——SDK 的 `setModel` 默认只改本会话（`persist` 才写进全局默认），两条路都摆出来。
 */
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
            "选择模型",
            style = PiTheme.text.prose,
            color = PiTheme.palette.text,
            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
        )
        PiEditorField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSpacing.small * 2),
            placeholder = "搜索模型或供应商",
            height = PiSettingsMetrics.searchFieldHeight,
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
                        if (models.isEmpty()) "还没有可用的模型——先连接一个供应商。" else "没有匹配「${query.trim()}」的模型。",
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
                    val h = host() ?: run { error = "引擎没在运行。"; return@UseBar }
                    scope.launch {
                        working = true
                        runCatching { h.selectModel(m.provider, m.id, use.persist) }
                            .onSuccess {
                                onSwitched((if (use.persist) "已设为默认并切换到 " else "已切换到 ") + m.title + "。")
                                onDismiss()
                            }
                            .onFailure { error = "切换失败：${it.message}"; working = false }
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = PiTheme.text.prose, color = PiTheme.palette.text, maxLines = 1)
        error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction(UseScope.Session.label, enabled = !working, onClick = { onUse(UseScope.Session) })
            PiSettingsChip(text = UseScope.Default.label, enabled = !working, onClick = { onUse(UseScope.Default) })
            PiSettingsChip(text = "取消", onClick = onCancel)
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

/**
 * 一个供应商：头像与状态、连接方式（订阅 / 网页登录 / API Key）、它的全部模型。
 *
 * 「已连接」只读 [provider]（宿主每次改动后由页面重新读取并传回），不在这里留一份会过期的副本。
 */
@Composable
internal fun ProviderSheet(
    host: () -> ModelsHost?,
    provider: HostAuth.Provider,
    facts: ProviderFacts,
    available: List<app.pi.rpc.PiResponses.ModelInfo>,
    current: Pair<String, String>?,
    custom: JsonObject?,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    onEditCustom: (JsonObject) -> Unit,
    onOverrideBuiltin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var login by remember { mutableStateOf<Login?>(null) }
    var promptText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var modelQuery by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ModelFacts?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    // 目录里这家的全部模型：还没连接也看得到它有什么（`model.list {all, provider}`）。读不到就退回
    // 页面给的「可用模型」，不留空白。
    var catalog by remember { mutableStateOf<List<app.pi.rpc.PiResponses.ModelInfo>?>(null) }
    LaunchedEffect(provider.id, provider.configured) {
        catalog = host()?.let { h -> runCatching { h.catalog(provider.id) }.getOrNull() }
    }
    val allModels = (catalog ?: available).map { it.facts() }
    val connection = connectionOf(facts)
    val configured = provider.configured

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
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---- 头部 ----
            item(key = "head") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderAvatar(facts.id, facts.title, size = 48.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(facts.title, style = PiTheme.text.prose, color = PiTheme.palette.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ConnectionPill(connection)
                            Text(facts.id, style = PiTheme.text.monoSmall, color = PiTheme.palette.dim, maxLines = 1)
                        }
                    }
                }
            }
            note?.let { n -> item(key = "note") { Text(n, style = PiTheme.text.meta, color = PiTheme.palette.warning, modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small)) } }

            // ---- 连接 ----
            item(key = "connect") {
                ModelsCard {
                    Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("连接方式", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        provider.oauth?.let { oauth ->
                            val l = login
                            if (l == null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PrimaryAction(
                                        oauth.loginLabel ?: (if (oauth.isSubscription) "用订阅账号登录" else "网页登录"),
                                        onClick = {
                                            val h = host() ?: run { note = "引擎没在运行。"; return@PrimaryAction }
                                            val id = "l" + System.currentTimeMillis().toString(36)
                                            login = Login(id)
                                            note = null
                                            scope.launch {
                                                runCatching { h.auth.login(provider.id, "oauth", id) }
                                                    .onSuccess { login = null; note = "登录成功。"; onChanged() }
                                                    .onFailure { login = Login(id, error = it.message ?: "登录失败") }
                                            }
                                        },
                                    )
                                    Text(oauth.name, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                                }
                            } else {
                                LoginProgress(
                                    l = l,
                                    promptText = promptText,
                                    onPromptText = { promptText = it },
                                    onOpenUrl = { runCatching { uri.openUri(it) } },
                                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                                    onSubmit = { pr ->
                                        val h = host() ?: return@LoginProgress
                                        scope.launch {
                                            runCatching { h.answer(pr.requestId, promptText, false) }
                                            login = Login(l.loginId, l.lines, l.url, l.code, null, l.error)
                                        }
                                    },
                                    onCancel = {
                                        val h = host()
                                        if (l.error == null && h != null) scope.launch { runCatching { h.auth.cancel(l.loginId) } }
                                        login = null
                                    },
                                )
                            }
                        }
                        if (provider.supportsApiKey) {
                            LabeledField(
                                label = "API Key",
                                value = key,
                                placeholder = if (configured) "已保存；输入新 Key 覆盖" else "粘贴 API Key",
                                secret = true,
                                onChange = { key = it },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PiSettingsChip(text = "保存 Key", enabled = key.isNotBlank(), onClick = {
                                    val h = host() ?: run { note = "引擎没在运行。"; return@PiSettingsChip }
                                    scope.launch {
                                        runCatching { h.auth.setApiKey(provider.id, key.trim()) }
                                            .onSuccess { key = ""; note = "已保存。"; onChanged() }
                                            .onFailure { note = "保存失败：${it.message}" }
                                    }
                                })
                            }
                        }
                        if (provider.oauth == null && !provider.supportsApiKey) {
                            Text("这个供应商没有登录方式；用自定义设置给它写好地址与密钥。", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                        // 环境变量来的凭据不在 auth.json 里，`logout` 删不掉它，不假装能删。
                        if (configured && (provider.statusSource == "stored" || provider.usingOAuth)) {
                            PiSettingsChip(text = "退出登录 / 删除凭据", onClick = {
                                val h = host() ?: return@PiSettingsChip
                                scope.launch {
                                    runCatching { h.auth.logout(provider.id) }
                                        .onSuccess { note = "已删除凭据。"; onChanged() }
                                        .onFailure { note = "失败：${it.message}" }
                                }
                            })
                        } else if (configured && provider.statusSource == "environment") {
                            Text("凭据来自环境变量，要去环境里改。", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                        if (custom != null) {
                            PiSettingsChip(text = "编辑自定义设置…", onClick = { onEditCustom(custom) })
                        } else {
                            PiSettingsChip(text = "自定义地址与请求头…", onClick = onOverrideBuiltin)
                        }
                    }
                }
            }

            // ---- 模型 ----
            item(key = "models-head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelsSectionLabel("模型（${allModels.size}）")
                    if (!configured && allModels.isNotEmpty()) {
                        Text(
                            "先连接这个供应商，才能选用这些模型。",
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.dim,
                            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                        )
                    }
                    if (allModels.isEmpty()) {
                        Text(
                            "目录里没有这个供应商的模型——刷新目录，或用自定义设置添加。",
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.dim,
                            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                        )
                    }
                    if (allModels.size > MODEL_SEARCH_THRESHOLD) {
                        PiEditorField(
                            value = modelQuery,
                            onValueChange = { modelQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal),
                            placeholder = "搜索这家的模型",
                            height = PiSettingsMetrics.searchFieldHeight,
                        )
                    }
                }
            }
            val filtered = searchModels(allModels, emptyMap(), modelQuery)
            items(filtered, key = { "m:${it.id}" }) { m ->
                ModelRow(
                    model = m,
                    current = current?.let { m.sameAs(it.first, it.second) } == true,
                    selected = picked?.id == m.id,
                    // 没连接的供应商，模型只读：点了也选不了（SDK 的 model.set 会拒绝），不如不给点。
                    onClick = if (configured) ({ picked = m; pickError = null }) else null,
                )
            }
            if (modelQuery.isNotBlank() && filtered.isEmpty()) {
                item(key = "m:none") {
                    Text(
                        "没有匹配「${modelQuery.trim()}」的模型。",
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
                error = pickError,
                onUse = { use ->
                    val h = host() ?: run { pickError = "引擎没在运行。"; return@UseBar }
                    scope.launch {
                        working = true
                        runCatching { h.selectModel(m.provider.ifBlank { provider.id }, m.id, use.persist) }
                            .onSuccess {
                                note = (if (use.persist) "已设为默认并切换到 " else "已切换到 ") + m.title + "。"
                                picked = null
                                onChanged()
                            }
                            .onFailure { pickError = "切换失败：${it.message}" }
                        working = false
                    }
                },
                onCancel = { picked = null; pickError = null },
            )
        }
    }
}

@Composable
private fun LoginProgress(
    l: Login,
    promptText: String,
    onPromptText: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSubmit: (HostAuth.AuthPrompt) -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (l.error == null && l.lines.isEmpty() && l.url == null && l.prompt == null) {
            Text("正在登录…", style = PiTheme.text.meta, color = PiTheme.palette.muted)
        }
        l.lines.forEach { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.text) }
        l.url?.let { url ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PiSettingsChip(text = "打开浏览器", onClick = { onOpenUrl(url) })
                PiSettingsChip(text = "复制链接", onClick = { onCopy(url) })
            }
        }
        l.code?.let { code ->
            Text(code, style = PiTheme.text.mono, color = PiTheme.palette.accent)
            PiSettingsChip(text = "复制验证码", onClick = { onCopy(code) })
        }
        l.prompt?.let { pr ->
            Text(pr.message, style = PiTheme.text.prose, color = PiTheme.palette.text)
            PiEditorField(
                value = promptText,
                onValueChange = onPromptText,
                placeholder = pr.placeholder,
                // SDK 的提问带 secret 就别把输入摆在屏幕上。
                visualTransformation = if (pr.secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
            PiSettingsChip(text = "提交", onClick = { onSubmit(pr) })
        }
        l.error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
        PiSettingsChip(text = if (l.error == null) "取消登录" else "关闭", onClick = onCancel)
    }
}

/** 一家的模型多到这个数才显示搜索框。 */
private const val MODEL_SEARCH_THRESHOLD = 12
