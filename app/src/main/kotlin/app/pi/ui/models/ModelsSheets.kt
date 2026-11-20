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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
            style = app.pi.ui.components.PiType.title,
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, PiTheme.palette.accent.copy(alpha = 0.35f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = PiTheme.text.prose.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = PiTheme.palette.text,
                maxLines = 1,
            )
            error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction(UseScope.Session.label, enabled = !working, onClick = { onUse(UseScope.Session) })
                PiSettingsChip(text = UseScope.Default.label, enabled = !working, onClick = { onUse(UseScope.Default) })
                PiSettingsChip(text = "取消", onClick = onCancel)
            }
        }
    }
}

// ------------------------------------------------------------------ 供应商面板 + 登录

private data class LoginState(
    val loginId: String,
    val lines: List<String> = emptyList(),
    val url: String? = null,
    val code: String? = null,
    val links: List<HostAuth.InfoLink> = emptyList(),
    val prompt: HostAuth.AuthPrompt? = null,
    val browserWarning: String? = null,
    val error: String? = null,
)

/**
 * 一个供应商：头像与状态、连接方式（订阅 / 网页登录 / API Key）、它的全部模型。
 *
 * 「已连接」只读 [provider]（宿主每次改动后由页面重新读取并传回），不在这里留一份会过期的副本。
 *
 * ## 为什么登录事件订阅必须用 `CoroutineStart.UNDISPATCHED`
 * `h.events` 和 `h.requests` 都是 replay=0 的 `SharedFlow`。此前代码靠 `LaunchedEffect(active)`
 * 在下一帧才开始 collect，导致像 Anthropic / OpenAI Codex / GitHub Copilot 这样立刻发 `select`
 * 或 `auth_url` 的供应商在订阅建立前就把消息发丢，界面永远卡在「正在登录…」。
 * 现在在调用 `h.auth.login()` 之前就以 `CoroutineStart.UNDISPATCHED` 立即挂好订阅，彻底消除竞态；
 * 同时监听 `ui.cancel` 自动收起已被本地回调胜出作废的 `manual_code` 输入框，关闭面板时自动取消登录并释放回调端口。
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
    var login by remember { mutableStateOf<LoginState?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var promptText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var modelQuery by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ModelFacts?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    // 目录里这家的全部模型：还没连接也看得到它有什么（`model.list {all, provider}`）。
    var catalog by remember { mutableStateOf<List<app.pi.rpc.PiResponses.ModelInfo>?>(null) }
    LaunchedEffect(provider.id, provider.configured) {
        catalog = host()?.let { h -> runCatching { h.catalog(provider.id) }.getOrNull() }
    }

    // 面板被滑走/关闭时，如果登录仍在进行，立刻向宿主发 auth.cancel 释放本地回调端口（避免重试报 BUSY）。
    DisposableEffect(provider.id) {
        onDispose {
            val activeId = login?.takeIf { it.error == null }?.loginId
            loginJob?.cancel()
            if (activeId != null) {
                host()?.let { h -> scope.launch { runCatching { h.auth.cancel(activeId) } } }
            }
        }
    }

    fun openExternalUrl(url: String) {
        val opened = runCatching { uri.openUri(url) }
        if (opened.isFailure) {
            login = login?.copy(browserWarning = "未能自动打开浏览器，请点击「复制链接」后手动粘贴到浏览器中打开。")
        } else {
            login = login?.copy(browserWarning = null)
        }
    }

    fun startOAuthLogin() {
        val h = host() ?: run { note = "引擎没在运行，无法登录。"; return }
        // 若已有残留登录任务先清掉。
        loginJob?.cancel()
        val id = "l" + System.nanoTime().toString(36)
        login = LoginState(loginId = id)
        promptText = ""
        note = null

        loginJob = scope.launch {
            var lastAutoOpenedUrl: String? = null
            val eventWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                h.events.collect { e ->
                    // 回调服务器胜出或登录结束：宿主发 ui.cancel，立刻收起过期的 prompt。
                    val cancelledReqId = HostAuth.cancelId(e)
                    if (cancelledReqId != null && login?.prompt?.requestId == cancelledReqId) {
                        login = login?.copy(prompt = null)
                        return@collect
                    }
                    val ev = HostAuth.authEvent(e)?.takeIf { it.loginId == id } ?: return@collect
                    val cur = login ?: return@collect
                    val nextUrl = ev.url ?: cur.url
                    val nextLines = if (!ev.message.isNullOrBlank()) {
                        (cur.lines + ev.message).distinct().takeLast(6)
                    } else {
                        cur.lines
                    }
                    val nextLinks = if (ev.links.isNotEmpty()) ev.links else cur.links
                    login = cur.copy(
                        lines = nextLines,
                        url = nextUrl,
                        code = ev.code ?: cur.code,
                        links = nextLinks,
                    )
                    // 对齐官方 LoginDialogComponent：收到 auth_url 时主动拉起系统浏览器一次（device_code 等用户先看清码或一键复制打开）。
                    if (ev.kind == "auth_url" && !ev.url.isNullOrBlank() && ev.url != lastAutoOpenedUrl) {
                        lastAutoOpenedUrl = ev.url
                        openExternalUrl(ev.url)
                    }
                }
            }
            val requestWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                h.requests.collect { r ->
                    val pr = HostAuth.authPrompt(r)?.takeIf { it.loginId == id } ?: return@collect
                    val cur = login ?: return@collect
                    promptText = ""
                    login = cur.copy(prompt = pr)
                }
            }
            try {
                h.auth.login(provider.id, "oauth", id)
                login = null
                note = "登录成功。"
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                login = (login ?: LoginState(id)).copy(prompt = null, error = e.message ?: "登录失败")
            } finally {
                eventWatcher.cancel()
                requestWatcher.cancel()
            }
        }
    }

    fun cancelActiveLogin() {
        val active = login
        loginJob?.cancel()
        loginJob = null
        if (active != null && active.error == null) {
            host()?.let { h -> scope.launch { runCatching { h.auth.cancel(active.loginId) } } }
        }
        login = null
    }

    val allModels = (catalog ?: available).map { it.facts() }
    val connection = connectionOf(facts)
    val configured = provider.configured

    PiSheet(
        onDismiss = {
            cancelActiveLogin()
            onDismiss()
        },
        fullHeight = true,
    ) {
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
            note?.let { n ->
                item(key = "note") {
                    Text(
                        n,
                        style = PiTheme.text.meta,
                        color = if (n.contains("失败") || n.contains("未")) PiTheme.palette.warning else PiTheme.palette.success,
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                    )
                }
            }

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
                                        onClick = { startOAuthLogin() },
                                    )
                                    Text(oauth.name, style = PiTheme.text.meta, color = PiTheme.palette.dim)
                                }
                            } else {
                                LoginProgress(
                                    l = l,
                                    promptText = promptText,
                                    onPromptText = { promptText = it },
                                    onOpenUrl = { openExternalUrl(it) },
                                    onCopy = { value ->
                                        clipboard.setText(AnnotatedString(value))
                                        note = "已复制到剪贴板。"
                                    },
                                    onCopyAndOpen = { code, url ->
                                        clipboard.setText(AnnotatedString(code))
                                        note = "验证码已复制，正在打开验证页面…"
                                        openExternalUrl(url)
                                    },
                                    onAnswer = { pr, value ->
                                        val h = host() ?: return@LoginProgress
                                        scope.launch {
                                            runCatching { h.answer(pr.requestId, value, false) }
                                            login = login?.copy(prompt = null)
                                        }
                                    },
                                    onCancel = { cancelActiveLogin() },
                                )
                            }
                        }
                        if (provider.supportsApiKeyInput) {
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
                        } else if (provider.hasAmbientApiKey && provider.oauth == null) {
                            Text(
                                "这个供应商通过环境变量或本机云凭证工作，不支持直接填写 API Key。",
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.dim,
                            )
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
    l: LoginState,
    promptText: String,
    onPromptText: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCopyAndOpen: (code: String, url: String) -> Unit,
    onAnswer: (HostAuth.AuthPrompt, String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (l.error == null && l.lines.isEmpty() && l.url == null && l.code == null && l.prompt == null) {
            Text("正在准备登录…", style = PiTheme.text.meta, color = PiTheme.palette.muted)
        }
        l.lines.forEach { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.text) }
        if (l.links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                l.links.forEach { link ->
                    PiSettingsChip(text = link.label?.takeIf { it.isNotBlank() } ?: "打开说明", onClick = { onOpenUrl(link.url) })
                }
            }
        }
        // 设备码流程（GitHub Copilot / Kimi / Meta 等）：高亮展示验证码，并提供「复制验证码并打开浏览器」一键操作。
        l.code?.let { code ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = PiShapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, PiTheme.palette.borderAccent.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("设备验证码", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    Text(code, style = PiTheme.text.mono, color = PiTheme.palette.accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val url = l.url
                        if (url != null) {
                            PrimaryAction("复制验证码并打开浏览器", onClick = { onCopyAndOpen(code, url) })
                        }
                        PiSettingsChip(text = "复制验证码", onClick = { onCopy(code) })
                    }
                }
            }
        }
        // 授权链接（auth_url 或 device_code 附带 url）。
        l.url?.let { url ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (l.code == null) {
                    PrimaryAction("打开浏览器授权", onClick = { onOpenUrl(url) })
                } else {
                    PiSettingsChip(text = "打开验证页", onClick = { onOpenUrl(url) })
                }
                PiSettingsChip(text = "复制链接", onClick = { onCopy(url) })
            }
        }
        l.browserWarning?.let { warn ->
            Text(warn, style = PiTheme.text.meta, color = PiTheme.palette.warning)
        }
        // 交互式提问：区分 select / manual_code / secret / text 四种 SDK 协议形态。
        l.prompt?.let { pr ->
            if (pr.isSelect) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pr.message.isNotBlank()) {
                        Text(pr.message, style = PiTheme.text.prose, color = PiTheme.palette.text)
                    }
                    pr.options.forEach { opt ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAnswer(pr, opt.id) },
                            shape = PiShapes.card,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(opt.label, style = PiTheme.text.prose, color = PiTheme.palette.text)
                                opt.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                    Text(desc, style = PiTheme.text.meta, color = PiTheme.palette.muted)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = if (pr.isManualCode) {
                        pr.message.ifBlank { "如果浏览器未自动跳转回来，请粘贴完整回调链接或授权码：" }
                    } else {
                        pr.message
                    }
                    if (label.isNotBlank()) {
                        Text(label, style = PiTheme.text.prose, color = PiTheme.palette.text)
                    }
                    if (pr.isManualCode) {
                        Text("浏览器自动回跳成功后会自动继续，无需手动粘贴。", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                    }
                    PiEditorField(
                        value = promptText,
                        onValueChange = onPromptText,
                        placeholder = pr.placeholder ?: if (pr.isManualCode) "粘贴回调 URL 或授权码" else null,
                        visualTransformation = if (pr.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    )
                    val canSubmit = pr.allowEmpty || promptText.isNotBlank() || (!pr.isManualCode && !pr.secret)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryAction(
                            if (promptText.isBlank() && pr.allowEmpty) "使用默认继续" else "提交",
                            enabled = canSubmit,
                            onClick = { onAnswer(pr, promptText) },
                        )
                    }
                }
            }
        }
        l.error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
        PiSettingsChip(text = if (l.error == null) "取消登录" else "关闭", onClick = onCancel)
    }
}

/** 一家的模型多到这个数才显示搜索框。 */
private const val MODEL_SEARCH_THRESHOLD = 12

