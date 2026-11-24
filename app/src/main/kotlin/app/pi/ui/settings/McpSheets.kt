package app.pi.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
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
import app.pi.ui.theme.contrastOn

@Composable
private fun McpSectionLabel(text: String) {
    Text(
        text = text,
        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
        color = PiTheme.palette.muted,
        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + 4.dp),
    )
}

@Composable
private fun McpSectionCard(content: @Composable () -> Unit) {
    val palette = PiTheme.palette
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = RoundedCornerShape(14.dp),
        color = palette.cardBg,
        border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.72f)),
    ) {
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

/** 编辑 / 新增 MCP 服务器表单：分组卡片层级 + 折叠高级项 + 吸底保存栏，字段校验全走 Pi SDK / [PiMcpConfig]。 */
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
    val palette = PiTheme.palette
    var draft by remember(initial) { mutableStateOf(initial) }
    var showProblem by remember(initial) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var advanced by remember(initial) { mutableStateOf(draft.hasAdvanced()) }
    val problem = PiMcpConfig.check(draft, otherNames)
    val fieldBg = palette.pageBg.copy(alpha = 0.55f)
    val fieldBorder = palette.borderMuted.copy(alpha = 0.75f)

    PiSettingsSheet(onDismiss = onDismiss, fullHeight = true) {
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
                    color = palette.text,
                )
                Text(
                    text = tr("写入 mcp.json 并由 Pi SDK 实时校验", "Writes to mcp.json with live Pi SDK validation"),
                    style = PiTheme.text.meta,
                    color = palette.dim,
                )
            }

            // 1. 基本信息与连接
            McpSectionLabel(tr("基本信息与连接", "Server & Connection"))
            McpSectionCard {
                PiEditorField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("名字（字母、数字、_ 和 -）", "Name (letters, numbers, _ and -)"),
                    placeholder = "my-server",
                    height = PiNumberFieldHeight,
                    container = fieldBg,
                    idleBorder = fieldBorder,
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
                        container = fieldBg,
                        idleBorder = fieldBorder,
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
                        container = fieldBg,
                        idleBorder = fieldBorder,
                    )
                    PiEditorField(
                        value = draft.bearer,
                        onValueChange = { draft = draft.copy(bearer = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("访问令牌（可选：直接粘贴，或写 \${变量}；留空且服务器要登录则走 OAuth）", "Access Token (optional: paste token or \${VAR}; leave blank for OAuth)"),
                        height = PiNumberFieldHeight,
                        container = fieldBg,
                        idleBorder = fieldBorder,
                    )
                } else {
                    PiEditorField(
                        value = draft.command,
                        onValueChange = { draft = draft.copy(command = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("命令（只填一个可执行文件）", "Command (single executable)"),
                        placeholder = "npx",
                        height = PiNumberFieldHeight,
                        container = fieldBg,
                        idleBorder = fieldBorder,
                    )
                    PiEditorField(
                        value = draft.args,
                        onValueChange = { draft = draft.copy(args = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("参数（每行一个，无需引号）", "Arguments (one per line, no quotes needed)"),
                        placeholder = "-y\n@modelcontextprotocol/server-filesystem\n/workspace",
                        singleLine = false,
                        maxLines = 6,
                        minHeight = PiTextFieldMinHeight,
                        contentPadding = PiFieldPaddingBlock,
                        container = fieldBg,
                        idleBorder = fieldBorder,
                    )
                    PiEditorField(
                        value = draft.env,
                        onValueChange = { draft = draft.copy(env = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("环境变量（每行一个，名称=值；可写 \${变量} 或 !命令）", "Environment (one per line, NAME=VALUE; supports \${VAR} or !command)"),
                        placeholder = "API_KEY=\${MY_API_KEY}",
                        singleLine = false,
                        maxLines = 6,
                        minHeight = PiTextFieldMinHeight,
                        contentPadding = PiFieldPaddingBlock,
                        container = fieldBg,
                        idleBorder = fieldBorder,
                    )
                }
            }

            // 2. 工具暴露方式
            McpSectionLabel(tr("工具暴露方式", "Tool Exposure"))
            McpSectionCard {
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
                                    onClick = { draft = draft.copy(exposure = option) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) {
                                        palette.accent.copy(alpha = 0.13f)
                                    } else {
                                        fieldBg
                                    },
                                    border = BorderStroke(
                                        width = if (selected) 1.dp else PiSpacing.hairlineThin,
                                        color = if (selected) palette.accent.copy(alpha = 0.65f) else fieldBorder,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            mcpExposureShortLabel(option),
                                            style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (selected) palette.accent else palette.text,
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
                }
                Text(
                    mcpExposureHelp(draft.exposure),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }

            // 3. 连接状态与 OAuth 快捷入口（编辑态）
            if (state != null || (onLogin != null && onLogout != null)) {
                McpSectionLabel(tr("连接状态与授权", "Status & Auth"))
                McpSectionCard {
                    if (state != null) {
                        Text(
                            PiMcpStatus.stateLabel(state),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = if (state.connected || !state.enabled) palette.muted else palette.error,
                        )
                        state.error?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
                    }
                    if (onLogin != null && onLogout != null) {
                        Text(
                            tr(
                                "需要账号授权的远程服务可在浏览器完成 OAuth 登录，令牌由 pi 自动保存（改过网址请先保存）。",
                                "For OAuth servers, sign in via browser and pi saves the token automatically (save first after changing URL).",
                            ),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PiSettingsChip(
                                text = tr("登录 / 重新登录", "Sign In / Re-authenticate"),
                                active = state?.needsAuth == true,
                                onClick = onLogin,
                            )
                            PiSettingsChip(
                                text = tr("退出登录", "Sign Out"),
                                onClick = onLogout,
                            )
                        }
                    }
                }
            }

            // 4. 高级选项（可折叠卡片，替换突兀胶囊）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                shape = RoundedCornerShape(14.dp),
                color = palette.cardBg,
                border = BorderStroke(
                    PiSpacing.hairlineThin,
                    if (advanced) palette.accent.copy(alpha = 0.40f) else palette.borderMuted.copy(alpha = 0.72f),
                ),
            ) {
                Column(Modifier.fillMaxWidth()) {
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
                                color = palette.text,
                            )
                            Text(
                                text = tr("说明 · 超时 · OAuth 登记 · 单工具暴露", "Description · Timeout · OAuth · Per-tool exposure"),
                                style = PiTheme.text.meta,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = if (advanced) palette.accent else palette.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (advanced) {
                        HorizontalDivider(
                            thickness = PiSpacing.hairlineThin,
                            color = palette.borderMuted.copy(alpha = 0.6f),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PiEditorField(
                                value = draft.description,
                                onValueChange = { draft = draft.copy(description = it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = tr("说明（可选，给你自己看）", "Description (optional, for your reference)"),
                                height = PiNumberFieldHeight,
                                textStyle = PiTheme.text.meta,
                                container = fieldBg,
                                idleBorder = fieldBorder,
                            )
                            PiEditorField(
                                value = draft.timeoutSeconds,
                                onValueChange = { draft = draft.copy(timeoutSeconds = it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = tr("超时（秒，留空 = 60）", "Timeout (seconds, blank = 60)"),
                                placeholder = "60",
                                height = PiNumberFieldHeight,
                                container = fieldBg,
                                idleBorder = fieldBorder,
                            )
                            if (!draft.http) {
                                PiEditorField(
                                    value = draft.cwd,
                                    onValueChange = { draft = draft.copy(cwd = it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = tr("工作目录（可选，留空用当前项目目录）", "Working Directory (optional, defaults to project dir)"),
                                    placeholder = "/workspace/my-project",
                                    height = PiNumberFieldHeight,
                                    container = fieldBg,
                                    idleBorder = fieldBorder,
                                )
                            } else {
                                McpOauthFields(draft = draft, onChange = { draft = it })
                            }
                            if (state != null && state.tools.isNotEmpty()) {
                                Text(
                                    tr(
                                        "已发现的工具（点一下可加入或取消单工具覆盖）",
                                        "Discovered tools (tap to toggle per-tool override)",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
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
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                                container = fieldBg,
                                idleBorder = fieldBorder,
                            )
                        }
                    }
                }
            }

            if (showProblem && problem != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.error.copy(alpha = 0.10f),
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
                    color = palette.error.copy(alpha = 0.10f),
                    border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
                ) {
                    Text(err, style = PiTheme.text.meta, color = palette.error, modifier = Modifier.padding(11.dp))
                }
            }
        }

        // ---- 吸底操作栏（永远无需翻越 15 个字段去找保存键） ----
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = palette.cardBg,
            border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.50f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    Surface(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = palette.error.copy(alpha = 0.10f),
                        border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                tr("删除", "Delete"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.error,
                            )
                        }
                    }
                }
                Surface(
                    onClick = onDismiss,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.pageBg.copy(alpha = 0.55f),
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.72f)),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tr("取消", "Cancel"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = palette.text,
                        )
                    }
                }
                Surface(
                    onClick = { if (problem == null) onSave(draft) else showProblem = true },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.accent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            tr("保存配置", "Save"),
                            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.contrastOn(palette.accent),
                        )
                    }
                }
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
    val palette = PiTheme.palette
    val fieldBg = palette.pageBg.copy(alpha = 0.55f)
    val fieldBorder = palette.borderMuted.copy(alpha = 0.75f)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PiEditorField(
            value = draft.oauthClientId,
            onValueChange = { onChange(draft.copy(oauthClientId = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("客户端 ID（预注册的客户端）", "Client ID (pre-registered client)"),
            height = PiNumberFieldHeight,
            container = fieldBg,
            idleBorder = fieldBorder,
        )
        PiEditorField(
            value = draft.oauthClientSecret,
            onValueChange = { onChange(draft.copy(oauthClientSecret = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("客户端密钥（可选，可写 \${变量}）", "Client Secret (optional, supports \${VAR})"),
            height = PiNumberFieldHeight,
            container = fieldBg,
            idleBorder = fieldBorder,
        )
        PiEditorField(
            value = draft.oauthCallbackPort,
            onValueChange = { onChange(draft.copy(oauthCallbackPort = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("回调端口（http://127.0.0.1:端口/callback）", "Callback Port (http://127.0.0.1:<port>/callback)"),
            placeholder = "8765",
            height = PiNumberFieldHeight,
            container = fieldBg,
            idleBorder = fieldBorder,
        )
        PiEditorField(
            value = draft.oauthCallbackUrl,
            onValueChange = { onChange(draft.copy(oauthCallbackUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("回调地址（可选，仅 localhost / 127.0.0.1 / [::1]）", "Callback URL (optional, localhost / 127.0.0.1 / [::1] only)"),
            height = PiNumberFieldHeight,
            container = fieldBg,
            idleBorder = fieldBorder,
        )
        PiEditorField(
            value = draft.oauthScope,
            onValueChange = { onChange(draft.copy(oauthScope = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("scope（空格分隔，服务器没声明时才填）", "Scope (space-separated, only if not advertised by server)"),
            height = PiNumberFieldHeight,
            container = fieldBg,
            idleBorder = fieldBorder,
        )
        PiEditorField(
            value = draft.oauthClientName,
            onValueChange = { onChange(draft.copy(oauthClientName = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("客户端名称（默认 pi；有的服务器只认已知客户端）", "Client Name (default: pi; some servers require a known client)"),
            height = PiNumberFieldHeight,
            container = fieldBg,
            idleBorder = fieldBorder,
        )
        PiEditorField(
            value = draft.oauthAuthServerUrl,
            onValueChange = { onChange(draft.copy(oauthAuthServerUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = tr("授权服务器元数据地址（服务器宣告错了才填，https）", "Auth Server Metadata URL (https, only if server advertises wrong URL)"),
            height = PiNumberFieldHeight,
            container = fieldBg,
            idleBorder = fieldBorder,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                tr("客户端登记方式（clientRegistration）", "Client Registration Mode"),
                style = PiTheme.text.meta,
                color = palette.muted,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
    val fieldBg = palette.pageBg.copy(alpha = 0.55f)
    val fieldBorder = palette.borderMuted.copy(alpha = 0.75f)
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
                Text(tr("导入 MCP 配置", "Import MCP Config"), style = PiType.title, color = palette.text)
                Text(
                    tr("兼容 Claude Desktop、Cursor、OpenCode 与标准 mcp.json 格式，同名不覆盖。", "Compatible with Claude Desktop, Cursor, OpenCode, and standard mcp.json; existing names are kept."),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = palette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                        Text(tr("选择 .json 文件", "Pick .json"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.text)
                    }
                }
                Surface(
                    onClick = {
                        val clip = clipboard.getText()?.text
                        if (!clip.isNullOrBlank()) {
                            text = clip
                            readError = null
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = palette.cardBg,
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                        Text(tr("粘贴剪贴板", "Paste"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.text)
                    }
                }
            }
            PiEditorField(
                value = text,
                onValueChange = { text = it; readError = null },
                modifier = Modifier.fillMaxWidth(),
                label = tr("MCP JSON 内容", "MCP JSON"),
                placeholder = "{\n  \"mcpServers\": {\n    \"docs\": { \"url\": \"https://example.com/mcp\" }\n  }\n}",
                singleLine = false,
                maxLines = 12,
                minHeight = 160.dp,
                contentPadding = PiFieldPaddingBlock,
                container = fieldBg,
                idleBorder = fieldBorder,
            )
            if (preview.entries.size == 1 && preview.entries.first().name == fallbackName) {
                PiEditorField(
                    value = fallbackName,
                    onValueChange = { fallbackName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("单条配置默认名字", "Default Server Name"),
                    placeholder = "imported",
                    height = PiNumberFieldHeight,
                    container = fieldBg,
                    idleBorder = fieldBorder,
                )
            }
            readError?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
            preview.error?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
            if (preview.entries.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    color = palette.cardBg,
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
                                    color = palette.text,
                                )
                                Text(
                                    if (entry.isHttp) "HTTP" else "STDIO",
                                    style = PiTheme.text.monoSmall,
                                    color = palette.muted,
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
            color = palette.cardBg,
            border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.50f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = onDismiss,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.pageBg.copy(alpha = 0.55f),
                    border = BorderStroke(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.72f)),
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(tr("取消", "Cancel"), style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium), color = palette.text)
                    }
                }
                val canImport = text.isNotBlank() && preview.error == null && preview.entries.isNotEmpty()
                Surface(
                    onClick = { onImport(text, fallbackName.trim().ifEmpty { "imported" }) },
                    enabled = canImport,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (canImport) palette.accent else palette.pageBg.copy(alpha = 0.45f),
                    border = if (canImport) null else BorderStroke(PiSpacing.hairlineThin, palette.borderMuted),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (preview.entries.size > 1) tr("导入 ${preview.entries.size} 个", "Import ${preview.entries.size}")
                            else tr("导入", "Import"),
                            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                            color = if (canImport) palette.contrastOn(palette.accent) else palette.muted,
                        )
                    }
                }
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
