package app.pi.ui.screens

import app.pi.ui.blocks.PiImageCache
import app.pi.ui.blocks.decodePiImage
import app.pi.ui.blocks.piImageDecodeGate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import app.pi.ui.earlierRowText
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
import app.pi.ui.chat.earlierRowHeightPx
import app.pi.ui.chat.freshRowKeysAfter
import app.pi.ui.chat.hiddenRows
import app.pi.ui.chat.itemIndexOfVisibleRow
import app.pi.ui.chat.mayArmEarlier
import app.pi.ui.chat.thinkingLabelOf
import app.pi.ui.chat.unlistedBuiltinHint
import app.pi.ui.components.PiContextRing
import app.pi.ui.render.LocalPiMarkdownImmediate
import app.pi.ui.render.LocalPiMarkdownParsed
import app.pi.ui.render.RowHeightCache
import app.pi.ui.render.piMarkdownParseBudget
import app.pi.ui.render.rememberedRowHeight
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.extension.ExtensionInfoCards
import app.pi.ui.extension.ExtensionWidgetStack
import app.pi.ui.extension.WidgetPlacement
import app.pi.ui.extension.windowTitleOf
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import app.pi.ui.theme.stateToneColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
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

/** `Icon`'s default box, which is what the 「加载更早」 row's glyph measures at. */
private val EARLIER_ROW_ICON = 24.dp

/** The 6 dp between that glyph and its label (`Spacer(Modifier.width(6.dp))`). */
private val EARLIER_ROW_ICON_GAP = 6.dp

/** The row's own vertical padding (`padding(vertical = 8.dp)`), 8 top and 8 bottom. */
private val EARLIER_ROW_PADDING = 8.dp

/**
 * 「加载更早」 drawn as an **overlay** over the transcript list, not as its item 0.
 *
 * It is the same row it always was — same `Icon`, same 6 dp, same `Text(PiTheme.text.meta)`,
 * same centred `Row`, same `fillMaxWidth().clickable{…}.padding(vertical = 8.dp)` order so the
 * ripple covers the same area and the tap target is the same one. What changed is where it
 * lives: `docs/scroll-diagnosis.md` §3.4 (landed as D51) took it out of the `LazyColumn`
 * because while it was item 0 its key stayed at index 0 across a prepend, which defeated the
 * list's own key anchoring and made every 「加载更早」 batch move the content under the reader.
 * The caller owns its position and the band the list reserves for it.
 */
@Composable
private fun EarlierRowsRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = EARLIER_ROW_PADDING),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = PiTheme.palette.muted,
            modifier = Modifier.size(EARLIER_ROW_ICON),
        )
        Spacer(Modifier.width(EARLIER_ROW_ICON_GAP))
        Text(
            text = text,
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
    }
}

/**
 * The height that row occupies, in dp — computed **before** the list is laid out, because two
 * things depend on it and both would otherwise need a second layout pass: the band the list
 * reserves in `contentPadding.top`, and the overlay's own offset. A measure → pad → measure
 * dependency is what this avoids (`design/ui-refactor/08-hang-diagnosis.md`).
 *
 * `Row` is as tall as its tallest child plus its padding, so the number is
 * `max(icon, the label's line box) + 16` — `earlierRowHeightPx` in `ui/chat/TailFollow.kt`,
 * where the harness pins the arithmetic. The label's line box is measured with the same
 * `TextMeasurer` the `Text` composable uses internally: the same merged `TextStyle` (the
 * composition's `LocalTextStyle` merged with `PiTheme.text.meta`, which is exactly what
 * `Text(text, style = …)` renders), the same `LocalDensity` (font scale included), the same
 * font resolver and the same `softWrap`/`maxLines`/`overflow` defaults, and the width the
 * `Row` leaves it — the window minus the list's own horizontal padding, the icon and the
 * spacer. That is what makes this exact rather than an estimate of "one line of meta text".
 *
 * Keyed on the inputs, so a recomposition per streamed token re-measures nothing.
 */
