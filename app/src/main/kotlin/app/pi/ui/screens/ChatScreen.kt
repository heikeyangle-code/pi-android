package app.pi.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import app.pi.engine.PiEngineSession
import app.pi.i18n.tr
import app.pi.ui.extension.ExtensionHubShortcut
import app.pi.ui.extension.ExtensionWidgetsSheet
import app.pi.ui.extension.stripAnsiControls
import app.pi.ui.extension.widgetCaption
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.semantics.semantics
import app.pi.settings.readBoolean
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import app.pi.protocol.AssistantText
import app.pi.protocol.DateSeparator
import app.pi.protocol.ErrorText
import app.pi.protocol.ModelChange
import app.pi.protocol.ThinkingBlock
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolStatus
import app.pi.protocol.TranscriptItem
import app.pi.protocol.PiImage
import app.pi.protocol.UserMessage
import app.pi.ui.Boot
import app.pi.ui.NavRequest
import app.pi.ui.PiSessionViewModel
import app.pi.ui.earlierRowText
import app.pi.ui.blocks.BlockRenderer
import app.pi.ui.blocks.LocalTranscriptRowAnchor
import app.pi.ui.blocks.PiImageViewer
import app.pi.ui.blocks.TranscriptRowAnchor
import app.pi.ui.chat.ComposerRoute
import app.pi.ui.chat.ChatAction
import app.pi.ui.chat.argumentContextOf
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.PiCommandSource
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.TailFollow
import app.pi.ui.chat.TailSnapshot
import app.pi.ui.chat.TailViewport
import app.pi.ui.chat.mayLoadEarlier
import app.pi.ui.chat.mergeRestoredQueue
import app.pi.ui.chat.routeComposerText
import app.pi.ui.chat.freshRowKeysAfter
import app.pi.ui.chat.hiddenRows
import app.pi.ui.chat.itemIndexOfVisibleRow
import app.pi.ui.chat.mayArmEarlier
import app.pi.ui.chat.CLIENT_BUILTINS
import app.pi.ui.chat.unsupportedReason
import app.pi.ui.render.LocalPiMarkdownImmediate
import app.pi.ui.render.LocalPiMarkdownParsed
import app.pi.ui.render.RowHeightCache
import app.pi.ui.render.gapFor
import app.pi.ui.render.piMarkdownParseBudget
import app.pi.ui.render.rememberedRowHeight
import app.pi.ui.render.transcriptGap
import app.pi.ui.extension.ExtensionWidgetStack
import app.pi.ui.extension.ExtensionWidgetSurfaceSheet
import app.pi.ui.extension.PLACEMENT_BELOW
import app.pi.ui.extension.toExtensionPalette
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Conversation screen (`ChatScreen`).
 *
 * Mounts the transcript list, composer, queue/extension docks, and sheets.
 * PiRoot mounts `ExtensionUiHost` once above the active destination.
 */
