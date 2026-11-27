package app.pi.ui.settings

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.packages.PiMcpConfig
import app.pi.packages.PiMcpStatus
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val mcpPrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

private enum class HttpAuthMode { OAuth, Token, Provider }

@Composable
private fun McpFormCard(content: @Composable () -> Unit) {
    PiSettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

/** 统一短标题 + 可选右端操作（如粘贴）+ 密码显隐 + 下方换行说明，彻底告别标题截断。 */
@Composable
private fun McpLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 6,
    secret: Boolean = false,
    enabled: Boolean = true,
    labelTrailing: (@Composable () -> Unit)? = null,
) {
    var revealed by remember(secret) { mutableStateOf(false) }
    val fieldBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val fieldBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            labelTrailing?.invoke()
        }
        PiEditorField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = singleLine,
            maxLines = maxLines,
            enabled = enabled,
            height = if (singleLine) PiNumberFieldHeight else null,
            minHeight = if (singleLine) null else PiTextFieldMinHeight,
            contentPadding = if (singleLine) PaddingValuesSingleLine else PiFieldPaddingBlock,
            container = fieldBg,
            idleBorder = fieldBorder,
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            trailing = if (secret) {
                {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { revealed = !revealed },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) tr("隐藏", "Hide") else tr("显示", "Show"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                null
            },
        )
        if (!supporting.isNullOrBlank()) {
            Text(
                text = supporting,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PaddingValuesSingleLine = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)

@Composable
private fun McpInlinePasteChip(onPaste: (String) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            if (clip.isNotEmpty()) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPaste(clip)
            }
        },
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Filled.ContentPaste,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                tr("粘贴", "Paste"),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 编辑 / 新增 MCP 服务器表单：
 *  - 分组卡片层级 + 清晰的远程 HTTP 认证方式三段式切换（OAuth 浏览器登录 / 访问令牌 / 复用模型供应商凭据）；
 *  - 短标题 + 换行说明（杜绝 1 行标签截断），支持一键粘贴与密钥显隐；
 *  - 高级项默认折叠、吸底统一操作栏，全部字段与校验严格对齐 Pi 1.6.1 SDK。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun McpEditSheet(
    initial: PiMcpConfig.Draft,
    state: PiMcpStatus.Server?,
    oauthSignedIn: Boolean = false,
    isNew: Boolean,
    otherNames: List<String>,
    isProject: Boolean = false,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onSave: (PiMcpConfig.Draft) -> Unit,
    onLogin: (() -> Unit)?,
    onLogout: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    var draft by remember(initial) { mutableStateOf(initial) }
    var authMode by remember(initial) {
        val initialMode = when {
            initial.authProvider.isNotBlank() -> HttpAuthMode.Provider
            initial.bearer.isNotBlank() ||
                PiMcpConfig.parseHeaders(initial.headers).map.keys.any { it.equals("Authorization", ignoreCase = true) } ->
                HttpAuthMode.Token
            else -> HttpAuthMode.OAuth
        }
        mutableStateOf(initialMode)
    }
    var showProblem by remember(initial) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var oauthAdvanced by remember(initial) { mutableStateOf(initial.hasOauth()) }
    var advanced by remember(initial) { mutableStateOf(initial.hasNonOauthAdvanced()) }

    val effectiveDraft = remember(draft, authMode) {
        if (!draft.http) draft
        else when (authMode) {
            HttpAuthMode.OAuth -> draft.copy(
                bearer = "",
                authProvider = "",
                headers = stripAuthorizationHeader(draft.headers),
            )
            HttpAuthMode.Token -> draft.copy(
                authProvider = "",
                oauthClientId = "",
                oauthClientSecret = "",
                oauthCallbackPort = "",
                oauthCallbackUrl = "",
                oauthScope = "",
                oauthClientName = "",
                oauthAuthServerUrl = "",
                oauthClientRegistration = "",
            )
            HttpAuthMode.Provider -> draft.copy(
                bearer = "",
                headers = stripAuthorizationHeader(draft.headers),
                oauthClientId = "",
                oauthClientSecret = "",
                oauthCallbackPort = "",
                oauthCallbackUrl = "",
                oauthScope = "",
                oauthClientName = "",
                oauthAuthServerUrl = "",
                oauthClientRegistration = "",
            )
        }
    }

    val problem = PiMcpConfig.check(effectiveDraft, otherNames, isProject = isProject)
    val urlChanged = !isNew && draft.url.trim() != initial.url.trim()
    val fieldBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val fieldBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部标题区
            Column(
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = if (isNew) tr("新增 MCP 服务器", "Add MCP Server") else tr("编辑 MCP 服务器", "Edit MCP Server"),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (isProject) {
                        tr("写入本项目 .pi/mcp.json 并由 Pi SDK 实时校验", "Writes to project .pi/mcp.json with live Pi SDK validation")
                    } else {
                        tr("写入全局 mcp.json 并由 Pi SDK 实时校验", "Writes to global mcp.json with live Pi SDK validation")
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 1. 基本信息与传输方式
            PiSettingsSectionHeader(tr("基本信息与连接", "Server & Connection"))
            McpFormCard {
                McpLabeledField(
                    label = tr("服务器名称", "Server Name"),
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    placeholder = "my-server",
                    supporting = tr("仅支持字母、数字、下划线 _ 和连字符 -", "Letters, numbers, underscores _ and hyphens - only"),
                )
                McpSegmentedTrack(
                    options = listOf(tr("本地命令 (STDIO)", "Local (STDIO)"), tr("远程服务 (HTTP)", "Remote (HTTP)")),
                    selectedIndex = if (draft.http) 1 else 0,
                    onSelect = { draft = draft.copy(http = it == 1) },
                )
                if (draft.http) {
                    McpLabeledField(
                        label = tr("服务网址", "Server URL"),
                        value = draft.url,
                        onValueChange = { draft = draft.copy(url = it) },
                        placeholder = "https://example.com/mcp",
                        supporting = tr("使用 Streamable HTTP 地址（通常以 /mcp 结尾；不支持旧 SSE）", "Streamable HTTP endpoint (typically ends with /mcp; legacy SSE unsupported)"),
                        labelTrailing = { McpInlinePasteChip { draft = draft.copy(url = it) } },
                    )
                } else {
                    McpLabeledField(
                        label = tr("启动命令", "Executable Command"),
                        value = draft.command,
                        onValueChange = { draft = draft.copy(command = it) },
                        placeholder = "npx",
                        supporting = tr("只填单个可执行程序名（参数放在下方）", "Single executable name; pass arguments below"),
                    )
                    McpLabeledField(
                        label = tr("命令参数", "Arguments"),
                        value = draft.args,
                        onValueChange = { draft = draft.copy(args = it) },
                        placeholder = "-y\n@modelcontextprotocol/server-filesystem\n/workspace",
                        supporting = tr("每行一个参数，无需额外引号", "One argument per line, no quotes needed"),
                        singleLine = false,
                        maxLines = 6,
                    )
                    McpLabeledField(
                        label = tr("环境变量", "Environment Variables"),
                        value = draft.env,
                        onValueChange = { draft = draft.copy(env = it) },
                        placeholder = "API_KEY=\${MY_API_KEY}",
                        supporting = tr("每行 名称=值；支持 \${变量名} 或 !命令动态读取", "NAME=VALUE per line; supports \${VAR} or !command"),
                        singleLine = false,
                        maxLines = 6,
                    )
                }
            }

            // 2. 远程 HTTP 认证方式（清晰三选一：OAuth / 访问令牌 / 复用模型供应商）
            if (draft.http) {
                PiSettingsSectionHeader(tr("认证方式", "Authentication"))
                McpFormCard {
                    val modes = if (isProject && authMode != HttpAuthMode.Provider) {
                        listOf(HttpAuthMode.OAuth, HttpAuthMode.Token)
                    } else {
                        listOf(HttpAuthMode.OAuth, HttpAuthMode.Token, HttpAuthMode.Provider)
                    }
                    val modeLabels = modes.map { mode ->
                        when (mode) {
                            HttpAuthMode.OAuth -> tr("OAuth 登录", "OAuth")
                            HttpAuthMode.Token -> tr("访问令牌", "Token / Header")
                            HttpAuthMode.Provider -> tr("复用供应商", "Provider Auth")
                        }
                    }
                    val modeIndex = modes.indexOf(authMode).coerceAtLeast(0)
                    McpSegmentedTrack(
                        options = modeLabels,
                        selectedIndex = modeIndex,
                        onSelect = { idx -> authMode = modes[idx] },
                    )

                    when (authMode) {
                        HttpAuthMode.OAuth -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val dotColor = when {
                                    state?.needsAuth == true -> palette.warning
                                    oauthSignedIn || state?.connected == true -> palette.success
                                    else -> palette.accent
                                }
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )
                                Text(
                                    text = when {
                                        state?.needsAuth == true -> tr("服务器要求浏览器授权（待登录）", "Server requires browser OAuth sign-in")
                                        oauthSignedIn -> tr("已保存 OAuth 凭据（由 Pi SDK 自动刷新）", "OAuth credentials stored (auto-refreshed by Pi SDK)")
                                        isNew -> tr("免填静态密钥：保存后点击「OAuth 登录」即可在浏览器完成授权", "No static key needed: save, then tap Sign In to authorize via browser")
                                        else -> tr("无需静态令牌：支持 PKCE 与自动刷新，需要授权时点下方登录", "Supports browser OAuth with PKCE & automatic token refresh")
                                    },
                                    style = PiTheme.text.meta,
                                    color = if (state?.needsAuth == true) palette.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (urlChanged) {
                                Text(
                                    text = tr("你修改了服务网址，请先点击底部「保存配置」再发起 OAuth 登录。", "URL changed — tap Save below before starting OAuth sign-in."),
                                    style = PiTheme.text.meta,
                                    color = palette.warning,
                                )
                            }
                            if (!isNew && onLogin != null && onLogout != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (state?.needsAuth == true || !oauthSignedIn) {
                                        PiPrimaryButton(
                                            label = tr("OAuth 登录", "Sign In via OAuth"),
                                            enabled = !urlChanged,
                                            compact = true,
                                            modifier = Modifier.weight(1f),
                                            onClick = onLogin,
                                        )
                                    } else {
                                        PiSecondaryButton(
                                            label = tr("重新授权", "Re-authorize"),
                                            enabled = !urlChanged,
                                            compact = true,
                                            modifier = Modifier.weight(1f),
                                            onClick = onLogin,
                                        )
                                    }
                                    if (oauthSignedIn || state?.needsAuth == false) {
                                        PiSecondaryButton(
                                            label = tr("退出登录", "Sign Out"),
                                            compact = true,
                                            onClick = onLogout,
                                        )
                                    }
                                }
                            }
                            // 折叠的 OAuth 预注册客户端与回调参数
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
                                border = BorderStroke(PiSpacing.hairlineThin, fieldBorder),
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { oauthAdvanced = !oauthAdvanced }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                            Text(
                                                text = tr("OAuth 客户端与回调参数（可选）", "OAuth Client & Callback Options (Optional)"),
                                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = tr("默认自动登记；仅服务器要求预注册 Client ID 或固定端口时填写", "Auto by default; only set for pre-registered clients or fixed ports"),
                                                style = PiTheme.text.meta,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Icon(
                                            imageVector = if (oauthAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    if (oauthAdvanced) {
                                        HorizontalDivider(
                                            thickness = PiSpacing.hairlineThin,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        )
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            McpOauthFields(draft = draft, onChange = { draft = it })
                                        }
                                    }
                                }
                            }
                        }

                        HttpAuthMode.Token -> {
                            McpLabeledField(
                                label = tr("访问令牌 (Bearer Token)", "Access Token (Bearer)"),
                                value = draft.bearer,
                                onValueChange = { draft = draft.copy(bearer = it) },
                                placeholder = "\${MCP_TOKEN}",
                                supporting = tr("直接粘贴令牌，或填 \${环境变量名} 自动注入 Authorization 头", "Paste token directly or use \${ENV_VAR} for Authorization Bearer"),
                                secret = true,
                                labelTrailing = { McpInlinePasteChip { draft = draft.copy(bearer = it) } },
                            )
                            McpLabeledField(
                                label = tr("自定义请求头", "Custom Headers"),
                                value = draft.headers,
                                onValueChange = { draft = draft.copy(headers = it) },
                                placeholder = "X-Api-Key: \${MY_API_KEY}",
                                supporting = tr("每行 名称: 值；值中支持 \${变量名}", "Name: Value per line; supports \${VAR}"),
                                singleLine = false,
                                maxLines = 6,
                            )
                        }

                        HttpAuthMode.Provider -> {
                            McpLabeledField(
                                label = tr("模型供应商 ID", "Model Provider ID"),
                                value = draft.authProvider,
                                onValueChange = { draft = draft.copy(authProvider = it) },
                                enabled = !isProject || draft.authProvider.isNotBlank(),
                                placeholder = "anthropic",
                                supporting = if (isProject) {
                                    tr("出于安全限制，Pi SDK 仅允许在全局 mcp.json 中使用 auth.provider。", "For security, Pi SDK restricts auth.provider to global mcp.json.")
                                } else {
                                    tr("自动携带该模型供应商已保存的凭据作为 Bearer Token（对应 auth.provider）", "Sends the stored credential of this model provider as Bearer token")
                                },
                            )
                            if (!isProject) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    listOf("anthropic", "openai", "github-copilot", "google").forEach { id ->
                                        PiSettingsChip(
                                            text = id,
                                            active = draft.authProvider.trim() == id,
                                            onClick = { draft = draft.copy(authProvider = id) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state != null) {
                        HorizontalDivider(
                            thickness = PiSpacing.hairlineThin,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = PiMcpStatus.stateLabel(state),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = when {
                                    state.connected -> palette.success
                                    !state.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                    state.needsAuth -> palette.warning
                                    else -> palette.error
                                },
                            )
                            state.error?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
                        }
                    }
                }
            } else if (state != null) {
                PiSettingsSectionHeader(tr("最近检测状态", "Last Check Status"))
                McpFormCard {
                    Text(
                        text = PiMcpStatus.stateLabel(state),
                        style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                        color = when {
                            state.connected -> palette.success
                            !state.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> palette.error
                        },
                    )
                    state.error?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
                }
            }

            // 3. 工具暴露方式
            PiSettingsSectionHeader(tr("工具暴露方式", "Tool Exposure"))
            McpFormCard {
                val rows = PiMcpConfig.EXPOSURES.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            pair.forEach { option ->
                                val selected = draft.exposure == option
                                val subtitle = when (option) {
                                    "codemode" -> tr("0 提示词 · 推荐", "0 prompt · Recommended")
                                    "deferred" -> tr("按需检索加载", "Discover on demand")
                                    "direct" -> tr("全量直接暴露", "All tools direct")
                                    "hidden" -> tr("连接但不暴露", "Connect only")
                                    else -> ""
                                }
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        draft = draft.copy(exposure = option)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) palette.accent.copy(alpha = 0.11f) else fieldBg,
                                    border = BorderStroke(
                                        width = if (selected) 1.dp else PiSpacing.hairlineThin,
                                        color = if (selected) palette.accent.copy(alpha = 0.60f) else fieldBorder,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            mcpExposureShortLabel(option),
                                            style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (selected) palette.accent else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                        )
                                        Text(
                                            subtitle,
                                            style = PiTheme.text.meta,
                                            color = if (selected) palette.accent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    mcpExposureHelp(draft.exposure),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 4. 高级选项（可折叠旗舰卡片）
            PiSettingsCard(
                border = BorderStroke(
                    PiSpacing.hairlineThin,
                    if (advanced) palette.accent.copy(alpha = 0.42f) else palette.borderMuted.copy(alpha = 0.45f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advanced = !advanced }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = tr("高级选项", "Advanced Options"),
                            style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (draft.http) {
                                tr("说明 · 超时 · 附加请求头 · 单工具暴露覆盖", "Description · Timeout · Extra Headers · Per-tool exposure")
                            } else {
                                tr("说明 · 超时 · 工作目录 · 单工具暴露覆盖", "Description · Timeout · Working Dir · Per-tool exposure")
                            },
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = if (advanced) palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (advanced) {
                    HorizontalDivider(
                        thickness = PiSpacing.hairlineThin,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        McpLabeledField(
                            label = tr("备注说明", "Description"),
                            value = draft.description,
                            onValueChange = { draft = draft.copy(description = it) },
                            placeholder = tr("可选，说明这个服务器提供什么能力", "Optional summary shown to you and the model"),
                        )
                        McpLabeledField(
                            label = tr("请求超时（秒）", "Timeout (seconds)"),
                            value = draft.timeoutSeconds,
                            onValueChange = { draft = draft.copy(timeoutSeconds = it) },
                            placeholder = "60",
                            supporting = tr("留空默认 60 秒", "Blank uses default 60s"),
                        )
                        if (!draft.http) {
                            McpLabeledField(
                                label = tr("工作目录", "Working Directory"),
                                value = draft.cwd,
                                onValueChange = { draft = draft.copy(cwd = it) },
                                placeholder = "/workspace/my-project",
                                supporting = tr("留空默认使用当前项目目录", "Blank defaults to active project directory"),
                            )
                        } else if (authMode != HttpAuthMode.Token) {
                            McpLabeledField(
                                label = tr("附加请求头（非 Authorization）", "Extra Headers (non-Authorization)"),
                                value = draft.headers,
                                onValueChange = { draft = draft.copy(headers = it) },
                                placeholder = "X-Workspace: default",
                                supporting = tr("每行 名称: 值；认证方式为 OAuth / 供应商时会自动过滤 Authorization 头", "Name: Value per line; Authorization header is omitted in OAuth / Provider modes"),
                                singleLine = false,
                                maxLines = 5,
                            )
                        }
                        if (state != null && state.tools.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    tr(
                                        "已发现的工具（点一下循环：沿用 → 直接 → 检索 → 代码模式 → 隐藏）",
                                        "Discovered tools (tap to cycle: inherit → direct → deferred → codemode → hidden)",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val draftExposureMap = remember(draft.toolExposure) {
                                    draft.toolExposure.lines()
                                        .mapNotNull { line ->
                                            val cut = line.indexOf('=')
                                            if (cut <= 0) null
                                            else line.substring(0, cut).trim() to line.substring(cut + 1).trim()
                                        }
                                        .toMap()
                                }
                                val cycleOrder = remember { listOf("direct", "deferred", "codemode", "hidden") }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    state.tools.forEach { tool ->
                                        val draftMode = draftExposureMap[tool]
                                        val current = draftMode ?: state.toolExposure[tool]
                                        val inDraft = draftMode != null
                                        PiSettingsChip(
                                            text = if (current == null) tool else "$tool · ${mcpExposureShortLabel(current)}",
                                            active = inDraft || current != null,
                                            onClick = {
                                                val nextMode = when (draftMode) {
                                                    null -> cycleOrder.first()
                                                    else -> {
                                                        val idx = cycleOrder.indexOf(draftMode)
                                                        if (idx < 0 || idx == cycleOrder.lastIndex) null else cycleOrder[idx + 1]
                                                    }
                                                }
                                                val kept = draft.toolExposure.lines()
                                                    .map { it.trim() }
                                                    .filter { it.isNotEmpty() && it.substringBefore('=').trim() != tool }
                                                val updated = if (nextMode == null) kept else kept + "$tool=$nextMode"
                                                draft = draft.copy(toolExposure = updated.joinToString("\n"))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        McpLabeledField(
                            label = tr("单工具暴露覆盖", "Per-Tool Exposure Overrides"),
                            value = draft.toolExposure,
                            onValueChange = { draft = draft.copy(toolExposure = it) },
                            placeholder = "search_code=direct\ndelete_*=hidden",
                            supporting = tr("每行 工具名=暴露方式，支持通配符 *", "tool=mode per line, supports * wildcards"),
                            singleLine = false,
                            maxLines = 6,
                        )
                    }
                }
            }

            if (showProblem && problem != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.error.copy(alpha = 0.08f),
                    border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
                ) {
                    Text(problem, style = PiTheme.text.meta, color = palette.error, modifier = Modifier.padding(11.dp))
                }
            }
            saveError?.takeIf { it.isNotBlank() }?.let { err ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.error.copy(alpha = 0.08f),
                    border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
                ) {
                    Text(err, style = PiTheme.text.meta, color = palette.error, modifier = Modifier.padding(11.dp))
                }
            }
        }

        // ---- 吸底操作栏 ----
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
                if (onDelete != null) {
                    PiSecondaryButton(
                        label = tr("删除", "Delete"),
                        tone = palette.error,
                        onClick = { confirmDelete = true },
                    )
                }
                PiSecondaryButton(
                    label = tr("取消", "Cancel"),
                    onClick = onDismiss,
                )
                PiPrimaryButton(
                    label = tr("保存配置", "Save"),
                    modifier = Modifier.weight(1f),
                    onClick = { if (problem == null) onSave(effectiveDraft) else showProblem = true },
                )
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        PiSettingsDialog(
            onDismissRequest = { confirmDelete = false },
            title = tr("删除「${initial.name}」？", "Delete \"${initial.name}\"?"),
            body = tr("只会从这份 mcp.json 里去掉这一条，其他服务器不受影响。", "Removes only this entry from mcp.json; other servers are unaffected."),
            confirmationLabel = tr("删除", "Delete"),
            confirmationTone = palette.error,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmDelete = false },
        )
    }
}

private fun stripAuthorizationHeader(raw: String): String =
    raw.lines()
        .filter { line ->
            val key = line.substringBefore(':', "").trim()
            !key.equals("Authorization", ignoreCase = true)
        }
        .joinToString("\n")
        .trim()

private fun PiMcpConfig.Draft.hasNonOauthAdvanced(): Boolean =
    description.isNotBlank() || timeoutSeconds.isNotBlank() || cwd.isNotBlank() || toolExposure.isNotBlank()

private fun PiMcpConfig.Draft.hasOauth(): Boolean =
    oauthClientRegistration.isNotBlank() || listOf(
        oauthClientId, oauthClientSecret, oauthCallbackPort, oauthCallbackUrl, oauthScope, oauthClientName, oauthAuthServerUrl,
    ).any { it.isNotBlank() }

/** OAuth 高级参数：短标题 + 换行说明，仅在预注册客户端 / 固定端口 / 指定登记模式时填写。 */
@Composable
private fun McpOauthFields(draft: PiMcpConfig.Draft, onChange: (PiMcpConfig.Draft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        McpLabeledField(
            label = tr("客户端 ID", "Client ID"),
            value = draft.oauthClientId,
            onValueChange = { onChange(draft.copy(oauthClientId = it)) },
            placeholder = "client_id",
            supporting = tr("仅预注册客户端需要；留空将使用 CIMD / DCR 自动登记", "Pre-registered client ID only; blank uses auto registration"),
        )
        McpLabeledField(
            label = tr("客户端密钥", "Client Secret"),
            value = draft.oauthClientSecret,
            onValueChange = { onChange(draft.copy(oauthClientSecret = it)) },
            placeholder = "\${OAUTH_CLIENT_SECRET}",
            supporting = tr("可选，支持直接填或写 \${环境变量名}", "Optional; supports \${ENV_VAR}"),
            secret = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McpLabeledField(
                label = tr("回调端口", "Callback Port"),
                value = draft.oauthCallbackPort,
                onValueChange = { onChange(draft.copy(oauthCallbackPort = it)) },
                placeholder = "8765",
                modifier = Modifier.weight(1f),
            )
            McpLabeledField(
                label = tr("客户端名称", "Client Name"),
                value = draft.oauthClientName,
                onValueChange = { onChange(draft.copy(oauthClientName = it)) },
                placeholder = "pi",
                modifier = Modifier.weight(1f),
            )
        }
        McpLabeledField(
            label = tr("回调地址", "Callback URL"),
            value = draft.oauthCallbackUrl,
            onValueChange = { onChange(draft.copy(oauthCallbackUrl = it)) },
            placeholder = "http://127.0.0.1:8765/callback",
            supporting = tr("可选，仅允许 localhost / 127.0.0.1 / [::1]", "Optional; loopback hosts only"),
        )
        McpLabeledField(
            label = tr("权限范围 (Scope)", "OAuth Scope"),
            value = draft.oauthScope,
            onValueChange = { onChange(draft.copy(oauthScope = it)) },
            placeholder = "read write",
            supporting = tr("空格分隔，仅服务器未宣告默认 scope 时填写", "Space-separated; only if server omits default scope"),
        )
        McpLabeledField(
            label = tr("授权元数据地址", "Auth Server Metadata URL"),
            value = draft.oauthAuthServerUrl,
            onValueChange = { onChange(draft.copy(oauthAuthServerUrl = it)) },
            placeholder = "https://auth.example.com/.well-known/oauth-authorization-server",
            supporting = tr("仅服务器宣告错误元数据时覆盖（必须 https）", "Override only if server advertises wrong metadata (https)"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                tr("客户端登记方式", "Client Registration Mode"),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            )
            val regValues = listOf("", "dcr", "cimd")
            val regIndex = regValues.indexOf(draft.oauthClientRegistration).coerceAtLeast(0)
            McpSegmentedTrack(
                options = listOf(tr("自动", "Auto"), "DCR", "CIMD"),
                selectedIndex = regIndex,
                onSelect = { idx -> onChange(draft.copy(oauthClientRegistration = regValues[idx])) },
            )
        }
    }
}

/** 粘贴或选择 `.json` 文件导入别的客户端的配置，实时预览检测到的服务器，支持一键格式化 JSON。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpImportSheet(
    onDismiss: () -> Unit,
    onImport: (text: String, fallbackName: String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var text by remember { mutableStateOf("") }
    var fallbackName by remember { mutableStateOf("imported") }
    var readError by remember { mutableStateOf<String?>(null) }
    val palette = PiTheme.palette
    val fieldBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val fieldBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content.isNullOrBlank()) {
                readError = tr("所选文件为空", "Selected file is empty")
            } else {
                readError = null
                text = content
                val stem = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    ?.takeIf { it.isNotBlank() && it != "mcp" }
                if (stem != null) fallbackName = stem
            }
        }.onFailure { err ->
            readError = err.message ?: tr("读取文件失败", "Failed to read file")
        }
    }
    val preview = remember(text, fallbackName) { PiMcpConfig.previewImport(text, fallbackName) }

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                    bottom = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(tr("导入 MCP 配置", "Import MCP Config"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    tr("兼容 Claude Desktop、Cursor、OpenCode 与标准 mcp.json 格式，同名不覆盖。", "Compatible with Claude Desktop, Cursor, OpenCode, and standard mcp.json; existing names are kept."),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiSecondaryButton(
                    label = tr("选文件", "File"),
                    leadingIcon = Icons.Filled.FolderOpen,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
                PiSecondaryButton(
                    label = tr("粘贴", "Paste"),
                    leadingIcon = Icons.Filled.ContentPaste,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!clip.isNullOrBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            text = clip
                            readError = null
                        }
                    },
                )
                PiSecondaryButton(
                    label = tr("格式化", "Format"),
                    leadingIcon = Icons.Filled.AutoFixHigh,
                    compact = true,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = {
                        runCatching {
                            val el = Json.parseToJsonElement(text.trim())
                            text = mcpPrettyJson.encodeToString(JsonElement.serializer(), el)
                            readError = null
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }.onFailure {
                            readError = tr("JSON 语法有误，无法格式化", "Invalid JSON syntax; cannot format")
                        }
                    },
                )
            }
            PiEditorField(
                value = text,
                onValueChange = { text = it; readError = null },
                modifier = Modifier.fillMaxWidth(),
                label = tr("MCP JSON 内容", "MCP JSON"),
                placeholder = "{\n  \"mcpServers\": {\n    \"docs\": { \"url\": \"https://example.com/mcp\" }\n  }\n}",
                singleLine = false,
                maxLines = 14,
                minHeight = 168.dp,
                contentPadding = PiFieldPaddingBlock,
                container = fieldBg,
                idleBorder = fieldBorder,
            )
            if (preview.entries.size == 1 && preview.entries.first().name == fallbackName) {
                McpLabeledField(
                    label = tr("默认服务器名", "Default Server Name"),
                    value = fallbackName,
                    onValueChange = { fallbackName = it },
                    placeholder = "imported",
                )
            }
            readError?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
            preview.error?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
            if (preview.entries.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.accent.copy(alpha = 0.40f)),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            tr("识别到 ${preview.entries.size} 个服务器", "Detected ${preview.entries.size} server(s)"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.accent,
                        )
                        preview.entries.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    entry.name,
                                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    if (entry.isHttp) "HTTP" else "STDIO",
                                    style = PiTheme.text.monoSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
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
                val canImport = text.isNotBlank() && preview.error == null && preview.entries.isNotEmpty()
                PiPrimaryButton(
                    label = if (preview.entries.size > 1) tr("导入 ${preview.entries.size} 个", "Import ${preview.entries.size}")
                    else tr("导入", "Import"),
                    enabled = canImport,
                    modifier = Modifier.weight(1f),
                    onClick = { onImport(text, fallbackName.trim().ifEmpty { "imported" }) },
                )
            }
        }
    }
}

internal fun mcpExposureShortLabel(exposure: String): String = when (exposure) {
    "codemode" -> tr("代码模式", "Code Mode")
    "deferred" -> tr("按需检索", "Tool Search")
    "direct" -> tr("直接暴露", "Direct")
    "hidden" -> tr("隐藏", "Hidden")
    else -> exposure
}

internal fun mcpExposureHelp(exposure: String): String = when (exposure) {
    "codemode" -> tr("工具放进 codemode 脚本里调用，不占提示词（推荐）。", "Tools are invoked via codemode scripts with zero prompt overhead (recommended).")
    "deferred" -> tr("不在提示词里列出，模型要用时用 tool_search 检索。", "Not listed in the prompt; the model discovers tools on demand via tool_search.")
    "direct" -> tr("每个工具都作为独立工具直接给模型，最方便但最占上下文。", "Exposes every tool directly to the model — convenient, but uses the most context.")
    "hidden" -> tr("连接但不暴露任何工具。", "Connects to the server without exposing any tools.")
    else -> ""
}
