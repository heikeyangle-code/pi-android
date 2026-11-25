package app.pi.ui.resources

import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.host.ExtensionsAccess
import app.pi.host.HostAdvanced
import app.pi.i18n.tr
import app.pi.packages.PiProjectTrust
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.packages.PiResourceCatalog.Type
import app.pi.ui.PiSeg
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.components.PiSheet
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.PrimaryAction
import app.pi.ui.models.TagPill
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsToggleRow
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「扩展与资源」——资源管理的**唯一**入口。
 *
 * 以前这件事散在四处：设置组里一行「扩展包与项目信任」、工作区的「资源」分段、MCP、Pi 文件（一个通用文件浏览器），
 * 彼此不知道对方存在，而且没有任何一处说得清「pi 现在到底加载了什么」。现在按 pi 自己的分法排：
 *  1. 项目信任（决定项目里的 `.pi` 资源要不要加载，所以放最上）；
 *  2. 已加载的四类资源 + 上下文文件（扩展 / 技能 / 提示模板 / 主题 / 上下文与系统提示）；
 *  3. 扩展包（安装来源）与 MCP；
 *  4. 相关入口：命令与参数、扩展参数与工具（引擎）。
 * 资源就是文件：每一类里都能直接编辑、新建、删除（宿主按 SDK 的资源位置表判定，见 `ResourceFileEditor`），
 * 所以不再需要一个通用的「Pi 文件」浏览器。
 * 所有数字都是 pi 的 `ResourceLoader` 说的，不在 App 里推算。
 */
@Composable
internal fun ResourcesHubScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    access: () -> ExtensionsAccess?,
    advanced: () -> HostAdvanced?,
    onOpenMcp: () -> Unit,
    onOpenCommandSettings: () -> Unit,
    onSettingsSynced: () -> Unit = {},
    startOnProject: Boolean = false,
) {
    // 页内层级：hub / ext / skills / prompts / themes / context / packages。它自己接返回键（组合顺序在设置栈之后，先收到）。
    var page by rememberSaveable { mutableStateOf("hub") }
    // 编辑页：从哪一页进来的、编辑哪个文件。关掉回到来处。
    var editPath by rememberSaveable { mutableStateOf("") }
    var editReturn by rememberSaveable { mutableStateOf("hub") }
    var showFlagsSheet by remember { mutableStateOf(false) }
    val edit: (String) -> Unit = { path -> editReturn = page; editPath = path; page = "edit" }
    BackHandler(enabled = page != "hub" && page != "edit") { page = "hub" }

    val host = access()
    val scope = rememberCoroutineScope()
    val model = remember(host) { host?.let { ResourcesModel(it, scope) } }
    LaunchedEffect(model) {
        // 从项目页进来：直接落在项目层（项目没被信任时 effectiveLayer 仍回全局，页面照常说明原因）。
        if (startOnProject) model?.layer = app.pi.packages.PiResourceCatalog.Layer.Project
        model?.refresh()
    }
    // 回到枢纽（例如从包页回来）时数字可能变了：重读一次。
    DisposableEffect(page) { onDispose { if (page != "hub") model?.refresh() } }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        if (host == null || model == null) {
            PiTopBar(title = tr("扩展与资源", "Extensions & Resources"), onBack = onBack)
            PiInfoNote(tr("引擎还没起来。资源由引擎里的 pi 加载与管理，起来之后这里才有内容。", "Engine is not running yet. Resources are loaded and managed by pi once the engine starts."))
            return@Column
        }
        androidx.compose.animation.AnimatedContent(
            targetState = page,
            transitionSpec = {
                val toChild = targetState != "hub" && (initialState == "hub" || targetState == "edit")
                val ease = androidx.compose.animation.core.FastOutSlowInEasing
                if (toChild) {
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(190, easing = ease)) { it / 6 } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(170)))
                        .togetherWith(
                            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(160, easing = ease)) { -it / 12 } +
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                        )
                } else {
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(190, easing = ease)) { -it / 12 } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(170)))
                        .togetherWith(
                            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(160, easing = ease)) { it / 5 } +
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                        )
                }
            },
            label = "resources-page",
        ) { targetPage ->
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            ) {
                when (targetPage) {
                    "edit" -> ResourceFileEditor(
                        path = editPath,
                        model = model,
                        contentPadding = contentPadding,
                        onClose = { page = editReturn; model.refresh() },
                        onDeleted = { page = editReturn; model.refresh() },
                    )
                    "ext", "skills", "prompts", "themes" -> ResourceTypeScreen(
                        type = typeOfPage(targetPage),
                        model = model,
                        contentPadding = contentPadding,
                        onBack = { page = "hub" },
                        onEdit = edit,
                    )
                    "context" -> ContextScreen(model, contentPadding, onBack = { page = "hub" }, onEdit = edit)
                    "packages" -> PackagesScreen(
                        access = host,
                        model = model,
                        contentPadding = contentPadding,
                        onBack = { page = "hub" },
                    )
                    else -> Hub(
                        model = model,
                        contentPadding = contentPadding,
                        onBack = onBack,
                        onOpen = { page = it },
                        onOpenMcp = onOpenMcp,
                        onOpenFlags = { showFlagsSheet = true },
                        onOpenCommandSettings = onOpenCommandSettings,
                        onSettingsSynced = onSettingsSynced,
                    )
                }
            }
        }
    }

    if (showFlagsSheet) {
        ExtensionFlagsSheet(
            advanced = advanced,
            onDismiss = { showFlagsSheet = false },
        )
    }
}