@Composable
fun ChatScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
    /**
     * 点顶栏那个会话名时打开会话列表覆盖层。
     *
     * 会话列表是「选择器」，它挂在**当前会话的名字**上：那个名字就是用户会说「我要换一个」
     * 时看的东西（`03-navigation-decision.md` 把列表从底栏挪到这里的原话）。入口做成一个
     * callback 而不是这里直接发导航请求，是因为覆盖层归 `PiRoot` 管，而这一屏不知道它。
     */
    onOpenSessions: () -> Unit,
    /**
     * 工作区（`PiDestination.Workbench`）。
     *
     * 底栏拆掉之后，工作区和设置的门只在会话页顶栏；从对话页过去要先开会话覆盖层再点
     * 一次，多一层。这一条把入口直接放进对话页的「⋯」菜单（用户裁决）。两条都由
     * `PiRoot` 给 —— 目的地归它管，这一屏不知道有哪些目的地。
     */
    onOpenWorkspace: () -> Unit,
    /** 设置（`PiDestination.Settings`），同上。 */
    onOpenSettings: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val bottomInset = contentPadding.calculateBottomPadding()

    // D31: the boot surface is for **work** and for **failure** only.
    //
    // `Boot.Working` is a real unpack (first install, or a new engine revision) —
    // minutes long, with steps and a progress bar, so it takes the screen.
    // `Boot.Failed` is not a wait at all, it is the error state with 重试 and the
    // diagnostics.
    //
    // `Boot.Idle` is the 1–2 s between "the app is up" and "pi answered the first
    // `get_state`", and it used to take the screen too — the 「运行时正在启动」 page the
    // user asked to delete. It no longer does: the chat page, its bottom bar and its
    // composer are drawn immediately. The one thing that had to be true for that to
    // be safe is that a message typed in that second is not lost; `send`,
    // `sendFollowUp` and `runPromptCommand` now park it in
    // `PiSessionViewModel.pendingPrompts` and replay it the moment a session
    // attaches, so the composer is usable before the engine exists.
    val boot = state.boot
    if (boot is Boot.Working || boot is Boot.Failed) {
        BootScreen(
            boot = boot,
            onRetry = { session.boot() },
            // The explicit repair path (`ensureReady(rebuild = true)`), reachable from
            // this one button and nowhere else. A plain retry never deletes anything.
            onRebuild = { session.boot(rebuild = true) },
            modifier = Modifier.padding(bottom = bottomInset),
        )
        return
    }
    ChatBody(
        state = state,
        session = session,
        onOpenSessions = onOpenSessions,
        onOpenWorkspace = onOpenWorkspace,
        onOpenSettings = onOpenSettings,
    )
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBody(
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
    onOpenSessions: () -> Unit,
    /** 见 [ChatScreen] 的 `onOpenWorkspace`：底栏拆掉之后对话页「⋯」里的两扇门。 */
    onOpenWorkspace: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 扩展渲染器的键：宿主让 App 重取渲染结果时 +1，三个 `remember(renderRev)` 靠它失效。
    // 拆文件之前这个值在 `ChatScreen` 里读；`ChatBody` 现在自己读，否则 2010 行附近那三个
    // `remember(renderRev)` 引用的就是不存在的东西（0.1.7.1 之后的编译红）。
    val renderRev by session.renderRev.collectAsStateWithLifecycle()
    // `draft` and `attachments` are the **ViewModel's** state, not this composable's:
    // `session.composerDraft` ([ComposerDraft]). Nothing about them reaches the Bundle.
    //
    // They were `rememberSaveable` here until that turned out to kill the app. The
    // staged images were saved as their base64 text (`AttachmentListSaver`, deleted
    // with that change), and one phone photo in the saved instance state is already
    // past Binder's ~1 MB transaction limit — so opening the picker for a *second*
    // image (`onSaveInstanceState` → Binder → `TransactionTooLargeException`) exited
    // the app. `ComposerDraft`'s KDoc has the whole chain.
    //
    // The behaviour D28/D30 asked for is kept, and kept better: leaving the chat
    // destination (工作区 / 设置) used to drop this composition entirely, and the
    // `SaveableStateHolder` in `PiRoot` was what gave the text and images back on the
    // way in. A ViewModel outlives the composition, so they are simply still there —
    // and a rotation is now safe as well.
    //
    // Deliberately **not** keyed on `sessionKey`, unlike `renderWindow` / `tail` /
    // `pausedRows` above: those describe an *opening of a session*, while a draft is
    // the user's unsent writing. This screen has never dropped the draft when the
    // session changed underneath it (the composable stays mounted for a switch), and
    // making it session-keyed would start silently deleting text on a switch.
    //
    // `state.workspace.revision` is the one exception the user ruled on (草稿就清空就行):
    // a workspace switch ends the session context the text was typed for — a new engine
    // process in a new directory — so a draft typed for the old workspace must not be
    // sendable to the new one. That rule now lives where the state lives
    // (`PiSessionViewModel.alignComposerToWorkspace`, called from
    // `publishWorkspaceState`), because a screen cannot clear state it does not own —
    // and it is `>` there for the reason it was `>` here: only a *switch* clears, a
    // revision that merely gets aligned (the startup reconcile) does not.
    var draft by session.composerDraft.text
    var attachments by session.composerDraft.attachments

    // 悬浮底部工作台与软键盘感知：
    // 软键盘升降由根 Column 的 imePadding() 同步承担（零 1 帧滞后）；键盘收起时聊天列表
    // 一路延展至屏幕最底端，输入框作为悬浮卡片叠在柔和渐隐羽化层之上，并始终通过
    // listBottomPad 保证末条消息完整停在输入框上方、零遮挡。
    val density = LocalDensity.current
    val navOnlyInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).exclude(WindowInsets.ime)
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val defaultDockHeightPx = with(density) { 128.dp.roundToPx() }
    var dockHeightPx by remember { mutableIntStateOf(defaultDockHeightPx) }
    val dockHeightDp = with(density) { dockHeightPx.toDp() }
    val listBottomPad = dockHeightDp + 12.dp
    val listBottomPadPx = dockHeightPx + with(density) { 12.dp.roundToPx() }
    var hiddenWidgetKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val hiddenWidgetSet = remember(hiddenWidgetKeys) { hiddenWidgetKeys.toSet() }
    var widgetsInspectorOpen by remember { mutableStateOf(false) }
    // One-shot overlays: a menu or a sheet that survives a trip to 工作区 would
    // reappear on a screen the user has moved on from, so these stay plain
    // `remember` on purpose.
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    // The image the user tapped, if any. It is a full-screen `Dialog`
    // (`PiImageViewer`) and therefore its own window: nothing here unmounts the
    // `LazyColumn`, so closing the viewer returns to the exact scroll position
    // without a single line of restoration logic.
    var viewedImage by remember { mutableStateOf<PiImage?>(null) }
    // The app-local display preferences, read from pi's settings documents. They
    // are preferences this screen actually obeys — before that they were rows
    // whose value nothing consulted (`PiSessionViewModel.UiPrefs`).
    val prefs = state.prefs
    // 模型 chip 的文字：先读 `get_state` 的 `meta.model`，再用 transcript 里最后一条
    // `model_change` 补洞。顶栏那颗 chip 挪到输入框工具行之后，这段读法原样搬到这里
    // —— 两个来源与之前完全一致，只是不再占顶栏宽度，而是喂给下面那颗 chip。
    //
    // `remember` 无条件调用、键带 `meta.model`：有 live 值时 lambda 立刻返回，不做
    // 反向扫描；没值时才走一次，且键在 transcript 上，行被原地改写时也不会读到旧值。
    val lastModelChange = remember(
        state.meta.model?.id,
        state.meta.model?.name,
        state.transcript,
    ) {
        if (state.meta.model?.id != null || state.meta.model?.name != null) {
            null
        } else {
            state.transcript.asReversed().firstNotNullOfOrNull { item ->
                (item as? ModelChange)?.modelId?.takeIf { it.isNotBlank() }
            }
        }
    }
    val modelLabel = state.meta.model?.id ?: state.meta.model?.name ?: lastModelChange
    // 悬浮输入框上方的渐隐要用的底色。在组合里读一次，交给 `drawBehind` 用 ——
    // `drawBehind` 的 lambda 不是 composable，不能在那里读 `MaterialTheme`。
    val pageBackground = MaterialTheme.colorScheme.background
    // 工具展开态：以 `app.tools.expandByDefault` 起步，同时与扩展 `ctx.ui.setToolsExpanded` 双向同步。
    var toolsExpanded by rememberSaveable(prefs.expandToolsByDefault) {
        mutableStateOf(prefs.expandToolsByDefault)
    }
    LaunchedEffect(state.ext.toolsExpanded) {
        state.ext.toolsExpanded?.let { toolsExpanded = it }
    }
    // 把输入框内容同步给宿主，确保扩展调用 `ctx.ui.getEditorText()` 读到真实文本。
    LaunchedEffect(draft) {
        session.syncEditorState(draft)
    }
    // 可交互工厂 widget（带 handleInput / handleMouse）点「交互」打开的底部表面。
    var interactingWidgetKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // 顶栏的 0.5dp 发丝线随列表离开顶部平滑显现（用 drawBehind 绘制，不改变顶栏高度，零视口抖动）。
    val topBarScrolled by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val context = LocalContext.current

    // 发出去之后把键盘收起来。
    //
    // `LocalSoftwareKeyboardController.current` is nullable: on a device with no
    // software keyboard at all (a hardware-keyboard tablet, or a preview) there is
    // nothing to hide, and the `?.` says so rather than demanding one. The focus
    // clear is the half that makes the state consistent — with the field still
    // focused, the next recomposition that touches it can raise the keyboard again,
    // and the cursor would sit blinking in a field the user has left. Tapping the
    // composer re-focuses it and the keyboard comes back on its own, which is the
    // existing behaviour and is not changed here.
    //
    // Only the paths that actually handed something to pi call this: a notice
    // (`ComposerRoute.Unknown`, or a name pi has but this palette does not list)
    // leaves both the draft and the keys alone, because the user still owns the text
    // it is complaining about.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismissKeys: () -> Unit = {
        keyboard?.hide()
        focus.clearFocus()
    }

    // pi's `tui.altScreen.previousPrompt` / `nextPrompt`
    // (`keybindings.md:112`): jump between the messages the *user* wrote. On a
    // phone there is no keybinding for it, so it lives in the overflow menu.

    // Attachments — one picker, two channels, chosen by what the user picked.
    //
    // pi has exactly **one** channel that carries bytes: `prompt`/`steer`/
    // `follow_up` take an `images` array (`rpc-types.ts:22-24`) whose wire shape is
    // inline base64 + MIME (`ImageContent`, `packages/ai/src/types.ts:367-371`,
    // `:456`; `docs/rpc.md:51-53`). There is no path and no size field, so an image
    // needs no guest file at all — `GuestImageBytes` resolves guest paths *into* the
    // app for markdown images, the opposite direction, and reusing it here would
    // invent a second channel. **Any other file has no byte channel at all**: pi's
    // `@file` is a CLI argument and RPC mode does not support it, so the phone's
    // equivalent of pi's terminal-side 「drop files to attach」 is to put the file
    // where the agent can read it — the session's own workspace — and hand it the
    // path in the prompt. That is what this picker does for everything that is not
    // an image.
    //
    // Picker: `ActivityResultContracts.GetContent()` (the SAF document picker) with
    // `*/*`. Chosen over the Android 13 Photo Picker because (a) it needs no storage
    // or media permission at all — the system grants a per-URI read to this app for
    // exactly the file the user picked, and (b) it is available on every API
    // level this app supports without a backport dependency, and covers file
    // providers as well as the gallery.

    // There is **no external-editor chip** any more. pi's `app.editor.external`
    // (ctrl+g, `keybindings.md:129` → `interactive-mode.ts:4246-4261` →
    // `modes/interactive/external-editor.ts:14-52`) writes the composer to a temp
    // file, spawns `$EDITOR` and reads it back **only on exit 0** — a command line
    // this platform does not have. The app mapped it onto an `ACTION_EDIT` handoff,
    // and on the user's phone nothing answers that intent, so the chip could only
    // ever say 「没有应用能编辑文本」. Deleted by decision **D19**: the chip, its
    // launcher, its `ACTION_EDIT` mapping and both notices are gone rather than left
    // as a button that reports its own uselessness.
    //
    // The pi setting key (`externalEditor`) is pi's own and is untouched: it
    // configures Ctrl+G inside pi's TUI, and it has no row in this app's settings
    // (`docs/settings-review.md` §9 removed the TUI-only keys).

    // Where a picked file is turned into an attachment. The picker callback itself
    // runs on the main thread, and both halves of the work — "load 6 MB and base64
    // it" for an image, "stream it into the workspace" for anything else — belong
    // off it.
    val pickerScope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var attachmentSheetOpen by remember { mutableStateOf(false) }

    // 统一处理单选/多选/剪贴板粘贴来的 URI：逐个在 IO 线程流式拷贝或按模型档位压缩，
    // 顺序累计本条消息的 base64 预算并遵守模型发布的 `inputLimits.images.maxPerMessage`。
    fun handlePickedUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        pickerScope.launch {
            val resolver = context.contentResolver
            val limits = AttachmentBudget.limitsFor(state.meta.model?.inputLimits)
            val maxPerMessage = state.meta.model?.inputLimits?.images?.maxPerMessage
            val autoResize = session.settingsStore.readBoolean("images.autoResize") ?: true
            var workingAttachments = attachments

            suspend fun appendPath(relativePath: String) {
                draft = if (draft.isBlank()) relativePath else "$draft $relativePath"
            }

            suspend fun reportCopyFailure(failure: WorkspaceCopy) {
                when (failure) {
                    WorkspaceCopy.Unreadable -> session.notifyUser(
                        tr(
                            "读不到这个文件：来源没有把它打开（权限被拒或文件已被删除）。请重新选择，或先把文件存到手机上再选。",
                            "Cannot read this file: the source could not open it (permission denied or file deleted). Try picking it again or save it locally first.",
                        ),
                        warning = true,
                    )
                    is WorkspaceCopy.OutOfSpace -> session.notifyUser(workspaceOutOfSpaceText(failure), warning = true)
                    WorkspaceCopy.WriteFailed -> session.notifyUser(
                        tr(
                            "写不进工作区（目录不可写，或写入过程中空间用尽）。这个文件没有被复制，也没有加进这条消息。",
                            "Could not write to workspace (directory not writable or disk full). The file was not copied or added to this message.",
                        ),
                        warning = true,
                    )
                    is WorkspaceCopy.Copied -> Unit
                }
            }

            suspend fun fallbackInline(uri: android.net.Uri, sourceUri: android.net.Uri?, bytes: ByteArray?, why: String) {
                val fallback = withContext(Dispatchers.IO) {
                    when {
                        sourceUri != null -> copyIntoWorkspace(context, sourceUri)
                        bytes != null -> copyBytesIntoWorkspace(context, bytes, displayNameOf(context, uri))
                        else -> WorkspaceCopy.WriteFailed
                    }
                }
                when (fallback) {
                    is WorkspaceCopy.Copied -> {
                        appendPath(fallback.relativePath)
                        session.notifyUser(inlineDowngradeText(fallback.relativePath, why))
                    }
                    else -> reportCopyFailure(fallback)
                }
            }

            for (uri in uris) {
                val mime = resolver.getType(uri).orEmpty()
                if (!mime.startsWith("image/")) {
                    val result = withContext(Dispatchers.IO) { copyIntoWorkspace(context, uri) }
                    when (result) {
                        is WorkspaceCopy.Copied -> appendPath(result.relativePath)
                        else -> reportCopyFailure(result)
                    }
                    continue
                }

                // 遵守模型声明的单条消息图片上限（`inputLimits.images.maxPerMessage`）：超额不丢弃，自动降级为工作区路径。
                if (maxPerMessage != null && workingAttachments.size >= maxPerMessage) {
                    fallbackInline(
                        uri = uri,
                        sourceUri = uri,
                        bytes = null,
                        why = tr(
                            "当前模型单条消息最多内联 $maxPerMessage 张图片",
                            "the current model allows at most $maxPerMessage inline images per message",
                        ),
                    )
                    continue
                }

                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.openInputStream(uri)
                            ?.use { readBounded(it, AttachmentBudget.MAX_PICKED_IMAGE_BYTES) }
                    }.getOrNull()
                }
                when {
                    bytes == null || bytes.isEmpty() -> session.notifyUser(
                        tr(
                            "读取所选图片失败：无法打开这个文件（权限被拒或文件已被删除）。请重新选择，或换一张本地图片。",
                            "Failed to read selected image: unable to open file (permission denied or deleted). Please pick another image.",
                        ),
                        warning = true,
                    )
                    bytes.size > AttachmentBudget.MAX_PICKED_IMAGE_BYTES -> fallbackInline(
                        uri = uri,
                        sourceUri = uri,
                        bytes = null,
                        why = tr(
                            "原图超过 ${mibLabel(AttachmentBudget.MAX_PICKED_IMAGE_BYTES)} MB，没有整张读进内存来压缩",
                            "original image exceeds ${mibLabel(AttachmentBudget.MAX_PICKED_IMAGE_BYTES)} MB and was not loaded into memory for resizing",
                        ),
                    )
                    else -> {
                        val image = withContext(Dispatchers.IO) {
                            compressAttachment(bytes, mime, limits, autoResize)
                        }
                        if (image == null) {
                            fallbackInline(
                                uri = uri,
                                sourceUri = null,
                                bytes = bytes,
                                why = if (autoResize) {
                                    tr(
                                        "按当前模型的上限（最长边 ${limits.maxWidth}×${limits.maxHeight}、" +
                                            "base64 ${mibLabel(limits.maxBase64Chars)} MB）压不出足够小的版本",
                                        "could not resize within the current model's limits (max ${limits.maxWidth}×${limits.maxHeight}, " +
                                            "base64 ${mibLabel(limits.maxBase64Chars)} MB)",
                                    )
                                } else {
                                    tr(
                                        "自动缩放关着（images.autoResize: false），这张图也不是 pi 能直接内联的格式，转换不出来",
                                        "auto-resize is off (images.autoResize: false) and this format could not be converted for inline sending",
                                    )
                                },
                            )
                        } else {
                            val staged = workingAttachments.map { it.base64.length }
                            when (val verdict = AttachmentBudget.decide(staged, image.base64.length)) {
                                AttachmentBudget.Verdict.Fits -> {
                                    workingAttachments = workingAttachments + image
                                    attachments = workingAttachments
                                }
                                is AttachmentBudget.Verdict.MessageFull -> fallbackInline(
                                    uri = uri,
                                    sourceUri = null,
                                    bytes = bytes,
                                    why = tr(
                                        "本条消息的内联额度不够（上限 ${mibLabel(verdict.limitBytes)} MB，" +
                                            "已用 ${mibLabel(verdict.usedBytes)} MB，这张" +
                                            (if (autoResize) "压缩后 " else "原图 ") +
                                            "${mibLabel(verdict.candidateBytes)} MB）",
                                        "not enough inline budget left in this message (limit ${mibLabel(verdict.limitBytes)} MB, " +
                                            "used ${mibLabel(verdict.usedBytes)} MB, this image " +
                                            (if (autoResize) "resized " else "original ") +
                                            "${mibLabel(verdict.candidateBytes)} MB)",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentBudget.MAX_PI_SIZED_IMAGES.coerceAtLeast(2)),
    ) { uris ->
        handlePickedUris(uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedUris(uris)
    }

    fun clipboardImageUri(): android.net.Uri? {
        val sysClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return null
        val clip = sysClipboard.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val u = clip.getItemAt(i)?.uri ?: continue
            val type = context.contentResolver.getType(u).orEmpty()
            if (type.startsWith("image/")) return u
        }
        return null
    }


    // Transcript search. pi has the feature in its fullscreen viewport
    // (`keybindings.md` `tui.altScreen.search`) and the GUI needs it more: a long
    // transcript on a phone has no other navigation. Matches are block-level, and
    // the current one is both scrolled to and outlined with pi's own search
    // tokens (`searchMatchBg`/`searchMatchText`), so the highlight is the theme's,
    // not an invented colour.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchCursor by rememberSaveable { mutableStateOf(0) }

    // When the match scan last ran, as a counter the `remember` below is keyed on.
    //
    // **Why the scan is not keyed on the transcript.** It used to be
    // (`remember(visibleItems, searchQuery, …)`), and `visibleItems` is a new list on
    // every publication — so while a turn streamed, the whole session was re-scanned and
    // every row's search text rebuilt once per token. Measured on a desktop JVM, one
    // pass over 800 rows (600 × 2 KB of prose plus 150 rows of 20 KB tool output) is
    // 110 ms; the old shape paid that per token, which is a frozen screen for as long as
    // the search bar is open.
    //
    // The query itself still scans at once: the `remember` below is keyed on it, so a
    // keystroke is answered in the frame it happens. What this counter adds is the
    // **streaming** half — a tick while rows keep arriving, and one final scan when the
    // transcript settles.
    var searchScan by remember { mutableIntStateOf(0) }
    val searchActive = searchQuery.isNotBlank()
    // Keyed on the two *edges* and never on the query's text: keying it on the query
    // would restart this effect per keystroke, and the leading scan the composition
    // already did would be paid a second time.
    LaunchedEffect(searchActive, state.streaming) {
        if (!searchActive) return@LaunchedEffect
        // A loop, not a `delay` per publication: a wait restarted by every token would
        // never fire while tokens arrive faster than the window, and the match count
        // would sit at `0 / 0` for the length of a long answer.
        while (state.streaming) {
            delay(SEARCH_RESCAN_MS)
            searchScan++
        }
        // The settled scan. It runs on both edges this effect is keyed on: when a turn
        // stops streaming (the ticks are cancelled mid-window, so rows that arrived since
        // the last one are not in the match list yet) and when the search first becomes
        // active. The second case is one redundant scan — the composition's own `remember`
        // has just run with the same query — and it is paid deliberately, because the
        // alternative is to track "was this instance streaming" and get the turn's last
        // window wrong in the case that matters.
        searchScan++
    }

    // 扩展的 `custom` 条目只在扩展注册了 `registerEntryRenderer` 时可见（pi：没渲染器就不添加任何子组件）。
    val entryRenderable by session.entryRenderable.collectAsStateWithLifecycle()
    val visibleItems = remember(state.transcript, prefs.showTimestamps, entryRenderable) {
        state.transcript.filter { item ->
            when (item) {
                is DateSeparator -> prefs.showTimestamps
                is app.pi.protocol.ExtensionEntry -> item.customType in entryRenderable
                else -> true
            }
        }
    }
    // The ViewModel's coarse tool clock (`UiState.nowMs`): what every 「已运行 N 秒」 in the
    // transcript is measured against, ticking once a second **only** while a tool card is
    // pending (predicate: `UiState.hasPendingToolClock`). Read as a plain value beside the
    // other `state.*` reads: `state` is a value parameter, so nothing in this function
    // subscribes to it — the subscription is `ChatScreen`'s `collectAsState`, and this
    // function already recomposes whenever that value changes (it reads `transcript`,
    // `revision`, `streaming`). What the clock adds is one extra recomposition per second
    // while a command runs, and its absence of change at rest is why it costs nothing then:
    // the value is null and never moves.
    val nowMs = state.nowMs
    // F34 / spec §4.5: a long session opens on its last
    // [TRANSCRIPT_WINDOW_STEP] rows and grows that window upwards in the same steps.
    // This is a **rendering** window only — the reducer keeps every row, which is the
    // whole reason "load earlier" can be local. pi cannot answer the question this
    // window asks: `get_entries` is documented with a forward-only `since` cursor
    // (`packages/coding-agent/src/modes/rpc/rpc-types.ts:65`,
    // `modes/rpc/rpc-mode.ts:638-648`), so no command returns the *earlier* half of a
    // session, and inventing one would be begging pi for a protocol it does not have.
    // (The spec's parenthetical "`get_entries` 天然支持增量" is only true forwards.)
    //
    // The window belongs to **one opening of one session**, which is why its
    // `rememberSaveable` input is the session's identity:
    //
    //  - switching, forking, cloning or starting a session rebinds in place and the
    //    ViewModel re-reads `get_state` (`PiSessionViewModel.afterSessionReplaced`), so
    //    `sessionFile`/`sessionId` change — a changed input discards the saved value and
    //    re-runs the initialiser, and session B opens on its own last 50 rows instead of
    //    inheriting session A's expanded window. Without this, "打开时先渲染最后 50 条"
    //    (spec §4.5) would be true only for the first session of the process.
    //  - inside one opening the key is **constant**: `refreshState` runs on attach and
    //    after every `agent_settled`, but it writes the same `sessionId`/`sessionFile`
    //    those calls already returned, and pi answers `get_state` with an id for an
    //    in-memory session too — so there is no null-then-id transition to reset a
    //    window the user has grown. Even so, only growth would be lost, never rows.
    //  - a configuration change (rotation) keeps the same key, so the window the user
    //    built up is *restored* rather than reset — that is what `rememberSaveable`
    //    rather than plain `remember` buys here.
    val sessionKey = state.meta.sessionFile ?: state.meta.sessionId
    var renderWindow by rememberSaveable(sessionKey) { mutableStateOf(TRANSCRIPT_WINDOW_STEP) }
    // A window the user opened **all the way** — by tapping 「回到顶部」, which goes to
    // row 0 and therefore has to materialise every row above the window — stays open.
    // Without this flag the window would be a *count* (`renderWindow`) while the
    // transcript only grows, so the next streamed row would re-hide itself behind a
    // fresh「加载更早的 1 条」row and the user would be looking at the top of the
    // conversation with the newest text hidden from them. `reArmTail()` closes it
    // again, so following the tail costs the last-50 window exactly as before.
    var windowOpen by rememberSaveable(sessionKey) { mutableStateOf(false) }
    val renderedItems = remember(visibleItems, renderWindow, windowOpen) {
        if (windowOpen) visibleItems else visibleItems.takeLast(renderWindow)
    }
    // The same number `renderedItems` implies, but computed from the *window* rather
    // than from the list it produced: the anchor restore below needs to know how many
    // rows sit above a given row without having to build that list
    // (`TailFollow.hiddenRows`, pinned by the `tail-follow` harness).
    val hiddenCount = hiddenRows(visibleItems.size, renderWindow, windowOpen)
    // While anything is above the rendered window the loading row is item 0, so a
    // full-list index is a rendered index plus `hiddenCount` plus that one row — the
    // arithmetic every jump/reveal below relies on.
    //
    // The second term is why this is not just `hiddenCount > 0`: the same row is also
    // where "the transcript holds everything loaded, but the session file has more
    // above it" is stated. `earlierRowText` is the one owner of what that row says (and
    // of whether it exists at all): it distinguishes a count already in memory, a read
    // in flight, a read that could not happen — with its reason — and the two states in
    // which there is nothing to offer. It answers null exactly when the reader is
    // holding the file's first entry, so the screen can never offer to load earlier when
    // the reader believes it is at the start. See `ui/HistoryRetention.kt`.
    val earlierText = earlierRowText(
        hiddenCount = hiddenCount,
        cursorKnown = state.history != null,
        loading = state.history?.loading == true,
        reachedStart = state.history?.reachedStart == true,
        stop = state.history?.stop,
    )
    val showsEarlierRow = earlierText != null
    val searchMatches = remember { mutableStateOf(SearchHits.None) }
    // Published-scan counter: the reveal effect below is keyed on it rather than on the
    // result object, so it re-runs when a scan *lands* (not while one is running) and
    // never has to compare two multi-thousand-element lists.
    var searchMatchesSeq by remember { mutableIntStateOf(0) }
    // **The scan itself runs off the frame thread.**
    //
    // It used to be a `remember(searchQuery, hideThinkingBlock, searchScan)` — a
    // synchronous pass over the whole loaded transcript, taken *during composition*.
    // The pass is `searchHits` over every row, and the dominant term is the
    // case-insensitive `contains` over each tool card's output: measured on this
    // device's JVM against the real `TranscriptItem`s, 500 rows (375×2 KB prose +
    // 125×20 KB tool output) cost a median **80.6 ms** (min 32.3 ms) per pass. The
    // streaming tick below re-runs it every `SEARCH_RESCAN_MS` (200 ms) while a turn
    // is in flight, so with the search bar open the frame thread was busy ~40 % of
    // the time in 80 ms slabs — every scroll taken during a stream dropped frames.
    //
    // The same pass on `Dispatchers.Default`, published into snapshot state when it
    // lands, costs the UI nothing: the frame thread only reads the result. The
    // answer is identical (the items are immutable data classes and the list is
    // captured at launch); what changes is that it appears one dispatch later
    // instead of in the frame that asked for it — a frame or two, invisible next to
    // the 80 ms it used to block with.
    //
    // It deliberately does **not** compete with the frame thread's own markdown
    // parses: those run on the same `Dispatchers.Default` pool, but the pool is as
    // wide as the device's cores and the alternative is blocking the only thread
    // that can draw. The last key (`searchScan`) is the streaming/settle tick.
    LaunchedEffect(searchQuery, prefs.hideThinkingBlock, searchScan) {
        val items = visibleItems
        val query = searchQuery
        val hideThinking = prefs.hideThinkingBlock
        val hits = if (query.isBlank()) {
            SearchHits.None
        } else {
            // `withContext` re-checks cancellation on the way back, so a superseded
            // scan (a new keystroke, a new tick) can never publish over a newer one.
            withContext(Dispatchers.Default) { scanSearchHits(items, query, hideThinking) }
        }
        searchMatches.value = hits
        searchMatchesSeq++
    }
    LaunchedEffect(searchQuery) { searchCursor = 0 }

    // pi's `tui.altScreen.previousPrompt` / `nextPrompt` (`keybindings.md:112`):
    // jump between the messages the *user* wrote. Computed over the same list the
    // LazyColumn renders, so a hidden day separator cannot shift the target row.
    //
    // F34: derived **on demand** rather than per composition. It is an O(n) scan of
    // the whole session and it is read only when one of the two overflow entries is
    // tapped, so deriving it here meant paying that scan on every streamed token.
    // The result is unchanged: it is still the full list that is scanned, which also
    // keeps the jumps working across rows the window has not rendered yet.
    fun userRowIndices(): List<Int> =
        visibleItems.mapIndexedNotNull { index, item -> if (item is UserMessage) index else null }

    // §4.8's 编辑并从此分叉, hoisted out of the item lambda and given a **stable
    // identity** on purpose.
    //
    // It was written inline in the `itemsIndexed` content lambda, where it captured
    // `visibleItems` — a list the reducer replaces whenever a row changes. Under the
    // Compose compiler's strong skipping (on by default since Kotlin 2.0.20; this
    // tree is 2.2.21) a lambda with unstable captures is memoized **keyed on those
    // captures by instance equality**, so a new `visibleItems` produced a new
    // lambda, `BlockRenderer`'s parameter was
    // never equal, and every visible `UserMessage` row re-executed its whole block
    // on every publication — the 「发完消息、回答在流式输出时滑动发顿」 half that
    // has nothing to do with layout. Reading the list through
    // `rememberUpdatedState` at *call* time instead of capturing it keeps the
    // lambda (and therefore the row's skippability) stable, while the callback
    // still sees the current list because it is read when it runs.
    //
    // The other two callbacks are already stable under the same rule: `onBranchClick`
    // captures `session` (one instance for the ViewModel's life) and `onImageClick`
    // captures the `viewedImage` state object, not its value.
    val latestVisibleItems = rememberUpdatedState(visibleItems)
    val onForkFromMessage: (String) -> Unit = remember(session) {
        { key ->
            // The order that is reliable lives here: a bubble drawn for a prompt sent
            // in this run carries a synthetic key, so the row's position among the user
            // rows and its text are read from the full list (not just the rendered
            // window) and handed to the ViewModel, which matches them against
            // `get_fork_messages` — pi's own list of legal fork points.
            val items = latestVisibleItems.value
            val row = items.firstOrNull { it is UserMessage && it.key == key } as? UserMessage
            session.forkFromMessage(
                key = key,
                ordinal = userMessageOrdinal(items, key),
                text = row?.text.orEmpty(),
            )
        }
    }

    // F34: a jump target may sit in the part of the session the window has not
    // rendered, and `LazyListState` cannot be asked for an index the current item list
    // does not have. So a jump is two-phased: grow the window until the target is the
    // *first* rendered row, then scroll from an effect that runs once that list
    // exists. `hiddenCount` then equals the target's full index, which is why the
    // second phase can compute its index from state rather than from a captured one.
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    // The pixel offset that goes with [pendingJump]. Every *navigation* jump lands its
    // target at the top of the viewport (offset 0, pi's own reveal — see the consumer
    // below); the one caller that needs a real offset is the anchor restore
    // ([anchorKey]), which is putting back the exact position the reader left rather
    // than navigating to a row. A separate slot instead of an extra parameter on
    // `reveal` keeps the four navigation call sites unchanged.
    var pendingJumpOffset by remember { mutableIntStateOf(0) }
    fun reveal(row: Int) {
        val needed = visibleItems.size - row
        if (needed > renderWindow) renderWindow = needed
        pendingJumpOffset = 0
        pendingJump = row
    }

    // ---------------------------------------------------------------- follow the tail
    //
    // Spec §4.5: follow the newest block by default, and **never steal the scroll**
    // once the user has scrolled up — pause the follow and show a "back to newest"
    // affordance instead. The rules themselves live in `ui/chat/TailFollow.kt`, a
    // Compose-free state machine pinned by `TailFollowCheck` on a bare JVM; what is
    // left here is the reading of `LazyListState` and the one call that acts on the
    // decision.
    //
    // What drives the follow, and why it is **data** and never layout.
    //
    // Spec §4.5 wants three things: follow the newest block by default, never steal the
    // scroll once the user has moved the viewport, and re-arm from the 「回到最新」
    // affordance. The rules themselves live in `ui/chat/TailFollow.kt` — Compose-free,
    // pinned by `TailFollowCheck` on a bare JVM — and what is left here is reading
    // `LazyListState` once per trigger and calling the one primitive that acts on the
    // decision (`requestScrollToItem`).
    //
    // **The trigger is the part that has to be right.** It used to be a long-lived
    // `snapshotFlow { listState.layoutInfo … }`, chosen so the effect could not be
    // starved per token the way `LaunchedEffect(state.revision, …)` with a suspending
    // `scrollToItem` could. But an effect that
    // *observes the layout* and then *requests a scroll* is an effect whose own output
    // can write its input: `requestScrollToItem` ends in a measure pass, a measure pass
    // writes `LazyListLayoutInfo`, and that write is what the observation was watching.
    // Convergence then rests on "the geometry stops changing" — a claim about a
    // measurement rather than about this code — and a remeasure loop on the frame
    // thread is exactly the reported 「卡住不动 / 没有响应」.
    //
    // So the trigger is data. The keys below move on a publication (once per streamed
    // event), on a row being added, on the user's own gestures, on a session change and
    // on an inset change; **a scroll or a measure cannot move any of them.** The body
    // may request a scroll, and a scroll cannot change a key, so this effect cannot
    // re-enter itself. That is the termination argument, in one sentence.
    //
    // The user's hand is still observed, but narrowly: one `snapshotFlow` over
    // `isScrollInProgress` *alone*. That input cannot be written by anything this code
    // calls — `requestScrollToItem` starts no scroll session (`TailFollow`'s KDoc, from
    // `LazyListState`'s own source) — so, unlike the old observation, this one cannot
    // see its own effect.
    //
    // **But the collector no longer pauses the follow by itself.** Pausing is a decision
    // about a *movement* (the anchor went backwards) and only the machine can make it:
    // an `isScrollInProgress` that a layout pass started (an optimistic row landing, an
    // inset change, a fling settling) is not the user's hand, and a pause written from
    // it could not be taken back — which is exactly the reported 「明明就在底部发消息，
    // 发完就不跟随」. The collector keeps `scrolling` as a *key* so the machine re-decides
    // on both edges of a session, and only mirrors the row count into `pausedRows` while
    // the machine is still deciding; the machine's own `TailDecision.following` is what
    // writes the state.
    //
    // **The machine is the only owner of `following`.** It is written by a `TailDecision`
    // (and by `pauseTail()`/`reArmTail()` for the explicit navigation and "go to newest"
    // actions, which set their own machine flags); the effect that feeds it is therefore
    // **not keyed on `following`**, or its own write would cancel and restart it. It must
    // also never return early while paused: a machine that is not observed cannot learn
    // that the user scrolled back to the end, which is the second half of the same report.
    //
    // `rememberSaveable(sessionKey, ...)`: the rules belong to one opening of one
    // session, and a rotation must not resurrect a paused follow as "keep following"
    // (`LazyListState` restores its own position, so the next token would yank a reader
    // who had scrolled up into history back to the bottom).
    val tail = rememberSaveable(sessionKey, saver = TailFollowSaver) { TailFollow() }
    var following by remember { mutableStateOf(tail.following) }
    // The transcript's size when the follow was paused: the count on the affordance is
    // then a subtraction from data already in hand, instead of a per-frame reading of
    // the list (which is what the machine's own `unseenRows` used to be fed by). Saved
    // across rotation, like the window, so the pause the user comes back to keeps its
    // count.
    var pausedRows by rememberSaveable(sessionKey) { mutableStateOf(0) }
    // Bumped by the affordance and by a send: an explicit "go to the newest" must pin
    // even when no other key would have moved.
    var tailPoke by remember { mutableLongStateOf(0L) }
    // The *whole* transcript's row count, not the rendered window's: the count must
    // include rows the window has not materialised. `rememberUpdatedState` because the
    // gesture observer below is started once and would otherwise capture the first
    // composition's value forever.
    val transcriptRows by rememberUpdatedState(state.transcript.size)
    val unseenRows = if (following) 0 else (transcriptRows - pausedRows).coerceAtLeast(0)

    fun pauseTail() {
        tail.pause()
        following = false
        pausedRows = transcriptRows
    }

    fun reArmTail() {
        tail.reArm()
        following = true
        pausedRows = transcriptRows
        tailPoke++
    }

    /** Stamp the unread counter's baseline without touching the machine. */
    fun markPausedRows() {
        pausedRows = transcriptRows
    }

    // The user's hand, mirrored into snapshot state: the follow effect needs the session's
    // two edges as keys, and the "load earlier" rule below needs a session as a key too.
    // Started once; `collect` sees the current value first, and every later `true` is a
    // drag or a fling — this code starts no scroll session (`requestScrollToItem` is not
    // one, and the two jumps below use it for exactly that reason).
    var scrolling by remember { mutableStateOf(false) }

    // `!canScrollForward` — the end test `TailFollow` uses (`TailViewport.atBottom`), and
    // for the same reason: it is true exactly when the last row's bottom is inside the
    // viewport, including the tail of a row taller than the viewport. Declared here
    // because it is a *key* of the follow effect: the moment the user's own scroll reaches
    // the end is the moment a paused follow has to be observed again so it can re-arm.
    val atBottom by remember(listState) {
        derivedStateOf { !listState.canScrollForward }
    }

    // 触底跟随核心信号：通过长生命周期 snapshotFlow + conflate() 连续观测真实布局与流式更新。
    // 不再用 `LaunchedEffect(state.revision, ...)`——高频 token 到达时重启协程会打断 `withFrameNanos`，
    // 且旧版在流式期间禁用了末行高度监听导致 Markdown 异步排版撑高后漏钉。
    // 现在流式增长、Markdown 异步落地、图片解码、软键盘升降、扩展面板展开/收起都走同一条非阻塞观测链。
    val latestRevision by rememberUpdatedState(state.revision)
    val latestRenderedCount by rememberUpdatedState(renderedItems.size)
    val latestStreaming by rememberUpdatedState(state.streaming)
    LaunchedEffect(listState, sessionKey) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val scrollingNow = listState.isScrollInProgress
            val atBottomNow = !listState.canScrollForward
            // 仅在用户手指真正拖动（scrollingNow）时订阅首行坐标：
            // `requestScrollToItem` 会同步写入 `firstVisibleItemScrollOffset`（未等 layoutInfo 重测），
            // 若在非拖动期订阅该字段，会在同一帧内反复唤醒 snapshotFlow 造成主线程死循环 ANR！
            TailObservation(
                revision = latestRevision,
                renderedCount = latestRenderedCount,
                poke = tailPoke,
                totalItems = info.totalItemsCount,
                firstVisibleIndex = if (scrollingNow) listState.firstVisibleItemIndex else -1,
                firstVisibleOffsetPx = if (scrollingNow) listState.firstVisibleItemScrollOffset else 0,
                lastVisibleIndex = last?.index ?: -1,
                lastVisibleOffsetPx = last?.offset ?: 0,
                lastVisibleSizePx = last?.size ?: 0,
                viewportEndOffsetPx = info.viewportEndOffset,
                afterContentPaddingPx = info.afterContentPadding,
                isScrollInProgress = scrollingNow,
                atBottom = atBottomNow,
            )
        }.conflate().collect { obs ->
            val wasFollowing = following
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val decision = tail.onSnapshot(
                TailSnapshot(
                    transcriptRows = transcriptRows,
                    poke = obs.poke,
                    viewport = TailViewport(
                        totalItems = info.totalItemsCount,
                        firstVisibleIndex = listState.firstVisibleItemIndex,
                        firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset,
                        lastVisibleIndex = last?.index ?: -1,
                        lastVisibleOffsetPx = last?.offset ?: 0,
                        lastVisibleSizePx = last?.size ?: 0,
                        viewportEndOffsetPx = info.viewportEndOffset,
                        bottomContentPaddingPx = info.afterContentPadding.takeIf { it > 0 } ?: listBottomPadPx,
                        isScrollInProgress = listState.isScrollInProgress,
                        atBottom = !listState.canScrollForward,
                    ),
                ),
            )
            following = decision.following
            if (wasFollowing && !decision.following) pausedRows = transcriptRows
            val pin = decision.pin
            if (pin != null && !listState.isScrollInProgress) {
                listState.requestScrollToItem(pin.index, pin.offsetPx)
                // 在触发滚动请求之后让出一帧，确保 Compose 完成真实测量与输入事件派发，
                // 既保持触底零滞后，又从结构上彻底杜绝同帧自激死循环与 ANR。
                withFrameNanos { }
            }
        }
    }

    // The user's hand, mirrored into snapshot state: the "load earlier" rule below needs
    // a scroll session as a *key*, and the follow effect needs the session's two edges.
    // Started once; `collect` sees the current value first, and every later `true` is a
    // drag or a fling — this code starts no scroll session (`requestScrollToItem` is not
    // one, and the two jumps below use it for exactly that reason).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            scrolling = inProgress
            // The gesture that pauses the follow is the machine's to make (see above), but
            // the unread count has to start from the row the user left. Stamping it on the
            // session's rising edge is early by one frame at most, and is what keeps the
            // badge honest if the machine's own pause is a frame later.
            if (inProgress && following) markPausedRows()
        }
    }
    // Spec §4.5: "向上滚动时分批加载更早的 entry". Reaching the top grows the window by
    // one step. The `earlierArmed` guard is what keeps that from looping: while the
    // user stays at the top the flag is cleared by the load itself, and it is re-armed
    // once they scroll away — after a prepend, `LazyColumn` keeps the row they were
    // looking at anchored by key, so "still at the top" means the same batch would
    // otherwise be requested again on the next frame.
    //
    // The flag has a **second** arming edge, and it is what keeps a collapsed transcript
    // from wedging: a viewport that cannot scroll forward at all (`reArmsEarlier`). Short
    // rows — collapsed tool cards are exactly that — make the list shorter than the screen,
    // `atTop` is then true for ever, and the original single edge could never fire again:
    // one batch was prepended and `hiddenCount` stayed above zero permanently, which also
    // gated off the session-file read (`hiddenCount == 0`). See `reArmsEarlier`'s KDoc.
    val atTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }
    // A key of the rule below: entering or leaving "the list cannot scroll" is precisely
    // when the second arming edge becomes true, and nothing else about the list changes
    // when it happens.
    val canScrollForward by remember(listState) {
        derivedStateOf { listState.canScrollForward }
    }
    // (`atBottom` is declared above, next to the follow effect that is keyed on it.)
    var earlierArmed by rememberSaveable(sessionKey) { mutableStateOf(false) }
    // Spec §4.5's second half. The client-side window below only re-reveals rows the
    // **transcript already holds**, and what the transcript holds used to be the whole
    // session (one `get_entries`), so the two were the same list. It now starts as the
    // newest window of the session *file* and grows backwards on demand, which means
    // reaching the top of it is no longer the same as reaching the start of the
    // conversation. This is what asks for more: `history.hasEarlier` is the reader's own
    // position (`HistoryCursor.startOffset`), not an inference from the row count, so it
    // stops exactly when the file's first entry has been loaded — and, since the row is
    // the only way to ask again, a step that delivered nothing (a range that could not be
    // read) clears it too, so a failed read cannot become a retry loop. That second half
    // is why the row's own visibility is `HistoryCursor.showsEarlierRow` rather than
    // `hasEarlier`: a failure still has to be on screen to say what happened.
    val earlierHistory = state.history

    // ------------------------------------------------- the reader's place, by row key
    //
    // **What was wrong without this.** `rememberLazyListState()` restores its position
    // through `LazyListState.Saver`, which saves **`firstVisibleItemIndex` and
    // `firstVisibleItemScrollOffset` and nothing else** (verified against
    // `foundation-android:1.8.3`'s bytecode: the saver is
    // `listOf(getFirstVisibleItemIndex, getFirstVisibleItemScrollOffset)`). An index is
    // only a position while the *item list* is the same list — and this list is a
    // **suffix** of the transcript (`renderedItems = visibleItems.takeLast(renderWindow)`).
    // Every row the engine publishes while the user is on 工作区 / 设置 moves the
    // content under that index towards the newest row: come back and the same index is
    // `g` rows further along, where `g` is how many rows arrived. Measured with
    // `/tmp/probe/scroll/WindowAnchorProbe.kt` (group A): `g = 3 → 3 rows`, `g = 40 →
    // 40 rows`, `g = 250 → 250 rows`. At the bottom the suffix window makes the
    // *relative* index invariant, which is why the symptom is "sometimes".
    //
    // The fix is to save the **key of the row the reader is on** next to the offset, and
    // to put that row back when the item list has changed underneath the position. It
    // also covers the two head-side disturbances the same window arithmetic causes
    // (both in `docs/scroll-diagnosis.md` §1.2): the session-file read inserts rows at
    // the *transcript* head, which re-cuts the suffix window and adds up to
    // `renderWindow - rows` rows *between* the sentinel and the reading position (P2),
    // and the sentinel itself disappears when a read starts (`hasEarlier` is false while
    // `HistoryCursor.loading`) or when the last batch lands, which shifts every index by
    // one (`headerRows`).
    //
    // The anchor is **not** the row at index 0 when index 0 is the sentinel — that row's
    // key means "the 加载更早 row", not "the message the reader is on"; the tracker below
    // therefore records the first *content* row (`index - headerRows`), and skips the
    // frame where that is negative. Keeping that row pinned is what `prependAnchoredIndex`
    // already does for the client-side batch, and what the file read and the destination
    // switch were missing.
    var anchorKey by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
    var anchorOffset by rememberSaveable(sessionKey) { mutableIntStateOf(0) }
    // 分支跳转（/tree navigate）或会话替换后，清空旧分支阅读锚点并重新触底到新分支末尾。
    LaunchedEffect(state.transcriptResetSeq) {
        if (state.transcriptResetSeq > 0L) {
            anchorKey = null
            anchorOffset = 0
            windowOpen = false
            reArmTail()
        }
    }
    // False until the correction below has run once for this composition. The tracker
    // must not write before that: on the frame a destination is re-entered, the position
    // it would read is the *restored, already drifted* one, and recording that would
    // destroy the very value the correction needs. (The two effects are launched in the
    // same apply-changes batch and this is what makes their order irrelevant.)
    val anchorSettled = remember { mutableStateOf(false) }
    // Read by the tracker at the moment it fires, not captured at composition: the
    // tracker outlives every individual item list.
    val anchorWindow by rememberUpdatedState(renderedItems)
    // The correction. Keyed on the three numbers that describe the *shape* of the item
    // list — how many rows are hidden, whether the sentinel is there, and how big the
    // loaded list is — rather than on the item list itself. `visibleItems.size` is a key
    // on purpose: while the reader is paused and a turn streams in, an append can push
    // their row out of the suffix window entirely, and that is the live version of the
    // same drift (the destination-switch case is only the most visible one). A pure
    // content update costs one early return (the guard below runs first) plus, while
    // paused, one `indexOfFirst` over the loaded list. It never runs for a position it
    // does not own — a gesture in flight, a navigation jump, or a following tail.
    LaunchedEffect(hiddenCount, visibleItems.size) {
        // The user's hand, a reveal/search jump, or the follow: all three own the
        // position, and one of them is where the reader asked to be.
        if (listState.isScrollInProgress || following || pendingJump != null) {
            anchorSettled.value = true
            return@LaunchedEffect
        }
        val key = anchorKey
        if (key != null) {
            val visibleRow = visibleItems.indexOfFirst { it.key == key }
            if (visibleRow >= 0) {
                val index = itemIndexOfVisibleRow(
                    visibleRow = visibleRow,
                    visibleRows = visibleItems.size,
                    renderWindow = renderWindow,
                    windowOpen = windowOpen,
                    headerRows = 0,
                )
                if (index < 0) {
                    // The row is in the hidden prefix: enough rows arrived that the suffix
                    // window no longer reaches their row. Grow the window until it is the
                    // window's head, and let the deferred consumer below land on it — that
                    // is what computes the index in the *new* list's coordinates
                    // (`reveal`'s two-phase trick, without the navigation).
                    renderWindow = maxOf(renderWindow, visibleItems.size - visibleRow)
                    pendingJumpOffset = anchorOffset
                    pendingJump = visibleRow
                } else if (index != listState.firstVisibleItemIndex) {
                    listState.requestScrollToItem(index, anchorOffset)
                }
            }
        }
        anchorSettled.value = true
    }
    // The tracker: remember which row the viewport is parked on, so the next re-entry
    // has a key to restore. Written only on a real change, so a settled screen writes
    // nothing; and only after the correction above has had its one shot.
    LaunchedEffect(listState) {
        var firstEmission = true
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // The first value a `snapshotFlow` reads is the state as it is *now*,
                // which on the frame a destination is re-entered is the restored (and
                // possibly drifted) position — never record it.
                if (firstEmission) {
                    firstEmission = false
                    return@collect
                }
                if (!anchorSettled.value) return@collect
                val key = anchorWindow.getOrNull(index)?.key
                if (key != null && key != anchorKey) anchorKey = key
                if (offset != anchorOffset) anchorOffset = offset
            }
    }

    // ------------------------------------------------- rows that must not arrive at zero height
    //
    // `RowHeightCache`/`rememberedRowHeight` restore the height of a row that has been
    // measured *before* it was composed again (a destination switch, a scroll back into
    // known content). A row a batch has just brought in has never been measured, so there is
    // no height to restore — and it is the one that matters most: a row entering the viewport
    // from **above** (which is what 「加载更早」 hands the reader) pushes every visible row down
    // by its own height on the frame its markdown parse lands. That is P3 of
    // `docs/scroll-diagnosis.md` §1.2.
    //
    // The only way to have that height on the first frame is to have the content ready on the
    // first frame, which is the library's `immediate = true` (`LocalPiMarkdownImmediate`).
    // This is the gate that makes paying for it bounded:
    //
    //  - the rows the *window* has just gained at its head — the client batches, the session
    //    file read (whose rows the window picks up when it is re-cut from the tail), the two
    //    navigation paths that grow it (`reveal`, the anchor restore). One effect computes
    //    them all from the one thing they have in common: `renderedItems.size` grew, so the
    //    rows `[hiddenCount, hiddenCount + gained)` are the ones that just became visible;
    //  - only while the transcript is **not streaming**: a row published mid-turn arrives at
    //    the *bottom*, where its height correction is off-screen, and marking it would make
    //    every streamed tool card parse synchronously for nothing;
    //  - and each row at most once: `RowHeightCache` is the latch (the item lambda asks only
    //    when there is no remembered height), so a row that has been measured — including one
    //    that scrolls out and back — never asks again.
    //
    // The set is bounded by `FRESH_ROW_KEYS_MAX` keys and never accumulates with the session.
    var freshRowKeys by remember(sessionKey) { mutableStateOf<Set<String>>(emptySet()) }
    var lastRenderedRows by remember(sessionKey) { mutableIntStateOf(renderedItems.size) }
    LaunchedEffect(renderedItems.size, hiddenCount, state.streaming) {
        val gained = renderedItems.size - lastRenderedRows
        lastRenderedRows = renderedItems.size
        if (gained <= 0 || state.streaming) return@LaunchedEffect
        val keys = ArrayList<String>(minOf(gained, FRESH_ROW_KEYS_MAX))
        for (i in hiddenCount until minOf(hiddenCount + gained, visibleItems.size)) {
            keys += visibleItems[i].key
        }
        if (keys.isEmpty()) return@LaunchedEffect
        // `freshRowKeysAfter` owns the trim and its bound (`ui/chat/TailFollow.kt`, where the
        // harness pins it): the keys the window has just gained are the rows whose first
        // layout must not be a zero-height one, and the set may not grow with the session.
        freshRowKeys = freshRowKeysAfter(freshRowKeys, keys, FRESH_ROW_KEYS_MAX)
    }
    // True for the first composition of a *restored* screen (a destination switch, a
    // rotation): its visible rows have remembered heights but no parsed content yet, so
    // without this the reader gets a column of correctly-sized **blank** rows for a frame.
    // Best-effort on purpose — the height cache is what makes the geometry correct; this only
    // removes the blank frame. It is a `remember`, so a fresh composition starts it again.
    var restoring by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        restoring = false
    }

    LaunchedEffect(atTop, canScrollForward, hiddenCount, scrolling, earlierHistory) {
        // Two arming edges, one rule: away from the top, or a viewport with nowhere left to
        // scroll (a transcript shorter than the screen — see `reArmsEarlier`). Armed *and*
        // at the top is the only state that loads.
        //
        // `mayArmEarlier` and not `reArmsEarlier`: a `LazyListState` reports
        // `canScrollForward == false` until its **first measure** (`mutableStateOf(false)`
        // in its constructor, bytecode again), so on the first frame of every re-entered
        // destination the second edge is true for a reason that has nothing to do with the
        // content — see that function's KDoc.
        if (mayArmEarlier(atTop, canScrollForward, listState.layoutInfo.totalItemsCount > 0)) {
            earlierArmed = true
        }
        if (!atTop) return@LaunchedEffect
        // Asked for only once the loaded rows are exhausted: while `hiddenCount > 0`
        // the batch below is a slice of rows already in memory, and starting a file
        // read at the same moment would do both jobs for one gesture.
        if (hiddenCount == 0 && earlierHistory != null && earlierHistory.hasEarlier) {
            session.expandEarlierHistory()
        }
        // `mayLoadEarlier` is the whole fix for 「用力往旧消息方向一划就跳到最顶部」: a
        // batch may only be prepended once the gesture is over. Prepending *while a
        // fling is running* grows the list in the direction the fling is travelling,
        // so the fling never reaches an end and one flick walks the whole session.
        if (!mayLoadEarlier(atTop, earlierArmed, hiddenCount, scrolling)) return@LaunchedEffect
        earlierArmed = false
        renderWindow += TRANSCRIPT_WINDOW_STEP
        // **No position request here any more.** While 「加载更早」 was the list's item 0,
        // a batch had to be compensated for by hand: the sentinel kept its key at index 0,
        // so the `LazyColumn`'s own key anchoring held *it* still while the whole batch was
        // inserted under it. Now that the row lives outside the list, the viewport's first
        // item is a real transcript row whose key survives the prepend, and the anchoring
        // does exactly what this call used to do by hand — with the padding rather than the
        // viewport top as the reference (the old call landed the row at the content start,
        // which is where it already was).
        //
        // `prependAnchoredIndex` is gone with it: the sentinel leaving the list is what
        // `docs/scroll-diagnosis.md` §3.4 called the only way to remove the last few tens of
        // dp of movement, and it removes the function's whole reason to exist.
    }
    // Keyed on the **published-scan counter**, the cursor and nothing else: the list is
    // rebuilt by every scan, and a `LaunchedEffect` keyed on it would compare two
    // thousand-element lists with `equals` on every recomposition. The counter moves
    // exactly when a scan *lands* (see the scan effect above), which is also the moment
    // this reveal has a fresh answer to act on — the old code was keyed on the tick
    // that *started* a scan, so it could reveal against the previous scan's list.
    LaunchedEffect(searchMatchesSeq, searchCursor) {
        val matches = searchMatches.value.ordered
        val index = matches.getOrNull(searchCursor.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
        if (index != null) {
            // Jumping to a match is navigation, so following stops until the user
            // asks for the newest block again — pi's `disableFollow` semantics, which
            // keep it stopped even if the hit is in the last row
            // (`packages/tui/src/components/scroll-view.ts:131`, the search reveal at
            // `tui-alt-screen.ts:636`).
            pauseTail()
            reveal(index)
        }
    }
    LaunchedEffect(pendingJump, renderedItems.size, hiddenCount) {
        val row = pendingJump ?: return@LaunchedEffect
        val index = itemIndexOfVisibleRow(
            visibleRow = row,
            visibleRows = visibleItems.size,
            renderWindow = renderWindow,
            windowOpen = windowOpen,
            headerRows = 0,
        )
        if (renderedItems.isNotEmpty() && index in 0 until renderedItems.size) {
            // A direct position change, not `animateScrollToItem`: the jump is a
            // navigation, and an animation is a *scroll session*, which the follow
            // machine would read as the user's own hand and which would clear the
            // "this was navigation" suppression that keeps a reveal to the last row
            // from re-arming the follow. pi's own reveal is a direct `scrollTo`
            // (`tui-alt-screen.ts:636` → `scroll-view.ts:127`).
            //
            // The offset is `pendingJumpOffset`, which every navigation leaves at 0
            // (`reveal` resets it) and the anchor restore sets to the pixel offset the
            // reader left — the one caller for which "put the target at the top" is not
            // the same as "put the reader back where they were".
            listState.requestScrollToItem(index, pendingJumpOffset)
            pendingJump = null
            pendingJumpOffset = 0
        }
    }

    // `set_editor_text`: an extension owns the composer content until the user
    // types again, so the fill is applied once and then consumed. Consuming is
    // load-bearing — this effect is keyed on the sequence, but leaving and
    // re-entering the destination re-runs it, and a stale fill would silently
    // overwrite whatever the user typed in the meantime.
    val messageActions = remember(session, state.streaming, state.transcript.lastOrNull()) {
        app.pi.ui.chat.MessageActions(
            quote = session::quoteIntoComposer,
            continueFrom = session::continueFromMessage,
            label = session::labelMessage,
            hideFromContext = session::hideMessageFromContext,
            streaming = state.streaming,
            retryKey = (state.transcript.lastOrNull() as? app.pi.protocol.ErrorText)?.key?.takeIf { !state.streaming },
            retry = session::retryLastPrompt,
        )
    }
    val themePalette = PiTheme.palette
    // 键是「引擎在不在」而不是引擎状态本身：Busy/Ready 每个回合都在切，推色板会让宿主重绘全部表面。
    val engineUp = state.engine == app.pi.engine.PiEngineSession.EngineState.Ready ||
        state.engine == app.pi.engine.PiEngineSession.EngineState.Busy
    LaunchedEffect(themePalette, engineUp) {
        if (engineUp) {
            session.pushExtensionPalette(themePalette.toExtensionPalette())
        }
    }

    val fill = state.composerFill
    LaunchedEffect(fill?.seq) {
        if (fill != null) {
            if (!(fill.onlyIfEmpty && draft.isNotBlank())) {
                draft = if (fill.append) draft + fill.text else fill.text
            }
            session.consumeComposerFill(fill.seq)
        }
    }

    // 上下文用量现已直接由 SDK Host 的 `state` 快照流实时推给 `PiSessionViewModel`（`stateStore`），
    // 不再需要旧 RPC 时代的 `delay(700)` 延迟补读 `get_session_stats`。

    // The `@` mention list. pi looks its candidates up on every keystroke of the
    // token, debounced by 20 ms (`packages/tui/src/components/editor.ts:251`); here
    // each lookup is a guest process (see `ui/chat/PiMentionSource.kt`), so the
    // debounce is deliberately longer. That delays the list, it does not change what
    // the list contains: the query that runs is the one whose result is still wanted,
    // because `session.requestMentions` drops every superseded answer.
    val mentionPrefix = PiFileMentions.prefixOf(draft)
    // 命令名写完（出现空格）之后：给用法提示，扩展声明了补全的就去要候选。
    val argContext = remember(draft, state.commands) { argumentContextOf(draft, state.commands) }
    LaunchedEffect(argContext?.command?.name, argContext?.prefix) {
        val ctx = argContext
        if (ctx == null || !ctx.command.completes) {
            session.dismissArgCompletions()
        } else {
            delay(ARG_COMPLETION_DEBOUNCE_MS)
            session.requestArgCompletions(ctx.command.name, ctx.prefix)
        }
    }
    LaunchedEffect(mentionPrefix) {
        if (mentionPrefix == null) {
            session.dismissMentions()
        } else {
            delay(MENTION_DEBOUNCE_MS)
            session.requestMentions(mentionPrefix)
        }
    }
    // 扩展通过 `ctx.ui.addAutocompleteProvider` 注册的自动补全：在 `/` 命令、`@` 提及未占屏时自动防抖查询。
    val extAutocompleteEligible = state.ext.autocompleteCount > 0 &&
        draft.isNotEmpty() &&
        paletteQueryOf(draft) == null &&
        argContext == null &&
        mentionPrefix == null
    LaunchedEffect(draft, extAutocompleteEligible) {
        if (!extAutocompleteEligible) {
            session.dismissExtensionAutocomplete()
        } else {
            delay(ARG_COMPLETION_DEBOUNCE_MS)
            session.requestExtensionAutocomplete(draft)
        }
    }

    // The follow itself is the single `LaunchedEffect(listState)` above; nothing
    // below this line decides anything about it, except the two jumps and the FAB
    // that *pause* and *re-arm* it explicitly.

    // `/import <path.jsonl>` (`interactive-mode.ts:6107-6119`). pi's TUI asks for a
    // path in its editor; a phone has no path to type, so the palette row opens the
    // document picker instead, and the ViewModel copies the picked file into pi's
    // session directory and switches to it. Unconditional, as Compose requires of
    // `rememberLauncherForActivityResult`, and declared before `pick` uses it.
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // A null uri is the user backing out of the picker, not a failure.
        if (uri != null) session.importSession(uri)
    }

    // Where the export row's two delivery calls run. Both touch the filesystem
    // (read the artifact; MediaStore/binder for the sink), so neither belongs on the
    // frame thread; the scope only decides *that* they run, not which thread.
    val exportScope = rememberCoroutineScope()

    // A command the palette offers: pi's own dispatch rule (`agent-session.ts`
    // `prompt` → extension command → skill → template) for anything pi owns, and
    // the app's native implementation for pi's built-ins, which pi's TUI also
    // implements client-side (`interactive-mode.ts` `setupEditorSubmitHandler`).
    fun perform(action: ChatAction, command: PiSlashCommand? = null, args: String = "") {
        when (action) {
            ChatAction.Prompt -> command?.let { session.runPromptCommand(it, args) }
            ChatAction.PickFork -> {
                session.refreshForkMessages()
                sheet = ChatSheet.Fork
            }
            ChatAction.CloneSession -> session.cloneSession()
            // `/export <path>`: pi picks the writer from the extension
            // (`interactive-mode.ts:6062-6066`), and so does the ViewModel.
            ChatAction.ExportSession -> session.exportSession(args.takeIf { it.isNotBlank() })
            ChatAction.CopyLastAssistant -> copyLastAssistant(session, context)
            // `/name <名字>` renames outright, exactly as pi does
            // (`interactive-mode.ts:6193-6207`); the dialog is for the bare `/name`,
            // where there is nothing to apply yet. The argument was silently dropped
            // here — twice, in fact, because the first fix was lost in a later
            // rewrite of this function — so it is worth saying out loud: this arm
            // reads `args`.
            ChatAction.RenameSession -> if (args.isNotBlank()) {
                session.renameSession(args)
            } else {
                sheet = ChatSheet.Rename
            }
            ChatAction.Compact -> session.compact(args.takeIf { it.isNotBlank() })
            // `/import`: pi takes a path argument, a phone takes a picked document.
            // Any argument is ignored — there is no path to type — and the picker's
            // cancel is the "cancelled" answer rather than an error.
            // pi's `/import <path.jsonl>` takes a path (`interactive-mode.ts:6108`).
            // This app's import cannot honour one: `prepareImport`
            // (`PiSessionViewModel.kt`) reads a picked **document** through
            // `contentResolver.openInputStream`, and a typed path names a file in the
            // *guest*, which the app's own reader has no entry point for. So the
            // picker opens — and the argument is **said to be ignored** rather than
            // dropped in silence, which is the one option that is never acceptable.
            ChatAction.ImportSession -> {
                if (args.isNotBlank()) {
                    session.notifyUser(
                        tr(
                            "本应用改用文件选择器导入，参数「$args」被忽略：请在选择器里挑那个 .jsonl 文件。",
                            "This app imports via the file picker; argument \"$args\" was ignored. Please select the .jsonl file in the picker.",
                        ),
                    )
                }
                importPicker.launch("*/*")
            }
            // 以下都是 pi 的内建命令在手机上的做法：打开对应的界面，或直接执行。每一项都和
            // 「⋯」菜单 / 顶栏里的同名入口走同一个函数，不另起一套。
            ChatAction.OpenSettings -> {
                when (command?.name) {
                    "scoped-models" -> session.requestNav(NavRequest.SettingsFocus("hub:scoped-models"))
                    "login" -> session.requestNav(
                        NavRequest.SettingsFocus(
                            if (args.isNotBlank()) "hub:models:${args.trim()}" else "hub:models",
                        ),
                    )
                    "logout" -> session.requestNav(NavRequest.SettingsFocus("hub:models"))
                    "trust" -> session.requestNav(NavRequest.SettingsFocus("hub:resources:project"))
                    else -> {
                        command?.let { CLIENT_BUILTINS[it.name]?.note }?.let { session.notifyUser(it) }
                        onOpenSettings()
                    }
                }
            }
            ChatAction.OpenTerminal -> session.requestNav(NavRequest.Terminal)
            // `/model <引用>`：精确匹配直接切，否则开选择器并预填（interactive-mode.js handleModelCommand）。
            ChatAction.PickModel -> if (args.isNotBlank()) {
                session.refreshModels()
                session.runModelCommand(args.trim()) { sheet = ChatSheet.Model }
            } else {
                session.seedModelPicker("")
                session.refreshModels()
                sheet = ChatSheet.Model
            }
            ChatAction.CycleModel -> session.cycleModel()
            // `/thinking <level>`：对齐官方 interactive-mode.ts，带参数直接切等级，无参数开选择器。
            ChatAction.PickThinking -> {
                val wanted = args.trim().lowercase()
                if (wanted.isNotEmpty()) {
                    val levels = state.meta.thinkingLevels
                    val matched = levels.firstOrNull { it.equals(wanted, ignoreCase = true) }
                    if (matched != null) {
                        session.setThinkingLevel(matched)
                    } else if (levels.isEmpty()) {
                        session.setThinkingLevel(wanted)
                    } else {
                        session.notifyUser(
                            tr(
                                "无效思考等级「$args」，当前模型支持：${levels.joinToString(", ")}",
                                "Invalid thinking level \"$args\". Available: ${levels.joinToString(", ")}",
                            ),
                            warning = true,
                        )
                    }
                } else {
                    session.refreshThinkingLevels()
                    sheet = ChatSheet.Thinking
                }
            }
            ChatAction.OpenTree -> session.requestNav(NavRequest.SessionTree)
            ChatAction.SessionStats -> {
                session.refreshStats()
                sheet = ChatSheet.Stats
            }
            ChatAction.NewSession -> session.newSession()
            ChatAction.OpenSessions -> onOpenSessions()
            ChatAction.ReloadResources -> session.reloadResources()
            ChatAction.BugReport -> session.generateBugReport(args.takeIf { it.isNotBlank() }) { text ->
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                session.notifyUser(
                    tr(
                        "问题报告已复制（${text.length} 字），可以直接粘贴发给 pi 开发者。",
                        "Bug report copied (${text.length} chars). You can paste it directly to share with pi developers.",
                    ),
                )
            }
            ChatAction.ContextUsage -> {
                session.refreshStats()
                sheet = ChatSheet.Context
            }
            ChatAction.ToolsSheet -> sheet = ChatSheet.Tools
            ChatAction.ViewSystemPrompt -> sheet = ChatSheet.SystemPrompt
            ChatAction.Unsupported ->
                session.notifyUser(unsupportedReason(command?.name.orEmpty()), warning = true)
        }
    }

    /** 命令面板点了一行 / 手敲了 `/name args`。 */
    fun pick(command: PiSlashCommand, args: String) = perform(command.action, command, args)

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---------------------------------------------------------------- 顶栏
        //
        // 用户 2026-10-26 的裁决（截图里的五个问题）：56 高、页面同色不透明、默认没有
        // 分隔线（滚动后才出现 0.5dp 发丝线）；左侧是「状态点 + 会话标题 + ▾」一行，
        // 不再有第二行「✓ 就绪」；右侧是搜索 + 工作区 + ⋮（24dp 字形、48dp 热区）。
        // 模型 chip 已经移到输入框工具行，标题因此拿得到完整会话名的宽度；标题拿不到
        // 名字时才退回 18sp 的「会话」。
        //
        // 仍然手画而不是 `material3.TopAppBar`：M3 的高度/容器色/自带行为都不是这里要的，
        // 而这个标题槽不是普通字符串 —— 它是打开会话列表的入口（`03-navigation-decision.md`）。
        ChatTopBar(
            engineLabel = session.engineLabel(state),
            sessionTitle = state.meta.sessionName?.takeIf { it.isNotBlank() },
            workspaceName = state.workspace.displayName.takeIf { it.isNotBlank() },
            searchOpen = searchOpen,
            scrolled = topBarScrolled,
            toolsExpanded = toolsExpanded,
            terminalRunningCount = app.pi.ui.terminal.PiTerminalManager.runningCount,
            onOpenSessions = onOpenSessions,
            onOpenWorkspace = onOpenWorkspace,
            onOpenTerminal = { session.requestNav(NavRequest.Terminal) },
            onToggleSearch = { clearOnClose ->
                searchOpen = !searchOpen
                if (clearOnClose && !searchOpen) searchQuery = ""
            },
            onJumpQuestion = { forward ->
                val firstFull = listState.firstVisibleItemIndex + hiddenCount
                val target = if (forward) {
                    userRowIndices().firstOrNull { it > firstFull }
                } else {
                    userRowIndices().lastOrNull { it < firstFull }
                }
                target?.let { row ->
                    pauseTail()
                    reveal(row)
                }
            },
            onToggleTools = {
                val next = !toolsExpanded
                toolsExpanded = next
                RowHeightCache.shared.clear()
                session.setToolsExpandedRemote(next)
            },
            onAction = { perform(it) },
        )

        // v2's `ChatShell` puts the search strip **between the TopBar and the status
        // row** (`direction-b-v2.html:1366-1367`: `{p.searchBar}` then `{StateLine}`),
        // because it is chrome for the bar above it rather than a row of the
        // transcript's header — the app used to draw it under the extension status row.
        androidx.compose.animation.AnimatedVisibility(
            visible = searchOpen,
            enter = androidx.compose.animation.expandVertically(tween(170)) + fadeIn(tween(150)),
            exit = androidx.compose.animation.shrinkVertically(tween(140)) + fadeOut(tween(120)),
        ) {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = searchMatches.value.ordered.size,
                cursor = searchCursor,
                onPrevious = {
                    val count = searchMatches.value.ordered.size
                    if (count > 0) {
                        searchCursor = (searchCursor - 1 + count) % count
                    }
                },
                onNext = {
                    val count = searchMatches.value.ordered.size
                    if (count > 0) {
                        searchCursor = (searchCursor + 1) % count
                    }
                },
                onClose = {
                    searchOpen = false
                    searchQuery = ""
                },
            )
        }

        // The transcript's **status row is gone** (user ruling, decision D23): a
        // permanent 32 dp band of figures above the stream spends display area the
        // transcript wants, and the same numbers now live one tap away in the
        // composer's context ring (a small used / window bubble; the full sheet is
        // still in the ⋮ menu as 「上下文与用量」).
        //
        // The row component went with it: `PiStatusLine` had no caller left, so it is
        // deleted; the detail sheet draws the same figures in its own layout.
        val emptyTranscript = state.transcript.isEmpty() && state.busy == null


        // 全高聊天视口：一路铺到屏幕最底端（含底部手势条区域），悬浮工作台叠于柔和渐隐羽化层之上。
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (emptyTranscript) {
                ChatEmptyWelcome(
                    workspaceName = state.workspace.displayName,
                    modelLabel = modelLabel,
                    onOpenWorkspace = onOpenWorkspace,
                    onOpenModel = { perform(ChatAction.PickModel) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = dockHeightDp),
                )
            } else {
            val blockSpacing = prefs.typographyProfile.blockSpacing
            val earlierRowHeightValue = earlierRowHeight(earlierText.orEmpty())
            val earlierBand = if (showsEarlierRow) earlierRowHeightValue + blockSpacing else 0.dp
            val sharedToolRenderer = remember(renderRev, session) {
                app.pi.ui.extension.ToolRenderer { item, expanded -> session.toolRenderer.render(item, expanded) }
            }
            val sharedEntryRenderer = remember(renderRev, session) {
                app.pi.ui.extension.EntryRenderer { item, expanded -> session.entryRenderer.render(item, expanded) }
            }
            val sharedMessageRenderer = remember(renderRev, session) {
                app.pi.ui.extension.MessageRenderer { item, expanded -> session.messageRenderer.render(item, expanded) }
            }
            val sharedThinkingLabel = remember(state.ext.thinkingLabel) {
                state.ext.thinkingLabel?.let { app.pi.ui.extension.chromeText(it).trim() }?.takeIf { it.isNotEmpty() }
            }
            val onBranchClick: (app.pi.protocol.BranchSummary) -> Unit = remember(session) {
                { session.requestNav(NavRequest.SessionTree) }
            }
            val onImageClick: (PiImage) -> Unit = remember {
                { viewedImage = it }
            }
            CompositionLocalProvider(
                app.pi.ui.extension.LocalToolRenderer provides sharedToolRenderer,
                app.pi.ui.extension.LocalEntryRenderer provides sharedEntryRenderer,
                app.pi.ui.extension.LocalMarkdownTransform provides session.markdownTransform,
                app.pi.ui.extension.LocalMessageRenderer provides sharedMessageRenderer,
                app.pi.ui.chat.LocalMessageActions provides messageActions,
                app.pi.ui.blocks.LocalThinkingLabel provides sharedThinkingLabel,
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PiSpacing.pageHorizontal,
                    end = PiSpacing.pageHorizontal,
                    top = 10.dp + earlierBand,
                    bottom = listBottomPad,
                ),
                verticalArrangement = Arrangement.spacedBy(blockSpacing),
            ) {
                // The 「加载更早」 row is the overlay below, not an item here — see the block
                // above for why, and for the three things that had to stay identical.
                //
                // Keyed by the reducer's stable per-block key, which is what lets
                // Compose animate the row that changed while streaming instead of
                // recomposing the list.
                //
                // F33: without a `contentType` the
                // `LazyColumn` cannot reuse a slot when the block kind changes, so
                // scrolling a long mixed session re-inflates every row it passes.
                // The item types are already distinct sealed subtypes, which is
                // exactly the granularity the slot table reuses on, so the class is
                // the content type — no new taxonomy needed.
                itemsIndexed(
                    renderedItems,
                    key = { _, item -> item.key },
                    contentType = { _, item -> item::class },
                ) { sliceIndex, item ->
                    // F34: the search highlights and the prompt jumps speak full-list
                    // indices, and both the window prefix and the loading row sit in
                    // front of this item — translate once, here, so everything below
                    // keeps using `index` as before.
                    val index = sliceIndex + hiddenCount
                    // `present` is a `Set`: this line runs once per rendered row on every
                    // frame the list composes, and the old `List<Int>.contains` was a
                    // linear scan of every match in the session.
                    val isMatch = searchMatches.value.present.contains(index)
                    val isCurrentMatch = isMatch && searchMatches.value.ordered.getOrNull(searchCursor) == index
                    // This row's markdown has finished parsing — the library's own
                    // `State.Success`, reported up from `PiMarkdownText` through
                    // `LocalPiMarkdownParsed`. It is what releases the height floor
                    // `rememberedRowHeight` holds a freshly composed row at: "the parse
                    // landed" rather than "enough frames passed" or "the content happened to
                    // measure something", which is what makes the release exact. Both are
                    // per-row state, keyed on the row's own key so a recycled item slot
                    // cannot inherit the previous row's answer.
                    val markdownParsed = remember(item.key) { mutableStateOf(false) }
                    val onMarkdownParsed: () -> Unit = remember(markdownParsed) { { markdownParsed.value = true } }
                    // 消息级间距（换人时大、同一条消息内部小）：这一行属于哪一档由
                    // `transcriptGap` 按**列表顺序**判定，理由在
                    // `ui/render/TranscriptSpacing.kt`（实时那条路没有可剥的 key 前缀）。
                    //
                    // 只补**差值**：列表自己的 `spacedBy(blockSpacing)` 一格不动，换人那一行
                    // 在自己头顶多留 `间距 − blockSpacing`。这样 a) 同一条消息内部逐像素不变；
                    // b) 经典档三段都是 8 → 差值 0 → 连 padding 节点都不进链，等于今天。
                    //
                    // 加在**搜索高亮之外**（链的最外层），两个理由：高亮框不该把这段留白也
                    // 涂上；而 `rememberedRowHeight` 的 `onSizeChanged` 在它里面，所以
                    // `RowHeightCache` 记下的仍然是**内容**高度 —— 与今天同一个数。
                    // 行的实际高度是「留白 + max(内容, 记忆高度)」，留白是这一行自己的确定
                    // 值，所以不会出现"看着 8dp、测出来另一个数"。间距因此**在测量之内**
                    // （`LazyColumn` 量的是带 padding 的节点），也**不在缓存之内**。
                    val extraTop = (
                        prefs.typographyProfile
                            .gapFor(transcriptGap(visibleItems, hiddenCount + sliceIndex)) - blockSpacing
                        ).coerceAtLeast(0.dp)
                    val highlightModifier = when {
                        isCurrentMatch -> Modifier
                            .border(1.dp, PiTheme.palette.searchMatchText, PiShapes.cardInner)
                            .background(PiTheme.palette.searchMatchBg, PiShapes.cardInner)

                        isMatch -> Modifier.background(PiTheme.palette.searchMatchBg, PiShapes.cardInner)
                        else -> Modifier
                    }
                    val rowModifier = (if (extraTop > 0.dp) Modifier.padding(top = extraTop) else Modifier)
                        .then(highlightModifier)
                        // The height this row had before, for the frames of a *fresh*
                        // composition of it: markdown's parse state is built with a plain
                        // `remember`, so a row that leaves and comes back (a destination
                        // switch, a scroll past it and back) is composed at zero height and
                        // grows when the parse lands — and every row below it moves with it.
                        // A floor, not a size, released by the parse itself: see
                        // `ui/render/TranscriptRowHeight.kt` (and `RowHeightCache`'s bound).
                        .rememberedRowHeight(item.key, contentReady = markdownParsed.value)
                    // The list no longer computes anything about a row's neighbours: the
                    // execution rail's two ends were the only thing it read them for, and
                    // the rail is gone (the state glyph moved into a tool card's own
                    // header — `ui/blocks/ToolRail.kt`). A tool card is therefore the same
                    // shape wherever it sits, which is also why no row's height depends on
                    // what precedes or follows it.
                    // Whether this row's markdown must be parsed before its first layout:
                    // see `PiMarkdownImmediate.kt` for the defect, and the block above for
                    // the gate. `RowHeightCache` is the latch — a row that has already been
                    // measured never asks again, so the synchronous parse happens at most
                    // once per row — and streaming text never asks: its content changes on
                    // every token, and that parse belongs off the frame thread.
                    //
                    // The last term is the **frame budget**: `piMarkdownParseBudget` is billed
                    // with the measured cost of every eager parse this frame (`PiMarkdown.kt`
                    // times the `Markdown(state = …)` call), and when a frame has spent its
                    // 4 ms the rest of its new rows take the asynchronous path. So a crowd of
                    // expensive rows is thinned out while a lone row is unaffected — the
                    // common case — and no row's fate is decided by its character count.
                    val streamingRow = (item as? AssistantText)?.streaming == true ||
                        (item as? ThinkingBlock)?.streaming == true
                    // A row entering from ABOVE the first visible one (fast upward fling over
                    // rows never measured: a long user bubble with code blocks, say) must be
                    // parsed before its first layout and is exempt from the frame budget.
                    // Otherwise it is laid out at zero height, the fling runs through it, and
                    // when the parse lands it grows by its own height and the viewport jumps
                    // by that much. Read without subscribing: the list state moves every frame.
                    val enteringFromAbove = androidx.compose.runtime.snapshots.Snapshot
                        .withoutReadObservation { sliceIndex <= listState.firstVisibleItemIndex }
                    val itemCharCount = when (item) {
                        is AssistantText -> item.text.length
                        is UserMessage -> item.text.length
                        is ThinkingBlock -> item.text.length
                        else -> 0
                    }
                    // 超长消息（>8000 字）绝不豁免 4ms 帧预算，避免快速上滑长会话时主线程同步解析卡死 ANR。
                    val allowSyncFromAbove = enteringFromAbove && itemCharCount <= 8_000
                    val immediateMarkdown = !streamingRow &&
                        RowHeightCache.shared.of(item.key) == null &&
                        (item.key in freshRowKeys || restoring || allowSyncFromAbove) &&
                        (allowSyncFromAbove || piMarkdownParseBudget.allow(System.nanoTime()))
                    // The row's own place in the list, for a card that is about to change
                    // its height (see `TranscriptRowAnchor`). `following` is handed over as
                    // a lambda: an armed follow re-pins to the end on every height change
                    // (pi's `updateLayout` does the same,
                    // `scroll-view.ts:189-201`), so the two must not both own the position.
                    // Read at click time, which is the only time it matters.
                    val rowAnchor = remember(listState, sliceIndex, item.key) {
                        TranscriptRowAnchor(
                            listState = listState,
                            index = sliceIndex,
                            isFollowing = { following },
                            onPauseFollow = { pauseTail() },
                            onEvictHeight = { RowHeightCache.shared.evict(item.key) },
                            // 静止态展开任意卡、或流式态展开非末尾卡时立即暂停跟随，绝不把用户刚点开的卡拖走。
                            shouldPauseOnExpand = { !latestStreaming || sliceIndex < latestRenderedCount - 1 },
                        )
                    }
                    CompositionLocalProvider(
                        LocalTranscriptRowAnchor provides rowAnchor,
                        LocalPiMarkdownImmediate provides immediateMarkdown,
                        LocalPiMarkdownParsed provides onMarkdownParsed,
                    ) {
                        BlockRenderer(
                            item = item,
                            modifier = rowModifier,
                            hideThinking = prefs.hideThinkingBlock,
                            thinkingDefaultExpanded = !prefs.thinkingCollapsedByDefault,
                            toolsDefaultExpanded = toolsExpanded,
                            showBilledCost = prefs.showCacheMissNotices,
                            showTimestamps = prefs.showTimestamps,
                            onBranchClick = onBranchClick,
                            onForkFromMessage = onForkFromMessage,
                            onImageClick = onImageClick,
                            // 仅把 1 Hz 粗时钟交给正在运行中的工具卡，避免每秒触发整屏已完成 Markdown/代码卡重组。
                            nowMs = if (item is ToolCall && item.status == ToolStatus.Pending) nowMs else null,
                        )
                    }
                }
            }
            }
            // 「加载更早」 — the same row, drawn *outside* the list so that inserting a batch
            // cannot move it and cannot move the reader (the block above has the mechanism
            // and the three things that stay identical). Its position is the list's own
            // geometry, read at layout time (`Modifier.offset`'s lambda runs there, so this
            // costs no recomposition per frame): one band above the list's first item, which
            // is exactly where the item was.
            if (earlierText != null) {
                val bandPx = with(LocalDensity.current) { earlierBand.roundToPx() }
                EarlierRowsRow(
                    text = earlierText,
                    onClick = {
                        if (hiddenCount > 0) {
                            renderWindow += TRANSCRIPT_WINDOW_STEP
                        } else {
                            // The press is also the retry after a failed reading: the
                            // cursor keeps `reachedStart` false and carries the reason,
                            // so this is the reader asking again, not the effect looping.
                            session.expandEarlierHistory()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                            IntOffset(0, if (first == null) -bandPx else first.offset - bandPx)
                        }
                        .padding(horizontal = PiSpacing.pageHorizontal),
                )
            }

            // Spec §4.5's affordance, in its second shape. It used to be one bordered
            // pill reading 「回到最新 · N」; the user's ruling was 「弄得小一点，只剩一个
            // 箭头，半透明一点，现在太遮挡视线了。弄成上下两个箭头」, so it is now two
            // round arrow buttons — one to the first row, one to the newest — and the
            // count moved into a 5 dp badge, which is the board's own badge swatch
            // (`06-v2-construction-reference.md` §「徽标」: 色块 5×5 圆角 1). v2 has no
            // floating-button row, so this borrows the pill's exact tokens rather than
            // inventing any: `surfaceContainerHigh` ground, a `borderMuted` hairline,
            // no elevation (`direction-b-v2.html:1510`, and `04 §2.3`: 层级不用阴影).
            //
            // **When each is shown.** Up: once the reader is roughly two screens
            // away from the first row — approximated by 2× the number of rows the
            // viewport currently shows, so a small nudge does not summon it. Down:
            // when there is something below **or** the follow is paused — the second
            // half matters, because a navigation that lands on the last row
            // deliberately keeps the follow off (`TailFollow.pause`, pi's
            // `disableFollow`), and hiding the button there would leave no way to
            // re-arm it. A viewport that is at the bottom *and* following shows
            // neither, which is the state the transcript is in most of the time.
            // 「回到顶部」：带迟滞的开关。原来是 `firstVisibleItemIndex >= 可见行数 * 2` 的单阈值，
            // 而「可见行数」会随流式增长的末行变高而抖（末行越高，同屏行数越少），首行下标
            // 又在阈值附近，于是按钮在跟随期间一帧出、一帧没。现在：**越过 2 倍屏才出现，
            // 回落到 1 倍屏以内才消失**，中间这一段不换状态，抖动被带宽吃掉。
            var showUp by remember(listState) { mutableStateOf(false) }
            LaunchedEffect(listState) {
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size
                }.collect { (first, visible) ->
                    if (visible > 0) {
                        showUp = if (showUp) first >= visible else first >= visible * 2
                    }
                }
            }

            // 底部柔和渐隐羽化层：消除输入框色带上方半毫米的硬切遮罩感，
            // 同时在全面屏底部手势条区域留出呼吸感、柔化滑过的历史文字而不显凌乱。
            val fadeFeather = PiSpacing.composerGradientFade
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(dockHeightDp + fadeFeather)
                    .drawBehind {
                        val featherPx = fadeFeather.toPx().coerceAtMost(size.height)
                        val stopFeather = if (size.height > 0f) (featherPx / size.height).coerceIn(0.06f, 0.85f) else 0.18f
                        val stopLower = (stopFeather + (1f - stopFeather) * 0.48f).coerceIn(stopFeather, 0.98f)
                        val featherAlpha = if (imeVisible) 0.90f else 0.72f
                        val lowerAlpha = if (imeVisible) 0.97f else 0.86f
                        val bottomAlpha = if (imeVisible) 1.00f else 0.90f
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to pageBackground.copy(alpha = 0f),
                                stopFeather to pageBackground.copy(alpha = featherAlpha),
                                stopLower to pageBackground.copy(alpha = lowerAlpha),
                                1f to pageBackground.copy(alpha = bottomAlpha),
                            ),
                        )
                    },
            )

            val showDown = !following && (canScrollForward || unseenRows > 0)
            androidx.compose.animation.AnimatedVisibility(
                visible = showUp || showDown,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut() + scaleOut(targetScale = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = dockHeightDp + 10.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (showUp) {
                        ScrollArrowButton(
                            icon = Icons.Filled.KeyboardArrowUp,
                            label = tr("回到顶部", "Scroll to top"),
                            onClick = {
                                pauseTail()
                                windowOpen = true
                                reveal(0)
                            },
                        )
                    }
                    if (showDown) {
                        ScrollArrowButton(
                            icon = Icons.Filled.KeyboardArrowDown,
                            label = tr("回到最新", "Jump to latest"),
                            unreadCount = unseenRows,
                            onClick = {
                                windowOpen = false
                                reArmTail()
                            },
                        )
                    }
                }
            }
            }

            if (topBarScrolled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(PiSpacing.chatTopBarFade)
                        .drawBehind {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        pageBackground,
                                        pageBackground.copy(alpha = 0f),
                                    ),
                                ),
                            )
                        },
                )
            }

            // 瞬时自动补全浮层（`/` 命令、`@` 文件、参数、扩展补全）：悬浮在底部工作台正上方，不挤动列表滚动位置
            ChatTransientPalettes(
                state = state,
                session = session,
                draft = draft,
                onDraft = { draft = it },
                mentionPrefix = mentionPrefix,
                onPickCommand = { command, args ->
                    dismissKeys()
                    pick(command, args)
                },
                argContext = argContext,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = dockHeightDp + 4.dp),
            )

            // 悬浮底部工作台：透明底叠在柔和羽化层之上；高度同步反馈给 listBottomPad，末条消息永远零遮挡。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        if (size.height > 0 && size.height != dockHeightPx) {
                            dockHeightPx = size.height
                        }
                    }
                    .windowInsetsPadding(navOnlyInsets),
            ) {
                val hubShortcuts = remember(state.extensionShortcuts) {
                    state.extensionShortcuts.mapNotNull { sc ->
                        val key = sc.shortcut.trim()
                        if (key.isEmpty()) return@mapNotNull null
                        ExtensionHubShortcut(
                            key = key,
                            label = key,
                            description = sc.description?.takeIf { it.isNotBlank() },
                        )
                    }
                }
                val firstInteractivePanel = remember(state.ext.widgets, state.ext.header, state.ext.footer) {
                    (state.ext.widgets + listOfNotNull(state.ext.header, state.ext.footer))
                        .firstOrNull { it.interactive && it.surfaceId != null }
                }
                val extDefaultLabel = tr("扩展", "Ext")
                val keysDefaultLabel = tr("快捷", "Keys")
                val extensionChip = remember(
                    state.ext.statuses,
                    state.ext.widgets,
                    state.ext.header,
                    state.ext.footer,
                    state.ext.editorTakeover,
                    hubShortcuts,
                    firstInteractivePanel,
                    extDefaultLabel,
                    keysDefaultLabel,
                ) {
                    val statusCount = state.ext.statuses.size
                    val panelCount = state.ext.widgets.size +
                        (if (state.ext.header != null) 1 else 0) +
                        (if (state.ext.footer != null) 1 else 0)
                    val totalCount = statusCount + panelCount + (if (state.ext.editorTakeover) 1 else 0)
                    when {
                        totalCount > 0 -> {
                            val firstStatus = state.ext.statuses.firstOrNull()?.let { st ->
                                stripAnsiControls(st.text).trim().ifEmpty { widgetCaption(st.key) ?: st.key }
                            }
                            val firstPanel = (state.ext.widgets + listOfNotNull(state.ext.header, state.ext.footer))
                                .firstOrNull()?.let { panel -> widgetCaption(panel.key) ?: panel.key }
                            val label = when {
                                firstStatus != null -> firstStatus.take(18)
                                firstPanel != null -> firstPanel.take(18)
                                else -> extDefaultLabel
                            }
                            ComposerExtensionChipState(
                                label = label,
                                count = totalCount,
                                hasInteractive = firstInteractivePanel != null,
                            )
                        }
                        hubShortcuts.isNotEmpty() -> ComposerExtensionChipState(
                            label = keysDefaultLabel,
                            count = hubShortcuts.size,
                            hasInteractive = false,
                        )
                        else -> null
                    }
                }

                ChatDockStatusAndWidgets(
                    state = state,
                    session = session,
                    draft = draft,
                    onDraft = { draft = it },
                    attachments = attachments,
                    onAttachments = { attachments = it },
                    imeVisible = imeVisible,
                    hiddenWidgetKeys = hiddenWidgetSet,
                    onHideWidget = { key ->
                        if (key !in hiddenWidgetKeys) hiddenWidgetKeys = hiddenWidgetKeys + key
                    },
                    onInteractWidget = { panel -> interactingWidgetKey = panel.key },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PiSpacing.composerPageMargin)
                        .padding(top = 2.dp, bottom = PiSpacing.composerBottomGap),
                ) {
                    val activeCommands = remember(state.commands, state.commandsLoaded) {
                        if (state.commandsLoaded || state.commands.isNotEmpty()) state.commands
                        else app.pi.ui.chat.builtinRows(emptyList())
                    }
                    Composer(
                        draft = draft,
                        onPickAttachment = { attachmentSheetOpen = true },
                        contextUsage = state.stats?.contextUsage,
                        onOpenContext = { perform(ChatAction.ContextUsage) },
                        modelLabel = modelLabel,
                        onOpenModel = { perform(ChatAction.PickModel) },
                        onCycleModel = { perform(ChatAction.CycleModel) },
                        onDraftChange = { draft = it },
                        thinkingLevel = state.meta.thinkingLevel,
                        streaming = state.streaming,
                        canFollowUp = state.streaming && (draft.isNotBlank() || attachments.isNotEmpty()),
                        canSteer = state.streaming && (draft.isNotBlank() || attachments.isNotEmpty()),
                        onCycleThinking = { session.cycleThinkingLevel() },
                        onPickThinking = { sheet = ChatSheet.Thinking },
                        onOpenPalette = {
                            draft = when (draft.trim()) {
                                "" -> "/"
                                "/" -> ""
                                else -> if (draft.trimStart().startsWith("/")) draft else "/$draft"
                            }
                        },
                        onOpenBash = {
                            draft = when (draft.trim()) {
                                "" -> "!"
                                "!" -> "!!"
                                "!!" -> ""
                                else -> if (draft.trimStart().startsWith("!")) draft else "!$draft"
                            }
                        },
                        onOpenBashExcluded = {
                            draft = when (draft.trim()) {
                                "" -> "!!"
                                "!!" -> ""
                                else -> if (draft.trimStart().startsWith("!!")) draft else "!!${draft.trimStart().removePrefix("!")}"
                            }
                        },
                        onOpenMention = { if (PiFileMentions.prefixOf(draft) == null) draft += "@" },
                        onSteer = {
                            session.send(draft, attachments)
                            draft = ""
                            attachments = emptyList()
                            reArmTail()
                            dismissKeys()
                        },
                        onSend = {
                            when (val route = routeComposerText(draft, activeCommands)) {
                                ComposerRoute.Empty -> if (attachments.isNotEmpty()) {
                                    session.send("", attachments)
                                    attachments = emptyList()
                                    reArmTail()
                                    dismissKeys()
                                }
                                is ComposerRoute.Message -> {
                                    session.send(route.text, attachments)
                                    draft = ""
                                    attachments = emptyList()
                                    reArmTail()
                                    dismissKeys()
                                }
                                is ComposerRoute.Bash -> {
                                    session.runBash(route.command, route.excludeFromContext)
                                    draft = ""
                                    dismissKeys()
                                }
                                is ComposerRoute.Command -> {
                                    draft = ""
                                    dismissKeys()
                                    pick(route.command, route.args)
                                }
                                is ComposerRoute.Unknown -> session.notifyUnknownCommand(route.name)
                            }
                        },
                        canClear = draft.isNotBlank() || attachments.isNotEmpty(),
                        onClear = {
                            draft = ""
                            attachments = emptyList()
                        },
                        onFollowUp = {
                            session.sendFollowUp(draft, attachments)
                            draft = ""
                            attachments = emptyList()
                            reArmTail()
                            dismissKeys()
                        },
                        onStop = {
                            session.stop { restored -> draft = mergeRestoredQueue(restored, draft) }
                        },
                        extensionChip = extensionChip,
                        onOpenExtensions = { widgetsInspectorOpen = true },
                        onQuickInteractExtension = firstInteractivePanel?.let { panel ->
                            { interactingWidgetKey = panel.key }
                        },
                        onTerminalInputKey = if (state.ext.terminalInputCount > 0) {
                            session::sendTerminalInput
                        } else {
                            null
                        },
                    )
                }

                // 官方 widgetContainerBelow（placement == "belowEditor"）：默认直接显示，支持一键收纳，软键盘弹起时平滑让位。
                val belowWidgets = remember(state.ext.widgets, hiddenWidgetSet) {
                    state.ext.widgets.filter { it.placement == PLACEMENT_BELOW && it.key !in hiddenWidgetSet }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = !imeVisible && belowWidgets.isNotEmpty(),
                    enter = androidx.compose.animation.expandVertically(tween(150)) + fadeIn(tween(130)),
                    exit = androidx.compose.animation.shrinkVertically(tween(130)) + fadeOut(tween(110)),
                ) {
                    ExtensionWidgetStack(
                        widgets = belowWidgets,
                        onInteract = { panel -> interactingWidgetKey = panel.key },
                        onHide = { panel ->
                            if (panel.key !in hiddenWidgetKeys) hiddenWidgetKeys = hiddenWidgetKeys + panel.key
                        },
                        maxStackHeight = 96.dp,
                    )
                }
            }
        }
    }

    ChatSheetHost(sheet = sheet, onSheet = { sheet = it }, state = state, session = session)

    if (attachmentSheetOpen) {
        val clipImg = clipboardImageUri()
        AttachmentPickerSheet(
            hasClipboardImage = clipImg != null,
            stagedCount = attachments.size,
            maxPerMessage = state.meta.model?.inputLimits?.images?.maxPerMessage,
            onPickPhotos = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickFiles = {
                filePicker.launch(arrayOf("*/*"))
            },
            onPasteClipboardImage = {
                val uri = clipboardImageUri()
                if (uri != null) {
                    handlePickedUris(listOf(uri))
                } else {
                    session.notifyUser(tr("剪贴板里当前没有可读取的图片。", "No readable image found in clipboard."), warning = true)
                }
            },
            onInsertMention = {
                if (PiFileMentions.prefixOf(draft) == null) draft += "@"
            },
            onDismiss = { attachmentSheetOpen = false },
        )
    }

    if (widgetsInspectorOpen) {
        val hubShortcuts = remember(state.extensionShortcuts) {
            state.extensionShortcuts.mapNotNull { sc ->
                val key = sc.shortcut.trim()
                if (key.isEmpty()) return@mapNotNull null
                ExtensionHubShortcut(
                    key = key,
                    label = key,
                    description = sc.description?.takeIf { it.isNotBlank() },
                )
            }
        }
        ExtensionWidgetsSheet(
            header = state.ext.header,
            footer = state.ext.footer,
            statuses = state.ext.statuses,
            widgets = state.ext.widgets,
            editorTakeover = state.ext.editorTakeover,
            onInteract = { panel -> interactingWidgetKey = panel.key },
            onDismiss = { widgetsInspectorOpen = false },
            shortcuts = hubShortcuts,
            hiddenWidgetKeys = hiddenWidgetSet,
            onToggleHideWidget = { key ->
                hiddenWidgetKeys = if (key in hiddenWidgetKeys) {
                    hiddenWidgetKeys.filterNot { it == key }
                } else {
                    hiddenWidgetKeys + key
                }
            },
            onTriggerShortcut = session::triggerExtensionShortcut,
        )
    }

    interactingWidgetKey?.let { activeKey ->
        val activePanel = (listOfNotNull(state.ext.header, state.ext.footer) + state.ext.widgets)
            .firstOrNull { it.key == activeKey && it.surfaceId != null }
        if (activePanel == null) {
            interactingWidgetKey = null
        } else {
            ExtensionWidgetSurfaceSheet(
                panel = activePanel,
                onInput = session::sendSurfaceInput,
                onMouse = session::sendSurfaceMouse,
                onResize = session::resizeSurface,
                onDismiss = { interactingWidgetKey = null },
            )
        }
    }

    // Hosted here, at the top of the screen's own tree, for the same reason the
    // sheets are: the tap can come from any block, and the surface that opens must
    // not live inside the row it came from — a `LazyColumn` disposes a scrolled-away
    // item, and a viewer whose state died with its row would vanish under the
    // dialog. `PiImageViewer` is a `Dialog` (a separate window), so this placement
    // does not overlay the list; it only decides who owns "which image is open".
    viewedImage?.let { image ->
        PiImageViewer(
            image = image,
            onDismiss = { viewedImage = null },
            // 「保存到下载目录」的结果走全应用同一条通知队列（`ExtensionUiHost` 的 snackbar），
            // 与这个屏里其它 UI 层的失败（例如选择器什么都没返回）同一条路。
            onMessage = { session.notifyUser(it) },
        )
    }
}
