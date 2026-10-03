package app.pi.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.pi.bridge.DeviceActionException
import app.pi.bridge.DeviceSystemActions
import app.pi.ui.blocks.PiImageViewerSource
import app.pi.ui.blocks.PiImageViewerSurface
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.render.PiCodeLanguage
import app.pi.ui.render.rememberPiHighlightedCode
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateChip
import app.pi.ui.theme.StateTone
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件查看器 / 编辑器 —— design 稿的 `Viewer`（`:1074-1159`）+ 它自己单开的
 * `binary` / `toolarge` 两个状态。
 *
 * ## 为什么是一层全屏覆盖层，而不是新页面
 *
 * `03-navigation-decision.md` 的裁决：底栏是三个「地方」，别的一律是覆盖层。查看器属于
 * 「我在看这个文件」，不是「我到了另一个地方」。实现上用 `Dialog` + 全屏窗口，于是
 * **返回键、点外部、下滑**三件事都由窗口本身处理，而全应用那一处 `BackHandler`
 * （`PiRoot` 的 KDoc 记着「两处会互相遮盖」）依然只有一处。
 *
 * ## 状态，一个都不能少
 *
 * | 状态 | 触发 | 界面 |
 * |---|---|---|
 * | 加载中 | 还在磁盘上读 | 一行「正在读取…」 |
 * | 文本 | 读到了 | 行号列 + 等宽正文，可滚动、只读（`viewing`） |
 * | 空文件 | 0 行 | 一句话（不是错误，也不是加载中） |
 * | 图片 | 图片后缀 | 图片查看器的同一块画布：可放大、可存到 Download（`ImageBody`） |
 * | 二进制 | NUL 字节 / 已知后缀 | 不让看内容，只说类型与大小，并由系统里的应用打开 |
 * | 超大文件 | > 1 MB | 前 25 行 + 一行说明（`toolarge`） |
 * | 读取失败 | IO 异常 / 文件没了 | 原因 + 详情 + 重试 |
 * | HTML | `.html` / `.htm` | 系统 WebView 渲染预览 |
 * | 编辑 / 保存中 / 保存失败 | 用户点编辑之后 | `editing` / `saving` / `savefailed` |
 * | 未保存离开 | 脏着点返回 | `unsavedLeave` 三选一 |
 */

/** 查看器要打开的那个文件。 */
internal data class WorkspaceViewerTarget(
    val relativePath: String,
    val file: File,
    val kind: WorkspaceEntryKind,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /**
     * 这个文件能不能编辑。
     *
     * 工作区里的文件可以（稿子 ④ 的行菜单里有「编辑」）；⑤ 的资源**不行** ——
     * 稿子底部第 5 条写明资源段只有「打开」与「在对话里用」，工作区屏里不动 `.pi` 里的东西。
     */
    val editable: Boolean = true,
    /** 从行菜单的「编辑」进来的，直接落在编辑态；新建文件也一样（稿子：建完直接进编辑态）。 */
    val startInEdit: Boolean = false,
)

/** WebView 的三个状态（稿子的 `loading` / 加载完 / `failed`）。 */
internal sealed interface HtmlState {
    data object Loading : HtmlState
    data object Done : HtmlState
    data class Failed(val reason: String) : HtmlState
}

private enum class ViewerMode { View, Edit }

/**
 * 全屏查看器。
 *
 * @param onClose 关掉这一层（调用方负责把 [target] 置空）。
 * @param onMessage Snackbar 文案（复制、导出、分享的结果）。
 * @param onSaved 一次成功的保存，参数是**工作区相对路径** —— 调用方据此把「本次会话改过」
 *   加上这一条（design 稿第 2 条口径里的「你自己的 File I/O 编辑」）。
 * @param onMore 顶栏右上那个 ⋮（与 ③④ 行尾是同一套文件菜单）。
 */