// ------------------------------------------------------------------ 统一作用域 / 循环模型选择器（对齐官方 ScopedModelsSelectorComponent）

/**
 * 统一「作用域与循环模型」选择器：对照 Pi 官方 `modes/interactive/components/scoped-models-selector.js`。
 *
 * 支持搜索、逐项勾选、按供应商一键全选/取消、调整循环顺序（↑/↓）、全选/清空，
 * 并在底部明确提供两条官方路径：
 *  - **「仅本会话应用」**：调用 SDK `models.setScoped` 立即切换当前会话循环列表；
 *  - **「应用并设为默认」**：同时调用 `models.setScoped` 与 `settings.set("setEnabledModels", ...)` 落盘持久化。
 */
@Composable
internal fun ScopedModelsSheet(
    host: () -> ModelsHost?,
    models: List<app.pi.rpc.PiResponses.ModelInfo>,
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
    // 初始顺序：优先采用当前会话已激活的作用域模型，其次按持久化精确 pattern 匹配。
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

    fun toggleProvider(providerId: String, providerModelKeys: List<String>) {
        val allSelected = providerModelKeys.isNotEmpty() && providerModelKeys.all { it in selectedSet }
        selectedKeys = if (allSelected) {
            selectedKeys.filterNot { it in providerModelKeys }
        } else {
            (selectedKeys + providerModelKeys).distinct()
        }
    }

    fun applySelection(persist: Boolean) {
        val h = host() ?: run { error = "引擎没在运行。"; return }
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
                    refs.isEmpty() && persist -> "已清空作用域与默认循环模型（将循环全部可用模型）。"
                    refs.isEmpty() -> "已清空当前会话作用域模型。"
                    persist -> "已应用并设为默认循环模型（${refs.size} 个）。"
                    else -> "已应用到当前会话作用域模型（${refs.size} 个）。"
                }
                onApplied(msg)
                onDismiss()
            }.onFailure {
                error = "应用失败：${it.message}"
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
            Text("作用域与循环模型", style = PiTheme.text.prose, color = PiTheme.palette.text)
            Text(
                if (selectedKeys.isEmpty()) "未选中任何模型 = 循环切换全部可用模型" else "已选 ${selectedKeys.size} 个模型（按顺序循环）",
                style = PiTheme.text.meta,
                color = PiTheme.palette.dim,
            )
            if (persistedPatterns.any { it.contains('*') }) {
                Text(
                    "当前配置含通配符：${persistedPatterns.joinToString(", ")}；点「应用并设为默认」将按所选模型覆盖。",
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.warning,
                )
            }
        }
        PiEditorField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSpacing.small * 2),
            placeholder = "搜索模型或供应商",
            height = PiSettingsMetrics.searchFieldHeight,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiSettingsChip(
                text = "全选可见",
                onClick = {
                    val visibleKeys = filtered.flatMap { g -> g.models.map { "${it.provider}/${it.id}" } }
                    selectedKeys = (selectedKeys + visibleKeys).distinct()
                },
            )
            PiSettingsChip(
                text = "清空",
                enabled = selectedKeys.isNotEmpty(),
                onClick = { selectedKeys = emptyList() },
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            if (selectedKeys.isNotEmpty() && query.isBlank()) {
                item(key = "selected-head") {
                    ModelsSectionLabel("已选顺序（${selectedKeys.size}）", Modifier.padding(top = 10.dp, bottom = 4.dp))
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
                        PiSettingsChip(text = "移除", onClick = { toggleKey(key) })
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
                                text = if (allIn) "取消全选" else "全选该供应商",
                                active = allIn,
                                onClick = { toggleProvider(g.providerId, providerKeys) },
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
                        if (models.isEmpty()) "还没有可用模型——先连接一个供应商。" else "没有匹配「${query.trim()}」的模型。",
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
                PrimaryAction("仅本会话应用", enabled = !working, onClick = { applySelection(persist = false) })
                PiSettingsChip(text = "应用并设为默认", enabled = !working, onClick = { applySelection(persist = true) })
                PiSettingsChip(text = "取消", onClick = onDismiss)
            }
        }
    }
}

