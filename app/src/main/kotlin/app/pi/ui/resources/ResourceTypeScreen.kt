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
                creatableOf(type)?.let { PiSettingsChip(text = "新建", onClick = { creating = true }) }
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
                    options = listOf("全局", "本项目"),
                    selectedIndex = if (layer == Layer.Global) 0 else 1,
                    onSelect = { if (model.projectUsable) model.layer = if (it == 0) Layer.Global else Layer.Project },
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            if (!model.projectUsable) {
                item { PiInfoNote("这个项目还没被信任：pi 不读项目里的 .pi 资源，所以只能改全局。去枢纽页的「项目信任」表态后才能改项目层。", tone = PiTheme.palette.warning) }
            }
            model.error?.let { item { PiInfoNote(it, tone = PiTheme.palette.error) } }
            item {
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索名字、描述、路径",
                    modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal),
                )
            }
            if (diagnostics.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        ModelsSectionLabel("pi 报告的问题 · ${diagnostics.size}")
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
                item { PiInfoNote(if (model.loading) "正在向 pi 读取…" else "还没读到。点右上角刷新重试。") }
            } else if (sections.isEmpty()) {
                item { PiInfoNote(if (query.isBlank()) emptyHint(type) else "没有匹配「$query」的${type.label}。") }
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
            loaded = model.loaded,
            onDismiss = { detail = null },
            onEdit = { path -> detail = null; onEdit(path) },
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
    Type.Extensions -> "还没有扩展。装一个扩展包，或把 .ts / .js 放进 ~/.pi/agent/extensions（项目里是 .pi/extensions）。"
    Type.Skills -> "还没有技能。技能是带 SKILL.md 的目录，放进 ~/.pi/agent/skills 或项目的 .pi/skills、.agents/skills。"
    Type.Prompts -> "还没有提示模板。把 .md 文件放进 ~/.pi/agent/prompts，文件名就是 /命令名。"
    Type.Themes -> "还没有自定义主题。把主题 .json 放进 ~/.pi/agent/themes。"
}

private fun sectionTitle(s: SourceSection): String {
    val kind = when (s.group.kind) {
        SourceKind.Package -> "包"
        SourceKind.Settings -> "设置里列的"
        SourceKind.Directory -> "目录"
        SourceKind.Builtin -> "内置"
    }
    return s.group.label + " · " + kind + (if (s.inherited) " · 继承自全局" else "")
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
    if (row.effective && !row.info.loaded) add("未加载") else addAll(row.info.tags.filter { it != "未加载" })
    if (!row.effective) add("已关闭")
    if (layer == Layer.Project && row.item.override == Override.Load) add("本项目强制加载")
    if (layer == Layer.Project && row.item.override == Override.Unload) add("本项目已屏蔽")
}.distinct().take(5)

/** 项目层的三态，用 pi 的符号：继承（跟随全局）/ + 加载 / − 屏蔽；点一下走一格。 */
@Composable
private fun OverrideChip(item: PiResourceCatalog.Item, effective: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (glyph, word) = when (item.override) {
        Override.Load -> "+" to "加载"
        Override.Unload -> "−" to "屏蔽"
        Override.Inherit -> (if (effective) "●" else "○") to (if (effective) "继承·开" else "继承·关")
    }
    PiSettingsChip(text = word, glyph = glyph, active = item.override != Override.Inherit, enabled = enabled, onClick = onClick)
}

// ------------------------------------------------------------------ 详情

