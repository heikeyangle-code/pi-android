package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.i18n.tr
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiSheetChromeMetrics
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import java.io.File
import java.util.concurrent.TimeUnit

// ================================================================ 浮层：切换工作区

/**
 * 切换工作区：这一屏的「其它底部面板」之一，用同一支自绘的 [WsSheet]。
 *
 * 头与页脚的归属照稿子（`workspace-final.html:1577-1608`）：副标题是那句「工作区就是
 * pi 的现场目录」，正文末尾那句「新建的工作区落在 App 私有目录里……」留在滚动区里，
 * 而「换工作区等于换一个现场」是**页脚**（`p.footer`），钉在面板底部不随内容滚。
 * 正文上限是稿子给这一处的 `maxBody={520}`（默认那档是 420）。
 *
 * ## 清单来自引擎，不是兄弟目录
 *
 * [entries] 是 `session.workspaceEntries()` 的读数（`WorkspaceStore.Entry`）：`displayName`
 * 是显示名（没改过就是目录名）、`isCurrent` 是当前那一格。稿子的行副行是
 * 「N 个文件 · 最后打开 X」—— 那两个数**这一版没有**：目录的 mtime 只说明里面有东西被
 * 加/删过（改内容不动它），而完整数一遍要遍历整棵树，引擎只在**删除前的统计**
 * （`previewWorkspaceDelete`）里提供 —— 那也正是需要那个数的地方。副行改印工作区
 * 相对 files 目录的路径（`pi/workspaces/workspace-1`），与副标题那句「它就在 App
 * 私有目录里」对得上。这是与稿子的一处**有意偏离**。
 *
 * ## 行尾 ⋮
 *
 * 稿子的每一行只有「当前」徽标与一次整行点击。重命名与删除是引擎侧（`renameWorkspace`
 * / `previewWorkspaceDelete`）新开的两条路，这一版给每行加了一颗 ⋮（[WsMoreButton]，
 * 与 ③④ 的文件行同一个构件、同一支菜单面板），点击整行仍然是切换。这是第二处**有意
 * 偏离**：行里并排两个按钮比一颗 ⋮ 更挤，也不像这一屏别处的做法。
 */