@Composable
internal fun WorkspaceViewer(
    target: WorkspaceViewerTarget,
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
    onSaved: (String) -> Unit,
    onMore: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reloadTick by remember(target.relativePath) { mutableIntStateOf(0) }
    // null = 正在读。它和「0 行」是两件事，所以不能用空表代替。
    var opened by remember(target.relativePath, reloadTick) { mutableStateOf<WorkspaceOpen?>(null) }
    var mode by remember(target.relativePath) {
        // `startInEdit` 也要过「这个 kind 能不能按文本改」那道判据（判据一处，见
        // `WorkspaceFiles.editableAsText`）：菜单里的「编辑」与被写死的 `startInEdit` 都可能
        // 落到图片/二进制上，而编辑态对着空草稿保存，就是拿 UTF-8 文本覆盖一张图片。
        mutableStateOf(
            if (target.startInEdit && WorkspaceFiles.editableAsText(target.kind)) {
                ViewerMode.Edit
            } else {
                ViewerMode.View
            },
        )
    }
    var draft by remember(target.relativePath) { mutableStateOf("") }
    var dirty by remember(target.relativePath) { mutableStateOf(false) }
    var saving by remember(target.relativePath) { mutableStateOf(false) }
    var saveError by remember(target.relativePath) { mutableStateOf<WorkspaceOpen.Failed?>(null) }
    // 打开（或上次保存后重读）时的 `(size, mtime)`：`.pi/` 里的文件保存前会比一次，发现被
    // pi 或别处改过就拒绝覆盖。null = 还没读到过内容，那时不做变更检测。
    var openedStamp by remember(target.relativePath) { mutableStateOf<String?>(null) }
    var askUnsaved by remember(target.relativePath) { mutableStateOf(false) }
    var htmlReload by remember(target.relativePath) { mutableIntStateOf(0) }

    val isHtml = target.kind == WorkspaceEntryKind.Html

    // 文件内容的解码同样在 IO 线程：一份 5 MB 的文本在 composition 里 `readLines()` 就是
    // 一次掉帧。
    LaunchedEffect(target.file.absolutePath, reloadTick, isHtml) {
        if (isHtml) return@LaunchedEffect
        opened = null
        // 读内容与记指纹在同一跳 IO 里：指纹必须是**这次读到的**那一版，晚一步记就等于把
        // 两次读之间的改动算成"我看到的"。保存成功后 `reloadTick` 会让这个 effect 再跑一次，
        // 指纹跟着刷新，所以连续编辑两次不会被自己的上一次保存拦住。
        val loaded = withContext(Dispatchers.IO) {
            WorkspaceFiles.open(target.file, target.kind) to WorkspaceFiles.stampOf(target.file)
        }
        opened = loaded.first
        openedStamp = loaded.second
    }

    // 「编辑」可以直接从行菜单进来，那时文本还没读完：读完就把草稿铺上。
    LaunchedEffect(opened, mode) {
        val text = opened
        if (mode == ViewerMode.Edit && text is WorkspaceOpen.Text && draft.isEmpty() && !dirty) {
            draft = text.lines.joinToString("\n")
        }
    }

    fun leave() {
        when {
            saving -> Unit
            dirty -> askUnsaved = true
            else -> onClose()
        }
    }

    fun save(closeAfter: Boolean = false) {
        if (saving) return
        saving = true
        saveError = null
        scope.launch {
            // `save`, not `writeText`: a save to the workspace's own `.pi/**` is a write to a
            // file pi reads, and it has to go through pi's lock + atomic replace (and its
            // content check) rather than a whole-file overwrite. The rule and the check live in
            // `WorkspacePiWrite`; the write itself is still `PiConfigFiles`'s one implementation.
            val result = withContext(Dispatchers.IO) {
                WorkspaceFiles.save(target.file, target.relativePath, draft, openedStamp)
            }
            saving = false
            result.fold(
                onSuccess = {
                    dirty = false
                    mode = ViewerMode.View
                    onSaved(target.relativePath)
                    onMessage("已保存 · ${target.file.name}")
                    if (closeAfter) onClose() else reloadTick++
                },
                onFailure = { error ->
                    saveError = WorkspaceOpen.Failed(
                        reason = "写盘失败：" + (error.message ?: error::class.java.simpleName),
                        detail = error.stackTraceToString(),
                    )
                },
            )
        }
    }

    Dialog(
        onDismissRequest = { leave() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                ViewerTopBar(
                    name = target.file.name,
                    relativePath = target.relativePath,
                    sizeBytes = target.sizeBytes,
                    modifiedAt = target.modifiedAt,
                    dirty = dirty,
                    saving = saving,
                    editing = mode == ViewerMode.Edit,
                    canEdit = target.editable && WorkspaceFiles.editableAsText(target.kind),
                    onBack = { leave() },
                    onEdit = {
                        val text = opened
                        draft = if (text is WorkspaceOpen.Text) text.lines.joinToString("\n") else ""
                        dirty = false
                        mode = ViewerMode.Edit
                    },
                    onSave = { save() },
                    onMore = onMore,
                )
                HorizontalDivider(
                    thickness = PiSettingsMetrics.hairline,
                    color = MaterialTheme.colorScheme.outline,
                )
                Box(Modifier.fillMaxSize()) {
                    when {
                        isHtml -> HtmlPreview(
                            file = target.file,
                            reloadTick = htmlReload,
                            onRetry = { htmlReload++ },
                        )

                        mode == ViewerMode.Edit -> Column(Modifier.fillMaxSize()) {
                            if (saveError != null) {
                                WsErrBlock(
                                    title = "保存失败",
                                    message = saveError?.reason,
                                    detail = saveError?.detail,
                                    actions = {
                                        WsErrAction("重试") { save() }
                                        WsErrAction("复制错误") {
                                            copyToClipboard(context, saveError?.detail.orEmpty())
                                            onMessage("已复制错误")
                                        }
                                    },
                                )
                            }
                            EditorBody(
                                draft = draft,
                                onDraftChange = {
                                    draft = it
                                    dirty = true
                                },
                                saving = saving,
                            )
                        }

                        else -> ViewerBody(
                            opened = opened,
                            saveError = saveError,
                            file = target.file,
                            // 高亮语言按工作区相对路径判（`PiCodeLanguage.forPath`），与对话里
                            // read/write 卡同一条路径；pi 也一样按路径后缀决定语言
                            // （`renderers/read.ts:126-127`）。
                            path = target.relativePath,
                            onRetry = { reloadTick++ },
                            onRetrySave = { save() },
                            onExport = {
                                onMessage(exportBinary(context, target.file))
                            },
                            onShare = {
                                onMessage(sharePath(context, target))
                            },
                            onOpenWithApp = {
                                onMessage(openWithOtherApp(context, target.file))
                            },
                            onCopyError = {
                                copyToClipboard(context, saveError?.detail.orEmpty())
                                onMessage("已复制错误")
                            },
                            // 图片那一支的 ✕ / 点空白处：与顶栏返回走同一条路（`leave()`），
                            // 图片没有草稿，它只会关掉这一层。
                            onDismissImage = { leave() },
                            onMessage = onMessage,
                        )
                    }
                }
            }
        }
    }

    if (askUnsaved) {
        PiDialog(onDismissRequest = { askUnsaved = false }) {
            PiDialogTitle(title = "还没保存", glyph = "!", glyphTone = PiTheme.palette.warning)
            PiDialogBody(
                "「${target.file.name}」有改动还没保存。返回之后这些改动就没了。",
            )
            PiDialogActions {
                PiDialogAction(
                    label = "继续编辑",
                    primary = false,
                    onClick = { askUnsaved = false },
                )
                PiDialogAction(
                    label = "不保存",
                    primary = false,
                    tone = PiTheme.palette.error,
                    onClick = {
                        askUnsaved = false
                        dirty = false
                        onClose()
                    },
                )
                PiDialogAction(
                    label = "保存并返回",
                    primary = true,
                    onClick = {
                        askUnsaved = false
                        // 成功才关窗（`save(closeAfter = true)` 在 onSuccess 里关）；
                        // 失败就留在编辑态并把错误块画出来，不会「保存失败却也退出了」。
                        save(closeAfter = true)
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 顶栏 ----

/**
 * 查看器自己的顶栏：48 高、`surfaceDim` 底、底部 1px 线，标题是**等宽的文件名**
 * （稿子 `TopBar title={<span className="mono">{f.name}</span>}`），副行是相对路径 +
 * 大小 + 时间。
 *
 * 不复用 `PiTopBar`：它的标题是 `titleMedium`（无衬线），而这一屏的标题必须等宽 ——
 * 文件名是机器产出的字符串，v2 的规矩是「文件名与路径一律等宽」。
 */
@Composable
private fun ViewerTopBar(
    name: String,
    relativePath: String,
    sizeBytes: Long,
    modifiedAt: Long,
    dirty: Boolean,
    saving: Boolean,
    editing: Boolean,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onMore: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceDim),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PiSpacing.topBarHeight)
                .padding(horizontal = PiSettingsMetrics.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = PiTheme.text.mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // One line, two voices — which is exactly how the frozen board draws it:
                // `<span className="mono">{path2}</span><span> · {f.size} · {f.time}</span>`
                // (`workspace-final.html:1081-1085`, the viewer's `metaNode`; the `TopBar` it
                // is handed to renders its meta in `t12 c-muted`, i.e. the UI face). So the
                // **path** is the machine span and the size/time reading beside it is not.
                //
                // The whole line used to be `monoSmall`, which is the case `05 §4.3` names as
                // the reason the mono face is bundled at all: 「中文会掉出 mono 回退」 —
                // `WorkspaceFiles.formatTime` returns Chinese (「今天 14:26」), JetBrains Mono
                // ships no CJK glyphs, so that half fell back to the system face while still
                // being asked for a fixed advance width.
                val monoFamily = PiTheme.text.monoSmall.fontFamily
                // The non-path half is built by the stdlib: `AnnotatedString.Builder`
                // implements `Appendable`, so chaining `append(…).append(…)` on it resolves
                // through `Appendable.append(CharSequence?)` and stops returning a Builder.
                val tail = buildString {
                    append(" · ").append(WorkspaceFiles.formatSize(sizeBytes))
                    append(" · ").append(WorkspaceFiles.formatTime(modifiedAt))
                    if (saving) append(" · 正在写盘")
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = monoFamily)) { append(relativePath) }
                        append(tail)
                    },
                    modifier = Modifier.padding(top = 1.dp),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (dirty) {
                // 稿子把「● 未保存」画在副行里、用 warning 色。副行是纯文本，承载不了第二个
                // 字色，所以这里挂成徽标 —— 三重编码（字 + 符号 + 颜色）一个不少。
                StateChip(label = "未保存", tone = StateTone.Warning, glyph = "●")
            }
            when {
                saving -> Text(
                    "… 保存中",
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.warning,
                    maxLines = 1,
                )

                editing -> Text(
                    "保存",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = dirty, onClick = onSave)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (dirty) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (dirty) PiTheme.palette.accent else PiTheme.palette.muted,
                    maxLines = 1,
                )

                else -> {
                    if (canEdit) {
                        Text(
                            "编辑",
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onEdit)
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    WsMoreButton(onClick = onMore)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 正文 ----

@Composable
private fun ViewerBody(
    opened: WorkspaceOpen?,
    saveError: WorkspaceOpen.Failed?,
    /**
     * 正在看的那个文件。
     *
     * 图片那一支要把它交给图片查看器（[PiImageViewerSurface] 收的是宿主文件），别的几支只
     * 用到它的名字 —— 一个 [File] 足够，不需要再带一个 `fileName` 字符串（两个参数说的是
     * 同一件事，就有第二份真相）。
     */
    file: File,
    /**
     * 工作区相对路径。只做两件事：给正文挑高亮语言（`PiCodeLanguage.forPath`，与对话里的
     * `read`/`write` 卡同一条路径），以及 [`TooLargeBody`] 的提示。**显示**用的是
     * [file] 的名字与顶栏那份 meta。
     */
    path: String,
    onRetry: () -> Unit,
    onRetrySave: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onOpenWithApp: () -> Unit,
    onCopyError: () -> Unit,
    /** 图片那一支的 ✕ / 点图片周围：关掉整个查看器（图片没有草稿，中间没有可问的东西）。 */
    onDismissImage: () -> Unit,
    /** 图片查看器里「保存到下载目录」的结果。 */
    onMessage: (String) -> Unit,
) {
    if (saveError != null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(PiSettingsMetrics.notePaddingVertical))
            WsErrBlock(
                title = "保存失败",
                message = "改过的地方还在编辑器里，磁盘上还是旧的。",
                detail = saveError.detail,
                actions = {
                    WsErrAction("重试") { onRetrySave() }
                    WsErrAction("复制错误") { onCopyError() }
                },
            )
        }
        return
    }
    when (opened) {
        null -> LoadingBody()
        is WorkspaceOpen.Text -> if (opened.lines.isEmpty()) {
            EmptyFileBody(file.name)
        } else {
            CodeBody(opened.lines, opened.totalLines, opened.truncated, path)
        }

        is WorkspaceOpen.TooLarge -> TooLargeBody(opened, path)
        is WorkspaceOpen.Image -> ImageBody(file, onDismissImage, onMessage)
        is WorkspaceOpen.Binary -> BinaryBody(opened, file.name, onOpenWithApp, onExport, onShare)
        is WorkspaceOpen.Failed -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(PiSettingsMetrics.notePaddingVertical))
            WsErrBlock(
                title = "读不到这个文件",
                message = opened.reason,
                detail = opened.detail,
                actions = { WsErrAction("重试") { onRetry() } },
            )
        }
    }
}

/** 加载中：一行字。它不能画成空态，因为「还没有答案」和「答案是空」必须分得开。 */
@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "正在读取…",
            style = MaterialTheme.typography.bodyMedium,
            color = PiTheme.palette.muted,
        )
    }
}

