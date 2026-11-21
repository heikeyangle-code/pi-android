package app.pi.ui.resources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.packages.PiLoadedResources
import app.pi.packages.PiResourceCatalog.Parsed
import app.pi.packages.PiResourceFile
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiAutoFocus
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
 * 资源文件全屏编辑器（技能 / 提示模板 / 主题 / 扩展 / 系统提示词 / 上下文文件统一入口）：
 *  - 未创建的官方系统提示词与上下文文件在内存中以纯空白打开（绝不塞入硬编码预设废话），
 *    仅展示浅色占位提示，用户输入内容并保存后才真正落盘并同步热重载生效；
 *  - 整块编辑区支持任意位置点击即时聚焦并弹出输入法，新建空白文件打开即自动拉起键盘；
 *  - 保存与删除均真正等待宿主 `session.reload` 与生效状态刷新完成，回到列表后顶部状态卡与每行徽标 100% 即时同步。
 */
@Composable
internal fun ResourceFileEditor(
    path: String,
    model: ResourcesModel,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
    /** 删除并重载完成后调用（技能是整个目录）。 */
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    val editorFocus = remember(path) { FocusRequester() }
    val interaction = remember(path) { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    var doc by remember(path) { mutableStateOf<PiResourceFile.Doc?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var draft by remember(path) { mutableStateOf("") }
    var saving by remember(path) { mutableStateOf(false) }
    var deleting by remember(path) { mutableStateOf(false) }
    var message by remember(path) { mutableStateOf<String?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var askDiscard by remember(path) { mutableStateOf(false) }
    var askDelete by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        when (val r = model.readFile(path)) {
            is Parsed.Ok -> {
                doc = r.value
                draft = r.value.text
            }
            is Parsed.Error -> {
                val fileName = path.substringAfterLast('/')
                val virtualKind = when (fileName) {
                    "SYSTEM.md" -> PiResourceFile.Kind.System
                    "APPEND_SYSTEM.md" -> PiResourceFile.Kind.AppendSystem
                    "AGENTS.md", "CLAUDE.md", "AGENTS.override.md" -> PiResourceFile.Kind.Context
                    else -> null
                }
                if (virtualKind != null) {
                    val agentDir = model.loaded.agentDir?.trimEnd('/') ?: "/root/.pi/agent"
                    val isUserScope = path.startsWith("$agentDir/") || path.contains("/.pi/agent/")
                    val scopeKind = if (isUserScope) PiLoadedResources.Scope.User else PiLoadedResources.Scope.Project
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
    val isNewFile = d != null && d.size < 0L
    val dirty = d != null && draft != d.text
    val check = remember(d?.kind, d?.rel, draft) { d?.let { PiResourceFile.check(it.kind, it.rel, draft) } }
    val canSave = d != null && d.writable && dirty && !saving && !deleting && check?.ok != false

    fun leave() {
        if (saving || deleting) return
        if (dirty) {
            askDiscard = true
        } else {
            keyboard?.hide()
            onClose()
        }
    }
    BackHandler(enabled = true) { leave() }

    fun save() {
        val cur = d ?: return
        saving = true
        failure = null
        message = null
        scope.launch {
            val stamp = if (cur.size >= 0L) PiResourceFile.Stamp(cur.size, cur.mtimeMs) else null
            when (val r = model.writeFile(cur.path, draft, stamp)) {
                is Parsed.Ok -> {
                    doc = cur.copy(text = draft, size = r.value.size, mtimeMs = r.value.mtimeMs)
                    val reloadErr = model.reloadAndRefresh()
                    if (reloadErr == null) {
                        message = tr("已保存并立即生效。", "Saved and active in current session.")
                    } else {
                        failure = tr("文件已保存，但热重载失败：$reloadErr", "File saved, but reload failed: $reloadErr")
                    }
                }
                is Parsed.Error -> failure = r.message
            }
            saving = false
        }
    }

    val editorScroll = rememberScrollState()
    // PiRoot 已经在非对话目的地挂载了 imePadding()，这里不重复加，避免软键盘弹起时双倍抬升。
    Column(Modifier.fillMaxSize()) {
        PiTopBar(
            title = d?.rel?.substringAfterLast('/') ?: tr("编辑文件", "Edit File"),
            subtitle = d?.let { listOfNotNull(it.scope.label.ifEmpty { null }, it.kind.label).joinToString(" · ") },
            onBack = { leave() },
            scrolled = editorScroll.canScrollBackward,
            actions = {
                if (d?.writable == true) {
                    PrimaryAction(
                        text = if (saving) tr("保存中…", "Saving…") else tr("保存", "Save"),
                        enabled = canSave,
                        onClick = { save() },
                    )
                }
            },
        )
        when {
            loadError != null -> PiInfoNote(loadError.orEmpty(), tone = palette.error)
            d == null -> PiInfoNote(tr("读取中…", "Loading…"))
            else -> {
                if (d.writable && isNewFile) {
                    PiAutoFocus(editorFocus)
                }
                val editorShape = RoundedCornerShape(12.dp)
                val lineCount = remember(draft) { if (draft.isEmpty()) 0 else draft.count { it == '\n' } + 1 }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                        .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(PiSpacing.small),
                ) {
                    TagRow(
                        listOfNotNull(
                            d.scope.label.ifEmpty { null },
                            if (isNewFile) tr("新建草稿（保存后创建）", "New draft (created on save)")
                            else if (d.writable) tr("可编辑", "Editable") else tr("只读", "Read-only"),
                            if (dirty) tr("未保存", "Unsaved") else null,
                        ),
                    )
                    Text(d.path, style = PiTheme.text.monoSmall, color = palette.dim)
                    if (!d.writable) d.reason?.let { Text(it, style = PiTheme.text.meta, color = palette.warning) }
                    check?.blockers?.forEach { Text(it, style = PiTheme.text.meta, color = palette.error) }
                    check?.notices?.forEach { Text(it, style = PiTheme.text.meta, color = palette.warning) }
                    failure?.let { Text(it, style = PiTheme.text.meta, color = palette.error) }
                    message?.let { Text(it, style = PiTheme.text.meta, color = palette.success) }

                    // 全高可点编辑面板：任意空白处轻点都立即聚焦并拉起软键盘，内部平滑滚动。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(editorShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = PiSettingsMetrics.hairline,
                                color = if (focused) palette.borderAccent else MaterialTheme.colorScheme.outlineVariant,
                                shape = editorShape,
                            )
                            .clickable(
                                enabled = d.writable,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                runCatching { editorFocus.requestFocus() }
                                keyboard?.show()
                            }
                            .verticalScroll(editorScroll)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = editorPlaceholderFor(d),
                                style = PiTheme.text.monoSmall,
                                color = palette.dim,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                message = null
                                failure = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 260.dp)
                                .focusRequester(editorFocus),
                            enabled = !saving && !deleting,
                            readOnly = !d.writable,
                            singleLine = false,
                            textStyle = PiTheme.text.monoSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(palette.accent),
                            interactionSource = interaction,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tr("${draft.length} 字 · $lineCount 行", "${draft.length} chars · $lineCount lines"),
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                        if (d.writable && d.size >= 0L) {
                            PiSettingsChip(
                                text = if (deleting) tr("删除中…", "Deleting…") else deleteLabel(d),
                                enabled = !saving && !deleting,
                                onClick = { askDelete = true },
                            )
                        }
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
            onConfirm = {
                askDiscard = false
                keyboard?.hide()
                onClose()
            },
            confirmationTone = palette.error,
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
                tr("删除后会自动重载当前会话，立即恢复默认行为。不可撤销。", "The file will be permanently deleted and the active session will reload immediately.")
            },
            confirmationLabel = tr("删除", "Delete"),
            onConfirm = {
                askDelete = false
                deleting = true
                failure = null
                scope.launch {
                    when (val r = model.deleteFile(d.path)) {
                        is Parsed.Ok -> {
                            model.reloadAndRefresh()
                            keyboard?.hide()
                            onDeleted()
                        }
                        is Parsed.Error -> {
                            failure = r.message
                            deleting = false
                        }
                    }
                }
            },
            confirmationTone = palette.error,
            dismissalLabel = tr("取消", "Cancel"),
            onDismissButton = { askDelete = false },
        )
    }
}

private fun editorPlaceholderFor(d: PiResourceFile.Doc): String = when (d.kind) {
    PiResourceFile.Kind.AppendSystem -> tr(
        "在此输入要追加到 Pi 默认系统提示词末尾的指令…\n（不会破坏内置工具规则，保存后立即生效）",
        "Write instructions to append after Pi's default system prompt…\n(Preserves built-in tool rules; active immediately on save)",
    )
    PiResourceFile.Kind.System -> tr(
        "在此输入自定义完整系统提示词…\n（注意：将整份替换 Pi 默认提示词与工具规则；删除文件可恢复默认）",
        "Write a custom full system prompt…\n(Note: replaces Pi's entire default prompt and tool rules; delete file to restore)",
    )
    PiResourceFile.Kind.Context -> tr(
        "在此输入项目说明、架构约定、构建与测试命令…\n（保存后自动重载并注入对话上下文）",
        "Write project overview, conventions, build and test commands…\n(Reloads and injects into conversation context on save)",
    )
    else -> tr("在此输入文件内容…", "Enter file content…")
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