@Composable
internal fun WorkspaceRootSheet(
    entries: List<WorkspaceStore.Entry>,
    /** guest 路径 → 信任结论（`true` 信任 / `false` 不信任 / `null` 待表态）；没有这一项 = 不知道，不画徽标。 */
    trust: Map<String, Boolean?>,
    onClose: () -> Unit,
    onPick: (WorkspaceStore.Entry) -> Unit,
    onNew: () -> Unit,
    onPickExternal: () -> Unit,
    onMenu: (WorkspaceStore.Entry) -> Unit,
) {
    WsSheet(
        title = tr("切换项目", "Switch Project"),
        onClose = onClose,
        subtitle = tr("项目就是一个目录：App 私有目录和设备目录里的都可以。", "A project is just a directory — either app-private or on your device."),
        footer = tr("换项目会新建一个 pi 会话，当前会话不会被删除。项目信任按目录记在 pi 的信任库里。", "Switching starts a new pi session without deleting the current one. Project trust is tracked per directory in pi's trust store."),
        maxBodyHeight = ROOT_SHEET_BODY_MAX,
    ) {
        var projectQuery by remember { mutableStateOf("") }
        val filteredEntries = remember(entries, projectQuery) {
            val q = projectQuery.trim()
            if (q.isEmpty()) entries
            else entries.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.name.contains(q, ignoreCase = true) ||
                    it.guestPath.contains(q, ignoreCase = true)
            }
        }
        WsCard {
            WsRow(
                title = tr("新建项目", "New Project"),
                strong = true,
                titleColor = PiTheme.palette.accent,
                lead = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                        tint = PiTheme.palette.accent,
                    )
                },
                meta = tr("在应用沙盒或手机存储中创建新项目目录。", "Create a new project folder in sandbox or device storage."),
                onClick = onNew,
            )
            WsHairline(startInset = WsIconHairlineStart)
            WsRow(
                title = tr("打开设备文件夹", "Open Device Folder"),
                strong = true,
                titleColor = PiTheme.palette.accent,
                lead = { FolderGlyph() },
                meta = tr("直接打开或新建手机存储里的目录，不复制文件。", "Open or create a folder on device storage directly."),
                onClick = onPickExternal,
            )
        }
        if (entries.size >= 5) {
            WorkspaceFilterField(
                value = projectQuery,
                onChange = { projectQuery = it },
            )
        }

        // 一行项目：当前与其它共用同一份画法。
        val entryRow: @Composable (WorkspaceStore.Entry) -> Unit = { item ->
            val pathMeta = app.pi.runtime.GuestWorkspacePath.shortenForDisplay(item.guestPath)
            WsRow(
                title = item.displayName,
                mono = false,
                strong = true,
                lead = { FolderGlyph() },
                leadBackground = if (item.isCurrent) PiTheme.palette.accent.copy(alpha = 0.14f) else null,
                badge = if (item.isCurrent || item.external || !item.available || (item.guestPath in trust && trust[item.guestPath] != true)) {
                    {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
                        ) {
                            if (item.external) {
                                WsBadge(text = tr("设备", "Device"), tone = StateTone.Muted, dot = false)
                            }
                            if (!item.available) {
                                WsBadge(text = tr("不可读", "Unreadable"), tone = StateTone.Warning)
                            }
                            if (item.guestPath in trust) {
                                when (trust[item.guestPath]) {
                                    false -> WsBadge(text = tr("未信任", "Untrusted"), tone = StateTone.Warning)
                                    null -> WsBadge(text = tr("待表态", "Pending"), tone = StateTone.Warning)
                                    true -> Unit
                                }
                            }
                            if (item.isCurrent) {
                                WsBadge(text = tr("当前", "Current"), tone = StateTone.Accent, glyph = "●")
                            }
                        }
                    }
                } else {
                    null
                },
                meta = pathMeta,
                metaMono = true,
                trail = { WsMoreButton(onClick = { onMenu(item) }) },
                onClick = { onPick(item) },
            )
        }
        // 「当前」与「其它」分成两段：当前那一个单独成卡、不再混在列表里靠颜色认。
        val current = remember(filteredEntries) { filteredEntries.firstOrNull { it.isCurrent } }
        val others = remember(filteredEntries) { filteredEntries.filter { !it.isCurrent } }
        if (current != null) {
            WsSectionHeader(label = tr("当前", "Current"))
            WsCard { entryRow(current) }
        }
        if (others.isNotEmpty()) {
            WsSectionHeader(label = tr("其它项目", "Other Projects"), count = "${others.size}")
            WsCard {
                others.forEachIndexed { index, item ->
                    if (index > 0) WsHairline(startInset = WsIconHairlineStart)
                    entryRow(item)
                }
            }
        }
        if (current == null && others.isEmpty() && projectQuery.isNotBlank()) {
            Text(
                tr("没有匹配「${projectQuery.trim()}」的项目。", "No projects matching \"${projectQuery.trim()}\"."),
                modifier = Modifier.padding(
                    horizontal = PiSettingsMetrics.pageHorizontal,
                    vertical = PiSettingsMetrics.groupGap,
                ),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
        Text(
            tr("设备目录不会被复制或移动；取消登记只是不再列在这里，文件留在设备上。设备目录需要「所有文件访问」授权。", "Device folders are never copied or moved; unregistering only removes them from this list while keeping files on your device. Device folders require \"All files access\"."),
            modifier = Modifier.padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.cardPaddingLoose,
                bottom = PiSettingsMetrics.groupGap,
            ),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
    }
}

/** 稿子给这一处的 `maxBody={520}`（`Sheet` 的默认那档是 420）。 */
private val ROOT_SHEET_BODY_MAX = 520.dp

/**
 * 工作区行尾 ⋮ 的菜单：重命名 / 删除。
 *
 * 与文件行的 [WorkspaceMenuSheet] 同一副壳、同一支 [MenuAction]：标题是显示名，副标题是
 * **等宽**的相对路径（这个对象是路径型的，与那支菜单的规矩一致），删除用错误色（它是唯一
 * 不可逆的那一个）。不在这里放「切换」—— 整行点击就是切换。
 *
 * 稿子的 rootsheet 每一行只有一个「当前」徽标和一次整行点击，没有行级动作；这两条是引擎
 * 接口带来的新入口（见 [WorkspaceRootSheet] 的 KDoc）。
 */
@Composable
internal fun WorkspaceRowMenuSheet(
    entry: WorkspaceStore.Entry,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    WsSheet(
        title = entry.displayName,
        onClose = onClose,
        subtitle = app.pi.runtime.GuestWorkspacePath.shortenForDisplay(entry.guestPath),
        subtitleMono = true,
    ) {
        MenuAction(
            tr("重命名显示名", "Rename"),
            onRename,
            icon = Icons.Filled.DriveFileRenameOutline,
        )
        WsHairline()
        MenuAction(
            if (entry.external) tr("从列表移除", "Remove from list") else tr("删除项目", "Delete project"),
            onDelete,
            tone = PiTheme.palette.error,
            icon = Icons.Filled.Delete,
        )
    }
}

// ================================================================ 浮层：文件菜单