/** 空文件：一句话，不是错误。 */
@Composable
private fun EmptyFileBody(fileName: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "这个文件是空的",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "「$fileName」里还没有任何内容。点右上「编辑」可以写第一行；" +
                    "写什么由你，这一屏不会替你填。",
                modifier = Modifier.padding(top = PiSpacing.inline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 文本正文：行号列 34 + 等宽正文（稿子的 `CodeView`）。
 *
 * `LazyColumn` 而不是 `Column`：文本上限是 [WorkspaceFiles.MAX_TEXT_LINES] 行，一次性
 * 组合 5000 个 `Text` 会把首帧拖住。
 *
 * ## 语法着色（稿子的 `CodeView` 是逐段上色的，`workspace-final.html:1025-1046`）
 *
 * 用的是**现成**的那条高亮通道 —— `blocks/ToolBodyText.SourceLines` 与 markdown 码块
 * 共用的 `rememberPiHighlightedCode`（引擎里的 highlight.js）。没有任何新的服务、客户端、
 * 线程池或端口，也没有新的请求节奏：
 *
 *  - **整份正文问一次**。请求的文本就是行号切片的那份文本，所以字符偏移天然对齐；**不是**
 *    每行一次 —— 多行结构（注释块、字符串）会因此错色，而且请求数会变成行数量级。
 *  - **按行惰性切片**。整块结果是一段 `AnnotatedString`，只有**可见行**才做一次
 *    `subSequence`；行首偏移预先算成一个 `IntArray`（一个对象、每行一个 int），所以 5000 行
 *    不会预生成 5000 个对象，`LazyColumn` 的结构一行没动。
 *  - **高亮服务自己的上限照旧**（>64 KiB 或 >400 行不上色，见 `PiNodeCodeHighlighter`），
 *    这一屏不为查看器抬预算；超限就是原来的单色。
 *  - **引擎没起来 → 空 spans → 单色**，与这一屏原来**完全一样**：不提示、不重试、不占位
 *    （`PiCodeLanguage.isUnspecified` 时那条路连请求都不发）。
 *  - **编辑态不上色**：`EditBody` 走自己的 `BasicTextField`，这里根本不会被调用。
 */
@Composable
private fun CodeBody(lines: List<String>, totalLines: Int?, truncated: Boolean, path: String) {
    val palette = PiTheme.palette
    // 一次请求，问的是「将要被切片的这段文本」。
    val code = remember(lines) { lines.joinToString("\n") }
    // `key(path)`：`rememberPiHighlightedCode` 用「同一个调用点是不是又变了一次」来判断正文
    // 还在不在流式产出，而它据此会把第二次以后的变化延后 `STREAM_SETTLE_MS`（200 ms）再发问
    // —— 那对流式 markdown 是对的，对查看器是错的：这里每一次变化都是**换了一个文件**，
    // 正文已经落定。按 `path` 分组就等于「换文件 = 一个新的调用点」，首帧立刻着色，且仍然
    // 只有一次请求、`ui/render/**` 一行未动。
    val highlighted = key(path) {
        rememberPiHighlightedCode(code, remember(path) { PiCodeLanguage.forPath(path) })
    }
    // 每一行在整块文本里的起始偏移。`IntArray` 而不是 List<IntRange>：一行一个 int。
    val offsets = remember(lines) {
        val out = IntArray(lines.size)
        var cursor = 0
        lines.forEachIndexed { index, line ->
            out[index] = cursor
            cursor += line.length + 1
        }
        out
    }
    // 引擎知道这门语言时，它没包进 span 的字符按 pi 的规矩保持正文色；不知道（或引擎不在）
    // 时就是这一屏原来那一个颜色。
    val baseColor = if (highlighted.languageKnown) palette.text else MaterialTheme.colorScheme.onSurface
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PiSettingsMetrics.pageHorizontal,
            end = PiSettingsMetrics.pageHorizontal,
            top = PiSettingsMetrics.rowPaddingVertical,
            bottom = PiSpacing.scrollBottom,
        ),
    ) {
        itemsIndexed(lines) { index, line ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${index + 1}",
                    modifier = Modifier
                        .width(ViewerLineNumberWidth)
                        .padding(end = PiSettingsMetrics.rowGap),
                    style = PiTheme.text.monoSmall,
                    color = palette.bodyOnTool,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                val start = offsets[index].coerceIn(0, highlighted.text.length)
                val end = (start + line.length).coerceIn(start, highlighted.text.length)
                Text(
                    // 只有可见行会走到这里；偏移越界时退回原始那一行，宁可不上色也不抛异常
                    // （与 `SourceLines.numberLines` 同一条规矩）。
                    text = if (start < end) highlighted.text.subSequence(start, end) else AnnotatedString(line),
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.code,
                    color = baseColor,
                )
            }
        }
        if (truncated) {
            item {
                Row(
                    modifier = Modifier.padding(top = PiSettingsMetrics.notePaddingVertical),
                    horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
                ) {
                    Text(
                        "⋮",
                        modifier = Modifier.width(ViewerLineNumberWidth).padding(end = PiSettingsMetrics.rowGap),
                        style = PiTheme.text.monoSmall,
                        color = PiTheme.palette.bodyOnTool,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        "… 其余 ${(totalLines ?: 0) - lines.size} 行未显示",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.bodyOnTool,
                    )
                }
            }
        }
    }
}

