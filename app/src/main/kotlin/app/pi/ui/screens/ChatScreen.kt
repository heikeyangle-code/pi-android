package app.pi.ui.screens

import app.pi.ui.blocks.decodePiImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.rpc.AssistantText
import app.pi.rpc.BranchSummary
import app.pi.rpc.CompactionMarker
import app.pi.rpc.DateSeparator
import app.pi.rpc.ErrorText
import app.pi.rpc.HookMessage
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
import app.pi.ui.NavRequest
import app.pi.ui.PiSessionViewModel
import app.pi.ui.blocks.BlockRenderer
import app.pi.ui.chat.BashPanel
import app.pi.ui.chat.ComposerRoute
import app.pi.ui.chat.ForkPickerSheet
import app.pi.ui.chat.MentionPalette
import app.pi.ui.chat.ModelPickerSheet
import app.pi.ui.chat.PiCommandAction
import app.pi.ui.chat.PiCommandSource
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.RenameSessionDialog
import app.pi.ui.chat.SessionStatsSheet
import app.pi.ui.chat.SessionToolsSheet
import app.pi.ui.chat.SlashPalette
import app.pi.ui.chat.ThinkingPickerSheet
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
import kotlinx.coroutines.delay

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
     * Open the workbench terminal.
     *
     * The terminal is where the surfaces the RPC path cannot carry live —
     * `custom()`, `setFooter`/`setHeader`, custom editors, extension renderers —
     * because `pi` itself runs there. It is a plain shell, so the user types `pi`
     * to reach them; the caller owns only the destination, which is why this is
     * one callback and no longer a tab selection.
     */
    onOpenTerminal: () -> Unit,
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
    ChatBody(state = state, session = session, bottomInset = bottomInset, onOpenTerminal = onOpenTerminal)
}