/** 行尾 ⋮ 的目标。 */
internal data class WorkspaceMenuTarget(
    val path: String,
    val file: File,
    val isDirectory: Boolean,
    /**
     * 这个条目是哪种文件（`WorkspaceFiles.kindOf`）。
     *
     * 菜单要它只为一件事：给不给「编辑」（判据 `WorkspaceFiles.editableAsText`）。以前每个
     * 文件行都画一个「编辑」，点开一张图/一个 `.so` 却是空的编辑态 —— 保存下去就是拿 UTF-8
     * 文本覆盖那些字节。
     */
    val kind: WorkspaceEntryKind,
    /** 这一行是从 ③「本次会话改过」点进来的（保留给以后区分来路用）。 */
    val fromSession: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

internal fun menuTargetFor(path: String, file: File, fromSession: Boolean): WorkspaceMenuTarget =
    WorkspaceMenuTarget(
        path = path,
        file = file,
        isDirectory = file.isDirectory,
        // 与目录树/查看器同一个判据（`WorkspaceFiles.kindOf`），菜单据此决定给不给「编辑」。
        kind = if (file.isDirectory) WorkspaceEntryKind.Directory else WorkspaceFiles.kindOf(file),
        fromSession = fromSession,
        sizeBytes = if (file.isFile) file.length() else 0L,
        modifiedAt = file.lastModified(),
    )

/**
 * 行尾 ⋮ 的底部菜单。
 *
 * 稿子的动作表：③ 的行与 ④ 一样，④ 的文件是
 * 「打开 / 编辑 / 重命名 / 删除 / 复制路径」，④ 的目录是「进入 / 重命名 / 删除 / 复制路径」。
 * 删除用错误色（它是唯一不可逆的那一个）。
 *
 * ## 「用其他应用打开」与「导出到 Download」是这张菜单加的两条（稿子的动作表里没有）
 *
 * 放在这里而不是查看器的正文里，正因为这张 sheet 是**两个入口共用**的：文件列表行的 ⋮，
 * 以及查看器的 ⋮（查看器的 `onMore` 落回这一屏的 `menuFor`）。加一次，文本、代码、markdown、
 * HTML、空文件、超大、图片、二进制 —— **每一种**都拿得到这两条路；各支正文再各画一遍就会有
 * 七八个说法，第一个改的人只会改自己看见的那一个。
 *
 * 两条都只对**文件**存在（目录没有"用别的应用打开"这回事）。这两个 lambda 的实现都在
 * `WorkspaceViewer.kt` 里（`openWithOtherApp` / `exportWorkspaceFile`）—— 查看器二进制那一支
 * 用的是同一对函数，所以两条入口的行为不可能漂移。「导出到 Download」也是真的一条路：
 * 「用其他应用打开」在没人能接时说的那句「先导出到 Download」，指的就是同一张菜单里的这一条。
 *
 * 壳是 [WsSheet]：标题是 `mid(文件名, 22)`（稿子 `:1613`），副标题是**等宽**的完整路径
 * （稿子那儿是一个 `span.mono`），动作行自己带 `10px 14px` 的内边距（见 [MenuAction]），
 * 所以头下面不再垫一个空档 —— 头的 8 加上行的 10 就是面板头到第一条动作的那道缝。
 */
@Composable
internal fun WorkspaceMenuSheet(
    target: WorkspaceMenuTarget,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onOpenWithApp: () -> Unit,
    onShareFile: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onEnter: () -> Unit,
    onOpenAsProject: (() -> Unit)? = null,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onMention: () -> Unit,
) {
    val palette = PiTheme.palette
    WsSheet(
        title = WorkspaceFiles.middleEllipsis(target.file.name, MENU_TITLE_MAX_CHARS),
        onClose = onClose,
        subtitle = target.path,
        subtitleMono = true,
    ) {
        if (target.isDirectory) {
            MenuAction(tr("进入文件夹", "Open Folder"), onEnter, icon = Icons.Filled.FolderOpen)
            if (onOpenAsProject != null) {
                MenuAction(
                    tr("设为当前项目", "Set as Active Project"),
                    onOpenAsProject,
                    tone = palette.accent,
                    icon = Icons.Filled.SwapHoriz,
                )
            }
            MenuAction(tr("在对话里引用（@）", "Mention in chat (@)"), onMention, icon = Icons.Filled.AlternateEmail)
            WsHairline()
            MenuAction(tr("复制副本", "Duplicate"), onDuplicate, icon = Icons.Filled.FileCopy)
            MenuAction(tr("重命名", "Rename"), onRename, icon = Icons.Filled.DriveFileRenameOutline)
            MenuAction(tr("复制路径", "Copy path"), onCopyPath, icon = Icons.Filled.ContentCopy)
            WsHairline()
            MenuAction(tr("删除文件夹", "Delete folder"), onDelete, tone = palette.error, icon = Icons.Filled.Delete)
        } else {
            MenuAction(tr("打开查看", "Open"), onOpen, icon = Icons.Filled.Visibility)
            if (WorkspaceFiles.editableAsText(target.kind)) {
                MenuAction(tr("编辑", "Edit"), onEdit, icon = Icons.Filled.Edit)
            }
            MenuAction(tr("在对话里引用（@）", "Mention in chat (@)"), onMention, tone = palette.accent, icon = Icons.Filled.AlternateEmail)
            WsHairline()
            MenuAction(tr("分享文件", "Share file"), onShareFile, icon = Icons.Filled.Share)
            MenuAction(tr("用其他应用打开", "Open with another app"), onOpenWithApp, icon = Icons.AutoMirrored.Filled.OpenInNew)
            MenuAction(tr("导出到 Download", "Export to Downloads"), onExport, icon = Icons.Filled.Download)
            WsHairline()
            MenuAction(tr("复制副本", "Duplicate"), onDuplicate, icon = Icons.Filled.FileCopy)
            MenuAction(tr("重命名", "Rename"), onRename, icon = Icons.Filled.DriveFileRenameOutline)
            MenuAction(tr("复制路径", "Copy path"), onCopyPath, icon = Icons.Filled.ContentCopy)
            WsHairline()
            MenuAction(tr("删除文件", "Delete file"), onDelete, tone = palette.error, icon = Icons.Filled.Delete)
        }
    }
}

/** 稿子给这个标题的 `mid(…, 22)`。 */
private const val MENU_TITLE_MAX_CHARS = 22

@Composable
private fun MenuAction(
    label: String,
    onClick: () -> Unit,
    tone: androidx.compose.ui.graphics.Color? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val fg = tone ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = 11.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = tone ?: PiTheme.palette.muted,
            )
        }
        Text(
            label,
            style = PiType.rowTitle,
            color = fg,
        )
    }
}

