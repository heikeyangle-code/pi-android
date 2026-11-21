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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
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
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.runtime.PiPaths
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PtyLauncher
import app.pi.ui.PiSeg
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.PrimaryAction
import app.pi.ui.models.TagPill
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsStore
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「系统提示词与上下文」统一专页（设置 → 系统提示词 & 扩展与资源 → 上下文与系统提示 共用）。
 *
 * 人性化设计（不丢任何能力、层次清晰、一眼看懂）：
 *  1. **顶部「当前生效总览」状态卡**：直接告诉用户此刻会话用的是默认提示词还是哪个 `SYSTEM.md`、有没有追加 `APPEND_SYSTEM.md`、总共拼入了哪几份 `AGENTS.md` / `CLAUDE.md`；
 *  2. **带计数角标的「全局 / 本项目」分段切换**：不再把两套层级的 8 个入口乱炖在一屏，同时在每一行清楚标注**继承与覆盖关系**（例如项目未设时显示「当前继承全局」，项目已设时显示「覆盖全局」）；
 *  3. **完整覆盖全部 4 类官方文件**（`APPEND_SYSTEM.md`、`SYSTEM.md`、`AGENTS.md`、`CLAUDE.md` + 上层父目录自动发现的上下文文件），无需额外弹窗选文件名；
 *  4. **点任意一项直接打开编辑器**：未创建的文件在内存中打开为空白草稿（无预设废话文字），只有输入内容并点保存才落盘并自动重载生效。
 */
