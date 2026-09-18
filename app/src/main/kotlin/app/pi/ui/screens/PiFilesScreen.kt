package app.pi.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pi.packages.PiConfigFiles
import app.pi.runtime.PiProjectConfig
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import app.pi.settings.PiFilesAccess
import app.pi.settings.PiFilesCheck
import app.pi.settings.PiFilesRoot
import app.pi.settings.checkPiFileWrite
import app.pi.settings.piFilesAccessFor
import app.pi.settings.piFilesBreadcrumb
import app.pi.settings.piFilesChild
import app.pi.settings.piFilesDirIsWritable
import app.pi.settings.piFilesParent
import app.pi.settings.piFilesReadOnlyReason
import app.pi.settings.piFilesRoots
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.settings.PiSettingsBadge
import app.pi.ui.settings.PiSettingsCard
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsHairline
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSectionHeader
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 「Pi 文件」：pi 到底读哪些文件，哪些只给看、哪些能改。
 *
 * ## 这一屏解决的问题
 *
 * 在此之前，`~/.pi/agent` 的**根文件一个都看不到**（`settings.json`、`models.json`、
 * `AGENTS.md`、`SYSTEM.md`、主题……），而 `<workspace>/.pi/settings.json` 反而能通过
 * 工作区文件树当普通文本**整份覆盖**改 —— 最该被保护的那份文件最容易被整份写坏
 * （`docs/settings-audit-pi-gap.md` §6.2 的最后一行）。这一屏把文件面收成两个根、一个
 * 白名单、三档：
 *
 *  - **可改**：pi 会读、而 pi 自己从不重写的文档 —— `settings.json`、`models.json`、
 *    `AGENTS.md` / `SYSTEM.md` / `APPEND_SYSTEM.md`，以及 `themes/`、`skills/`、
 *    `prompts/`、`extensions/` 里的文件。判定是纯函数
 *    （`app.pi.settings.piFilesAccessFor`，默认**拒绝**）。
 *  - **只读**：pi 自己会重写的（`auth.json`、`models-store.json`、`sessions/`）、有别的
 *    权威界面的（`trust.json`）、或别的程序会覆盖的（`npm/`、`bin/`）。每一类都给一句
 *    为什么（`piFilesReadOnlyReason`）。
 *  - **`*.lock`**：pi 的锁目录（`proper-lockfile`），只读，且不作为文件打开。
 *
 * ## 为什么查看复用 `WorkspaceViewer`，而编辑不复用
 *
 * 查看走 `WorkspaceViewer`（它已经能做文本 / 二进制 / 超大文件预览 / HTML / 高亮），
 * 但它的**编辑态**不能复用：它的保存路径硬编码成 `WorkspaceFiles.writeText`（整份覆盖、
 * 非原子、无锁），而这一屏的每一次写都必须走 `PiConfigFiles.withLock` + `write`
 * （pi 的 `proper-lockfile` 语义 + 临时文件 rename）。改 `WorkspaceViewer` 不在本批范围，
 * 所以这里传 `editable = false`，编辑入口放在查看器右上角的 ⋮（`onMore`）。
 *
 * ## 写盘前的三道闸
 *
 * 1. **档位**：只读文件根本不给编辑入口（⋮ 只说明原因）。
 * 2. **内容**：`checkPiFileWrite` 纯函数拦住「写下去 pi 会抛异常」的形状 —— 主要是
 *    `httpIdleTimeoutMs`/`websocketConnectTimeoutMs`/`compaction.*Tokens` 的 `null`
 *    与非非负整数（`core/settings-manager.ts:188-197`、`:860-877`）。有 blocker 时保存按钮
 *    不可用，原因显示在编辑器里；保存时**再查一次**（界面上那份是异步算的，可能还没回来）。
 * 3. **并发**：打开编辑器时记下 `(size, mtime)`，保存前再比一次；不一致就拒绝写，提示返回
 *    重开。pi 自己、终端里的命令、AI 的工具调用都可能在这中间改过同一个文件。
 *
 * 写完之后不做别的：`PiSettingsStack` 已经挂着 `PiDirectoryWatch`（inotify + ON_RESUME
 * 指纹），它会自己发现文件变了，刷新设置页的缓存与行值。
 *
 * 未保存就返回时先问一次（与 `WorkspaceViewer` 同一种做法）：整份文件的编辑器里丢掉草稿
 * 是不可撤销的。
 */