// ================================================================ 浮层：新建

/**
 * 「新建」先问文件还是文件夹（稿子的 `dlg.kind === 'new'`，`:1623`）：同一支 [WsSheet]，
 * 标题「新建」、副标题「建在「…」里」、两条选择各自带 `10px 14px` 内边距（见 [NewChoice]）。
 */
@Composable
internal fun WorkspaceNewSheet(
    where: String,
    onClose: () -> Unit,
    onNewFile: () -> Unit,
    onNewDir: () -> Unit,
    onImportFiles: () -> Unit,
) {
    WsSheet(
        title = tr("新建与导入", "New & Import"),
        onClose = onClose,
        subtitle = tr("当前目录：$where", "Directory: $where"),
    ) {
        NewChoice(
            title = tr("新建文件", "New File"),
            supporting = tr("建完直接进编辑态，写什么由你。", "Opens directly in the editor after creation."),
            glyph = { EntryGlyph(WorkspaceEntryKind.Text) },
            onClick = onNewFile,
        )
        WsHairline()
        NewChoice(
            title = tr("新建文件夹", "New Folder"),
            supporting = tr("放在当前目录下。", "Created in the current directory."),
            glyph = { EntryGlyph(WorkspaceEntryKind.Directory) },
            onClick = onNewDir,
        )
        WsHairline()
        NewChoice(
            title = tr("从手机导入文件", "Import Files from Device"),
            supporting = tr("支持多选任意文档、图片或代码，自动防重名。", "Select one or more files from your phone; auto-renames on conflict."),
            glyph = {
                Icon(
                    Icons.Filled.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                    tint = PiTheme.palette.accent,
                )
            },
            onClick = onImportFiles,
        )
    }
}

@Composable
private fun NewChoice(
    title: String,
    supporting: String,
    glyph: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.searchIconGap),
    ) {
        glyph()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                // 与 `MenuAction` / 设置面的行标题同一个角色：`PiType.rowTitle`（15/23 Medium）。
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                supporting,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
        }
        Text("›", style = PiTheme.text.mono, color = PiTheme.palette.muted, maxLines = 1)
    }
}

// ================================================================ 浮层：名字输入

internal enum class WorkspaceInputKind { NewFile, NewDir, Rename, RenameWorkspace, NewWorkspace }

/** 新建文件 / 新建文件夹 / 重命名 / 重命名工作区共用的一支输入框（稿子就是这么复用的）。 */
internal data class WorkspaceInputDialog(
    val kind: WorkspaceInputKind,
    val title: String,
    /** 副行的「建在哪里」文案。 */
    val where: String,
    /** 相对工作区的父目录；只有新建文件 / 文件夹用它。 */
    val relativeParent: String?,
    /** 绝对父目录；文件重命名用它。 */
    val absoluteParent: File?,
    val initial: String,
    val dialogSub: String? = null,
    /** 要改显示名的那个工作区（目录名 = identity）；只有重命名工作区用它。 */
    val workspaceName: String? = null,
)

