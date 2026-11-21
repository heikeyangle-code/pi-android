package app.pi.ui.resources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * 彻底化繁为简：
 *  1. 顶部用「本项目 / 全局」分段直接切换层级，不再把两套目录混排在同一屏；
 *  2. 取消繁琐的「新建弹窗」与「更多文件」二级菜单：当前层级下直接展示 3 个固定槽位
 *     （`AGENTS.md` 上下文说明、`APPEND_SYSTEM.md` 追加提示词、`SYSTEM.md` 替换提示词）；
 *  3. 点任意一项直接进全屏编辑器：未创建的文件打开为空白草稿，只有真正输入内容并点「保存」才落盘，
 *     绝不提前在磁盘上生成带硬编码模板文字的废文件。
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

private data class ScopeSlotFiles(
    val agents: File,
    val claude: File,
    val append: File,
    val system: File,
    val guestAgents: String,
    val guestAppend: String,
    val guestSystem: String,
    val displayAgentsPath: String,
    val displayAppendPath: String,
    val displaySystemPath: String,
)

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
    val projectTrusted = model.trust?.trusted != false

    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val workspace = remember(context, model.trust?.cwd) { PtyLauncher.workspaceHost(context) }
    val guestAgentDir = "/root/.pi/agent"
    val guestCwd = model.trust?.cwd?.takeIf { it.startsWith("/") } ?: "/workspace"

    val slots = remember(paths, workspace, isProject, guestCwd) {
        if (isProject) {
            val dotPi = PiProjectConfig.root(workspace)
            ScopeSlotFiles(
                agents = File(workspace, "AGENTS.md"),
                claude = File(workspace, "CLAUDE.md"),
                append = File(dotPi, "APPEND_SYSTEM.md"),
                system = File(dotPi, "SYSTEM.md"),
                guestAgents = "$guestCwd/AGENTS.md",
                guestAppend = "$guestCwd/.pi/APPEND_SYSTEM.md",
                guestSystem = "$guestCwd/.pi/SYSTEM.md",
                displayAgentsPath = "AGENTS.md",
                displayAppendPath = ".pi/APPEND_SYSTEM.md",
                displaySystemPath = ".pi/SYSTEM.md",
            )
        } else {
            ScopeSlotFiles(
                agents = File(paths.agentDir, "AGENTS.md"),
                claude = File(paths.agentDir, "CLAUDE.md"),
                append = File(paths.agentDir, "APPEND_SYSTEM.md"),
                system = File(paths.agentDir, "SYSTEM.md"),
                guestAgents = "$guestAgentDir/AGENTS.md",
                guestAppend = "$guestAgentDir/APPEND_SYSTEM.md",
                guestSystem = "$guestAgentDir/SYSTEM.md",
                displayAgentsPath = "~/.pi/agent/AGENTS.md",
                displayAppendPath = "~/.pi/agent/APPEND_SYSTEM.md",
                displaySystemPath = "~/.pi/agent/SYSTEM.md",
            )
        }
    }

    var fileSnippets by remember { mutableStateOf<Map<String, Pair<Long, String>>>(emptyMap()) }
    LaunchedEffect(slots, diskEpoch, model.loaded, model.pendingReload) {
        fileSnippets = withContext(Dispatchers.IO) {
            listOf(slots.agents, slots.claude, slots.append, slots.system).associate { f ->
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
    val agentsActive = loaded.contextFiles.any { it.path == slots.guestAgents }
    val appendActive = loaded.appendSystemPrompt.any { it == slots.guestAppend }
    val systemActive = loaded.systemPrompt == slots.guestSystem

    // 当前层级下额外已加载的 CLAUDE.md 或上层目录 AGENTS.md
    val extraContextFiles = remember(loaded.contextFiles, isProject, guestCwd, slots.guestAgents) {
        loaded.contextFiles.filter { f ->
            if (f.path == slots.guestAgents) return@filter false
            val isGlobalFile = f.path.startsWith("$guestAgentDir/")
            if (isProject) !isGlobalFile else isGlobalFile
        }
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
            item(key = "scope-seg") {
                PiSeg(
                    options = listOf(tr("全局", "Global"), tr("本项目", "Project")),
                    selectedIndex = if (isProject) 1 else 0,
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
                        tr("当前项目尚未信任，仅可编辑全局文件。", "Project is not trusted yet; only global files can be edited."),
                        tone = palette.warning,
                    )
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
                                    tr("已保存修改", "Changes Saved"),
                                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                                    color = palette.text,
                                )
                                Text(
                                    tr("点右侧重载让当前会话生效", "Tap Reload to apply to active session"),
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                )
                            }
                            PrimaryAction(
                                text = if (model.reloading) tr("重载中…", "Reloading…") else tr("重载", "Reload"),
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

            model.error?.let { err ->
                item(key = "model-err") { PiInfoNote(err, tone = palette.error) }
            }

            // 1. 上下文说明 (AGENTS.md)
            item(key = "context-card") {
                val (bytes, preview) = fileSnippets[slots.agents.absolutePath] ?: (-1L to "")
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(tr("上下文说明", "Context Instructions"))
                    ModelsCard {
                        Column {
                            CleanPromptRow(
                                icon = Icons.Filled.Article,
                                iconTint = palette.accent,
                                title = "AGENTS.md",
                                path = slots.displayAgentsPath,
                                hint = preview.ifEmpty { tr("项目背景、目录结构与代码规范", "Project background, structure & coding conventions") },
                                fileBytes = bytes,
                                isActive = agentsActive,
                                onClick = { onEdit(slots.guestAgents) },
                            )
                            extraContextFiles.forEach { extra ->
                                ModelsDivider()
                                CleanPromptRow(
                                    icon = Icons.Filled.Article,
                                    iconTint = palette.accent,
                                    title = extra.path.substringAfterLast('/'),
                                    path = shortPath(extra.path),
                                    hint = extra.body.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                                        .ifEmpty { tr("已加载上下文文件", "Loaded context file") },
                                    fileBytes = extra.body.size,
                                    isActive = true,
                                    onClick = { onEdit(extra.path) },
                                )
                            }
                        }
                    }
                }
            }

            // 2. 系统提示词 (APPEND_SYSTEM.md & SYSTEM.md)
            item(key = "system-prompt-card") {
                val (appendBytes, appendPreview) = fileSnippets[slots.append.absolutePath] ?: (-1L to "")
                val (systemBytes, systemPreview) = fileSnippets[slots.system.absolutePath] ?: (-1L to "")
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(tr("系统提示词", "System Prompt"))
                    ModelsCard {
                        Column {
                            CleanPromptRow(
                                icon = Icons.Filled.Edit,
                                iconTint = palette.success,
                                title = tr("追加系统提示词", "Append System Prompt"),
                                roleBadge = tr("推荐", "Recommended"),
                                roleBadgeTone = palette.success,
                                path = slots.displayAppendPath,
                                hint = appendPreview.ifEmpty { tr("附在默认系统提示之后，保留默认工具规则", "Appended after default prompt; keeps tool rules") },
                                fileBytes = appendBytes,
                                isActive = appendActive,
                                onClick = { onEdit(slots.guestAppend) },
                            )
                            ModelsDivider()
                            CleanPromptRow(
                                icon = Icons.Filled.Warning,
                                iconTint = palette.warning,
                                title = tr("替换默认提示词", "Override System Prompt"),
                                roleBadge = tr("高级", "Advanced"),
                                roleBadgeTone = palette.warning,
                                path = slots.displaySystemPath,
                                hint = systemPreview.ifEmpty { tr("整份替换默认提示词；删除即恢复默认", "Replaces entire built-in prompt; delete to restore") },
                                fileBytes = systemBytes,
                                isActive = systemActive,
                                onClick = { onEdit(slots.guestSystem) },
                            )
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
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            isActive -> TagPill(tr("已生效", "Active"), tone = palette.success)
            exists -> TagPill(tr("待重载", "Reload"), tone = palette.warning)
            else -> TagPill(tr("未设置", "Not set"), tone = palette.muted)
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}
