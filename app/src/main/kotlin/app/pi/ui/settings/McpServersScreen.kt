package app.pi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import app.pi.packages.AgentLayout
import app.pi.packages.GuestCommand
import app.pi.packages.PiMcpLogin
import app.pi.runtime.PtyLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.pi.packages.PiMcpConfig
import app.pi.packages.PiMcpFile
import app.pi.packages.PiMcpCheck
import app.pi.packages.PiMcpStatus
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
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
    onRestartEngine: () -> Unit,
    onFilesWritten: () -> Unit,
) {
    // 登录 / 退出登录：跑 pi 自己的 `pi mcp login|logout`（见 [PiMcpLogin]）。
    val context = LocalContext.current
    val hostWorkspace = PtyLauncher.workspaceHost(context)
    val mcpLogin = remember(context, hostWorkspace.absolutePath) {
        val layout = AgentLayout(context = context.applicationContext, hostWorkspace = hostWorkspace)
        PiMcpLogin(layout, GuestCommand(layout))
    }
    val mcpCheck = remember(context, hostWorkspace.absolutePath) {
        val layout = AgentLayout(context = context.applicationContext, hostWorkspace = hostWorkspace)
        PiMcpCheck(layout, GuestCommand(layout))
    }
    // 「检测连接」的结果：只在用户点了才去连服务器；改过配置后它就过期了，会被清掉。
    var status by remember { mutableStateOf<PiMcpStatus.Report?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var loginUi by remember { mutableStateOf<LoginUi?>(null) }
    val userStore = remember(userFile) { PiMcpFile(userFile) }
    val projectStore = remember(projectFile) { PiMcpFile(projectFile) }
    // 每次写盘后 +1：文件不是快照状态，靠这个让界面重读（与 `PiSettingsStack.writeEpoch` 同理）。
    var epoch by remember { mutableIntStateOf(0) }
    var project by remember { mutableStateOf(false) }
    var pendingRestart by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importNote by remember { mutableStateOf<String?>(null) }
    // 正在编辑：`oldName == null` 表示新增。
    var editing by remember { mutableStateOf<Editing?>(null) }

    val activeStore = if (project) projectStore else userStore
    // `epoch` 在这里被读，所以它一变这两份就重读磁盘。
    val userDoc = remember(userStore, epoch) { userStore.read() }
    val doc = remember(activeStore, epoch) { activeStore.read() }

    fun write(transform: (kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject) {
        val failure = activeStore.update(transform)
        error = failure
        if (failure == null) {
            status = null
            pendingRestart = true
            onFilesWritten()
        }
        epoch++
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = "MCP 服务器",
            onBack = onBack,
            actions = {
                PiTopBarIcon(
                    onClick = { picking = true },
                    contentDescription = "添加服务器",
                    icon = Icons.Filled.Add,
                )
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            item {
                Row(
                    Modifier.padding(
                        horizontal = PiSettingsMetrics.pageHorizontal,
                        vertical = PiSpacing.inline,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                ) {
                    PiSettingsChip(text = "全局", active = !project, onClick = { project = false })
                    PiSettingsChip(text = "本项目", active = project, onClick = { project = true })
                }
            }
            item {
                PiInfoNote(
                    if (project) {
                        "写入本项目的 .pi/mcp.json，只有项目被信任时 pi 才会读它；同名条目会整条替换全局的。"
                    } else {
                        "写入全局的 mcp.json，所有项目共用。服务器在会话开始时连接，改完要新会话或重启引擎才生效。"
                    },
                )
            }
            if (doc.corrupt) {
                item {
                    PiInfoNote(
                        "这份 mcp.json 不是合法的 JSON，为了不弄丢里面的内容，这里不会改它。" +
                            "请到「其他 → Pi 文件」里修好或删掉。",
                        tone = MaterialTheme.colorScheme.error,
                    )
                }
            }
            error?.let { message ->
                item { PiInfoNote(message, tone = MaterialTheme.colorScheme.error) }
            }
            if (pendingRestart) {
                item {
                    PiSettingsCard {
                        PiSettingsEntryRow(
                            title = "让改动生效",
                            supporting = "新会话会自动读取；想马上用，点这里重启引擎",
                            onClick = onRestartEngine,
                        )
                    }
                }
            }

            item {
                PiSettingsCard {
                    PiSettingsEntryRow(
                        title = if (checking) "正在检测…" else "检测连接",
                        supporting = "真的去连每个已启用的服务器（等同 pi mcp list），看连没连上、有哪些工具、失败原因",
                        onClick = {
                            if (!checking) {
                                checking = true
                                checkFailure = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { mcpCheck.run() }
                                    when (result) {
                                        is PiMcpCheck.Result.Ok -> status = result.report
                                        is PiMcpCheck.Result.Failed -> checkFailure = result.message
                                    }
                                    checking = false
                                }
                            }
                        },
                    )
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
            // 本项目页里的服务器是「完整定义」；覆盖条目（只含 enabled/exposure）单独显示在下面。
            val definitions = doc.servers.filter { !project || it.kind != PiMcpConfig.Kind.Override }
            item {
                if (definitions.isEmpty()) {
                    PiSettingsCard {
                        PiSettingsEntryRow(
                            title = "还没有服务器",
                            supporting = "点右上角 ＋，选一个常用的，或填自己的",
                            onClick = { picking = true },
                        )
                    }
                } else {
                    PiSettingsCard {
                        definitions.forEachIndexed { index, server ->
                            if (index > 0) PiSettingsHairline()
                            McpServerRow(
                                server = server,
                                state = status?.of(server.name),
                                onOpen = {
                                    editing = Editing(server.name, PiMcpConfig.toDraft(server))
                                },
                                onToggle = { on ->
                                    if (!doc.corrupt) write { PiMcpConfig.setEnabled(it, server.name, on) }
                                },
                            )
                        }
                    }
                }
            }

            importNote?.let { note -> item { PiInfoNote(note) } }
            item {
                PiSettingsCard {
                    PiSettingsToggleRow(
                        title = "自动启用代码模式",
                        supporting = "有服务器连上时自动打开 codemode；关掉则需自己在工具里启用",
                        checked = PiMcpConfig.autoEnableCodemode(doc.root),
                        onCheckedChange = { on ->
                            if (!doc.corrupt) write { PiMcpConfig.setAutoEnableCodemode(it, on) }
                        },
                    )
                }
            }
            if (project) {
                // 全局服务器在这个项目里的开关：写一条只含 enabled 的覆盖。
                val inherited = userDoc.servers.filter { global ->
                    global.kind != PiMcpConfig.Kind.Override &&
                        doc.server(global.name)?.kind.let { it == null || it == PiMcpConfig.Kind.Override }
                }
                if (inherited.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                "全局服务器在本项目里",
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                                modifier = Modifier.padding(
                                    horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
                                    vertical = PiSettingsMetrics.groupHeaderGap,
                                ),
                            )
                            PiSettingsCard {
                                inherited.forEachIndexed { index, global ->
                                    if (index > 0) PiSettingsHairline()
                                    val override = doc.server(global.name)
                                    val effective = override?.config?.get("enabled")
                                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content != "false" }
                                        ?: global.enabled
                                    PiSettingsToggleRow(
                                        title = global.name,
                                        supporting = if (override != null && override.config.containsKey("enabled")) {
                                            "本项目已覆盖"
                                        } else {
                                            "沿用全局设置"
                                        },
                                        checked = effective,
                                        onCheckedChange = { on ->
                                            if (!doc.corrupt) {
                                                write {
                                                    PiMcpConfig.setProjectOverride(it, global.name, on, global.enabled)
                                                }
                                            }
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
                LoginUi.Kind.Login -> "登录「${ui.server}」"
                LoginUi.Kind.Logout -> "退出登录「${ui.server}」"
            },
            body = when (ui.phase) {
                LoginUi.Phase.Waiting -> if (ui.url == null) {
                    "正在连接服务器…"
                } else {
                    "已在浏览器里打开授权页，授权后回到这里即可，最长等 3 分钟。\n\n${ui.url}"
                }
                LoginUi.Phase.Done -> ui.message
                LoginUi.Phase.Failed -> ui.message
            },
            confirmationLabel = when {
                waiting && ui.url != null -> "再打开一次"
                waiting -> null
                else -> "知道了"
            },
            onConfirm = {
                if (waiting) ui.url?.let { uriHandler.openUri(it) } else loginUi = null
            },
            dismissalLabel = if (waiting) "不等了" else null,
            onDismissButton = { loginUi = null },
        )
    }

    if (importing) {
        McpImportSheet(
            onDismiss = { importing = false },
            onImport = { text ->
                var result: PiMcpConfig.Imported? = null
                write { root ->
                    PiMcpConfig.importJson(root, text, "imported").also { result = it }.root
                }
                val r = result
                importNote = when {
                    r == null -> null
                    r.error != null -> r.error
                    else -> buildString {
                        append(if (r.added.isEmpty()) "没有新增。" else "已添加：${r.added.joinToString("、")}。")
                        if (r.skipped.isNotEmpty()) append("\n跳过：").append(r.skipped.joinToString("；"))
                    }
                }
                importing = false
            },
        )
    }

    editing?.let { current ->
        McpEditSheet(
            initial = current.draft,
            state = current.oldName?.let { status?.of(it) },
            isNew = current.oldName == null,
            otherNames = doc.servers.map { it.name }.filter { it != current.oldName },
            onDismiss = { editing = null },
            onSave = { draft ->
                val base = current.oldName?.let { doc.server(it)?.config }
                val config = PiMcpConfig.toConfig(draft, base)
                write { PiMcpConfig.upsert(it, current.oldName, draft.name.trim(), config) }
                editing = null
            },
            onLogin = current.oldName?.takeIf { current.draft.http }?.let { name ->
                {
                    editing = null
                    loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Waiting, null, "")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            mcpLogin.login(name) { url ->
                                loginUi = LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Waiting, url, "")
                                scope.launch { runCatching { uriHandler.openUri(url) } }
                            }
                        }
                        loginUi = when (result) {
                            is PiMcpLogin.Result.Ok -> LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Done, null,
                                if (result.alreadySignedIn) "已经是登录状态，无需再登录。" else "登录成功。新会话里就能用它的工具了。")
                            is PiMcpLogin.Result.Failed -> LoginUi(LoginUi.Kind.Login, name, LoginUi.Phase.Failed, null, result.message)
                        }
                        if (result is PiMcpLogin.Result.Ok) pendingRestart = true
                    }
                }
            },
            onLogout = current.oldName?.takeIf { current.draft.http }?.let { name ->
                {
                    editing = null
                    loginUi = LoginUi(LoginUi.Kind.Logout, name, LoginUi.Phase.Waiting, null, "")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { mcpLogin.logout(name) }
                        loginUi = when (result) {
                            is PiMcpLogin.Result.Ok -> LoginUi(LoginUi.Kind.Logout, name, LoginUi.Phase.Done, null, result.message)
                            is PiMcpLogin.Result.Failed -> LoginUi(LoginUi.Kind.Logout, name, LoginUi.Phase.Failed, null, result.message)
                        }
                    }
                }
            },
            onDelete = current.oldName?.let { name ->
                {
                    write { PiMcpConfig.remove(it, name) }
                    editing = null
                }
            },
        )
    }
}

private data class Editing(val oldName: String?, val draft: PiMcpConfig.Draft)

private data class LoginUi(
    val kind: Kind,
    val server: String,
    val phase: Phase,
    val url: String?,
    val message: String,
) {
    enum class Kind { Login, Logout }
    enum class Phase { Waiting, Done, Failed }
}

/** 一行：名字 + 一行小字（命令或网址 · 暴露方式）+ 开关；点行进编辑。 */
@Composable
private fun McpServerRow(
    server: PiMcpConfig.Server,
    state: PiMcpStatus.Server?,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                server.name,
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val exposure = if (server.exposure == "codemode") "" else " · ${exposureLabel(server.exposure)}"
            Text(
                server.summary + exposure,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state != null) {
                Text(
                    PiMcpStatus.stateLabel(state),
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.meta,
                    color = if (state.connected || !state.enabled) PiTheme.palette.muted else MaterialTheme.colorScheme.error,
                )
            }
        }
        PiSettingsSwitch(checked = server.enabled, onCheckedChange = onToggle)
    }
}