/** `CodeView` 的行号列是 34（`:1029`）。 */
private val ViewerLineNumberWidth = 34.dp

/** 超大文件：先一条说明，再前 25 行，末尾写清还有多少行没显示。 */
@Composable
private fun TooLargeBody(opened: WorkspaceOpen.TooLarge, path: String) {
    Column(Modifier.fillMaxSize()) {
        WsNotice(
            text = "文件 ${WorkspaceFiles.formatSize(opened.sizeBytes)}。" +
                "查看器不整份载入，只给前 ${WorkspaceFiles.PREVIEW_LINES} 行；" +
                "要看全部就到「对话」里让 pi 读它。",
            tone = StateTone.Warning,
            glyph = "!",
        )
        CodeBody(
            lines = opened.lines,
            totalLines = opened.omittedLines?.plus(opened.lines.size),
            truncated = true,
            path = path,
        )
    }
}

/**
 * 图片：**交给聊天那个图片查看器的同一块画布**（[PiImageViewerSurface]）。
 *
 * 为什么不在这里另写一个"图片预览"：解码只有一份（`decodePiImageFile`）、闸门只有一把
 * （`piImageDecodeGate`）、手势只有一套（捏合 / 拖动 / 双击），而"保存到下载目录"这件事
 * 也就只有一处（`DeviceSystemActions.export`）。抄一份出来的第一天就会和那一份不一致 ——
 * 这个文件里每一个"第二份"都写着同样的结论。
 *
 * 这一支只说明"画哪里"：它已经在一个全屏 `Dialog` 里（顶栏、⋮ 菜单都在），再为一张图开
 * 第二个窗口没有意义，所以画布直接铺在正文区；✕ 与点空白处都交给 [onDismiss]，与顶栏返回
 * 是同一条路（[ViewerBody] 的 `onDismissImage`）。
 */