private fun typeOfPage(page: String): Type = when (page) {
    "skills" -> Type.Skills
    "prompts" -> Type.Prompts
    "themes" -> Type.Themes
    else -> Type.Extensions
}

@Composable
private fun Hub(
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenMcp: () -> Unit,
    onOpenFlags: () -> Unit,
    onOpenCommandSettings: () -> Unit,
    onSettingsSynced: () -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrolled by remember(listState) {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    Column(Modifier.fillMaxSize()) {
    PiTopBar(
        title = tr("扩展与资源", "Extensions & Resources"),
        onBack = onBack,
        scrolled = scrolled,
        actions = { PiTopBarIcon(onClick = { model.refresh() }, contentDescription = tr("刷新", "Refresh"), icon = Icons.Filled.Refresh) },
    )
    val ov = overview(model.loaded, model.snapshot, model.effectiveLayer)
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
        verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
    ) {
        model.error?.let { item { PiInfoNote(it, tone = PiTheme.palette.error) } }

        if (model.pendingReload) {
            item {
                ModelsCard(accent = true) {
                    Row(
                        Modifier.fillMaxWidth().padding(ModelsCardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.gutter),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("改动还没生效", "Changes Pending Reload"), style = PiTheme.text.prose, color = PiTheme.palette.text)
                            Text(tr("开关已写入设置；重新加载后，正在运行的会话才会读到。", "Settings saved; reload for the running session to pick them up."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        }
                        PrimaryAction(if (model.reloading) tr("加载中…", "Reloading…") else tr("重新加载", "Reload"), enabled = !model.reloading, onClick = { model.reload() })
                    }
                }
            }
        }

        item { TrustCard(model, onSettingsSynced) }

        item {
            PiSeg(
                options = listOf(tr("全局视图", "Global View"), tr("本项目视图", "Project View")),
                selectedIndex = if (model.effectiveLayer == Layer.Global) 0 else 1,
                disabledIndices = if (model.projectUsable) emptySet() else setOf(1),
                onSelect = { idx ->
                    if (idx == 0 || model.projectUsable) {
                        model.layer = if (idx == 0) Layer.Global else Layer.Project
                    }
                },
                modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
            )
        }

        item {
            val palette = PiTheme.palette
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                ModelsSectionLabel(
                    if (!model.hasLoaded) tr("读取中…", "Loading…")
                    else if (model.effectiveLayer == Layer.Project) tr("pi 已加载 · 本项目视图", "Loaded by Pi · Project View")
                    else tr("pi 已加载 · 全局视图", "Loaded by Pi · Global View"),
                )
                ModelsCard {
                    Column {
                        listOf(
                            Triple(tr("扩展", "Extensions"), "ext", Type.Extensions),
                            Triple(tr("技能", "Skills"), "skills", Type.Skills),
                            Triple(tr("提示模板", "Prompt Templates"), "prompts", Type.Prompts),
                            Triple(tr("主题", "Themes"), "themes", Type.Themes),
                        ).forEachIndexed { i, (label, page, type) ->
                            if (i > 0) ModelsDivider()
                            val (icon, tint) = when (type) {
                                Type.Extensions -> Icons.Filled.Extension to palette.accent
                                Type.Skills -> Icons.Filled.AutoAwesome to palette.success
                                Type.Prompts -> Icons.Filled.Description to palette.warning
                                Type.Themes -> Icons.Filled.Palette to palette.borderAccent
                            }
                            HubRow(
                                icon = icon,
                                iconTint = tint,
                                title = label,
                                summary = if (model.hasLoaded) typeSummary(type, ov) else "",
                                badge = if (model.hasLoaded) {
                                    app.pi.ui.resources.diagnosticsFor(type, model.loaded).size.takeIf { it > 0 }?.let { tr("$it 个问题", "$it issues") }
                                } else {
                                    null
                                },
                                onClick = { onOpen(page) },
                            )
                        }
                        ModelsDivider()
                        HubRow(
                            icon = Icons.AutoMirrored.Filled.Article,
                            iconTint = palette.muted,
                            title = tr("上下文与系统提示", "Context & System Prompt"),
                            summary = if (model.hasLoaded) contextSummary(model) else "",
                            badge = null,
                            onClick = { onOpen("context") },
                        )
                    }
                }
            }
        }

        item {
            val palette = PiTheme.palette
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                ModelsSectionLabel(tr("扩展包", "Packages"))
                ModelsCard {
                    HubRow(
                        icon = Icons.Filled.Inventory2,
                        iconTint = palette.accent,
                        title = tr("管理扩展包", "Manage Packages"),
                        summary = packagesSummary(model),
                        badge = ov.installFailures.takeIf { it > 0 }?.let { tr("$it 个没装上", "$it failed") },
                        onClick = { onOpen("packages") },
                    )
                }
            }
        }

        item {
            val palette = PiTheme.palette
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                ModelsSectionLabel(tr("参数与选项", "Flags & Options"))
                ModelsCard {
                    Column {
                        HubRow(
                            icon = Icons.Filled.Flag,
                            iconTint = palette.borderAccent,
                            title = tr("扩展运行时参数（flag）", "Extension Runtime Flags"),
                            summary = tr("查看扩展注册的 --flag 并即时修改当前值", "Inspect --flag options registered by extensions and update them live"),
                            badge = null,
                            onClick = onOpenFlags,
                        )
                        ModelsDivider()
                        HubRow(
                            icon = Icons.Filled.Tune,
                            iconTint = palette.warning,
                            title = tr("包安装与命令选项", "Package Install & Command Options"),
                            summary = tr("/skill:name 命令开关、npm 镜像命令、安装遥测", "/skill:name command toggle, npm mirror command, install telemetry"),
                            badge = null,
                            onClick = onOpenCommandSettings,
                        )
                    }
                }
            }
        }

        val problems = model.loaded.diagnostics.map(::describeDiagnostic) +
            model.loaded.extensionErrors.map { tr("${it.message}（${shortPath(it.path)}）", "${it.message} (${shortPath(it.path)})") } +
            model.loaded.extensionWarnings.map { tr("${it.message}（${shortPath(it.path)}）", "${it.message} (${shortPath(it.path)})") } +
            model.catalogDiagnostics.map { d ->
                val tag = when (d.kind) {
                    "command" -> tr("命令冲突", "Command")
                    "shortcut" -> tr("快捷键冲突", "Shortcut")
                    else -> tr("扩展", "Extension")
                }
                val loc = d.path?.takeIf { it.isNotBlank() }?.let { " (${shortPath(it)})" }.orEmpty()
                "$tag: ${d.message}$loc"
            }
        if (problems.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel(tr("pi 报告的问题 · ${problems.size}", "Issues Reported by Pi · ${problems.size}"))
                    ModelsCard {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                            problems.take(10).forEach { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                            if (problems.size > 10) Text(tr("还有 ${problems.size - 10} 条，在各类资源页里看。", "${problems.size - 10} more; view details in each resource screen."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        }
                    }
                }
            }
        }
    }
    }
}

