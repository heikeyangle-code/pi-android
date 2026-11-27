package app.pi.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import app.pi.ui.components.PiPrimaryButton
import app.pi.ui.components.PiSecondaryButton
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
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
 * 设置 → 「MCP 服务器」：pi 的 `mcp.json` 可视化管理器（100% 对齐 Pi 1.6.1 SDK）。
 *
 * 覆盖官方完整层级：
 * - 全局（`<agentDir>/mcp.json`）、本项目（`<工作区>/.pi/mcp.json`，信任项目后生效）与扩展注册（`pi.registerMcpServer` 只读展示）；
 * - 项目层对全局服务器的 `enabled` 与 `exposure` 覆盖；
 * - 实时检测连接（工具数 + MCP 资源能力）、OAuth 授权与供应商认证（`auth.provider`）、JSON 兼容导入。
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
    var importing by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var editSaveError by remember { mutableStateOf<String?>(null) }
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

    fun updateInheritedExposure(name: String, nextExposure: String, globalExposure: String) {
        val host = access()
        if (host != null) {
            scope.launch {
                try {
                    host.ext.mcpUpdate(name, exposure = nextExposure, project = true)
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
            write { PiMcpConfig.setProjectExposureOverride(it, name, nextExposure, globalExposure) }
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
                val report = host.ext.mcpCheck()
                status = PiMcpStatus.Report(
                    servers = report.servers.map {
                        PiMcpStatus.Server(
                            name = it.name,
                            scope = "",
                            enabled = it.state != "disabled",
                            state = it.state,
                            tools = it.tools,
                            toolExposure = it.toolExposure,
                            resources = it.resources,
                            error = it.error,
                        )
                    },
                    errors = report.errors,
                    note = report.note,
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

    fun cancelOAuth() {
        val activeId = loginId
        val activeRedirect = loginUi?.redirectRequestId
        loginJob?.cancel()
        loginJob = null
        loginId = null
        loginUi = null
        val host = access()
        if (host != null && (activeId != null || activeRedirect != null)) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                if (activeRedirect != null) {
                    runCatching { host.respond(activeRedirect, null, true) }
                }
                if (activeId != null) {
                    runCatching { host.ext.mcpCancelLogin(activeId) }
                }
            }
        }
    }

    // 离开页面时若 MCP OAuth 登录仍在进行，用独立非取消协程取消挂起请求并通知宿主 mcpCancelLogin，避免下次报 ERR.BUSY。
    DisposableEffect(Unit) {
        onDispose {
            val activeId = loginId
            val activeRedirect = loginUi?.redirectRequestId
            loginJob?.cancel()
            val host = access()
            if (host != null && (activeId != null || activeRedirect != null)) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    if (activeRedirect != null) {
                        runCatching { host.respond(activeRedirect, null, true) }
                    }
                    if (activeId != null) {
                        runCatching { host.ext.mcpCancelLogin(activeId) }
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
                            loginUi = (loginUi ?: LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Waiting, null, ""))
                                .copy(url = auth.url)
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
                epoch++
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
    val oauthSignedInByName = remember(cfgNow) {
        buildMap {
            cfgNow?.globalServers?.forEach { put(it.name, it.oauthSignedIn) }
            cfgNow?.servers?.forEach { put(it.name, it.oauthSignedIn) }
        }
    }
    val definitions = if (cfgNow != null) {
        if (project) {
            cfgNow.servers.filter { it.isProject && it.override == null }.map(::hostServerToConfig)
        } else {
            cfgNow.globalServers.filter { it.scope != "extension" }.map(::hostServerToConfig)
        }
    } else {
        doc.servers.filter { !project || it.kind != PiMcpConfig.Kind.Override }
    }
    val extensionServers = remember(cfgNow) {
        cfgNow?.servers?.filter { it.scope == "extension" }.orEmpty()
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
                    onClick = {
                        editSaveError = null
                        editing = Editing(null, PiMcpConfig.Draft(http = true, url = "https://"))
                    },
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
            item(key = "scope") {
                McpSegmentedTrack(
                    options = listOf(tr("全局", "Global"), tr("本项目", "Project")),
                    selectedIndex = if (project) 1 else 0,
                    onSelect = { project = it == 1 },
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            item(key = "overview") {
                val palette = PiTheme.palette
                val enabledCount = definitions.count { it.enabled }
                val healthSummary = status?.let { report ->
                    val enabled = report.servers.filter { it.enabled }
                    val ok = enabled.count { it.connected }
                    val auth = enabled.count { it.needsAuth }
                    val failed = enabled.size - ok - auth
                    val text = tr("$ok / ${enabled.size} 已连接", "$ok / ${enabled.size} connected") +
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
                                vertical = 14.dp,
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
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(
                                        PiSpacing.hairlineThin,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                        RoundedCornerShape(10.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(19.dp),
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
                                    healthSummary?.first ?: tr(
                                        "已启用 $enabledCount / ${definitions.size} 个 MCP 服务",
                                        "$enabledCount / ${definitions.size} MCP servers enabled",
                                    ),
                                    style = PiTheme.text.meta,
                                    color = healthSummary?.second ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (project && cfgNow != null && !cfgNow.projectTrusted) {
                                PiSettingsBadge(label = tr("未信任", "Untrusted"), tone = palette.warning)
                            } else if (definitions.isNotEmpty()) {
                                PiSettingsBadge(
                                    label = "$enabledCount/${definitions.size}",
                                    tone = if (enabledCount > 0) palette.accent else palette.muted,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PiPrimaryButton(
                                label = tr("添加服务", "Add"),
                                compact = true,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    editSaveError = null
                                    editing = Editing(null, PiMcpConfig.Draft(http = true, url = "https://"))
                                },
                            )
                            PiSecondaryButton(
                                label = if (checking) tr("检测中…", "Checking…") else tr("检测连接", "Check"),
                                enabled = !checking,
                                compact = true,
                                modifier = Modifier.weight(1f),
                                onClick = { runCheck() },
                            )
                            PiSecondaryButton(
                                label = tr("导入 JSON", "Import"),
                                compact = true,
                                modifier = Modifier.weight(1f),
                                onClick = { importing = true },
                            )
                        }
                    }
                }
            }
            importNote?.let { note ->
                item(key = "import-note") {
                    PiSettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = PiSettingsMetrics.rowPaddingVertical,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        ) {
                            Text(
                                text = note,
                                modifier = Modifier.weight(1f),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            PiSecondaryButton(
                                label = tr("知道了", "Dismiss"),
                                compact = true,
                                onClick = { importNote = null },
                            )
                        }
                    }
                }
            }
            if (doc.corrupt) {
                item(key = "corrupt") {
                    PiInfoNote(
                        tr("这份 mcp.json 不是合法的 JSON，请检查 ~/.pi/agent/mcp.json 或 .pi/mcp.json 后再编辑。", "This mcp.json is not valid JSON. Fix ~/.pi/agent/mcp.json or .pi/mcp.json before editing here."),
                        tone = PiTheme.palette.error,
                    )
                }
            }
            if (project && cfgNow != null && !cfgNow.projectTrusted) {
                item(key = "untrusted") {
                    PiInfoNote(
                        tr("当前项目尚未被信任，pi 暂不加载 .pi/mcp.json；信任项目后即生效。", "This project is not trusted yet, so pi will not load .pi/mcp.json until you trust it."),
                        tone = PiTheme.palette.warning,
                    )
                }
            }
            if (cfgNow != null && cfgNow.errors.isNotEmpty()) {
                item(key = "cfg-errors") {
                    PiInfoNote(
                        cfgNow.errors.joinToString("\n"),
                        tone = PiTheme.palette.error,
                    )
                }
            }
            error?.let { message ->
                item(key = "error") { PiInfoNote(message, tone = PiTheme.palette.error) }
            }
            if (pendingRestart) {
                item(key = "pending-reload") {
                    PiSettingsCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = 12.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(PiTheme.palette.accent),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tr("配置已保存，尚未重载", "Saved — Pending Reload"),
                                    style = PiType.rowTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    tr("热重载立即生效（不重启引擎）", "Hot-reload applies immediately without restarting the engine"),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            PiPrimaryButton(
                                label = if (reloading) tr("重载中…", "Reloading…") else tr("立即生效", "Reload"),
                                enabled = !reloading,
                                compact = true,
                                onClick = { reload() },
                            )
                        }
                    }
                }
            }
            checkFailure?.let { message -> item(key = "check-failure") { PiInfoNote(message, tone = MaterialTheme.colorScheme.error) } }
            status?.let { report ->
                if (report.errors.isNotEmpty() || report.note != null) {
                    item(key = "report-note") {
                        PiInfoNote(
                            (report.errors + listOfNotNull(report.note)).joinToString("\n"),
                            tone = if (report.errors.isNotEmpty()) MaterialTheme.colorScheme.error else null,
                        )
                    }
                }
            }
            item(key = "servers") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(
                        title = if (project) tr("本项目服务器", "Project Servers") else tr("已配置服务器", "Configured Servers"),
                    )
                    if (definitions.isEmpty()) {
                        PiSettingsCard {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                        vertical = 14.dp,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                            ) {
                                Text(
                                    tr("还没有配置 MCP 服务器", "No MCP Servers Configured"),
                                    style = PiType.rowTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    tr("按需添加远程 HTTP 服务、本地 STDIO 命令，或直接导入 Claude / Cursor / VS Code 的 mcpServers JSON。", "Add a remote HTTP server, a local STDIO command, or import mcpServers JSON from Claude, Cursor, or VS Code."),
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                                    verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
                                ) {
                                    PiSecondaryButton(
                                        label = tr("＋ 远程服务 (HTTP)", "+ Remote HTTP"),
                                        compact = true,
                                        onClick = {
                                            editSaveError = null
                                            editing = Editing(null, PiMcpConfig.Draft(http = true, url = "https://"))
                                        },
                                    )
                                    PiSecondaryButton(
                                        label = tr("＋ 本地命令 (STDIO)", "+ Local STDIO"),
                                        compact = true,
                                        onClick = {
                                            editSaveError = null
                                            editing = Editing(null, PiMcpConfig.Draft(http = false, command = "npx", args = "-y\n"))
                                        },
                                    )
                                    PiPrimaryButton(
                                        label = tr("导入 JSON", "Import JSON"),
                                        compact = true,
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
                                    oauthSignedIn = oauthSignedInByName[server.name] == true,
                                    onLogin = { startLogin(server.name) },
                                    onOpen = {
                                        editSaveError = null
                                        editing = Editing(server.name, PiMcpConfig.toDraft(server))
                                    },
                                    onToggle = { on -> updateServerEnabled(server.name, on) },
                                )
                            }
                        }
                    }
                }
            }

            val autoCodemodeChecked = cfgNow?.let {
                if (project) it.autoEnableCodemode else it.globalAutoEnableCodemode
            } ?: PiMcpConfig.autoEnableCodemode(doc.root)
            item(key = "codemode-auto") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                    PiSettingsSectionHeader(tr("行为", "Behavior"))
                    PiSettingsCard {
                        PiSettingsToggleRow(
                            title = tr("自动启用代码模式", "Auto-Enable Code Mode"),
                            supporting = tr("有 codemode 暴露的服务器连上时自动打开 codemode；关掉则需自己在工具里启用", "Automatically enables codemode when a codemode-exposed server connects"),
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
            }
            if (project) {
                val inheritedRows: List<InheritedGlobalRow> = if (cfgNow != null) {
                    val fullProjectNames = cfgNow.servers.filter { it.isProject && it.override == null }.map { it.name }.toSet()
                    val mergedByName = cfgNow.servers.associateBy { it.name }
                    cfgNow.globalServers
                        .filter { it.scope != "extension" && it.name !in fullProjectNames }
                        .map { global ->
                            val merged = mergedByName[global.name]
                            val effectiveEnabled = merged?.enabled ?: global.enabled
                            val effectiveExposure = merged?.exposure ?: global.exposure
                            val overridden = merged?.override != null ||
                                doc.server(global.name)?.config?.let { it.containsKey("enabled") || it.containsKey("exposure") } == true
                            InheritedGlobalRow(
                                name = global.name,
                                effectiveEnabled = effectiveEnabled,
                                globalEnabled = global.enabled,
                                effectiveExposure = effectiveExposure,
                                globalExposure = global.exposure,
                                overridden = overridden,
                            )
                        }
                } else {
                    userDoc.servers.filter { global ->
                        global.kind != PiMcpConfig.Kind.Override &&
                            doc.server(global.name)?.kind.let { it == null || it == PiMcpConfig.Kind.Override }
                    }.map { global ->
                        val override = doc.server(global.name)
                        val effectiveEnabled = override?.config?.get("enabled")
                            ?.let { (it as? JsonPrimitive)?.content != "false" }
                            ?: global.enabled
                        val effectiveExposure = override?.config?.get("exposure")
                            ?.let { PiMcpConfig.normalizeExposure((it as? JsonPrimitive)?.content) }
                            ?: global.exposure
                        val overridden = override != null &&
                            (override.config.containsKey("enabled") || override.config.containsKey("exposure"))
                        InheritedGlobalRow(
                            name = global.name,
                            effectiveEnabled = effectiveEnabled,
                            globalEnabled = global.enabled,
                            effectiveExposure = effectiveExposure,
                            globalExposure = global.exposure,
                            overridden = overridden,
                        )
                    }
                }
                if (inheritedRows.isNotEmpty()) {
                    item(key = "inherited") {
                        Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                            PiSettingsSectionHeader(tr("全局服务器在本项目里（可单独覆盖启停与暴露）", "Global Servers in This Project (Override Enabled & Exposure)"))
                            PiSettingsCard {
                                inheritedRows.forEachIndexed { index, row ->
                                    if (index > 0) PiSettingsHairline()
                                    InheritedServerRow(
                                        row = row,
                                        onToggle = { on ->
                                            updateServerEnabled(
                                                row.name,
                                                on,
                                                isInheritedOverride = true,
                                                globalEnabled = row.globalEnabled,
                                            )
                                        },
                                        onCycleExposure = {
                                            val curIdx = PiMcpConfig.EXPOSURES.indexOf(row.effectiveExposure).coerceAtLeast(0)
                                            val next = PiMcpConfig.EXPOSURES[(curIdx + 1) % PiMcpConfig.EXPOSURES.size]
                                            updateInheritedExposure(row.name, next, row.globalExposure)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (extensionServers.isNotEmpty()) {
                item(key = "extension-servers") {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupHeaderGap)) {
                        PiSettingsSectionHeader(
                            title = tr("扩展注册的服务器 · ${extensionServers.size}", "Extension-Registered Servers · ${extensionServers.size}"),
                            trailing = {
                                PiSettingsBadge(label = tr("只读", "Read-only"), tone = PiTheme.palette.muted)
                            },
                        )
                        PiSettingsCard {
                            extensionServers.forEachIndexed { index, srv ->
                                if (index > 0) PiSettingsHairline()
                                ExtensionMcpServerRow(
                                    server = srv,
                                    state = status?.of(srv.name),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    loginUi?.let { ui ->
        McpOAuthSheet(
            ui = ui,
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
            onSubmitRedirect = { pasted ->
                val requestId = ui.redirectRequestId ?: return@McpOAuthSheet
                loginUi = loginUi?.copy(redirectRequestId = null)
                scope.launch {
                    runCatching {
                        access()?.respond?.invoke(
                            requestId,
                            JsonPrimitive(pasted),
                            false,
                        )
                    }
                }
            },
            onCancel = { cancelOAuth() },
            onClose = { loginUi = null },
        )
    }

    if (importing) {
        McpImportSheet(
            onDismiss = { importing = false },
            onImport = { text, fallbackName ->
                val host = access()
                if (host != null) {
                    scope.launch {
                        try {
                            val r = host.ext.mcpImport(text, project, fallbackName)
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
                        PiMcpConfig.importJson(root, text, fallbackName).also { result = it }.root
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
        val existingServer = current.oldName?.let { name -> editServers.firstOrNull { it.name == name } }
        val hasExplicitAuth = current.draft.bearer.isNotBlank() ||
            current.draft.authProvider.isNotBlank() ||
            PiMcpConfig.parseHeaders(current.draft.headers).map.keys.any { it.equals("Authorization", ignoreCase = true) } ||
            existingServer?.config?.get("auth") != null
        val canUseOauth = current.oldName != null && current.draft.http && !hasExplicitAuth
        McpEditSheet(
            initial = current.draft,
            state = current.oldName?.let { status?.of(it) },
            oauthSignedIn = current.oldName?.let { oauthSignedInByName[it] == true } == true,
            isNew = current.oldName == null,
            isProject = project,
            otherNames = editServers.map { it.name }.filter { it != current.oldName },
            saveError = editSaveError,
            onDismiss = {
                editSaveError = null
                editing = null
            },
            onSave = { draft ->
                val base = current.oldName?.let { name -> editServers.firstOrNull { it.name == name }?.config ?: doc.server(name)?.config }
                val config = PiMcpConfig.toConfig(draft, base)
                val targetName = draft.name.trim()
                val host = access()
                if (host != null) {
                    scope.launch {
                        try {
                            host.ext.mcpSave(targetName, current.oldName, config, project)
                            editSaveError = null
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
                            editSaveError = e.message ?: tr("保存失败", "Failed to save")
                        }
                    }
                } else {
                    write { PiMcpConfig.upsert(it, current.oldName, targetName, config) }
                    editSaveError = null
                    editing = null
                    if (current.oldName == null && draft.http && !activeStore.read().corrupt) runCheck()
                }
            },
            onLogin = current.oldName?.takeIf { canUseOauth }?.let { name ->
                {
                    editSaveError = null
                    editing = null
                    startLogin(name)
                }
            },
            onLogout = current.oldName?.takeIf { canUseOauth }?.let { name ->
                {
                    editSaveError = null
                    editing = null
                    loginUi = LoginUi(LoginUi.Kind.Logout, name, LoginUi.Phase.Waiting, null, "")
                    scope.launch {
                        loginUi = try {
                            val message = access()?.ext?.mcpLogout(name, project) ?: tr("已退出登录。", "Signed out.")
                            status = null
                            epoch++
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
                                editSaveError = null
                                error = null
                                status = null
                                pendingRestart = true
                                onFilesWritten()
                                epoch++
                                editing = null
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                editSaveError = e.message ?: tr("删除失败", "Failed to delete")
                            }
                        }
                    } else {
                        write { PiMcpConfig.remove(it, name) }
                        editSaveError = null
                        editing = null
                    }
                }
            },
        )
    }
}

private data class InheritedGlobalRow(
    val name: String,
    val effectiveEnabled: Boolean,
    val globalEnabled: Boolean,
    val effectiveExposure: String,
    val globalExposure: String,
    val overridden: Boolean,
)

private fun hostServerToConfig(server: HostExtensions.McpServer): PiMcpConfig.Server {
    val map = LinkedHashMap<String, JsonElement>()
    server.type?.let { map["type"] = JsonPrimitive(it) }
    server.command?.let { map["command"] = JsonPrimitive(it) }
    if (server.args.isNotEmpty()) map["args"] = JsonArray(server.args.map(::JsonPrimitive))
    server.env?.let { map["env"] = it }
    server.cwd?.let { map["cwd"] = JsonPrimitive(it) }
    server.url?.let { map["url"] = JsonPrimitive(it) }
    server.headers?.let { map["headers"] = it }
    server.auth?.let { map["auth"] = it }
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

/** 统一旗舰设置风格分段切换器（抬升滑块 + 发丝描边 + 轻触反馈）。 */
@Composable
internal fun McpSegmentedTrack(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val isDark = PiTheme.isDark
    val trackBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    val activeBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
    val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.55f else 0.72f)
    val activeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.75f else 0.90f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = trackBg,
        border = BorderStroke(PiSpacing.hairlineThin, trackBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEachIndexed { idx, label ->
                val active = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (active) activeBg else Color.Transparent)
                        .border(
                            width = if (active) PiSpacing.hairlineThin else 0.dp,
                            color = if (active) activeBorder else Color.Transparent,
                            shape = RoundedCornerShape(7.dp),
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(idx)
                        }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = PiTheme.text.meta.copy(
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 克制单色图标槽 + 细腻状态圆点 + 协议/暴露/OAuth/工具微标 + 开关；点行进编辑。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpServerRow(
    server: PiMcpConfig.Server,
    state: PiMcpStatus.Server?,
    oauthSignedIn: Boolean,
    onLogin: () -> Unit,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val palette = PiTheme.palette
    val dotColor = when {
        !server.enabled -> palette.dim.copy(alpha = 0.45f)
        state == null -> if (oauthSignedIn) palette.success.copy(alpha = 0.85f) else palette.muted.copy(alpha = 0.55f)
        state.connected -> palette.success
        state.needsAuth -> palette.warning
        else -> palette.error
    }
    val isHttp = server.kind == PiMcpConfig.Kind.Http
    val authProvider = server.authProvider
    val hasAuthHeader = (server.config["headers"] as? JsonObject)?.keys?.any {
        it.equals("Authorization", ignoreCase = true)
    } == true
    val oauthEligible = isHttp && authProvider == null && server.config["auth"] == null && !hasAuthHeader
    val showOauthTag = oauthEligible && (state?.needsAuth == true || oauthSignedIn || server.config["oauth"] != null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(38.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        PiSpacing.hairlineThin,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isHttp) Icons.Filled.Language else Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = if (server.enabled) MaterialTheme.colorScheme.onSurface else palette.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                McpMetaTag(text = if (isHttp) "HTTP" else "STDIO")
                if (server.exposure != "codemode") {
                    McpMetaTag(text = mcpExposureShortLabel(server.exposure), tone = palette.accent)
                }
            }
            Text(
                server.summary,
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (authProvider != null || showOauthTag || state != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (authProvider != null) {
                        McpMetaTag(text = tr("凭据：$authProvider", "Auth: $authProvider"))
                    }
                    if (showOauthTag) {
                        when {
                            state?.needsAuth == true -> McpMetaTag(
                                text = tr("待 OAuth 登录", "OAuth Required"),
                                tone = palette.warning,
                            )
                            oauthSignedIn -> McpMetaTag(
                                text = tr("OAuth 已登录", "OAuth Signed In"),
                                tone = palette.success,
                            )
                            else -> McpMetaTag(text = "OAuth")
                        }
                    }
                    if (state != null) {
                        Text(
                            PiMcpStatus.stateLabel(state),
                            style = PiTheme.text.meta,
                            color = when {
                                state.connected -> palette.success
                                state.needsAuth -> palette.warning
                                !state.enabled -> palette.muted
                                else -> palette.error
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (state?.needsAuth == true && server.enabled && oauthEligible) {
            PiPrimaryButton(label = tr("登录", "Sign In"), compact = true, onClick = onLogin)
        }
        PiSettingsSwitch(checked = server.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun InheritedServerRow(
    row: InheritedGlobalRow,
    onToggle: (Boolean) -> Unit,
    onCycleExposure: () -> Unit,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = row.name,
                style = PiType.rowTitle,
                color = if (row.effectiveEnabled) MaterialTheme.colorScheme.onSurface else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (row.overridden) tr("本项目已覆盖", "Overridden in project") else tr("沿用全局设置", "Inherits global setting"),
                style = PiTheme.text.meta,
                color = if (row.overridden) palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCycleExposure()
            },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                PiSpacing.hairlineThin,
                if (row.effectiveExposure != row.globalExposure) palette.accent.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Text(
                text = mcpExposureShortLabel(row.effectiveExposure),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = if (row.effectiveExposure != row.globalExposure) palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
        PiSettingsSwitch(checked = row.effectiveEnabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun ExtensionMcpServerRow(
    server: HostExtensions.McpServer,
    state: PiMcpStatus.Server?,
) {
    val palette = PiTheme.palette
    val sourceName = remember(server.source) {
        server.source.substringAfterLast('/').ifBlank { server.source }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    PiSpacing.hairlineThin,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = server.name,
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                McpMetaTag(text = if (!server.url.isNullOrBlank()) "HTTP" else "STDIO")
                McpMetaTag(text = mcpExposureShortLabel(server.exposure))
            }
            Text(
                text = server.url ?: listOfNotNull(server.command, *server.args.toTypedArray()).joinToString(" ").ifBlank { sourceName },
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state?.let { PiMcpStatus.stateLabel(it) }
                    ?: tr("来源扩展：$sourceName", "Registered by extension: $sourceName"),
                style = PiTheme.text.meta,
                color = when {
                    state?.connected == true -> palette.success
                    state?.needsAuth == true -> palette.warning
                    state != null -> palette.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun McpMetaTag(text: String, tone: Color? = null) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (tone != null) tone.copy(alpha = 0.11f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            PiSpacing.hairlineThin,
            if (tone != null) tone.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Text(
            text = text,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 统一 MCP OAuth 授权 / 登出底抽屉：
 * 合并原来的拆分弹窗与回跳粘贴抽屉——在等待授权期间同时展示
 * 「步骤 1 · 浏览器授权（打开 / 复制链接）」与「步骤 2 · 手动粘贴回调 URL（备用）」，
 * 关闭或取消时统一触发宿主 cancel + 挂起回调拒绝，绝不锁死后续登录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpOAuthSheet(
    ui: LoginUi,
    onOpenUrl: (String) -> Unit,
    onSubmitRedirect: (String) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = PiTheme.palette
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val waiting = ui.phase == LoginUi.Phase.Waiting
    var copied by remember(ui.url) { mutableStateOf(false) }
    var pasted by remember(ui.redirectRequestId) { mutableStateOf("") }

    PiSheet(
        onDismiss = { if (waiting) onCancel() else onClose() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 6.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部状态头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal + 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            PiSpacing.hairlineThin,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when (ui.phase) {
                        LoginUi.Phase.Waiting -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = palette.accent,
                        )
                        LoginUi.Phase.Done -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = palette.success,
                            modifier = Modifier.size(20.dp),
                        )
                        LoginUi.Phase.Failed -> Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = palette.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when (ui.kind) {
                            LoginUi.Kind.Login -> tr("OAuth 授权 · ${ui.server}", "OAuth Sign-In · ${ui.server}")
                            LoginUi.Kind.Logout -> tr("退出登录 · ${ui.server}", "Sign Out · ${ui.server}")
                        },
                        style = PiType.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (ui.phase) {
                            LoginUi.Phase.Waiting -> when {
                                ui.kind == LoginUi.Kind.Logout -> tr("正在清除本地 OAuth 凭据…", "Clearing local OAuth credentials…")
                                ui.url == null -> tr("正在发现 OAuth 端点并准备授权链接…", "Discovering OAuth endpoints…")
                                else -> tr("请在浏览器完成授权后返回（最长等待 5 分钟）", "Complete authorization in your browser (up to 5 minutes)")
                            }
                            LoginUi.Phase.Done -> tr("操作已完成", "Completed")
                            LoginUi.Phase.Failed -> tr("未能完成", "Failed")
                        },
                        style = PiTheme.text.meta,
                        color = when (ui.phase) {
                            LoginUi.Phase.Done -> palette.success
                            LoginUi.Phase.Failed -> palette.error
                            LoginUi.Phase.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (waiting && ui.kind == LoginUi.Kind.Login) {
                // 步骤 1：浏览器授权
                PiSettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = tr("步骤 1 · 在浏览器完成授权", "Step 1 · Authorize in Browser"),
                            style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (ui.url == null) {
                            Text(
                                text = tr("正在连接 MCP 服务器并生成 PKCE 授权链接，请稍候…", "Connecting to the MCP server and generating a PKCE authorization URL…"),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = BorderStroke(
                                    PiSpacing.hairlineThin,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                ),
                            ) {
                                Text(
                                    text = ui.url,
                                    style = PiTheme.text.monoSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PiPrimaryButton(
                                    label = tr("打开浏览器授权", "Open Browser"),
                                    compact = true,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenUrl(ui.url) },
                                )
                                PiSecondaryButton(
                                    label = if (copied) tr("已复制", "Copied") else tr("复制链接", "Copy Link"),
                                    compact = true,
                                    onClick = {
                                        val cm = context.getSystemService(ClipboardManager::class.java)
                                        cm?.setPrimaryClip(ClipData.newPlainText("MCP OAuth URL", ui.url))
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        copied = true
                                    },
                                )
                            }
                        }
                    }
                }

                // 步骤 2：手动回调地址（仅在宿主已发出 redirect 请求时显示，作自动回调失败的备用通道）
                if (ui.redirectRequestId != null) {
                    PiSettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = tr("步骤 2 · 手动粘贴回调地址（备用）", "Step 2 · Paste Callback URL (Fallback)"),
                                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Surface(
                                    onClick = {
                                        val cm = context.getSystemService(ClipboardManager::class.java)
                                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                        if (clip.isNotEmpty()) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            pasted = clip
                                        }
                                    },
                                    shape = RoundedCornerShape(7.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        PiSpacing.hairlineThin,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                    ),
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
                            Text(
                                text = tr(
                                    "多数情况下浏览器会自动跳回完成授权；若停在无法访问的页面，复制地址栏里的完整 URL 粘到下方提交即可。",
                                    "Usually the browser redirects automatically; if it stops on an unreachable page, copy the full URL from the address bar and paste it here.",
                                ),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PiEditorField(
                                value = pasted,
                                onValueChange = { pasted = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "http://127.0.0.1:.../callback?code=...",
                                singleLine = true,
                                height = PiNumberFieldHeight,
                                container = MaterialTheme.colorScheme.surfaceContainerLowest,
                                idleBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                            )
                            PiPrimaryButton(
                                label = tr("提交回调地址", "Submit Callback URL"),
                                enabled = pasted.isNotBlank(),
                                compact = true,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onSubmitRedirect(pasted.trim()) },
                            )
                        }
                    }
                }
            } else if (!waiting) {
                PiSettingsCard {
                    Text(
                        text = ui.message.ifBlank {
                            if (ui.phase == LoginUi.Phase.Done) tr("已完成。", "Done.") else tr("操作失败。", "Operation failed.")
                        },
                        style = PiTheme.text.meta,
                        color = if (ui.phase == LoginUi.Phase.Failed) palette.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    )
                }
            }

            // 底部按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSettingsMetrics.pageHorizontal),
            ) {
                if (waiting) {
                    PiSecondaryButton(
                        label = tr("取消授权", "Cancel"),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCancel,
                    )
                } else {
                    PiPrimaryButton(
                        label = if (ui.phase == LoginUi.Phase.Done) tr("完成", "Done") else tr("知道了", "OK"),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onClose,
                    )
                }
            }
        }
    }
}