@Composable
private fun ImageBody(
    file: File,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    PiImageViewerSurface(
        image = PiImageViewerSource.HostFile(file),
        onDismiss = onDismiss,
        onMessage = onMessage,
    )
}

/**
 * 二进制：不给内容，只说类型与大小 —— 但**能交给系统里的应用打开**。
 *
 * 「渲染不了的交系统」是这一屏现在的判据：主动作是 `ACTION_VIEW` + `content://` URI +
 * 正确的 MIME（[openWithOtherApp]），导出与分享退为次动作（系统里没人能接时它们还在，
 * 而且那时提示会把这句话说清楚）。以前这里的主动作是「导出到 Download」—— 用户想看一眼
 * 那个 PDF，得到的却是 Download 里多一个文件，还得自己再去找。
 */
@Composable
private fun BinaryBody(
    opened: WorkspaceOpen.Binary,
    fileName: String,
    onOpenWithApp: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 稿子给二进制正文画的是 `Icon n="bin" s={30}`（`workspace-final.html:1120`）：
            // 与目录树里那个文件态同一个图标，只是放大到 30。原来这里是一个字符 `▤`。
            Icon(
                imageVector = WsBinaryFileGlyph,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = PiTheme.palette.muted,
            )
            Text(
                "这是二进制文件",
                modifier = Modifier.padding(top = PiSettingsMetrics.rowGap),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "查看器不展开它：它没有字符编码可以按，展开出来只是乱码。" +
                    "「$fileName」是${opened.typeLabel}，${WorkspaceFiles.formatSize(opened.sizeBytes)}，" +
                    "可以交给系统里的应用打开。",
                modifier = Modifier.padding(top = PiSpacing.inline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = PiSettingsMetrics.cardPaddingLoose),
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.pageHorizontal),
            ) {
                WsErrAction("用其他应用打开", tone = PiTheme.palette.accent, onClick = onOpenWithApp)
            }
            Row(
                modifier = Modifier.padding(top = PiSettingsMetrics.cardPadding),
                horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.pageHorizontal),
            ) {
                WsErrAction("导出到 Download", tone = PiTheme.palette.muted, onClick = onExport)
                WsErrAction("分享", tone = PiTheme.palette.muted, onClick = onShare)
            }
        }
    }
}