private fun exposureLabel(exposure: String): String = when (exposure) {
    "codemode" -> "代码模式（默认）"
    "deferred" -> "按需检索"
    "direct" -> "直接暴露"
    "hidden" -> "隐藏"
    else -> exposure
}

private fun exposureHelp(exposure: String): String = when (exposure) {
    "codemode" -> "工具放进 codemode 脚本里调用，不占提示词（推荐）。"
    "deferred" -> "不在提示词里列出，模型要用时用 tool_search 检索。"
    "direct" -> "每个工具都作为独立工具直接给模型，最方便但最占上下文。"
    "hidden" -> "连接但不暴露任何工具。"
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
                "添加 MCP 服务器",
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
            )
            Text(
                "选一个常用的，名字和参数都可以在下一步改。这些用 npx 启动，需要设备能联网下载。",
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
                        onClick = { onPick(template.draft) },
                    )
                }
                PiSettingsHairline()
                PiSettingsEntryRow(
                    title = "从别处导入（粘贴 JSON）",
                    supporting = "Claude Desktop / Claude Code / Cursor / VS Code / OpenCode 的配置直接贴进来",
                    onClick = onImport,
                )
                PiSettingsHairline()
                PiSettingsEntryRow(
                    title = "命令式（自己填）",
                    supporting = "本地启动一个进程，例如 npx / uvx 包",
                    onClick = { onPick(PiMcpConfig.Draft(command = "npx", args = "-y\n")) },
                )
            }
        }
    }
}

