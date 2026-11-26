package app.pi.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.packages.PiMcpConfig
import app.pi.packages.PiMcpStatus
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

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

/** 编辑 / 新增 MCP 服务器表单：分组卡片层级 + 折叠高级项 + 吸底保存栏，字段校验全走 Pi SDK / [PiMcpConfig]。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun McpEditSheet(
    initial: PiMcpConfig.Draft,
    state: PiMcpStatus.Server?,
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
    var showProblem by remember(initial) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var advanced by remember(initial) { mutableStateOf(draft.hasAdvanced()) }
    val problem = PiMcpConfig.check(draft, otherNames, isProject = isProject)
    val fieldBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val fieldBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)

    app.pi.ui.components.PiSheet(onDismiss = onDismiss, fullHeight = true) {
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

            // 1. 基本信息与连接
            PiSettingsSectionHeader(tr("基本信息与连接", "Server & Connection"))
            McpFormCard {
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
                McpSegmentedTrack(
                    options = listOf(tr("本地命令 (STDIO)", "Local (STDIO)"), tr("远程服务 (HTTP)", "Remote (HTTP)")),
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
                                    color = if (selected) {
                                        palette.accent.copy(alpha = 0.11f)
                                    } else {
                                        fieldBg
                                    },
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

            // 3. 连接状态与 OAuth 快捷入口（编辑态）
            if (state != null || (onLogin != null && onLogout != null)) {
                PiSettingsSectionHeader(tr("连接状态与授权", "Status & Auth"))
                McpFormCard {
                    if (state != null) {
                        Text(
                            PiMcpStatus.stateLabel(state),
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
                    if (onLogin != null && onLogout != null) {
                        Text(
                            tr(
                                "需要账号授权的远程服务可在浏览器完成 OAuth 登录，令牌由 pi 自动保存（改过网址请先保存）。",
                                "For OAuth servers, sign in via browser and pi saves the token automatically (save first after changing URL).",
                            ),
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state?.needsAuth == true) {
                                PiPrimaryButton(
                                    label = tr("登录 / 重新登录", "Sign In / Re-auth"),
                                    compact = true,
                                    onClick = onLogin,
                                )
                            } else {
                                PiSecondaryButton(
                                    label = tr("登录 / 重新登录", "Sign In / Re-auth"),
                                    compact = true,
                                    onClick = onLogin,
                                )
                            }
                            PiSecondaryButton(
                                label = tr("退出登录", "Sign Out"),
                                compact = true,
                                onClick = onLogout,
                            )
                        }
                    }
                }
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
                                tr("说明 · 超时 · 供应商认证 · OAuth 登记 · 单工具暴露", "Description · Timeout · Provider Auth · OAuth · Per-tool exposure")
                            } else {
                                tr("说明 · 超时 · 工作目录 · 单工具暴露", "Description · Timeout · Working Dir · Per-tool exposure")
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
                            // Pi SDK 1.6.1: auth.provider（复用已登录的供应商凭据作为 Bearer Token，仅限全局 mcp.json）
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PiEditorField(
                                    value = draft.authProvider,
                                    onValueChange = { draft = draft.copy(authProvider = it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isProject || draft.authProvider.isNotBlank(),
                                    label = tr("复用供应商认证（auth.provider，仅全局）", "Reuse Provider Auth (auth.provider, global only)"),
                                    placeholder = "anthropic / openai / github-copilot",
                                    height = PiNumberFieldHeight,
                                    container = fieldBg,
                                    idleBorder = fieldBorder,
                                )
                                Text(
                                    text = if (isProject) {
                                        tr(
                                            "为防止项目配置窃取凭据，Pi SDK 仅允许在全局 mcp.json 中使用 auth.provider。",
                                            "For credential safety, Pi SDK only allows auth.provider in global mcp.json.",
                                        )
                                    } else {
                                        tr(
                                            "填入模型供应商 ID 后，将自动携带该供应商已保存凭据作为 Bearer Token（与请求头 Authorization / OAuth 互斥）。",
                                            "Reuses a configured model provider's stored credential as Bearer token (mutually exclusive with Authorization header / OAuth).",
                                        )
                                    },
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            McpOauthFields(draft = draft, onChange = { draft = it })
                        }
                        if (state != null && state.tools.isNotEmpty()) {
                            Text(
                                tr(
                                    "已发现的工具（点一下循环切换：沿用 → 直接 → 检索 → 代码模式 → 隐藏）",
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

        // ---- 吸底操作栏（统一 PiSecondaryButton / PiPrimaryButton） ----
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
                    onClick = { if (problem == null) onSave(draft) else showProblem = true },
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

private fun PiMcpConfig.Draft.hasAdvanced(): Boolean =
    description.isNotBlank() || timeoutSeconds.isNotBlank() || cwd.isNotBlank() ||
        authProvider.isNotBlank() || toolExposure.isNotBlank() || hasOauth()

private fun PiMcpConfig.Draft.hasOauth(): Boolean =
    oauthClientRegistration.isNotBlank() || listOf(
        oauthClientId, oauthClientSecret, oauthCallbackPort, oauthCallbackUrl, oauthScope, oauthClientName, oauthAuthServerUrl,
    ).any { it.isNotBlank() }

/** OAuth 高级项：只在服务器不支持动态注册 / 要预注册客户端 / 要指定回调时才需要。 */
@Composable
private fun McpOauthFields(draft: PiMcpConfig.Draft, onChange: (PiMcpConfig.Draft) -> Unit) {
    val fieldBg = MaterialTheme.colorScheme.surfaceContainerLowest
    val fieldBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 粘贴或选择 `.json` 文件导入别的客户端的配置，实时预览检测到的服务器，转成 pi 的写法并入当前 mcp.json（同名不覆盖）。 */
@OptIn(ExperimentalMaterial3Api::class)
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

    app.pi.ui.components.PiSheet(onDismiss = onDismiss, fullHeight = true) {
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
                    label = tr("选择 .json 文件", "Pick .json"),
                    leadingIcon = Icons.Filled.FolderOpen,
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
                PiSecondaryButton(
                    label = tr("粘贴剪贴板", "Paste"),
                    leadingIcon = Icons.Filled.ContentPaste,
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = {
                        val clip = clipboard.getText()?.text
                        if (!clip.isNullOrBlank()) {
                            text = clip
                            readError = null
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