@Composable
private fun earlierRowHeight(text: String): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.merge(PiTheme.text.meta)
    val windowWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val heightPx = remember(text, labelStyle, density, windowWidthPx, measurer) {
        val pagePx = with(density) { PiSpacing.pageHorizontal.roundToPx() }
        val iconPx = with(density) { EARLIER_ROW_ICON.roundToPx() }
        val gapPx = with(density) { EARLIER_ROW_ICON_GAP.roundToPx() }
        val paddingPx = with(density) { (EARLIER_ROW_PADDING * 2).roundToPx() }
        val availablePx = (windowWidthPx - pagePx * 2 - iconPx - gapPx).coerceAtLeast(0)
        val linePx = measurer.measure(
            text = text,
            style = labelStyle,
            constraints = Constraints(maxWidth = availablePx),
        ).size.height
        earlierRowHeightPx(iconPx = iconPx, textHeightPx = linePx, verticalPaddingPx = paddingPx)
    }
    return with(density) { heightPx.toDp() }
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
                            // **No notice here on purpose.** There used to be one
                            // (「已放入工作区：<path>」) so that a new file in 工作区 was not
                            // unexplained; the path that has just landed in the composer says
                            // the same thing, and it says it without covering the composer for
                            // four seconds with a snackbar that has no dismiss action (the
                            // app-wide `ExtensionUiHost` host, `Info` tone →
                            // `SnackbarDuration.Short`, `actionLabel = null`). The user asked
                            // for it to go: 「发送完为什么有个提示呢？占住我的输入框了好几秒，
                            // 去不掉，把这个提示删掉。」 The **failure** arms below stay — those
                            // explain why nothing was copied, and there is no path in the
                            // composer to say it for them.
                        }
                        // Each failure names its own cause and inserts nothing: a
                        // `content://` string or a guessed path would be answered by pi
                        // with an error about a file the user never named.
                        WorkspaceCopy.Unreadable -> session.notifyUser(
                            "读不到这个文件：来源没有把它打开（权限被拒或文件已被删除）。" +
                                "请重新选择，或先把文件存到手机上再选。",
                            warning = true,
                        )

                        // 空间不够是**磁盘**说出来的事，不是「文件太大」：这条路上限只有可用空间
                        // （见 `AttachmentBudget.workspaceSpace`），所以这句话里的两个数字都来自
                        // 当时的测量，而 `neededBytes` 是下界，所以说「至少」。
                        is WorkspaceCopy.OutOfSpace -> session.notifyUser(
                            "磁盘空间不足：这个文件至少需要 " +
                                "${mibLabel(result.neededBytes)} MB，工作区所在磁盘可用 " +
                                "${mibLabel(result.availableBytes)} MB" +
                                "（要留 ${mibLabel(result.marginBytes)} MB 余量）。" +
                                "它没有被复制进工作区，也没有加进这条消息；" +
                                "腾出空间后可以再选一次。",
                            warning = true,
                        )

                        WorkspaceCopy.WriteFailed -> session.notifyUser(
                            "写不进工作区（目录不可写，或写入过程中空间用尽）。" +
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
                    // `InputStream` — and only then compared the length with the cap.
                    // A cloud/gallery provider happily hands out tens of megabytes, so
                    // "the image is too large" was decided after allocating it, twice
                    // (the byte array, then the base64 string), on the thread that
                    // draws: on a phone with ~1 GB free that is an OutOfMemoryError for
                    // the app, not a warning. `readBounded` stops at the guard and
                    // reports it without ever holding more than the guard, the decode and
                    // the encode happen on IO, and the **acceptance test is the whole
                    // message's budget**, not this image's size — see [AttachmentBudget].
                    pickerScope.launch {
                        // 内联不成时的**一处**收尾：把这份文件放进工作区、把相对路径插进 composer、
                        // 说清为什么它没有随消息内联。三个原因（原图超过读取上限、按 pi 的上限压不出
                        // 足够小的版本、本条消息的内联额度不够）走的是同一段代码，所以三处的行为
                        // 不可能漂移；`bytes` 与 `uri` 二选一由调用方给（见下）。
                        suspend fun fallbackInline(sourceUri: android.net.Uri?, bytes: ByteArray?, why: String) {
                            val fallback = withContext(Dispatchers.IO) {
                                when {
                                    // **从 URI 流式复制。** 原图超过读取上限那一支只持有
                                    // `MAX_PICKED_IMAGE_BYTES + 1` 个字节 —— 那是文件的**一段前缀**，
                                    // 写进工作区会留下一个被截断的文件。流式复制没有这个问题。
                                    sourceUri != null -> copyIntoWorkspace(context, sourceUri)
                                    // 名字仍取自用户选的那份文档（外层的 `uri`，这里已知非 null）：
                                    // `bytes` 那一支手上没有 URI，名字只能从这里来。
                                    bytes != null -> copyBytesIntoWorkspace(context, bytes, displayNameOf(context, uri))
                                    else -> WorkspaceCopy.WriteFailed
                                }
                            }
                            when (fallback) {
                                is WorkspaceCopy.Copied -> {
                                    draft = if (draft.isBlank()) {
                                        fallback.relativePath
                                    } else {
                                        draft + " " + fallback.relativePath
                                    }
                                    // 这一句**不能**省，与纯文件那条路（D47）不同：那次是「用户要的就是
                                    // 一个路径」，这次是「本来要内联、改成了给路径」——消息的形状与用户预期
                                    // 不一样，而 composer 里多出来的那个路径自己不解释这件事。
                                    session.notifyUser(inlineDowngradeText(fallback.relativePath, why))
                                }
                                // 降级本身也可能失败；三种原因各自的可读句子。
                                WorkspaceCopy.Unreadable -> session.notifyUser(
                                    "读不到这个文件：来源没有把它打开（权限被拒或文件已被删除）。" +
                                        "请重新选择，或先把文件存到手机上再选。",
                                    warning = true,
                                )

                                is WorkspaceCopy.OutOfSpace -> session.notifyUser(
                                    workspaceOutOfSpaceText(fallback),
                                    warning = true,
                                )

                                WorkspaceCopy.WriteFailed -> session.notifyUser(
                                    "写不进工作区（目录不可写，或写入过程中空间用尽）。" +
                                        "这个文件没有被复制，也没有加进这条消息。",
                                    warning = true,
                                )
                            }
                        }
                        val staged = attachments.map { it.base64.length }
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching {
                                resolver.openInputStream(uri)
                                    ?.use { readBounded(it, AttachmentBudget.MAX_PICKED_IMAGE_BYTES) }
                            }.getOrNull()
                        }
                        when {
                            bytes == null || bytes.isEmpty() -> session.notifyUser(
                                "读取所选图片失败：无法打开这个文件（权限被拒或文件已被删除）。请重新选择，或换一张本地图片。",
                                warning = true,
                            )

                            // 读取上限（一条记录的大小，64 MiB；App 自己的内存门槛），不是消息的额度，也不是「太大」。
                            // 这一支以前是**拒绝**；现在原图照样进工作区、agent 照样能读它 —— 而且
                            // 走的是流式复制，所以「不把几十兆读进内存」这条理由一分没丢。
                            bytes.size > AttachmentBudget.MAX_PICKED_IMAGE_BYTES -> fallbackInline(
                                sourceUri = uri,
                                bytes = null,
                                why = "原图超过 ${mibLabel(AttachmentBudget.MAX_PICKED_IMAGE_BYTES)} MB，" +
                                    "没有整张读进内存来压缩",
                            )

                            else -> {
                                // The profile this pass works against: the **current model's**
                                // own `inputLimits.images.resize` where pi published one, pi's
                                // defaults otherwise, and the record's budget as a ceiling
                                // (`AttachmentBudget.limitsFor`). Reading it here is what makes a
                                // user's per-model profile in `models.json` actually apply —
                                // before this, the App pre-resized to pi's default 2000/4.5 MB
                                // and pi then resized again to whatever the model really allows.
                                val limits = AttachmentBudget.limitsFor(state.meta.model?.inputLimits)
                                val image = withContext(Dispatchers.IO) { compressAttachment(bytes, mime, limits) }
                                if (image == null) {
                                    // 压不出来：读不出像素，或者所有候选都超 **这个模型的** 上限。
                                    // 数字来自同一份 limits，所以句子与判定不可能对不上。
                                    fallbackInline(
                                        sourceUri = null,
                                        bytes = bytes,
                                        why = "按当前模型的上限（最长边 ${limits.maxWidth}×${limits.maxHeight}、" +
                                            "base64 ${mibLabel(limits.maxBase64Chars)} MB）" +
                                            "压不出足够小的版本",
                                    )
                                } else {
                                    when (val verdict = AttachmentBudget.decide(staged, image.base64.length)) {
                                        // `image` is non-null here: the verdict is only
                                        // asked for once there is something to add.
                                        AttachmentBudget.Verdict.Fits -> attachments = attachments + image

                                        // 本条消息的内联额度不够：不拒绝，改成「只给路径」。原图进工作区
                                        // （不是那份压缩结果 —— agent 读到的是用户选的那份文件），两个数
                                        // 据仍然来自 verdict 本身，所以句子与判定不可能对不上。
                                        is AttachmentBudget.Verdict.MessageFull -> fallbackInline(
                                            sourceUri = null,
                                            bytes = bytes,
                                            why = "本条消息的内联额度不够" +
                                                "（上限 ${mibLabel(verdict.limitBytes)} MB，" +
                                                "已用 ${mibLabel(verdict.usedBytes)} MB，" +
                                                "这张压缩后 ${mibLabel(verdict.candidateBytes)} MB）",
                                        )
                                    }
                                }
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

    LaunchedEffect(
        state.revision,
        state.streaming,
        renderedItems.size,
        // `following` is deliberately **not** a key (see above: the machine owns it and
        // writing it here would restart this effect). The two keys that replace its old
        // two jobs are `scrolling` (the gesture edges) and `atBottom` (the moment the
        // user's own scroll reaches the end, which is when a paused follow has to be
        // observed again so it can re-arm).
        scrolling,
        atBottom,
        tailPoke,
        sessionKey,
        bottomInset,
    ) {
        // **No early return while paused.** A transcript that is not following must still
        // be observed: "the user scrolled back to the bottom" and "a new row arrived
        // while the viewport happened to be at the end" are both facts only the machine
        // can turn back into `following = true` (`TailFollow.onSnapshot`'s rule 3, and
        // harness group I), and a machine that is skipped while paused can never see
        // either. Feeding it costs one arithmetic pass per publication and issues no
        // scroll while paused (`TailDecision.pin` is null whenever `!following`).
        //
        // One frame, so the rows this publication added have been measured: reading
        // `layoutInfo` before the layout pass would compute the pin from the previous
        // frame's geometry. This is a *wait*, not an observation — nothing here is
        // re-triggered by the measure that follows it.
        withFrameNanos { }
        // The state the machine went into this observation with, for the pause
        // transition below.
        val wasFollowing = following
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
        // Unconditional. The removed `if (following != decision.following)` guard was a
        // second writer that could leave the mirror armed while the machine was paused
        // (and the badge counting as if it were not), so the two states could disagree
        // for as long as nothing else moved a key.
        following = decision.following
        // The machine pauses on the gesture frame (rule 2), not in the collector: read
        // `pausedRows` off the transition, never on every snapshot, or the accumulated
        // unread count would be re-baselined by each token that arrives while paused.
        if (wasFollowing && !decision.following) pausedRows = transcriptRows
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
                        // The scan is the fallback, so it is only run when it can be the
                        // answer: `meta.model` is the chip's first choice, and while it is
                        // present this walked the whole transcript backwards on every
                        // publication — once per streamed token — to compute a value that
                        // was then discarded. Keyed on the transcript (not on its size) so
                        // the fallback cannot go stale when rows are rewritten in place.
                        val lastModelChange =
                            if (state.meta.model?.id == null && state.meta.model?.name == null) {
                                remember(state.transcript) {
                                    state.transcript.asReversed().firstNotNullOfOrNull { item ->
                                        (item as? ModelChange)?.modelId?.takeIf { it.isNotBlank() }
                                    }
                                }
                            } else {
                                null
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
                        // Composed only while it is open. `PiMenu` returns immediately
                        // when `!expanded`, but `items = buildList { … }` is evaluated at
                        // *this* call site, so the ~20 rows, their lambdas and their
                        // captured state were rebuilt on every recomposition of this
                        // screen — which is once per streamed token — for a menu nobody
                        // had opened. The popup still anchors to the same `Box`, so
                        // nothing about its position changes.
                        if (overflow) PiMenu(
                            expanded = true,
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
                                            listState.firstVisibleItemIndex + hiddenCount
                                        userRowIndices().lastOrNull { it < firstFull }?.let { row ->
                                            pauseTail()
                                            reveal(row)
                                        }
                                    },
                                )
                                add(
                                    PiMenuItem("跳到下一条提问") {
                                        val firstFull =
                                            listState.firstVisibleItemIndex + hiddenCount
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
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
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
            //
            // ---------------------------------------------------------------- 加载更早
            //
            // **The 「加载更早」 row is not an item of this list any more** — it is drawn as
            // an overlay over the list's own top padding, at the exact place the item used
            // to occupy (`docs/scroll-diagnosis.md` §3.4, landed as D51).
            //
            // Why it had to leave: while it was item 0, its key (`transcript-earlier`)
            // stayed at index 0 across a batch, so the `LazyColumn`'s key anchoring held
            // *it* still and the content slid by the whole batch underneath. Compensating by
            // hand (`prependAnchoredIndex`, now deleted) moved the viewport top to the old
            // first content row instead, which cost the sentinel's own height — 30–45 dp of
            // movement on every batch. With the row outside the list, the viewport's first
            // item is a real transcript row and the anchoring is simply correct.
            //
            // What must stay identical, and how it is kept:
            //  - **the same height and the same place.** `earlierRowHeightPx` is the height
            //    the `Row` below measures itself at (`max(24 dp icon, the text's measured
            //    line box) + 8 + 8`), measured *before* the list composes, so the list can
            //    put it in `contentPadding.top` and the overlay can sit above the first row
            //    by exactly that much plus the block gap the list no longer inserts for it;
            //  - **the same appearance**: the same `Icon` + 6 dp + `Text(PiTheme.text.meta)`
            //    in the same centred `Row`, painted on the page's own ground;
            //  - **the same click**: `fillMaxWidth().clickable{…}.padding(vertical = 8.dp)`,
            //    the same order, so the ripple covers the same area.
            //
            // The list keeps `Arrangement.spacedBy(blockSpacing)` for its own rows, and the
            // reserved band is `earlierRowHeight + blockSpacing` so the first row sits
            // where it did when the sentinel was an item with a gap under it.
            val earlierRowHeightValue = earlierRowHeight(earlierText.orEmpty())
            // Where the overlay sits, and how much of the list's own top padding is the band
            // it occupies: exactly the row's height plus the block gap the list no longer
            // inserts between it and the first message.
            val earlierBand = if (showsEarlierRow) earlierRowHeightValue + blockSpacing else 0.dp
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // The vertical ends are not the block gap: they are the scroll
                // container's own `10` top (plus the sentinel's reserved band, when there
                // is one) and `12` bottom.
                contentPadding = PaddingValues(
                    start = PiSpacing.pageHorizontal,
                    end = PiSpacing.pageHorizontal,
                    top = 10.dp + earlierBand,
                    bottom = 12.dp,
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
                    val rowModifier = when {
                        isCurrentMatch -> Modifier
                            .border(1.dp, PiTheme.palette.searchMatchText, PiShapes.cardInner)
                            .background(PiTheme.palette.searchMatchBg, PiShapes.cardInner)

                        isMatch -> Modifier.background(PiTheme.palette.searchMatchBg, PiShapes.cardInner)
                        else -> Modifier
                    }
                        // The height this row had before, for the frames of a *fresh*
                        // composition of it: markdown's parse state is built with a plain
                        // `remember`, so a row that leaves and comes back (a destination
                        // switch, a scroll past it and back) is composed at zero height and
                        // grows when the parse lands — and every row below it moves with it.
                        // A floor, not a size, released by the parse itself: see
                        // `ui/render/TranscriptRowHeight.kt` (and `RowHeightCache`'s bound).
                        .rememberedRowHeight(item.key, contentReady = markdownParsed.value)
                    // The execution rail's two ends (`06 §2` 执行轨道「竖线上下各缩进 16」).
                    // A run is a property of *consecutive transcript rows*, so the one
                    // place that can answer "is this the first/last tool card of a run"
                    // is the list that holds the order — the blocks themselves only ever
                    // see one item. `previous`/`next` therefore come from `visibleItems`,
                    // the whole loaded list, and not from the rendered slice: inside the
                    // window the two are the same row (`renderedItems[i] ==
                    // visibleItems[hiddenCount + i]`), but at the window's two ends the
                    // slice has no neighbour where the transcript has one. Reading the
                    // slice made the boundary row's rail insets flip the moment a batch
                    // was prepended *under* it — and that boundary row is exactly the one
                    // the anchor above is holding still, so the flip was a few tens of dp
                    // of movement on the reader's own row, once per load-earlier batch.
                    // `ToolCall` and `ToolDiff` are the only two kinds that draw a rail
                    // (`ui/blocks/ToolRail.kt`).
                    val previous = visibleItems.getOrNull(hiddenCount + sliceIndex - 1)
                    val next = visibleItems.getOrNull(hiddenCount + sliceIndex + 1)
                    val firstOfRun = previous !is ToolCall && previous !is ToolDiff
                    val lastOfRun = next !is ToolCall && next !is ToolDiff
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
                    val immediateMarkdown = !streamingRow &&
                        RowHeightCache.shared.of(item.key) == null &&
                        (item.key in freshRowKeys || restoring) &&
                        piMarkdownParseBudget.allow(System.nanoTime())
                    CompositionLocalProvider(
                        LocalPiMarkdownImmediate provides immediateMarkdown,
                        LocalPiMarkdownParsed provides onMarkdownParsed,
                    ) {
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
                            // answered with `Invalid entry ID for forking`. What makes the
                            // order reliable — reading the *full* list rather than the
                            // rendered window — lives in `onForkFromMessage` above, which
                            // is hoisted out of this lambda to keep its identity stable.
                            onForkFromMessage = onForkFromMessage,
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

        // The two non-widget extension surfaces: `setStatus` entries, and the extensions
        // whose UI `--mode rpc` cannot carry. Both fold to one row and draw nothing when
        // empty — the old always-on status row was deleted for being permanently *there*,
        // not for showing status. Above the editor only: a widget's own `widgetPlacement`
        // decides which side it goes on, these two have no placement of their own, and
        // mounting them at both sites would draw two copies of one list.
        ExtensionInfoCards(
            statuses = state.extensionStatuses,
            tuiOnly = state.tuiOnlyExtensions,
        )

        ExtensionWidgetStack(
            // Remembered on the widget list, which only changes when an extension
            // pushes one: the filter used to allocate a new list on every recomposition
            // of this screen, i.e. once per streamed token, for an answer that had not
            // changed. `remember` rather than a cached field because the placement is a
            // property of the widget, not of this screen.
            widgets = remember(state.extensionWidgets) {
                state.extensionWidgets.filter { it.placement == WidgetPlacement.AboveEditor }
            },
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
                // these bytes travel inside the message and count against the model — and
                // the **count and the running total** are what the message's limit is made
                // of, so both are shown. The total is the bytes the staged base64 encodes,
                // the same unit the downgrade sentence uses, so "合计 X MB / 上限 Y MB" and
                // "已用 Z MB" are one arithmetic rather than two.
                //
                // 「内联」 is in the sentence because this limit is only about what travels
                // **inside** the record: a file that goes into the workspace is handed over
                // as a path and is not bounded by it at all (see `AttachmentBudget`'s
                // `workspaceSpace`). Without that word the row reads as "attachments are
                // limited to 24 MB", which is the misunderstanding the copy of the refusal
                // sentence below used to confirm.
                val stagedBytes = AttachmentBudget.base64CharsToBytes(attachments.sumOf { it.base64.length })
                Text(
                    "随消息一起内联：${attachments.size} 张，合计 ${mibLabel(stagedBytes)} MB" +
                        "（内联上限 ${mibLabel(AttachmentBudget.MESSAGE_BYTES)} MB）" +
                        "；其它文件复制进工作区后只给路径，不受这个上限约束。",
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
            widgets = remember(state.extensionWidgets) {
                state.extensionWidgets.filter { it.placement == WidgetPlacement.BelowEditor }
            },
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
 * The transcript's search results for one query.
 *
 * Two shapes of the same answer, because the two readers want different operations:
 * [ordered] is the navigation order (`cursor / total` and the reveal), and [present] is
 * the per-row membership test the transcript runs once per rendered row per frame.
 *
 * A `List<Int>` served both before this, and the membership half was a linear scan of
 * every match in the session — a session with a common word in it has thousands of them,
 * and the transcript asks per visible row. Both halves are built in one pass over the
 * transcript (see `ChatBody`'s match scan).
 */
private data class SearchHits(val ordered: List<Int>, val present: Set<Int>) {
    companion object {
        /** A blank query, or one that matched nothing. */
        val None = SearchHits(emptyList(), emptySet())
    }
}

/**
 * Whether [item] is a search result for [query] — the same coverage the old
 * `searchTextOf` had, asked as a predicate instead of built as a string.
 *
 * (`mergeRestoredQueue` — pi's queue/editor merge rule, shared by Stop and the
 * queue row's dequeue — lives in `ui/chat/QueueRestore.kt`: both actions must merge
 * identically, and a pure function is the only form
 * `tools/run-app-pure-checks.sh` can execute, because this file imports Compose.)
 *
 * Every rendered block kind is covered, because a search that silently skips a
 * kind would report "no matches" for text the user can see. Kinds with no text of
 * their own (a date separator) contribute nothing.
 *
 * **Why a predicate and not a string.** The old form joined each row's parts and then
 * ran `contains` on the result, which copied a tool's whole output — the largest thing
 * in a transcript — once per row per scan (measured: 0.86 ms per 20 KB row on a desktop
 * JVM). Where a row's parts were joined with a newline the two forms are *identical*,
 * because the query cannot contain one: the search field is `singleLine`
 * (`ChatScreen.kt`'s `SearchBar`), so a match could never have straddled the separator.
 * The joins that remain are the tiny ones, kept as they were so the behaviour of a `/`
 * or newline inside a query is unchanged rather than accidentally "cleaned up".
 */
private fun searchHits(item: TranscriptItem, query: String): Boolean = when (item) {
    is UserMessage -> item.text.hits(query)
    is AssistantText -> item.text.hits(query)
    is ThinkingBlock -> item.text.hits(query)
    is ToolCall -> item.toolName.hits(query) || item.argsSummary.hits(query) || item.output.hits(query)
    is ToolDiff -> item.path.hits(query) || item.diffText.hits(query)
    is CompactionMarker -> item.summary.hits(query)
    is BranchSummary -> item.summary.hits(query)
    is HookMessage -> item.markdown.hits(query)
    is ModelChange -> listOfNotNull(item.provider, item.modelId).joinToString("/").hits(query)
    is SkillInvocation -> listOf(item.skillName, item.body).joinToString("\n").hits(query)
    is ErrorText -> listOfNotNull(item.message, item.detail).joinToString("\n").hits(query)
    is Notice -> item.text.hits(query)
    is DateSeparator -> false
}

/** pi's `searchMatch` is a case-insensitive substring test over the row's text. */
private fun String.hits(query: String): Boolean = contains(query, ignoreCase = true)

/**
 * One full pass of the search over [items]: the ordered hits and the membership set the
 * transcript reads per rendered row.
 *
 * Extracted so it can be run **off the frame thread** — the caller dispatches it to
 * `Dispatchers.Default` and publishes the result (see `ChatBody`'s scan effect). It is a
 * pure function of its three arguments, over an immutable list, so nothing is read that
 * the dispatcher change could make stale.
 *
 * A block the user asked to hide cannot be a search result: the row is not on screen to
 * scroll to.
 */
private fun scanSearchHits(
    items: List<TranscriptItem>,
    query: String,
    hideThinking: Boolean,
): SearchHits {
    val ordered = ArrayList<Int>()
    val present = HashSet<Int>()
    items.forEachIndexed { index, item ->
        if (hideThinking && item is ThinkingBlock) return@forEachIndexed
        if (searchHits(item, query)) {
            ordered += index
            present += index
        }
    }
    return SearchHits(ordered, present)
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
 * The context ring in the composer's key row (design decision D23/D27).
 *
 * The ring is [CONTEXT_RING_DIAMETER] across with a 2 dp pen, the touch target is
 * **32** rather than the usual 48, and its slot in the row is **30** — the same as the
 * send disc opposite it. The 32 is the designer's ruling and it is load-bearing: a 48 dp
 * box would grow this row by 18 dp, and the row exists to keep the transcript's display
 * area (the status band it replaces was 32 dp tall).
 *
 * **22 dp, not D27's 24.** D27 fixed the visual at 24 dp; the user's later ruling on this
 * screen was 「这个圆环缩小一点点」, so the drawn arc is 2 dp smaller. The two numbers D27
 * also fixed are untouched: the touch box is still 32 dp (D27's floor), and the slot is
 * still 30 dp, so the row's height and the ring's position in it do not move. The arc is
 * drawn **centred inside** the caller's box (`PiContextRing` owns the visual, its caller
 * owns the target), which is what makes "32 dp you can hit, 22 dp you see" one statement
 * rather than two.
 */
private val CONTEXT_RING_DIAMETER = 22.dp
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
 * ## The bound is the **disk**, not the message
 *
 * This used to be bounded by [AttachmentBudget.MESSAGE_BYTES] (23.95 MB) — the number that
 * exists because a message's inline base64 must fit inside one JSONL record. That number
 * has nothing to do with this path: what is produced here is a **relative path in the
 * composer** (`attachments/<name>`, a dozen bytes) and the agent reads the file off disk
 * itself. The user's report is the consequence — 「发个文件还说太大，复制不进去，太傻逼了」:
 * a 30 MB PDF was refused, and the copy is exactly how it was supposed to become readable.
 *
 * So the only bound here is free space, and it is checked twice: up front and then against
 * every chunk through [AttachmentBudget.workspaceSpace], with
 * [AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES] left unspent. `StatFs` is read **once**
 * per copy (`availableBytesOf`) and the running total is subtracted locally — this file is
 * the only writer of the target, so a second syscall per chunk would buy nothing but
 * latency on a 2 GB stream. A filesystem that will not report its free space is
 * [AttachmentBudget.DiskSpace.Unknown]: the copy proceeds, and a genuine failure surfaces
 * as [WorkspaceCopy.WriteFailed].
 *
 * The copy is still **streamed**: one 64 KiB buffer, so a 2 GB provider document never
 * lives in memory. What changed is only which question stops it.
 */
private fun copyIntoWorkspace(context: Context, uri: android.net.Uri): WorkspaceCopy {
    // Opened first, so a source that will not open is [WorkspaceCopy.Unreadable] rather
    // than a directory problem — the same order, and the same two failure arms, as before.
    val source = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: return WorkspaceCopy.Unreadable
    return source.use { input -> copyAttachment(context, input, displayNameOf(context, uri)) }
}

/**
 * The same copy, from bytes the caller already holds: the **inline fallback** for a picked
 * image ([fallbackInline] in the picker callback).
 *
 * It exists because an image that cannot be inlined is still a file the agent should be
 * able to read, and because the bytes in hand are the user's own pick — writing the
 * *re-encoded* picture instead would hand the agent a different file than the one that was
 * chosen. The name goes through the same sanitiser and the same
 * `attachments/`-containment guard as any other copy, and the space rule is the same one.
 */
private fun copyBytesIntoWorkspace(context: Context, bytes: ByteArray, displayName: String?): WorkspaceCopy =
    java.io.ByteArrayInputStream(bytes).use { copyAttachment(context, it, displayName) }

/** Where one copy will land, after every guard has passed. */
private class AttachmentTarget(val directory: java.io.File, val file: java.io.File, val relativePath: String)

/**
 * The file one copy will land in, or null when the workspace cannot be created or the name
 * would not land inside `attachments/`.
 *
 * Both guards are unchanged and neither depends on the other: the name comes from
 * [app.pi.ui.chat.sanitizeAttachmentName] and [app.pi.ui.chat.uniqueAttachmentName], and
 * then the **canonical path** of the file about to be written must be exactly
 * `<attachments>/<that name>`. A name that escapes the directory is a path this app would
 * be writing on a stranger's behalf.
 */
private fun attachmentTarget(context: Context, displayName: String?): AttachmentTarget? {
    val directory = java.io.File(app.pi.runtime.PtyLauncher.workspaceHost(context), ATTACHMENTS_DIR)
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val name = app.pi.ui.chat.uniqueAttachmentName(
        app.pi.ui.chat.sanitizeAttachmentName(displayName),
    ) { candidate -> java.io.File(directory, candidate).exists() }
    val target = java.io.File(directory, name)
    val root = runCatching { directory.canonicalPath }.getOrNull() ?: return null
    val resolved = runCatching { target.canonicalPath }.getOrNull() ?: return null
    if (resolved != "$root${java.io.File.separator}$name") return null
    return AttachmentTarget(directory, target, "$ATTACHMENTS_DIR/$name")
}

/**
 * Stream [input] into a new file under `attachments/`, bounded only by free space.
 *
 * The space reading is taken once, before the first byte is written ([availableBytesOf]);
 * the running total is then compared against it chunk by chunk. That is the honest reading
 * for a number the user is shown: if some other writer fills the disk while this copy runs,
 * the `write` throws and the answer is [WorkspaceCopy.WriteFailed] — never a made-up figure.
 */
private fun copyAttachment(context: Context, input: java.io.InputStream, displayName: String?): WorkspaceCopy {
    val target = attachmentTarget(context, displayName) ?: return WorkspaceCopy.WriteFailed
    val available = availableBytesOf(target.directory)
    val margin = AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES
    val streamed = runCatching {
        target.file.outputStream().use { sink -> copyStreamed(input, sink, available, margin) }
    }.getOrNull()
    return when (streamed) {
        is Streamed.Done -> WorkspaceCopy.Copied(target.relativePath)

        // **No half file is ever left behind**, for either reason: the user was told this
        // file did not make it, so a truncated copy in 工作区 would be a lie. (Only the
        // over-limit arm deleted before; a mid-write failure used to leave its partial.)
        is Streamed.NoSpace -> {
            runCatching { target.file.delete() }
            WorkspaceCopy.OutOfSpace(
                neededBytes = streamed.neededBytes,
                availableBytes = streamed.availableBytes,
                marginBytes = streamed.marginBytes,
            )
        }

        null -> {
            runCatching { target.file.delete() }
            WorkspaceCopy.WriteFailed
        }
    }
}

/** What one [copyStreamed] did. */
private sealed interface Streamed {
    data object Done : Streamed

    /**
     * The disk ran out at [neededBytes] (a **lower bound** — the source had that many
     * bytes to give). [availableBytes] and [marginBytes] are the two numbers the sentence
     * needs, carried rather than recomputed.
     */
    data class NoSpace(val neededBytes: Long, val availableBytes: Long, val marginBytes: Long) : Streamed
}

/**
 * The streaming loop: one 64 KiB buffer, and [AttachmentBudget.workspaceSpace] asked before
 * every write.
 *
 * `availableBytes` is the free space read once before the copy started; `total` is what has
 * been written. The question asked is about the **next** chunk (`total + read`), so a chunk
 * is never written into the margin. `null` means the filesystem would not say, and then no
 * chunk is refused here.
 */
private fun copyStreamed(
    input: java.io.InputStream,
    sink: java.io.OutputStream,
    availableBytes: Long?,
    marginBytes: Long,
): Streamed {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return Streamed.Done
        if (read == 0) continue
        val needed = total + read
        when (val space = AttachmentBudget.workspaceSpace(needed, availableBytes, marginBytes)) {
            AttachmentBudget.DiskSpace.Room, AttachmentBudget.DiskSpace.Unknown -> {
                sink.write(buffer, 0, read)
                total = needed
            }
            is AttachmentBudget.DiskSpace.Insufficient -> return Streamed.NoSpace(
                neededBytes = space.neededBytes,
                availableBytes = space.availableBytes,
                marginBytes = marginBytes,
            )
        }
    }
}

/**
 * Free bytes on the filesystem [directory] sits on, or null when it cannot be read.
 *
 * `StatFs` on the directory itself, not on the workspace root or the app's `filesDir`: the
 * attachments directory is where the bytes land, and on a device with an adopted external
 * `filesDir` those are not necessarily the same filesystem. A reading of 0 or a throw is
 * reported as **unknown** rather than as "no space" — an unreadable number must not become
 * a refusal (`RuntimeSpaceBudget.shortfall` makes the same call for the same reason).
 */
private fun availableBytesOf(directory: java.io.File): Long? = runCatching {
    android.os.StatFs(directory.path).availableBytes.takeIf { it > 0L }
}.getOrNull()

/** One chunk of a workspace copy. 64 KiB, the same size the old bounded copy used. */
private const val COPY_BUFFER_BYTES = 64 * 1024

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

    /**
     * The disk filled up (or was already too full to keep
     * [AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES] spare); the partial file was removed.
     *
     * Not a size limit: this path's only bound is free space, and the numbers come from the
     * measurement that stopped the copy rather than from a constant.
     */
    data class OutOfSpace(
        val neededBytes: Long,
        val availableBytes: Long,
        val marginBytes: Long,
    ) : WorkspaceCopy

    /** The workspace could not be created, or the copy failed — disk full, read-only. */
    data object WriteFailed : WorkspaceCopy
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
 * How often the transcript's search may re-scan while a turn is streaming.
 *
 * The same number and the same argument as the transcript's tool-output throttle
 * (`rpc/.../Transcript.kt` `TOOL_UPDATE_THROTTLE_MS`): pi publishes once per streamed
 * event, and a search over the whole session is expensive enough (110 ms for 800 rows
 * of realistic size, measured on a desktop JVM) that paying it per token is a frozen
 * screen. A search is a reading of the transcript, not a live feed — a match list that
 * is 200 ms behind the newest text is imperceptible, and the turn's last publication is
 * always scanned because the scan is repeated once the transcript stops streaming.
 *
 * The residual cost is bounded by what the scan has to read, which is the session's own
 * text: the follow-up (if this is ever still felt) is to cache each row's search text in
 * the reducer, not to lengthen this window.
 */
private const val SEARCH_RESCAN_MS: Long = 200L

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
 * How many row keys are remembered as "the window has just brought this row in, and it has
 * never been measured" — the gate for the one synchronous markdown parse a row may pay for
 * (`LocalPiMarkdownImmediate`, P3 of `docs/scroll-diagnosis.md` §1.2).
 *
 * One batch is at most [TRANSCRIPT_WINDOW_STEP] rows (the file read's rows enter the *window*
 * at that step too, however many entries it read), and the window's head can be grown again
 * before the reader has composed the previous batch's rows — so this holds several batches'
 * worth. A key dropped off the end costs that row the old behaviour (one frame at zero
 * height); it never costs correctness, and the set cannot grow with the session.
 */
private const val FRESH_ROW_KEYS_MAX = 200

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
        // The same rule as the top bar's menu: the item list is built at this call site,
        // so a closed menu must not build it. See that site's note.
        if (open) PiMenu(
            expanded = true,
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
 *
 * The decode is bounded by [ATTACHMENT_THUMB_SIZE] instead of left at its natural size: a
 * staged photo is up to pi's 2000 px ceiling and this paints a 48 dp square. The note
 * inside has the arithmetic and the shared decode gate.
 */
private val ATTACHMENT_THUMB_SIZE = 48.dp

@Composable
private fun AttachmentThumb(
    image: PiImage,
    index: Int,
    onRemove: () -> Unit,
) {
    // The decode is bounded by the box it is drawn in, the same way the transcript's
    // cells bound theirs (`decodePiImage`'s `targetWidth`/`targetHeight`). Without it the
    // default is "the natural size": a staged photo is up to pi's 2000 px ceiling, i.e.
    // ~16 MB of ARGB per attachment held to paint a 48 dp square, and a message may carry
    // seven of them. `decodePiImage` keeps the result within 2× of this box on each axis,
    // so the thumbnail is still sampled above its drawn size at any density.
    val thumbPx = with(LocalDensity.current) { ATTACHMENT_THUMB_SIZE.roundToPx() }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, image.base64, thumbPx) {
        value = withContext(Dispatchers.IO) {
            // The same gate the transcript's decodes go through
            // (`MAX_CONCURRENT_IMAGE_DECODES`): staging five photos must not start five
            // simultaneous full-res codec runs on the frame's behalf.
            piImageDecodeGate.withPermit {
                // …and the same process-wide bitmap cache the transcript's cells use
                // (`PiImageCache`, keyed by payload + box), so re-staging the same picture — or
                // leaving the composer and coming back — reuses the decode instead of running
                // the codec again. The permit is held for the lookup too; a hit is a reference
                // compare or one `memcmp` over the payload and costs no codec work, and the
                // 32 MiB bound (payloads charged) lives in that object.
                PiImageCache.readThrough(image.base64, thumbPx, thumbPx) {
                    decodePiImage(image.base64, thumbPx, thumbPx)
                }
            }
        }
    }
    Surface(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(ATTACHMENT_THUMB_SIZE)
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
 * Read at most `limit + 1` bytes, so "bigger than the limit" is answered without
 * ever materialising the whole input.
 *
 * `InputStream.readNBytes`/`readBytes()` allocate for whatever the provider offers;
 * a gallery or cloud provider can offer hundreds of megabytes. One byte past the
 * limit is all the caller's comparison needs, and it bounds both the allocation and
 * the time spent on a file that is about to be rejected.
 *
 * The limit callers pass is [AttachmentBudget.MAX_PICKED_IMAGE_BYTES]: the app's own
 * memory guard, not pi's rule and not the message's budget — pi's resize has no input
 * bound at all, and the image is compressed after this read, so a file over the guard
 * is refused for what it costs to hold, not for how big it would have been on the wire.
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

/**
 * pi's inline-image normalization on Android's codecs — the thin half that needs a
 * `Bitmap`.
 *
 * Every number and every ordering decision comes from [AttachmentBudget] (pi's
 * `maxWidth`/`maxHeight`, the 4.5 MB base64 ceiling, the quality ladder, the
 * three-quarter shrink, and which encodings to try when); this function only performs
 * them. That split is deliberate: it is the difference between "the harness checks the
 * sizes and orderings that decide whether a picture fits" and "the harness checks
 * nothing, because `BitmapFactory` needs a phone".
 *
 * ## pi's flow, and where this differs
 *
 *  1. **Fast path** (`image-resize-core.ts:82-93`): a picture already within both limits
 *     goes on the wire **byte for byte**, with its own MIME type. This is the only path
 *     that does not re-encode, and it is what keeps an ordinary photo from being
 *     re-compressed for nothing. One condition is added to pi's: the MIME must be one pi
 *     accepts inline ([AttachmentBudget.piInlineSupported]). pi converts everything else
 *     to PNG first (`image-process.ts:49-65`); without this, a `image/heic` under the
 *     limits would be forwarded as HEIC and rejected by the provider — a case pi cannot
 *     have.
 *  2. **Resize loop** (`:95-160`): clamp the long edge to 2000, then try the encodings at
 *     that size, then shrink by three quarters and try again, down to 1×1. pi uses
 *     Lanczos3 through Photon; Android's `Bitmap.createScaledBitmap(..., filter = true)`
 *     is bilinear, which is a resampling difference, not a size or an ordering one — it
 *     is listed as device-only in the change report.
 *  3. **Alpha, and a PNG source, pick the format**
 *     (`AttachmentBudget.encodings`): PNG first for a picture whose decoded bitmap can
 *     carry alpha **or** whose source MIME is `image/png`; JPEG otherwise. `Bitmap.hasAlpha()`
 *     is the alpha test, and on a decoded `ARGB_8888` it reports whether the *source* had
 *     an alpha channel, which is why an opaque JPEG does not get encoded as a PNG. pi
 *     pushes that PNG candidate for every source that needs a re-encode; asking for it only
 *     where it can win is the app's one deliberate difference — `AttachmentBudget`'s class
 *     KDoc has the reasoning and the residual difference.
 *
 * Returns null when the bytes do not decode, or when even 1×1 cannot be encoded under
 * [limits]. The caller tells the user which of the two happened is not knowable
 * here, so it says both.
 *
 * [limits] is `AttachmentBudget.limitsFor(state.meta.model?.inputLimits)`: the model's own
 * profile when pi published one, pi's defaults otherwise, with the record's budget as a
 * ceiling. The **fast path** below uses the same limits, so a model with a smaller profile
 * no longer forwards an original the model cannot take.
 *
 * Runs on `Dispatchers.IO` — one full-size decode plus one encode is tens of
 * milliseconds and a few megabytes, which must not happen on the frame thread. A model
 * profile *larger* than pi's default makes that first attempt proportionally more expensive
 * (a 4000px decode, not 2000px): that is the cost of no longer throwing away detail the
 * model accepts, and `maxBase64Chars` is still what decides when the loop stops.
 */
private fun compressAttachment(
    bytes: ByteArray,
    mime: String,
    limits: AttachmentBudget.Limits,
): PiImage? {
    // Header only: `inJustDecodeBounds` reads the size without allocating pixels, which
    // is also how the fast path is decided without decoding anything.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    if (width <= limits.maxWidth &&
        height <= limits.maxHeight &&
        AttachmentBudget.piInlineSupported(mime) &&
        AttachmentBudget.base64Chars(bytes.size) < limits.maxBase64Chars
    ) {
        return PiImage(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)
    }

    val source = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
    ) ?: return null
    try {
        // `mime` is the *source's* type and it decides the candidate order: a PNG source
        // (or any source whose decoded bitmap carries alpha) gets pi's PNG candidate
        // first, everything else starts at the profile's first JPEG quality. See
        // `AttachmentBudget.pngFirst` and `jpegQualities`.
        for (attempt in AttachmentBudget.attemptPlan(mime, source.hasAlpha(), width, height, limits)) {
            val scaled = scaleForAttachment(source, attempt.width, attempt.height) ?: continue
            try {
                val encoded = encodeForAttachment(scaled, attempt.encoding) ?: continue
                // pi's own test is strict (`encodedSize < maxBytes`).
                if (encoded.base64.length < limits.maxBase64Chars) return encoded
            } finally {
                // `scaleForAttachment` returns the source itself when the target is the
                // source's own size, and the outer `finally` owns that one.
                if (scaled !== source) scaled.recycle()
            }
        }
        return null
    } finally {
        source.recycle()
    }
}

/** One resize step; the source itself when the target is already its size. */
private fun scaleForAttachment(source: Bitmap, width: Int, height: Int): Bitmap? =
    if (width == source.width && height == source.height) {
        source
    } else {
        runCatching { Bitmap.createScaledBitmap(source, width, height, true) }.getOrNull()
    }

/** One encode step, as the wire value it becomes — the base64 the budget counts. */
private fun encodeForAttachment(bitmap: Bitmap, encoding: AttachmentBudget.Encoding): PiImage? {
    val out = java.io.ByteArrayOutputStream()
    val ok = when (encoding) {
        // PNG's quality parameter is ignored by the platform encoder; 100 is what pi's
        // `get_bytes()` is equivalent to (lossless).
        is AttachmentBudget.Encoding.Png -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        is AttachmentBudget.Encoding.Jpeg -> bitmap.compress(Bitmap.CompressFormat.JPEG, encoding.quality, out)
    }
    if (!ok) return null
    return PiImage(
        base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
        mimeType = when (encoding) {
            is AttachmentBudget.Encoding.Png -> "image/png"
            is AttachmentBudget.Encoding.Jpeg -> "image/jpeg"
        },
    )
}

/**
 * The sentence for a file that was put in the workspace **because it could not be inlined**,
 * with [why] being the reason the inline path gave up.
 *
 * It has to say three things, and each one is load-bearing: where the file went (the user
 * can see 工作区), that it did **not** travel inside the message (otherwise the next question
 * is "why did the model not see it"), and that the model can still read it (the path is in
 * the composer, and pi's agent resolves it like any other path).
 *
 * This is not the 「已放入工作区」 notice D47 deleted: that one repeated a path that was
 * already in the composer and nothing else. Here the message's shape differs from what the
 * user asked for, so the composer's own contents cannot explain it.
 */
private fun inlineDowngradeText(relativePath: String, why: String): String =
    "已放进工作区 $relativePath —— $why，所以没有随消息内联；" +
        "模型可以用工具读取这个文件。"

/**
 * The sentence for a copy the disk refused, built only from the measurement that stopped it.
 *
 * 「至少需要」 is deliberate: [WorkspaceCopy.OutOfSpace.neededBytes] is the number of bytes
 * the source had already handed over, which is a lower bound on the file's size, not its
 * size. Saying "needs X" without the qualifier would be a claim this code cannot check.
 */
private fun workspaceOutOfSpaceText(out: WorkspaceCopy.OutOfSpace): String =
    "磁盘空间不足：这个文件至少需要 ${mibLabel(out.neededBytes)} MB，" +
        "工作区所在磁盘可用 ${mibLabel(out.availableBytes)} MB" +
        "（要留 ${mibLabel(out.marginBytes)} MB 余量）。" +
        "它没有被复制进工作区，也没有加进这条消息；腾出空间后可以再选一次。"

/**
 * One count in MiB, one decimal, locale-stable.
 *
 * A plain formatter rather than a unit conversion: the copies count **base64
 * characters** for pi's ceiling and **bytes** for the message budget, and both are
 * shown in the same MiB so the two numbers can be compared. `%.1f` rather than an
 * integer, because pi's own ceiling is 4.5 and truncating it to "4" would understate
 * what the composer is allowed to send by half a megabyte.
 *
 * The [Long] overload is for the disk figures, which are byte counts from `StatFs` and can
 * legitimately exceed an `Int`.
 */
private fun mibLabel(count: Int): String =
    String.format(java.util.Locale.US, "%.1f", count / 1024.0 / 1024.0)

private fun mibLabel(count: Long): String =
    String.format(java.util.Locale.US, "%.1f", count / 1024.0 / 1024.0)