// ------------------------------------------------------------------ 官方两步「逐模型思考等级」选择器（对齐 settings-selector.js:392-485）

private val OFFICIAL_THINKING_META = mapOf(
    "minimal" to ("极简" to "最少推理（约 1k token 预算）"),
    "low" to ("低" to "轻量推理（约 2k token 预算）"),
    "medium" to ("中" to "均衡推理（约 8k token 预算）"),
    "high" to ("高" to "深度推理（约 16k token 预算）"),
    "xhigh" to ("超高" to "极深推理（约 32k token 预算）"),
    "max" to ("最高" to "最大推理深度"),
)

/**
 * 逐模型思考等级设置面板：对照 Pi 官方 `settings-selector.js` 的两步子菜单。
 *
 *  - 第 1 步：列出全部可用推理模型（已覆盖的排在前面，并保留配置中可能存在的离线/自定义覆盖项），支持搜索；
 *  - 第 2 步：点击模型后，仅展示**该模型真实支持的思考等级**（来自 `@earendil-works/pi-ai` 的 `getSupportedThinkingLevels`），
 *    外加「恢复跟随全局默认」，直接调用 SDK `setModelThinkingLevel` / `removeModelThinkingLevel` 落盘。
 */
@Composable
internal fun ModelThinkingLevelsSheet(
    host: () -> ModelsHost?,
    models: List<app.pi.rpc.PiResponses.ModelInfo>,
    providerNames: Map<String, String>,
    overrides: Map<String, String>,
    defaultLevel: String,
    onDismiss: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<app.pi.rpc.PiResponses.ModelInfo?>(null) }
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
            compareByDescending<app.pi.rpc.PiResponses.ModelInfo> { "${it.provider.orEmpty()}/${it.id}" in liveOverrides }
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
                Text("逐模型思考等级", style = PiTheme.text.prose, color = PiTheme.palette.text)
                Text(
                    "全局默认：${OFFICIAL_THINKING_META[defaultLevel]?.first ?: defaultLevel} · 已单独覆盖 ${liveOverrides.size} 个模型",
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.dim,
                )
            }
            PiEditorField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSpacing.small * 2),
                placeholder = "搜索推理模型",
                height = PiSettingsMetrics.searchFieldHeight,
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
                        ModelsSectionLabel("其他已配置覆盖（未连接供应商）", Modifier.padding(top = 6.dp, bottom = 4.dp))
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
                                        "当前覆盖：${OFFICIAL_THINKING_META[lvl]?.first ?: lvl}",
                                        style = PiTheme.text.meta,
                                        color = PiTheme.palette.accent,
                                    )
                                }
                                PiSettingsChip(
                                    text = "移除",
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
                                                    onChanged("已移除 $key 的思考等级覆盖。")
                                                }
                                                .onFailure { error = "移除失败：${it.message}" }
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
                                        append(" · 支持 ")
                                        append(m.supportedThinkingLevels.joinToString("/") { OFFICIAL_THINKING_META[it]?.first ?: it })
                                    }
                                },
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.dim,
                                maxLines = 1,
                            )
                        }
                        Text(
                            currentLvl?.let { OFFICIAL_THINKING_META[it]?.first ?: it } ?: "跟随默认",
                            style = PiTheme.text.meta,
                            color = if (currentLvl != null) PiTheme.palette.accent else PiTheme.palette.muted,
                        )
                        Text("›", style = PiTheme.text.prose, color = PiTheme.palette.dim)
                    }
                }
                if (reasoningModels.isEmpty()) {
                    item(key = "no-reasoning") {
                        Text(
                            if (models.isEmpty()) "还没有可用模型——先连接一个供应商。" else "没有找到支持推理的模型。",
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
                PiSettingsChip(text = "完成", onClick = onDismiss)
            }
        } else {
            // 第 2 步：选该模型真实支持的思考等级
            val key = "${active.provider.orEmpty()}/${active.id}"
            val currentLvl = liveOverrides[key]
            val levels = active.supportedThinkingLevels.ifEmpty { listOf("minimal", "low", "medium", "high") }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(active.name.ifBlank { active.id }, style = PiTheme.text.prose, color = PiTheme.palette.text)
                Text(
                    "$key · 选择该模型新会话启动时的默认思考等级",
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
                                val h = host() ?: run { error = "引擎没在运行。"; return@clickable }
                                scope.launch {
                                    busy = true
                                    error = null
                                    runCatching { h.removeModelThinkingLevel(active.provider.orEmpty(), active.id) }
                                        .onSuccess {
                                            liveOverrides = liveOverrides - key
                                            onChanged("${active.name.ifBlank { active.id }} 已恢复跟随全局默认。")
                                            selectedModel = null
                                        }
                                        .onFailure { error = "保存失败：${it.message}" }
                                    busy = false
                                }
                            },
                        shape = PiShapes.card,
                        color = if (currentLvl == null) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (currentLvl == null) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                (if (currentLvl == null) "✓ " else "") + "跟随全局默认（${OFFICIAL_THINKING_META[defaultLevel]?.first ?: defaultLevel}）",
                                style = PiTheme.text.prose,
                                color = if (currentLvl == null) PiTheme.palette.accent else PiTheme.palette.text,
                            )
                            Text("不为此模型单独覆盖，使用全局默认思考等级。", style = PiTheme.text.meta, color = PiTheme.palette.dim)
                        }
                    }
                }
                items(levels, key = { it }) { lvl ->
                    val meta = OFFICIAL_THINKING_META[lvl]
                    val activeLevel = currentLvl == lvl
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 4.dp)
                            .clickable(enabled = !busy) {
                                val h = host() ?: run { error = "引擎没在运行。"; return@clickable }
                                scope.launch {
                                    busy = true
                                    error = null
                                    runCatching { h.setModelThinkingLevel(active.provider.orEmpty(), active.id, lvl) }
                                        .onSuccess {
                                            liveOverrides = liveOverrides + (key to lvl)
                                            onChanged("${active.name.ifBlank { active.id }} 思考等级已设为 ${meta?.first ?: lvl}。")
                                            selectedModel = null
                                        }
                                        .onFailure { error = "保存失败：${it.message}" }
                                    busy = false
                                }
                            },
                        shape = PiShapes.card,
                        color = if (activeLevel) PiTheme.palette.selectedBg else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (activeLevel) PiTheme.palette.accent else MaterialTheme.colorScheme.outlineVariant,
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
                PiSettingsChip(text = "← 返回模型列表", enabled = !busy, onClick = { selectedModel = null; error = null })
            }
        }
    }
}