@Composable
internal fun WorkspaceNameDialog(
    dialog: WorkspaceInputDialog,
    onClose: () -> Unit,
    onPickDeviceLocation: () -> Unit = {},
    onSubmit: (String) -> Unit,
) {
    var field by remember(dialog) {
        val dot = dialog.initial.lastIndexOf('.')
        val end = if (dialog.kind == WorkspaceInputKind.Rename && dot > 0) dot else dialog.initial.length
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                dialog.initial,
                if (dialog.kind == WorkspaceInputKind.RenameWorkspace || dialog.kind == WorkspaceInputKind.Rename) {
                    androidx.compose.ui.text.TextRange(0, end)
                } else {
                    androidx.compose.ui.text.TextRange(dialog.initial.length)
                },
            ),
        )
    }
    var newInDevice by remember(dialog) { mutableStateOf(false) }
    val name = field.text
    val focus = remember(dialog) { androidx.compose.ui.focus.FocusRequester() }
    if (!(dialog.kind == WorkspaceInputKind.NewWorkspace && newInDevice)) {
        PiAutoFocus(focus)
    }
    PiDialog(onDismissRequest = onClose) {
        PiDialogTitle(title = dialog.title)
        if (dialog.kind == WorkspaceInputKind.NewWorkspace) {
            val palette = PiTheme.palette
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    false to tr("应用沙盒（高速）", "App Sandbox (Fast)"),
                    true to tr("设备存储（自选位置）", "Device Storage"),
                ).forEach { (isDevice, label) ->
                    val selected = newInDevice == isDevice
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) palette.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { newInDevice = isDevice }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = PiTheme.text.meta.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                            color = if (selected) palette.accent else palette.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            PiDialogBody(dialog.dialogSub ?: dialog.where, mono = true)
        }
        if (dialog.kind == WorkspaceInputKind.NewWorkspace && newInDevice) {
            Text(
                text = tr(
                    "进入手机存储浏览器后，可导航到任意位置并点击「新建文件夹」创建项目，或直接将已有文件夹设为项目。",
                    "Browse to any location on your device, tap \"New Folder\" to create a project there, or use an existing folder.",
                ),
                modifier = Modifier.padding(top = 12.dp),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
            )
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = onClose)
                PiDialogAction(
                    label = tr("浏览设备目录", "Browse Device"),
                    primary = true,
                    onClick = onPickDeviceLocation,
                )
            }
        } else {
        val trimmed = name.trim()
        val problem = when (dialog.kind) {
            WorkspaceInputKind.RenameWorkspace -> if (trimmed.isEmpty()) tr("名字不能为空。", "Name cannot be empty.") else null
            WorkspaceInputKind.NewWorkspace ->
                if (trimmed.isEmpty()) null
                else WorkspaceChoice.nameProblem(trimmed)
                    ?: if (java.io.File(WorkspaceStore.root(LocalContext.current), trimmed).exists()) tr("已经有叫「$trimmed」的项目了。", "A project named \"$trimmed\" already exists.") else null
            else -> WorkspaceFiles.nameProblem(name) ?: run {
                val parent = dialog.absoluteParent
                if (parent != null && trimmed.isNotEmpty() &&
                    !(dialog.kind == WorkspaceInputKind.Rename && trimmed == dialog.initial) &&
                    java.io.File(parent, trimmed).exists()
                ) {
                    tr("这里已经有叫「$trimmed」的文件或文件夹了。", "A file or folder named \"$trimmed\" already exists here.")
                } else {
                    null
                }
            }
        }
        val canSubmit = trimmed.isNotEmpty() && problem == null
        val showProblem = problem != null && name.isNotEmpty()
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = PiSettingsMetrics.cardPadding)
                .height(NAME_FIELD_HEIGHT)
                .clip(RoundedCornerShape(NAME_FIELD_RADIUS))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    PiSettingsMetrics.hairline,
                    if (showProblem) PiTheme.palette.error else PiTheme.palette.borderAccent,
                    RoundedCornerShape(NAME_FIELD_RADIUS),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (canSubmit) onSubmit(trimmed) },
                ),
                textStyle = PiTheme.text.mono.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PiTheme.palette.accent),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = NAME_FIELD_PADDING),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                when (dialog.kind) {
                                    WorkspaceInputKind.NewFile -> tr("文件名，例如 notes.md", "File name, e.g. notes.md")
                                    WorkspaceInputKind.NewDir -> tr("文件夹名", "Folder name")
                                    WorkspaceInputKind.Rename -> tr("新名字", "New name")
                                    WorkspaceInputKind.RenameWorkspace -> tr("项目显示名", "Project display name")
                                    WorkspaceInputKind.NewWorkspace -> tr("项目名称，例如 my-app", "Project name, e.g. my-app")
                                },
                                style = PiTheme.text.mono.copy(fontSize = 17.sp),
                                color = PiTheme.palette.muted,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        Text(
            if (showProblem) {
                problem.orEmpty()
            } else {
                when (dialog.kind) {
                    WorkspaceInputKind.Rename -> tr("只改文件名，内容不动。", "Renames the file only; contents remain unchanged.")
                    WorkspaceInputKind.NewWorkspace -> tr("保存在应用高速沙盒内（原生 ext4 速度，支持完整 git/npm，无需权限）。", "Created in fast app sandbox (native speed, full git/npm support, no permissions needed).")
                    WorkspaceInputKind.RenameWorkspace -> tr("只改显示名，目录路径不变，会话记录与项目信任不会丢失。", "Changes display name only; folder path, sessions, and project trust stay intact.")
                    else -> tr("建在当前目录下。", "Created in the current directory.")
                }
            },
            modifier = Modifier.padding(top = PiSpacing.inline),
            style = PiTheme.text.meta,
            color = if (showProblem) PiTheme.palette.error else PiTheme.palette.muted,
        )
        PiDialogActions {
            PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = onClose)
            PiDialogAction(
                label = if (dialog.kind == WorkspaceInputKind.NewWorkspace) tr("创建并切换", "Create & Open") else tr("确定", "OK"),
                primary = true,
                enabled = canSubmit,
                onClick = { onSubmit(trimmed) },
            )
        }
        }
    }
}

