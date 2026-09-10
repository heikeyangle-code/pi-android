package app.pi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    // pi's `app.tools.expand` (Ctrl+O): one switch that expands or collapses every
    // expandable row. Extensions cannot drive it over RPC — `setToolsExpanded` is
    // a documented no-op there (`rpc-mode.ts:303-310`) and this app does not
    // pretend otherwise — so the preference belongs to the user, not to a wire
    // message.
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

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

    // Follow the tail while streaming, but never steal the scroll: only scroll
    // when the count grows, and let the user's own scrolling win afterwards.
    LaunchedEffect(state.revision) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

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
            PiCommandAction.ExportHtml -> session.exportHtml(args.takeIf { it.isNotBlank() })
            PiCommandAction.CopyLastAssistant -> copyLastAssistant(session, context)
            PiCommandAction.RenameSession -> sheet = ChatSheet.Rename
            PiCommandAction.SessionStats -> {
                session.refreshStats()
                sheet = ChatSheet.Stats
            }
            PiCommandAction.NewSession -> session.newSession()
            PiCommandAction.Compact -> session.compact(args.takeIf { it.isNotBlank() })
            PiCommandAction.OpenSessions -> session.requestNav(NavRequest.Sessions)
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
                    OverflowItem("导出为 HTML") {
                        session.exportHtml()
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

        if (state.transcript.isEmpty()) {
            PiEmptyState(
                icon = Icons.Filled.ChatBubble,
                title = "引擎已就绪",
                body = "pi 会读写你选定的工作区、执行命令、改代码。\n" +
                    "输入 / 查看全部命令，输入 ! 直接跑 shell 命令。",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    vertical = PiSpacing.unit,
                    horizontal = PiSpacing.screen,
                ),
                verticalArrangement = Arrangement.spacedBy(PiSpacing.unit),
            ) {
                // Keyed by the reducer's stable per-block key, which is what lets
                // Compose animate the row that changed while streaming instead of
                // recomposing the list.
                items(state.transcript, key = { it.key }) { item ->
                    BlockRenderer(
                        item = item,
                        toolsDefaultExpanded = toolsExpanded,
                        thinkingDefaultExpanded = false,
                    )
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

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            thinkingLevel = state.meta.thinkingLevel,
            streaming = state.streaming,
            onCycleThinking = { session.cycleThinkingLevel() },
            onOpenPalette = { if (draft.isBlank()) draft = "/" },
            onOpenBash = { if (draft.isBlank()) draft = "!" },
            onOpenTui = { session.requestNav(NavRequest.Workbench) },
            onSend = {
                when (val route = routeComposerText(draft, state.commands)) {
                    ComposerRoute.Empty -> Unit
                    is ComposerRoute.Message -> {
                        session.send(route.text)
                        draft = ""
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
            onStop = { session.stop() },
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
                session.exportHtml()
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
    onCycleThinking: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenBash: () -> Unit,
    onOpenTui: () -> Unit,
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