/** Which bottom sheet the chat screen has open, if any. */
private enum class ChatSheet { Model, Thinking, Tools, Stats, Fork, Rename }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBody(
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
    bottomInset: Dp,
    onOpenTerminal: () -> Unit,
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
            session.notifyUser(
                "没有应用能编辑文本（ACTION_EDIT）：草稿保持原样。设置 →「外部编辑器」说明了这条的来历。",
                warning = true,
            )
        }
    }
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
                    val bytes = runCatching {
                        resolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    when {
                        bytes == null || bytes.isEmpty() -> session.notifyUser(
                            "读取所选图片失败：无法打开这个文件（权限被拒或文件已被删除）。请重新选择，或换一张本地图片。",
                            warning = true,
                        )

                        bytes.size > MAX_ATTACHMENT_BYTES -> session.notifyUser(
                            "图片太大（${bytes.size / (1024 * 1024)} MB），上限是 " +
                                "${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB；它要整段随消息发送。" +
                                "请先压缩或裁剪后再试。",
                            warning = true,
                        )

                        else -> attachments = attachments + PiImage(
                            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                            mimeType = mime.ifEmpty { "image/*" },
                        )
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

    // Follow-the-tail state. The spec is explicit (§4.5): follow the newest block
    // by default, and **never steal the scroll** once the user has scrolled up —
    // unlocking the follow and showing a "back to newest" affordance instead.
    // F4 (`docs/rendering-review.md`): the previous implementation animated to the
    // last item on *every* token, so scrolling up during a stream was yanked back
    // on the next token, and each token restarted an animation.
    var following by rememberSaveable { mutableStateOf(true) }
    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 2
        }
    }
    // Only ever unlocks: scrolling back down does not silently re-arm, because the
    // user asked to stop following. The button below re-arms it explicitly.
    LaunchedEffect(atBottom) { if (!atBottom) following = false }
    LaunchedEffect(state.revision, following, renderedItems.size, headerRows) {
        if (!following) return@LaunchedEffect
        // F34: the tail of the rendered window rather than of the whole session. The
        // window always ends on the newest row, so this is the same row as before —
        // and the +`headerRows` accounts for the loading row in front of it.
        val last = renderedItems.size - 1 + headerRows
        if (last >= headerRows) {
            // Non-suspending while streaming: an animation per token is exactly the
            // stutter F4 describes, and a jump is what "follow" means here.
            if (state.streaming) listState.scrollToItem(last) else listState.animateScrollToItem(last)
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
            // asks for the newest block again.
            following = false
            reveal(index)
        }
    }
    LaunchedEffect(pendingJump, renderedItems.size, hiddenCount, headerRows) {
        val row = pendingJump ?: return@LaunchedEffect
        val index = row - hiddenCount + headerRows
        if (renderedItems.isNotEmpty() && index in 0 until renderedItems.size + headerRows) {
            listState.animateScrollToItem(index)
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

    // Follow the tail while streaming, but never steal the scroll: the effect
    // above only runs while `following` is armed, and that flag is dropped the
    // moment the user scrolls away from the bottom.

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
            PiCommandAction.OpenSessions -> session.requestNav(NavRequest.Sessions)
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
        TopAppBar(
            title = {
                Column {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        session.engineLabel(state),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                ModelChip(state.meta.model?.id ?: state.meta.model?.name) {
                    session.refreshModels()
                    sheet = ChatSheet.Model
                }
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = if (searchOpen) "关闭对话内查找" else "在对话里查找",
                    )
                }
                IconButton(onClick = { session.notifyTerminalOnly(reloadCommand()) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重载扩展、技能与主题")
                }
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
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
                            following = false
                            reveal(row)
                        }
                        overflow = false
                    }
                    OverflowItem("跳到下一条提问") {
                        val firstFull = listState.firstVisibleItemIndex - headerRows + hiddenCount
                        userRowIndices().firstOrNull { it > firstFull }?.let { row ->
                            following = false
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
                    // The terminal, named for what it now is. It used to say "打开原版
                    // pi TUI", which described the tab the workbench opened on; the
                    // workbench opens a shell, so the entry says where it goes and
                    // the command to type once there.
                    OverflowItem("打开终端（输入 pi 进原版 TUI）") {
                        onOpenTerminal()
                        overflow = false
                    }
                }
            },
        )

        // F10 (`docs/rendering-review.md`): pi's footer figures — token totals, the
        // cache-hit rate and the context percentage — put where spec §4.1 asks for
        // them, the 32dp status row under the bar. Every figure comes from pi's own
        // `getSessionStats()` (`core/agent-session.ts:3359-3407`), which is what the
        // footer renders (`components/footer.ts:130-161`); an item with no data is
        // omitted by the component rather than shown as a placeholder.
        PiStatusLine(
            stats = state.stats,
            latestUsage = state.lastUsage,
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
            // scaled around the spec's own gap. F11 (`docs/rendering-review.md`):
            // blocks used to pad themselves as well, so the real gap was
            // 18 (spacedBy) + 9 + 9 (BlockColumn) = 36 dp and the prose column lost
            // 16 dp on each side; spec §7.4 asks for 块间距 16dp, and the list is now
            // the only place that margins.
            val blockSpacing = when (prefs.messageDensity) {
                "compact" -> PiSpacing.unit / 2
                "cozy" -> PiSpacing.unit * 4 / 3
                else -> PiSpacing.screen
            }
            val horizontal = if (prefs.messageDensity == "compact") 12.dp else PiSpacing.screen
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = PiSpacing.screen, horizontal = horizontal),
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
                    BlockRenderer(
                        item = item,
                        modifier = rowModifier,
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
            // Spec §4.5's affordance: once the follow has been unlocked by the
            // user's own scrolling, this is the only thing that re-arms it.
            if (!following) {
                SmallFloatingActionButton(
                    onClick = { following = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到最新")
                }
            }
            }
        }

        if (state.queueSteering > 0 || state.queueFollowUp > 0) {
            QueueRow(steering = state.queueSteering, followUp = state.queueFollowUp)
        }

        ExtensionWidgetStack(
            widgets = state.extensionWidgets.filter { it.placement == WidgetPlacement.AboveEditor },
        )

        state.bash?.let { bashRun ->
            BashPanel(
                run = bashRun,
                onAbort = { session.abortBash() },
                onDismiss = { session.dismissBash() },
                modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = 4.dp),
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
                modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = 4.dp),
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
                modifier = Modifier.padding(horizontal = PiSpacing.screen, vertical = 4.dp),
            )
        }

        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiSpacing.screen, vertical = 4.dp),
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
            onOpenTui = onOpenTerminal,
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
            },
            onSend = {
                when (val route = routeComposerText(draft, state.commands)) {
                    // An image with no caption is still a message.
                    ComposerRoute.Empty -> if (attachments.isNotEmpty()) {
                        session.send("", attachments)
                        attachments = emptyList()
                    }
                    is ComposerRoute.Message -> {
                        session.send(route.text, attachments)
                        draft = ""
                        attachments = emptyList()
                    }
                    is ComposerRoute.Bash -> {
                        session.runBash(route.command, route.excludeFromContext)
                        draft = ""
                    }
                    is ComposerRoute.Command -> {
                        draft = ""
                        pick(route.command, route.args)
                    }
                    // The draft stays: the notice explains what to do, and throwing
                    // the user's text away would make the explanation harder to act
                    // on.
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
 * pi's cancel semantics, which Stop has to reproduce exactly
 * (`restoreQueuedMessagesToEditor`, `interactive-mode.ts:4387-4406`):
 *
 * ```
 * const combinedText = [queuedText, currentText].filter((t) => t.trim()).join("\n\n");
 * ```
 *
 * Queued messages come first, the text already in the editor follows, and blank
 * halves are dropped — so pressing Stop twice does not accumulate blank lines and
 * does not lose the draft.
 */
private fun mergeRestoredQueue(restored: List<String>, current: String): String =
    listOf(restored.filter { it.isNotBlank() }.joinToString("\n\n"), current)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

/**
 * The text a block contributes to transcript search.
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
    Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 4.dp)) {
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
 * pi's `/reload` has no RPC command at all — it rebinds the whole runtime inside
 * pi (`agent-session.ts` `reload()`, reachable only from an extension command,
 * `docs/extensions.md` §"Reloading") — so the AppBar's reload button says where
 * it *can* be run instead of pretending it can be run here.
 */
private fun reloadCommand(): PiSlashCommand = PiSlashCommand(
    name = "reload",
    description = "重载快捷键、扩展、技能、模板、主题与上下文文件",
    source = PiCommandSource.Builtin,
    sourceTag = null,
    action = PiCommandAction.TerminalOnly,
)

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
    Surface(
        modifier = Modifier
            .padding(end = 4.dp)
            .clickable(onClick = onClick),
        shape = PiShapes.badge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label ?: "选择模型",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QueueRow(steering: Int, followUp: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (steering > 0) QueueChip("穿插 $steering")
        if (steering > 0 && followUp > 0) Spacer(Modifier.width(8.dp))
        if (followUp > 0) QueueChip("后续 $followUp")
    }
}

@Composable
private fun QueueChip(text: String) {
    Surface(shape = PiShapes.badge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    onOpenTui: () -> Unit,
    onPickImage: () -> Unit,
    /** `app.editor.external`: hand the draft to an external editor (`ACTION_EDIT`). */
    onOpenExternalEditor: () -> Unit,
    onFollowUp: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen)
                .heightIn(min = 56.dp, max = 160.dp),
            placeholder = { Text("输入消息，/ 选命令，! 直接跑命令，@ 提及文件") },
            shape = PiShapes.inputMultiline,
            // A state channel, not decoration: pi paints its editor with the
            // current thinking level's token and switches to `bashMode` when the
            // line starts with `!` (`interactive-mode.ts` `updateEditorBorderColor`).
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (bashModeOf(draft)) {
                    PiTheme.palette.bashMode
                } else {
                    PiTheme.palette.thinking(thinkingLevel)
                },
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            trailingIcon = {
                IconButton(onClick = { if (streaming) onStop() else onSend() }) {
                    Icon(
                        if (streaming) Icons.Filled.Stop else Icons.Filled.Send,
                        contentDescription = if (streaming) "停止" else "发送",
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(percent = 50),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                // choices differ in pi as well.
                if (streaming) {
                    Surface(
                        modifier = Modifier.clickable(enabled = canFollowUp, onClick = onFollowUp),
                        shape = PiShapes.badge,
                        color = if (canFollowUp) {
                            PiTheme.palette.accent.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ) {
                        Text(
                            "后续",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (canFollowUp) {
                                PiTheme.palette.accent
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // pi's escape hatch, one tap from the composer: the surfaces RPC
                // cannot carry (`custom()`, `setFooter`/`setHeader`, custom
                // editors, terminal input, the extension renderers) exist in pi's
                // own TUI, and the terminal is where `pi` runs. The chip says
                // "终端" rather than "原版 TUI" because that is what it opens — a
                // shell, in which the user types `pi` (audit §6.11).
                Text(
                    "终端",
                    modifier = Modifier
                        .clickable(onClick = onOpenTui)
                        .padding(end = 12.dp),
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The signature element: pi encodes the thinking level as a colour
                // temperature, and this chip is that language carried into the GUI.
                // A tap cycles it — pi's `app.thinking.cycle` binding, the action
                // users reach for most — and the full supported set (which comes
                // from the model) is one entry away in the overflow menu.
                Surface(
                    modifier = Modifier.clickable(onClick = onCycleThinking),
                    shape = PiShapes.badge,
                    color = PiTheme.palette.thinking(thinkingLevel).copy(alpha = 0.18f),
                ) {
                    Text(
                        "◐ ${thinkingLabelOf(thinkingLevel)}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = PiTheme.palette.thinking(thinkingLevel),
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

@Composable
private fun KeyHint(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(end = 10.dp),
        style = PiTheme.text.monoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
 */
private const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024