/** 名字输入框：从 38 抬到 48（38 在手机上又矮又难戳），左右留 12 的内边距。 */
private val NAME_FIELD_HEIGHT = 58.dp
private val NAME_FIELD_RADIUS = WsShape.medium
private val NAME_FIELD_PADDING = 16.dp

// ================================================================ Snackbar

internal data class WorkspaceSnack(val text: String, val tone: StateTone)

/** Snack 右侧那个可选动作：一个标签 + 一下点击（稿子 `Snack` 的 `p.action`）。 */
internal data class WorkspaceSnackAction(val label: String, val onClick: () -> Unit)

/**
 * 底部一条提示（稿子的 `Snack`，`workspace-final.html:627-640`）：三档 tone，2.6 秒后自己走
 * （稿子的 `setTimeout(…, 2600)`）。它是这一屏唯一的反馈通道 —— 复制、导出、保存、文件操作
 * 的结果都在这里说，而不是弹一个对话框要用户点掉。
 *
 * ## 三档的底与字（照稿子）
 *
 * ```
 * Error    toolErrorBg    error     ✗
 * Warning  infoBg         warning   !
 * Info     cardBg         text      ·
 * ```
 *
 * Info 那一档原来用 `surfaceContainerHigh` 画底、`stateToneColor(Muted)` 画符号，两处各偏一
 * 格（`--card` 是 `cardBg`；`·` 的颜色在稿子里是 `--text`），这里收回来。符号与动作用
 * `ui/extension/ExtensionUiHost.kt` 的 `ExtensionSnack` 同一套角色（等宽 `mono`、动作
 * 14 + 500）—— 那是全应用同一套「符号 + 颜色 + 一句话」的编码，只是那一支挂在
 * `PiRoot` 上管全局通知，这一支属于工作区一屏。
 *
 * @param action 右侧可选动作（稿子 `p.action`），可空。这一屏目前**一个调用点都没接**：
 *   现在这些提示（复制、新建、删除的结果）都不需要用户再点一下，「删除后撤销」才需要，
 *   所以构件先把槽留出来。
 */
@Composable
internal fun WorkspaceSnackBar(
    message: WorkspaceSnack,
    modifier: Modifier = Modifier,
    action: WorkspaceSnackAction? = null,
) {
    val palette = PiTheme.palette
    val glyph = when (message.tone) {
        StateTone.Error -> "✗"
        StateTone.Warning -> "!"
        else -> "·"
    }
    val glyphColor = when (message.tone) {
        StateTone.Error -> palette.error
        StateTone.Warning -> palette.warning
        else -> palette.text
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // `.b-snack{left:12px;right:12px;border-radius:12px;padding:10px 12px;gap:10px}`
            // （`workspace-final.html:185-186`，与 `06 §2`「Snackbar：左右 12、圆角 12、
            // `padding:10px 12px` gap 10」同一组数）。**没有描边** —— 那一圈
            // `1px borderMuted` 是本屏早期自造的，稿子里三种语气都只有底色。
            .padding(
                start = PiSettingsMetrics.cardPadding,
                end = PiSettingsMetrics.cardPadding,
                bottom = PiSettingsMetrics.cardPadding,
            )
            .clip(PiShapes.snackbar)
            .background(
                when (message.tone) {
                    StateTone.Error -> palette.toolErrorBg
                    StateTone.Warning -> palette.infoBg
                    else -> palette.cardBg
                },
            )
            .padding(
                horizontal = PiSettingsMetrics.cardPadding,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        // 稿子的 Snack 符号是 `mono t14`（`workspace-final.html:635`）：比正文大一号的
        // 等宽符号，与 14 的消息正文同档；`PiTheme.text.mono` 是 13 的机器正文档。
        Text(
            glyph,
            style = PiTheme.text.mono.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = glyphColor,
            maxLines = 1,
        )
        Text(
            message.text,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.prose,
            color = palette.text,
        )
        if (action != null) {
            Text(
                action.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(SNACK_ACTION_RADIUS))
                    .clickable(role = Role.Button, onClick = action.onClick)
                    .padding(vertical = SNACK_ACTION_PADDING_VERTICAL),
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = palette.text,
            )
        }
    }
}

