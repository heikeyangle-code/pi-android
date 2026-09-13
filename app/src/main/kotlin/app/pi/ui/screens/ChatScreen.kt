package app.pi.ui.screens

import app.pi.ui.blocks.decodePiImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import app.pi.ui.chat.BashPanel
import app.pi.ui.chat.ComposerRoute
import app.pi.ui.chat.ForkPickerSheet
import app.pi.ui.chat.MentionPalette
import app.pi.ui.chat.ModelPickerSheet
import app.pi.ui.chat.PI_BUILTIN_SLASH_COMMANDS
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
import app.pi.ui.chat.mergeRestoredQueue
import app.pi.ui.chat.routeComposerText
import app.pi.ui.chat.thinkingLabelOf
import app.pi.ui.components.PiEmptyState
import app.pi.ui.components.PiStatusLine
import app.pi.ui.extension.ExtensionStatusRow
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

    if (state.boot !is Boot.Ready) {
        BootScreen(
            boot = state.boot,
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
private enum class ChatSheet { Model, Thinking, Tools, Stats, Fork, Rename }

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
    var draft by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    var overflow by remember { mutableStateOf(false) }
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
    // (`ComposerRoute.Unreachable` / `Unknown`) leaves both the draft and the keys
    // alone, because the user still owns the text it is complaining about.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismissKeys: () -> Unit = {
        keyboard?.hide()
        focus.clearFocus()
    }

    // pi's `tui.altScreen.previousPrompt` / `nextPrompt`
    // (`keybindings.md:112`): jump between the messages the *user* wrote. On a
    // phone there is no keybinding for it, so it lives in the overflow menu.

    // Image attachments. pi's `prompt`/`steer`/`follow_up` all carry `images`
    // (`rpc-types.ts:22-24`), and the wire shape is inline base64 + MIME
    // (`ImageContent`, `packages/ai/src/types.ts:367-371`; `docs/rpc.md:51-53`).
    // There is no path and no size field, so the bytes travel inside the RPC
    // message — which is why this needs no guest file at all: `GuestImageBytes`
    // resolves guest paths *into* the app for markdown images, the opposite
    // direction, and reusing it here would invent a second channel.
    //
    // Picker: `ActivityResultContracts.GetContent()` (the SAF document picker).
    // Chosen over the Android 13 Photo Picker because (a) it needs no storage or
    // media permission at all — the system grants a per-URI read to this app for
    // exactly the file the user picked, which is the same model the app already
    // uses for directory grants (`DeviceSafStore`), and (b) it is available on
    // every API level this app supports without a backport dependency, and covers
    // file providers as well as the gallery.
    var attachments by remember { mutableStateOf<List<PiImage>>(emptyList()) }

    // pi's `app.editor.external` (`keybindings.md:129`, ctrl+g) → `handleOpenExternalEditor`
    // (`interactive-mode.ts:4246-4261`) → `editInExternalEditor`
    // (`modes/interactive/external-editor.ts:14-52`): pi writes the composer text to a
    // temp `prompt.md`, spawns the configured command, and **only if it exits zero**
    // reads the file back (strip BOM, drop one trailing newline) and replaces the
    // composer. Android has no command line to spawn, so the 1:1 mapping is an
    // `ACTION_EDIT` handoff, and pi's exit-code rule maps onto Android's result code:
    //   * `RESULT_OK` + text → replace the draft (pi: exit 0 → read back)
    //   * `RESULT_CANCELED` → keep the draft (pi: non-zero exit discards everything)
    //   * nothing handles it → keep the draft **and say so** (pi's `spawn` error
    //     resolves `status: "failed"` and it prints a line; never silent)
    val externalEditor = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val returned = result.data
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.removePrefix("\uFEFF")
            ?.removeSuffix("\n")
        when {
            result.resultCode == Activity.RESULT_OK && returned != null -> draft = returned
            result.resultCode == Activity.RESULT_CANCELED -> session.notifyUser(
                "外部编辑器没有改动内容，草稿保持原样。",
            )
            else -> session.notifyUser(
                "外部编辑器没有返回可读的文本，草稿保持原样。",
                warning = true,
            )
        }
    }
    fun openInExternalEditor() {
        val intent = Intent(Intent.ACTION_EDIT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, draft)
        val opened = runCatching { externalEditor.launch(intent) }.isSuccess
        if (!opened) {
            // No pointer to a settings row: the pi key (`externalEditor`) only
            // configures the Ctrl+G command *inside* pi's own TUI
            // (`interactive-mode.ts:2628,4247`), so its row was removed with the
            // TUI-only keys (`docs/settings-review.md` §9). This editor is the
            // app's own and goes through `ACTION_EDIT`.
            session.notifyUser(
                "没有应用能编辑文本，草稿保持原样。",
                warning = true,
            )
        }
    }
    // Where the picked image is read and encoded. The picker callback itself runs on
    // the main thread, and both halves of "load 6 MB and base64 it" belong off it.
    val pickerScope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // A null uri is the user backing out of the picker, not a failure.
        if (uri != null) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            when {
                !mime.startsWith("image/") -> session.notifyUser(
                    "只能附加图片：所选文件的类型是 ${mime.ifEmpty { "未知类型" }}。请回到选择器换一张图片。",
                    warning = true,
                )

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
    val renderedItems = remember(visibleItems, renderWindow) { visibleItems.takeLast(renderWindow) }
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
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) pauseTail()
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
    var earlierArmed by rememberSaveable(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(atTop, hiddenCount) {
        if (!atTop) {
            earlierArmed = true
        } else if (earlierArmed && hiddenCount > 0) {
            earlierArmed = false
            renderWindow += TRANSCRIPT_WINDOW_STEP
        }
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
            PiCommandAction.OpenSettings -> session.requestNav(NavRequest.Settings)
            PiCommandAction.PickModel -> {
                session.refreshModels()
                sheet = ChatSheet.Model
            }
            PiCommandAction.PickThinking -> {
                session.refreshThinkingLevels()
                sheet = ChatSheet.Thinking
            }
            PiCommandAction.OpenTree -> {
                session.refreshTree()
                session.requestNav(NavRequest.SessionTree)
            }
            PiCommandAction.PickFork -> {
                session.refreshForkMessages()
                sheet = ChatSheet.Fork
            }
            PiCommandAction.CloneSession -> session.cloneSession()
            // `/export <path>`: pi picks the writer from the extension
            // (`interactive-mode.ts:6062-6066`), and so does the ViewModel.
            PiCommandAction.ExportSession -> session.exportSession(args.takeIf { it.isNotBlank() })
            PiCommandAction.CopyLastAssistant -> copyLastAssistant(session, context)
            PiCommandAction.RenameSession -> sheet = ChatSheet.Rename
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
            PiCommandAction.ImportSession -> importPicker.launch("*/*")
            // pi's `/scoped-models` opens the model-scope selector
            // (`interactive-mode.ts:2975-2978` → `showModelsSelector()`, `:5024`)
            // and clears the editor first (`:2976`). The app's row for what that
            // selector toggles is `enabledModels`, so this navigates to it and
            // highlights it (`settings-manager.ts:1316-1326`).
            PiCommandAction.OpenModelScope -> {
                draft = ""
                session.requestNav(NavRequest.SettingsFocus("enabledModels"))
            }
            PiCommandAction.TerminalOnly -> session.notifyTerminalOnly(command)
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
                    ModelChip(state.meta.model?.id ?: state.meta.model?.name) {
                        session.refreshModels()
                        sheet = ChatSheet.Model
                    }
                    ChatTopBarIcon(
                        onClick = { searchOpen = !searchOpen },
                        contentDescription = if (searchOpen) "关闭对话内查找" else "在对话里查找",
                        icon = Icons.Filled.Search,
                    )
                    ChatTopBarIcon(
                        onClick = { session.notifyTerminalOnly(reloadCommand()) },
                        contentDescription = "重载扩展、技能与主题",
                        icon = Icons.Filled.Refresh,
                    )
                    ChatTopBarIcon(
                        onClick = { overflow = true },
                        contentDescription = "更多",
                        icon = Icons.Filled.MoreVert,
                    )
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    OverflowItem("命令面板") {
                        draft = "/"
                        overflow = false
                    }
                    OverflowItem("会话与队列") {
                        session.refreshTuiOnlyExtensions()
                        sheet = ChatSheet.Tools
                        overflow = false
                    }
                    OverflowItem("会话树") {
                        session.refreshTree()
                        session.requestNav(NavRequest.SessionTree)
                        overflow = false
                    }
                    OverflowItem("会话信息") {
                        session.refreshStats()
                        sheet = ChatSheet.Stats
                        overflow = false
                    }
                    OverflowItem("新建会话") {
                        session.newSession()
                        overflow = false
                    }
                    OverflowItem("从历史消息分支") {
                        session.refreshForkMessages()
                        sheet = ChatSheet.Fork
                        overflow = false
                    }
                    OverflowItem("复制当前会话") {
                        session.cloneSession()
                        overflow = false
                    }
                    OverflowItem("重命名") {
                        sheet = ChatSheet.Rename
                        overflow = false
                    }
                    OverflowItem("导出会话（按扩展名）") {
                        session.exportSession()
                        overflow = false
                    }
                    OverflowItem("跳到上一条提问") {
                        // F34: the first visible row as a *full-list* index — the
                        // loading row does not exist in `visibleItems`, and the
                        // hidden prefix does not exist in the rendered list.
                        val firstFull = listState.firstVisibleItemIndex - headerRows + hiddenCount
                        userRowIndices().lastOrNull { it < firstFull }?.let { row ->
                            pauseTail()
                            reveal(row)
                        }
                        overflow = false
                    }
                    OverflowItem("跳到下一条提问") {
                        val firstFull = listState.firstVisibleItemIndex - headerRows + hiddenCount
                        userRowIndices().firstOrNull { it > firstFull }?.let { row ->
                            pauseTail()
                            reveal(row)
                        }
                        overflow = false
                    }
                    OverflowItem("复制最后一条回复") {
                        copyLastAssistant(session, context)
                        overflow = false
                    }
                    OverflowItem(if (toolsExpanded) "收起全部工具输出" else "展开全部工具输出") {
                        toolsExpanded = !toolsExpanded
                        overflow = false
                    }
                    OverflowItem("循环切换模型") {
                        session.cycleModel()
                        overflow = false
                    }
                    OverflowItem("思考等级…") {
                        session.refreshThinkingLevels()
                        sheet = ChatSheet.Thinking
                        overflow = false
                    }
                    OverflowItem("循环切换思考等级") {
                        session.cycleThinkingLevel()
                        overflow = false
                    }
                    OverflowItem("选择模型…") {
                        session.refreshModels()
                        sheet = ChatSheet.Model
                        overflow = false
                    }
                    // There is no terminal entry here any more. It used to open pi's
                    // original TUI, and the user retired the terminal outright
                    // ("现在是个废品那个功能"), so the only way in is the one row on
                    // the settings home. Leaving it here would keep the most
                    // valuable part of the overflow menu pointing at the least
                    // usable surface in the app.
                    }
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // F10 (`docs/rendering-review.md`): pi's footer figures — token totals, the
        // cache-hit rate and the context percentage — put where spec §4.1 asks for
        // them, the 32dp status row under the bar. Every figure comes from pi's own
        // `getSessionStats()` (`core/agent-session.ts:3359-3407`), which is what the
        // footer renders (`components/footer.ts:130-161`); an item with no data is
        // omitted by the component rather than shown as a placeholder.
        PiStatusLine(
            stats = state.stats,
            contextWindowFallback = state.meta.model?.contextWindow,
            autoCompaction = state.meta.autoCompaction,
        )

        ExtensionStatusRow(state.extensionStatuses)

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

        if (state.transcript.isEmpty()) {
            // The two empty states are the two different waits: the engine may exist
            // and still not be reading its stdin, which is the whole reason a message
            // sent right after launch used to sit unanswered (`PiSessionViewModel
            // .engineStarting`). Saying 已就绪 in that window is the lie that made the
            // app look broken, so the window gets its own words and its own promise
            // (the message is kept, not dropped).
            val starting = session.engineStarting(state)
            PiEmptyState(
                icon = Icons.Filled.ChatBubble,
                title = if (starting) "引擎正在启动" else "引擎已就绪",
                body = if (starting) {
                    "首次启动要加载 pi 的运行时与扩展，通常要几十秒。\n" +
                        "现在发消息也可以：引擎开始工作后会立刻处理。"
                } else {
                    "pi 会读写你选定的工作区、执行命令、改代码。\n" +
                        "输入 / 查看全部命令，输入 ! 直接跑 shell 命令。"
                },
                modifier = Modifier.weight(1f),
            )
        } else {
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
            val horizontal = if (prefs.messageDensity == "compact") 12.dp else PiSpacing.pageHorizontal
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // `06 §2`'s own scroll padding is `10px 14px 12px`
                // (`direction-b-v2.html:1376`, the transcript's `.b-scroll`), so the
                // page margin is 14 and the vertical ends are not the block gap.
                contentPadding = PaddingValues(
                    start = horizontal,
                    end = horizontal,
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
                        // F19 (`docs/rendering-review.md`) / RR-P10: the two of the
                        // renderer's five callbacks whose target already exists in
                        // this app are supplied here instead of leaving the blocks'
                        // gated labels unreachable. Model row → the picker sheet
                        // (`ChatSheet.Model`); branch summary → the session tree,
                        // which is where the app can move the leaf (the same target
                        // the palette's 会话树 action uses, `:404-407`).
                        onModelClick = {
                            session.refreshModels()
                            sheet = ChatSheet.Model
                        },
                        onBranchClick = {
                            session.refreshTree()
                            session.requestNav(NavRequest.SessionTree)
                        },
                        // §4.8's 编辑并从此分叉: pi's user-message row opens the
                        // fork picker and re-runs from that message
                        // (`interactive-mode.ts:5216` / `docs/sessions.md:31`);
                        // `forkFrom` is the same `fork` command that picker sends,
                        // so the entry id here is the projected block's key.
                        onForkFromMessage = { entryId -> session.forkFrom(entryId) },
                        // The other three (`onImageClick`, `onDiffOpenFull`,
                        // `onErrorRetry`) had no target anywhere in the app — no
                        // image viewer, no full-screen diff route, no retry action —
                        // so the review's other allowed branch was taken: the dead
                        // parameters and their gated labels are deleted from the
                        // blocks, rather than left claiming a feature.
                    )
                }
            }
            // Spec §4.5's affordance: once the follow has been paused by the user's
            // own scrolling (or by a jump into history), this is the explicit way back.
            // pi draws its own version only while the follow is off
            // (`tui-alt-screen.ts:1620`) and clicking it changes the position directly
            // (`:1015-1021` → `scroll-view.ts:477-480`); the count is the App's
            // addition, from spec §4.5's 「↓ 回到最新（N）」.
            if (!following) {
                // v2 draws this as a bordered pill with **no elevation**
                // (`direction-b-v2.html:1510`: `background:surf-high`,
                // `border:1px solid borderMuted`, `padding:6px 12px`, and no
                // `box-shadow` anywhere in the board — `04 §2.3`: 层级不用阴影).
                // The app's copy carried `shadowElevation = 3.dp`, the only shadow in
                // its half of the UI, and a `↓` glyph v2 does not draw; the words
                // already say what the tap does.
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clickable(onClickLabel = "回到最新", onClick = { reArmTail() }),
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, PiTheme.palette.borderMuted),
                ) {
                    Text(
                        text = if (unseenRows > 0) "回到最新 · $unseenRows" else "回到最新",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
        val paletteQuery = paletteQueryOf(draft)
        if (paletteQuery != null) {
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
            onPickImage = { imagePicker.launch("image/*") },
            // pi's ctrl+g lives in the key-hint row rather than on the send button:
            // that button is send/stop, and an editor handoff is neither. The chips
            // beside it (`/`, `!`, `@`, `图片`) are the same kind of affordance.
            onOpenExternalEditor = { openInExternalEditor() },
            onDraftChange = { draft = it },
            thinkingLevel = state.meta.thinkingLevel,
            streaming = state.streaming,
            canFollowUp = state.streaming && draft.isNotBlank(),
            onCycleThinking = { session.cycleThinkingLevel() },
            onOpenPalette = { if (draft.isBlank()) draft = "/" },
            onOpenBash = { if (draft.isBlank()) draft = "!" },
            // `@` sits behind the phone keyboard's symbol page, and a mention only
            // opens at a token boundary (`PiFileMentions.prefixOf`, pi's
            // `PATH_DELIMITERS`), so this chip types exactly the trigger character —
            // nothing else, which is why it does nothing when a mention is already
            // open. The `/` and `!` chips beside it are the same kind of affordance.
            onOpenMention = { if (PiFileMentions.prefixOf(draft) == null) draft += "@" },
            onFollowUp = {
                // pi's alt+enter: queue this message for after the current turn
                // (`interactive-mode.ts:4126-4155` → `session.prompt(text,
                // { streamingBehavior: "followUp" })`, which is `follow_up` on the
                // wire). Only offered while streaming, because that is the only
                // time pi's own binding queues rather than submits.
                //
                // The attachments go with it: pi's own call hands the editor's
                // images to `_queueFollowUp` (`agent-session.ts:1225-1226`), and
                // F21's finding in `docs/gap-disposition.md` §10 found this call
                // dropping them — the chip looked like it queued the message and
                // the picture was silently gone.
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
                    is ComposerRoute.Unreachable -> session.notifyTerminalOnly(route.command)
                    is ComposerRoute.Unknown -> session.notifyUnknownCommand(route.name)
                }
            },
            // pi's Escape (`interactive-mode.ts:2855-2857`):
            // `restoreQueuedMessagesToEditor({ abort: true })` clears the queue,
            // puts the queued text **and** the current editor text back into the
            // editor, then aborts. Dropping the queued text here is what made Stop
            // silently destroy what the user had typed.
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
                session.refreshTree()
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

/**
 * The transcript's search field, modelled on pi's fullscreen search panel
 * (`keybindings.md` `tui.altScreen.search` / `searchNext` / `searchPrevious` /
 * `searchClose`): a query, a match count, previous/next, and a close.
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
    Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("在对话里查找") },
                textStyle = PiTheme.text.mono,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (matchCount == 0) "0/0" else "${cursor.coerceIn(0, matchCount - 1) + 1}/$matchCount",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onPrevious, enabled = matchCount > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一个匹配")
            }
            IconButton(onClick = onNext, enabled = matchCount > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下一个匹配")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭查找")
            }
        }
    }
}

/**
 * The AppBar's reload button asks the palette's `/reload` row what to say.
 *
 * pi's `/reload` has no RPC command — it rebinds the whole runtime inside pi
 * (`agent-session.ts` `reload()`), so this app cannot run it. What the app *can* do
 * is restart the engine, which re-reads `settings.json` and rescans every resource
 * directory (`core/agent-session-runtime.ts:226-252` → `createRuntime`); the palette
 * row carries that as its `appLanding`. Reusing the row keeps one answer to "where
 * does `/reload` live here" instead of two that can drift apart.
 */
private fun reloadCommand(): PiSlashCommand =
    PI_BUILTIN_SLASH_COMMANDS.first { it.name == "reload" }

/** `get_last_assistant_text`, then the system clipboard; this is pi's `/copy`. */
private fun copyLastAssistant(session: PiSessionViewModel, context: Context) {
    session.copyLastAssistantText { text ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("pi", text))
    }
}

/** One row of the AppBar overflow menu. */
@Composable
private fun OverflowItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
    )
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
        if (steering > 0) QueueChip(glyph = "⇢", tone = PiTheme.palette.warning, text = "穿插 $steering")
        if (steering > 0 && followUp > 0) Spacer(Modifier.width(PiSpacing.inline))
        if (followUp > 0) QueueChip(glyph = "⇣", tone = PiTheme.palette.muted, text = "后续 $followUp")
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
    onCycleThinking: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenBash: () -> Unit,
    onOpenMention: () -> Unit,
    onPickImage: () -> Unit,
    /** `app.editor.external`: hand the draft to an external editor (`ACTION_EDIT`). */
    onOpenExternalEditor: () -> Unit,
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
                KeyHint("!", onOpenBash)
                KeyHint("!!", onOpenBash)
                KeyHint("@", onOpenMention)
                KeyHint("图片", onPickImage)
                // pi's ctrl+g (`app.editor.external`, keybindings.md:129). It sits in
                // the key-hint row, not on the send button: that button is send/stop
                // and an editor handoff is neither, so there is no gesture to share
                // or to collide with.
                KeyHint("编辑器", onOpenExternalEditor)
                // pi's `alt+enter` (`app.message.followUp`, keybindings.md:165):
                // queue this text for the end of the current turn instead of
                // steering it into the middle. The affordance only exists while a
                // turn is running, because that is the only time the two delivery
                // choices differ in pi as well. It is drawn as one more key chip:
                // that is the family it belongs to.
                if (streaming) {
                    KeyChip(label = "后续", enabled = canFollowUp, onClick = onFollowUp)
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
private val TailFollowSaver: Saver<TailFollow, Any> = listSaver(
    save = { it.savedState() },
    restore = { saved -> TailFollow.fromSavedState(saved) },
)

@Composable
private fun KeyHint(label: String, onClick: () -> Unit) {
    KeyChip(label = label, enabled = true, onClick = onClick)
}

/**
 * [KeyHint]'s implementation, with the one optional member of the family: pi's
 * `alt+enter` 后续 chip is drawn muted (and takes no tap) until there is a non-blank
 * draft to queue, because queueing an empty message is not an action pi offers.
 *
 * `06 §2` 输入区「键位 chip `24×24` 圆角 6（`/` `!` `!!` `@` `图片` `编辑器`）」, and v2
 * draws each one as `height:24;min-width:24;padding:0 7px;border-radius:6;
 * background:surf-highest` with a **mono 12** label (`direction-b-v2.html:1430-1431`).
 * The row used to be bare mono text with no chip at all, so it read as five stray
 * characters rather than as keys.
 */
@Composable
private fun KeyChip(label: String, enabled: Boolean, onClick: () -> Unit) {
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
            .then(if (enabled) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PiTheme.text.monoSmall,
            color = content,
            maxLines = 1,
        )
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
