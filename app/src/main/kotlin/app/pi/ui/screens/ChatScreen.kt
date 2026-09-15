package app.pi.ui.screens

import app.pi.ui.blocks.decodePiImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.rpc.AssistantText
import app.pi.rpc.BranchSummary
import app.pi.rpc.CompactionMarker
import app.pi.rpc.DateSeparator
import app.pi.rpc.ErrorText
import app.pi.rpc.HookMessage
import app.pi.rpc.JsonlFramer
import app.pi.rpc.ModelChange
import app.pi.rpc.Notice
import app.pi.rpc.SkillInvocation
import app.pi.rpc.ThinkingBlock
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolDiff
import app.pi.rpc.TranscriptItem
import app.pi.rpc.PiImage
import app.pi.rpc.UserMessage
import app.pi.ui.Boot
import app.pi.ui.ExportedSession
import app.pi.ui.NavRequest
import app.pi.ui.PiSessionViewModel
import app.pi.ui.blocks.BlockRenderer
import app.pi.ui.blocks.PiImageViewer
import app.pi.ui.chat.BashPanel
import app.pi.ui.chat.ComposerRoute
import app.pi.ui.chat.ContextSheet
import app.pi.ui.chat.ForkPickerSheet
import app.pi.ui.chat.MentionPalette
import app.pi.ui.chat.ModelPickerSheet
import app.pi.ui.chat.PiCommandAction
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.RenameSessionDialog
import app.pi.ui.chat.SessionExportDelivery
import app.pi.ui.chat.SessionStatsSheet
import app.pi.ui.chat.SessionToolsSheet
import app.pi.ui.chat.SlashPalette
import app.pi.ui.chat.TailFollow
import app.pi.ui.chat.TailSnapshot
import app.pi.ui.chat.TailViewport
import app.pi.ui.chat.ThinkingPickerSheet
import app.pi.ui.chat.mayLoadEarlier
import app.pi.ui.chat.mergeRestoredQueue
import app.pi.ui.chat.routeComposerText
import app.pi.ui.chat.prependAnchoredIndex
import app.pi.ui.chat.thinkingLabelOf
import app.pi.ui.chat.unlistedBuiltinHint
import app.pi.ui.components.PiContextRing
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.extension.ExtensionWidgetStack
import app.pi.ui.extension.WidgetPlacement
import app.pi.ui.extension.windowTitleOf
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import app.pi.ui.theme.stateToneColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The transcript — the app's main stage (destination 2).
 *
 * Structure follows pi's own viewport stack, with the footer moved to the top
 * because the keyboard would otherwise cover it (docs/pi-android-ui-spec.md §4.1):
 *
 *   AppBar          session name · status · model · overflow
 *   StatusRow       extension `setStatus` entries (pi's footer, relocated)
 *   Transcript      the block kinds, rendered by `ui/blocks`
 *   QueueChips      steering / follow-up
 *   Widgets         extension `setWidget`, above the editor
 *   BashPanel       a running or finished `!` command
 *   Palette         the `/` command list while the composer holds a command name
 *   Mentions        the `@` file-mention list while the composer holds a mention
 *   Composer        border colour = thinking level, or bashMode for `!`
 *   Widgets         extension `setWidget`, below the editor
 *
 * Until the engine is up this screen shows the boot surface instead: the first
 * launch unpacks a Linux userland and that takes long enough that hiding it
 * behind a spinner would read as a hang.
 *
 * The screen does **not** mount the extension UI host: PiRoot mounts
 * `ExtensionUiHost` exactly once, above the active destination, and renders this
 * screen inside that Box. Mounting it here as well would compose two hosts for
 * the same request — two dialogs, two snackbar hosts racing for one notice
 * queue — so this screen relies on PiRoot's single mount. See
 * `ui/extension/ExtensionUiHost.kt` for the feature itself.
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
) {
    val state by session.state.collectAsState()
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
            modifier = Modifier.padding(bottom = bottomInset),
        )
        return
    }
    ChatBody(
        state = state,
        session = session,
        bottomInset = bottomInset,
        onOpenSessions = onOpenSessions,
    )
}

/** Which bottom sheet the chat screen has open, if any. */
private enum class ChatSheet { Model, Thinking, Tools, Stats, Context, Fork, Rename }

/** `06 §2`「顶栏：高 48」. The same number `ui/PiRoot.kt`'s `PiTopBar` uses. */
private val CHAT_TOP_BAR_HEIGHT = 48.dp

/** v2's `TopBar` `gap:10` between the title block and the right cluster. */
private val CHAT_TOP_BAR_GAP = 10.dp

/** v2's `TopBar` press boxes: `border-radius:8`. */
private val CHAT_TOP_BAR_PRESS_SHAPE = RoundedCornerShape(8.dp)

/** v2's `TopBar` action box and glyph: `30x30` with a `17` icon. */
private val CHAT_TOP_BAR_ACTION_BOX = 30.dp
private val CHAT_TOP_BAR_ACTION_GLYPH = 17.dp

/**
 * One top-bar icon action: the board's `30x30` radius-8 press box with a `17` glyph
 * (`direction-b-v2.html:504-511`).
 *
 * M3's `IconButton` is 48 dp with a 24 dp glyph, which is a visibly larger box than
 * the board's and is why the app's three icons used to read as a row of buttons
 * rather than as chrome.
 */
