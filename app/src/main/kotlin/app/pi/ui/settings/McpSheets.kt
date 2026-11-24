package app.pi.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.packages.PiMcpConfig
import app.pi.packages.PiMcpStatus
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/** 编辑 / 新增 MCP 服务器表单。字段、校验、写盘形状全在 [PiMcpConfig]；这里只收集输入。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun McpEditSheet(
    initial: PiMcpConfig.Draft,
    state: PiMcpStatus.Server?,
    isNew: Boolean,
    otherNames: List<String>,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onSave: (PiMcpConfig.Draft) -> Unit,
    onLogin: (() -> Unit)?,
    onLogout: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showProblem by remember { mutableStateOf(false) }
    val problem = PiMcpConfig.check(draft, otherNames)

    PiSettingsSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
        ) {
            Text(
                if (isNew) tr("新增 MCP 服务器", "Add MCP Server") else tr("编辑 MCP 服务器", "Edit MCP Server"),
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PiEditorField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = tr("名字（字母、数字、_ 和 -）", "Name (letters, digits, _ and -)"),
                placeholder = "my-server",
                height = PiNumberFieldHeight,
            )
            app.pi.ui.PiSeg(
                options = listOf(tr("本地命令 (STDIO)", "Local Command (STDIO)"), tr("远程服务 (HTTP)", "Remote Server (HTTP)")),
                selectedIndex = if (draft.http) 1 else 0,
                onSelect = { draft = draft.copy(http = it == 1) },
            )
            if (draft.http) {
                PiEditorField(
                    value = draft.url,
                    onValueChange = { draft = draft.copy(url = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("网址（streamable HTTP，通常以 /mcp 结尾）", "URL (Streamable HTTP, usually ends with /mcp)"),
                    placeholder = "https://example.com/mcp",
                    height = PiNumberFieldHeight,
                )
                PiEditorField(
                    value = draft.headers,
                    onValueChange = { draft = draft.copy(headers = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("请求头（每行一个，名称: 值；值里可写 \${变量名}）", "Headers (one per line, Name: Value; supports \${VAR})"),
                    placeholder = "Authorization: Bearer \${DOCS_TOKEN}",
                    singleLine = false,
                    maxLines = 6,
                    minHeight = PiTextFieldMinHeight,
                    contentPadding = PiFieldPaddingBlock,
                )
                PiEditorField(
                    value = draft.bearer,
                    onValueChange = { draft = draft.copy(bearer = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("访问令牌（可选：直接粘贴，或写 \${变量}；留空且服务器要登录则走 OAuth）", "Access Token (optional: paste token or \${VAR}; leave blank for OAuth)"),
                    height = PiNumberFieldHeight,
                )
            } else {
                PiEditorField(
                    value = draft.command,
                    onValueChange = { draft = draft.copy(command = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("命令（只填一个可执行文件）", "Command (single executable)"),
                    placeholder = "npx",
                    height = PiNumberFieldHeight,
                )
                PiEditorField(
                    value = draft.args,
                    onValueChange = { draft = draft.copy(args = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("参数（每行一个）", "Arguments (one per line)"),
                    placeholder = "-y\n@modelcontextprotocol/server-memory",
                    singleLine = false,
                    maxLines = 8,
                    minHeight = PiTextFieldMinHeight,
                    contentPadding = PiFieldPaddingBlock,
                )
                PiEditorField(
                    value = draft.env,
                    onValueChange = { draft = draft.copy(env = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("环境变量（每行一个，名称=值；值可写 \${变量} 或整条 !命令）", "Environment Variables (one per line, NAME=VALUE; supports \${VAR} or !command)"),
                    placeholder = "API_KEY=\${MY_API_KEY}",
                    singleLine = false,
                    maxLines = 6,
                    minHeight = PiTextFieldMinHeight,
                    contentPadding = PiFieldPaddingBlock,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("工具暴露方式", "Tool Exposure"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val palette = PiTheme.palette
                val rows = PiMcpConfig.EXPOSURES.chunked(2)
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
                                onClick = { draft = draft.copy(exposure = option) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) {
                                    palette.accent.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                border = BorderStroke(
                                    width = if (selected) 1.dp else 0.5.dp,
                                    color = if (selected) palette.accent else palette.borderMuted.copy(alpha = 0.60f),
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                                        color = if (selected) palette.accent.copy(alpha = 0.85f) else palette.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    mcpExposureHelp(draft.exposure),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
            if (state != null) {
                Text(
                    PiMcpStatus.stateLabel(state),
                    style = PiTheme.text.meta,
                    color = if (state.connected || !state.enabled) PiTheme.palette.muted else MaterialTheme.colorScheme.error,
                )
                state.error?.let { Text(it, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.error) }
            }
            if (onLogin != null && onLogout != null) {
                Text(
                    tr("要账号授权的服务器：在浏览器里登录，令牌由 pi 保存。已经填了访问令牌的不需要。登录用的是已保存的配置，改了网址请先保存。", "For OAuth servers: sign in via browser and pi saves the token. Not needed if you entered an access token. Sign-in uses the saved config — save first if you changed the URL."),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                    PiSettingsChip(text = tr("登录 / 重新登录", "Sign In / Re-authenticate"), active = state?.needsAuth == true, onClick = onLogin)
                    PiSettingsChip(text = tr("退出登录", "Sign Out"), onClick = onLogout)
                }
            }
            var advanced by remember { mutableStateOf(draft.hasAdvanced()) }
            PiSettingsChip(
                text = if (advanced) tr("收起高级选项", "Hide Advanced") else tr("高级选项（说明、超时、单个工具、OAuth…）", "Advanced (description, timeout, per-tool, OAuth…)"),
                active = advanced,
                onClick = { advanced = !advanced },
            )
            if (advanced) {
                PiEditorField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("说明（可选，给你自己看）", "Description (optional, for your reference)"),
                    height = PiNumberFieldHeight,
                    textStyle = PiTheme.text.meta,
                )
                PiEditorField(
                    value = draft.timeoutSeconds,
                    onValueChange = { draft = draft.copy(timeoutSeconds = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("超时（秒，留空 = 60）", "Timeout (seconds, blank = 60)"),
                    placeholder = "60",
                    height = PiNumberFieldHeight,
                )
                if (!draft.http) {
                    PiEditorField(
                        value = draft.cwd,
                        onValueChange = { draft = draft.copy(cwd = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("工作目录（可选；相对路径按会话目录，~/ 是家目录）", "Working Directory (optional; relative to session dir, ~/ for home)"),
                        height = PiNumberFieldHeight,
                    )
                }
                if (draft.http) {
                    McpOauthFields(draft) { draft = it }
                }
                if (state != null && state.tools.isNotEmpty()) {
                    Text(
                        tr("这个服务器的工具（${state.tools.size}）。点一个，会在下面加一行（默认 direct），可改成 codemode / deferred / hidden：", "Tools on this server (${state.tools.size}). Tap one to add an override below (default direct; change to codemode / deferred / hidden):"),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                    val draftExposureMap = remember(draft.toolExposure) {
                        draft.toolExposure.lines()
                            .mapNotNull { line ->
                                val trimmed = line.trim()
                                if ('=' !in trimmed) return@mapNotNull null
                                val k = trimmed.substringBefore('=').trim()
                                val v = trimmed.substringAfter('=').trim()
                                if (k.isEmpty() || v.isEmpty()) null else k to v
                            }
                            .toMap()
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                    ) {
                        state.tools.forEach { tool ->
                            val current = draftExposureMap[tool] ?: state.toolExposure[tool]
                            val inDraft = tool in draftExposureMap
                            PiSettingsChip(
                                text = if (current == null) tool else "$tool · ${mcpExposureShortLabel(current)}",
                                active = inDraft || current != null,
                                onClick = {
                                    if (!inDraft) {
                                        val prefix = draft.toolExposure.trimEnd().let { if (it.isEmpty()) "" else "$it\n" }
                                        draft = draft.copy(toolExposure = "$prefix$tool=direct")
                                    } else {
                                        val remaining = draft.toolExposure.lines()
                                            .filterNot { it.substringBefore('=').trim() == tool }
                                            .joinToString("\n")
                                        draft = draft.copy(toolExposure = remaining)
                                    }
                                },
                            )
                        }
                    }
                }
                PiEditorField(
                    value = draft.toolExposure,
                    onValueChange = { draft = draft.copy(toolExposure = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("单个工具另行设置（每行 工具名=暴露方式，名字可带 * ）", "Per-Tool Exposure Overrides (tool=mode per line, supports *)"),
                    placeholder = "search_code=direct\ndelete_*=hidden",
                    singleLine = false,
                    maxLines = 6,
                    minHeight = PiTextFieldMinHeight,
                    contentPadding = PiFieldPaddingBlock,
                )
            }
            if (showProblem && problem != null) {
                Text(problem, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.error)
            }
            saveError?.takeIf { it.isNotBlank() }?.let { err ->
                Text(err, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline, Alignment.End),
            ) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
                Button(onClick = {
                    if (problem == null) onSave(draft) else showProblem = true
                }) { Text(tr("保存", "Save")) }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }

    if (confirmDelete && onDelete != null) {
        PiSettingsDialog(
            onDismissRequest = { confirmDelete = false },
            title = tr("删除「${initial.name}」？", "Delete \"${initial.name}\"?"),
            body = tr("只会从这份 mcp.json 里去掉这一条，其他服务器不受影响。", "Removes only this entry from mcp.json; other servers are unaffected."),
            confirmationLabel = tr("删除", "Delete"),
            confirmationTone = MaterialTheme.colorScheme.error,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { confirmDelete = false },
        )
    }
}

private fun PiMcpConfig.Draft.hasAdvanced(): Boolean =
    description.isNotBlank() || timeoutSeconds.isNotBlank() || cwd.isNotBlank() ||
        toolExposure.isNotBlank() || hasOauth()

private fun PiMcpConfig.Draft.hasOauth(): Boolean =
    oauthClientRegistration.isNotBlank() || listOf(
        oauthClientId, oauthClientSecret, oauthCallbackPort, oauthCallbackUrl, oauthScope, oauthClientName, oauthAuthServerUrl,
    ).any { it.isNotBlank() }

/** OAuth 高级项：只在服务器不支持动态注册 / 要预注册客户端 / 要指定回调时才需要。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpOauthFields(draft: PiMcpConfig.Draft, onChange: (PiMcpConfig.Draft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter)) {
        PiEditorField(
            value = draft.oauthClientId,
            onValueChange = { onChange(draft.copy(oauthClientId = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("客户端 ID（预注册的客户端）", "Client ID (pre-registered client)"),
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthClientSecret,
            onValueChange = { onChange(draft.copy(oauthClientSecret = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("客户端密钥（可选，可写 \${变量}）", "Client Secret (optional, supports \${VAR})"),
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthCallbackPort,
            onValueChange = { onChange(draft.copy(oauthCallbackPort = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("回调端口（http://127.0.0.1:端口/callback）", "Callback Port (http://127.0.0.1:<port>/callback)"),
            placeholder = "8765",
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthCallbackUrl,
            onValueChange = { onChange(draft.copy(oauthCallbackUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("回调地址（可选，仅 localhost / 127.0.0.1 / [::1]）", "Callback URL (optional, localhost / 127.0.0.1 / [::1] only)"),
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthScope,
            onValueChange = { onChange(draft.copy(oauthScope = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("scope（空格分隔，服务器没声明时才填）", "Scope (space-separated, only if not advertised by server)"),
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthClientName,
            onValueChange = { onChange(draft.copy(oauthClientName = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("客户端名称（默认 pi；有的服务器只认已知客户端）", "Client Name (default: pi; some servers require a known client)"),
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthAuthServerUrl,
            onValueChange = { onChange(draft.copy(oauthAuthServerUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("授权服务器元数据地址（服务器宣告错了才填，https）", "Auth Server Metadata URL (https, only if server advertises wrong URL)"),
            height = PiNumberFieldHeight,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                tr("客户端登记方式（clientRegistration）", "Client Registration Mode"),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
            ) {
                listOf(
                    "" to tr("自动 (auto)", "Auto"),
                    "dcr" to "DCR",
                    "cimd" to "CIMD",
                ).forEach { (value, label) ->
                    PiSettingsChip(
                        text = label,
                        active = draft.oauthClientRegistration == value,
                        onClick = { onChange(draft.copy(oauthClientRegistration = value)) },
                    )
                }
            }
        }
    }
}

/** 粘贴或选择 `.json` 文件导入别的客户端的配置，实时预览检测到的服务器，转成 pi 的写法并入当前 mcp.json（同名不覆盖）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun McpImportSheet(
    onDismiss: () -> Unit,
    onImport: (text: String, fallbackName: String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var fallbackName by remember { mutableStateOf("imported") }
    var readError by remember { mutableStateOf<String?>(null) }
    val palette = PiTheme.palette

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

    PiSettingsSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
        ) {
            Text(tr("导入 MCP 配置", "Import MCP Config"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.accent.copy(alpha = 0.12f))
                        .clickable { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = palette.accent, modifier = Modifier.size(14.dp))
                    Text(tr("选择 .json 文件", "Pick .json file"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.accent)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            val pasted = clipboard.getText()?.text.orEmpty()
                            if (pasted.isNotBlank()) {
                                readError = null
                                text = pasted
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, tint = palette.text, modifier = Modifier.size(14.dp))
                    Text(tr("从剪贴板粘贴", "Paste"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.text)
                }
            }
            Text(
                tr(
                    "支持直接导入 Claude Desktop / Claude Code / Cursor / VS Code / OpenCode 的 mcpServers JSON 或单个服务器对象；同名项不会覆盖。",
                    "Supports mcpServers JSON from Claude, Cursor, VS Code, or OpenCode, or a single server object. Existing names are not overwritten.",
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            readError?.let { err ->
                Text(err, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.error)
            }
            PiEditorField(
                value = text,
                onValueChange = {
                    readError = null
                    text = it
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "{ \"mcpServers\": { … } }",
                singleLine = false,
                maxLines = 12,
                minHeight = PiTextFieldMinHeight * 2,
                contentPadding = PiFieldPaddingBlock,
            )
            if (preview.isSingleServer) {
                PiEditorField(
                    value = fallbackName,
                    onValueChange = { fallbackName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("单个服务器名称", "Server Name"),
                    placeholder = "my-server",
                    height = PiNumberFieldHeight,
                )
            }
            if (text.isNotBlank()) {
                if (preview.error != null) {
                    Text(preview.error, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.error)
                } else if (preview.entries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            tr("检测到 ${preview.entries.size} 个服务器：", "Detected ${preview.entries.size} server(s):"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = palette.success,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            preview.entries.forEach { entry ->
                                val tag = if (entry.isHttp) "HTTP" else "STDIO"
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f)),
                                ) {
                                    Text(
                                        text = "${entry.name} · $tag",
                                        style = PiTheme.text.monoSmall,
                                        color = palette.text,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
                Button(
                    onClick = { onImport(text, fallbackName.trim().ifEmpty { "imported" }) },
                    enabled = text.isNotBlank() && preview.error == null && preview.entries.isNotEmpty(),
                ) {
                    Text(
                        if (preview.entries.size > 1) tr("导入 ${preview.entries.size} 个", "Import ${preview.entries.size}")
                        else tr("导入", "Import"),
                    )
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
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
