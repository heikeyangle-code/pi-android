package app.pi.ui.resources

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.ExtensionsAccess
import app.pi.i18n.tr
import app.pi.packages.PiResourceCatalog.Parsed
import app.pi.packages.PiResourceFile
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.PrimaryAction
import app.pi.ui.models.TagPill
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsStore
import app.pi.ui.settings.PiSettingsToggleRow
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 「系统提示词与上下文文件」统一专页（被「设置 → 系统提示词」和「扩展与资源 → 上下文与系统提示」共用）。
 *
 * 彻底消除旧版两大痛点：
 *  1. 消除「两套上下文/两套系统提示词」割裂与边界溢出：统一按 Pi 1.6.0 `buildSystemPromptSections` 真实拼装顺序展示
 *     ① 追加指令（`APPEND_SYSTEM.md`，推荐）→ ② 替换系统提示（`SYSTEM.md`，高级）→ ③ 项目说明文件（`AGENTS.md` / `CLAUDE.md`）；
 *  2. 同时呈现**全局 (`~/.pi/agent`)** 与 **当前项目 (`.pi/` 或项目根)** 槽位：存在即一键进入全屏编辑器、不存在即一键创建并打开，
 *     并在改完后于本页顶部直接提供「重载生效」按钮（`session.reload()`），无需跳回枢纽页再找重载。
 */
@Composable
internal fun SystemPromptHostScreen(
    contentPadding: PaddingValues,
    access: () -> ExtensionsAccess?,
    store: PiSettingsStore,
    onSettingWritten: (String) -> Unit,
    onBack: () -> Unit,
) {
    val host = access()
    val scope = rememberCoroutineScope()
    val model = remember(host) { host?.let { ResourcesModel(it, scope) } }
    var editPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(model) { model?.refresh() }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        if (model == null) {
            PiTopBar(title = tr("系统提示词与上下文", "System Prompt & Context"), onBack = onBack)
            PiInfoNote(
                tr(
                    "引擎还没起来。系统提示词与上下文文件由引擎里的 pi 加载与管理，启动后这里会显示实时状态。",
                    "Engine is not running yet. System prompt and context files are loaded by pi once the engine starts.",
                ),
            )
            return@Column
        }
        val pathToEdit = editPath
        if (pathToEdit != null) {
            ResourceFileEditor(
                path = pathToEdit,
                model = model,
                contentPadding = contentPadding,
                onClose = {
                    editPath = null
                    model.refresh()
                },
                onDeleted = {
                    editPath = null
                    model.refresh()
                },
            )
        } else {
            ContextScreen(
                model = model,
                contentPadding = contentPadding,
                onBack = onBack,
                onEdit = { editPath = it },
                store = store,
                onSettingWritten = onSettingWritten,
            )
        }
    }
}