@Composable
private fun ChatTopBarIcon(onClick: () -> Unit, contentDescription: String, icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(CHAT_TOP_BAR_ACTION_BOX)
            .clip(CHAT_TOP_BAR_PRESS_SHAPE)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(CHAT_TOP_BAR_ACTION_GLYPH),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The engine state line's symbol — `06 §4`'s table, applied to pi's own wording.
 *
 * The words are `PiSessionViewModel.engineLabel`'s and are not translated here: the
 * six settled states are pi's (`就绪 / 工作中 / 启动中 / 排队中 / 引擎已退出 /
 * 引擎已停止`, `direction-b-v2.html:461-470`) and every other value the label can
 * carry is one of pi's own busy verbs (`正在加载会话` …), which the board draws with
 * `…` too. So the glyph is a reading of the label, not a second state machine.
 */
private fun engineGlyphOf(label: String): String = when (label) {
    "就绪" -> "✓"
    "启动中" -> "◌"
    "排队中" -> "≡"
    "引擎已退出" -> "✗"
    "引擎已停止" -> "■"
    "工作中" -> "…"
    else -> "…"
}

/** The engine state line's colour — the same table (`06 §4`), through [stateToneColor]. */
private fun engineToneOf(label: String): StateTone = when (label) {
    "就绪" -> StateTone.Success
    "引擎已退出", "引擎已停止" -> StateTone.Error
    else -> StateTone.Warning
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBody(
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
    bottomInset: Dp,
    onOpenSessions: () -> Unit,
) {
    // `draft` and `attachments` are **saveable**: they are the user's own input, and
    // leaving the chat destination (工作区 / 设置) used to throw them away, because
    // `PiRoot`'s `when (current)` drops this screen's composition entirely. Coming
    // back now finds the text and the pending images where they were left.
    //
    // Deliberately **not** keyed on `sessionKey`, unlike `renderWindow` / `tail` /
    // `pausedRows` above: those describe an *opening of a session*, while a draft is
    // the user's unsent writing. This screen has never dropped the draft when the
    // session changed underneath it (the composable stays mounted for a switch), and
    // making it session-keyed would start silently deleting text on a switch. The
    // risk that carries — a draft typed for one session being sent to another — is
    // the one the screen already had.
    var draft by rememberSaveable { mutableStateOf("") }
    var attachments by rememberSaveable(stateSaver = AttachmentListSaver) {
        mutableStateOf<List<PiImage>>(emptyList())
    }
    // One-shot overlays: a menu or a sheet that survives a trip to 工作区 would
    // reappear on a screen the user has moved on from, so these stay plain
    // `remember` on purpose.
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    var overflow by remember { mutableStateOf(false) }
    // The image the user tapped, if any. It is a full-screen `Dialog`
    // (`PiImageViewer`) and therefore its own window: nothing here unmounts the
    // `LazyColumn`, so closing the viewer returns to the exact scroll position
    // without a single line of restoration logic.
    var viewedImage by remember { mutableStateOf<PiImage?>(null) }
    // The app-local display preferences, read from pi's settings documents. They
    // are preferences this screen actually obeys — before that they were rows
    // whose value nothing consulted (`PiSessionViewModel.UiPrefs`).
    val prefs = state.prefs
    // pi's `app.tools.expand` (Ctrl+O): one switch that expands or collapses every
    // expandable row. Extensions cannot drive it over RPC — `setToolsExpanded` is
    // a documented no-op there (`rpc-mode.ts:303-310`) and this app does not
    // pretend otherwise — so the preference belongs to the user, not to a wire
    // message, and `app.tools.expandByDefault` is where it starts.
    var toolsExpanded by rememberSaveable(prefs.expandToolsByDefault) {
        mutableStateOf(prefs.expandToolsByDefault)
    }
    val listState = rememberLazyListState()
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
    // exactly the file the user picked, which is the same model the app already uses
    // for directory grants (`DeviceSafStore`), and (b) it is available on every API
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
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // A null uri is the user backing out of the picker, not a failure.
        if (uri != null) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            when {
                // Anything that is not an image has no byte channel, so the bytes are
                // copied into the workspace and the agent is handed the **path** — see
                // [copyIntoWorkspace] for why a copy and never a guessed device path.
                // The work is streamed and bounded on `Dispatchers.IO`; the callback
                // resumed the Activity, so this is the frame thread.
                !mime.startsWith("image/") -> pickerScope.launch {
                    val result = withContext(Dispatchers.IO) { copyIntoWorkspace(context, uri) }
                    when (result) {
                        // The path goes into the composer as **text**: pi's agent
                        // resolves it the same way it resolves one the user typed, and
                        // the app does not get to write the prompt.
                        is WorkspaceCopy.Copied -> {
                            draft = if (draft.isBlank()) {
                                result.relativePath
                            } else {
                                draft + " " + result.relativePath
                            }
                            // Named, because the file is now visible in 工作区 and an
                            // unexplained new file there is worse than the copy was.
                            session.notifyUser("已放入工作区：${result.relativePath}")
                        }
                        // Each failure names its own cause and inserts nothing: a
                        // `content://` string or a guessed path would be answered by pi
                        // with an error about a file the user never named.
                        WorkspaceCopy.Unreadable -> session.notifyUser(
                            "读不到这个文件：来源没有把它打开（权限被拒或文件已被删除）。" +
                                "请重新选择，或先把文件存到手机上再选。",
                            warning = true,
                        )

                        WorkspaceCopy.TooLarge -> session.notifyUser(
                            "文件太大：上限是 ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB。" +
                                "它还没有被复制进工作区，也没有加进这条消息；请先裁剪或压缩。",
                            warning = true,
                        )

                        WorkspaceCopy.WriteFailed -> session.notifyUser(
                            "写不进工作区（存储空间不足或目录不可写）。" +
                                "这个文件没有被复制，也没有加进这条消息。",
                            warning = true,
                        )
                    }
                }

                else -> {
                    // Off the frame thread, and bounded while reading. This callback
                    // runs on the main thread (`rememberLauncherForActivityResult`
                    // resumes the Activity), and the previous shape read *everything*
                    // the provider would give it — `readBytes()` on the whole
                    // `InputStream` — and only then compared the length with
                    // `MAX_ATTACHMENT_BYTES`. A cloud/gallery provider happily hands
                    // out tens of megabytes, so "the image is too large" was decided
                    // after allocating it, twice (the byte array, then the base64
                    // string), on the thread that draws: on a phone with ~1 GB free
                    // that is an OutOfMemoryError for the app, not a warning.
                    // `readBounded` stops at the cap and reports "too large" without
                    // ever holding more than the cap, and the encode happens on IO.
                    pickerScope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching {
                                resolver.openInputStream(uri)?.use { readBounded(it, MAX_ATTACHMENT_BYTES) }
                            }.getOrNull()
                        }
                        when {
                            bytes == null || bytes.isEmpty() -> session.notifyUser(
                                "读取所选图片失败：无法打开这个文件（权限被拒或文件已被删除）。请重新选择，或换一张本地图片。",
                                warning = true,
                            )

                            bytes.size > MAX_ATTACHMENT_BYTES -> session.notifyUser(
                                "图片太大，上限是 " +
                                    "${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB；它要整段随消息发送。" +
                                    "请先压缩或裁剪后再试。",
                                warning = true,
                            )

                            else -> {
                                val image = withContext(Dispatchers.IO) {
                                    PiImage(
                                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                                        mimeType = mime.ifEmpty { "image/*" },
                                    )
                                }
                                attachments = attachments + image
                            }
                        }
                    }
                }
            }
        }
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

    val visibleItems = remember(state.transcript, prefs.showTimestamps) {
        if (prefs.showTimestamps) {
            state.transcript
        } else {
            state.transcript.filterNot { it is DateSeparator }
        }
    }
    // F34 (`docs/rendering-review.md`) / spec §4.5: a long session opens on its last
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
    val hiddenCount = visibleItems.size - renderedItems.size
    // While anything is hidden the loading row is item 0, so a full-list index is a
    // rendered index plus `hiddenCount` plus that one row.
    val headerRows = if (hiddenCount > 0) 1 else 0
    val searchMatches = remember(visibleItems, searchQuery, prefs.hideThinkingBlock) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            visibleItems.mapIndexedNotNull { index, item ->
                // A block the user asked to hide cannot be a search result: the
                // row is not on screen to scroll to.
                if (prefs.hideThinkingBlock && item is ThinkingBlock) return@mapIndexedNotNull null
                if (searchTextOf(item).contains(searchQuery, ignoreCase = true)) index else null
            }
        }
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

    // F34: a jump target may sit in the part of the session the window has not
    // rendered, and `LazyListState` cannot be asked for an index the current item list
    // does not have. So a jump is two-phased: grow the window until the target is the
    // *first* rendered row, then scroll from an effect that runs once that list
    // exists. `hiddenCount` then equals the target's full index, which is why the
    // second phase can compute its index from state rather than from a captured one.
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    fun reveal(row: Int) {
        val needed = visibleItems.size - row
        if (needed > renderWindow) renderWindow = needed
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
    // `scrollToItem` could (`docs/streaming-review.md` §2.1/§2.2). But an effect that
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
    // see its own effect. All it does on a rising edge is pause, a state the user can
    // always undo with the affordance.
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

    LaunchedEffect(
        state.revision,
        state.streaming,
        renderedItems.size,
        following,
        tailPoke,
        sessionKey,
        bottomInset,
    ) {
        if (!following) return@LaunchedEffect
        // One frame, so the rows this publication added have been measured: reading
        // `layoutInfo` before the layout pass would compute the pin from the previous
        // frame's geometry. This is a *wait*, not an observation — nothing here is
        // re-triggered by the measure that follows it.
        withFrameNanos { }
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        val decision = tail.onSnapshot(
            TailSnapshot(
                transcriptRows = transcriptRows,
                poke = tailPoke,
                viewport = TailViewport(
                    totalItems = info.totalItemsCount,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset,
                    lastVisibleIndex = last?.index ?: -1,
                    lastVisibleOffsetPx = last?.offset ?: 0,
                    lastVisibleSizePx = last?.size ?: 0,
                    viewportEndOffsetPx = info.viewportEndOffset,
                    isScrollInProgress = listState.isScrollInProgress,
                    atBottom = !listState.canScrollForward,
                ),
            ),
        )
        if (following != decision.following) following = decision.following
        val pin = decision.pin
        // `requestScrollToItem` is the whole reason this is not a stutter: it applies
        // the position at the next remeasure instead of animating, so it starts no
        // scroll session, is never cancelled by the next token and can never be
        // mistaken for the user's hand. The `isScrollInProgress` re-read closes the gap
        // between the snapshot and this line — the follow must never cancel a drag.
        if (pin != null && !listState.isScrollInProgress) {
            listState.requestScrollToItem(pin.index, pin.offsetPx)
        }
    }

    // The user's hand: the rising edge of a scroll session. Started once; `collect`
    // sees the current value first, and every later `true` is a drag or a fling —
    // this code starts no scroll session (`requestScrollToItem` is not one, and the
    // two jumps below use it for exactly that reason), so a `true` here is the user.
    // `isScrollInProgress` is a plain field on `LazyListState`, not snapshot state, so
    // it is mirrored into one here: the "load earlier" rule needs it as a *key*, and a
    // `derivedStateOf` cannot see it change. This collector already existed for the
    // pause; it now feeds both readers.
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            scrolling = inProgress
            if (inProgress) pauseTail()
        }
    }
    // Spec §4.5: "向上滚动时分批加载更早的 entry". Reaching the top grows the window by
    // one step. The `earlierArmed` guard is what keeps that from looping: while the
    // user stays at the top the flag is cleared by the load itself, and it is only
    // re-armed once they scroll away — after a prepend, `LazyColumn` keeps the row
    // they were looking at anchored by key, so "still at the top" means the same
    // batch would otherwise be requested again on the next frame.
    val atTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 }
    }
    // `!canScrollForward` — the same end test `TailFollow` uses (`TailViewport.atBottom`),
    // and for the same reason: it is true exactly when the last row's bottom is inside
    // the viewport, including the tail of a row taller than the viewport.
    val atBottom by remember(listState) {
        derivedStateOf { !listState.canScrollForward }
    }
    var earlierArmed by rememberSaveable(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(atTop, hiddenCount, scrolling) {
        if (!atTop) {
            earlierArmed = true
            return@LaunchedEffect
        }
        // `mayLoadEarlier` is the whole fix for 「用力往旧消息方向一划就跳到最顶部」: a
        // batch may only be prepended once the gesture is over. Prepending *while a
        // fling is running* grows the list in the direction the fling is travelling,
        // so the fling never reaches an end and one flick walks the whole session.
        if (!mayLoadEarlier(atTop, earlierArmed, hiddenCount, scrolling)) return@LaunchedEffect
        earlierArmed = false
        val headerBefore = headerRows
        val prepended = minOf(TRANSCRIPT_WINDOW_STEP, hiddenCount)
        renderWindow += TRANSCRIPT_WINDOW_STEP
        // The "load earlier" row keeps its key at index 0 for as long as anything is
        // hidden, so the `LazyColumn`'s own key anchoring cannot see this prepend when
        // that row is the first visible item — the content slides by the whole batch
        // under a stationary index. Asking for the anchored index is the other half of
        // the fix (`prependAnchoredIndex`, `ui/chat/TailFollow.kt`).
        val headerAfter = if (hiddenCount - prepended > 0) 1 else 0
        listState.requestScrollToItem(
            prependAnchoredIndex(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                prependedRows = prepended,
                headerRowsBefore = headerBefore,
                headerRowsAfter = headerAfter,
            ),
            listState.firstVisibleItemScrollOffset,
        )
    }
    LaunchedEffect(searchMatches, searchCursor) {
        val index = searchMatches.getOrNull(searchCursor.coerceIn(0, (searchMatches.size - 1).coerceAtLeast(0)))
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
    LaunchedEffect(pendingJump, renderedItems.size, hiddenCount, headerRows) {
        val row = pendingJump ?: return@LaunchedEffect
        val index = row - hiddenCount + headerRows
        if (renderedItems.isNotEmpty() && index in 0 until renderedItems.size + headerRows) {
            // A direct position change, not `animateScrollToItem`: the jump is a
            // navigation, and an animation is a *scroll session*, which the follow
            // machine would read as the user's own hand and which would clear the
            // "this was navigation" suppression that keeps a reveal to the last row
            // from re-arming the follow. pi's own reveal is a direct `scrollTo`
            // (`tui-alt-screen.ts:636` → `scroll-view.ts:127`).
            listState.requestScrollToItem(index, 0)
            pendingJump = null
        }
    }

    // `set_editor_text`: an extension owns the composer content until the user
    // types again, so the fill is applied once and then consumed. Consuming is
    // load-bearing — this effect is keyed on the sequence, but leaving and
    // re-entering the destination re-runs it, and a stale fill would silently
    // overwrite whatever the user typed in the meantime.
    val fill = state.composerFill
    LaunchedEffect(fill?.seq) {
        if (fill != null) {
            draft = fill.text
            session.consumeComposerFill(fill.seq)
        }
    }

    // The `@` mention list. pi looks its candidates up on every keystroke of the
    // token, debounced by 20 ms (`packages/tui/src/components/editor.ts:251`); here
    // each lookup is a guest process (see `ui/chat/PiMentionSource.kt`), so the
    // debounce is deliberately longer. That delays the list, it does not change what
    // the list contains: the query that runs is the one whose result is still wanted,
    // because `session.requestMentions` drops every superseded answer.
    val mentionPrefix = PiFileMentions.prefixOf(draft)
    LaunchedEffect(mentionPrefix) {
        if (mentionPrefix == null) {
            session.dismissMentions()
        } else {
            delay(MENTION_DEBOUNCE_MS)
            session.requestMentions(mentionPrefix)
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
    fun pick(command: PiSlashCommand, args: String) {
        when (command.action) {
            PiCommandAction.Prompt -> session.runPromptCommand(command, args)
            // The refresh belongs to the *navigation*: `PiRoot` handles this
            // `NavRequest` by raising the session-list overlay, selecting the tree
            // view and calling `refreshTree()` itself (`ui/PiRoot.kt`), which is the
            // only way into the tree now. Refreshing here as well issued the same
            // two RPCs twice.
            PiCommandAction.OpenTree -> session.requestNav(NavRequest.SessionTree)
            PiCommandAction.PickFork -> {
                session.refreshForkMessages()
                sheet = ChatSheet.Fork
            }
            PiCommandAction.CloneSession -> session.cloneSession()
            // `/export <path>`: pi picks the writer from the extension
            // (`interactive-mode.ts:6062-6066`), and so does the ViewModel.
            PiCommandAction.ExportSession -> session.exportSession(args.takeIf { it.isNotBlank() })
            PiCommandAction.CopyLastAssistant -> copyLastAssistant(session, context)
            // `/name <名字>` renames outright, exactly as pi does
            // (`interactive-mode.ts:6193-6207`); the dialog is for the bare `/name`,
            // where there is nothing to apply yet. The argument was silently dropped
            // here — twice, in fact, because the first fix was lost in a later
            // rewrite of this function — so it is worth saying out loud: this arm
            // reads `args`.
            PiCommandAction.RenameSession -> if (args.isNotBlank()) {
                session.renameSession(args)
            } else {
                sheet = ChatSheet.Rename
            }
            PiCommandAction.SessionStats -> {
                session.refreshStats()
                sheet = ChatSheet.Stats
            }
            PiCommandAction.NewSession -> session.newSession()
            PiCommandAction.Compact -> session.compact(args.takeIf { it.isNotBlank() })
            PiCommandAction.OpenSessions -> session.requestNav(NavRequest.SessionList)
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
            PiCommandAction.ImportSession -> {
                if (args.isNotBlank()) {
                    session.notifyUser(
                        "本应用改用文件选择器导入，参数「$args」被忽略：请在选择器里挑那个 .jsonl 文件。",
                    )
                }
                importPicker.launch("*/*")
            }
            // `OpenSettings`, `PickModel`, `PickThinking`, `OpenModelScope` and
            // `TerminalOnly` used to have arms here. Their palette rows (`/settings`,
            // `/model`, `/thinking`, `/scoped-models`, and the five whose only answer
            // was a notice naming a Settings row) are deleted — each row was a second
            // door onto a surface the user already has one gesture away — and the
            // `when` is exhaustive over [PiCommandAction], so the arms went with them.
            // The surfaces themselves are untouched: 设置 is the bottom bar's third
            // destination, the model is the AppBar chip, and the thinking level is the
            // composer's `◐`. Typing one of those names is answered by
            // `unlistedBuiltinHint` rather than by a lie.
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- 顶栏
        //
        // v2's `TopBar` (`design-demos/direction-b-v2.html:489-516`) value for value:
        // 48 high on the dim surface with a 1px bottom rule, 14 horizontal, a 17/600
        // title (M3's `titleMedium`, which this app's `piTypography` already sets to
        // exactly 17/600), a 12 meta line 1 dp under it, and 30x30 radius-8 press
        // boxes with 17 glyphs on the right.
        //
        // Hand-drawn rather than `material3.TopAppBar` for the same three reasons the
        // rest of the app's chrome is (`ui/PiRoot.kt`'s `PiTopBar`): M3's height is 64,
        // its container is `surface` rather than the board's `surfaceDim`, and it draws
        // no bottom rule. It is *not* `PiTopBar` itself only because the chat's title
        // slot is not a string: the session name is the way into the session list
        // (`03-navigation-decision.md`), so it is a tap target carrying a chevron, and
        // the line under it is the engine state in its own three channels. Both are
        // chat-specific; the numbers are not, and they are the same four.
        Column(
            Modifier
                .fillMaxWidth()
                // The edge-to-edge window reports the status bar as an inset instead of
                // reserving space for it; M3's `TopAppBar` used to absorb it through its
                // own default `windowInsets`, so a hand-drawn bar has to say so.
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surfaceDim),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(CHAT_TOP_BAR_HEIGHT)
                    .padding(horizontal = PiSpacing.pageHorizontal),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CHAT_TOP_BAR_GAP),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 会话名是这一屏上「换一个会话」的入口：点它打开会话列表覆盖层。
                    // 它整体可点（不是只点文字），因为一个看起来像标题的东西如果不响应
                    // 点击，用户就再也找不到列表——底栏已经不再有「会话」那一项了
                    // （`03-navigation-decision.md`）。右侧那个 `∨` 是这个 affordance 的
                    // 符号：v2 的顶栏用符号表示「这里能展开」。
                    Row(
                        modifier = Modifier
                            .clip(CHAT_TOP_BAR_PRESS_SHAPE)
                            .clickable(onClickLabel = "打开会话列表", onClick = onOpenSessions),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            // The session name is pi's own (`set_session_name` /
                            // `session_info_changed`). `setTitle` is a *terminal window
                            // title*, and reusing it as the app title is an
                            // interpretation the audit grades DEGRADED — so it is only
                            // the fallback.
                            text = state.meta.sessionName
                                ?: windowTitleOf(
                                    state.windowTitle,
                                    if (state.transcript.isEmpty()) "新会话" else "会话",
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // `06 §4` 的引擎状态行是三重编码（字 + 符号 + 颜色）与 AppBar 的
                    // `Engine`（`direction-b-v2.html:461-472`）：pi 自己的 `engineLabel`
                    // 词表配上它自己的符号，颜色只是第三层。
                    Row(
                        modifier = Modifier.padding(top = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val label = session.engineLabel(state)
                        Text(
                            text = engineGlyphOf(label),
                            style = PiTheme.text.monoSmall,
                            color = stateToneColor(engineToneOf(label), PiTheme.palette),
                            maxLines = 1,
                        )
                        Text(
                            text = label,
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Hidden on the same three engine-empty screens v2 hides it on
                    // (`modelChip={false}`); with no transcript there is no model
                    // choice to report. See the status row's note below.
                    //
                    // The label has a **second source**, because `meta.model` is only
                    // whatever the last `get_state` said (`PiSessionViewModel
                    // .refreshState`), and a session that already has a transcript can
                    // legitimately have no answer there — which is how the chip came
                    // to read its own fallback 「选择模型」 on a session whose model the
                    // transcript was printing one row below it. pi's own record of
                    // that switch is the `model_change` entry, which the reducer keeps
                    // as `ModelChange.modelId` (`rpc/.../Transcript.kt`): the same
                    // field pi's tree selector labels (`components/tree-selector.ts:600`
                    // `parts.push("model", entry.modelId)`). Taking it from there is a
                    // **read of pi's data**, not a second guess at the live model.
                    // `meta.model` keeps its meaning ("what pi reports now") and stays
                    // the first choice; this only fills a hole.
                    if (!state.transcript.isEmpty()) {
                        val lastModelChange = remember(state.transcript) {
                            state.transcript.asReversed().firstNotNullOfOrNull { item ->
                                (item as? ModelChange)?.modelId?.takeIf { it.isNotBlank() }
                            }
                        }
                        ModelChip(
                            state.meta.model?.id
                                ?: state.meta.model?.name
                                ?: lastModelChange,
                        ) {
                            session.refreshModels()
                            sheet = ChatSheet.Model
                        }
                    }
                    ChatTopBarIcon(
                        onClick = { searchOpen = !searchOpen },
                        contentDescription = if (searchOpen) "关闭对话内查找" else "在对话里查找",
                        icon = Icons.Filled.Search,
                    )
                    // There is **no reload icon** any more. pi's `/reload` has no RPC
                    // channel at all — it rebinds pi's runtime *inside* pi
                    // (`agent-session.ts` `reload()`) and `rpc-types.ts` carries no
                    // `reload` command — so this button could only ever answer with
                    // 「只在 pi 原版 TUI 里有」, promising a reload it cannot perform.
                    // 设置 → 运行时与诊断 的「重启引擎」 is a different action (it
                    // restarts the process, which re-reads the settings pi caches at
                    // startup) and is **not** a replacement for this button, so
                    // nothing was moved there to take its place.
                    // The two ⋮s of this screen — here and the composer's — draw the
                    // same v2 container (`PiMenu`), not Material's menu: M3's shadow,
                    // its 4 dp corner and its `primary` ripple are all three things
                    // `04 §2.3` and v2 refuse. See `PiMenu`'s KDoc.
                    Box {
                        ChatTopBarIcon(
                            onClick = { overflow = true },
                            contentDescription = "更多",
                            icon = Icons.Filled.MoreVert,
                        )
                        PiMenu(
                            expanded = overflow,
                            onDismiss = { overflow = false },
                            // This anchor is at the top of the screen, so the menu
                            // opens **downwards**; the composer's ⋮ is the mirror
                            // image and opens upwards. Neither flips itself.
                            placement = PiMenuPlacement.Below,
                            items = buildList {
                                add(PiMenuItem("命令面板") { draft = "/" })
                                add(
                                    PiMenuItem("会话与队列") {
                                        session.refreshTuiOnlyExtensions()
                                        sheet = ChatSheet.Tools
                                    },
                                )
                                add(
                                    PiMenuItem("会话树") {
                                        // `PiRoot` refreshes the tree when it handles
                                        // this request (see the `/tree` arm in
                                        // `pick`); refreshing here too issued the same
                                        // two RPCs twice.
                                        session.requestNav(NavRequest.SessionTree)
                                    },
                                )
                                // Two sheets, two names: 会话信息 is metadata
                                // (name / id / file / counts) and 上下文与用量 is
                                // every figure the app has. The composer's ring opens
                                // the second one directly; this row exists so the
                                // first is reachable without the ring.
                                add(
                                    PiMenuItem("会话信息") {
                                        session.refreshStats()
                                        sheet = ChatSheet.Stats
                                    },
                                )
                                add(
                                    PiMenuItem("上下文与用量") {
                                        session.refreshStats()
                                        sheet = ChatSheet.Context
                                    },
                                )
                                add(PiMenuItem("新建会话") { session.newSession() })
                                add(
                                    PiMenuItem("从历史消息分支") {
                                        session.refreshForkMessages()
                                        sheet = ChatSheet.Fork
                                    },
                                )
                                add(PiMenuItem("复制当前会话") { session.cloneSession() })
                                add(PiMenuItem("重命名") { sheet = ChatSheet.Rename })
                                add(PiMenuItem("导出会话（按扩展名）") { session.exportSession() })
                                add(
                                    PiMenuItem("跳到上一条提问") {
                                        // F34: the first visible row as a *full-list*
                                        // index — the loading row does not exist in
                                        // `visibleItems`, and the hidden prefix does
                                        // not exist in the rendered list.
                                        val firstFull =
                                            listState.firstVisibleItemIndex - headerRows + hiddenCount
                                        userRowIndices().lastOrNull { it < firstFull }?.let { row ->
                                            pauseTail()
                                            reveal(row)
                                        }
                                    },
                                )
                                add(
                                    PiMenuItem("跳到下一条提问") {
                                        val firstFull =
                                            listState.firstVisibleItemIndex - headerRows + hiddenCount
                                        userRowIndices().firstOrNull { it > firstFull }?.let { row ->
                                            pauseTail()
                                            reveal(row)
                                        }
                                    },
                                )
                                add(
                                    PiMenuItem("复制最后一条回复") {
                                        copyLastAssistant(session, context)
                                    },
                                )
                                // The label is read at build time, so it is still the
                                // toggle it always was.
                                add(
                                    PiMenuItem(
                                        if (toolsExpanded) "收起全部工具输出" else "展开全部工具输出",
                                    ) { toolsExpanded = !toolsExpanded },
                                )
                                // 「循环切换模型」 is **deleted**. pi has the keybinding
                                // (`app.model.cycleForward` / `cycleBackward`,
                                // `keybindings.md`) and a keyboard is where a blind
                                // cycle belongs: you press it again and watch the
                                // footer. On a phone the same tap has no visible
                                // result — the model chip and the composer both stay as
                                // they were until the next turn — so it read as a
                                // button that did nothing, and it is redundant next to
                                // the AppBar's model chip and `/model`, both of which
                                // *show* what they are choosing.
                                add(
                                    PiMenuItem("思考等级…") {
                                        session.refreshThinkingLevels()
                                        sheet = ChatSheet.Thinking
                                    },
                                )
                                add(
                                    PiMenuItem("循环切换思考等级") {
                                        session.cycleThinkingLevel()
                                    },
                                )
                                add(
                                    PiMenuItem("选择模型…") {
                                        session.refreshModels()
                                        sheet = ChatSheet.Model
                                    },
                                )
                                // There is no terminal entry here any more: the user
                                // retired the terminal outright, so the settings home's
                                // row is the one door.
                            },
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // v2's `ChatShell` puts the search strip **between the TopBar and the status
        // row** (`direction-b-v2.html:1366-1367`: `{p.searchBar}` then `{StateLine}`),
        // because it is chrome for the bar above it rather than a row of the
        // transcript's header — the app used to draw it under the extension status row.
        if (searchOpen) {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = searchMatches.size,
                cursor = searchCursor,
                onPrevious = {
                    if (searchMatches.isNotEmpty()) {
                        searchCursor = (searchCursor - 1 + searchMatches.size) % searchMatches.size
                    }
                },
                onNext = {
                    if (searchMatches.isNotEmpty()) {
                        searchCursor = (searchCursor + 1) % searchMatches.size
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
        // composer's context ring → 会话信息.
        //
        // `PiStatusLine` itself is kept (it is no longer called from this screen):
        // the detail sheet draws the same figures in its own layout and is the reason
        // the component still exists. Nothing else here reads it.
        val emptyTranscript = state.transcript.isEmpty()

        // 扩展状态行（`ExtensionStatusRow`，即「📋 3/5」那一行）按用户裁决删除：
        // 扩展自己给的任意字符串不该在我们的顶栏常驻占一条横带。数据仍在收集
        // （`state.extensionStatuses`），将来放进「会话与队列」那张 sheet 按需查看；
        // 裁决与四个替代方案的比较见 `design/ui-refactor/11-designer-adjudication.md` D-3。

        // **An empty session shows nothing.** The two states that used to be drawn
        // here — 「引擎正在启动」 and 「引擎已就绪」 with their two paragraphs — are
        // deleted by the user's ruling (「引擎启动什么玩意，那些文字」: a brand-new
        // conversation is a blank page with the bottom bar and the composer, and the
        // AppBar's one-word engine state above it says everything the paragraphs did).
        // v2's `EmptyState` component is not used here any more, but it is not dead:
        // the session list, the settings search and the session tree still draw it.
        //
        // Deliberate deviation from v2's phone59/phone60: those two screens no longer
        // appear in this state. See `design/ui-refactor/07-construction-decisions.md`
        // D31.

        // **The transcript's box is always there, even when the transcript is not.**
        //
        // This is the fix for 「打开软件之后，输入框跑到屏幕最上面，启动成功之后才落到正常
        // 位置」, and the mechanism is the whole story: a `Column` measures its
        // non-weighted children first and gives the weighted ones what is *left*. With
        // an empty transcript the `weight(1f)` row did not exist at all (the `if` was
        // around it), so nothing absorbed the free space and the composer — the next
        // child — was laid out immediately under the top bar. It dropped into place
        // only when the first rows arrived and the weighted box appeared with them.
        // The trigger is therefore deterministic and is *not* a first-frame inset
        // artefact: **any empty transcript** puts the composer at the top, and the 1–2 s
        // of `Boot.Idle` (D31: the chat page is drawn immediately, the boot page no
        // longer is) is simply when the user sees it. A brand-new conversation with
        // nothing sent yet is the same state, which is why this is a layout bug and not
        // a startup one.
        //
        // Keeping the box and moving the emptiness *inside* it makes the composer's
        // position depend only on the screen's height and on the heights of the rows
        // below it — never on what the transcript measured, whether it holds 0 rows or
        // 5000. **Why not `Box(fillMaxSize)` with the composer `align(BottomCenter)`**
        // (the other shape considered): the transcript then has to know how tall the
        // composer is in order to pad its last rows out from under it, and the only way
        // to learn that is to measure the composer and feed the result back into the
        // transcript — a measure → pad → measure dependency, which is the coupling this
        // file already went out of its way to avoid (`design/ui-refactor/08-hang-diagnosis.md`,
        // and the follow effect's own argument above). One owner for the vertical order
        // is what keeps this fixable.
        //
        // `bottomInset` is untouched by it: the trailing spacer and the boot page's
        // padding keep exactly the semantics they had (see the `Spacer` below and
        // `ChatScreen`'s boot branch).
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (!emptyTranscript) {
            // `app.appearance.messageDensity`: the transcript's block rhythm,
            // scaled around v2's own gap. F11 (`docs/rendering-review.md`):
            // blocks used to pad themselves as well, so the real gap was
            // 18 (spacedBy) + 9 + 9 (BlockColumn) = 36 dp and the prose column lost
            // 16 dp on each side; the list is now the only place that margins.
            //
            // B7: the base gap is **8**, not 16. `06 §2`「块间距 8」 is v2's rhythm
            // (every card in the frozen board carries `marginBottom:8`), and the
            // three density steps hang off it — compact is half, cozy is double —
            // so the pref still moves the stream and the default is the design's.
            // `PiSpacing.blockGap` is the same constant `ToolRail` bridges with, so
            // the rail's overdraw and the gap it spans cannot drift apart.
            val blockSpacing = when (prefs.messageDensity) {
                "compact" -> PiSpacing.blockGap / 2
                "cozy" -> PiSpacing.blockGap * 2
                else -> PiSpacing.blockGap
            }
            // The page margin is **14 at every density** (`06 §2`「屏水平 14px」,
            // `direction-b-v2.html:1376`: `.b-scroll{padding:10px 14px 12px}`). The
            // compact step used to narrow it to 12 as well, which put the transcript's
            // left edge out of line with the AppBar's own 14 and with every other
            // screen; the density preference moves the *block rhythm*, not the page.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // The vertical ends are not the block gap: they are the scroll
                // container's own `10` top and `12` bottom.
                contentPadding = PaddingValues(
                    start = PiSpacing.pageHorizontal,
                    end = PiSpacing.pageHorizontal,
                    top = 10.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(blockSpacing),
            ) {
                // Spec §4.5's "顶部显示加载指示". There is deliberately no spinner: the
                // earlier rows are already in memory (the reducer never dropped them),
                // so between the tap and the rows there is nothing but a slice, and a
                // spinner would be animating nothing. The row states what it does and
                // how much is left, and is itself the tap target as well as the
                // scroll-to-top trigger above.
                if (hiddenCount > 0) {
                    item(key = "transcript-earlier", contentType = "transcript-earlier") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { renderWindow += TRANSCRIPT_WINDOW_STEP }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = null,
                                tint = PiTheme.palette.muted,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "加载更早的 $hiddenCount 条",
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                        }
                    }
                }
                // Keyed by the reducer's stable per-block key, which is what lets
                // Compose animate the row that changed while streaming instead of
                // recomposing the list.
                //
                // F33 (`docs/rendering-review.md`): without a `contentType` the
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
                    val isMatch = searchMatches.contains(index)
                    val isCurrentMatch = isMatch && searchMatches.getOrNull(searchCursor) == index
                    val rowModifier = when {
                        isCurrentMatch -> Modifier
                            .border(1.dp, PiTheme.palette.searchMatchText, PiShapes.cardInner)
                            .background(PiTheme.palette.searchMatchBg, PiShapes.cardInner)

                        isMatch -> Modifier.background(PiTheme.palette.searchMatchBg, PiShapes.cardInner)
                        else -> Modifier
                    }
                    // The execution rail's two ends (`06 §2` 执行轨道「竖线上下各缩进 16」).
                    // A run is a property of *consecutive transcript rows*, so the one
                    // place that can answer "is this the first/last tool card of a run"
                    // is the list that holds the order — the blocks themselves only ever
                    // see one item. `previous`/`next` come from the same rendered slice
                    // the `LazyColumn` is building, so the answer cannot disagree with
                    // what is on screen: `ToolCall` and `ToolDiff` are the only two kinds
                    // that draw a rail (`ui/blocks/ToolRail.kt`).
                    val previous = renderedItems.getOrNull(sliceIndex - 1)
                    val next = renderedItems.getOrNull(sliceIndex + 1)
                    val firstOfRun = previous !is ToolCall && previous !is ToolDiff
                    val lastOfRun = next !is ToolCall && next !is ToolDiff
                    BlockRenderer(
                        item = item,
                        modifier = rowModifier,
                        firstOfRun = firstOfRun,
                        lastOfRun = lastOfRun,
                        // pi's `hideThinkingBlock` (`settings-manager.ts:119`) and
                        // the app's collapse-by-default preference both land here;
                        // the renderer already honours both.
                        hideThinking = prefs.hideThinkingBlock,
                        thinkingDefaultExpanded = !prefs.thinkingCollapsedByDefault,
                        toolsDefaultExpanded = toolsExpanded,
                        // F18 (`docs/rendering-review.md`): pi's own
                        // `showCacheMissNotices` switch now reaches the blocks that
                        // print the summarization billing line. pi's default is
                        // `false` (`core/settings-manager.ts:120`), so an install
                        // that never touched that row shows nothing new.
                        showBilledCost = prefs.showCacheMissNotices,
                        // F19 (`docs/rendering-review.md`) / RR-P10: the one
                        // remaining callback of the renderer's original five whose
                        // target already exists in this app is supplied here instead of
                        // leaving the block's gated label unreachable — the branch
                        // summary → the session tree, which is where the app can move
                        // the leaf (the same target the palette's 会话树 action uses,
                        // `:404-407`). The model row's own tap used to be the second;
                        // the row no longer renders (see `BlockRenderer`), so its
                        // callback is gone with it and the model is reached from the
                        // AppBar chip.
                        onBranchClick = {
                            // Navigation refreshes the tree — see the `/tree` arm in
                            // `pick`; this pair used to run the same two RPCs twice.
                            session.requestNav(NavRequest.SessionTree)
                        },
                        // §4.8's 编辑并从此分叉: pi's user-message row opens the
                        // fork picker and re-runs from that message
                        // (`interactive-mode.ts:5216` / `docs/sessions.md:31`).
                        //
                        // The block hands over its transcript key, which is pi's entry
                        // id only on the replay path — a bubble drawn for a prompt sent
                        // in this run carries a synthetic key (`Transcript.kt:982`,
                        // `:1010`), and sending that to `fork` is exactly what pi
                        // answered with `Invalid entry ID for forking`. The order that
                        // *is* reliable lives here, so the row's position among the user
                        // rows and its text are read from the full list (not just the
                        // rendered window) and handed to the ViewModel, which matches
                        // them against `get_fork_messages` — pi's own list of legal fork
                        // points.
                        onForkFromMessage = { key ->
                            val row = visibleItems
                                .firstOrNull { it is UserMessage && it.key == key } as? UserMessage
                            session.forkFromMessage(
                                key = key,
                                ordinal = userMessageOrdinal(visibleItems, key),
                                text = row?.text.orEmpty(),
                            )
                        },
                        // F19 (`docs/rendering-review.md`) deleted `onImageClick`
                        // because no viewer existed to receive it. One does now, so
                        // the callback is back and supplied: every image anywhere in
                        // the transcript — a user attachment, an assistant's picture,
                        // a tool's screenshot — opens [PiImageViewer]. The other two
                        // (`onDiffOpenFull`, `onErrorRetry`) still have no target and
                        // stay deleted.
                        onImageClick = { viewedImage = it },
                    )
                }
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
            // **When each is shown.** Up: only when there is something above, i.e. the
            // viewport is not on the first item. Down: when there is something below
            // **or** the follow is paused — the second half matters, because a
            // navigation that lands on the last row deliberately keeps the follow off
            // (`TailFollow.pause`, pi's `disableFollow`), and hiding the button there
            // would leave no way to re-arm it. A viewport that is at the bottom *and*
            // following shows neither, which is the state the transcript is in most of
            // the time and the reason the old pill was the only thing on screen.
            val showUp = !atTop
            val showDown = !atBottom || !following
            if (showUp || showDown) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(SCROLL_ARROWS_PADDING),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(SCROLL_ARROWS_GAP),
                ) {
                    if (showUp) {
                        ScrollArrowButton(
                            icon = Icons.Filled.KeyboardArrowUp,
                            label = "回到顶部",
                            onClick = {
                                // Navigation, not following: pi's `disableFollow`
                                // semantics for anything that reveals a row.
                                pauseTail()
                                // Row 0 of the whole transcript. `reveal` is the app's
                                // existing "materialise the rows I need, then go there"
                                // primitive (the prompt jumps use it), and it is what
                                // keeps this honest: the window is a rendering budget,
                                // not a limit on what the user may look at.
                                windowOpen = true
                                reveal(0)
                            },
                        )
                    }
                    if (showDown) {
                        ScrollArrowButton(
                            icon = Icons.Filled.KeyboardArrowDown,
                            label = "回到最新",
                            unread = unseenRows > 0,
                            onClick = {
                                // Closing the window is the counterpart of the up
                                // arrow's `windowOpen = true`: following the tail means
                                // the last-50 window again, not a transcript the user
                                // once opened all the way.
                                windowOpen = false
                                reArmTail()
                            },
                        )
                    }
                }
            }
            }
        }

        if (state.queueSteering > 0 || state.queueFollowUp > 0) {
            QueueRow(
                steering = state.queueSteering,
                followUp = state.queueFollowUp,
                // pi's `app.message.dequeue` (`alt+up`): the queue comes back to the
                // editor and **the turn keeps running** (`interactive-mode.ts:4157-4164`
                // → `:4387-4406` with no `abort`). Stop is the other half — it drains
                // and aborts — and stays on the send button.
                onRestore = {
                    session.restoreQueue { restored -> draft = mergeRestoredQueue(restored, draft) }
                },
            )
        }

        // A finished export is a file the user cannot otherwise open: it lives in
        // this app's private workspace. The row is the delivery, and it is the
        // reason `exportSession` publishes `state.exported` at all.
        val exported = state.exported
        if (exported != null) {
            ExportDeliveryRow(
                exported = exported,
                onSave = {
                    exportScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            SessionExportDelivery.saveToDownloads(context, exported)
                        }
                        session.notifyUser(result.sentence, warning = result.warning)
                    }
                },
                onShare = {
                    exportScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            SessionExportDelivery.share(context, exported)
                        }
                        session.notifyUser(result.sentence, warning = result.warning)
                    }
                },
                onDismiss = { session.dismissExport() },
            )
        }

        ExtensionWidgetStack(
            widgets = state.extensionWidgets.filter { it.placement == WidgetPlacement.AboveEditor },
        )

        state.bash?.let { bashRun ->
            BashPanel(
                run = bashRun,
                onAbort = { session.abortBash() },
                onDismiss = { session.dismissBash() },
                modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
            )
        }

        // pi opens its autocomplete as soon as the line starts with `/` and closes
        // it once the command name is complete (its `CombinedAutocompleteProvider`
        // completes the first token only). The same rule keeps this list from
        // covering the transcript while arguments are being typed.
        // `commandsLoaded` gates the panel: before the first `get_commands` answer
        // the list is empty for a reason that has nothing to do with what the user
        // typed, so rendering here would flash the panel's "nothing here" state for
        // one frame every time `/` opens. Nothing is shown until the list it would
        // draw is real.
        //
        // The panel's own empty branch (`ui/chat/SlashPalette.kt`) is only *half*
        // unreachable, and that half is why this is a gate rather than a deletion:
        // `piCommandPalette` always returns `builtins + fromPi`
        // (`ui/chat/PiSlashCommands.kt:285`), so with a blank query the list can
        // never be empty and 「没有可用命令」 never renders — but a query nothing
        // matches renders 「没有匹配「…」的命令」 from the same branch, which is a
        // real state a user reaches by typing. Deleting the branch would delete that
        // state too.
        val paletteQuery = paletteQueryOf(draft)
        if (paletteQuery != null && state.commandsLoaded) {
            SlashPalette(
                commands = state.commands,
                query = paletteQuery,
                onPick = { command ->
                    // A tap is pi's Enter-on-a-suggestion: the editor applies the
                    // completion and then falls through to submit
                    // (`packages/tui/src/components/editor.ts:784-802`,
                    // `CombinedAutocompleteProvider.applyCompletion` inserts
                    // `/name `), so selecting a command runs it. The exception is
                    // a command pi itself marks as argument-taking
                    // (`argumentHint`, which only the built-ins have): there the
                    // name is completed and left in the composer, which is pi's Tab
                    // behaviour, because submitting `/login` with no provider is
                    // never what the user meant.
                    //
                    // An argument-taking command leaves its name in the composer
                    // (pi's Tab behaviour). The two exceptions that used to be here —
                    // `/model` and `/thinking`, whose argument the app picked itself —
                    // are gone with their rows: they are no longer in this palette at
                    // all, so the exception set is empty rather than wrong.
                    if (command.argumentHint != null) {
                        draft = "${command.invocation} "
                    } else {
                        draft = ""
                        pick(command, "")
                    }
                },
                modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
            )
        }

        // The `@` mention list, next to the `/` palette and under the same two
        // rules: it belongs to what the composer holds right now (`mentions.query`
        // is the prefix the answer was computed for, so a late answer for an older
        // keystroke cannot appear), and **it is not drawn when there is nothing to
        // offer** — pi's autocomplete returns no suggestions at all in that case
        // (`packages/tui/src/autocomplete.ts:305`), it does not show an empty popup.
        //
        // A tap inserts and does not send: pi's `@` branch of `applyCompletion`
        // accepts the completion and stops, while only the `/` branch falls through
        // to submitting (`packages/tui/src/components/editor.ts:784-802`). This
        // app has no Enter-to-send for the composer to collide with, so a tap is
        // the whole gesture.
        val mentions = state.mentions
        if (mentionPrefix != null && mentions != null && mentions.query == mentionPrefix && mentions.items.isNotEmpty()) {
            val prefix = mentionPrefix
            MentionPalette(
                candidates = mentions.items,
                onPick = { item -> draft = PiFileMentions.apply(draft, prefix, item) },
                modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
            )
        }

        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachments.forEachIndexed { index, image ->
                    AttachmentThumb(
                        image = image,
                        index = index,
                        onRemove = {
                            attachments = attachments.filterIndexed { i, _ -> i != index }
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                // The consequence is stated where the user acts, not after the send:
                // these bytes travel inside the message and count against the model.
                Text(
                    "随消息一起发送",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Composer(
            draft = draft,
            // One picker for everything: `*/*`, and the callback decides which of
            // pi's two channels the file goes down (see the attachment note above).
            onPickAttachment = { attachmentPicker.launch("*/*") },
            // The reading the transcript's status row used to carry; pi's own percent,
            // with no fallback invented for it (null draws the ring's `?` state).
            contextPercent = state.stats?.contextUsage?.percent,
            onOpenContext = {
                session.refreshStats()
                sheet = ChatSheet.Context
            },
            onDraftChange = { draft = it },
            thinkingLevel = state.meta.thinkingLevel,
            streaming = state.streaming,
            canFollowUp = state.streaming && draft.isNotBlank(),
            // `steer` carries attachments as well as text, so it is enabled by
            // either — the same rule the send button's own "an image with no caption
            // is still a message" branch uses.
            canSteer = state.streaming && (draft.isNotBlank() || attachments.isNotEmpty()),
            onCycleThinking = { session.cycleThinkingLevel() },
            onOpenPalette = { if (draft.isBlank()) draft = "/" },
            onOpenBash = { if (draft.isBlank()) draft = "!" },
            // `!!` types both characters: it is a different pi mode, not a double
            // `!` (`interactive-mode.ts:933`).
            onOpenBashExcluded = { if (draft.isBlank()) draft = "!!" },
            // `@` sits behind the phone keyboard's symbol page, and a mention only
            // opens at a token boundary (`PiFileMentions.prefixOf`, pi's
            // `PATH_DELIMITERS`), so this chip types exactly the trigger character —
            // nothing else, which is why it does nothing when a mention is already
            // open. The `/` and `!` chips beside it are the same kind of affordance.
            onOpenMention = { if (PiFileMentions.prefixOf(draft) == null) draft += "@" },
            onSteer = {
                // `session.send` **is** the steer route mid-turn: it picks
                // `streamingBehavior: "steer"` whenever the transcript is streaming
                // (`PiSessionViewModel.kt`'s `send`), which is exactly this chip's
                // condition, and it echoes the row locally so the message is visible
                // before pi answers.
                session.send(draft, attachments)
                draft = ""
                attachments = emptyList()
                reArmTail()
                dismissKeys()
            },
            onSend = {
                when (val route = routeComposerText(draft, state.commands)) {
                    // An image with no caption is still a message.
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
                        // Spec §4.5: 发送后清空输入、插入用户消息、**滚动到底**. The
                        // machine re-arms and the poke makes it pin without waiting for
                        // the next layout pass, so the user sees their own message
                        // rather than a transcript they had scrolled up into.
                        reArmTail()
                        // The message is on the wire, so the keyboard has done its
                        // job: dropping it is what turns the send into "watch the
                        // answer" instead of "read through a half-covered screen"
                        // ([dismissComposerKeys]).
                        dismissKeys()
                    }
                    is ComposerRoute.Bash -> {
                        session.runBash(route.command, route.excludeFromContext)
                        draft = ""
                        // `!` runs too: the command left the composer and output is
                        // about to stream into the panel above it.
                        dismissKeys()
                    }
                    is ComposerRoute.Command -> {
                        draft = ""
                        pick(route.command, route.args)
                    }
                    // The draft stays: the notice explains what to do, and throwing
                    // the user's text away would make the explanation harder to act
                    // on. For the same reason the keyboard stays — nothing was sent,
                    // and taking the keys away from text the user still owns is the
                    // one way this change could feel like a bug.
                    // `/name` where pi has the command but this palette does not list
                    // it: a statement of fact plus this app's own door, never a
                    // "retry it as prose" — the old wording told the user to drop the
                    // slash and send it as a message, which is exactly wrong for a name
                    // pi *does* implement.
                    is ComposerRoute.Unknown -> unlistedBuiltinHint(route.name)
                        ?.let { session.notifyUser(it) }
                        ?: session.notifyUnknownCommand(route.name)
                }
            },
            // pi's Escape (`interactive-mode.ts:2855-2857`):
            // `restoreQueuedMessagesToEditor({ abort: true })` clears the queue,
            // puts the queued text **and** the current editor text back into the
            // editor, then aborts. Dropping the queued text here is what made Stop
            // silently destroy what the user had typed.
            // 清空 and 排队 are no longer Composer parameters: the designer's overflow
            // ladder moved both out of the key row and into this screen's ⋮ menu, so
            // the composer does not gate or run them any more.
            // 清空 is the composer's own ⋮ entry (`ComposerMenu`); the editor it
            // clears is the draft plus the attachments staged for it.
            canClear = draft.isNotBlank() || attachments.isNotEmpty(),
            onClear = {
                draft = ""
                attachments = emptyList()
            },
            onFollowUp = {
                // pi's alt+enter: queue this message for after the current turn
                // (`interactive-mode.ts:4126-4155` → `session.prompt(text,
                // { streamingBehavior: "followUp" })`, which is `follow_up` on the
                // wire). Only offered while streaming, because that is the only time
                // pi's own binding queues rather than submits.
                //
                // The attachments go with it: pi's own call hands the editor's images
                // to `_queueFollowUp` (`agent-session.ts:1225-1226`), and F21's
                // finding in `docs/gap-disposition.md` §10 found this call dropping
                // them — the chip looked like it queued the message and the picture
                // was silently gone.
                session.sendFollowUp(draft, attachments)
                draft = ""
                attachments = emptyList()
                // Spec §4.5's 发送行为: a send lands on the newest row. Queued text
                // included — the queue row is chrome next to the composer, but the
                // turn it belongs to is at the tail.
                reArmTail()
                // Queued is still delivered, so this is a "sent" for the same reason a
                // normal send is: the text left the composer and the user is done
                // typing it. See [dismissComposerKeys].
                dismissKeys()
            },
            onStop = {
                session.stop { restored -> draft = mergeRestoredQueue(restored, draft) }
            },
        )

        // `belowEditor` widgets sit after the composer's key-hint strip, which is
        // the app's footer; pi puts them between editor and footer, and the
        // difference is invisible at this density.
        ExtensionWidgetStack(
            widgets = state.extensionWidgets.filter { it.placement == WidgetPlacement.BelowEditor },
        )

        Spacer(Modifier.height(bottomInset + 8.dp))
    }

    when (sheet) {
        ChatSheet.Model -> ModelPickerSheet(
            models = state.models,
            current = state.meta.model,
            busy = state.busy != null,
            onPick = { model ->
                session.setModel(model)
                sheet = null
            },
            onRefresh = { session.refreshModels() },
            onDismiss = { sheet = null },
        )

        ChatSheet.Thinking -> ThinkingPickerSheet(
            levels = state.meta.thinkingLevels,
            current = state.meta.thinkingLevel,
            onPick = { session.setThinkingLevel(it) },
            onDismiss = { sheet = null },
        )

        ChatSheet.Tools -> SessionToolsSheet(
            state = state,
            onSteeringMode = { session.setSteeringMode(it) },
            onFollowUpMode = { session.setFollowUpMode(it) },
            onAutoCompaction = { session.setAutoCompaction(it) },
            onAutoRetry = { session.setAutoRetry(it) },
            onAbortRetry = { session.abortRetry() },
            onCompact = {
                session.compact()
                sheet = null
            },
            onStats = {
                session.refreshStats()
                sheet = ChatSheet.Stats
            },
            onTree = {
                // Navigation refreshes the tree — see the `/tree` arm in `pick`.
                session.requestNav(NavRequest.SessionTree)
                sheet = null
            },
            onFork = {
                session.refreshForkMessages()
                sheet = ChatSheet.Fork
            },
            onClone = {
                session.cloneSession()
                sheet = null
            },
            onRename = { sheet = ChatSheet.Rename },
            onExport = {
                session.exportSession()
                sheet = null
            },
            onCopyLast = { copyLastAssistant(session, context) },
            onDismiss = { sheet = null },
        )

        ChatSheet.Stats -> SessionStatsSheet(state = state, onDismiss = { sheet = null })

        ChatSheet.Context -> ContextSheet(state = state, onDismiss = { sheet = null })

        ChatSheet.Fork -> ForkPickerSheet(
            messages = state.forkMessages,
            onPick = { entryId -> session.forkFrom(entryId) },
            onDismiss = { sheet = null },
        )

        ChatSheet.Rename -> RenameSessionDialog(
            initial = state.meta.sessionName,
            onConfirm = { name ->
                session.renameSession(name)
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        null -> Unit
    }

    // Hosted here, at the top of the screen's own tree, for the same reason the
    // sheets are: the tap can come from any block, and the surface that opens must
    // not live inside the row it came from — a `LazyColumn` disposes a scrolled-away
    // item, and a viewer whose state died with its row would vanish under the
    // dialog. `PiImageViewer` is a `Dialog` (a separate window), so this placement
    // does not overlay the list; it only decides who owns "which image is open".
    viewedImage?.let { image ->
        PiImageViewer(image = image, onDismiss = { viewedImage = null })
    }
}

/**
 * The palette is open exactly while the first token is still being typed: the
 * draft starts with `/` and no space has been typed yet. Returns the query, or
 * null when the palette should be hidden.
 */
private fun paletteQueryOf(draft: String): String? {
    val trimmed = draft.trimStart()
    if (!trimmed.startsWith("/")) return null
    if (trimmed.contains(' ')) return null
    return trimmed.removePrefix("/")
}

/**
 * The 0-based index of the user-message row [key] among the list's user-message
 * rows, or -1 when the list does not hold it.
 *
 * This is the app-side half of pi's fork list order: `getUserMessagesForForking`
 * walks the session's entries in file order and keeps every user message with
 * text (`core/agent-session.ts:3309-3327`), and a transcript row is projected
 * from one of those same entries, so the two orders agree. It is derived on
 * demand — the callback runs once per long press — rather than kept as a map
 * that a streaming publication would rebuild on every chunk (the append-only
 * cost this screen already went out of its way to avoid, see `userRowIndices`).
 *
 * The caller in the ViewModel trusts the value only together with the row's own
 * text, because one entry can project no user row at all (`Transcript.kt:2353-2368`).
 */
private fun userMessageOrdinal(items: List<TranscriptItem>, key: String): Int {
    var ordinal = 0
    for (item in items) {
        if (item !is UserMessage) continue
        if (item.key == key) return ordinal
        ordinal++
    }
    return -1
}

/**
 * The text a block contributes to transcript search.
 *
 * (`mergeRestoredQueue` — pi's queue/editor merge rule, shared by Stop and the
 * queue row's dequeue — lives in `ui/chat/QueueRestore.kt`: both actions must merge
 * identically, and a pure function is the only form
 * `tools/run-app-pure-checks.sh` can execute, because this file imports Compose.)
 *
 * Every rendered block kind is covered, because a search that silently skips a
 * kind would report "no matches" for text the user can see. Kinds with no text of
 * their own (a date separator, a model change) contribute nothing.
 */
private fun searchTextOf(item: TranscriptItem): String = when (item) {
    is UserMessage -> item.text
    is AssistantText -> item.text
    is ThinkingBlock -> item.text
    is ToolCall -> listOf(item.toolName, item.argsSummary, item.output).joinToString("\n")
    is ToolDiff -> listOf(item.path, item.diffText).joinToString("\n")
    is CompactionMarker -> item.summary
    is BranchSummary -> item.summary
    is HookMessage -> item.markdown
    is ModelChange -> listOfNotNull(item.provider, item.modelId).joinToString("/")
    is SkillInvocation -> listOf(item.skillName, item.body).joinToString("\n")
    is ErrorText -> listOfNotNull(item.message, item.detail).joinToString("\n")
    is Notice -> item.text
    is DateSeparator -> ""
}

// The "pi has this command but this palette does not list it" table lives in
// `ui/chat/PiSlashCommands.kt` (`PI_UNLISTED_BUILTIN_COMMANDS` /
// `unlistedBuiltinHint`), beside the built-in list it complements — a private copy
// here would be a second table that goes stale the moment pi's list moves.

/**
 * The transcript's search bar, transcribed from v2's `SearchState` bar
 * (`direction-b-v2.html:2755-2767`, i.e. phone14).
 *
 * v2 draws **one strip** under the AppBar:
 *
 * ```
 * 容器      padding:8px 14px, border-bottom:1px borderMuted, background:surf-dim
 * 输入条    height:36, border:1px borderAccent, radius:9, background:surf-low,
 *           padding:0 10px, gap:8
 *   ·       search 字形 15 muted
 *   ·       查询文本 mono t13 text
 *   ·       `3 / 8` mono t12 muted
 *   ·       「关闭查找」 t12 muted
 * 动作行    margin-top:8, gap:8: `↑ 上一个匹配` / `↓ 下一个匹配` chips + 右侧说明 t12 muted
 * ```
 *
 * The app used to draw an `OutlinedTextField` plus three Material `IconButton`s —
 * two controls' worth of chrome, an M3 field outline the board does not have, and two
 * icon buttons whose only label was a content description. The **mechanism** is
 * unchanged and is still pi's: the query is the same string, the two arrows move
 * `searchCursor`, and the count keeps pi's `cursor / total` reading.
 *
 * What is deliberately *not* v2 is the colouring of the hits themselves: the current
 * match is filled with pi's `searchMatchBg` and ringed in `searchMatchText`, the
 * others carry `searchMatchBg` alone (`ui/screens/ChatScreen.kt`, the row modifier in
 * the transcript). Those two are pi's own search tokens and 09's highlight-fidelity
 * record settled them; v2's prototype stands in `selectedBg`/`surf-high` there, which
 * the parent's ruling keeps out. This bar therefore borrows v2's *structure* only.
 *
 * The trailing sentence is v2's, verbatim (`direction-b-v2.html:2766`).
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    cursor: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // `surf-dim` is the chrome tone the AppBar and the bottom bar use; the
            // strip is chrome, not content.
            .background(MaterialTheme.colorScheme.surfaceDim)
            .padding(horizontal = PiSpacing.pageHorizontal, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEARCH_BAR_HEIGHT)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, PiTheme.palette.borderAccent, RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = PiTheme.palette.muted,
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                // `06 §2` 机器语言层: a search query over machine output is typed in the
                // machine face, and v2 sets it `mono t13`.
                textStyle = PiTheme.text.mono.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(PiTheme.palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "在对话里查找",
                                style = PiTheme.text.mono,
                                color = PiTheme.palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Text(
                text = if (matchCount == 0) {
                    "0 / 0"
                } else {
                    "${cursor.coerceIn(0, matchCount - 1) + 1} / $matchCount"
                },
                style = PiTheme.text.monoSmall,
                color = PiTheme.palette.muted,
                maxLines = 1,
            )
            Text(
                text = "关闭查找",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "关闭查找", onClick = onClose)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A disabled end of the transcript is drawn muted rather than removed: the
            // pair is a reading of "there is nothing further this way", and a missing
            // chip would move the other one.
            SearchChip("↑", "上一个匹配", enabled = matchCount > 0, onClick = onPrevious)
            SearchChip("↓", "下一个匹配", enabled = matchCount > 0, onClick = onNext)
            Spacer(Modifier.weight(1f))
            Text(
                text = "当前命中给底 + 亮，其余命中只给底",
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The context ring in the composer's key row (design decision D23).
 *
 * The ring is 24 dp across with a 2 dp pen (`r = 11`, circumference 69.12 dp), the
 * touch target is **32** rather than the usual 48, and its slot in the row is **30**
 * — the same as the send disc opposite it. The 32 is the designer's ruling and it is
 * load-bearing: a 48 dp box would grow this row by 18 dp, and the row exists to keep
 * the transcript's display area (the status band it replaces was 32 dp tall).
 */
private val CONTEXT_RING_DIAMETER = 24.dp
private val CONTEXT_RING_STROKE = 2.dp
private val CONTEXT_RING_TOUCH = 32.dp
private val CONTEXT_RING_SLOT = 30.dp

/** `direction-b-v2.html:2756`: the search input row's height is v2's `36`. */
private val SEARCH_BAR_HEIGHT = 36.dp

/**
 * One search action chip — v2's `Chip` (`direction-b-v2.html:605-617`) as the two
 * match arrows use it (`:2763-2764`): `height:26`, `border-radius:999`, a
 * `1px borderMuted` ring on a transparent ground, a glyph in the muted tone and the
 * label in the text colour.
 */
@Composable
private fun SearchChip(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(percent = 50))
            .border(1.dp, PiTheme.palette.borderMuted, RoundedCornerShape(percent = 50))
            .then(if (enabled) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.monoSmall,
            color = PiTheme.palette.muted,
            maxLines = 1,
        )
        Text(
            text = label,
            style = PiTheme.text.meta,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/**
 * Put a picked non-image file where the agent can read it, and answer with the
 * workspace-relative path to insert.
 *
 * ## Why a copy, and not a device path
 *
 * This is the implementation of the sentence that used to sit above the *old* code
 * as its stated design — 「把文件放到 agent 能读到的地方」 — and which that code did
 * not follow: it guessed a phone path instead (`/storage/emulated/0/…`, a
 * `_data` column, `raw:`), and on any source that would not answer with one it failed
 * with 「这个来源没有可用的文件路径」. On Android 10+ that is nearly every source: the
 * system picker hands back a `content://` URI from Files/Downloads/Recent/cloud
 * providers, `_data` is unset for a virtual document, and a cloud provider has no
 * filesystem path at all. The user's report — 「除了图片，其他的东西我点完发送，它说
 * 『这个来源没有可用的文件路径』」 — is that failure, and it is deterministic rather
 * than intermittent.
 *
 * A guessed path was also only ever *half* a fix. The proot bind makes
 * `/storage/emulated/0/…` the same spelling inside the guest, but the guest runs as
 * this app's uid under `untrusted_app` (`bridge/DeviceWorkspace.kt:54-58`): a shared
 * path is visible and may still be unreadable, and the app cannot tell the difference
 * from here. The failure then happens *inside pi*, about a file the user never named —
 * strictly worse than the error above.
 *
 * A copy has neither problem. The workspace is app-private storage that the engine
 * already mounts (`PtyLauncher.workspaceHost`), the app writes the bytes itself, and
 * the path handed over is one the guest demonstrably owns. It is also the shape pi's
 * own terminal uses for a dropped file: the drop becomes a **path in the prompt**, not
 * a payload.
 *
 * The path is **relative to the workspace** (`attachments/report.pdf`), which is what
 * `@` mentions insert too (`ui/chat/PiFileMentions.kt`). Relative resolves against the
 * engine's cwd, which *is* the workspace, so the guest/host "one directory, two
 * spellings" trap (`GuestWorkspacePath`) never arises. The old comment's worry that
 * the two shapes would collide is answered by making them the same shape.
 *
 * ## What the user is told
 *
 * Success names the file it placed, because the workspace is a directory the user can
 * see from 工作区 and an unexplained new file there is worse than the copy. Failure
 * gives the actual cause ([WorkspaceCopy]) and inserts **nothing** — never a
 * `content://` string and never a guessed path.
 *
 * The cap is the same number as the image cap on purpose: one limit for the user to
 * learn, and the copy is streamed and bounded, so a 2 GB provider stream is refused
 * without ever being held in memory.
 */
private fun copyIntoWorkspace(context: Context, uri: android.net.Uri): WorkspaceCopy {
    val directory = java.io.File(
        app.pi.runtime.PtyLauncher.workspaceHost(context),
        ATTACHMENTS_DIR,
    )
    if (!directory.isDirectory && !directory.mkdirs()) return WorkspaceCopy.WriteFailed

    val source = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: return WorkspaceCopy.Unreadable

    val displayName = displayNameOf(context, uri)
    return source.use { input ->
        val name = app.pi.ui.chat.uniqueAttachmentName(
            app.pi.ui.chat.sanitizeAttachmentName(displayName),
        ) { candidate -> java.io.File(directory, candidate).exists() }
        val target = java.io.File(directory, name)
        // The name is sanitised, and this is the guard that does not depend on the
        // sanitiser being right: whatever it returned must land *inside* the
        // attachments directory. A name that escapes it is a path the app would be
        // writing on a stranger's behalf.
        val root = runCatching { directory.canonicalPath }.getOrNull() ?: return WorkspaceCopy.WriteFailed
        val resolved = runCatching { target.canonicalPath }.getOrNull() ?: return WorkspaceCopy.WriteFailed
        if (resolved != "$root${java.io.File.separator}$name") return WorkspaceCopy.WriteFailed

        val written = runCatching {
            target.outputStream().use { sink -> copyBounded(input, sink, MAX_ATTACHMENT_BYTES) }
        }.getOrNull() ?: return WorkspaceCopy.WriteFailed
        when {
            written < 0 -> {
                // The partial file must not stay behind: the user was told the
                // attachment was refused, so a half file in 工作区 would be a lie.
                target.delete()
                WorkspaceCopy.TooLarge
            }
            else -> WorkspaceCopy.Copied("$ATTACHMENTS_DIR/$name")
        }
    }
}

/** The picker's own name for a document, or null when the provider will not say. */
private fun displayNameOf(context: Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column < 0 || !cursor.moveToFirst()) null else cursor.getString(column)
        }
}.getOrNull()?.takeIf { it.isNotBlank() }

/** Where picked non-image files are placed inside the workspace. */
private const val ATTACHMENTS_DIR = "attachments"

/** One picked file that was put into the workspace. */
private sealed interface WorkspaceCopy {
    /** The workspace-relative path to insert, e.g. `attachments/report.pdf`. */
    data class Copied(val relativePath: String) : WorkspaceCopy

    /** The provider would not open the document: permission, or it is gone. */
    data object Unreadable : WorkspaceCopy

    /** Larger than [MAX_ATTACHMENT_BYTES]; the partial file was removed. */
    data object TooLarge : WorkspaceCopy

    /** The workspace could not be created, or the copy failed — disk full, read-only. */
    data object WriteFailed : WorkspaceCopy
}

/**
 * Stream `input` into `sink`, stopping the moment the total would pass [limit].
 *
 * Returns the number of bytes written, or `-1` when the limit was exceeded — the
 * signal the caller needs before it has read anything more. `readBytes()` on the whole
 * stream is what the image path's KDoc already rejects for the same reason (a cloud
 * provider offers hundreds of megabytes); nothing here ever holds more than one
 * 64 KiB buffer, and the bytes only ever exist on disk.
 */
private fun copyBounded(input: java.io.InputStream, sink: java.io.OutputStream, limit: Int): Int {
    var total = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) return -1
        sink.write(buffer, 0, read)
    }
    return total.toInt()
}


/** `get_last_assistant_text`, then the system clipboard; this is pi's `/copy`. */
private fun copyLastAssistant(session: PiSessionViewModel, context: Context) {
    session.copyLastAssistantText { text ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("pi", text))
    }
}

/**
 * The AppBar's model chip.
 *
 * pi has no always-visible model indicator either — its footer carries one — but
 * on a phone the model is the most consequential piece of hidden state, and the
 * only way to read it is `get_state` (audit §5.3). It shows `Model.id` because
 * that is what `set_model` takes, and it is tappable because a chip that reports
 * state without offering the picker is a dead end.
 */
@Composable
private fun ModelChip(label: String?, onClick: () -> Unit) {
    // `06 §2` chip「高 26 圆角 999 `padding:0 9px`」, and v2 draws this one as
    // `mono t12` in the text colour with a `1px borderMuted` ring
    // (`direction-b-v2.html:500-503`). It carried a Material `Info` glyph and a
    // filled surface before; the board's chip has neither, and a model id is machine
    // language, so it takes the machine face.
    Surface(
        modifier = Modifier
            .height(26.dp)
            .clip(PiShapes.badge)
            .clickable(onClickLabel = "选择模型", onClick = onClick),
        shape = PiShapes.badge,
        color = Color.Transparent,
        border = BorderStroke(1.dp, PiTheme.palette.borderMuted),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label ?: "选择模型",
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What is waiting behind the running turn, plus the one action pi offers on it.
 *
 * pi draws the same thing above its editor (`updatePendingMessagesDisplay`,
 * `interactive-mode.ts:4368-4385`): one line per queued message and a single hint —
 * "↳ <key> to edit all queued messages" — because `app.message.dequeue` restores
 * **all** of them (`:4387-4406`) and `clear_queue` has no per-message form
 * (`rpc-types.ts:26`). This row keeps the app's existing count chips and adds that
 * one action; it deliberately does not pretend each message can be taken back
 * alone, because nothing on the wire can do that.
 *
 * v2's `QueueRow` (`direction-b-v2.html:1381-1390`, phone11/phone12) is the shape
 * here: a `⇢` chip for the steering count and a `⇣` chip for the follow-up count —
 * the symbol is the queue's own third channel, coloured by meaning (`⇢` warning,
 * `⇣` muted) while the words and the number stay in the normal text colour — and
 * 「收回并编辑」 in the accent at the far right. The app used to print the two counts
 * as bare words in surface-coloured pills, which left the queue the only state on
 * the screen without a symbol.
 */
@Composable
private fun QueueRow(steering: Int, followUp: Int, onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The two words are pi's two waiting modes and are **not** synonyms:
        // 插话 is `steeringMode`/steer (this turn is steered by the message, applied
        // between its tool calls), 排队 is `followUpMode`/followUp (processed once the
        // whole turn is over). The glyphs and tones are the queue's own third channel
        // (`06 §4`: `⇢` warning, `⇣` muted).
        if (steering > 0) QueueChip(glyph = "⇢", tone = PiTheme.palette.warning, text = "插话 $steering")
        if (steering > 0 && followUp > 0) Spacer(Modifier.width(PiSpacing.inline))
        if (followUp > 0) QueueChip(glyph = "⇣", tone = PiTheme.palette.muted, text = "排队 $followUp")
        Spacer(Modifier.weight(1f))
        // The consequence, not the mechanism: the text goes back into the input box
        // and the turn that is running is not touched.
        Text(
            "收回并编辑",
            modifier = Modifier
                .clickable(onClickLabel = "收回并编辑", onClick = onRestore)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * One count chip: `06 §2` 的 chip（高 26 圆角 999），加上队列自己的符号。
 *
 * Both halves are monospace — a queue count is a machine reading, and the symbol
 * belongs to the machine face in v2 too (`<Chip glyph="⇢" … mono>`).
 */
@Composable
private fun QueueChip(glyph: String, tone: Color, text: String) {
    Surface(
        shape = PiShapes.badge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, PiTheme.palette.borderMuted),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = glyph, style = PiTheme.text.monoSmall, color = tone, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            Text(text = text, style = PiTheme.text.monoSmall, color = PiTheme.palette.text, maxLines = 1)
        }
    }
}

/**
 * The delivery row for a finished `/export`.
 *
 * pi's export lands in the user's own cwd (`core/export-html/index.ts:274-281`), so
 * on a desktop the file is simply there. This app's export lands in its private
 * workspace, where no file manager can reach it — so "where is it and how do I get
 * it out" is the only question the row has to answer. It shows the file name (what
 * the user will look for) and the two sinks the app already owns for its own
 * diagnostic report ([SessionExportDelivery]). No location is shown, because the
 * only location the app could print is one the user cannot open.
 */
@Composable
private fun ExportDeliveryRow(
    exported: ExportedSession,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp),
        shape = PiShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "会话已导出",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                exported.name,
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeliveryAction("保存到 Download", onSave)
                Spacer(Modifier.width(14.dp))
                DeliveryAction("分享", onShare)
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeliveryAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    thinkingLevel: String,
    streaming: Boolean,
    /** True while a follow-up could actually be queued: streaming, non-blank draft. */
    canFollowUp: Boolean,
    /** True while a `steer` could actually be delivered: streaming, and something to send. */
    canSteer: Boolean,
    onCycleThinking: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenBash: () -> Unit,
    /** pi's `!!`: run the command without putting it in the model's context. */
    onOpenBashExcluded: () -> Unit,
    onOpenMention: () -> Unit,
    /** The paperclip: an image goes on the wire, any other file in as its path. */
    onPickAttachment: () -> Unit,
    /** pi's context occupancy, 0–100, or null when pi has not reported one. */
    contextPercent: Double?,
    /** The ring's tap: the detail sheet, where the same reading is spelled out. */
    onOpenContext: () -> Unit,
    /** pi's `app.clear`: empty editor. True while there is something to clear. */
    canClear: Boolean,
    onClear: () -> Unit,
    /** 插话: pi's steer — the message joins the running turn. */
    onSteer: () -> Unit,
    /** 排队: pi's followUp — the message waits for the turn to finish. */
    onFollowUp: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // A state channel, not decoration: pi paints its editor with the current
    // thinking level's token and switches to `bashMode` when the line starts with
    // `!` (`interactive-mode.ts` `updateEditorBorderColor`).
    val borderColor = if (bashModeOf(draft)) {
        PiTheme.palette.bashMode
    } else {
        PiTheme.palette.thinking(thinkingLevel)
    }
    val inputShape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal)) {
        // v2 draws the editor and its key row as **one** bordered container
        // (`direction-b-v2.html:1428-1440`: `border:1px solid <level>`,
        // `border-radius:14`, `background:surf-low`, `padding:9px 10px`, the input
        // and the chip row 8 dp apart inside it). The app used to draw an
        // `OutlinedTextField` and then a *second*, separately-shaped strip below it,
        // which read as two controls where v2 has one.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, inputShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, inputShape)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp, max = 120.dp),
                // `06 §2` 输入区「输入 14/20」: the composer's own text is the 14 sp
                // chat-text step with a 20 sp lead, not M3's larger field default.
                textStyle = PiTheme.text.prose.copy(
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(PiTheme.palette.accent),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                text = "输入消息，/ 选命令，! 直接跑命令，@ 提及文件",
                                style = PiTheme.text.prose.copy(lineHeight = 20.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyHint("/", onOpenPalette)
                // The three trigger characters that used to be their own chips live
                // here now (`@`, `!`, `!!`). The row could not hold everything on a
                // 360 dp phone, and the user's ruling was to shorten the row rather
                // than to add a fallback order for it.
                ComposerMenu(
                    onOpenMention = onOpenMention,
                    onOpenBash = onOpenBash,
                    onOpenBashExcluded = onOpenBashExcluded,
                    canClear = canClear,
                    onClear = onClear,
                )
                // The paperclip: one picker, two channels (see the attachment note in
                // `ChatBody`). It used to be a `图片` chip wired to an `image/*`
                // picker, which is why nothing but an image could be attached, and
                // then a paperclip **with** the word 附件 — the user asked for the
                // icon alone ("附件本身图标就行，不要有汉字"). Its click label is not the
                // visible label (there is none), so it is passed separately.
                KeyChip(
                    label = "",
                    a11yLabel = "添加附件",
                    enabled = true,
                    onClick = onPickAttachment,
                    icon = Icons.Filled.AttachFile,
                )
                // The two ways to hand pi something **while it is working**, which is
                // the only time they differ — both are `prompt` with a
                // `streamingBehavior`, and the names are picked so the pair cannot be
                // read as synonyms:
                //
                //   插话 = `steeringMode` / steer — this turn is steered by the
                //          message, applied between its tool calls
                //          (`interactive-mode.ts:3137-3142`, pi's own Enter while
                //          streaming)
                //   排队 = `followUpMode` / followUp — processed after the whole turn
                //          is over (`app.message.followUp`, `keybindings.md:165`,
                //          pi's `alt+enter`)
                //
                // Before this they were one chip (「后续」) and the steer half was
                // reachable only through the send button — which, now that the engine
                // flag means "a turn is running" rather than "a message is arriving",
                // is a stop button for the whole turn. Both are drawn as key chips:
                // that is the family they belong to.
                if (streaming) {
                    KeyChip(label = "插话", enabled = canSteer, onClick = onSteer)
                    KeyChip(label = "排队", enabled = canFollowUp, onClick = onFollowUp)
                }
                // The transcript's status row used to print this reading in its own
                // 32 dp band above the stream; it is a ring in the key row now
                // (decision D23). `06 §2` 输入区 gives the row no such member, so the
                // geometry is the designer's ruling rather than the board's: a 24 dp
                // ring with a 2 dp pen, a **32 dp** touch target and a **30 dp** slot
                // — the slot matches the send disc opposite it so the row's height
                // does not move, and the touch target is 32 rather than the usual 48
                // because a 48 dp box would add 18 dp to a row whose whole point is
                // not to take display area.
                Box(
                    modifier = Modifier.size(CONTEXT_RING_SLOT),
                    contentAlignment = Alignment.Center,
                ) {
                    PiContextRing(
                        percent = contextPercent,
                        diameter = CONTEXT_RING_DIAMETER,
                        stroke = CONTEXT_RING_STROKE,
                        placeholderStyle = PiTheme.text.meta,
                        modifier = Modifier
                            .size(CONTEXT_RING_TOUCH)
                            .clip(RoundedCornerShape(percent = 50))
                            // A ring has no words, so the label is the only thing a
                            // screen reader has: it names the reading *and* the tap.
                            .clickable(onClickLabel = "查看上下文与用量", onClick = onOpenContext),
                    )
                }
                Spacer(Modifier.weight(1f))
                // The terminal chip that used to sit here is gone: the composer's
                // right-hand side belongs to the two things a person reaches for
                // while typing, and the terminal is not one of them any more — the
                // user retired it, and the settings home's row is the one door.
                // The signature element: pi encodes the thinking level as a colour
                // temperature, and this chip is that language carried into the GUI.
                // A tap cycles it — pi's `app.thinking.cycle` binding, the action
                // users reach for most — and the full supported set (which comes
                // from the model) is one entry away in the overflow menu.
                // v2 draws it as plain text rather than a pill: a `◐` in the level's
                // own colour and the level's name in the text colour
                // (`direction-b-v2.html:1432-1435`).
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "切换思考等级", onClick = onCycleThinking)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "◐",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.thinking(thinkingLevel),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = thinkingLabelOf(thinkingLevel),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // `06 §2` 输入区「发送/停止 30×30 圆角 999 accent」: v2's send key is the
                // accent disc with a page-coloured glyph (`direction-b-v2.html:1437`),
                // not a Material icon button — it is the composer's one primary action.
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(PiTheme.palette.accent)
                        .clickable(
                            onClickLabel = if (streaming) "停止" else "发送",
                            onClick = { if (streaming) onStop() else onSend() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (streaming) Icons.Filled.Stop else Icons.Filled.Send,
                        contentDescription = if (streaming) "停止" else "发送",
                        modifier = Modifier.size(16.dp),
                        tint = PiTheme.palette.pageBg,
                    )
                }
            }
        }
    }
}

/** pi: `isBashMode = text.trimStart().startsWith("!")`. */
private fun bashModeOf(draft: String): Boolean = draft.trimStart().startsWith("!")

/**
 * How long the composer waits before asking for `@` mention candidates.
 *
 * pi debounces this trigger by 20 ms and then spawns `fd` in its own process
 * (`packages/tui/src/components/editor.ts:251`, `:2362-2385`). Here the lookup is a
 * whole guest process (`ui/chat/PiMentionSource.kt`), so the wait is longer on
 * purpose. It is a delay, not a different answer: the debounce only decides *when*
 * the query runs, and a superseded query's result is dropped rather than shown.
 * No measured latency is claimed for that guest process - there is no device here.
 */
private const val MENTION_DEBOUNCE_MS: Long = 150L

/**
 * F34 (`docs/rendering-review.md`) / spec §4.5: how many transcript rows are rendered
 * at once, and how many more each "load earlier" step (or a scroll to the top) reveals.
 * The spec's number is 50 for the first paint; the same step is used for the batches,
 * so the window grows in the units the spec describes.
 *
 * This is a *rendering* bound, not a data bound: the reducer keeps every row, which is
 * what makes the earlier batches instant and keeps search, the prompt jumps and the
 * reducer's own replay independent of how much is on screen.
 */
private const val TRANSCRIPT_WINDOW_STEP = 50

/** The two floating scroll arrows: v2's dense control step, and 8dp apart. */
private val SCROLL_ARROWS_GAP = 8.dp
private val SCROLL_ARROWS_PADDING = 12.dp
private val SCROLL_ARROW_SIZE = 30.dp

/**
 * Idle opacity of a scroll arrow. The buttons sit over the transcript, so at rest
 * they must not compete with the text; pressing one brings it to full opacity for as
 * long as the touch lasts, which is the feedback the user asked for (「半透明一点」
 * without losing the press).
 */
private const val SCROLL_ARROW_IDLE_ALPHA = 0.62f

/**
 * One of the transcript's two floating scroll arrows (spec §4.5's affordance, in the
 * shape the user ruled for).
 *
 * A circle with `surfaceContainerHigh`, a `borderMuted` hairline and **no** shadow —
 * the three values the 「回到最新」 pill already used, so this introduces no new
 * colour (`direction-b-v2.html:1510`, `04 §2.3` 层级不用阴影). The glyph is the same
 * Material chevron the block chrome and the composer already draw; no emoji, no text.
 *
 * @param unread draws the 5 dp badge — the board's badge swatch (`06` §「徽标」:
 *   色块 5×5 圆角 1) in the accent. It replaces the old pill's 「· N」 count: the user
 *   asked for one arrow with no words, and the *fact* that something arrived is what
 *   the count was there to say, not the number.
 */
@Composable
private fun ScrollArrowButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    unread: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(SCROLL_ARROW_SIZE)
            .alpha(if (pressed) 1f else SCROLL_ARROW_IDLE_ALPHA)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, PiTheme.palette.borderMuted, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(PiTheme.palette.accent),
            )
        }
    }
}

