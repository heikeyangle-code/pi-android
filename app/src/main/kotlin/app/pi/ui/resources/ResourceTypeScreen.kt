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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.packages.PiLoadedResources
import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.packages.PiResourceCatalog.Override
import app.pi.packages.PiResourceCatalog.SourceKind
import app.pi.packages.PiResourceCatalog.Type
import app.pi.ui.PiSeg
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiSheet
import app.pi.ui.models.ModelsCard
import app.pi.ui.models.ModelsCardPadding
import app.pi.ui.models.ModelsDivider
import app.pi.ui.models.ModelsSectionLabel
import app.pi.ui.models.TagRow
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSwitch
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch

/**
 * 一类资源（扩展 / 技能 / 提示模板 / 主题）的列表：**开关清单**（pi 的 `pi config`，含没开的）按来源分段，
 * 每行带上 pi **实际加载**到的信息（描述、命令、工具……）。点一行看详情；开关写 pi 自己的 settings，
 * 项目层是三态（继承 / 加载 / 屏蔽），与 `pi config` 项目视图的 灰 / + / − 一致。
 * 开关改完不再说「要重启」：枢纽页会出现「重新加载」（`session.reload`，热重载）。
 */
@Composable
internal fun ResourceTypeScreen(
    type: Type,
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ResourceRow?>(null) }
    val layer = model.effectiveLayer
    val snapshot = model.snapshot

    Column(Modifier.fillMaxSize()) {
        PiTopBar(
            title = type.label,
            onBack = onBack,
            actions = {
                app.pi.ui.PiTopBarIcon(
                    onClick = { model.refresh() },
                    contentDescription = tr("刷新", "Refresh"),
                    icon = Icons.Filled.Refresh,
                )
                creatableOf(type)?.let {
                    app.pi.ui.PiTopBarIcon(
                        onClick = { creating = true },
                        contentDescription = tr("新建", "New"),
                        icon = Icons.Filled.Add,
                    )
                }
            },
        )
        val sections = if (snapshot == null) emptyList() else sections(type, snapshot, layer, model.loaded, query)
        val diagnostics = diagnosticsFor(type, model.loaded)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + PiSpacing.scrollBottom),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.inline + PiSpacing.small),
        ) {
            item {
                PiSeg(
                    options = listOf(tr("全局", "Global"), tr("本项目", "Project")),
                    selectedIndex = if (layer == Layer.Global) 0 else 1,
                    disabledIndices = if (model.projectUsable) emptySet() else setOf(1),
                    onSelect = { if (model.projectUsable) model.layer = if (it == 0) Layer.Global else Layer.Project },
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            if (!model.projectUsable) {
                item { PiInfoNote(tr("当前项目尚未信任：去「扩展与资源 → 项目信任」信任本项目后即可配置项目层资源。", "Project is not trusted yet: trust it under Extensions & Resources → Project Trust to configure project-level resources."), tone = PiTheme.palette.warning) }
            }
            model.error?.let { item { PiInfoNote(it, tone = PiTheme.palette.error) } }
            item {
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = tr("搜索名字、描述、路径", "Search name, description, path"),
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            if (diagnostics.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        ModelsSectionLabel(tr("pi 报告的问题 · ${diagnostics.size}", "Issues Reported by Pi · ${diagnostics.size}"))
                        ModelsCard {
                            Column(Modifier.padding(ModelsCardPadding), verticalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                                diagnostics.forEach {
                                    Column {
                                        Text(
                                            diagnosticLabel(it.kind),
                                            style = PiTheme.text.meta,
                                            color = if (it.kind == PiLoadedResources.DiagKind.Error) PiTheme.palette.error else PiTheme.palette.warning,
                                        )
                                        Text(describeDiagnostic(it), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (snapshot == null) {
                item { PiInfoNote(if (model.loading) tr("正在向 pi 读取…", "Loading from pi…") else tr("还没读到。点右上角刷新重试。", "Not loaded yet. Tap refresh in the top right to retry.")) }
            } else if (sections.isEmpty()) {
                item { PiInfoNote(if (query.isBlank()) emptyHint(type) else tr("没有匹配「$query」的${type.label}。", "No ${type.label} matching \"$query\".")) }
            }
            sections.forEach { section ->
                item(key = "s:${section.group.key}") {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        ModelsSectionLabel(sectionTitle(section))
                        ModelsCard(modifier = Modifier.alpha(if (section.inherited) 0.75f else 1f)) {
                            Column {
                                section.rows.forEachIndexed { i, row ->
                                    if (i > 0) ModelsDivider()
                                    ResourceRowView(
                                        row = row,
                                        layer = layer,
                                        busy = model.busyKey == row.item.type.key + ":" + row.item.path,
                                        onOpen = { detail = row },
                                        onToggle = { model.toggle(row.item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { row ->
        ResourceDetailSheet(
            row = row,
            model = model,
            loaded = model.loaded,
            onDismiss = { detail = null },
            onEdit = { path -> detail = null; onEdit(path) },
            onDeleted = { detail = null; model.refresh() },
        )
    }
    creatableOf(type)?.let { c ->
        if (creating) {
            NewResourceDialog(
                model = model,
                initial = c,
                onDismiss = { creating = false },
                onCreated = { path -> creating = false; onEdit(path) },
            )
        }
    }
}

private fun creatableOf(type: Type): app.pi.packages.PiResourceFile.Creatable? = when (type) {
    Type.Skills -> app.pi.packages.PiResourceFile.Creatable.Skill
    Type.Prompts -> app.pi.packages.PiResourceFile.Creatable.Prompt
    Type.Extensions -> app.pi.packages.PiResourceFile.Creatable.Extension
    Type.Themes -> null
}

private fun emptyHint(type: Type): String = when (type) {
    Type.Extensions -> tr("还没有扩展。装一个扩展包，或把 .ts / .js 放进 ~/.pi/agent/extensions（项目里是 .pi/extensions）。", "No extensions yet. Install a package or place .ts / .js files in ~/.pi/agent/extensions (or .pi/extensions in a project).")
    Type.Skills -> tr("还没有技能。技能是带 SKILL.md 的目录，放进 ~/.pi/agent/skills 或项目的 .pi/skills、.agents/skills。", "No skills yet. Skills are folders containing SKILL.md under ~/.pi/agent/skills or project .pi/skills / .agents/skills.")
    Type.Prompts -> tr("还没有提示模板。把 .md 文件放进 ~/.pi/agent/prompts，文件名就是 /命令名。", "No prompt templates yet. Place .md files in ~/.pi/agent/prompts; the filename becomes the /command name.")
    Type.Themes -> tr("还没有自定义主题。把主题 .json 放进 ~/.pi/agent/themes。", "No custom themes yet. Place theme .json files in ~/.pi/agent/themes.")
}

private fun sectionTitle(s: SourceSection): String {
    val kind = when (s.group.kind) {
        SourceKind.Package -> tr("包", "Package")
        SourceKind.Settings -> tr("设置里列的", "Settings")
        SourceKind.Directory -> tr("目录", "Directory")
        SourceKind.Builtin -> tr("内置", "Built-in")
    }
    return s.group.label + " · " + kind + (if (s.inherited) tr(" · 继承自全局", " · Inherited from Global") else "")
}

@Composable
private fun ResourceRowView(
    row: ResourceRow,
    layer: Layer,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val palette = PiTheme.palette
    val dotColor = when {
        !row.effective -> palette.dim.copy(alpha = 0.45f)
        row.info.loaded -> palette.success
        else -> palette.warning
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = ModelsCardPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PiSpacing.tiny)) {
            Text(
                row.item.name,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = if (row.effective) palette.text else palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.info.description?.let {
                Text(it, style = PiTheme.text.meta, color = PiTheme.palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val tags = rowTags(row, layer)
            if (tags.isNotEmpty()) TagRow(tags)
        }
        if (layer == Layer.Project) {
            OverrideChip(row.item, row.effective, enabled = !busy, onClick = onToggle)
        } else {
            PiSettingsSwitch(checked = row.effective, onCheckedChange = { onToggle() }, enabled = !busy)
        }
    }
}

private fun rowTags(row: ResourceRow, layer: Layer): List<String> = buildList {
    val notLoadedTag = TAG_NOT_LOADED
    if (row.effective && !row.info.loaded) add(notLoadedTag) else addAll(row.info.tags.filter { it != notLoadedTag })
    if (!row.effective) add(tr("已关闭", "Disabled"))
    if (layer == Layer.Project && row.item.override == Override.Load) add(tr("本项目强制加载", "Forced in Project"))
    if (layer == Layer.Project && row.item.override == Override.Unload) add(tr("本项目已屏蔽", "Blocked in Project"))
}.distinct().take(5)

/** 项目层的三态，用 pi 的符号：继承（跟随全局）/ + 加载 / − 屏蔽；点一下走一格。 */
@Composable
private fun OverrideChip(item: PiResourceCatalog.Item, effective: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (glyph, word) = when (item.override) {
        Override.Load -> "+" to tr("加载", "Load")
        Override.Unload -> "−" to tr("屏蔽", "Block")
        Override.Inherit -> (if (effective) "●" else "○") to (if (effective) tr("继承·开", "Inherit · On") else tr("继承·关", "Inherit · Off"))
    }
    PiSettingsChip(text = word, glyph = glyph, active = item.override != Override.Inherit, enabled = enabled, onClick = onClick)
}

// ------------------------------------------------------------------ 详情

@Composable
private fun ResourceDetailSheet(
    row: ResourceRow,
    model: ResourcesModel,
    loaded: PiLoadedResources.Loaded,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val item = row.item
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var askDelete by remember(item.path) { mutableStateOf(false) }
    var deleteError by remember(item.path) { mutableStateOf<String?>(null) }
    val userOwnedPath = item.path.startsWith("/") && (
        item.path.contains("/.pi/agent/") ||
            item.path.contains("/.agents/skills/") ||
            item.path.contains("/.pi/skills/") ||
            item.path.contains("/.pi/prompts/") ||
            item.path.contains("/.pi/themes/") ||
            item.path.contains("/.pi/extensions/")
        ) && !item.path.contains("/node_modules/") && !item.path.contains("/git/")

    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter + PiSpacing.inline),
        ) {
            Text(item.name, style = PiTheme.text.prose, color = PiTheme.palette.text)
            TagRow(listOf(item.type.label) + (if (row.effective) listOf(tr("已开启", "Enabled")) else listOf(tr("已关闭", "Disabled"))) + (if (row.info.loaded) listOf(tr("已加载", "Loaded")) else emptyList()))
            Fact(tr("路径", "Path"), item.path)
            // 内置扩展（`builtin:名字`）没有文件可看；其余都能打开：自己的可改/可删，来自包的只读。
            if (item.path.startsWith("/")) {
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    PiSettingsChip(text = tr("打开 / 编辑文件", "Open / Edit File"), onClick = { onEdit(item.path) })
                    if (userOwnedPath) {
                        PiSettingsChip(text = tr("删除", "Delete"), onClick = { askDelete = true })
                    }
                }
            }
            deleteError?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            when (item.type) {
                Type.Extensions -> ExtensionDetail(loaded.extensions.firstOrNull { it.path == item.path || it.resolvedPath == item.path })
                Type.Skills -> {
                    val k = loaded.skills.firstOrNull { it.filePath == item.path }
                    if (k == null) NotLoaded(row.effective) else {
                        Fact(tr("命令", "Command"), "/skill:${k.name}")
                        if (k.description.isNotBlank()) Fact(tr("描述", "Description"), k.description)
                        Fact(
                            tr("模型自动调用", "Auto Model Invocation"),
                            if (k.disableModelInvocation) {
                                tr("否，只能手动 /skill:${k.name}", "No, manual /skill:${k.name} only")
                            } else {
                                tr("是，模型认为合适时会自己用", "Yes, model may invoke it automatically")
                            },
                        )
                        sourceFacts(k.source)
                    }
                }
                Type.Prompts -> {
                    val p = loaded.prompts.firstOrNull { it.filePath == item.path }
                    if (p == null) NotLoaded(row.effective) else {
                        Fact(tr("命令", "Command"), "/${p.name}" + (p.argumentHint?.let { " $it" } ?: ""))
                        if (p.description.isNotBlank()) Fact(tr("描述", "Description"), p.description)
                        sourceFacts(p.source)
                        Fact(
                            tr("正文", "Body") + if (p.body.truncated) tr("（前 ${p.body.text.length} 字，共 ${p.body.size}）", " (first ${p.body.text.length} of ${p.body.size} chars)") else "",
                            p.body.text,
                            mono = true,
                        )
                    }
                }
                Type.Themes -> {
                    val t = loaded.themes.firstOrNull { it.path == item.path }
                    if (t == null) NotLoaded(row.effective) else {
                        sourceFacts(t.source)
                        Text(tr("在「设置 → 外观 → 主题」里选用。", "Select in Settings → Appearance → Theme."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    }
                }
            }
            diagnosticsForPath(item.type, loaded, item.path).forEach {
                Text(describeDiagnostic(it), style = PiTheme.text.meta, color = PiTheme.palette.warning)
            }
        }
    }
    if (askDelete) {
        val isSkillDir = item.type == Type.Skills && item.path.endsWith("/SKILL.md")
        app.pi.ui.settings.PiSettingsDialog(
            onDismissRequest = { askDelete = false },
            title = if (isSkillDir) tr("删除整个技能？", "Delete entire skill?") else tr("删除这个资源文件？", "Delete this resource file?"),
            sub = item.path,
            body = if (isSkillDir) {
                tr("会删除整个技能目录及其中的所有脚本与参考文档，不可撤销。", "This deletes the entire skill folder including scripts and references. This cannot be undone.")
            } else {
                tr("文件将被永久删除，不可撤销。", "This file will be permanently deleted. This cannot be undone.")
            },
            confirmationLabel = tr("删除", "Delete"),
            onConfirm = {
                askDelete = false
                scope.launch {
                    when (val r = model.deleteFile(item.path)) {
                        is PiResourceCatalog.Parsed.Ok -> onDeleted()
                        is PiResourceCatalog.Parsed.Error -> deleteError = r.message
                    }
                }
            },
            confirmationTone = PiTheme.palette.error,
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { askDelete = false },
        )
    }
}

private fun diagnosticsForPath(type: Type, loaded: PiLoadedResources.Loaded, path: String) =
    diagnosticsFor(type, loaded).filter { d ->
        d.path == path || d.collision?.let { it.winnerPath == path || it.loserPath == path } == true
    }

@Composable
private fun NotLoaded(effective: Boolean) {
    Text(
        if (effective) tr("pi 这次没有加载它（可能被同名资源挤掉、格式不对，或项目未被信任）。看上面的问题列表。", "Pi did not load this resource (name collision, invalid format, or untrusted project). See the issues list above.") else tr("已关闭，pi 不会加载。", "Disabled; pi will not load it."),
        style = PiTheme.text.meta,
        color = PiTheme.palette.muted,
    )
}

@Composable
private fun sourceFacts(s: PiLoadedResources.Source?) {
    if (s == null) return
    val scope = s.scope.label.ifEmpty { null }
    val origin = when (s.origin) {
        PiLoadedResources.Origin.Package -> tr("来自包", "From package") + (s.source?.let { " $it" } ?: "")
        PiLoadedResources.Origin.TopLevel -> tr("顶层（目录或设置里直接列的）", "Top-level (directory or settings entry)")
        PiLoadedResources.Origin.Unknown -> null
    }
    listOfNotNull(scope, origin).takeIf { it.isNotEmpty() }?.let { Fact(tr("来源", "Source"), it.joinToString(" · ")) }
}

@Composable
private fun ExtensionDetail(e: PiLoadedResources.Extension?) {
    if (e == null) {
        Text(tr("pi 这次没有加载这个扩展（关闭、加载出错，或项目未被信任）。", "Pi did not load this extension (disabled, load error, or untrusted project)."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
        return
    }
    sourceFacts(e.source)
    if (e.commands.isNotEmpty()) Fact(tr("命令", "Commands"), e.commands.joinToString("\n") { "/" + it.name + (it.description?.let { d -> " — $d" } ?: "") })
    if (e.tools.isNotEmpty()) Fact(tr("工具", "Tools"), e.tools.joinToString("\n") { it.name + (it.description?.let { d -> " — ${d.lineSequence().first()}" } ?: "") })
    if (e.flags.isNotEmpty()) Fact(tr("启动参数", "Flags"), e.flags.joinToString("\n") { "--" + it.name + (if (it.isBoolean) "" else tr(" <值>", " <value>")) + (it.description?.let { d -> " — $d" } ?: "") })
    if (e.shortcuts.isNotEmpty()) Fact(tr("快捷键", "Shortcuts"), e.shortcuts.joinToString("\n") { it.shortcut + (it.description?.let { d -> " — $d" } ?: "") })
    if (e.events.isNotEmpty()) Fact(tr("监听的事件", "Events"), e.events.joinToString(tr("、", ", ")))
    if (e.registered == 0 && e.events.isEmpty()) Text(tr("它没有注册命令、工具、参数或快捷键。", "Does not register any commands, tools, flags, or shortcuts."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
}

@Composable
internal fun Fact(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.tiny)) {
        Text(label, style = PiTheme.text.meta, color = PiTheme.palette.muted)
        Text(value, style = if (mono) PiTheme.text.monoSmall else PiTheme.text.prose, color = PiTheme.palette.text)
    }
}