/** 动作标签的按压面：稿子只给了它一个 `press`，圆角与内边距取 `ExtensionSnack` 那一档。 */
private val SNACK_ACTION_RADIUS = 8.dp
private val SNACK_ACTION_PADDING_VERTICAL = 4.dp

// ================================================================ 通用

/**
 * 粗粒度相对时间，与会话列表**同一套措辞**（`SessionsScreen.kt:391-402`：`刚刚` /
 * `N分钟前` / `N小时前` / `N天前` / `N周前`）。规则重复一次而不是复用，因为那个函数
 * 是私有的、而且那个文件属于 B4（本轮不许碰）。
 */
internal fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    if (delta < 0) return tr("刚刚", "Just now")
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> tr("刚刚", "Just now")
        minutes < 60 -> tr("${minutes}分钟前", "${minutes}m ago")
        minutes < 60 * 24 -> tr("${minutes / 60}小时前", "${minutes / 60}h ago")
        minutes < 60 * 24 * 7 -> tr("${minutes / (60 * 24)}天前", "${minutes / (60 * 24)}d ago")
        else -> tr("${minutes / (60 * 24 * 7)}周前", "${minutes / (60 * 24 * 7)}w ago")
    }
}

/**
 * 新建之后要打开的那条「显示路径」：在工作区里就是相对工作区的路径（`src/a.md`），
 * 面包屑已经走到工作区外（rootfs 的 `/opt` 等）就用绝对路径 —— viewer 的 displayPath
 * 只是标签，真正读写的是传入的 [file]。
 */
internal fun workspaceDisplayPath(workspace: File, file: File, guestRootfs: File? = null): String {
    val base = runCatching { workspace.canonicalPath }.getOrDefault(workspace.absolutePath).trimEnd('/')
    val created = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    if (base.isNotEmpty() && created.startsWith("$base/")) {
        return created.removePrefix("$base/")
    }
    if (guestRootfs != null) {
        return app.pi.runtime.GuestWorkspacePath.hostToGuest(guestRootfs, file)
    }
    return created
}

// ================================================================ 浮层：快速位置跳转

private data class QuickLocationItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

/**
 * Studio location & ancestor jump sheet:
 * - Current real path copy card (`sheetCardBg`)
 * - 2×3 Studio quick-location grid (Current Project, Container `/`, Home `/root`,
 *   Pi Config `~/.pi/agent`, Temp `/tmp`, Phone Storage `/storage/emulated/0`)
 * - Full real ancestor chain with `当前项目` and `正在浏览` badges.
 */