/**
 * `rememberSaveable`'s saver for the follow machine.
 *
 * A configuration change must not turn a paused follow back into an armed one:
 * `LazyListState` restores its own scroll position, so coming back armed would yank a
 * reader who had scrolled up into history to the bottom on the next token. The three
 * ints are the machine's own state (`ui/chat/TailFollow.kt`, [TailFollow.savedState]);
 * the anchors are deliberately not among them, because they describe a single layout
 * pass and a fresh layout compared against a stale one would read as a user gesture.
 */
/**
 * `rememberSaveable`'s saver for the composer's pending attachments.
 *
 * A flat list of `base64, mimeType, base64, mimeType, …`: both halves are Strings, so
 * the pair needs no `Parcelable` and no custom `Bundle` handling. Written out rather
 * than left to `remember` because an attachment is up to ~6 MB of base64 the user
 * picked on purpose — losing it to a trip to 工作区 is the same data loss as losing
 * the draft.
 */
private val AttachmentListSaver: Saver<List<PiImage>, Any> = listSaver(
    save = { images -> images.flatMap { image -> listOf<Any>(image.base64, image.mimeType) } },
    restore = { flat ->
        flat.chunked(2).mapNotNull { pair ->
            val base64 = pair.getOrNull(0) as? String ?: return@mapNotNull null
            val mime = pair.getOrNull(1) as? String ?: return@mapNotNull null
            PiImage(base64 = base64, mimeType = mime)
        }
    },
)