/**
 * 编辑态：一个等宽文本域 + accent 聚焦边（稿子的 `editing`）。
 *
 * `BasicTextField` 而不是 `OutlinedTextField`：稿子画的是一个**没有标签、没有占位、
 * 没有 M3 容器**的纯文本域，边是一圈 `borderAccent`。`BasicTextField` 正好是那个形状，
 * 而 M3 的字段会给它加一圈自己的容器与内边距。
 */
@Composable
private fun EditorBody(
    draft: String,
    onDraftChange: (String) -> Unit,
    saving: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.cardPadding,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
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
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PiTheme.palette.accent),
            )
        }
        Text(
            if (saving) "正在把整份文件写回工作区。" else "返回时如果还没保存，会先问你一次。",
            modifier = Modifier.padding(top = PiSpacing.inline),
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
    }
}

// ------------------------------------------------------------------ HTML ----

/**
 * `.html` 的渲染预览（用户原话：「就只加一个功能：HTML 文件可以打开」）。
 *
 * 「本批新增的唯一一条能力」这句话已经过期了：图片现在走 [ImageBody]，二进制交给系统
 * （[openWithOtherApp]）—— 三条路加起来的判据是同一个：**能渲染的自己渲染，渲染不了的
 * 交系统**。
 *
 * ## 为什么是系统 WebView，而不是自己解析
 *
 * 用户要的是「能查看就行」。一个本地 HTML 文件要正确渲染，靠的是设备上那个真实的浏览器
 * 引擎（脚本、样式、charset、`<img>` 相对路径），自己写一个渲染器就是重造一个更差的
 * WebView。`androidx.webkit:webkit` 已经在依赖里，但**不需要**它：`android.webkit.WebView`
 * 就能做这件事，能不多引一层就不引。
 *
 * ## 三条设置，各自为什么（稿子/需求点名要的三条之外，其余一律不动）
 *
 * | 设置 | 为什么 |
 * |---|---|
 * | `javaScriptEnabled = true` | 现代 HTML 预览里脚本是内容的一部分；关掉会让一部分页面显示成半成品 |
 * | `domStorageEnabled = true` | 本地页面用 `localStorage`/`sessionStorage` 时不会抛异常（默认关，页面会白屏） |
 * | `allowFileAccess = true` | `file://` 是这一屏唯一的来源；API 30+ 默认是关的，不开就连文件都读不到 |
 * | `allowContentAccess = false` | 这是**收紧**：预览不需要 `content://`，关掉它少一条越权读路径 |
 *
 * `file://` 只能在 App 内加载，这一点不要试图用系统浏览器 —— 系统浏览器没有这个 App 的
 * 私有目录访问权，`startActivity(ACTION_VIEW)` 会拿到一个打不开的 URI。
 *
 * ## 不做什么
 *
 * 不做 `measure/layout + draw(Bitmap)` 导出图片 —— 那是下一批（稿子与需求都写明）。
 */
