package app.pi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.HostExtensions
import app.pi.i18n.tr
import app.pi.packages.PiMcpConfig
import app.pi.packages.PiMcpFile
import app.pi.packages.PiMcpStatus
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设置 → 「MCP 服务器」：pi 的 `mcp.json` 可视化管理器（严格对齐 Pi SDK，不内置第三方预设）。
 *
 * 两个层级与 pi 一致：全局（`<agentDir>/mcp.json`）与本项目（`<工作区>/.pi/mcp.json`，项目被信任才读）。
 * 表单/导入/接入方式选择面板拆分在 `McpSheets.kt`。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun McpServersScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    userFile: File,
    projectFile: File,
    access: () -> app.pi.host.ExtensionsAccess?,
    onFilesWritten: () -> Unit,
) {
    var status by remember { mutableStateOf<PiMcpStatus.Report?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var loginUi by remember { mutableStateOf<LoginUi?>(null) }
    val userStore = remember(userFile) { PiMcpFile(userFile) }
    val projectStore = remember(projectFile) { PiMcpFile(projectFile) }
    var epoch by remember { mutableIntStateOf(0) }
    var project by remember { mutableStateOf(false) }
    var pendingRestart by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var hostCfg by remember { mutableStateOf<HostExtensions.McpConfig?>(null) }

    val hostNow = access()
    LaunchedEffect(hostNow, epoch) {
        hostCfg = hostNow?.ext?.let { runCatching { it.mcpConfig() }.getOrNull() }
    }
    LaunchedEffect(hostNow) {
        val h = hostNow ?: return@LaunchedEffect
        h.events.collect { event ->
            if (HostExtensions.mcpChanged(event) != null) {
                status = null
                epoch++
            }
        }
    }

    val activeStore = if (project) projectStore else userStore
    val userDoc = remember(userStore, epoch) { userStore.read() }
    val doc = remember(activeStore, epoch) { activeStore.read() }

    fun write(transform: (JsonObject) -> JsonObject) {
        val failure = activeStore.update(transform)
        error = failure
        if (failure == null) {
            status = null
            pendingRestart = true
            onFilesWritten()
        }
        epoch++
    }

    fun updateServerEnabled(name: String, enabled: Boolean, isInheritedOverride: Boolean = false, globalEnabled: Boolean = true) {
        val host = access()
        if (host != null) {
            scope.launch {
                try {
                    host.ext.mcpUpdate(name, enabled = enabled, project = project)
                    error = null
                    status = null
                    pendingRestart = true
                    onFilesWritten()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: tr("更新失败", "Update failed")
                }
                epoch++
            }
            return
        }
        if (!doc.corrupt) {
            write {
                if (isInheritedOverride) PiMcpConfig.setProjectOverride(it, name, enabled, globalEnabled)
                else PiMcpConfig.setEnabled(it, name, enabled)
            }
        }
    }

    fun runCheck() {
        if (checking) return
        val host = access()
        if (host == null) {
            checkFailure = tr("引擎还没起来，起来之后才能检测连接。", "Engine is not running yet; start it before checking connections.")
            return
        }
        checking = true
        checkFailure = null
        scope.launch {
            try {
                val reports = host.ext.mcpCheck()
                status = PiMcpStatus.Report(
                    servers = reports.map {
                        PiMcpStatus.Server(
                            name = it.name, scope = "", enabled = it.state != "disabled", state = it.state,
                            tools = it.tools, toolExposure = emptyMap(), resources = it.resources, error = it.error,
                        )
                    },
                    errors = emptyList(),
                    note = null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                checkFailure = e.message ?: tr("检测失败", "Check failed")
            }
            checking = false
        }
    }

    var reloading by remember { mutableStateOf(false) }
    fun reload() {
        val host = access() ?: run {
            error = tr("引擎还没起来；下次起来时会读到新配置。", "Engine is not running yet; the new config will load next time it starts.")
            return
        }
        if (reloading) return
        reloading = true
        scope.launch {
            try {
                host.ext.reload()
                pendingRestart = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("重新加载失败", "Reload failed")
            }
            reloading = false
        }
    }

    var loginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var loginId by remember { mutableStateOf<String?>(null) }

    // 离开页面时若 MCP OAuth 登录仍在进行，用独立非取消协程向宿主发 mcpCancelLogin，避免下次报 ERR.BUSY。
    DisposableEffect(Unit) {
        onDispose {
            val activeId = loginId
            loginJob?.cancel()
            if (activeId != null) {
                val ext = access()?.ext
                if (ext != null) {
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        runCatching { ext.mcpCancelLogin(activeId) }
                    }
                }
            }
        }
    }

    fun startLogin(name: String) {
        val host = access()
        if (host == null) {
            loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Failed, null, tr("引擎还没起来，起来之后才能登录。", "Engine is not running yet; start it before signing in."))
            return
        }
        val id = "m${System.nanoTime().toString(36)}"
        loginId = id
        loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Waiting, null, "")
        loginJob = scope.launch {
            val watchers = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    host.events.collect { event ->
                        val cancelledId = app.pi.host.HostAuth.cancelId(event)
                        if (cancelledId != null && loginUi?.redirectRequestId == cancelledId) {
                            loginUi = loginUi?.copy(redirectRequestId = null)
                            return@collect
                        }
                        HostExtensions.authUrl(event)?.takeIf { it.loginId == id }?.let { auth ->
                            loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Waiting, auth.url, "")
                            runCatching { uriHandler.openUri(auth.url) }
                        }
                    }
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    host.requests.collect { request ->
                        HostExtensions.redirectRequest(request)?.takeIf { it.first == id }?.let {
                            loginUi = loginUi?.copy(redirectRequestId = request.id)
                        }
                    }
                },
            )
            try {
                val tools = host.ext.mcpLogin(name, id)
                loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Done, null, tr("登录成功，${tools.size} 个工具可用。", "Signed in; ${tools.size} tools available."))
                status = null
                pendingRestart = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: app.pi.host.HostException) {
                loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Failed, null, e.message ?: e.code)
            } finally {
                watchers.forEach { it.cancel() }
                loginId = null
            }
        }
    }

    val cfgNow = hostCfg
    val definitions = if (cfgNow != null) {
        if (project) {
            cfgNow.servers.filter { it.isProject && it.override == null }.map(::hostServerToConfig)
        } else {
            cfgNow.globalServers.filter { it.scope != "extension" }.map(::hostServerToConfig)
        }
    } else {
        doc.servers.filter { !project || it.kind != PiMcpConfig.Kind.Override }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = tr("MCP 服务器", "MCP Servers"),
            onBack = onBack,
            scrolled = scrolled,
            actions = {
                PiTopBarIcon(
                    onClick = { picking = true },
                    contentDescription = tr("添加服务器", "Add Server"),
                    icon = Icons.Filled.Add,
                )
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            item {
                app.pi.ui.PiSeg(
                    options = listOf(tr("全局", "Global"), tr("本项目", "Project")),
                    selectedIndex = if (project) 1 else 0,
                    onSelect = { project = it == 1 },
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            item {
                val palette = PiTheme.palette
                val enabledCount = definitions.count { it.enabled }
                val healthSummary = status?.let { report ->
                    val enabled = report.servers.filter { it.enabled }
                    val ok = enabled.count { it.connected }
                    val auth = enabled.count { it.needsAuth }
                    val failed = enabled.size - ok - auth
                    val text = tr("$ok/${enabled.size} 已连接", "$ok/${enabled.size} connected") +
                        if (auth > 0) tr(" · $auth 个待登录", " · $auth need login") else ""
                    val color = when {
                        enabled.isEmpty() -> palette.muted
                        failed > 0 -> palette.error
                        auth > 0 -> palette.warning
                        else -> palette.success
                    }
                    text to color
                }
                PiSettingsCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                vertical = PiSettingsMetrics.rowPaddingVertical,
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.accent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Hub,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (project) tr("本项目 · .pi/mcp.json", "Project · .pi/mcp.json") else tr("全局 · mcp.json", "Global · mcp.json"),
                                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    healthSummary?.first ?: tr("已启用 $enabledCount / ${definitions.size} 个 MCP 服务", "$enabledCount / ${definitions.size} MCP servers enabled"),
                                    style = PiTheme.text.meta,
                                    color = healthSummary?.second ?: palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (project && cfgNow != null && !cfgNow.projectTrusted) {
                                McpBadgePill(text = tr("未信任", "Untrusted"), tint = palette.warning)
                            } else if (definitions.isNotEmpty()) {
                                McpBadgePill(
                                    text = "$enabledCount/${definitions.size}",
                                    tint = if (enabledCount > 0) palette.accent else palette.muted,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PiSettingsChip(
                                text = if (checking) tr("检测中…", "Checking…") else tr("检测连接", "Check"),
                                active = status != null,
                                enabled = !checking,
                                onClick = { runCheck() },
                            )
                            PiSettingsChip(
                                text = tr("添加服务", "Add Server"),
                                onClick = { picking = true },
                            )
                            PiSettingsChip(
                                text = tr("导入 JSON", "Import JSON"),
                                onClick = { importing = true },
                            )
                        }
                    }
                }
            }
            if (doc.corrupt) {
                item {
                    PiInfoNote(
                        tr("这份 mcp.json 不是合法的 JSON，请检查 ~/.pi/agent/mcp.json 或 .pi/mcp.json 后再编辑。", "This mcp.json is not valid JSON. Fix ~/.pi/agent/mcp.json or .pi/mcp.json before editing here."),
                        tone = PiTheme.palette.error,
                    )
                }
            }
            if (project && cfgNow != null && !cfgNow.projectTrusted) {
                item {
                    PiInfoNote(
                        tr("当前项目尚未被信任，pi 暂不加载 .pi/mcp.json；信任项目后即生效。", "This project is not trusted yet, so pi will not load .pi/mcp.json until you trust it."),
                        tone = PiTheme.palette.warning,
                    )
                }
            }
            if (cfgNow != null && cfgNow.errors.isNotEmpty()) {
                item {
                    PiInfoNote(
                        cfgNow.errors.joinToString("\n"),
                        tone = PiTheme.palette.error,
                    )
                }
            }
            error?.let { message ->
                item { PiInfoNote(message, tone = PiTheme.palette.error) }
            }
            if (pendingRestart) {
                item {
                    PiSettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PiTheme.palette.accent.copy(alpha = 0.08f))
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = PiSettingsMetrics.rowPaddingVertical,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("配置已保存，尚未重载", "Saved — Pending Reload"),
                                    style = PiType.rowTitle,
                                    color = PiTheme.palette.accent,
                                )
                                Text(
                                    tr("热重载立即生效（不重启引擎）", "Hot-reload applies immediately without restarting the engine"),
                                    style = PiTheme.text.meta,
                                    color = PiTheme.palette.muted,
                                )
                            }
                            PiSettingsChip(
                                text = if (reloading) tr("重载中…", "Reloading…") else tr("立即生效", "Reload Now"),
                                active = true,
                                enabled = !reloading,
                                onClick = { reload() },
                            )
                        }
                    }
                }
            }
            checkFailure?.let { message -> item { PiInfoNote(message, tone = MaterialTheme.colorScheme.error) } }
            status?.let { report ->
                if (report.errors.isNotEmpty() || report.note != null) {
                    item {
                        PiInfoNote(
                            (report.errors + listOfNotNull(report.note)).joinToString("\n"),
                            tone = if (report.errors.isNotEmpty()) MaterialTheme.colorScheme.error else null,
                        )
                    }
                }
            }
            item {
                if (definitions.isEmpty()) {
                    PiSettingsCard {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = PiSettingsMetrics.rowPaddingVertical,
                                ),
                            verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        ) {
                            Text(
                                tr("还没有配置 MCP 服务器", "No MCP Servers Configured"),
                                style = PiType.rowTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                tr("按需添加远程 HTTP 服务、本地 STDIO 命令，或直接粘贴 Claude / Cursor / VS Code 的 mcpServers JSON。", "Add a remote HTTP server, a local STDIO command, or paste mcpServers JSON from Claude, Cursor, or VS Code."),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                                verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                            ) {
                                PiSettingsChip(
                                    text = tr("＋ 远程服务 (HTTP)", "+ Remote HTTP"),
                                    onClick = { editing = Editing(null, PiMcpConfig.Draft(http = true, url = "https://")) },
                                )
                                PiSettingsChip(
                                    text = tr("＋ 本地命令 (STDIO)", "+ Local STDIO"),
                                    onClick = { editing = Editing(null, PiMcpConfig.Draft(http = false, command = "npx", args = "-y\n")) },
                                )
                                PiSettingsChip(
                                    text = tr("粘贴 JSON 导入", "Import JSON"),
                                    active = true,
                                    onClick = { importing = true },
                                )
                            }
                        }
                    }
                } else {
                    PiSettingsCard {
                        definitions.forEachIndexed { index, server ->
                            if (index > 0) PiSettingsHairline()
                            McpServerRow(
                                server = server,
                                state = status?.of(server.name),
                                onLogin = { startLogin(server.name) },
                                onOpen = {
                                    editing = Editing(server.name, PiMcpConfig.toDraft(server))
                                },
                                onToggle = { on -> updateServerEnabled(server.name, on) },
                            )
                        }
                    }
                }
            }

            importNote?.let { note -> item { PiInfoNote(note) } }
            val autoCodemodeChecked = cfgNow?.let {
                if (project) it.autoEnableCodemode else it.globalAutoEnableCodemode
            } ?: PiMcpConfig.autoEnableCodemode(doc.root)
            item {
                PiSettingsCard {
                    PiSettingsToggleRow(
                        title = tr("自动启用代码模式", "Auto-Enable Code Mode"),
                        supporting = tr("有服务器连上时自动打开 codemode；关掉则需自己在工具里启用", "Automatically enables codemode when a server connects; otherwise enable it manually in tools"),
                        checked = autoCodemodeChecked,
                        onCheckedChange = { on ->
                            val host = access()
                            if (host != null) {
                                scope.launch {
                                    try {
                                        host.ext.mcpSetAutoEnableCodemode(on, project)
                                        error = null
                                        status = null
                                        pendingRestart = true
                                        onFilesWritten()
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.message ?: tr("更新失败", "Update failed")
                                    }
                                    epoch++
                                }
                            } else if (!doc.corrupt) {
                                write { PiMcpConfig.setAutoEnableCodemode(it, on) }
                            }
                        },
                    )
                }
            }
            if (project) {
                val inheritedRows: List<Triple<String, Boolean, Boolean>> = if (cfgNow != null) {
                    val fullProjectNames = cfgNow.servers.filter { it.isProject && it.override == null }.map { it.name }.toSet()
                    val mergedByName = cfgNow.servers.associateBy { it.name }
                    cfgNow.globalServers
                        .filter { it.scope != "extension" && it.name !in fullProjectNames }
                        .map { global ->
                            val merged = mergedByName[global.name]
                            val effective = merged?.enabled ?: global.enabled
                            val overridden = merged?.override != null || doc.server(global.name)?.config?.containsKey("enabled") == true
                            Triple(global.name, effective, overridden)
                        }
                } else {
                    userDoc.servers.filter { global ->
                        global.kind != PiMcpConfig.Kind.Override &&
                            doc.server(global.name)?.kind.let { it == null || it == PiMcpConfig.Kind.Override }
                    }.map { global ->
                        val override = doc.server(global.name)
                        val effective = override?.config?.get("enabled")
                            ?.let { (it as? JsonPrimitive)?.content != "false" }
                            ?: global.enabled
                        val overridden = override != null && override.config.containsKey("enabled")
                        Triple(global.name, effective, overridden)
                    }
                }
                if (inheritedRows.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                tr("全局服务器在本项目里", "Global Servers in This Project"),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                                modifier = Modifier.padding(
                                    horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
                                    vertical = PiSettingsMetrics.groupHeaderGap,
                                ),
                            )
                            PiSettingsCard {
                                inheritedRows.forEachIndexed { index, (name, effective, overridden) ->
                                    if (index > 0) PiSettingsHairline()
                                    val globalEnabled = userDoc.server(name)?.enabled ?: true
                                    PiSettingsToggleRow(
                                        title = name,
                                        supporting = if (overridden) tr("本项目已覆盖", "Overridden in project") else tr("沿用全局设置", "Inherits global setting"),
                                        checked = effective,
                                        onCheckedChange = { on ->
                                            updateServerEnabled(name, on, isInheritedOverride = true, globalEnabled = globalEnabled)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        McpAddMethodSheet(
            onDismiss = { picking = false },
            onImport = {
                picking = false
                importing = true
            },
            onPick = { draft ->
                picking = false
                editing = Editing(null, draft)
            },
        )
    }

    loginUi?.let { ui ->
        val waiting = ui.phase == LoginUi.Phase.Waiting
        PiSettingsDialog(
            onDismissRequest = { if (!waiting) loginUi = null },
            title = when (ui.kind) {
                LoginUi.Kind.Login -> tr("登录「${ui.server}」", "Sign in to \"${ui.server}\"")
                LoginUi.Kind.Logout -> tr("退出登录「${ui.server}」", "Sign out of \"${ui.server}\"")
            },
            body = when (ui.phase) {
                LoginUi.Phase.Waiting -> if (ui.url == null) {
                    tr("正在连接服务器…", "Connecting to server…")
                } else {
                    tr(
                        "已在浏览器里打开授权页，授权后回到这里即可，最长等 3 分钟。\n\n${ui.url}",
                        "Opened the authorization page in your browser. Return here after authorizing (up to 3 minutes).\n\n${ui.url}",
                    )
                }
                LoginUi.Phase.Done -> ui.message
                LoginUi.Phase.Failed -> ui.message
            },
            confirmationLabel = when {
                waiting && ui.url != null -> tr("再打开一次", "Open Again")
                waiting -> null
                else -> tr("知道了", "OK")
            },
            onConfirm = {
                if (waiting) ui.url?.let { uriHandler.openUri(it) } else loginUi = null
            },
            dismissalLabel = if (waiting) tr("不等了", "Cancel") else null,
            onDismissButton = {
                loginId?.let { id ->
                    val ext = access()?.ext
                    if (ext != null) {
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            runCatching { ext.mcpCancelLogin(id) }
                        }
                    }
                }
                loginUi = null
            },
        )
    }

    loginUi?.takeIf { it.redirectRequestId != null }?.let { ui ->
        var pasted by remember(ui.redirectRequestId) { mutableStateOf("") }
        val requestId = ui.redirectRequestId.orEmpty()
        fun answer(value: String?) {
            loginUi = loginUi?.copy(redirectRequestId = null)
            scope.launch {
                runCatching {
                    access()?.respond?.invoke(
                        requestId,
                        value?.let { JsonPrimitive(it) },
                        value == null,
                    )
                }
            }
        }
        PiSettingsSheet(onDismiss = { answer(null) }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = PiSettingsMetrics.sheetHeadTop),
                verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
            ) {
                Text(tr("粘贴回跳地址", "Paste Callback URL"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    tr("授权后浏览器若停在一个打不开的页面，把地址栏里那串完整网址复制到这里。", "If your browser stops on an unreachable page after authorizing, copy the full URL from the address bar and paste it here."),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
                PiEditorField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = tr("回跳网址", "Callback URL"),
                )
                Button(onClick = { answer(pasted.trim()) }, enabled = pasted.isNotBlank()) { Text(tr("提交", "Submit")) }
            }
        }
    }

    if (importing) {
        McpImportSheet(
            onDismiss = { importing = false },
            onImport = { text ->
                val host = access()
                if (host != null) {
                    scope.launch {
                        try {
                            val r = host.ext.mcpImport(text, project, "imported")
                            error = null
                            status = null
                            pendingRestart = true
                            onFilesWritten()
                            epoch++
                            importNote = buildString {
                                append(
                                    if (r.added.isEmpty()) tr("没有新增。", "Nothing added.")
                                    else tr("已添加：${r.added.joinToString("、")}。", "Added: ${r.added.joinToString(", ")}."),
                                )
                                if (r.skipped.isNotEmpty()) append(tr("\n跳过：", "\nSkipped: ")).append(r.skipped.joinToString(tr("；", "; ")))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            importNote = e.message ?: tr("导入失败", "Import failed")
                        }
                        importing = false
                    }
                } else {
                    var result: PiMcpConfig.Imported? = null
                    write { root ->
                        PiMcpConfig.importJson(root, text, "imported").also { result = it }.root
                    }
                    val r = result
                    importNote = when {
                        r == null -> null
                        r.error != null -> r.error
                        else -> buildString {
                            append(
                                if (r.added.isEmpty()) tr("没有新增。", "Nothing added.")
                                else tr("已添加：${r.added.joinToString("、")}。", "Added: ${r.added.joinToString(", ")}."),
                            )
                            if (r.skipped.isNotEmpty()) append(tr("\n跳过：", "\nSkipped: ")).append(r.skipped.joinToString(tr("；", "; ")))
                        }
                    }
                    importing = false
                }
            },
        )
    }

    editing?.let { current ->
        val editServers = definitions
        McpEditSheet(
            initial = current.draft,
            state = current.oldName?.let { status?.of(it) },
            isNew = current.oldName == null,
            otherNames = editServers.map { it.name }.filter { it != current.oldName },
            onDismiss = { editing = null },
            onSave = { draft ->
                val base = current.oldName?.let { name -> editServers.firstOrNull { it.name == name }?.config ?: doc.server(name)?.config }
                val config = PiMcpConfig.toConfig(draft, base)
                val targetName = draft.name.trim()
                val host = access()
                if (host != null) {
                    scope.launch {
                        try {
                            host.ext.mcpSave(targetName, current.oldName, config, project)
                            error = null
                            status = null
                            pendingRestart = true
                            onFilesWritten()
                            epoch++
                            editing = null
                            if (current.oldName == null && draft.http) runCheck()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: tr("保存失败", "Failed to save")
                            editing = null
                        }
                    }
                } else {
                    write { PiMcpConfig.upsert(it, current.oldName, targetName, config) }
                    editing = null
                    if (current.oldName == null && draft.http && !activeStore.read().corrupt) runCheck()
                }
            },
            onLogin = current.oldName?.takeIf { current.draft.http }?.let { name ->
                {
                    editing = null
                    startLogin(name)
                }
            },
            onLogout = current.oldName?.takeIf { current.draft.http }?.let { name ->
                {
                    editing = null
                    loginUi = LoginUi(LoginUi.Kind.Logout, name, LoginUi.Phase.Waiting, null, "")
                    scope.launch {
                        loginUi = try {
                            val message = access()?.ext?.mcpLogout(name, project) ?: tr("已退出登录。", "Signed out.")
                            LoginUi(LoginUi.Kind.Logout, name, LoginUi.Phase.Done, null, message.ifBlank { tr("已退出登录。", "Signed out.") })
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LoginUi(LoginUi.Kind.Logout, name, LoginUi.Phase.Failed, null, e.message ?: tr("退出失败", "Sign-out failed"))
                        }
                    }
                }
            },
            onDelete = current.oldName?.let { name ->
                {
                    val host = access()
                    if (host != null) {
                        scope.launch {
                            try {
                                host.ext.mcpRemove(name, project)
                                error = null
                                status = null
                                pendingRestart = true
                                onFilesWritten()
                                epoch++
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: tr("删除失败", "Failed to delete")
                            }
                            editing = null
                        }
                    } else {
                        write { PiMcpConfig.remove(it, name) }
                        editing = null
                    }
                }
            },
        )
    }
}

private fun hostServerToConfig(server: HostExtensions.McpServer): PiMcpConfig.Server {
    val map = LinkedHashMap<String, JsonElement>()
    server.type?.let { map["type"] = JsonPrimitive(it) }
    server.command?.let { map["command"] = JsonPrimitive(it) }
    if (server.args.isNotEmpty()) map["args"] = JsonArray(server.args.map(::JsonPrimitive))
    server.env?.let { map["env"] = it }
    server.cwd?.let { map["cwd"] = JsonPrimitive(it) }
    server.url?.let { map["url"] = JsonPrimitive(it) }
    server.headers?.let { map["headers"] = it }
    server.auth?.let { map["auth"] = JsonPrimitive(it) }
    server.oauth?.let { map["oauth"] = it }
    server.description?.let { map["description"] = JsonPrimitive(it) }
    server.timeout?.let {
        map["timeout"] = if (it == kotlin.math.floor(it)) JsonPrimitive(it.toLong()) else JsonPrimitive(it)
    }
    if (!server.enabled) map["enabled"] = JsonPrimitive(false)
    if (server.exposure != "codemode") map["exposure"] = JsonPrimitive(server.exposure)
    server.toolExposure?.let { map["toolExposure"] = it }
    return PiMcpConfig.Server(server.name, JsonObject(map))
}

private data class Editing(val oldName: String?, val draft: PiMcpConfig.Draft)

private data class LoginUi(
    val kind: Kind,
    val server: String,
    val phase: Phase,
    val url: String?,
    val message: String,
    val redirectRequestId: String? = null,
) {
    enum class Kind { Login, Logout }
    enum class Phase { Waiting, Done, Failed }
}

/** 状态圆点 + 名称 + FlowRow 协议/暴露/工具数徽标 + 开关；点行进编辑。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerRow(
    server: PiMcpConfig.Server,
    state: PiMcpStatus.Server?,
    onLogin: () -> Unit,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val palette = PiTheme.palette
    val dotColor = when {
        !server.enabled -> palette.dim.copy(alpha = 0.45f)
        state == null -> palette.accent.copy(alpha = 0.6f)
        state.connected -> palette.success
        state.needsAuth -> palette.warning
        else -> palette.error
    }
    val isHttp = server.kind == PiMcpConfig.Kind.Http
    val protoTint = if (isHttp) palette.accent else palette.success
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(40.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(11.dp))
                    .background(protoTint.copy(alpha = if (server.enabled) 0.14f else 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isHttp) Icons.Filled.Language else Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = if (server.enabled) protoTint else palette.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                server.name,
                style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                color = if (server.enabled) MaterialTheme.colorScheme.onSurface else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                McpBadgePill(
                    text = if (isHttp) "HTTP" else "STDIO",
                    tint = protoTint,
                )
                if (server.exposure != "codemode") {
                    McpBadgePill(
                        text = mcpExposureShortLabel(server.exposure),
                        tint = palette.warning,
                    )
                }
                if (state?.connected == true && state.tools.isNotEmpty()) {
                    McpBadgePill(
                        text = tr("${state.tools.size} 工具", "${state.tools.size} tools"),
                        tint = palette.success,
                    )
                }
            }
            Text(
                server.summary,
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state != null) {
                Text(
                    PiMcpStatus.stateLabel(state),
                    style = PiTheme.text.meta,
                    color = when {
                        state.connected -> palette.success
                        state.needsAuth -> palette.warning
                        !state.enabled -> palette.muted
                        else -> MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state?.needsAuth == true && server.enabled) {
            PiSettingsChip(text = tr("去登录", "Sign In"), active = true, onClick = onLogin)
        }
        PiSettingsSwitch(checked = server.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun McpBadgePill(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(PiShapes.badge)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = PiSpacing.small, vertical = PiSpacing.hairline * 2),
    ) {
        Text(
            text = text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = tint,
            maxLines = 1,
        )
    }
}
