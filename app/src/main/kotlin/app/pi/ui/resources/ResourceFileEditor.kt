package app.pi.ui.resources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pi.i18n.tr
import app.pi.packages.PiResourceCatalog.Parsed
import app.pi.packages.PiResourceFile
import app.pi.ui.PiTopBar
import app.pi.ui.models.PrimaryAction
import app.pi.ui.models.TagRow
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiInfoNote
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsDialog
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch

/**
 * 资源文件的编辑页（整页，不是弹层：手机上弹层的下滑手势会和文本框的滚动、选字打架，
 * 还会在有未保存内容时被误关）。
 *
 * 读写都走宿主（pi 进程自己的文件系统，不用把 guest 路径翻成宿主路径）：
 *  - 位置白名单在宿主，按 SDK 的资源位置表；不在表里的只读（来自包的资源），页里直接说原因；
 *  - 保存带打开时的指纹，文件在你编辑期间被别处改过就拒绝，不盖别人的改动；
 *  - 写前预检是 [PiResourceFile.check]：主题必须是合法 JSON 且有 `name` / `colors`（拦住保存）；
 *    技能缺 `description` pi 整个不加载（提醒，不拦，写到一半很常见）。
 * 保存后只记「待重新加载」——要让正在跑的会话读到，在枢纽页点一次「重新加载」。
 */
@Composable
internal fun ResourceFileEditor(
    path: String,
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
    /** 删除成功后调用（技能是整个目录）。 */
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var doc by remember(path) { mutableStateOf<PiResourceFile.Doc?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var draft by remember(path) { mutableStateOf("") }
    var saving by remember(path) { mutableStateOf(false) }
    var message by remember(path) { mutableStateOf<String?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var askDiscard by remember(path) { mutableStateOf(false) }
    var askDelete by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        when (val r = model.readFile(path)) {
            is Parsed.Ok -> { doc = r.value; draft = r.value.text }
            is Parsed.Error -> {
                val fileName = path.substringAfterLast('/')
                val virtualKind = when (fileName) {
                    "SYSTEM.md" -> PiResourceFile.Kind.System
                    "APPEND_SYSTEM.md" -> PiResourceFile.Kind.AppendSystem
                    "AGENTS.md", "CLAUDE.md", "AGENTS.override.md" -> PiResourceFile.Kind.Context
                    else -> null
                }
                if (virtualKind != null) {
                    val scopeKind = if (path.contains("/.pi/agent/")) {
                        app.pi.packages.PiLoadedResources.Scope.User
                    } else {
                        app.pi.packages.PiLoadedResources.Scope.Project
                    }
                    doc = PiResourceFile.Doc(
                        path = path,
                        kind = virtualKind,
                        scope = scopeKind,
                        rel = fileName,
                        text = "",
                        size = -1L,
                        mtimeMs = 0L,
                        writable = true,
                        reason = null,
                    )
                    draft = ""
                } else {
                    loadError = r.message
                }
            }
        }
    }

    val d = doc
    val dirty = d != null && draft != d.text
    val check = remember(d?.kind, d?.rel, draft) { d?.let { PiResourceFile.check(it.kind, it.rel, draft) } }
    val canSave = d != null && d.writable && dirty && !saving && check?.ok != false

    fun leave() { if (dirty) askDiscard = true else onClose() }
    BackHandler(enabled = true) { leave() }

    fun save() {
        val cur = d ?: return
        saving = true
        failure = null
        message = null
        scope.launch {
            when (val r = model.writeFile(cur.path, draft, PiResourceFile.Stamp(cur.size, cur.mtimeMs))) {
                is Parsed.Ok -> {
                    doc = cur.copy(text = draft, size = r.value.size, mtimeMs = r.value.mtimeMs)
                    model.reload()
                    message = tr("已保存并重载生效。", "Saved and reloaded into active session.")
                }
                is Parsed.Error -> failure = r.message
            }
            saving = false
        }
    }

    // PiRoot 已经在非对话目的地挂载了 imePadding()，这里不重复加，避免软键盘弹起时双倍抬升。
    Column(Modifier.fillMaxSize()) {
        PiTopBar(
            title = d?.let { it.kind.label + " · " + it.rel.substringAfterLast('/') } ?: tr("编辑", "Edit"),
            onBack = { leave() },
            actions = {
                if (d?.writable == true) PrimaryAction(if (saving) tr("保存中…", "Saving…") else tr("保存", "Save"), enabled = canSave, onClick = { save() })
            },
        )
        when {
            loadError != null -> PiInfoNote(loadError.orEmpty(), tone = PiTheme.palette.error)
            d == null -> PiInfoNote(tr("读取中…", "Loading…"))
            else -> Column(
                Modifier.weight(1f).padding(horizontal = PiSettingsMetrics.pageHorizontal),
                verticalArrangement = Arrangement.spacedBy(PiSpacing.inline),
            ) {
                TagRow(
                    listOfNotNull(
                        d.scope.label.ifEmpty { null },
                        if (d.writable) tr("可编辑", "Editable") else tr("只读", "Read-only"),
                        if (dirty) tr("未保存", "Unsaved") else null,
                    ),
                )
                Text(d.path, style = PiTheme.text.monoSmall, color = PiTheme.palette.dim)
                if (!d.writable) d.reason?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                check?.blockers?.forEach { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
                check?.notices?.forEach { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.warning) }
                failure?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
                message?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.success) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    PiEditorField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        readOnly = !d.writable,
                        minHeight = PiSettingsMetrics.searchFieldHeight * 6,
                        contentPadding = app.pi.ui.settings.PiFieldPaddingBlock,
                        textStyle = PiTheme.text.monoSmall,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = PiSpacing.gutter),
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (d.writable && d.size >= 0L) PiSettingsChip(text = deleteLabel(d), onClick = { askDelete = true })
                    }
                }
            }
        }
    }

    if (askDiscard) {
        PiSettingsDialog(
            onDismissRequest = { askDiscard = false },
            title = tr("放弃未保存的改动？", "Discard unsaved changes?"),
            body = tr("这份文件有没保存的修改，现在返回会丢掉它们。", "This file has unsaved changes that will be lost if you leave now."),
            confirmationLabel = tr("放弃", "Discard"),
            onConfirm = { askDiscard = false; onClose() },
            confirmationTone = PiTheme.palette.error,
            dismissalLabel = tr("继续编辑", "Keep Editing"),
            onDismissButton = { askDiscard = false },
        )
    }
    if (askDelete && d != null) {
        PiSettingsDialog(
            onDismissRequest = { askDelete = false },
            title = deleteLabel(d) + tr("？", "?"),
            sub = d.path,
            body = if (d.kind == PiResourceFile.Kind.Skill && d.rel.substringAfterLast('/') == "SKILL.md") {
                tr("会删掉整个技能目录，包括里面的 references/、scripts/ 等所有文件。不可撤销。", "This deletes the entire skill directory, including references/, scripts/, and all other files inside. This cannot be undone.")
            } else {
                tr("文件会被删除，不可撤销。", "This file will be permanently deleted. This cannot be undone.")
            },
            confirmationLabel = tr("删除", "Delete"),
            onConfirm = {
                askDelete = false
                scope.launch {
                    when (val r = model.deleteFile(d.path)) {
                        is Parsed.Ok -> onDeleted()
                        is Parsed.Error -> failure = r.message
                    }
                }
            },
            confirmationTone = PiTheme.palette.error,
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { askDelete = false },
        )
    }
}

