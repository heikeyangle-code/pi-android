package app.pi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 设置 → 「MCP 服务器」：pi 的 `mcp.json` 的点选式编辑器。
 *
 * pi 自己的 `/mcp`、`pi mcp add` 都是终端里的东西；手机上没有终端可敲，所以这一屏把同一份文件
 * 做成表单：开关、暴露方式、新增（模板或自填）、编辑、删除。**写的就是 pi 读的那份文件**，
 * 格式与规则全部来自 `docs/mcp.md`（校验在 [PiMcpConfig]，这里只管画）。
 *
 * 两个层级与 pi 一致：全局（`<agentDir>/mcp.json`）与本项目（`<工作区>/.pi/mcp.json`，项目被信任才读）。
 * 本项目页还能给**全局**服务器写一条只含 `enabled` 的覆盖 —— 「只在这个项目里关掉它」。
 *
 * 服务器是在**会话开始时**连接的（文档：改完要新会话或 `/reload`），所以保存之后这一屏给出
 * 「重启引擎」入口，而不是假装改动已经生效。需要 OAuth 登录的网址服务器：编辑页里的「登录」会在 guest 里
 * 跑 pi 自己的 `pi mcp login`，把授权网址交给浏览器（[PiMcpLogin]）；OAuth 本身一行都不是 App 写的。
 *
 * @param userFile `<agentDir>/mcp.json`
 * @param projectFile `<工作区>/.pi/mcp.json`
 * @param onRestartEngine 让改动立刻生效的入口（设置栈已有的那个重启确认）
 * @param onFilesWritten 写盘之后通知宿主丢缓存（与凭证表单同一个回调）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpServersScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    userFile: File,
    projectFile: File,
    access: () -> app.pi.host.ExtensionsAccess?,
    onFilesWritten: () -> Unit,
) {
    // 「检测连接」的结果：只在用户点了才去连服务器；改过配置后它就过期了，会被清掉。
    var status by remember { mutableStateOf<PiMcpStatus.Report?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var loginUi by remember { mutableStateOf<LoginUi?>(null) }
    val userStore = remember(userFile) { PiMcpFile(userFile) }
    val projectStore = remember(projectFile) { PiMcpFile(projectFile) }
    // 每次改动后 +1，重读宿主配置（或未启动时的磁盘文件）。
    var epoch by remember { mutableIntStateOf(0) }
    var project by remember { mutableStateOf(false) }
    var pendingRestart by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    // 正在编辑：`oldName == null` 表示新增。
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
    // 未接通宿主时的本地兜底；接通宿主后主视图读 hostCfg。
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

    // 宿主在引擎进程里直接连服务器（`mcp.list`），不再另起一个 guest 进程。
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

    // 「让改动生效」：热重载（`session.reload`），不重启引擎。
    var reloading by remember { mutableStateOf(false) }
    fun reload() {
        val host = access() ?: run { error = tr("引擎还没起来；下次起来时会读到新配置。", "Engine is not running yet; the new config will load next time it starts."); return }
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

    // 登录入口有两处：列表行上的「登录」（检测出需要登录时才出现）和编辑页里的「登录 / 重新登录」。
    // OAuth 是 pi 的实现（动态注册、PKCE、刷新），宿主命令 `mcp.login` 在引擎进程里跑；授权网址走
    // `mcp.auth` 事件，回调到不了时宿主会问「请贴回跳地址」（`mcp.redirect`），「不等了」真的会取消。
    var loginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var loginId by remember { mutableStateOf<String?>(null) }
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
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    host.events.collect { event ->
                        val cancelledId = app.pi.host.HostAuth.cancelId(event)
                        if (cancelledId != null && loginUi?.redirectRequestId == cancelledId) {
                            loginUi = loginUi?.copy(redirectRequestId = null)
                            return@collect
                        }
                        app.pi.host.HostExtensions.authUrl(event)?.takeIf { it.loginId == id }?.let { auth ->
                            loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Waiting, auth.url, "")
                            runCatching { uriHandler.openUri(auth.url) }
                        }
                    }
                },
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    host.requests.collect { request ->
                        app.pi.host.HostExtensions.redirectRequest(request)?.takeIf { it.first == id }?.let {
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
    // 本项目页里的服务器是「完整定义」；覆盖条目（只含 enabled/exposure）单独显示在下面。
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
                val connectedSummary = status?.let { report ->
                    val enabled = report.servers.filter { it.enabled }
                    val ok = enabled.count { it.connected }
                    val auth = enabled.count { it.needsAuth }
                    tr("$ok/${enabled.size} 已连接", "$ok/${enabled.size} connected") + if (auth > 0) tr(" · $auth 个待登录", " · $auth need login") else ""
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
                                    connectedSummary ?: tr("已启用 $enabledCount / ${definitions.size} 个 MCP 服务", "$enabledCount / ${definitions.size} MCP servers enabled"),
                                    style = PiTheme.text.meta,
                                    color = if (status != null) palette.success else palette.muted,
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
                                onClick = { runCheck() },
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
                                tr("快速接入常用服务，或粘贴 Claude / Cursor / VS Code 的 mcpServers JSON。", "Quickly add common servers or paste mcpServers JSON from Claude, Cursor, or VS Code."),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                                verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                            ) {
                                PiMcpConfig.TEMPLATES.take(4).forEach { tpl ->
                                    PiSettingsChip(
                                        text = tr("＋ ${tpl.title}", "+ ${tpl.title}"),
                                        onClick = { editing = Editing(null, tpl.draft) },
                                    )
                                }
                                PiSettingsChip(
                                    text = tr("粘贴 JSON 导入", "Paste JSON"),
                                    active = true,
                                    onClick = { importing = true },
                                )
                                PiSettingsChip(
                                    text = tr("全部模板…", "All Templates…"),
                                    onClick = { picking = true },
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
                // 全局服务器在这个项目里的开关：写一条只含 enabled 的覆盖。
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
        McpTemplateSheet(
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
                loginId?.let { id -> scope.launch { runCatching { access()?.ext?.mcpCancelLogin(id) } } }
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
                        value?.let { kotlinx.serialization.json.JsonPrimitive(it) },
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
                    // 新加的网址式服务器：马上检测一次，要登录的话列表行上直接出现「登录」。
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
    /** 宿主在问「请贴回跳地址」（浏览器回调到不了时）：这条提问的 id。 */
    val redirectRequestId: String? = null,
) {
    enum class Kind { Login, Logout }
    enum class Phase { Waiting, Done, Failed }
}

/** 状态圆点 + 名称 + 协议/暴露/工具数徽标 + 开关；点行进编辑。 */
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
    val isHttp = server.config.containsKey("url")
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    server.name,
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = if (server.enabled) MaterialTheme.colorScheme.onSurface else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                McpBadgePill(
                    text = if (isHttp) "HTTP" else "STDIO",
                    tint = protoTint,
                )
                if (server.exposure != "codemode") {
                    McpBadgePill(
                        text = exposureShortLabel(server.exposure),
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

private fun exposureShortLabel(exposure: String): String = when (exposure) {
    "codemode" -> tr("代码模式", "Code Mode")
    "deferred" -> tr("按需检索", "Tool Search")
    "direct" -> tr("直接暴露", "Direct")
    "hidden" -> tr("隐藏", "Hidden")
    else -> exposure
}

private fun exposureLabel(exposure: String): String = when (exposure) {
    "codemode" -> tr("代码模式（默认）", "Code Mode (Default)")
    "deferred" -> tr("按需检索", "Tool Search")
    "direct" -> tr("直接暴露", "Direct")
    "hidden" -> tr("隐藏", "Hidden")
    else -> exposure
}

private fun exposureHelp(exposure: String): String = when (exposure) {
    "codemode" -> tr("工具放进 codemode 脚本里调用，不占提示词（推荐）。", "Tools are invoked via codemode scripts with zero prompt overhead (recommended).")
    "deferred" -> tr("不在提示词里列出，模型要用时用 tool_search 检索。", "Not listed in the prompt; the model discovers tools on demand via tool_search.")
    "direct" -> tr("每个工具都作为独立工具直接给模型，最方便但最占上下文。", "Exposes every tool directly to the model — convenient, but uses the most context.")
    "hidden" -> tr("连接但不暴露任何工具。", "Connects to the server without exposing any tools.")
    else -> ""
}

/** 新增：先选模板（或自填）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpTemplateSheet(
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onPick: (PiMcpConfig.Draft) -> Unit,
) {
    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = PiSettingsMetrics.sheetHeadTop, bottom = PiSettingsMetrics.sheetFooterBottom),
        ) {
            Text(
                tr("添加 MCP 服务器", "Add MCP Server"),
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
            )
            Text(
                tr("名字和参数都可以在下一步改。远程服务器需要设备能联网；要账号的，添加后在列表里点「登录」。", "You can edit the name and arguments on the next step. Remote servers require network access; for OAuth servers, tap \"Sign In\" in the list after adding."),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSpacing.small,
                    bottom = PiSettingsMetrics.sheetHeadBottom,
                ),
            )
            PiSettingsCard {
                PiMcpConfig.TEMPLATES.forEachIndexed { index, template ->
                    if (index > 0) PiSettingsHairline()
                    PiSettingsEntryRow(
                        title = template.title,
                        supporting = template.note,
                        leadingIcon = if (template.draft.http) Icons.Filled.Language else Icons.Filled.Terminal,
                        onClick = { onPick(template.draft) },
                    )
                }
                PiSettingsHairline()
                PiSettingsEntryRow(
                    title = tr("从别处导入（粘贴 JSON）", "Import from Elsewhere (Paste JSON)"),
                    supporting = tr("Claude Desktop / Claude Code / Cursor / VS Code / OpenCode 的配置直接贴进来", "Paste configs directly from Claude Desktop, Claude Code, Cursor, VS Code, or OpenCode"),
                    leadingIcon = Icons.Filled.Code,
                    onClick = onImport,
                )
                PiSettingsHairline()
                PiSettingsEntryRow(
                    title = tr("命令式（自己填）", "Command-Based (Custom STDIO)"),
                    supporting = tr("本地启动一个进程，例如 npx / uvx 包", "Launch a local process such as an npx or uvx package"),
                    leadingIcon = Icons.Filled.Terminal,
                    onClick = { onPick(PiMcpConfig.Draft(command = "npx", args = "-y\n")) },
                )
            }
        }
    }
}

/** 编辑 / 新增表单。字段、校验、写盘形状全在 [PiMcpConfig]；这里只收集输入。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun McpEditSheet(
    initial: PiMcpConfig.Draft,
    state: PiMcpStatus.Server?,
    isNew: Boolean,
    otherNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (PiMcpConfig.Draft) -> Unit,
    onLogin: (() -> Unit)?,
    onLogout: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 校验只在点「保存」后才亮出来：用户还在打字时就红字会很吵。
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
                if (isNew) tr("新增服务器", "Add Server") else tr("编辑服务器", "Edit Server"),
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PiEditorField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = tr("名字（字母、数字、_ 和 -）", "Name (letters, digits, _ and -)"),
                placeholder = "filesystem",
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
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) palette.accent else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        exposureShortLabel(option),
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
                    exposureHelp(draft.exposure),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                )
            }
            // —— 这个服务器现在怎么样：只有「检测连接」跑过才有。
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
            // —— 高级：默认折起，一般用不到。
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                    ) {
                        state.tools.forEach { tool ->
                            val current = state.toolExposure[tool]
                            PiSettingsChip(
                                text = if (current == null) tool else "$tool · ${exposureShortLabel(current)}",
                                active = current != null,
                                onClick = {
                                    if (!draft.toolExposure.lines().any { it.substringBefore('=').trim() == tool }) {
                                        val prefix = draft.toolExposure.trimEnd().let { if (it.isEmpty()) "" else it + "\n" }
                                        draft = draft.copy(toolExposure = prefix + tool + "=direct")
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
    oauthCimd || listOf(
        oauthClientId, oauthClientSecret, oauthCallbackPort, oauthCallbackUrl, oauthScope, oauthClientName, oauthAuthServerUrl,
    ).any { it.isNotBlank() }

/** OAuth 高级项：只在服务器不支持动态注册 / 要预注册客户端 / 要指定回调时才需要。 */
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
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Text(
                tr("用 CIMD 登记（不做动态注册，不能与客户端 ID/名称同用）", "Use CIMD Registration (skips dynamic registration; incompatible with Client ID/Name)"),
                modifier = Modifier.weight(1f),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PiSettingsSwitch(
                checked = draft.oauthCimd,
                onCheckedChange = { onChange(draft.copy(oauthCimd = it)) },
            )
        }
    }
}

/** 粘贴别的客户端的配置，转成 pi 的写法并入当前这份 mcp.json（同名不覆盖）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpImportSheet(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
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
            Text(tr("导入配置", "Import Configuration"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                tr(
                    "把别的客户端的 MCP 配置整段贴进来（含 mcpServers 的那一段，或单个服务器）。" +
                        "同名的不会被覆盖；VS Code 的 \${input:…} 要先改成 \${环境变量名}。",
                    "Paste an MCP config snippet from another client (either a block containing mcpServers or a single server). " +
                        "Existing names will not be overwritten; replace VS Code's \${input:…} with \${ENV_VAR} first.",
                ),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PiEditorField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "{ \"mcpServers\": { … } }",
                singleLine = false,
                maxLines = 14,
                minHeight = PiTextFieldMinHeight * 2,
                contentPadding = PiFieldPaddingBlock,
            )
            HorizontalDivider(
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
                Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text(tr("导入", "Import")) }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}
