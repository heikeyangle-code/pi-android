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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.pi.packages.PiLoadedResources
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
 * 设计原则：
 *  1. 全部生效状态以宿主 `resources.loaded` 为唯一权威来源，使用宿主上报的 `agentDir` / `cwd`
 *     做规范化路径匹配，彻底杜绝符号链接或硬编码路径导致「已建文件但顶部状态不更新」；
 *  2. 顶部实时生效总览卡 + 带计数角标的「全局 / 本项目」分段切换 + 4 个官方槽位直达编辑；
 *  3. 未创建的文件打开为空白草稿（绝无硬编码预设废话文字），保存/删除均真正等待热重载完成并即时刷新本页。
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

private data class SlotHostFiles(
    val globalAppend: File,
    val globalSystem: File,
    val globalAgents: File,
    val globalClaude: File,
    val projectAppend: File,
    val projectSystem: File,
    val projectAgents: File,
    val projectClaude: File,
)

private data class DiskSnippet(val bytes: Long, val preview: String) {
    val exists: Boolean get() = bytes >= 0L
}

private fun normPath(p: String?): String = p?.trimEnd('/').orEmpty()

private fun isGlobalAgentPath(path: String?, guestAgentDir: String, fileName: String): Boolean {
    val p = normPath(path)
    if (p.isEmpty()) return false
    return p == "$guestAgentDir/$fileName" || p.endsWith("/.pi/agent/$fileName")
}

private fun isProjectDotPiPath(path: String?, guestCwd: String, guestAgentDir: String, fileName: String): Boolean {
    val p = normPath(path)
    if (p.isEmpty() || isGlobalAgentPath(p, guestAgentDir, fileName)) return false
    return p == "$guestCwd/.pi/$fileName" || p.endsWith("/.pi/$fileName")
}

