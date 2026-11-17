package app.pi.ui.resources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import app.pi.host.ExtensionsAccess
import app.pi.packages.PiProjectTrust
import app.pi.packages.PiResourceCatalog.Type
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
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

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
    onOpenMcp: () -> Unit,
    onOpenEngine: () -> Unit,
    onOpenCommandSettings: () -> Unit,
    onOpenTrustPolicy: () -> Unit,
) {
    // 页内层级：hub / ext / skills / prompts / themes / context / packages。它自己接返回键（组合顺序在设置栈之后，先收到）。
    var page by rememberSaveable { mutableStateOf("hub") }
    // 编辑页：从哪一页进来的、编辑哪个文件。关掉回到来处。
    var editPath by rememberSaveable { mutableStateOf("") }
    var editReturn by rememberSaveable { mutableStateOf("hub") }
    val edit: (String) -> Unit = { path -> editReturn = page; editPath = path; page = "edit" }
    BackHandler(enabled = page != "hub" && page != "edit") { page = "hub" }

    val host = access()
    val scope = rememberCoroutineScope()
    val model = remember(host) { host?.let { ResourcesModel(it, scope) } }
    LaunchedEffect(model) { model?.refresh() }
    // 回到枢纽（例如从包页回来）时数字可能变了：重读一次。
    DisposableEffect(page) { onDispose { if (page != "hub") model?.refresh() } }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        if (host == null || model == null) {
            PiTopBar(title = "扩展与资源", onBack = onBack)
            PiInfoNote("引擎还没起来。资源由引擎里的 pi 加载与管理，起来之后这里才有内容。")
            return@Column
        }
        when (page) {
            "edit" -> ResourceFileEditor(
                path = editPath,
                model = model,
                contentPadding = contentPadding,
                onClose = { page = editReturn },
                onDeleted = { model.refresh(); page = editReturn },
            )
            "ext", "skills", "prompts", "themes" -> ResourceTypeScreen(
                type = typeOfPage(page),
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
                onOpenEngine = onOpenEngine,
                onOpenCommandSettings = onOpenCommandSettings,
                onOpenTrustPolicy = onOpenTrustPolicy,
            )
        }
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
    onOpenEngine: () -> Unit,
    onOpenCommandSettings: () -> Unit,
    onOpenTrustPolicy: () -> Unit,
) {
    PiTopBar(
        title = "扩展与资源",
        onBack = onBack,
        actions = { PiTopBarIcon(onClick = { model.refresh() }, contentDescription = "刷新", icon = Icons.Filled.Refresh) },
    )
    val ov = overview(model.loaded, model.snapshot, model.effectiveLayer)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                            Text("改动还没生效", style = PiTheme.text.prose, color = PiTheme.palette.text)
                            Text("开关已写入设置；重新加载后，正在运行的会话才会读到。", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                        }
                        PrimaryAction(if (model.reloading) "加载中…" else "重新加载", enabled = !model.reloading, onClick = { model.reload() })
                    }
                }
            }
        }

        item { TrustCard(model, onOpenTrustPolicy) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                ModelsSectionLabel(if (model.hasLoaded) "pi 已加载" else "读取中…")
                ModelsCard {
                    Column {
                        listOf(
                            Triple("扩展", "ext", Type.Extensions),
                            Triple("技能", "skills", Type.Skills),
                            Triple("提示模板", "prompts", Type.Prompts),
                            Triple("主题", "themes", Type.Themes),
                        ).forEachIndexed { i, (label, page, type) ->
                            if (i > 0) ModelsDivider()
                            HubRow(
                                title = label,
                                summary = if (model.hasLoaded) typeSummary(type, ov) else "",
                                badge = if (model.hasLoaded) {
                                    app.pi.ui.resources.diagnosticsFor(type, model.loaded).size.takeIf { it > 0 }?.let { "$it 个问题" }
                                } else {
                                    null
                                },
                                onClick = { onOpen(page) },
                            )
                        }
                        ModelsDivider()
                        HubRow(
                            title = "上下文与系统提示",
                            summary = if (model.hasLoaded) contextSummary(model) else "",
                            badge = null,
                            onClick = { onOpen("context") },
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                ModelsSectionLabel("来源与连接")
                ModelsCard {
                    Column {
                        HubRow(
                            title = "扩展包",
                            summary = packagesSummary(model),
                            badge = ov.installFailures.takeIf { it > 0 }?.let { "$it 个没装上" },
                            onClick = { onOpen("packages") },
                        )
                        ModelsDivider()
                        HubRow(title = "MCP 服务器", summary = "给 pi 接外部工具", badge = null, onClick = onOpenMcp)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                ModelsSectionLabel("相关")
                ModelsCard {
                    Column {
                        HubRow(title = "技能命令与扩展启动参数", summary = "/skill:name 开关、--flag 传参", badge = null, onClick = onOpenCommandSettings)
                        ModelsDivider()
                        HubRow(title = "引擎：工具与扩展 flag", summary = "已注册的工具、运行时改 flag", badge = null, onClick = onOpenEngine)
                    }
                }
            }
        }

        val problems = model.loaded.diagnostics.map(::describeDiagnostic) +
            model.loaded.extensionErrors.map { it.message + "（" + shortPath(it.path) + "）" } +
            model.loaded.extensionWarnings.map { it.message + "（" + shortPath(it.path) + "）" }
        if (problems.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    ModelsSectionLabel("pi 报告的问题 · ${problems.size}")
                    ModelsCard {
                        Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                            problems.take(8).forEach { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                            if (problems.size > 8) Text("还有 ${problems.size - 8} 条，在各类资源页里看。", style = PiTheme.text.meta, color = PiTheme.palette.muted)
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
        rows.isEmpty() -> "没有 AGENTS.md / SYSTEM.md"
        else -> listOfNotNull(
            if (files > 0) "$files 个上下文文件" else null,
            if (replaced) "替换了系统提示" else null,
            if (appended > 0) "追加 $appended 段" else null,
        ).joinToString(" · ")
    }
}

private fun packagesSummary(model: ResourcesModel): String {
    val groups = model.snapshot?.global.orEmpty() + model.snapshot?.project.orEmpty()
    val n = groups.count { it.kind == app.pi.packages.PiResourceCatalog.SourceKind.Package }
    return if (n == 0) "安装、更新、移除" else "$n 个已配置 · 安装、更新、移除"
}

@Composable
private fun HubRow(title: String, summary: String, badge: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ModelsCardPadding, vertical = PiSpacing.inline + PiSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PiTheme.text.prose, color = PiTheme.palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotEmpty()) Text(summary, style = PiTheme.text.meta, color = PiTheme.palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        badge?.let { TagPill(it, tone = PiTheme.palette.warning) }
        Text("›", style = PiTheme.text.prose, color = PiTheme.palette.dim)
    }
}

// ------------------------------------------------------------------ 项目信任

/**
 * 信任现状卡：结论（标题）→ 后果（pi 会 / 不会做什么）→ 记录在哪 → 能改成什么。
 * 改的是官方信任库，改完宿主会让会话重载，所以卡上不用再提示「重启」。
 */
@Composable
private fun TrustCard(model: ResourcesModel, onOpenTrustPolicy: () -> Unit) {
    val st = model.trust
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
        ModelsSectionLabel("项目信任")
        ModelsCard(accent = st != null && !st.trusted) {
            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                if (st == null) {
                    Text("读取中…", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    Text(
                        PiProjectTrust.headline(st),
                        style = PiTheme.text.prose,
                        color = if (st.trusted) PiTheme.palette.success else PiTheme.palette.warning,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (st.needsTrust) TagPill("项目有 .pi 资源")
                }
                Text(PiProjectTrust.explanation(st), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                PiProjectTrust.recordNote(st)?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.dim) }
                Text(st.cwd, style = PiTheme.text.monoSmall, color = PiTheme.palette.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    PiSettingsChip(text = if (expanded) "收起" else "更改", onClick = { expanded = !expanded })
                    PiSettingsChip(text = "默认策略：${policyLabel(st.defaultProjectTrust)}", onClick = onOpenTrustPolicy)
                }
            }
        }
    }
}

private fun policyLabel(raw: String): String = when (raw) {
    "always" -> "总是信任"
    "never" -> "从不信任"
    else -> "每次询问"
}