private fun deleteLabel(d: PiResourceFile.Doc): String =
    if (d.kind == PiResourceFile.Kind.Skill && d.rel.substringAfterLast('/') == "SKILL.md") tr("删除整个技能", "Delete Entire Skill") else tr("删除这个文件", "Delete File")

// ------------------------------------------------------------------ 新建

/**
 * 「新建」一个资源：选类型与层级，给名字（上下文文件不用名字，选哪一个文件）。
 * 名字规则是 SDK 的（[PiResourceFile.nameError]）；项目层在项目未被信任时不给选。
 */
@Composable
internal fun NewResourceDialog(
    model: ResourcesModel,
    initial: PiResourceFile.Creatable?,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initial ?: PiResourceFile.Creatable.Skill) }
    var project by remember { mutableStateOf(model.effectiveLayer == app.pi.packages.PiResourceCatalog.Layer.Project && model.projectUsable) }
    var name by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val nameError = if (name.isEmpty()) null else PiResourceFile.nameError(type, name)

    PiSettingsDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = tr("新建", "New"),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                if (initial == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                        PiResourceFile.Creatable.entries.forEach { c ->
                            PiSettingsChip(text = c.label, active = type == c, onClick = { type = c; failure = null })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    PiSettingsChip(text = tr("全局", "Global"), active = !project, onClick = { project = false })
                    PiSettingsChip(text = tr("本项目", "Project"), active = project, enabled = model.projectUsable, onClick = { project = true })
                }
                if (!model.projectUsable) Text(tr("项目还没被信任，只能建全局的。", "Project is not trusted yet; only global resources can be created."), style = PiTheme.text.meta, color = PiTheme.palette.muted)
                PiEditorField(value = name, onValueChange = { name = it; failure = null }, placeholder = type.nameHint)
                (nameError ?: failure)?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            }
        },
        confirmationLabel = if (busy) tr("创建中…", "Creating…") else tr("创建", "Create"),
        confirmationEnabled = !busy && name.isNotEmpty() && nameError == null,
        onConfirm = {
            busy = true
            scope.launch {
                when (val r = model.createFile(type.kind, project, name)) {
                    is Parsed.Ok -> onCreated(r.value)
                    is Parsed.Error -> { failure = r.message; busy = false }
                }
            }
        },
        dismissalLabel = tr("取消", "Cancel"),
        onDismissButton = { if (!busy) onDismiss() },
    )
}

/** 新建上下文 / 系统提示文件：三个固定名字，层级二选一。 */
@Composable
internal fun NewContextDialog(model: ResourcesModel, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var project by remember { mutableStateOf(false) }
    PiSettingsDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = tr("新建上下文文件", "New Context File"),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.inline)) {
                Row(horizontalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    PiSettingsChip(text = tr("全局", "Global"), active = !project, onClick = { project = false })
                    PiSettingsChip(text = tr("本项目", "Project"), active = project, enabled = model.projectUsable, onClick = { project = true })
                }
                PiResourceFile.CONTEXT_FILES.forEach { (file, hint) ->
                    val where = if (project) tr("项目", "Project") else tr("全局", "Global")
                    Column(Modifier.fillMaxWidth().padding(vertical = PiSpacing.small)) {
                        PiSettingsChip(
                            text = "$where $file",
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    when (val r = model.createFile(PiResourceFile.Kind.Context, project, file)) {
                                        is Parsed.Ok -> onCreated(r.value)
                                        is Parsed.Error -> { failure = r.message; busy = false }
                                    }
                                }
                            },
                        )
                        Text(hint, style = PiTheme.text.meta, color = PiTheme.palette.muted)
                    }
                }
                failure?.let { Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error) }
            }
        },
        dismissalLabel = tr("取消", "Cancel"),
        onDismissButton = { if (!busy) onDismiss() },
    )
}