private data class LocalSlotFacts(
    val globalAppend: File,
    val projectAppend: File,
    val globalSystem: File,
    val projectSystem: File,
    val globalAgents: File,
    val projectAgents: File,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextScreen(
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    store: PiSettingsStore? = null,
    onSettingWritten: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette
    var creating by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busySlot by remember { mutableStateOf<String?>(null) }
    var diskEpoch by remember { mutableStateOf(0) }

    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val workspace = remember(context, model.trust?.cwd) { PtyLauncher.workspaceHost(context) }
    val slotFiles = remember(paths, workspace) {
        val projectPi = PiProjectConfig.root(workspace)
        LocalSlotFacts(
            globalAppend = File(paths.agentDir, "APPEND_SYSTEM.md"),
            projectAppend = File(projectPi, "APPEND_SYSTEM.md"),
            globalSystem = File(paths.agentDir, "SYSTEM.md"),
            projectSystem = File(projectPi, "SYSTEM.md"),
            globalAgents = File(paths.agentDir, "AGENTS.md"),
            projectAgents = File(workspace, "AGENTS.md"),
        )
    }

    var fileLengths by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(slotFiles, diskEpoch, model.loaded, model.pendingReload) {
        fileLengths = withContext(Dispatchers.IO) {
            listOf(
                slotFiles.globalAppend,
                slotFiles.projectAppend,
                slotFiles.globalSystem,
                slotFiles.projectSystem,
                slotFiles.globalAgents,
                slotFiles.projectAgents,
            ).associate { f ->
                f.absolutePath to if (f.isFile) f.length() else -1L
            }
        }
    }

    val loaded = model.loaded
    val projectTrusted = model.trust?.trusted != false

    // guest 侧规范路径映射：宿主只认 guest 路径（/root/.pi/agent/... 或 <cwd>/...），
    // 打开已存在文件优先使用 loaded 报出来的真实路径，否则通过 createFile/guestPath 打开。
    val guestAgentDir = "/root/.pi/agent"
    val guestCwd = model.trust?.cwd?.takeIf { it.startsWith("/") }

    fun guestPathFor(fileName: String, isProject: Boolean, underDotPi: Boolean): String? {
        return if (!isProject) {
            "$guestAgentDir/$fileName"
        } else {
            val cwd = guestCwd ?: return null
            if (underDotPi) "$cwd/.pi/$fileName" else "$cwd/$fileName"
        }
    }

    fun openOrCreate(fileName: String, isProject: Boolean, underDotPi: Boolean, hostFile: File) {
        val slotKey = "$isProject:$fileName"
        if (busySlot != null) return
        actionError = null
        val exists = (fileLengths[hostFile.absolutePath] ?: -1L) >= 0L
        if (exists) {
            val gPath = guestPathFor(fileName, isProject, underDotPi)
            if (gPath != null) {
                onEdit(gPath)
                return
            }
        }
        busySlot = slotKey
        scope.launch {
            when (val r = model.createFile(PiResourceFile.Kind.Context, isProject, fileName)) {
                is Parsed.Ok -> {
                    diskEpoch++
                    onEdit(r.value)
                }
                is Parsed.Error -> actionError = r.message
            }
            busySlot = null
        }
    }

    val noContextFiles = store?.let {
        (it.read("app.runtime.noContextFiles") as? JsonPrimitive)?.content == "true"
    } ?: false

    Column(Modifier.fillMaxSize()) {
        PiTopBar(
            title = tr("系统提示词与上下文", "System Prompt & Context"),
            onBack = onBack,
            actions = {
                PiTopBarIcon(
                    onClick = {
                        diskEpoch++
                        model.refresh()
                    },
                    contentDescription = tr("刷新", "Refresh"),
                    icon = Icons.Filled.Refresh,
                )
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
        ) {
            // 改动待重载横幅（直接在本页一键生效，不用退回枢纽页！）
            if (model.pendingReload) {
                item(key = "reload-banner") {
                    ModelsCard(accent = true) {
                        Row(
                            Modifier.fillMaxWidth().padding(ModelsCardPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    tr("提示词或上下文已修改", "Prompt or Context Updated"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                )
                                Text(
                                    tr("点右侧重载让当前会话立刻生效。", "Tap Reload to apply changes to the active session immediately."),
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                )
                            }
                            PrimaryAction(
                                text = if (model.reloading) tr("重载中…", "Reloading…") else tr("立即重载", "Reload"),
                                enabled = !model.reloading,
                                onClick = {
                                    model.reload()
                                    diskEpoch++
                                },
                            )
                        }
                    }
                }
            }

            // 拼装管线总览说明
            item(key = "pipeline-note") {
                PiInfoNote(
                    tr(
                        "Pi 按固定顺序拼装系统提示词：基础提示（默认或 SYSTEM.md）→ 追加指令（APPEND_SYSTEM.md）→ 上下文文件（AGENTS.md / CLAUDE.md）→ 技能清单。项目内 .pi/ 下的文件优先级高于全局。",
                        "Pi assembles the system prompt in order: base prompt (default or SYSTEM.md) → appended instructions (APPEND_SYSTEM.md) → context files (AGENTS.md / CLAUDE.md) → skills. Project .pi/ files take precedence over global.",
                    ),
                )
            }

            if (!projectTrusted) {
                item(key = "trust-warn") {
                    PiInfoNote(
                        tr(
                            "当前项目尚未信任：项目 .pi/ 下的 SYSTEM.md 与 APPEND_SYSTEM.md 暂不会加载（项目根目录的 AGENTS.md / CLAUDE.md 仍正常读取）。",
                            "Project is not trusted yet: .pi/SYSTEM.md and .pi/APPEND_SYSTEM.md will not be loaded (root AGENTS.md / CLAUDE.md are still read).",
                        ),
                        tone = palette.warning,
                    )
                }
            }

            actionError?.let { err ->
                item(key = "action-err") { PiInfoNote(err, tone = palette.error) }
            }
            model.error?.let { err ->
                item(key = "model-err") { PiInfoNote(err, tone = palette.error) }
            }

            // ------------------------------------------------------------------
            // 1. 追加系统提示（APPEND_SYSTEM.md — 推荐方式）
            // ------------------------------------------------------------------
            item(key = "append-section") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            tr("追加指令 · APPEND_SYSTEM.md", "Append Instructions · APPEND_SYSTEM.md"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                        )
                        TagPill(tr("推荐", "Recommended"), tone = palette.success)
                    }
                    ModelsCard {
                        Column {
                            val globalActive = loaded.appendSystemPrompt.any { it.endsWith("/agent/APPEND_SYSTEM.md") }
                            val projectActive = loaded.appendSystemPrompt.any { it.endsWith("/.pi/APPEND_SYSTEM.md") }
                            PromptSlotRow(
                                icon = Icons.Filled.Edit,
                                iconTint = palette.success,
                                title = tr("全局追加指令", "Global Append"),
                                pathHint = "~/.pi/agent/APPEND_SYSTEM.md",
                                subtitle = tr("所有项目共享；安全追加在默认提示之后，不破坏工具规则。", "Shared across all projects; safely appended without altering tool rules."),
                                fileBytes = fileLengths[slotFiles.globalAppend.absolutePath] ?: -1L,
                                isActive = globalActive,
                                enabled = busySlot == null,
                                onClick = { openOrCreate("APPEND_SYSTEM.md", isProject = false, underDotPi = false, slotFiles.globalAppend) },
                            )
                            ModelsDivider()
                            PromptSlotRow(
                                icon = Icons.Filled.Edit,
                                iconTint = palette.accent,
                                title = tr("本项目追加指令", "Project Append"),
                                pathHint = ".pi/APPEND_SYSTEM.md",
                                subtitle = if (projectTrusted) {
                                    tr("仅当前项目生效；存在时覆盖全局追加文件。", "Applies to this project only; overrides global append when present.")
                                } else {
                                    tr("需先在「扩展与资源 → 项目信任」信任本项目后生效。", "Requires project trust in Extensions & Resources to take effect.")
                                },
                                fileBytes = fileLengths[slotFiles.projectAppend.absolutePath] ?: -1L,
                                isActive = projectActive,
                                enabled = busySlot == null && projectTrusted,
                                onClick = { openOrCreate("APPEND_SYSTEM.md", isProject = true, underDotPi = true, slotFiles.projectAppend) },
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 2. 替换系统提示（SYSTEM.md — 高级）
            // ------------------------------------------------------------------
            item(key = "system-section") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            tr("整份替换系统提示 · SYSTEM.md", "Override System Prompt · SYSTEM.md"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                        )
                        TagPill(tr("高级 · 慎用", "Advanced"), tone = palette.warning)
                    }
                    ModelsCard {
                        Column {
                            val globalActive = loaded.systemPrompt?.endsWith("/agent/SYSTEM.md") == true
                            val projectActive = loaded.systemPrompt?.endsWith("/.pi/SYSTEM.md") == true
                            PromptSlotRow(
                                icon = Icons.Filled.Warning,
                                iconTint = palette.warning,
                                title = tr("全局系统提示（替换）", "Global System Override"),
                                pathHint = "~/.pi/agent/SYSTEM.md",
                                subtitle = tr("整份替换 Pi 内置提示词（含工具清单与默认规则）；删除文件即恢复默认。", "Replaces Pi's built-in prompt, tool snippets & rules; delete to restore default."),
                                fileBytes = fileLengths[slotFiles.globalSystem.absolutePath] ?: -1L,
                                isActive = globalActive,
                                enabled = busySlot == null,
                                onClick = { openOrCreate("SYSTEM.md", isProject = false, underDotPi = false, slotFiles.globalSystem) },
                            )
                            ModelsDivider()
                            PromptSlotRow(
                                icon = Icons.Filled.Warning,
                                iconTint = palette.warning,
                                title = tr("本项目系统提示（替换）", "Project System Override"),
                                pathHint = ".pi/SYSTEM.md",
                                subtitle = if (projectTrusted) {
                                    tr("仅当前项目生效，优先级高于全局 SYSTEM.md。", "Applies to this project only; takes precedence over global SYSTEM.md.")
                                } else {
                                    tr("需先信任本项目后才会加载。", "Requires project trust before pi will load it.")
                                },
                                fileBytes = fileLengths[slotFiles.projectSystem.absolutePath] ?: -1L,
                                isActive = projectActive,
                                enabled = busySlot == null && projectTrusted,
                                onClick = { openOrCreate("SYSTEM.md", isProject = true, underDotPi = true, slotFiles.projectSystem) },
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // 3. 上下文文件（AGENTS.md / CLAUDE.md）
            // ------------------------------------------------------------------
            item(key = "context-files-section") {
                val contextFiles = loaded.contextFiles
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            tr("项目与全局上下文 · AGENTS.md / CLAUDE.md", "Context Files · AGENTS.md / CLAUDE.md"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                        )
                        if (contextFiles.isNotEmpty()) {
                            TagPill(tr("${contextFiles.size} 份已加载", "${contextFiles.size} loaded"), tone = palette.accent)
                        }
                    }
                    ModelsCard {
                        Column {
                            if (contextFiles.isEmpty()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(ModelsCardPadding),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        tr("当前未加载任何 AGENTS.md / CLAUDE.md", "No AGENTS.md / CLAUDE.md loaded"),
                                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                        color = palette.text,
                                    )
                                    Text(
                                        tr(
                                            "Pi 会自动读取 ~/.pi/agent/ 以及项目目录到上层目录中的 AGENTS.md / CLAUDE.md 并拼入提示词。",
                                            "Pi automatically discovers AGENTS.md / CLAUDE.md in ~/.pi/agent/ and from the project root upward.",
                                        ),
                                        style = PiTheme.text.meta,
                                        color = palette.muted,
                                    )
                                }
                            } else {
                                contextFiles.forEachIndexed { index, file ->
                                    if (index > 0) ModelsDivider()
                                    val isGlobal = file.path.contains("/.pi/agent/")
                                    val isCurrentProject = guestCwd != null && file.path.startsWith("$guestCwd/")
                                    val scopeBadge = when {
                                        isGlobal -> tr("全局", "Global")
                                        isCurrentProject -> tr("本项目", "Project")
                                        else -> tr("上层目录", "Ancestor")
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onEdit(file.path) }
                                            .padding(horizontal = ModelsCardPadding, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(palette.accent.copy(alpha = 0.13f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Filled.Article,
                                                contentDescription = null,
                                                tint = palette.accent,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text = shortPath(file.path),
                                                style = PiTheme.text.mono.copy(fontWeight = FontWeight.Medium),
                                                color = palette.text,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                TagPill(scopeBadge, tone = if (isCurrentProject) palette.success else palette.accent)
                                                if (file.body.size > 0) {
                                                    TagPill(
                                                        tr("${file.body.size} 字符", "${file.body.size} chars"),
                                                        tone = palette.muted,
                                                    )
                                                }
                                            }
                                        }
                                        Icon(
                                            Icons.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = palette.dim,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                            ModelsDivider()
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ModelsCardPadding, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                                verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
                            ) {
                                PiSettingsChip(
                                    text = tr("编辑/新建项目 AGENTS.md", "Project AGENTS.md"),
                                    enabled = busySlot == null && guestCwd != null,
                                    onClick = { openOrCreate("AGENTS.md", isProject = true, underDotPi = false, slotFiles.projectAgents) },
                                )
                                PiSettingsChip(
                                    text = tr("编辑/新建全局 AGENTS.md", "Global AGENTS.md"),
                                    enabled = busySlot == null,
                                    onClick = { openOrCreate("AGENTS.md", isProject = false, underDotPi = false, slotFiles.globalAgents) },
                                )
                                PiSettingsChip(
                                    text = tr("更多文件…", "More Files…"),
                                    enabled = busySlot == null,
                                    onClick = { creating = true },
                                )
                            }
                        }
                    }
                }
            }

            // 如果从「设置」进入，顺带放上官方「不加载上下文文件」开关，免得再跑去运行时里找
            if (store != null && onSettingWritten != null) {
                item(key = "no-context-toggle") {
                    ModelsCard {
                        PiSettingsToggleRow(
                            title = tr("启动时不加载上下文文件", "Disable Context Files on Startup"),
                            supporting = tr(
                                "开启后重启引擎时跳过所有 AGENTS.md / CLAUDE.md（对应 --no-context-files）。",
                                "Skip AGENTS.md / CLAUDE.md on engine startup (equivalent to --no-context-files; requires engine restart).",
                            ),
                            checked = noContextFiles,
                            onCheckedChange = { on ->
                                store.write("app.runtime.noContextFiles", JsonPrimitive(on))
                                onSettingWritten("app.runtime.noContextFiles")
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NewContextDialog(
            model = model,
            onDismiss = { creating = false },
            onCreated = { path ->
                creating = false
                diskEpoch++
                model.refresh()
                onEdit(path)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptSlotRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    pathHint: String,
    subtitle: String,
    fileBytes: Long,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val exists = fileBytes >= 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = if (enabled) 0.14f else 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (exists) icon else Icons.Filled.Add,
                contentDescription = null,
                tint = if (enabled) iconTint else palette.dim,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) palette.text else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pathHint,
                style = PiTheme.text.monoSmall,
                color = palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = PiTheme.text.meta,
                color = palette.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    isActive -> TagPill(tr("已生效", "Active"), tone = palette.success)
                    exists -> TagPill(tr("已创建 · 待重载", "Saved · Reload to apply"), tone = palette.warning)
                    else -> TagPill(tr("未创建 · 点击新建", "Not set · Tap to create"), tone = palette.muted)
                }
                if (exists && fileBytes > 0) {
                    TagPill(tr("${fileBytes} B", "${fileBytes} B"), tone = palette.muted)
                }
            }
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}