/** 编辑 / 新增表单。字段、校验、写盘形状全在 [PiMcpConfig]；这里只收集输入。 */
@OptIn(ExperimentalMaterial3Api::class)
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
                if (isNew) "新增服务器" else "编辑服务器",
                style = PiType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PiEditorField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                modifier = Modifier.fillMaxWidth(),
                label = "名字（字母、数字、_ 和 -）",
                placeholder = "filesystem",
                height = PiNumberFieldHeight,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                PiSettingsChip(text = "命令式", active = !draft.http, onClick = { draft = draft.copy(http = false) })
                PiSettingsChip(text = "网址式", active = draft.http, onClick = { draft = draft.copy(http = true) })
            }
            if (draft.http) {
                PiEditorField(
                    value = draft.url,
                    onValueChange = { draft = draft.copy(url = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "网址（streamable HTTP，通常以 /mcp 结尾）",
                    placeholder = "https://example.com/mcp",
                    height = PiNumberFieldHeight,
                )
                PiEditorField(
                    value = draft.headers,
                    onValueChange = { draft = draft.copy(headers = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "请求头（每行一个，名称: 值；值里可写 \${变量名}）",
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
                    label = "访问令牌（可选：直接粘贴，或写 \${变量}；留空且服务器要登录则走 OAuth）",
                    height = PiNumberFieldHeight,
                )
                var oauthOpen by remember { mutableStateOf(draft.hasOauth()) }
                PiSettingsChip(
                    text = if (oauthOpen) "收起 OAuth 高级项" else "OAuth 高级项（一般不用填）",
                    active = oauthOpen,
                    onClick = { oauthOpen = !oauthOpen },
                )
                if (oauthOpen) {
                    McpOauthFields(draft) { draft = it }
                }
            } else {
                PiEditorField(
                    value = draft.command,
                    onValueChange = { draft = draft.copy(command = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "命令（只填一个可执行文件）",
                    placeholder = "npx",
                    height = PiNumberFieldHeight,
                )
                PiEditorField(
                    value = draft.args,
                    onValueChange = { draft = draft.copy(args = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "参数（每行一个）",
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
                    label = "环境变量（每行一个，名称=值；值可写 \${变量} 或整条 !命令）",
                    placeholder = "API_KEY=\${MY_API_KEY}",
                    singleLine = false,
                    maxLines = 6,
                    minHeight = PiTextFieldMinHeight,
                    contentPadding = PiFieldPaddingBlock,
                )
                PiEditorField(
                    value = draft.cwd,
                    onValueChange = { draft = draft.copy(cwd = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = "工作目录（可选；相对路径按会话目录，~/ 是家目录）",
                    height = PiNumberFieldHeight,
                )
            }
            PiEditorField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                modifier = Modifier.fillMaxWidth(),
                label = "说明（可选，给你自己看）",
                height = PiNumberFieldHeight,
                textStyle = PiTheme.text.meta,
            )
            PiEditorField(
                value = draft.timeoutSeconds,
                onValueChange = { draft = draft.copy(timeoutSeconds = it) },
                modifier = Modifier.fillMaxWidth(),
                label = "超时（秒，留空 = 60）",
                placeholder = "60",
                height = PiNumberFieldHeight,
            )
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter)) {
                Text("暴露方式", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                    PiMcpConfig.EXPOSURES.forEach { option ->
                        PiSettingsChip(
                            text = exposureLabel(option).substringBefore('（'),
                            active = draft.exposure == option,
                            onClick = { draft = draft.copy(exposure = option) },
                        )
                    }
                }
                Text(
                    exposureHelp(draft.exposure),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
            }
            if (state != null) {
                state.error?.let { Text(it, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.error) }
                if (state.tools.isNotEmpty()) {
                    Text(
                        "这个服务器的工具（${state.tools.size}）：" +
                            state.tools.joinToString("、") { tool ->
                                state.toolExposure[tool]?.let { "$tool［${exposureLabel(it).substringBefore('（')}］" } ?: tool
                            } + "\n要单独设置某个工具，把名字抄到下面。",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
            }
            PiEditorField(
                value = draft.toolExposure,
                onValueChange = { draft = draft.copy(toolExposure = it) },
                modifier = Modifier.fillMaxWidth(),
                label = "单个工具另行设置（每行 工具名=暴露方式，名字可带 * ）",
                placeholder = "search_code=direct\ndelete_*=hidden",
                singleLine = false,
                maxLines = 6,
                minHeight = PiTextFieldMinHeight,
                contentPadding = PiFieldPaddingBlock,
            )
            if (onLogin != null && onLogout != null) {
                Text(
                    "登录（OAuth）：服务器需要账号授权时点这里，会在浏览器里完成；已填访问令牌的不需要。" +
                        "登录用的是已保存的配置，改了网址请先保存。",
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                    PiSettingsChip(text = "登录 / 重新登录", active = true, onClick = onLogin)
                    PiSettingsChip(text = "退出登录", onClick = onLogout)
                }
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
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = {
                    if (problem == null) onSave(draft) else showProblem = true
                }) { Text("保存") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }

    if (confirmDelete && onDelete != null) {
        PiSettingsDialog(
            onDismissRequest = { confirmDelete = false },
            title = "删除「${initial.name}」？",
            body = "只会从这份 mcp.json 里去掉这一条，其他服务器不受影响。",
            confirmationLabel = "删除",
            confirmationTone = MaterialTheme.colorScheme.error,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            dismissalLabel = "取消",
            onDismissButton = { confirmDelete = false },
        )
    }
}

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
            label = "客户端 ID（预注册的客户端）",
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthClientSecret,
            onValueChange = { onChange(draft.copy(oauthClientSecret = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "客户端密钥（可选，可写 \${变量}）",
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthCallbackPort,
            onValueChange = { onChange(draft.copy(oauthCallbackPort = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "回调端口（http://127.0.0.1:端口/callback）",
            placeholder = "8765",
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthCallbackUrl,
            onValueChange = { onChange(draft.copy(oauthCallbackUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "回调地址（可选，仅 localhost / 127.0.0.1 / [::1]）",
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthScope,
            onValueChange = { onChange(draft.copy(oauthScope = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "scope（空格分隔，服务器没声明时才填）",
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthClientName,
            onValueChange = { onChange(draft.copy(oauthClientName = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "客户端名称（默认 pi；有的服务器只认已知客户端）",
            height = PiNumberFieldHeight,
        )
        PiEditorField(
            value = draft.oauthAuthServerUrl,
            onValueChange = { onChange(draft.copy(oauthAuthServerUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "授权服务器元数据地址（服务器宣告错了才填，https）",
            height = PiNumberFieldHeight,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Text(
                "用 CIMD 登记（不做动态注册，不能与客户端 ID/名称同用）",
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
            Text("导入配置", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "把别的客户端的 MCP 配置整段贴进来（含 mcpServers 的那一段，或单个服务器）。" +
                    "同名的不会被覆盖；VS Code 的 \${input:…} 要先改成 \${环境变量名}。",
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
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("导入") }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterBottom))
        }
    }
}
