package app.pi.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.HostAuth
import app.pi.i18n.tr
import app.pi.ui.components.PiSheet
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsDialog
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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
 * 供应商连接与模型面板：
 *  1. 顶部身份头：头像、标题、连接状态、provider ID、模型数；
 *  2. 账号 / 订阅 OAuth 登录卡（支持订阅账号与 OpenRouter 等网页 OAuth，含完整事件/设备码/手动回调流）；
 *  3. API Key 配置卡（一键剪贴板粘贴、明文切换眼睛、整宽主保存按钮、来源徽标）；
 *  4. 高级与凭据管理卡（自定义端点 models.json 覆盖 + 带二次确认的退出登录/移除凭据）；
 *  5. 该供应商下的全部模型目录（搜索、能力/价格标签、一键切换或设为默认）。
 */
@Composable
internal fun ProviderSheet(
    host: () -> ModelsHost?,
    provider: HostAuth.Provider,
    facts: ProviderFacts,
    available: List<app.pi.protocol.PiResponses.ModelInfo>,
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

    // 按 provider.id 做 key，避免同 sheet 实例切换不同供应商时残留输入。
    var key by remember(provider.id) { mutableStateOf("") }
    var savingKey by remember(provider.id) { mutableStateOf(false) }
    var login by remember(provider.id) { mutableStateOf<LoginState?>(null) }
    var loginJob by remember(provider.id) { mutableStateOf<Job?>(null) }
    var promptText by remember(provider.id) { mutableStateOf("") }
    var note by remember(provider.id) { mutableStateOf<String?>(null) }
    var noteWarning by remember(provider.id) { mutableStateOf(false) }
    var modelQuery by remember(provider.id) { mutableStateOf("") }
    var picked by remember(provider.id) { mutableStateOf<ModelFacts?>(null) }
    var pickError by remember(provider.id) { mutableStateOf<String?>(null) }
    var working by remember(provider.id) { mutableStateOf(false) }
    var confirmLogout by remember(provider.id) { mutableStateOf(false) }

    var catalog by remember(provider.id) { mutableStateOf<List<app.pi.protocol.PiResponses.ModelInfo>?>(null) }
    LaunchedEffect(provider.id, provider.configured) {
        catalog = host()?.let { h -> runCatching { h.catalog(provider.id) }.getOrNull() }
    }

    // 面板离开组合时，用独立非取消协程发 auth.cancel，确保宿主立刻释放本地 OAuth 回调端口，不再残留 ERR.BUSY。
    DisposableEffect(provider.id) {
        onDispose {
            val activeId = login?.takeIf { it.error == null }?.loginId
            loginJob?.cancel()
            if (activeId != null) {
                val h = host()
                if (h != null) {
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        runCatching { h.auth.cancel(activeId) }
                    }
                }
            }
        }
    }

    fun openExternalUrl(url: String) {
        val opened = runCatching { uri.openUri(url) }
        if (opened.isFailure) {
            login = login?.copy(
                browserWarning = tr(
                    "未能自动打开浏览器，请点击「复制链接」后手动粘贴到浏览器中打开。",
                    "Could not open the browser automatically. Tap \"Copy Link\" and paste it into your browser.",
                ),
            )
        } else {
            login = login?.copy(browserWarning = null)
        }
    }

    fun startOAuthLogin() {
        val h = host() ?: run {
            note = tr("引擎没在运行，无法登录。", "Engine is not running; cannot sign in.")
            noteWarning = true
            return
        }
        loginJob?.cancel()
        val id = "l" + System.nanoTime().toString(36)
        login = LoginState(loginId = id)
        promptText = ""
        note = null
        noteWarning = false

        loginJob = scope.launch {
            var lastAutoOpenedUrl: String? = null
            val eventWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                h.events.collect { e ->
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
                note = tr("登录成功，凭据已保存到 auth.json。", "Signed in. Credentials saved to auth.json.")
                noteWarning = false
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                login = (login ?: LoginState(id)).copy(prompt = null, error = e.message ?: tr("登录失败", "Sign-in failed"))
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
            val h = host()
            if (h != null) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { h.auth.cancel(active.loginId) }
                }
            }
        }
        login = null
    }

    val allModels = (catalog ?: available).map { it.facts() }
    val connection = connectionOf(facts)
    val configured = provider.configured
    val palette = PiTheme.palette

    PiSheet(
        onDismiss = {
            cancelActiveLogin()
            onDismiss()
        },
        fullHeight = true,
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 1. 顶部身份头 ----
            item(key = "head") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ProviderAvatar(facts.id, facts.title, size = 50.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            facts.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ConnectionPill(connection)
                            TagPill(facts.id)
                            if (allModels.isNotEmpty()) {
                                Text(
                                    tr("${allModels.size} 个模型", "${allModels.size} models"),
                                    style = PiTheme.text.meta,
                                    color = palette.dim,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 状态提示条 ----
            note?.let { n ->
                item(key = "note") {
                    val tone = if (noteWarning) palette.warning else palette.success
                    Surface(
                        onClick = { note = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        shape = RoundedCornerShape(12.dp),
                        color = tone.copy(alpha = 0.11f),
                        border = BorderStroke(0.5.dp, tone.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            n,
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = tone,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // ---- 2. OAuth / 订阅登录独立卡片 ----
            provider.oauth?.let { oauth ->
                item(key = "oauth") {
                    val isSub = oauth.isSubscription
                    val tint = if (isSub) palette.accent else palette.borderAccent
                    ModelsCard(accent = login != null || (configured && provider.usingOAuth)) {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tint.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (isSub) Icons.Filled.VerifiedUser else Icons.Filled.Language,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            oauth.name.ifBlank {
                                                if (isSub) tr("订阅账号登录", "Subscription Login") else tr("OAuth 网页登录", "OAuth Web Login")
                                            },
                                            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                            color = palette.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        TagPill(
                                            if (isSub) tr("订阅账号", "Subscription") else tr("网页授权", "OAuth"),
                                            tone = tint,
                                        )
                                    }
                                    Text(
                                        if (provider.usingOAuth) {
                                            tr("当前已通过账号授权连接，令牌由 Pi SDK 自动刷新", "Connected via account authorization; tokens refresh automatically")
                                        } else {
                                            tr("在浏览器中授权登录，无需手动复制密钥", "Authorize in your browser — no manual API key needed")
                                        },
                                        style = PiTheme.text.meta,
                                        color = palette.muted,
                                    )
                                }
                            }

                            val l = login
                            if (l == null) {
                                val ctaLabel = if (provider.usingOAuth) {
                                    tr("重新授权登录", "Re-authorize Account")
                                } else {
                                    oauth.loginLabel ?: (if (isSub) tr("用订阅账号登录", "Sign in with Subscription") else tr("通过网页授权登录", "Sign in via Browser"))
                                }
                                PrimaryAction(
                                    text = ctaLabel,
                                    fullWidth = true,
                                    onClick = { startOAuthLogin() },
                                )
                            } else {
                                LoginProgress(
                                    l = l,
                                    promptText = promptText,
                                    onPromptText = { promptText = it },
                                    onPastePrompt = {
                                        val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                        if (pasted.isNotEmpty()) promptText = pasted
                                    },
                                    onOpenUrl = { openExternalUrl(it) },
                                    onCopy = { value ->
                                        clipboard.setText(AnnotatedString(value))
                                        note = tr("已复制到剪贴板。", "Copied to clipboard.")
                                        noteWarning = false
                                    },
                                    onCopyAndOpen = { code, url ->
                                        clipboard.setText(AnnotatedString(code))
                                        note = tr("验证码已复制，正在打开验证页面…", "Code copied. Opening verification page…")
                                        noteWarning = false
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
                    }
                }
            }

            // ---- 3. API Key 独立卡片 ----
            if (provider.supportsApiKeyInput || provider.hasAmbientApiKey) {
                item(key = "apikey") {
                    ModelsCard {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.success.copy(alpha = 0.13f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Key,
                                        contentDescription = null,
                                        tint = palette.success,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            "API Key",
                                            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                            color = palette.text,
                                        )
                                        when {
                                            configured && provider.statusSource == "stored" && !provider.usingOAuth ->
                                                TagPill(tr("已保存在 auth.json", "Saved in auth.json"), tone = palette.success)
                                            configured && provider.statusSource == "environment" ->
                                                TagPill(tr("环境变量", "Environment"), tone = palette.success)
                                            configured && provider.statusSource == "runtime" ->
                                                TagPill(tr("临时 Key", "Runtime Key"), tone = palette.warning)
                                        }
                                    }
                                    Text(
                                        if (provider.supportsApiKeyInput) {
                                            tr("加密或按官方 auth.json 格式本地保存，不上传到第三方", "Saved locally to Pi's auth.json via the official SDK")
                                        } else {
                                            tr("该供应商通过环境变量或本机云凭证工作", "Configured via environment variables or local cloud credentials")
                                        },
                                        style = PiTheme.text.meta,
                                        color = palette.muted,
                                    )
                                }
                            }

                            if (provider.supportsApiKeyInput) {
                                LabeledField(
                                    label = if (configured && !provider.usingOAuth) {
                                        tr("更新 API Key", "Replace API Key")
                                    } else {
                                        tr("输入或粘贴 API Key", "Enter or Paste API Key")
                                    },
                                    value = key,
                                    placeholder = if (configured) {
                                        tr("已配置；粘贴新 Key 将覆盖现有凭据", "Configured; paste a new key to overwrite")
                                    } else {
                                        "sk-..."
                                    },
                                    secret = true,
                                    labelTrailing = {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(palette.accent.copy(alpha = 0.12f))
                                                .clickable {
                                                    val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                                    if (pasted.isNotEmpty()) {
                                                        key = pasted
                                                        note = tr("已从剪贴板填入 API Key，点下方按钮保存。", "Pasted API key from clipboard. Tap Save below.")
                                                        noteWarning = false
                                                    } else {
                                                        note = tr("剪贴板里没有可粘贴的文本。", "Clipboard is empty.")
                                                        noteWarning = true
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.ContentPaste,
                                                contentDescription = null,
                                                tint = palette.accent,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                tr("粘贴", "Paste"),
                                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                                color = palette.accent,
                                            )
                                        }
                                    },
                                    onChange = { key = it },
                                )

                                PrimaryAction(
                                    text = when {
                                        savingKey -> tr("正在保存…", "Saving…")
                                        configured && !provider.usingOAuth -> tr("更新 API Key", "Update API Key")
                                        else -> tr("保存 API Key", "Save API Key")
                                    },
                                    enabled = key.isNotBlank() && !savingKey,
                                    fullWidth = true,
                                    onClick = {
                                        val h = host() ?: run {
                                            note = tr("引擎没在运行。", "Engine is not running.")
                                            noteWarning = true
                                            return@PrimaryAction
                                        }
                                        scope.launch {
                                            savingKey = true
                                            runCatching { h.auth.setApiKey(provider.id, key.trim()) }
                                                .onSuccess {
                                                    key = ""
                                                    note = tr("API Key 已保存。", "API Key saved.")
                                                    noteWarning = false
                                                    onChanged()
                                                }
                                                .onFailure {
                                                    note = tr("保存失败：${it.message}", "Failed to save: ${it.message}")
                                                    noteWarning = true
                                                }
                                            savingKey = false
                                        }
                                    },
                                )
                            } else if (provider.hasAmbientApiKey && provider.oauth == null) {
                                Text(
                                    tr(
                                        "这个供应商通过环境变量或本机云凭证工作，不支持直接在此填写 API Key。",
                                        "This provider uses environment variables or local cloud credentials and does not accept a direct API key here.",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = palette.dim,
                                )
                            }
                        }
                    }
                }
            } else if (provider.oauth == null) {
                item(key = "no-auth-note") {
                    ModelsCard {
                        Text(
                            tr(
                                "这个供应商没有内置登录方式；请在下方「自定义端点与请求头」里填写地址与密钥。",
                                "This provider has no built-in login; configure its URL and key under Custom Endpoint & Headers below.",
                            ),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            modifier = Modifier.padding(ModelsCardPadding),
                        )
                    }
                }
            }

            // ---- 4. 高级配置与凭据管理 ----
            item(key = "advanced-actions") {
                val canLogout = configured && (provider.statusSource == "stored" || provider.usingOAuth)
                ModelsCard {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (custom != null) onEditCustom(custom) else onOverrideBuiltin()
                                }
                                .padding(horizontal = ModelsCardPadding, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = null,
                                tint = palette.muted,
                                modifier = Modifier.size(19.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        if (custom != null) tr("编辑自定义端点与请求头", "Edit Custom Endpoint & Headers")
                                        else tr("自定义端点与请求头", "Custom Endpoint & Headers"),
                                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                        color = palette.text,
                                    )
                                    if (custom != null) TagPill(tr("已自定义", "Customized"), tone = palette.accent)
                                }
                                Text(
                                    tr("通过 models.json 覆盖 Base URL、请求头或追加自定义模型", "Override base URL, headers, or add custom models via models.json"),
                                    style = PiTheme.text.meta,
                                    color = palette.dim,
                                )
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = palette.dim,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        if (configured && provider.statusSource == "environment") {
                            ModelsDivider()
                            Text(
                                tr("当前凭据来自环境变量，需在环境变量配置中修改。", "Credentials come from environment variables and must be updated there."),
                                style = PiTheme.text.meta,
                                color = palette.dim,
                                modifier = Modifier.padding(ModelsCardPadding),
                            )
                        }

                        if (canLogout) {
                            ModelsDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { confirmLogout = true }
                                    .padding(horizontal = ModelsCardPadding, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Logout,
                                    contentDescription = null,
                                    tint = palette.error,
                                    modifier = Modifier.size(19.dp),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        if (provider.usingOAuth) tr("退出登录", "Sign Out") else tr("移除已保存凭据", "Remove Saved Credentials"),
                                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                        color = palette.error,
                                    )
                                    Text(
                                        tr("从 auth.json 中清除该供应商的登录状态或 API Key", "Removes this provider's token or API key from auth.json"),
                                        style = PiTheme.text.meta,
                                        color = palette.dim,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- 5. 模型目录 ----
            item(key = "models-head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelsSectionLabel(
                        tr("可用模型（${allModels.size}）", "Models (${allModels.size})"),
                        subtitle = when {
                            !configured && allModels.isNotEmpty() -> tr("连接该供应商后，点任意模型即可切换或设为默认。", "Connect this provider to select any model below.")
                            allModels.isEmpty() -> tr("目录里暂无模型——请刷新模型目录，或通过自定义端点添加。", "No models in catalog — refresh catalog or add via custom endpoint.")
                            else -> null
                        },
                    )
                    if (allModels.size > PROVIDER_MODEL_SEARCH_THRESHOLD) {
                        ModelsSearchBar(
                            value = modelQuery,
                            onValueChange = { modelQuery = it },
                            placeholder = tr("搜索该供应商的模型", "Search provider models"),
                            modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        )
                    }
                }
            }

            val filtered = searchModels(allModels, emptyMap(), modelQuery)
            if (filtered.isNotEmpty()) {
                item(key = "models-card") {
                    ModelsCard {
                        Column {
                            filtered.forEachIndexed { idx, m ->
                                if (idx > 0) ModelsDivider()
                                ModelRow(
                                    model = m,
                                    current = current?.let { m.sameAs(it.first, it.second) } == true,
                                    selected = picked?.id == m.id,
                                    onClick = if (configured) ({ picked = m; pickError = null }) else null,
                                )
                            }
                        }
                    }
                }
            } else if (modelQuery.isNotBlank()) {
                item(key = "m:none") {
                    Text(
                        tr("没有匹配「${modelQuery.trim()}」的模型。", "No models matching \"${modelQuery.trim()}\"."),
                        style = PiTheme.text.meta,
                        color = palette.dim,
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
                    val h = host() ?: run {
                        pickError = tr("引擎没在运行。", "Engine is not running.")
                        return@UseBar
                    }
                    scope.launch {
                        working = true
                        runCatching { h.selectModel(m.provider.ifBlank { provider.id }, m.id, use.persist) }
                            .onSuccess {
                                note = if (use.persist) {
                                    tr("已设为默认并切换到 ${m.title}。", "Set as default and switched to ${m.title}.")
                                } else {
                                    tr("已切换到 ${m.title}。", "Switched to ${m.title}.")
                                }
                                noteWarning = false
                                picked = null
                                onChanged()
                            }
                            .onFailure { pickError = tr("切换失败：${it.message}", "Switch failed: ${it.message}") }
                        working = false
                    }
                },
                onCancel = { picked = null; pickError = null },
            )
        }
    }

    if (confirmLogout) {
        PiSettingsDialog(
            onDismissRequest = { confirmLogout = false },
            title = if (provider.usingOAuth) {
                tr("退出「${facts.title}」？", "Sign out of \"${facts.title}\"?")
            } else {
                tr("移除「${facts.title}」的凭据？", "Remove credentials for \"${facts.title}\"?")
            },
            body = tr("这会从本地 auth.json 删除该供应商的登录令牌或 API Key，之后可以随时重新登录或填写。", "Removes this provider's OAuth token or API key from auth.json. You can reconnect anytime."),
            confirmationLabel = if (provider.usingOAuth) tr("退出登录", "Sign Out") else tr("移除", "Remove"),
            confirmationTone = palette.error,
            onConfirm = {
                confirmLogout = false
                val h = host() ?: return@PiSettingsDialog
                scope.launch {
                    runCatching { h.auth.logout(provider.id) }
                        .onSuccess {
                            note = tr("已清除该供应商凭据。", "Credentials removed.")
                            noteWarning = false
                            onChanged()
                        }
                        .onFailure {
                            note = tr("操作失败：${it.message}", "Failed: ${it.message}")
                            noteWarning = true
                        }
                }
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmLogout = false },
        )
    }
}

@Composable
private fun LoginProgress(
    l: LoginState,
    promptText: String,
    onPromptText: (String) -> Unit,
    onPastePrompt: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCopyAndOpen: (code: String, url: String) -> Unit,
    onAnswer: (HostAuth.AuthPrompt, String) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = PiTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (l.error == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent,
                )
                Text(
                    l.lines.lastOrNull() ?: tr("正在等待浏览器授权…", "Waiting for browser authorization…"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.text,
                )
            }
        }
        if (l.lines.size > 1) {
            l.lines.dropLast(1).forEach {
                Text(it, style = PiTheme.text.meta, color = palette.muted)
            }
        }
        if (l.links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                l.links.forEach { link ->
                    PiSettingsChip(
                        text = link.label?.takeIf { it.isNotBlank() } ?: tr("打开说明", "Open Guide"),
                        onClick = { onOpenUrl(link.url) },
                    )
                }
            }
        }
        l.code?.let { code ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = PiShapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(0.5.dp, palette.borderAccent.copy(alpha = 0.55f)),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("设备验证码", "Device Verification Code"), style = PiTheme.text.meta, color = palette.muted)
                    Text(
                        code,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = PiTheme.text.mono.fontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = palette.accent,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val url = l.url
                        if (url != null) {
                            PrimaryAction(tr("复制并打开浏览器", "Copy & Open Browser"), onClick = { onCopyAndOpen(code, url) })
                        }
                        SecondaryAction(tr("仅复制码", "Copy Code"), onClick = { onCopy(code) })
                    }
                }
            }
        }
        l.url?.let { url ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (l.code == null) {
                    PrimaryAction(tr("打开浏览器授权", "Open Browser"), onClick = { onOpenUrl(url) })
                } else {
                    SecondaryAction(tr("打开验证页", "Open Page"), onClick = { onOpenUrl(url) })
                }
                SecondaryAction(tr("复制链接", "Copy Link"), onClick = { onCopy(url) })
            }
        }
        l.browserWarning?.let { warn ->
            Text(warn, style = PiTheme.text.meta, color = palette.warning)
        }
        l.prompt?.let { pr ->
            if (pr.isSelect) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pr.message.isNotBlank()) {
                        Text(pr.message, style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium), color = palette.text)
                    }
                    pr.options.forEach { opt ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAnswer(pr, opt.id) },
                            shape = PiShapes.card,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.65f)),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(opt.label, style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium), color = palette.text)
                                opt.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                    Text(desc, style = PiTheme.text.meta, color = palette.muted)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = if (pr.isManualCode) {
                        pr.message.ifBlank {
                            tr(
                                "如果浏览器未自动跳回，请粘贴完整回调链接或授权码：",
                                "If the browser didn't redirect back automatically, paste the callback URL or authorization code:",
                            )
                        }
                    } else {
                        pr.message
                    }
                    if (label.isNotBlank()) {
                        Text(label, style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium), color = palette.text)
                    }
                    if (pr.isManualCode) {
                        Text(
                            tr("浏览器自动回跳成功后会自动继续，无需手动粘贴。", "If the browser redirects back automatically, sign-in will continue without pasting."),
                            style = PiTheme.text.meta,
                            color = palette.dim,
                        )
                    }
                    PiEditorField(
                        value = promptText,
                        onValueChange = onPromptText,
                        placeholder = pr.placeholder ?: if (pr.isManualCode) tr("粘贴回调 URL 或授权码", "Paste callback URL or code") else null,
                        visualTransformation = if (pr.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        trailing = {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(palette.accent.copy(alpha = 0.12f))
                                    .clickable(onClick = onPastePrompt)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    tr("粘贴", "Paste"),
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                    color = palette.accent,
                                )
                            }
                        },
                    )
                    val canSubmit = pr.allowEmpty || promptText.isNotBlank()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryAction(
                            if (promptText.isBlank() && pr.allowEmpty) tr("使用默认继续", "Continue with Default") else tr("提交", "Submit"),
                            enabled = canSubmit,
                            onClick = { onAnswer(pr, promptText) },
                        )
                    }
                }
            }
        }
        l.error?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = palette.error.copy(alpha = 0.10f),
                border = BorderStroke(0.5.dp, palette.error.copy(alpha = 0.35f)),
            ) {
                Text(
                    it,
                    style = PiTheme.text.meta,
                    color = palette.error,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        SecondaryAction(
            text = if (l.error == null) tr("取消登录", "Cancel Sign-In") else tr("关闭", "Close"),
            onClick = onCancel,
        )
    }
}

private const val PROVIDER_MODEL_SEARCH_THRESHOLD = 10