@Composable
private fun HtmlPreview(
    file: File,
    reloadTick: Int,
    onRetry: () -> Unit,
) {
    var state by remember(file.absolutePath) { mutableStateOf<HtmlState>(HtmlState.Loading) }
    // `update` 里重新 load 只在重试计数变化时发生：`AndroidView` 的 update 每帧都可能跑，
    // 无条件 `loadUrl` 会把页面按在加载态里出不来。
    var lastLoaded by remember(file.absolutePath) { mutableIntStateOf(-1) }

    Column(Modifier.fillMaxSize()) {
        when (val current = state) {
            is HtmlState.Loading -> WsNotice(
                text = "正在用设备上的 WebView 渲染这个页面…",
                tone = StateTone.Muted,
            )

            is HtmlState.Done -> Unit
            is HtmlState.Failed -> WsErrBlock(
                title = "WebView 没能渲染这个页面",
                message = current.reason,
                actions = { WsErrAction("重试") { onRetry() } },
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            state = HtmlState.Done
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            // 只有主文档的失败才是「打不开这个页面」；一张图片加载不到不该
                            // 把整页判成失败。
                            if (!request.isForMainFrame) return
                            state = HtmlState.Failed(
                                "WebView 报错 ${error.errorCode}：${error.description}",
                            )
                        }
                    }
                }
            },
            update = { view ->
                if (lastLoaded != reloadTick) {
                    lastLoaded = reloadTick
                    state = HtmlState.Loading
                    view.loadUrl("file://${file.absolutePath}")
                }
            },
        )
    }
}

// ------------------------------------------------------------------ 设备动作 ----