private val TailFollowSaver: Saver<TailFollow, Any> = listSaver(
    save = { it.savedState() },
    restore = { saved -> TailFollow.fromSavedState(saved) },
)

/**
 * The composer's ⋮ chip: the trigger characters and 清空, which no longer fit in the
 * key row.
 *
 * ## Why the row is shorter
 *
 * The row could not hold a chip per trigger character on a 360 dp phone: with `@`,
 * `!` and `!!` in it the streaming state ran past the edge and pushed the **send disc
 * off screen**. The user's ruling was to stop budgeting for the overflow and shorten
 * the row instead: those three, plus 清空, moved in here, and the row now measures
 * **198 dp idle / 286 dp streaming** against the 312 dp a 360 dp phone gives the
 * composer's inner box (412 dp phones get 364 dp). Those figures are *computed* from
 * the design constants — chip = content + 2×7 dp padding + 6 dp gap, `/` a 7.2 dp
 * mono advance, the paperclip 16, the ⋮ 24, `◐ 中` 37, the ring 30, the send disc
 * 38 including its 8 dp lead — not measured on a device; there is no device here.
 *
 * ## The chip itself
 *
 * 24×24, radius 6, `surf-highest` — the same family as `/` and the paperclip — with a
 * **hand-drawn three-dot glyph** at 16 dp rather than M3's `MoreVert`: that glyph is a
 * 17–18 dp hairline drawn for the AppBar, and in a 24 dp chip it reads as a different
 * weight. The touch target is 32×32 (4 dp of bleed on each side, inside the row's
 * 30 dp), the pressed state is a flat `surf-high` rather than M3's ripple — the ripple
 * would tint the chip with `accent`, which this app reserves for real actions.
 *
 * The menu opens **upwards** ([PiMenuPlacement.Above]): the chip is in a row at the
 * bottom of the screen, so a downward menu would be under the keyboard.
 */