@Composable
internal fun SystemPromptHostScreen(
    contentPadding: PaddingValues,
    access: () -> ExtensionsAccess?,
    store: PiSettingsStore? = null,
    onSettingWritten: ((String) -> Unit)? = null,
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
            PiInfoNote(tr("引擎未运行，启动后可查看与编辑。", "Engine is not running. Start it to view and edit."))
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

private data class AllSlotFiles(
    val globalAppend: File,
    val globalSystem: File,
    val globalAgents: File,
    val globalClaude: File,
    val projectAppend: File,
    val projectSystem: File,
    val projectAgents: File,
    val projectClaude: File,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextScreen(
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") store: PiSettingsStore? = null,
    @Suppress("UNUSED_PARAMETER") onSettingWritten: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val palette = PiTheme.palette
    var diskEpoch by remember { mutableStateOf(0) }
    val layer = model.effectiveLayer
    val isProject = layer == Layer.Project

    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val workspace = remember(context, model.trust?.cwd) { PtyLauncher.workspaceHost(context) }
    val guestAgentDir = "/root/.pi/agent"
    val guestCwd = model.trust?.cwd?.takeIf { it.startsWith("/") } ?: "/workspace"

    val allFiles = remember(paths, workspace) {
        val dotPi = PiProjectConfig.root(workspace)
        AllSlotFiles(
            globalAppend = File(paths.agentDir, "APPEND_SYSTEM.md"),
            globalSystem = File(paths.agentDir, "SYSTEM.md"),
            globalAgents = File(paths.agentDir, "AGENTS.md"),
            globalClaude = File(paths.agentDir, "CLAUDE.md"),
            projectAppend = File(dotPi, "APPEND_SYSTEM.md"),
            projectSystem = File(dotPi, "SYSTEM.md"),
            projectAgents = File(workspace, "AGENTS.md"),
            projectClaude = File(workspace, "CLAUDE.md"),
        )
    }

    var fileSnippets by remember { mutableStateOf<Map<String, Pair<Long, String>>>(emptyMap()) }
    LaunchedEffect(allFiles, diskEpoch, model.loaded, model.pendingReload) {
        fileSnippets = withContext(Dispatchers.IO) {
            listOf(
                allFiles.globalAppend,
                allFiles.globalSystem,
                allFiles.globalAgents,
                allFiles.globalClaude,
                allFiles.projectAppend,
                allFiles.projectSystem,
                allFiles.projectAgents,
                allFiles.projectClaude,
            ).associate { f ->
                val info = if (f.isFile) {
                    val len = f.length()
                    val preview = runCatching {
                        f.bufferedReader().useLines { lines ->
                            lines.map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(80)
                        }
                    }.getOrDefault("")
                    len to preview
                } else {
                    -1L to ""
                }
                f.absolutePath to info
            }
        }
    }

    val loaded = model.loaded
    val globalAppendActive = loaded.appendSystemPrompt.any { it == "$guestAgentDir/APPEND_SYSTEM.md" }
    val projectAppendActive = loaded.appendSystemPrompt.any { it == "$guestCwd/.pi/APPEND_SYSTEM.md" }
    val globalSystemActive = loaded.systemPrompt == "$guestAgentDir/SYSTEM.md"
    val projectSystemActive = loaded.systemPrompt == "$guestCwd/.pi/SYSTEM.md"

    val globalCount = listOf(
        allFiles.globalAppend,
        allFiles.globalSystem,
        allFiles.globalAgents,
        allFiles.globalClaude,
    ).count { (fileSnippets[it.absolutePath]?.first ?: -1L) >= 0L }

    val projectCount = listOf(
        allFiles.projectAppend,
        allFiles.projectSystem,
        allFiles.projectAgents,
        allFiles.projectClaude,
    ).count { (fileSnippets[it.absolutePath]?.first ?: -1L) >= 0L }

    // 额外已加载的上层目录或 AGENTS.override.md 文件
    val primaryKnownPaths = setOf(
        "$guestAgentDir/AGENTS.md",
        "$guestAgentDir/CLAUDE.md",
        "$guestCwd/AGENTS.md",
        "$guestCwd/CLAUDE.md",
    )
    val extraLoadedContext = remember(loaded.contextFiles, primaryKnownPaths) {
        loaded.contextFiles.filter { it.path !in primaryKnownPaths }
    }

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
            // 顶部实时生效总览卡（一眼看清全局与项目的合成结果）
            item(key = "active-summary") {
                ModelsCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(ModelsCardPadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = palette.success,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = tr("当前会话生效状态", "Active in Current Session"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.text,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val baseLabel = when {
                                projectSystemActive -> tr("基础：项目 SYSTEM.md（已替换默认）", "Base: Project SYSTEM.md")
                                globalSystemActive -> tr("基础：全局 SYSTEM.md（已替换默认）", "Base: Global SYSTEM.md")
                                else -> tr("基础：Pi 官方默认提示词", "Base: Pi Default Prompt")
                            }
                            TagPill(
                                text = baseLabel,
                                tone = if (projectSystemActive || globalSystemActive) palette.warning else palette.success,
                            )
                            val appendLabel = when {
                                projectAppendActive -> tr("追加：项目 APPEND_SYSTEM.md", "Append: Project APPEND_SYSTEM.md")
                                globalAppendActive -> tr("追加：全局 APPEND_SYSTEM.md", "Append: Global APPEND_SYSTEM.md")
                                else -> tr("追加：无", "Append: None")
                            }
                            TagPill(
                                text = appendLabel,
                                tone = if (projectAppendActive || globalAppendActive) palette.accent else palette.muted,
                            )
                            TagPill(
                                text = tr(
                                    "上下文文件：${loaded.contextFiles.size} 份已加载",
                                    "Context: ${loaded.contextFiles.size} loaded",
                                ),
                                tone = if (loaded.contextFiles.isNotEmpty()) palette.accent else palette.muted,
                            )
                        }
                    }
                }
            }

            // 待重载横幅
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
                                    tr("改动待重载", "Reload Needed"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                )
                                Text(
                                    tr("点右侧重载让当前会话立刻生效", "Tap Reload to apply changes to the active session"),
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

            // 层级切换（带已创建文件数角标）
            item(key = "scope-seg") {
                val globalLabel = if (globalCount > 0) tr("全局 ($globalCount)", "Global ($globalCount)") else tr("全局", "Global")
                val projectLabel = if (projectCount > 0) tr("本项目 ($projectCount)", "Project ($projectCount)") else tr("本项目", "Project")
                PiSeg(
                    options = listOf(globalLabel, projectLabel),
                    selectedIndex = if (isProject) 1 else 0,
                    disabledIndices = if (model.projectUsable) emptySet() else setOf(1),
                    onSelect = { idx ->
                        if (idx == 0 || model.projectUsable) {
                            model.layer = if (idx == 0) Layer.Global else Layer.Project
                        }
                    },
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }

            if (!model.projectUsable) {
                item(key = "trust-warn") {
                    PiInfoNote(
                        tr("当前项目尚未信任：去「扩展与资源 → 项目信任」开启后即可配置本项目文件。", "Project is not trusted yet: enable trust under Extensions & Resources → Project Trust to edit project files."),
                        tone = palette.warning,
                    )
                }
            }

            model.error?.let { err ->
                item(key = "model-err") { PiInfoNote(err, tone = palette.error) }
            }

            // 1. 系统提示词卡片（APPEND_SYSTEM.md + SYSTEM.md）
            item(key = "system-prompt-card") {
                val appendFile = if (isProject) allFiles.projectAppend else allFiles.globalAppend
                val systemFile = if (isProject) allFiles.projectSystem else allFiles.globalSystem
                val appendGuest = if (isProject) "$guestCwd/.pi/APPEND_SYSTEM.md" else "$guestAgentDir/APPEND_SYSTEM.md"
                val systemGuest = if (isProject) "$guestCwd/.pi/SYSTEM.md" else "$guestAgentDir/SYSTEM.md"
                val appendDisplay = if (isProject) ".pi/APPEND_SYSTEM.md" else "~/.pi/agent/APPEND_SYSTEM.md"
                val systemDisplay = if (isProject) ".pi/SYSTEM.md" else "~/.pi/agent/SYSTEM.md"

                val (appendBytes, appendPreview) = fileSnippets[appendFile.absolutePath] ?: (-1L to "")
                val (systemBytes, systemPreview) = fileSnippets[systemFile.absolutePath] ?: (-1L to "")
                val appendActiveHere = if (isProject) projectAppendActive else globalAppendActive
                val systemActiveHere = if (isProject) projectSystemActive else globalSystemActive

                val appendNote = when {
                    isProject && appendActiveHere && (fileSnippets[allFiles.globalAppend.absolutePath]?.first ?: -1L) >= 0L ->
                        tr("已覆盖全局追加提示词", "Overrides global APPEND_SYSTEM.md")
                    isProject && !appendActiveHere && globalAppendActive ->
                        tr("未创建 · 当前继承全局 APPEND_SYSTEM.md", "Not set · Inheriting global APPEND_SYSTEM.md")
                    !isProject && (appendBytes >= 0L) && projectAppendActive ->
                        tr("当前已被本项目的 .pi/APPEND_SYSTEM.md 覆盖", "Currently overridden by project .pi/APPEND_SYSTEM.md")
                    else -> null
                }

                val systemNote = when {
                    isProject && systemActiveHere && (fileSnippets[allFiles.globalSystem.absolutePath]?.first ?: -1L) >= 0L ->
                        tr("已覆盖全局 SYSTEM.md", "Overrides global SYSTEM.md")
                    isProject && !systemActiveHere && globalSystemActive ->
                        tr("未创建 · 当前继承全局 SYSTEM.md", "Not set · Inheriting global SYSTEM.md")
                    !isProject && (systemBytes >= 0L) && projectSystemActive ->
                        tr("当前已被本项目的 .pi/SYSTEM.md 覆盖", "Currently overridden by project .pi/SYSTEM.md")
                    else -> null
                }

                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(
                        if (isProject) tr("系统提示词 · 本项目（优先于全局）", "System Prompt · Project (Overrides Global)")
                        else tr("系统提示词 · 全局（所有项目共享）", "System Prompt · Global (Shared Across Projects)"),
                    )
                    ModelsCard {
                        Column {
                            CleanPromptRow(
                                icon = Icons.Filled.Edit,
                                iconTint = palette.success,
                                title = tr("追加系统提示词", "Append System Prompt"),
                                roleBadge = tr("推荐", "Recommended"),
                                roleBadgeTone = palette.success,
                                path = appendDisplay,
                                hint = appendPreview.ifEmpty {
                                    tr("附在默认提示词末尾，保留内置工具规则", "Appended after default prompt; preserves built-in tool rules")
                                },
                                relationNote = appendNote,
                                fileBytes = appendBytes,
                                isActive = appendActiveHere,
                                onClick = { onEdit(appendGuest) },
                            )
                            ModelsDivider()
                            CleanPromptRow(
                                icon = Icons.Filled.Warning,
                                iconTint = palette.warning,
                                title = tr("替换默认提示词", "Override System Prompt"),
                                roleBadge = tr("高级", "Advanced"),
                                roleBadgeTone = palette.warning,
                                path = systemDisplay,
                                hint = systemPreview.ifEmpty {
                                    tr("整份替换默认提示词与工具规则；删除文件即恢复默认", "Replaces entire built-in prompt & tool rules; delete to restore")
                                },
                                relationNote = systemNote,
                                fileBytes = systemBytes,
                                isActive = systemActiveHere,
                                onClick = { onEdit(systemGuest) },
                            )
                        }
                    }
                }
            }

            // 2. 上下文说明文件卡片（AGENTS.md + CLAUDE.md，全局与项目会同时拼入！）
            item(key = "context-card") {
                val agentsFile = if (isProject) allFiles.projectAgents else allFiles.globalAgents
                val claudeFile = if (isProject) allFiles.projectClaude else allFiles.globalClaude
                val agentsGuest = if (isProject) "$guestCwd/AGENTS.md" else "$guestAgentDir/AGENTS.md"
                val claudeGuest = if (isProject) "$guestCwd/CLAUDE.md" else "$guestAgentDir/CLAUDE.md"
                val agentsDisplay = if (isProject) "AGENTS.md" else "~/.pi/agent/AGENTS.md"
                val claudeDisplay = if (isProject) "CLAUDE.md" else "~/.pi/agent/CLAUDE.md"

                val (agentsBytes, agentsPreview) = fileSnippets[agentsFile.absolutePath] ?: (-1L to "")
                val (claudeBytes, claudePreview) = fileSnippets[claudeFile.absolutePath] ?: (-1L to "")
                val agentsActive = loaded.contextFiles.any { it.path == agentsGuest }
                val claudeActive = loaded.contextFiles.any { it.path == claudeGuest }

                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(
                        if (isProject) tr("项目上下文文件（与全局上下文共同加载）", "Project Context Files (Combined with Global)")
                        else tr("全局上下文文件（所有项目共同加载）", "Global Context Files (Loaded in All Projects)"),
                    )
                    ModelsCard {
                        Column {
                            CleanPromptRow(
                                icon = Icons.Filled.Article,
                                iconTint = palette.accent,
                                title = "AGENTS.md",
                                roleBadge = tr("标准", "Standard"),
                                roleBadgeTone = palette.accent,
                                path = agentsDisplay,
                                hint = agentsPreview.ifEmpty {
                                    if (isProject) tr("本项目架构、构建测试命令与代码规范", "Project architecture, build/test commands & conventions")
                                    else tr("你的全局编码偏好与通用约定", "Your global coding preferences & universal conventions")
                                },
                                fileBytes = agentsBytes,
                                isActive = agentsActive,
                                onClick = { onEdit(agentsGuest) },
                            )
                            ModelsDivider()
                            CleanPromptRow(
                                icon = Icons.Filled.Article,
                                iconTint = palette.borderAccent,
                                title = "CLAUDE.md",
                                roleBadge = tr("兼容 Claude", "Claude Compat"),
                                roleBadgeTone = palette.muted,
                                path = claudeDisplay,
                                hint = claudePreview.ifEmpty {
                                    tr("兼容 Claude Code 的说明文件，与 AGENTS.md 同级拼入", "Claude Code compatible instructions, loaded alongside AGENTS.md")
                                },
                                fileBytes = claudeBytes,
                                isActive = claudeActive,
                                onClick = { onEdit(claudeGuest) },
                            )
                        }
                    }
                }
            }

            // 3. 上层目录或 AGENTS.override.md 自动发现的文件（有才显示，不占空位）
            if (extraLoadedContext.isNotEmpty()) {
                item(key = "extra-context-card") {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        ModelsSectionLabel(tr("自动发现的其他上下文 · ${extraLoadedContext.size}", "Auto-Discovered Context · ${extraLoadedContext.size}"))
                        ModelsCard {
                            Column {
                                extraLoadedContext.forEachIndexed { idx, extra ->
                                    if (idx > 0) ModelsDivider()
                                    val firstLine = extra.body.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                                    CleanPromptRow(
                                        icon = Icons.Filled.FolderOpen,
                                        iconTint = palette.accent,
                                        title = extra.path.substringAfterLast('/'),
                                        path = shortPath(extra.path),
                                        hint = firstLine.ifEmpty { tr("由上层目录或 override 规则自动加载", "Auto-loaded from ancestor directory or override rule") },
                                        fileBytes = extra.body.size,
                                        isActive = true,
                                        onClick = { onEdit(extra.path) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanPromptRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    path: String,
    hint: String,
    fileBytes: Long,
    isActive: Boolean,
    roleBadge: String? = null,
    roleBadgeTone: Color = iconTint,
    relationNote: String? = null,
    onClick: () -> Unit,
) {
    val palette = PiTheme.palette
    val exists = fileBytes >= 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (exists) icon else Icons.Filled.Add,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (roleBadge != null) {
                    TagPill(roleBadge, tone = roleBadgeTone)
                }
            }
            Text(
                text = path,
                style = PiTheme.text.monoSmall,
                color = palette.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hint,
                style = PiTheme.text.meta,
                color = if (exists) palette.text.copy(alpha = 0.85f) else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (relationNote != null) {
                Text(
                    text = relationNote,
                    style = PiTheme.text.meta,
                    color = palette.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            isActive -> TagPill(
                text = if (fileBytes > 0) tr("已生效 · ${fileBytes}B", "Active · ${fileBytes}B") else tr("已生效", "Active"),
                tone = palette.success,
            )
            exists -> TagPill(tr("已存 · ${fileBytes}B", "Saved · ${fileBytes}B"), tone = palette.warning)
            else -> TagPill(tr("点击编写", "Tap to write"), tone = palette.muted)
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}