private fun contextSummary(model: ResourcesModel): String {
    val rows = contextRows(model.loaded)
    val files = model.loaded.contextFiles.size
    val replaced = model.loaded.systemPrompt != null
    val appended = model.loaded.appendSystemPrompt.size
    return when {
        rows.isEmpty() -> tr("没有 AGENTS.md / SYSTEM.md", "No AGENTS.md / SYSTEM.md")
        else -> listOfNotNull(
            if (files > 0) tr("$files 个上下文文件", "$files context files") else null,
            if (replaced) tr("替换了系统提示", "Overrides system prompt") else null,
            if (appended > 0) tr("追加 $appended 段", "$appended appended") else null,
        ).joinToString(" · ")
    }
}

private fun packagesSummary(model: ResourcesModel): String {
    val groups = model.snapshot?.global.orEmpty() + model.snapshot?.project.orEmpty()
    val n = groups.count { it.kind == app.pi.packages.PiResourceCatalog.SourceKind.Package }
    return if (n == 0) tr("安装、更新、移除", "Install, update, remove") else tr("$n 个已配置 · 安装、更新、移除", "$n configured · Install, update, remove")
}

@Composable
private fun HubRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    summary: String,
    badge: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ModelsCardPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = PiTheme.palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        badge?.let { TagPill(it, tone = PiTheme.palette.warning) }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PiTheme.palette.dim,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ------------------------------------------------------------------ 项目信任