@Composable
private fun ComposerMenu(
    onOpenMention: () -> Unit,
    onOpenBash: () -> Unit,
    onOpenBashExcluded: () -> Unit,
    canClear: Boolean,
    onClear: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val palette = PiTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The menu is a `Popup` anchored to this box (the composer's container has a
    // 14 dp radius and clips, so an overlaid `Box` would be cut off at the corner).
    //
    // The box is **24 dp** — the chip's own size, in the same family as `/` and the
    // paperclip — while the node that takes the tap is 32 dp and simply overflows it
    // by 4 dp on each side. Setting the *box* to 32 would have made the chip 32 wide
    // and moved the row; Compose hit-tests the child's own bounds, so the larger
    // target works without the larger slot.
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (pressed) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clickable(
                    interactionSource = interaction,
                    // No ripple: M3's would tint the chip with `accent`, and accent is
                    // this app's colour for real actions.
                    indication = null,
                    onClickLabel = "更多输入方式",
                    onClick = { open = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = THREE_DOT_GLYPH,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        PiMenu(
            expanded = open,
            onDismiss = { open = false },
            placement = PiMenuPlacement.Above,
            items = buildList {
                add(
                    PiMenuItem(
                        symbol = "@",
                        label = "提及文件",
                        note = "插入文件路径",
                        symbolColor = palette.text,
                        onSelect = onOpenMention,
                    ),
                )
                // `!` and `!!` sit next to each other and differ **only** in the
                // right-hand note, because that is the only difference in pi: `!!`
                // runs the command without putting it in the model's context
                // (`modes/interactive/interactive-mode.ts:933`, `:3107`; the
                // execution side honours it through `ui/chat/SlashPalette.kt`'s
                // `excludeFromContext`). The symbol of both is the shell mode colour
                // pi paints a `!` line with.
                add(
                    PiMenuItem(
                        symbol = "!",
                        label = "跑命令",
                        note = "进上下文",
                        symbolColor = palette.bashMode,
                        onSelect = onOpenBash,
                    ),
                )
                add(
                    PiMenuItem(
                        symbol = "!!",
                        label = "跑命令",
                        note = "不进上下文",
                        symbolColor = palette.bashMode,
                        onSelect = onOpenBashExcluded,
                    ),
                )
                if (canClear) {
                    add(
                        PiMenuItem(
                            label = "清空",
                            // pi's `app.clear` (`interactive-mode.ts:919`, ctrl+c).
                            a11yLabel = "清空草稿",
                            dividerBefore = true,
                            onSelect = onClear,
                        ),
                    )
                }
            },
        )
    }
}

/**
 * The three dots of [ComposerMenu], drawn here rather than taken from
 * `Icons.Filled.MoreVert`.
 *
 * The viewport is 24 (M3's glyph is drawn for 18 and scaled), the dots are solid
 * circles of `r = 2` centred at `y = 6 / 12 / 18`, and the path is the six half-arcs
 * that are those three circles — an `ImageVector` path builder has no `circle()`
 * helper (`ui/components/PiNavGlyph.kt` documents the same substitution). The fill is
 * the same unreachable placeholder every vector here uses: `Icon`'s `tint` is what
 * colours it, because this vector carries no `tintColor` of its own.
 */
private val THREE_DOT_GLYPH: ImageVector = ImageVector.Builder(
    name = "PiComposerMenuDots",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M10 6a2 2 0 104 0a2 2 0 10-4 0" +
            "M10 12a2 2 0 104 0a2 2 0 10-4 0" +
            "M10 18a2 2 0 104 0a2 2 0 10-4 0",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

@Composable
private fun KeyHint(label: String, onClick: () -> Unit) {
    KeyChip(label = label, enabled = true, onClick = onClick)
}

/**
 * One chip of the composer's key row; [KeyHint] is the always-enabled member.
 *
 * ## Why three of the key row's triggers are not in the key row
 *
 * The row can run out of width on a 360 dp phone. It used to carry one chip per
 * trigger character — `/`, `!`, `!!`, `@`, the paperclip — and the user's ruling was
 * that the answer is not a priority ladder but a **shorter row**: `@`, `!` and `!!`
 * move into the ⋮ chip beside them ([ComposerMenu]), where they do exactly what the
 * chips did. What is left is what a person reaches for while typing (`/`, the
 * paperclip), the two delivery choices that exist only mid-turn (插话 / 排队), the
 * thinking level and the context ring.
 *
 * No width arithmetic, no fallback order, no budget constants — the row is short
 * enough that it does not need any.
 *
 * `06 §2` 输入区 names six key chips (`/` `!` `!!` `@` `图片` `编辑器`) at
 * `24×24` 圆角 6, and v2 draws each one as `height:24;min-width:24;padding:0 7px;
 * border-radius:6;background:surf-highest` with a **mono 12** label
 * (`direction-b-v2.html:1430-1431`). This row is five of them plus two conditional
 * ones: `图片` became the paperclip `附件` (one picker, two channels — see the
 * attachment note in `ChatBody`), and `编辑器` is **deleted** by decision **D19**,
 * because Android has no external editor to hand the draft to and the chip could
 * only report that. Before this batch the row was bare mono text with no chip at
 * all, so it read as stray characters rather than as keys.
 *
 * [enabled] is false for the conditional members whose action needs something to
 * deliver: pi's `alt+enter` 排队 and the 插话 chip both have nothing to hand over
 * while the composer is empty, and a disabled chip is drawn in the muted tokens and
 * takes no tap.
 *
 * ## An empty [label] is an icon-only chip, and it still has to be readable
 *
 * The paperclip draws **no text** (the user's ruling: 「附件本身图标就行，不要有汉字」),
 * so an empty label skips the `Text` **and** the 4 dp gap before it — a `Text("")`
 * would still claim the gap and quietly keep the chip wider than it looks. The
 * accessibility label is therefore a **separate parameter**: [onClickLabel] comes
 * from [a11yLabel], which defaults to [label] for the chips that do have words. An
 * icon-only chip whose click label were empty would announce nothing at all, which is
 * the one thing an icon-only control cannot afford.
 *
 * The icon is 16 dp, not the 14 dp used inside a labelled chip: with the same 7 dp of
 * horizontal padding it makes the icon-only chip exactly 30 dp wide, the designer's
 * number (16 + 7 + 7), and it matches the send disc's glyph weight opposite it.
 */
@Composable
private fun KeyChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Drawn before [label]; the paperclip is the one chip whose glyph carries meaning. */
    icon: ImageVector? = null,
    /** What a screen reader hears; required in practice whenever [label] is empty. */
    a11yLabel: String = label,
) {
    val content = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .heightIn(min = 24.dp)
            .defaultMinSize(minWidth = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .then(
                if (enabled) {
                    Modifier.clickable(onClickLabel = a11yLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (label.isEmpty()) 16.dp else 14.dp),
                    tint = content,
                )
                if (label.isNotEmpty()) Spacer(Modifier.width(4.dp))
            }
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = PiTheme.text.monoSmall,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}


/**
 * One pending attachment: a real thumbnail, not a file name.
 *
 * The decode is the same shared one the transcript uses ([decodePiImage]) and it
 * runs off the main thread, because a camera JPEG is not composition work. A
 * payload the platform codec refuses still gets a tappable chip with its MIME
 * type, so the user can see *what* will be sent and remove it.
 */
@Composable
private fun AttachmentThumb(
    image: PiImage,
    index: Int,
    onRemove: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, image.base64) {
        value = withContext(Dispatchers.IO) { decodePiImage(image.base64) }
    }
    Surface(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(48.dp)
            .clickable(onClickLabel = "移除第 ${index + 1} 张图片", onClick = onRemove),
        shape = PiShapes.cardInner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val decoded = bitmap
            if (decoded != null) {
                Image(
                    bitmap = decoded.asImageBitmap(),
                    contentDescription = "待发送的第 ${index + 1} 张图片，点击移除",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    image.mimeType.substringAfter('/').ifEmpty { "图片" },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Inline attachments are base64 inside the RPC message, so a huge image bloats
 * every prompt and every transcript row. This is the app's own guard: the
 * protocol carries no size field to check against (`ImageContent`).
 *
 * **Derived from the transport's own cap, not chosen.** pi echoes an attachment
 * back inside the records the app has to read — the `message` entries a
 * `get_entries` response carries, and the `message_start`/`message_end` events —
 * and the framer discards any single record longer than
 * [JsonlFramer.DEFAULT_MAX_RECORD_CHARS]. Base64 costs 4 characters per 3 bytes, so
 * an attachment of `B` bytes becomes a `4B/3`-character substring in that record;
 * allowing `B` anywhere near the record cap means the app accepts an image whose
 * own echo the app then refuses to read. At 8 MiB the cap was exactly the framer's
 * limit, so *every* legal maximum-size attachment was guaranteed to break the
 * session it was sent in.
 *
 * The arithmetic below keeps the two consistent: `(cap - slack) / 4 * 3`, where
 * `slack` is headroom for the rest of the record (entry id, role, timestamp,
 * mime type, JSON punctuation). `MAX_ATTACHMENT_BYTES` is therefore an `Int` and
 * stays one — `bytes.size` is an `Int`, and a `Long` constant here would silently
 * make that comparison a widening one nobody re-checks.
 */
private val MAX_ATTACHMENT_BYTES: Int =
    (JsonlFramer.DEFAULT_MAX_RECORD_CHARS - FRAMING_SLACK_CHARS) / 4 * 3

/** Headroom for everything in a record that is not the base64 payload. 64 KiB. */
private const val FRAMING_SLACK_CHARS = 64 * 1024

/**
 * Read at most `limit + 1` bytes, so "bigger than the limit" is answered without
 * ever materialising the whole input.
 *
 * `InputStream.readNBytes`/`readBytes()` allocate for whatever the provider offers;
 * a gallery or cloud provider can offer hundreds of megabytes. One byte past the
 * limit is all the caller's comparison needs, and it bounds both the allocation and
 * the time spent on a file that is about to be rejected.
 */
private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val cap = limit + 1
    val out = java.io.ByteArrayOutputStream(minOf(cap, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    while (out.size() < cap) {
        val read = input.read(buffer, 0, minOf(buffer.size, cap - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