@Composable
internal fun WorkspaceLocationSheet(
    currentPath: String,
    projectGuestPath: String,
    projectInRootfs: Boolean,
    browsingHostFs: Boolean,
    crumbs: List<String>,
    onClose: () -> Unit,
    onCopyPath: (String) -> Unit,
    onJumpProject: () -> Unit,
    onJumpContainer: (String) -> Unit,
    onJumpPhoneStorage: (String) -> Unit,
    onJumpCrumbDepth: (Int) -> Unit,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val prettyCurrent = remember(currentPath, browsingHostFs) {
        if (browsingHostFs) currentPath else app.pi.runtime.GuestWorkspacePath.shortenForDisplay(currentPath)
    }
    val prettyProject = remember(projectGuestPath) {
        app.pi.runtime.GuestWorkspacePath.shortenForDisplay(projectGuestPath)
    }

    val sameFsAsProject = browsingHostFs == !projectInRootfs
    val quickItems = listOf(
        QuickLocationItem(
            title = tr("当前项目", "Current Project"),
            subtitle = prettyProject,
            icon = Icons.Filled.FolderSpecial,
            active = sameFsAsProject && currentPath.trimEnd('/') == projectGuestPath.trimEnd('/'),
            onClick = {
                onClose()
                onJumpProject()
            },
        ),
        QuickLocationItem(
            title = tr("容器根 /", "Container /"),
            subtitle = "/",
            icon = Icons.Filled.Dns,
            active = !browsingHostFs && crumbs.isEmpty(),
            onClick = {
                onClose()
                onJumpContainer("/")
            },
        ),
        QuickLocationItem(
            title = tr("主目录", "Home ~"),
            subtitle = "/root",
            icon = Icons.Filled.Home,
            active = !browsingHostFs && currentPath == "/root",
            onClick = {
                onClose()
                onJumpContainer("/root")
            },
        ),
        QuickLocationItem(
            title = tr("Pi 配置", "Pi Config"),
            subtitle = "~/.pi/agent",
            icon = Icons.Filled.Tune,
            active = !browsingHostFs && currentPath == "/root/.pi/agent",
            onClick = {
                onClose()
                onJumpContainer("/root/.pi/agent")
            },
        ),
        QuickLocationItem(
            title = tr("临时目录", "Temp"),
            subtitle = "/tmp",
            icon = Icons.Filled.Schedule,
            active = !browsingHostFs && currentPath == "/tmp",
            onClick = {
                onClose()
                onJumpContainer("/tmp")
            },
        ),
        QuickLocationItem(
            title = tr("手机存储", "Phone Storage"),
            subtitle = "/storage/emulated/0",
            icon = Icons.Filled.PhoneAndroid,
            active = browsingHostFs && currentPath.startsWith("/storage/emulated/0"),
            onClick = {
                onClose()
                onJumpPhoneStorage("/storage/emulated/0")
            },
        ),
    )

    WsSheet(
        title = tr("跳转位置", "Jump to Location"),
        onClose = onClose,
        subtitle = if (browsingHostFs) {
            tr("正在浏览设备本地文件系统", "Browsing Android device filesystem")
        } else {
            tr("正在浏览 Linux 容器文件系统", "Browsing Linux container filesystem")
        },
        maxBodyHeight = ROOT_SHEET_BODY_MAX,
    ) {
        // Real path copy card
        val cardShape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 4.dp)
                .clip(cardShape)
                .background(PiSheetChromeMetrics.sheetCardBg)
                .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f), cardShape)
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = tr("当前路径", "CURRENT PATH"),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                    color = palette.muted,
                )
                Text(
                    text = currentPath,
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(PiSheetChromeMetrics.sheetControlBg)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        clipboard.setText(AnnotatedString(currentPath))
                        onCopyPath(prettyCurrent)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = tr("复制路径", "Copy path"),
                    modifier = Modifier.size(16.dp),
                    tint = palette.accent,
                )
            }
        }

        // 2x3 Studio quick-location tiles
        WsSectionHeader(label = tr("常用位置", "Quick Locations"))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickItems.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { item ->
                        QuickLocationTile(
                            item = item,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }

        // Full real ancestor chain
        WsSectionHeader(label = tr("路径层级", "Path Hierarchy"))
        val normalizedProject = projectGuestPath.trimEnd('/').ifEmpty { "/" }
        val normalizedCurrent = currentPath.trimEnd('/').ifEmpty { "/" }
        val ancestors = buildList {
            add("/" to 0)
            var acc = ""
            crumbs.forEachIndexed { idx, seg ->
                acc = "$acc/$seg"
                add(acc to (idx + 1))
            }
        }
        WsCard {
            ancestors.forEachIndexed { idx, (fullPath, depth) ->
                if (idx > 0) WsHairline(startInset = WsIconHairlineStart)
                val isProjectRoot = sameFsAsProject && fullPath == normalizedProject
                val isHere = fullPath == normalizedCurrent
                val label = if (depth == 0) {
                    if (browsingHostFs) tr("设备根 /", "Device /") else tr("容器根 /", "Container /")
                } else {
                    fullPath.substringAfterLast('/')
                }
                WsRow(
                    title = label,
                    mono = depth > 0,
                    strong = isHere || isProjectRoot,
                    lead = { FolderGlyph() },
                    meta = if (depth == 0) "/" else app.pi.runtime.GuestWorkspacePath.shortenForDisplay(fullPath),
                    badge = when {
                        isProjectRoot && isHere -> {
                            {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    WsBadge(text = tr("当前项目", "Project"), tone = StateTone.Accent)
                                    WsBadge(text = tr("正在浏览", "Viewing"), tone = StateTone.Success)
                                }
                            }
                        }
                        isProjectRoot -> {
                            { WsBadge(text = tr("当前项目", "Project"), tone = StateTone.Accent) }
                        }
                        isHere -> {
                            { WsBadge(text = tr("正在浏览", "Viewing"), tone = StateTone.Success) }
                        }
                        else -> null
                    },
                    onClick = {
                        onClose()
                        onJumpCrumbDepth(depth)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickLocationTile(
    item: QuickLocationItem,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    val bg = if (item.active) palette.accent.copy(alpha = 0.12f) else PiSheetChromeMetrics.sheetCardBg
    val borderColor = if (item.active) palette.accent.copy(alpha = 0.45f) else palette.borderMuted.copy(alpha = 0.50f)
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(if (item.active) 1.dp else PiSpacing.hairlineThin, borderColor, shape)
            .clickable(onClick = item.onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (item.active) palette.accent.copy(alpha = 0.18f)
                    else PiSheetChromeMetrics.sheetControlBg,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (item.active) palette.accent else palette.muted,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = item.title,
                style = PiType.rowTitle.copy(fontSize = 13.sp),
                color = if (item.active) palette.accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