/**
 * 信任现状卡：结论（标题）→ 后果（pi 会 / 不会做什么）→ 记录在哪 → 能改成什么。
 * 改的是官方信任库，改完宿主会让会话重载，所以卡上不用再提示「重启」。
 */
@Composable
private fun TrustCard(model: ResourcesModel, onSettingsSynced: () -> Unit) {
    val st = model.trust
    var expanded by remember { mutableStateOf(false) }
    var policyExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
        ModelsSectionLabel(tr("项目信任", "Project Trust"))
        ModelsCard(accent = st != null && !st.trusted) {
            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                if (st == null) {
                    Text(tr("读取中…", "Loading…"), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    return@Column
                }
                val tone = if (st.trusted) PiTheme.palette.success else PiTheme.palette.warning
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tone.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (st.trusted) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe,
                            contentDescription = null,
                            tint = tone,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Text(
                        PiProjectTrust.headline(st),
                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
                        color = tone,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (st.needsTrust) TagPill(tr("含 .pi 资源", "Has .pi resources"), tone = PiTheme.palette.accent)
                }
                Text(
                    text = shortPath(st.cwd),
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!st.trusted || expanded || policyExpanded) {
                    Text(PiProjectTrust.explanation(st), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    PiProjectTrust.recordNote(st)?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.dim) }
                }
                error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
                if (expanded) {
                    TrustOptionList(
                        options = PiProjectTrust.adjustments(st),
                        enabled = !busy,
                        onPick = { option ->
                            busy = true
                            error = null
                            model.setTrust(option) { failure ->
                                busy = false
                                error = failure
                                if (failure == null) expanded = false
                            }
                        },
                    )
                }
                if (policyExpanded) {
                    DefaultTrustPolicyList(
                        current = st.defaultProjectTrust,
                        enabled = !busy,
                        onPick = { nextPolicy ->
                            if (nextPolicy == st.defaultProjectTrust) {
                                policyExpanded = false
                                return@DefaultTrustPolicyList
                            }
                            busy = true
                            error = null
                            model.setDefaultProjectTrust(nextPolicy) { failure ->
                                busy = false
                                error = failure
                                if (failure == null) {
                                    policyExpanded = false
                                    onSettingsSynced()
                                }
                            }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    PiSettingsChip(
                        text = if (expanded) tr("收起", "Collapse") else tr("更改", "Change"),
                        onClick = {
                            expanded = !expanded
                            if (expanded) policyExpanded = false
                        },
                    )
                    PiSettingsChip(
                        text = tr("默认策略：${policyLabel(st.defaultProjectTrust)}", "Default Policy: ${policyLabel(st.defaultProjectTrust)}"),
                        active = policyExpanded,
                        onClick = {
                            policyExpanded = !policyExpanded
                            if (policyExpanded) expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultTrustPolicyList(
    current: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val palette = PiTheme.palette
    val choices = listOf(
        Triple(
            "ask",
            tr("每次询问（默认）", "Ask every time (default)"),
            tr("打开含 .pi/ 配置的新项目时弹窗确认。", "Prompt when opening an undecided project with .pi/ resources."),
        ),
        Triple(
            "always",
            tr("总是信任", "Always trust"),
            tr("未单独表态的项目默认信任并加载项目级资源。", "Automatically trust undecided projects and load their resources."),
        ),
        Triple(
            "never",
            tr("从不信任", "Never trust"),
            tr("未单独表态的项目默认跳过项目级扩展、技能与配置。", "Skip project-level extensions, skills, and settings unless explicitly trusted."),
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            tr("全局默认策略（defaultProjectTrust）", "Global default policy (defaultProjectTrust)"),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = palette.muted,
        )
        choices.forEach { (wire, title, desc) ->
            val selected = wire == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) palette.accent.copy(alpha = 0.12f) else palette.cardBg)
                    .clickable(enabled = enabled) { onPick(wire) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = PiTheme.text.prose.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (selected) palette.accent else palette.text,
                    )
                    Text(
                        text = desc,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
                if (selected) {
                    TagPill(tr("当前", "Active"), tone = palette.accent)
                }
            }
        }
    }
}

private fun policyLabel(raw: String): String = when (raw) {
    "always" -> tr("总是信任", "Always Trust")
    "never" -> tr("从不信任", "Never Trust")
    else -> tr("每次询问", "Ask")
}

/** 扩展注册的运行时 `--flag` 编辑器（原 `EngineScreen` 的 flag 段，现归位到「扩展与资源」）。 */
@Composable
private fun ExtensionFlagsSheet(
    advanced: () -> HostAdvanced?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var flags by remember { mutableStateOf<List<HostAdvanced.ExtensionFlag>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        val api = advanced()
        if (api == null) {
            flags = emptyList()
            error = tr("引擎未运行，读不到扩展 flag。", "Engine is not running; cannot read extension flags.")
        } else {
            runCatching { api.flags() }
                .onSuccess { flags = it; error = null }
                .onFailure { error = it.message ?: tr("读取失败", "Failed to load") }
        }
    }

    PiSheet(onDismiss = onDismiss, fullHeight = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr("扩展运行时参数（flag）", "Extension Runtime Flags"), style = app.pi.ui.components.PiType.title, color = PiTheme.palette.text)
            error?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            val list = flags
            if (list == null) {
                Text(tr("读取中…", "Loading…"), style = PiTheme.text.meta, color = PiTheme.palette.muted)
            } else if (list.isEmpty()) {
                Text(tr("当前已加载的扩展没有声明任何 --flag 参数。", "No loaded extensions declare any --flag options."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    items(list.size) { i ->
                        val f = list[i]
                        if (f.isBoolean) {
                            PiSettingsToggleRow(
                                title = "--${f.name}",
                                supporting = f.description,
                                checked = (f.value as? JsonPrimitive)?.content == "true",
                                onCheckedChange = { on ->
                                    val api = advanced() ?: return@PiSettingsToggleRow
                                    scope.launch {
                                        runCatching { api.setFlag(f.name, on) }
                                            .onSuccess { reload++ }
                                            .onFailure { error = it.message ?: tr("修改失败", "Update failed") }
                                    }
                                },
                            )
                        } else {
                            var text by remember(f.name, f.value) { mutableStateOf((f.value as? JsonPrimitive)?.content.orEmpty()) }
                            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                                Text("--${f.name}", style = PiTheme.text.prose, color = PiTheme.palette.text)
                                f.description?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.muted) }
                                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small), verticalAlignment = Alignment.CenterVertically) {
                                    PiEditorField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), placeholder = tr("值", "Value"))
                                    app.pi.ui.components.PiPrimaryButton(
                                        label = tr("保存", "Save"),
                                        compact = true,
                                        onClick = {
                                            val api = advanced() ?: return@PiPrimaryButton
                                            scope.launch {
                                                runCatching { api.setFlag(f.name, text) }
                                                    .onSuccess { reload++ }
                                                    .onFailure { error = it.message ?: tr("保存失败", "Failed to save") }
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
}