/**
 * 把二进制文件导出到公共 Download。
 *
 * 走 App 既有的那条导出通道（`DeviceSystemActions.export`，设置里「导出诊断报告」用的是
 * 同一支），而不是在这里另写一个 `MediaStore` 写入器：那会变成第二条写公共目录的路径，
 * 两条路径迟早会在分区存储的某个分支上不一致。
 *
 * 字节给的是**文件本身**（`sourceFile`）：这条路以前自己 `readBytes()` 再 base64 一遍，
 * 而 `export` 拿到 base64 又要解回来 —— 一个 20 MB 的文件，峰值内存里躺着三份同样的字节。
 */
private fun exportBinary(context: Context, file: File): String = try {
    DeviceSystemActions.export(
        context = context,
        name = file.name,
        text = null,
        base64 = null,
        mimeType = DeviceSystemActions.mimeTypeForFileName(file.name),
        sourceFile = file,
    )
    "已保存到 Download/${file.name}"
} catch (error: DeviceActionException) {
    "导出失败：" + error.denial.reason + (error.denial.hint?.let { " $it" } ?: "")
} catch (error: Exception) {
    "导出失败：${error::class.java.simpleName}: ${error.message}"
}

/**
 * 用系统里的应用打开这个文件：`ACTION_VIEW` + `content://` URI（[FileProvider] 授读权限）
 * + 正确的 MIME。
 *
 * 三条各自都有理由：
 *
 *  - **`content://` 而不是 `file://`**：Android 7 起把 `file://` 交给另一个应用会抛
 *    `FileUriExposedException`。这个 App 的私有目录别人也读不到，只有 URI 授权能给出去。
 *  - **`FLAG_GRANT_READ_URI_PERMISSION`**：URI 授权是要显式带上的，否则对面拿到一个
 *    读不了的 URI，报的是它自己的"文件不存在"。
 *  - **`FLAG_ACTIVITY_NEW_TASK`**：这里拿到的 `Context` 是查看器那个 `Dialog` 的 context
 *    （不是 Activity），从非 Activity 的 context 起界面必须带这个 flag，否则
 *    `AndroidRuntimeException`。
 *
 * 判"有没有人能接"靠的是 `startActivity` 抛不抛 `ActivityNotFoundException`，**不是**
 * `resolveActivity`：API 30 起包可见性会过滤 `resolveActivity` 的结果，一个系统明明能起的
 * 应用可能在这里答 null —— 那会让我们把"其实打得开"说成"没有应用能打开"。异常是平台自己
 * 的答案，不经过可见性过滤。
 *
 * 返回一句给用户看的话：成功、没人能接（保留导出/分享两条路）、或 FileProvider 覆盖不到
 * 这个路径。**不静默降级**：任何一种失败都带一句人话，而不是"什么都没发生"。
 */
private fun openWithOtherApp(context: Context, file: File): String {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: IllegalArgumentException) {
        // FileProvider 的路径清单只覆盖 `<files>/pi/`（`res/xml/file_paths.xml`）：工作区
        // 可以是一个设备上的真目录（「从设备目录选择」），那里不在授权范围内。不为了这一步
        // 把根目录也交出去 —— 那是把 shared_prefs/databases 一起放进可授权的范围。
        return "这个文件不在应用的私有目录里，没法直接交给其他应用打开。" +
            "可以先「导出到 Download」，再从那里打开或分享。"
    }
    val mime = DeviceSystemActions.mimeTypeForFileName(file.name)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        "已交给其他应用打开 · ${file.name}"
    } catch (error: ActivityNotFoundException) {
        "这台设备上没有能打开这种文件的应用（$mime）。可以导出到 Download，或分享它的路径。"
    } catch (error: Exception) {
        "打开失败：${error::class.java.simpleName}: ${error.message}"
    }
}

/** 分享：App 的分享通道只送文本（`ACTION_SEND` + `text/plain`），所以送路径。 */
private fun sharePath(context: Context, target: WorkspaceViewerTarget): String = try {
    DeviceSystemActions.share(
        context = context,
        text = "工作区文件：${target.relativePath}\n${target.file.absolutePath}",
        subject = target.file.name,
        url = null,
    )
    "已打开分享"
} catch (error: DeviceActionException) {
    "分享失败：" + error.message
} catch (error: Exception) {
    "分享失败：${error::class.java.simpleName}: ${error.message}"
}

/** 复制一段文本（错误详情、`/名字`）；失败不抛，回一句人话。 */
internal fun copyToClipboard(context: Context, text: String): Boolean =
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return false
        manager.setPrimaryClip(android.content.ClipData.newPlainText("pi", text))
        true
    }.getOrDefault(false)
