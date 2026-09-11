package app.pi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.pi.rpc.SystemPrompt
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
import app.pi.ui.chat.ModelPickerSheet
import app.pi.ui.chat.PiCommandAction
import app.pi.ui.chat.PiCommandSource
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.RenameSessionDialog
import app.pi.ui.chat.SessionStatsSheet
import app.pi.ui.chat.SessionToolsSheet
import app.pi.ui.chat.SlashPalette
import app.pi.ui.chat.ThinkingPickerSheet
import app.pi.ui.chat.routeComposerText
import app.pi.ui.chat.thinkingLabelOf
import app.pi.ui.components.PiEmptyState
import app.pi.ui.extension.ExtensionStatusRow
import app.pi.ui.extension.ExtensionWidgetStack
import app.pi.ui.extension.WidgetPlacement
import app.pi.ui.extension.windowTitleOf
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
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
    ChatBody(state = state, session = session, bottomInset = bottomInset)
}

/** Which bottom sheet the chat screen has open, if any. */
private enum class ChatSheet { Model, Thinking, Tools, Stats, Fork, Rename }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBody(
    state: PiSessionViewModel.UiState,
    session: PiSessionViewModel,
    bottomInset: Dp,
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
    val scope = rememberCoroutineScope()

    // Image attachments. pi's `prompt`/`steer`/`follow_up` all carry `images`
    // (`rpc-types.ts:22-24`) and the whole lower pipe already exists
    // (`PiImage`, `Commands.putImages`, `PiEngineSession.prompt(images)`), so the
    // only missing piece was a way to construct a non-empty list. A phone picker
    // is the app's own job; pi has no picker to copy.
    var attachments by remember { mutableStateOf<List<PiImage>>(emptyList()) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/*"
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                session.notifyUser("读取所选图片失败", warning = true)
            } else {
                attachments = attachments + PiImage(
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    mimeType = mime,
                )
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
    val userRowIndices = remember(visibleItems) {
        visibleItems.mapIndexedNotNull { index, item -> if (item is UserMessage) index else null }
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
    LaunchedEffect(state.revision, following, visibleItems.size) {
        if (!following) return@LaunchedEffect
        val last = visibleItems.size - 1
        // Non-suspending while streaming: an animation per token is exactly the
        // stutter F4 describes, and a jump is what "follow" means here.
        if (last >= 0) {
            if (state.streaming) listState.scrollToItem(last) else listState.animateScrollToItem(last)
        }
    }
    LaunchedEffect(searchMatches, searchCursor) {
        val index = searchMatches.getOrNull(searchCursor.coerceIn(0, (searchMatches.size - 1).coerceAtLeast(0)))
        if (index != null) {
            // Jumping to a match is navigation, so following stops until the user
            // asks for the newest block again.
            following = false
            listState.animateScrollToItem(index)
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
                        userRowIndices.lastOrNull { it < listState.firstVisibleItemIndex }?.let { row ->
                            following = false
                            scope.launch { listState.animateScrollToItem(row) }
                        }
                        overflow = false
                    }
                    OverflowItem("跳到下一条提问") {
                        userRowIndices.firstOrNull { it > listState.firstVisibleItemIndex }?.let { row ->
                            following = false
                            scope.launch { listState.animateScrollToItem(row) }
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
                    OverflowItem("打开原版 pi TUI") {
                        session.requestNav(NavRequest.Workbench)
                        overflow = false
                    }
                }
            },
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
            PiEmptyState(
                icon = Icons.Filled.ChatBubble,
                title = "引擎已就绪",
                body = "pi 会读写你选定的工作区、执行命令、改代码。\n" +
                    "输入 / 查看全部命令，输入 ! 直接跑 shell 命令。",
                modifier = Modifier.weight(1f),
            )
        } else {
            // `app.appearance.messageDensity`: the transcript's block rhythm. The
            // default is pi's own single `--line-height` gap (PiSpacing.unit).
            val blockSpacing = when (prefs.messageDensity) {
                "compact" -> PiSpacing.unit / 2
                "cozy" -> PiSpacing.unit * 4 / 3
                else -> PiSpacing.unit
            }
            val horizontal = if (prefs.messageDensity == "compact") 12.dp else PiSpacing.screen
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = PiSpacing.unit, horizontal = horizontal),
                verticalArrangement = Arrangement.spacedBy(blockSpacing),
            ) {
                // Keyed by the reducer's stable per-block key, which is what lets
                // Compose animate the row that changed while streaming instead of
                // recomposing the list.
                itemsIndexed(visibleItems, key = { _, item -> item.key }) { index, item ->
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

        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachments.forEachIndexed { index, _ ->
                    Surface(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clickable { attachments = attachments.filterIndexed { i, _ -> i != index } },
                        shape = PiShapes.badge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            "图片 ${index + 1} ✕",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Composer(
            draft = draft,
            onPickImage = { imagePicker.launch("image/*") },
            onDraftChange = { draft = it },
            thinkingLevel = state.meta.thinkingLevel,
            streaming = state.streaming,
            canFollowUp = state.streaming && draft.isNotBlank(),
            onCycleThinking = { session.cycleThinkingLevel() },
            onOpenPalette = { if (draft.isBlank()) draft = "/" },
            onOpenBash = { if (draft.isBlank()) draft = "!" },
            onOpenTui = { session.requestNav(NavRequest.Workbench) },
            onFollowUp = {
                // pi's alt+enter: queue this message for after the current turn
                // (`interactive-mode.ts:4126-4155` → `session.prompt(text,
                // { streamingBehavior: "followUp" })`, which is `follow_up` on the
                // wire). Only offered while streaming, because that is the only
                // time pi's own binding queues rather than submits.
                session.sendFollowUp(draft)
                draft = ""
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
    is SystemPrompt -> item.fullText
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
        // The honest scope of this search: it finds and jumps to the message that
        // contains the query and tints it, but the matched characters inside the
        // markdown are not individually recoloured.
        if (query.isNotBlank()) {
            Text(
                "匹配到消息块并跳转；块内文字不做逐字高亮。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    onOpenTui: () -> Unit,
    onPickImage: () -> Unit,
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
            placeholder = { Text("输入消息，/ 选命令，! 直接跑命令") },
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
                KeyHint("图片", onPickImage)
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
                // editors, terminal input, the extension renderers) exist in the
                // original TUI, and this is how a user reaches them without being
                // told to go looking (audit §6.11).
                Text(
                    "原版 TUI",
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