@Composable
internal fun PiFilesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 两个根都来自这个仓库唯一的转录点：`PiPaths.agentDir`（宿主那份就是 pi 读的那份）与
    // `PiProjectConfig.root(workspace)`（`<cwd>/.pi`）。这里不拼 `~/.pi/agent` 这种字面量。
    val paths = remember(context) {
        PiPaths(filesDir = context.filesDir, nativeLibDir = File(context.applicationInfo.nativeLibraryDir))
    }
    val workspace = remember(context) { PtyLauncher.workspaceHost(context) }
    val roots = remember(paths, workspace) { piFilesRoots(paths.agentDir, PiProjectConfig.root(workspace)) }

    var rootIndex by remember { mutableIntStateOf(0) }
    var relative by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }

    var entries by remember { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }

    var viewer by remember { mutableStateOf<WorkspaceViewerTarget?>(null) }
    var viewerRelative by remember { mutableStateOf("") }
    var viewerMenu by remember { mutableStateOf(false) }

    var editing by remember { mutableStateOf<PiFilesEdit?>(null) }
    var draft by remember { mutableStateOf("") }
    var editCheck by remember { mutableStateOf(PiFilesCheck.Ok) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val root: PiFilesRoot = roots.getOrNull(rootIndex) ?: roots.first()
    val currentDir = remember(root, relative) {
        if (relative.isEmpty()) root.file else File(root.file, relative)
    }

    LaunchedEffect(rootIndex, relative, refreshTick) {
        entries = null
        listError = null
        val result = withContext(Dispatchers.IO) { WorkspaceFiles.list(currentDir) }
        result.fold(
            onSuccess = { entries = it },
            onFailure = { listError = it.message ?: it::class.java.simpleName },
        )
    }

    // 校验在 Default 上跑：一份大 JSON 的解析不该占主线程，也不该每敲一个字就做一次。
    // 它只决定按钮的可用态与提示；真正拦住写入的是保存路径里那次同步校验。
    LaunchedEffect(editing, draft) {
        val state = editing
        editCheck = if (state == null) {
            PiFilesCheck.Ok
        } else {
            withContext(Dispatchers.Default) { checkPiFileWrite(state.relativePath, draft) }
        }
    }

    /** 打开一个文件（只读查看）。[entryRelative] 是相对当前根的路径。 */
    fun openViewer(file: File, entryRelative: String) {
        viewerRelative = entryRelative
        viewer = WorkspaceViewerTarget(
            relativePath = piFilesBreadcrumb(root.label, entryRelative),
            file = file,
            kind = WorkspaceFiles.kindOf(file),
            sizeBytes = if (file.isFile) file.length() else 0L,
            modifiedAt = file.lastModified(),
            // 永远 false：查看器的编辑路径写不到 pi 的锁与原子写上，编辑由本屏自己做
            // （见文件头）。它的 ⋮ 仍然可用，本屏在那里给出「编辑」。
            editable = false,
            startInEdit = false,
        )
    }

    fun beginEditing(file: File, entryRelative: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
            if (text == null) {
                notice = "读不到这个文件，无法编辑。"
                return@launch
            }
            draft = text
            editCheck = PiFilesCheck.Ok
            saveError = null
            viewerMenu = false
            editing = PiFilesEdit(
                target = file,
                relativePath = entryRelative,
                original = text,
                stamp = stampOf(file),
            )
        }
    }

    fun save() {
        val state = editing ?: return
        if (saving) return
        saving = true
        saveError = null
        scope.launch {
            // 全部在 IO 上：`checkPiFileWrite` 会解析整份 JSON，`writeThroughPiLock` 里的锁
            // 是**同步等待**（10 次 × 20ms），两者都不能在主线程上做。
            val outcome = withContext(Dispatchers.IO) {
                val check = checkPiFileWrite(state.relativePath, draft)
                when {
                    !check.ok -> check.blockers.joinToString("\n")
                    stampOf(state.target) != state.stamp ->
                        "文件在你编辑期间被改过（大小或时间变了）。请返回重开，避免把别人的改动覆盖掉。"
                    else -> writeThroughPiLock(state.target, draft)
                }
            }
            saving = false
            if (outcome == null) {
                notice = "已保存 · ${state.target.name}"
                editing = null
                refreshTick++
            } else {
                saveError = outcome
            }
        }
    }

    fun leaveEditor() {
        val state = editing ?: return
        if (draft != state.original) askDiscard = true else editing = null
    }

    // 返回键：先退到上一级目录，再交给调用方（`SettingsHome` 自己托管这一屏）。编辑器与查看器
    // 都是 `Dialog`，它们的返回由各自窗口先吃掉，所以这里只需要管目录层级。
    BackHandler(enabled = true) {
        if (relative.isNotEmpty()) relative = piFilesParent(relative) else onBack()
    }

    Column(Modifier.fillMaxSize()) {
        PiTopBar(title = "Pi 文件", onBack = onBack)

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        ) {
            Row(
                modifier = Modifier.padding(top = PiSettingsMetrics.cardPadding),
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                roots.forEachIndexed { index, candidate ->
                    PiSettingsChip(
                        text = candidate.label,
                        active = index == rootIndex,
                        onClick = {
                            rootIndex = index
                            relative = ""
                        },
                    )
                }
            }
            Text(
                "pi 读的就是这两个目录里的文件。可改的只有 pi 会读、而 pi 自己不会重写的那些文档；" +
                    "其余只给看，原因写在每一个只读文件上。",
                modifier = Modifier.padding(top = PiSpacing.inline),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val note = notice
            if (note != null) {
                Text(
                    note,
                    modifier = Modifier.padding(top = PiSpacing.inline),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        PiSettingsSectionHeader(
            label = piFilesBreadcrumb(root.label, relative),
            count = entries?.let { "${it.size} 项" },
        )

        if (relative.isNotEmpty()) {
            PiSettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { relative = piFilesParent(relative) }
                        .padding(
                            horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                            vertical = PiSettingsMetrics.rowPaddingVertical,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                        tint = PiTheme.palette.muted,
                    )
                    Text(
                        "上一级",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(PiSpacing.inline))
        }

        val error = listError
        val listing = entries
        when {
            error != null -> PiFilesMessage("读不到这个目录：$error", PiTheme.palette.error)

            listing == null -> PiFilesMessage("正在读取…", PiTheme.palette.muted)

            listing.isEmpty() -> PiFilesMessage("这个目录是空的。", PiTheme.palette.muted)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                item {
                    PiSettingsCard {
                        listing.forEachIndexed { index, entry ->
                            if (index > 0) PiSettingsHairline()
                            val entryRelative = piFilesChild(relative, entry.name)
                            PiFilesRow(
                                entry = entry,
                                entryRelative = entryRelative,
                                onOpen = {
                                    if (entry.isDirectory) {
                                        relative = entryRelative
                                    } else {
                                        openViewer(File(currentDir, entry.name), entryRelative)
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    Text(
                        "写盘走 pi 自己的锁（同一个文件旁边的 .lock 目录）与整份原子替换；" +
                            "pi 正在跑也不会丢更新。",
                        modifier = Modifier.padding(
                            start = PiSettingsMetrics.pageHorizontal,
                            end = PiSettingsMetrics.pageHorizontal,
                            top = PiSettingsMetrics.groupGap,
                            bottom = PiSettingsMetrics.groupGap,
                        ),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 查看器
    val shown = viewer
    if (shown != null) {
        WorkspaceViewer(
            target = shown,
            onClose = { viewer = null },
            onMessage = { notice = it },
            onSaved = { },
            onMore = { viewerMenu = true },
        )
    }

    if (viewerMenu && shown != null) {
        val access = piFilesAccessFor(viewerRelative)
        PiDialog(onDismissRequest = { viewerMenu = false }) {
            PiDialogTitle(title = shown.file.name)
            PiDialogBody(
                if (access == PiFilesAccess.Writable) {
                    "这个文件可以改。写入会取 pi 自己的锁（同一个文件旁边的 .lock 目录）并整份原子替换；" +
                        "保存前会先检查内容，pi 会因为某个值抛异常时不会写下去。"
                } else {
                    piFilesReadOnlyReason(viewerRelative)
                },
            )
            PiDialogActions {
                PiDialogAction(label = "关闭", onClick = { viewerMenu = false }, primary = false)
                if (access == PiFilesAccess.Writable) {
                    PiDialogAction(
                        label = "编辑",
                        onClick = { beginEditing(shown.file, viewerRelative) },
                        primary = true,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 未保存确认
    if (askDiscard) {
        PiDialog(onDismissRequest = { askDiscard = false }) {
            PiDialogTitle(title = "还没保存")
            PiDialogBody("这个文件改过但还没写盘。返回会丢掉这次的改动。")
            PiDialogActions {
                PiDialogAction(label = "继续编辑", onClick = { askDiscard = false }, primary = false)
                PiDialogAction(
                    label = "丢掉改动",
                    onClick = {
                        askDiscard = false
                        editing = null
                    },
                    primary = true,
                    tone = PiTheme.palette.error,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 编辑器
    val edit = editing
    if (edit != null) {
        PiFilesEditor(
            state = edit,
            draft = draft,
            check = editCheck,
            onDraftChange = {
                draft = it
                saveError = null
            },
            saving = saving,
            saveError = saveError,
            onSave = { save() },
            onDismiss = { leaveEditor() },
        )
    }
}

/** 正在编辑的那份文件，连同打开时的指纹与原文（保存前的并发判据、丢弃前的脏判据）。 */
private data class PiFilesEdit(
    val target: File,
    val relativePath: String,
    val original: String,
    /** `"<size>:<mtime>"`，打开时记下、保存前再比一次。 */
    val stamp: String,
)

/** `(size, mtime)` 指纹。与 `PiFileStamps` 同一种判据：只用来发现「变过」，不追求唯一。 */
private fun stampOf(file: File): String = "${file.length()}:${file.lastModified()}"

/**
 * 唯一的写盘路径：pi 的锁 + 原子替换。
 *
 * `PiConfigFiles.withLock` 实现的是 `proper-lockfile` 的语义（`<file>.lock` 目录、10 次 × 20ms、
 * 10 秒 stale），`PiConfigFiles.write` 是临时文件 + rename。这一屏**不**写第二套。
 *
 * `mode600 = false`：可写白名单里没有凭据文件（`auth.json` 是只读的），所以这一屏不需要收紧
 * 权限 —— 那件事仍然归 `PiAuthStorage`。
 *
 * @return null 表示成功，否则是给用户看的一句原因。
 */
private fun writeThroughPiLock(target: File, text: String): String? = runCatching {
    PiConfigFiles.withLock(target) {
        if (PiConfigFiles.write(target, text, mode600 = false)) {
            null
        } else {
            "写盘失败：${target.name}（原子替换没成功，磁盘上的内容没有被改）"
        }
    }
}.getOrElse { error ->
    "写盘失败：" + (error.message ?: error::class.java.simpleName)
}

@Composable
private fun PiFilesMessage(text: String, color: Color) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(PiSettingsMetrics.pageHorizontal),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun PiFilesRow(
    entry: WorkspaceEntry,
    entryRelative: String,
    onOpen: () -> Unit,
) {
    val writable = if (entry.isDirectory) {
        piFilesDirIsWritable(entryRelative)
    } else {
        piFilesAccessFor(entryRelative) == PiFilesAccess.Writable
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
            tint = PiTheme.palette.muted,
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = PiTheme.text.mono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.isDirectory) "${entry.childCount} 项" else metaOf(entry),
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PiSettingsBadge(
            label = if (writable) "可改" else "只读",
            tone = if (writable) PiTheme.palette.accent else PiTheme.palette.muted,
        )
    }
}

private fun metaOf(entry: WorkspaceEntry): String =
    WorkspaceFiles.formatSize(entry.sizeBytes) + " · " + WorkspaceFiles.formatTime(entry.modifiedAt)

/** 编辑器：整屏的多行框 + 保存前的拦阻说明（要改的可能是几千行的 JSON）。 */
@Composable
private fun PiFilesEditor(
    state: PiFilesEdit,
    draft: String,
    check: PiFilesCheck,
    onDraftChange: (String) -> Unit,
    saving: Boolean,
    saveError: String?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PiSpacing.topBarHeight)
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
                ) {
                    Text(
                        "返回",
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !saving, onClick = onDismiss)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.target.name,
                            style = PiTheme.text.mono,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.relativePath,
                            modifier = Modifier.padding(top = 1.dp),
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (saving) "… 保存中" else "保存",
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = check.ok && !saving, onClick = onSave)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (check.ok && !saving) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (check.ok && !saving) PiTheme.palette.accent else PiTheme.palette.muted,
                        maxLines = 1,
                    )
                }
                HorizontalDivider(
                    thickness = PiSettingsMetrics.hairline,
                    color = MaterialTheme.colorScheme.outline,
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                ) {
                    if (!check.ok) {
                        PiFilesEditorNote(
                            title = "还不能保存：写下去 pi 会出错",
                            body = check.blockers.joinToString("\n"),
                            tone = PiTheme.palette.error,
                        )
                    } else {
                        for (item in check.notices) {
                            PiFilesEditorNote(title = "提示", body = item, tone = PiTheme.palette.warning)
                        }
                    }
                    val failure = saveError
                    if (failure != null) {
                        PiFilesEditorNote(title = "没有写入", body = failure, tone = PiTheme.palette.error)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = PiSettingsMetrics.cardPadding)
                            .clip(RoundedCornerShape(PiSettingsMetrics.cardRadius))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                PiSettingsMetrics.hairline,
                                PiTheme.palette.borderAccent,
                                RoundedCornerShape(PiSettingsMetrics.cardRadius),
                            )
                            .padding(PiSettingsMetrics.cardPadding),
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving,
                            textStyle = PiTheme.text.code.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(PiTheme.palette.accent),
                        )
                    }
                    Text(
                        "这是整份文件的内容：保存会覆盖磁盘上的那一份。pi 正在跑也不怕（写盘取 pi 自己的锁），" +
                            "但如果在编辑期间有别的东西改过这个文件，保存会被拒绝。",
                        modifier = Modifier.padding(
                            top = PiSpacing.inline,
                            bottom = PiSettingsMetrics.groupGap,
                        ),
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun PiFilesEditorNote(title: String, body: String, tone: Color) {
    Text(
        title,
        modifier = Modifier.padding(top = PiSettingsMetrics.notePaddingVertical),
        style = MaterialTheme.typography.labelLarge,
        color = tone,
    )
    Text(
        body,
        modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