private fun isProjectRootContextPath(path: String?, guestCwd: String, guestAgentDir: String, fileName: String): Boolean {
    val p = normPath(path)
    if (p.isEmpty() || isGlobalAgentPath(p, guestAgentDir, fileName)) return false
    return p == "$guestCwd/$fileName" || (p.endsWith("/$fileName") && p.substringBeforeLast('/') == guestCwd)
}

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
    var diskEpoch by remember { mutableIntStateOf(0) }
    val layer = model.effectiveLayer
    val isProject = layer == Layer.Project
    val loaded = model.loaded

    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val workspaceHost = remember(context, model.trust?.cwd, loaded.cwd, model.dataVersion) {
        PtyLauncher.workspaceHost(context)
    }
    val guestAgentDir = normPath(loaded.agentDir).ifEmpty { "/root/.pi/agent" }
    val guestCwd = normPath(loaded.cwd ?: model.trust?.cwd).takeIf { it.startsWith("/") } ?: "/workspace"

    val hostFiles = remember(paths, workspaceHost.absolutePath) {
        val dotPi = PiProjectConfig.root(workspaceHost)
        SlotHostFiles(
            globalAppend = File(paths.agentDir, "APPEND_SYSTEM.md"),
            globalSystem = File(paths.agentDir, "SYSTEM.md"),
            globalAgents = File(paths.agentDir, "AGENTS.md"),
            globalClaude = File(paths.agentDir, "CLAUDE.md"),
            projectAppend = File(dotPi, "APPEND_SYSTEM.md"),
            projectSystem = File(dotPi, "SYSTEM.md"),
            projectAgents = File(workspaceHost, "AGENTS.md"),
            projectClaude = File(workspaceHost, "CLAUDE.md"),
        )
    }

    var diskMap by remember { mutableStateOf<Map<String, DiskSnippet>>(emptyMap()) }
    LaunchedEffect(hostFiles, model.dataVersion, diskEpoch) {
        diskMap = withContext(Dispatchers.IO) {
            listOf(
                hostFiles.globalAppend,
                hostFiles.globalSystem,
                hostFiles.globalAgents,
                hostFiles.globalClaude,
                hostFiles.projectAppend,
                hostFiles.projectSystem,
                hostFiles.projectAgents,
                hostFiles.projectClaude,
            ).associate { f ->
                val snap = if (f.isFile) {
                    val len = f.length()
                    val preview = runCatching {
                        f.bufferedReader().useLines { lines ->
                            lines.map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(90)
                        }
                    }.getOrDefault("")
                    DiskSnippet(len, preview)
                } else {
                    DiskSnippet(-1L, "")
                }
                f.absolutePath to snap
            }
        }
    }

    // 权威生效判定（以 Pi 宿主 `resources.loaded` 为准，兼容符号链接与自定义 agentDir/cwd）
    val globalSystemActive = isGlobalAgentPath(loaded.systemPrompt, guestAgentDir, "SYSTEM.md")
    val projectSystemActive = isProjectDotPiPath(loaded.systemPrompt, guestCwd, guestAgentDir, "SYSTEM.md")
    val otherSystemActive = !loaded.systemPrompt.isNullOrBlank() && !globalSystemActive && !projectSystemActive

    val globalAppendActive = loaded.appendSystemPrompt.any { isGlobalAgentPath(it, guestAgentDir, "APPEND_SYSTEM.md") }
    val projectAppendActive = loaded.appendSystemPrompt.any { isProjectDotPiPath(it, guestCwd, guestAgentDir, "APPEND_SYSTEM.md") }
    val otherAppendActive = loaded.appendSystemPrompt.isNotEmpty() && !globalAppendActive && !projectAppendActive

    val globalAgentsEntry = loaded.contextFiles.firstOrNull { isGlobalAgentPath(it.path, guestAgentDir, "AGENTS.md") }
    val globalClaudeEntry = loaded.contextFiles.firstOrNull { isGlobalAgentPath(it.path, guestAgentDir, "CLAUDE.md") }
    val projectAgentsEntry = loaded.contextFiles.firstOrNull { isProjectRootContextPath(it.path, guestCwd, guestAgentDir, "AGENTS.md") }
    val projectClaudeEntry = loaded.contextFiles.firstOrNull { isProjectRootContextPath(it.path, guestCwd, guestAgentDir, "CLAUDE.md") }

    fun snippetOf(file: File, loadedContext: PiLoadedResources.ContextFile? = null): DiskSnippet {
        val onDisk = diskMap[file.absolutePath]
        if (onDisk != null && onDisk.exists) return onDisk
        if (loadedContext != null) {
            val firstLine = loadedContext.body.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(90)
            return DiskSnippet(loadedContext.body.size, firstLine)
        }
        return DiskSnippet(-1L, "")
    }

    val globalCount = listOf(
        snippetOf(hostFiles.globalAppend).exists || globalAppendActive,
        snippetOf(hostFiles.globalSystem).exists || globalSystemActive,
        snippetOf(hostFiles.globalAgents, globalAgentsEntry).exists,
        snippetOf(hostFiles.globalClaude, globalClaudeEntry).exists,
    ).count { it }

    val projectCount = listOf(
        snippetOf(hostFiles.projectAppend).exists || projectAppendActive,
        snippetOf(hostFiles.projectSystem).exists || projectSystemActive,
        snippetOf(hostFiles.projectAgents, projectAgentsEntry).exists,
        snippetOf(hostFiles.projectClaude, projectClaudeEntry).exists,
    ).count { it }

    val knownLoadedPaths = setOfNotNull(
        globalAgentsEntry?.path,
        globalClaudeEntry?.path,
        projectAgentsEntry?.path,
        projectClaudeEntry?.path,
    )
    val extraLoadedContext = remember(loaded.contextFiles, knownLoadedPaths) {
        loaded.contextFiles.filter { it.path !in knownLoadedPaths }
    }

    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }

    Column(Modifier.fillMaxSize()) {
        PiTopBar(
            title = tr("系统提示词与上下文", "System Prompt & Context"),
            onBack = onBack,
            scrolled = scrolled,
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
        ) {
            // 顶部实时生效总览卡
            item(key = "active-summary") {
                ModelsCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(ModelsCardPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (model.hasLoaded) palette.success else palette.muted,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (model.hasLoaded) tr("当前会话生效状态", "Active in Current Session") else tr("正在读取生效状态…", "Reading Active Status…"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.text,
                            )
                        }
                        if (model.hasLoaded) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val baseLabel = when {
                                    projectSystemActive -> tr("基础：项目 SYSTEM.md（已替换）", "Base: Project SYSTEM.md")
                                    globalSystemActive -> tr("基础：全局 SYSTEM.md（已替换）", "Base: Global SYSTEM.md")
                                    otherSystemActive -> tr("基础：自定义 SYSTEM.md", "Base: Custom SYSTEM.md")
                                    else -> tr("基础：Pi 官方默认提示词", "Base: Pi Default Prompt")
                                }
                                TagPill(
                                    text = baseLabel,
                                    tone = if (projectSystemActive || globalSystemActive || otherSystemActive) palette.warning else palette.success,
                                )
                                val appendLabel = when {
                                    projectAppendActive -> tr("追加：项目 APPEND_SYSTEM.md", "Append: Project APPEND_SYSTEM.md")
                                    globalAppendActive -> tr("追加：全局 APPEND_SYSTEM.md", "Append: Global APPEND_SYSTEM.md")
                                    otherAppendActive -> tr("追加：${loaded.appendSystemPrompt.size} 份", "Append: ${loaded.appendSystemPrompt.size}")
                                    else -> tr("追加：无", "Append: None")
                                }
                                TagPill(
                                    text = appendLabel,
                                    tone = if (projectAppendActive || globalAppendActive || otherAppendActive) palette.accent else palette.muted,
                                )
                                TagPill(
                                    text = tr("上下文：${loaded.contextFiles.size} 份已加载", "Context: ${loaded.contextFiles.size} loaded"),
                                    tone = if (loaded.contextFiles.isNotEmpty()) palette.accent else palette.muted,
                                )
                            }
                        }
                    }
                }
            }

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
                    fillWidth = true,
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

            // 1. 系统提示词（APPEND_SYSTEM.md + SYSTEM.md）
            item(key = "system-prompt-card") {
                val appendSnap = snippetOf(if (isProject) hostFiles.projectAppend else hostFiles.globalAppend)
                val systemSnap = snippetOf(if (isProject) hostFiles.projectSystem else hostFiles.globalSystem)
                val appendGuest = if (isProject) "$guestCwd/.pi/APPEND_SYSTEM.md" else "$guestAgentDir/APPEND_SYSTEM.md"
                val systemGuest = if (isProject) "$guestCwd/.pi/SYSTEM.md" else "$guestAgentDir/SYSTEM.md"
                val appendDisplay = if (isProject) ".pi/APPEND_SYSTEM.md" else "~/.pi/agent/APPEND_SYSTEM.md"
                val systemDisplay = if (isProject) ".pi/SYSTEM.md" else "~/.pi/agent/SYSTEM.md"
                val appendActiveHere = if (isProject) projectAppendActive else globalAppendActive
                val systemActiveHere = if (isProject) projectSystemActive else globalSystemActive

                val appendNote = when {
                    isProject && appendActiveHere && snippetOf(hostFiles.globalAppend).exists ->
                        tr("已覆盖全局追加提示词", "Overrides global APPEND_SYSTEM.md")
                    isProject && !appendActiveHere && globalAppendActive ->
                        tr("未创建 · 当前继承全局 APPEND_SYSTEM.md", "Not set · Inheriting global APPEND_SYSTEM.md")
                    !isProject && appendSnap.exists && projectAppendActive ->
                        tr("当前已被本项目的 .pi/APPEND_SYSTEM.md 覆盖", "Currently overridden by project .pi/APPEND_SYSTEM.md")
                    else -> null
                }

                val systemNote = when {
                    isProject && systemActiveHere && snippetOf(hostFiles.globalSystem).exists ->
                        tr("已覆盖全局 SYSTEM.md", "Overrides global SYSTEM.md")
                    isProject && !systemActiveHere && globalSystemActive ->
                        tr("未创建 · 当前继承全局 SYSTEM.md", "Not set · Inheriting global SYSTEM.md")
                    !isProject && systemSnap.exists && projectSystemActive ->
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
                            PromptSlotRow(
                                icon = Icons.Filled.Edit,
                                iconTint = palette.success,
                                title = tr("追加系统提示词", "Append System Prompt"),
                                roleBadge = tr("推荐", "Recommended"),
                                roleBadgeTone = palette.success,
                                path = appendDisplay,
                                hint = appendSnap.preview.ifEmpty {
                                    tr("附在默认提示词末尾，保留内置工具规则", "Appended after default prompt; preserves built-in tool rules")
                                },
                                relationNote = appendNote,
                                fileBytes = appendSnap.bytes,
                                isActive = appendActiveHere,
                                onClick = { onEdit(appendGuest) },
                            )
                            ModelsDivider()
                            PromptSlotRow(
                                icon = Icons.Filled.Warning,
                                iconTint = palette.warning,
                                title = tr("替换默认提示词", "Override System Prompt"),
                                roleBadge = tr("高级", "Advanced"),
                                roleBadgeTone = palette.warning,
                                path = systemDisplay,
                                hint = systemSnap.preview.ifEmpty {
                                    tr("整份替换默认提示词与工具规则；删除文件即恢复默认", "Replaces entire built-in prompt & tool rules; delete to restore")
                                },
                                relationNote = systemNote,
                                fileBytes = systemSnap.bytes,
                                isActive = systemActiveHere,
                                onClick = { onEdit(systemGuest) },
                            )
                        }
                    }
                }
            }

            // 2. 上下文说明文件（对齐官方互斥优先级：每层目录仅加载首个命中的 AGENTS.override.md > AGENTS.md > CLAUDE.md）
            item(key = "context-card") {
                val agentsEntry = if (isProject) projectAgentsEntry else globalAgentsEntry
                val claudeEntry = if (isProject) projectClaudeEntry else globalClaudeEntry
                val agentsSnap = snippetOf(if (isProject) hostFiles.projectAgents else hostFiles.globalAgents, agentsEntry)
                val claudeSnap = snippetOf(if (isProject) hostFiles.projectClaude else hostFiles.globalClaude, claudeEntry)
                val agentsGuest = if (isProject) "$guestCwd/AGENTS.md" else "$guestAgentDir/AGENTS.md"
                val claudeGuest = if (isProject) "$guestCwd/CLAUDE.md" else "$guestAgentDir/CLAUDE.md"
                val agentsDisplay = if (isProject) "AGENTS.md" else "~/.pi/agent/AGENTS.md"
                val claudeDisplay = if (isProject) "CLAUDE.md" else "~/.pi/agent/CLAUDE.md"
                val showClaude = claudeEntry != null || claudeSnap.bytes > 0L

                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(
                        if (isProject) tr("项目上下文文件（与全局上下文共同加载）", "Project Context File (Combined with Global)")
                        else tr("全局上下文文件（所有项目共同加载）", "Global Context File (Loaded in All Projects)"),
                    )
                    ModelsCard {
                        Column {
                            PromptSlotRow(
                                icon = Icons.AutoMirrored.Filled.Article,
                                iconTint = palette.accent,
                                title = "AGENTS.md",
                                roleBadge = tr("标准", "Standard"),
                                roleBadgeTone = palette.accent,
                                path = agentsDisplay,
                                hint = agentsSnap.preview.ifEmpty {
                                    if (isProject) tr("本项目架构、构建测试命令与代码规范", "Project architecture, build/test commands & conventions")
                                    else tr("你的全局编码偏好与通用约定", "Your global coding preferences & universal conventions")
                                },
                                fileBytes = agentsSnap.bytes,
                                isActive = agentsEntry != null,
                                onClick = { onEdit(agentsGuest) },
                            )
                            if (showClaude) {
                                ModelsDivider()
                                PromptSlotRow(
                                    icon = Icons.AutoMirrored.Filled.Article,
                                    iconTint = palette.borderAccent,
                                    title = "CLAUDE.md",
                                    roleBadge = tr("兼容", "Compat"),
                                    roleBadgeTone = palette.muted,
                                    path = claudeDisplay,
                                    hint = claudeSnap.preview.ifEmpty {
                                        tr("仅在同目录不存在 AGENTS.md 时加载（优先级：AGENTS.override.md > AGENTS.md > CLAUDE.md）", "Loaded only when AGENTS.md is absent in the same directory (priority: AGENTS.override.md > AGENTS.md > CLAUDE.md)")
                                    },
                                    relationNote = if (agentsEntry != null) {
                                        tr("同目录已存在 AGENTS.md，按官方规则本文件当前被跳过", "Skipped by official Pi precedence because AGENTS.md exists in this directory")
                                    } else {
                                        null
                                    },
                                    fileBytes = claudeSnap.bytes,
                                    isActive = claudeEntry != null,
                                    onClick = { onEdit(claudeGuest) },
                                )
                            }
                        }
                    }
                }
            }

            if (extraLoadedContext.isNotEmpty()) {
                item(key = "extra-context-card") {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        ModelsSectionLabel(tr("自动发现的其他上下文 · ${extraLoadedContext.size}", "Auto-Discovered Context · ${extraLoadedContext.size}"))
                        ModelsCard {
                            Column {
                                extraLoadedContext.forEachIndexed { idx, extra ->
                                    if (idx > 0) ModelsDivider()
                                    val firstLine = extra.body.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(90)
                                    PromptSlotRow(
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
private fun PromptSlotRow(
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
    val exists = fileBytes >= 0L || isActive
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
                    modifier = Modifier.weight(1f, fill = false),
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
        val sizeLabel = formatBytesCompact(fileBytes)
        when {
            isActive -> TagPill(
                text = if (sizeLabel != null) tr("已生效 · $sizeLabel", "Active · $sizeLabel") else tr("已生效", "Active"),
                tone = palette.success,
            )
            exists -> TagPill(
                text = if (sizeLabel != null) tr("已存 · $sizeLabel", "Saved · $sizeLabel") else tr("空文件", "Empty"),
                tone = palette.warning,
            )
            else -> TagPill(tr("点击编写", "Tap to write"), tone = palette.muted)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun formatBytesCompact(bytes: Long): String? = when {
    bytes <= 0L -> null
    bytes < 1024L -> "${bytes}B"
    bytes < 10 * 1024L -> String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0)
    else -> "${bytes / 1024L}KB"
}