@Composable
private fun ResourceDetailSheet(row: ResourceRow, loaded: PiLoadedResources.Loaded, onDismiss: () -> Unit, onEdit: (String) -> Unit) {
    val item = row.item
    PiSheet(onDismiss = onDismiss, fullHeight = true) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter + PiSpacing.inline),
        ) {
            Text(item.name, style = PiTheme.text.prose, color = PiTheme.palette.text)
            TagRow(listOf(item.type.label) + (if (row.effective) listOf("已开启") else listOf("已关闭")) + (if (row.info.loaded) listOf("已加载") else emptyList()))
            Fact("路径", item.path)
            // 内置扩展（`builtin:名字`）没有文件可看；其余都能打开：自己的可改，来自包的只读。
            if (item.path.startsWith("/")) {
                PiSettingsChip(text = "打开 / 编辑文件", onClick = { onEdit(item.path) })
            }
            when (item.type) {
                Type.Extensions -> ExtensionDetail(loaded.extensions.firstOrNull { it.path == item.path || it.resolvedPath == item.path })
                Type.Skills -> {
                    val k = loaded.skills.firstOrNull { it.filePath == item.path }
                    if (k == null) NotLoaded(row.effective) else {
                        Fact("命令", "/skill:${k.name}")
                        if (k.description.isNotBlank()) Fact("描述", k.description)
                        Fact("模型自动调用", if (k.disableModelInvocation) "否，只能手动 /skill:${k.name}" else "是，模型认为合适时会自己用")
                        sourceFacts(k.source)
                    }
                }
                Type.Prompts -> {
                    val p = loaded.prompts.firstOrNull { it.filePath == item.path }
                    if (p == null) NotLoaded(row.effective) else {
                        Fact("命令", "/${p.name}" + (p.argumentHint?.let { " $it" } ?: ""))
                        if (p.description.isNotBlank()) Fact("描述", p.description)
                        sourceFacts(p.source)
                        Fact("正文" + if (p.body.truncated) "（前 ${p.body.text.length} 字，共 ${p.body.size}）" else "", p.body.text, mono = true)
                    }
                }
                Type.Themes -> {
                    val t = loaded.themes.firstOrNull { it.path == item.path }
                    if (t == null) NotLoaded(row.effective) else {
                        sourceFacts(t.source)
                        Text("在「设置 → 外观 → 主题」里选用。", style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    }
                }
            }
            diagnosticsForPath(item.type, loaded, item.path).forEach {
                Text(describeDiagnostic(it), style = PiTheme.text.meta, color = PiTheme.palette.warning)
            }
        }
    }
}

private fun diagnosticsForPath(type: Type, loaded: PiLoadedResources.Loaded, path: String) =
    diagnosticsFor(type, loaded).filter { d ->
        d.path == path || d.collision?.let { it.winnerPath == path || it.loserPath == path } == true
    }

@Composable
private fun NotLoaded(effective: Boolean) {
    Text(
        if (effective) "pi 这次没有加载它（可能被同名资源挤掉、格式不对，或项目未被信任）。看上面的问题列表。" else "已关闭，pi 不会加载。",
        style = PiTheme.text.meta,
        color = PiTheme.palette.muted,
    )
}

@Composable
private fun sourceFacts(s: PiLoadedResources.Source?) {
    if (s == null) return
    val scope = s.scope.label.ifEmpty { null }
    val origin = when (s.origin) {
        PiLoadedResources.Origin.Package -> "来自包" + (s.source?.let { " $it" } ?: "")
        PiLoadedResources.Origin.TopLevel -> "顶层（目录或设置里直接列的）"
        PiLoadedResources.Origin.Unknown -> null
    }
    listOfNotNull(scope, origin).takeIf { it.isNotEmpty() }?.let { Fact("来源", it.joinToString(" · ")) }
}

@Composable
private fun ExtensionDetail(e: PiLoadedResources.Extension?) {
    if (e == null) {
        Text("pi 这次没有加载这个扩展（关闭、加载出错，或项目未被信任）。", style = PiTheme.text.meta, color = PiTheme.palette.muted)
        return
    }
    sourceFacts(e.source)
    if (e.commands.isNotEmpty()) Fact("命令", e.commands.joinToString("\n") { "/" + it.name + (it.description?.let { d -> " — $d" } ?: "") })
    if (e.tools.isNotEmpty()) Fact("工具", e.tools.joinToString("\n") { it.name + (it.description?.let { d -> " — ${d.lineSequence().first()}" } ?: "") })
    if (e.flags.isNotEmpty()) Fact("启动参数", e.flags.joinToString("\n") { "--" + it.name + (if (it.isBoolean) "" else " <值>") + (it.description?.let { d -> " — $d" } ?: "") })
    if (e.shortcuts.isNotEmpty()) Fact("快捷键", e.shortcuts.joinToString("\n") { it.shortcut + (it.description?.let { d -> " — $d" } ?: "") })
    if (e.events.isNotEmpty()) Fact("监听的事件", e.events.joinToString("、"))
    if (e.registered == 0 && e.events.isEmpty()) Text("它没有注册命令、工具、参数或快捷键。", style = PiTheme.text.meta, color = PiTheme.palette.muted)
}

@Composable
internal fun Fact(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.tiny)) {
        Text(label, style = PiTheme.text.meta, color = PiTheme.palette.muted)
        Text(value, style = if (mono) PiTheme.text.monoSmall else PiTheme.text.prose, color = PiTheme.palette.text)
    }
}
