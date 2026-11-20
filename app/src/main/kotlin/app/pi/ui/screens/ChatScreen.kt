package app.pi.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import app.pi.engine.PiEngineSession
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.pi.settings.readBoolean
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.rpc.AssistantText
import app.pi.rpc.DateSeparator
import app.pi.rpc.ErrorText
import app.pi.rpc.ModelChange
import app.pi.rpc.ThinkingBlock
import app.pi.rpc.TranscriptItem
import app.pi.rpc.PiImage
import app.pi.rpc.UserMessage
import app.pi.ui.Boot
import app.pi.ui.NavRequest
import app.pi.ui.PiSessionViewModel
import app.pi.ui.earlierRowText
import app.pi.ui.blocks.BlockRenderer
import app.pi.ui.blocks.LocalTranscriptRowAnchor
import app.pi.ui.blocks.PiImageViewer
import app.pi.ui.blocks.TranscriptRowAnchor
import app.pi.ui.chat.BashPanel
import app.pi.ui.chat.ComposerRoute
import app.pi.ui.chat.ChatAction
import app.pi.ui.chat.argumentContextOf
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.chat.PiSlashCommand
import app.pi.ui.chat.TailFollow
import app.pi.ui.chat.TailSnapshot
import app.pi.ui.chat.TailViewport
import app.pi.ui.chat.mayLoadEarlier
import app.pi.ui.chat.mergeRestoredQueue
import app.pi.ui.chat.routeComposerText
import app.pi.ui.chat.earlierRowHeightPx
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
import app.pi.ui.extension.toExtensionPalette
import app.pi.ui.extension.ExtensionPanelCard
import app.pi.ui.extension.ExtensionStatusStrip
import app.pi.ui.extension.FOOTER_COLLAPSED_ROWS
import app.pi.ui.extension.HEADER_COLLAPSED_ROWS
import app.pi.ui.extension.PLACEMENT_BELOW
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
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
        onOpenSessions = onOpenSessions,
        onOpenWorkspace = onOpenWorkspace,
        onOpenSettings = onOpenSettings,
    )
}


/**
 * 对话页顶栏的图标动作是 [ChatTopBarAction]（48dp 热区 / 24dp 字形），不是别处用的
 * `PiTopBarIcon`（30dp 框 / 17dp 字形）：56 高的对话页顶栏只放三枚 24dp 字形，
 * 48dp 热区才和 56 高匹配；两套尺寸混在一起就是用户看到的「一大一小」。
 */
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
    val renderRev by session.renderRev.collectAsState()
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

    // 悬浮输入框的几何。输入框量自己的高度（`onSizeChanged`），列表的底边留白用它 +
    // 24；键盘/导航条只抬输入框那一层，列表照旧铺到屏幕底边。
    //
    // IME 与导航条用 `union` 合并成一个 inset 而不是各自加一次：两者都在底部，
    // 相加会让输入框在键盘上方多出一条导航条的高度。
    val dockInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    val dockBottom = dockInsets.asPaddingValues().calculateBottomPadding()
    var composerHeightPx by remember { mutableStateOf(0) }
    var aboveComposerHeightPx by remember { mutableStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }
    val composerSlot = composerHeight + PiSpacing.composerBottomGap
    val aboveComposerSlot = with(LocalDensity.current) { aboveComposerHeightPx.toDp() }
    // 输入框上方那几张持久卡（排队 / 导出 / 扩展状态 / widget / bash）也占列表底边：
    // 不算它们的高度，最后一条回复会被其中一张盖住 —— 恰好是最需要看的时候。
    // 命令面板和附件条是瞬时的，没有算进来。
    val listBottomClearance =
        composerSlot + aboveComposerSlot + dockBottom + PiSpacing.composerListClearance
    // The same clearance in the list's own unit. `TailViewport.pinToTail` targets
    // `viewportEndOffset - contentPadding.bottom` — the item offset the content's end
    // reaches at maximum scroll, i.e. the composer's top minus 24 dp — not the screen's
    // bottom. Compose rounds this very `Dp` for `afterContentPadding`, with the same
    // density, so the two agree to the pixel.
    val listBottomClearancePx = with(LocalDensity.current) { listBottomClearance.roundToPx() }
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
    // 顶栏的 0.5dp 发丝线只在**手指滚动/惯性滑动期间**出现；一停就收回，
    // 不是“离开顶部就永久留在那里”。顶栏本身是页面底色，没有常驻灰线。
    val topBarScrolled by remember(listState) {
        derivedStateOf { listState.isScrollInProgress }
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
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
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
                                // pi 的 `images.autoResize`（`settings-manager.ts:68` 定义、
                                // `:1404` 的 `getImageAutoResize()`，默认 `true`；pi 在归一化
                                // 每一轮 prompt 图片时读它，`agent-session.ts:1899`）。
                                // 关掉 = 不做 App 侧预压缩；语义与**没有**跟着关掉的那条消息预算
                                // 都写在 `compressAttachment` 的 KDoc 里。
                                // 这次读是内存命中（缓存文档按 1/4 秒节流 stat），与设置界面的
                                // 每一行同一条读法。
                                val autoResize = session.settingsStore.readBoolean("images.autoResize") ?: true
                                val image = withContext(Dispatchers.IO) {
                                    compressAttachment(bytes, mime, limits, autoResize)
                                }
                                if (image == null) {
                                    // 压不出来（自动缩放开着）：读不出像素，或者所有候选都超
                                    // **这个模型的** 上限。数字来自同一份 limits，所以句子与判定
                                    // 不可能对不上。
                                    // 自动缩放关着时不成的是**转换**（不是「压不小」）：那句
                                    // 不能借用这里的数字，否则句子里会出现一个没有参与判定的上限。
                                    fallbackInline(
                                        sourceUri = null,
                                        bytes = bytes,
                                        why = if (autoResize) {
                                            "按当前模型的上限（最长边 ${limits.maxWidth}×${limits.maxHeight}、" +
                                                "base64 ${mibLabel(limits.maxBase64Chars)} MB）" +
                                                "压不出足够小的版本"
                                        } else {
                                            "自动缩放关着（images.autoResize: false），" +
                                                "这张图也不是 pi 能直接内联的格式，转换不出来"
                                        },
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
                                                "这张" + (if (autoResize) "压缩后 " else "原图 ") +
                                                "${mibLabel(verdict.candidateBytes)} MB）",
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

    // 扩展的 `custom` 条目只在扩展注册了 `registerEntryRenderer` 时可见（pi：没渲染器就不添加任何子组件）。
    val entryRenderable by session.entryRenderable.collectAsState()
    val visibleItems = remember(state.transcript, prefs.showTimestamps, entryRenderable) {
        state.transcript.filter { item ->
            when (item) {
                is DateSeparator -> prefs.showTimestamps
                is app.pi.rpc.ExtensionEntry -> item.customType in entryRenderable
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

    // The settle pass — 「差几个像素到不了真底部」的那一半. A publication's pin is
    // computed from the frame after it, but the tail row can still change height
    // **without any key above moving**: the final markdown parse lands on
    // `Dispatchers.Default` a beat later, an image's intrinsic size arrives later
    // still. Neither bumps `revision`/`streaming`/any other key, so the follow simply
    // stopped a few pixels short of the true end until the user touched the list (or
    // switched screens — which is what the report said re-driven it). This collector
    // turns that silent settle into a key. While streaming it stays quiet (growth
    // there is already driven by `state.revision`), and a drag is the user's
    // business — never a re-pin trigger.
    var tailSize by remember(sessionKey) { mutableStateOf(-1) }
    val streamingNow by rememberUpdatedState(state.streaming)
    LaunchedEffect(listState, sessionKey) {
        snapshotFlow {
            val info = listState.layoutInfo
            val tailItem = info.visibleItemsInfo.lastOrNull()
            if (
                tailItem != null &&
                tailItem.index == info.totalItemsCount - 1 &&
                !listState.isScrollInProgress &&
                !streamingNow
            ) {
                tailItem.size
            } else {
                -1
            }
        }.collect { size -> tailSize = size }
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
        // 输入框一浮起来，"最新"的位置就由它和 inset 决定：高度动画、键盘弹出都要把
        // 尾巴重新钉到新的可见底边，否则最后几行会停在输入框下面。它同时是 pin 的
        // `bottomContentPaddingPx`（按 px 传下去）：列表真正的末尾是 `viewportEndOffset`
        // 减掉这段留白，而不是屏幕底边。
        listBottomClearance,
        // The tail row's settled height, from the collector above: the one geometry
        // fact that can change with no other key moving (final parse / image size).
        tailSize,
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
                    // The list's true end is `viewportEndOffset` minus this padding: at
                    // maximum scroll the content's end sits above the floating composer,
                    // not at the screen's bottom edge. It is the same value the
                    // `LazyColumn` below passes as `contentPadding.bottom`, in pixels.
                    bottomContentPaddingPx = listBottomClearancePx,
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
    val messageActions = remember(session, state.streaming, state.transcript.lastOrNull()) {
        app.pi.ui.chat.MessageActions(
            quote = session::quoteIntoComposer,
            continueFrom = session::continueFromMessage,
            label = session::labelMessage,
            hideFromContext = session::hideMessageFromContext,
            streaming = state.streaming,
            retryKey = (state.transcript.lastOrNull() as? app.pi.rpc.ErrorText)?.key?.takeIf { !state.streaming },
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

    // **`get_session_stats` 不能等用户点环才读。**
    //
    // 环与「已用 / 总量」的唯一数据源是 `get_session_stats().contextUsage`
    // （pi `core/agent-session.ts:4297` 的 `getContextUsage()`；`get_state` 里没有这个
    // 字段）。引擎进入 Ready 时 ViewModel 已经会读一次，但新会话/模型刚绑定的那一瞬它
    // 可能还回答不了（`getContextUsage()` 在 `_limitsModel()` 为空或 `contextWindow <= 0`
    // 时返回 undefined），于是环一直 `?`，直到用户点一次环触发第二次读。
    //
    // 这里按「Ready 且还没有 contextUsage」补一次，键是**模型 id**：模型一到位就重读，
    // 读到之后键不再变，不会变成轮询；已有 stats 的普通会话不会多读。
    //
    // 先等 700 ms 再判断：正常的启动路径上 ViewModel 在进入 Ready 时已经读了一次，
    // 不等它落地就补读会变成每次启动两次 `get_session_stats`（那个调用要遍历整份会话）。
    // 700 ms 之后还没有 contextUsage，才说明那一次读在了模型绑定之前（或者根本没跑）。
    val modelId = state.meta.model?.id
    LaunchedEffect(state.engine, modelId) {
        if (state.engine != PiEngineSession.EngineState.Ready) return@LaunchedEffect
        delay(700)
        if (state.stats?.contextUsage == null) session.refreshStats()
    }

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
                        "本应用改用文件选择器导入，参数「$args」被忽略：请在选择器里挑那个 .jsonl 文件。",
                    )
                }
                importPicker.launch("*/*")
            }
            // 以下都是 pi 的内建命令在手机上的做法：打开对应的界面，或直接执行。每一项都和
            // 「⋯」菜单 / 顶栏里的同名入口走同一个函数，不另起一套。
            ChatAction.OpenSettings -> {
                command?.let { CLIENT_BUILTINS[it.name]?.note }?.let { session.notifyUser(it) }
                onOpenSettings()
            }
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
            ChatAction.PickThinking -> {
                session.refreshThinkingLevels()
                sheet = ChatSheet.Thinking
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
                session.notifyUser("问题报告已复制（${text.length} 字），可以直接粘贴发给 pi 开发者。")
            }
            ChatAction.ContextUsage -> {
                session.refreshStats()
                sheet = ChatSheet.Context
            }
            ChatAction.ToolsSheet -> sheet = ChatSheet.Tools
            ChatAction.Unsupported ->
                session.notifyUser(unsupportedReason(command?.name.orEmpty()), warning = true)
        }
    }

    /** 命令面板点了一行 / 手敲了 `/name args`。 */
    fun pick(command: PiSlashCommand, args: String) = perform(command.action, command, args)

    Column(Modifier.fillMaxSize()) {
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
            searchOpen = searchOpen,
            scrolled = topBarScrolled,
            toolsExpanded = toolsExpanded,
            onOpenSessions = onOpenSessions,
            onOpenWorkspace = onOpenWorkspace,
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
                session.setToolsExpandedRemote(next)
            },
            onAction = { perform(it) },
        )

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
        // composer's context ring (a small used / window bubble; the full sheet is
        // still in the ⋮ menu as 「上下文与用量」).
        //
        // The row component went with it: `PiStatusLine` had no caller left, so it is
        // deleted; the detail sheet draws the same figures in its own layout.
        val emptyTranscript = state.transcript.isEmpty()

        // 扩展状态（setStatus）：输入框上方一行可横滑的小标签（`ExtensionStatusStrip`）。

        // **An empty session shows nothing.** The two states that used to be drawn
        // here — 「引擎正在启动」 and 「引擎已就绪」 with their two paragraphs — are
        // deleted by the user's ruling (「引擎启动什么玩意，那些文字」: a brand-new
        // conversation is a blank page with the top bar and the composer, and the
        // AppBar's one-word engine state above it says everything the paragraphs did).
        // v2's `EmptyState` component is not used here any more, but it is not dead:
        // the session list, the settings search and the session tree still draw it.
        //
        // Deliberate deviation from v2's phone59/phone60: those two screens no longer
        // appear in this state. See `design/ui-refactor/07-construction-decisions.md`
        // D31.

        // 扩展的 header（`ctx.ui.setHeader`）：顶栏之下的一条细栏，只在扩展挂了才有；点开看全部。
        state.ext.header?.let { header ->
            ExtensionPanelCard(
                caption = null,
                lines = header.lines,
                collapsedRows = HEADER_COLLAPSED_ROWS,
                modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            )
        }

        // **The transcript fills the page; the composer floats over it.**
        //
        // 这一层 Box 从顶栏/搜索条之下一直铺到屏幕底边（输入框、键盘都不再切它），
        // 输入框和它的浮层作为同一块底栏 `align(BottomCenter)` 叠在上面。列表因此
        // 满屏，滚动的文字可以从输入框底下穿过。
        //
        // 代价就是这里必然有「量输入框 → 填列表底边留白」的一次反馈：`Composer`
        // 高度由 `onSizeChanged` 量出来，列表的 `contentPadding.bottom` 取其值 +
        // [PiSpacing.composerListClearance]。这不是历史里那个 `rememberSaveable` 的位置 bug，
        // 而是悬浮输入框唯一能让最后一行停在输入框上方的办法；量到的值只影响
        // `contentPadding`，不参与列表自身的测量，所以不会形成 measure → pad →
        // measure 的循环。
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
        if (!emptyTranscript) {
            // `app.appearance.typography`: the transcript's block rhythm, one of the
            // preset's own rows (`PiTypographyProfile.blockSpacing`). F11
            // (`docs/rendering-review.md`): blocks used to pad themselves as well, so
            // the real gap was 18 (spacedBy) + 9 + 9 (BlockColumn) = 36 dp and the
            // prose column lost 16 dp on each side; the list is now the only place
            // that margins.
            //
            // B7: the base gap is **8**, not 16. `06 §2`「块间距 8」 is v2's rhythm
            // (every card in the frozen board carries `marginBottom:8`), and the four
            // presets hang off it — 经典 8 / 紧凑 4 / 舒适 8 / 宽松 12 — so the row
            // still moves the stream and the default is the design's.
            //
            // The rail used to have a stake in this number: `ToolRail` bridged the list's
            // own gap by drawing 8 dp *past* each card's bottom edge, so the line joined
            // two cards while the gap happened to be 8 (舒适, the default) and left a hole
            // at 宽松's 12. Neither the constant nor the line exists any more — a tool card
            // draws no rail at all, its state glyph is a cell of its own header row
            // (`ui/blocks/ToolRail.kt`) — so this value is now the list's rhythm and
            // nothing else's, and no two places have to agree about it.
            val blockSpacing = prefs.typographyProfile.blockSpacing
            // The page margin is **14 at every density** (`06 §2`「屏水平 14px」,
            // `direction-b-v2.html:1376`: `.b-scroll{padding:10px 14px 12px}`). The
            // compact step used to narrow it to 12 as well, which put the transcript's
            // left edge out of line with the AppBar's own 14 and with every other
            // screen; the density preference moves the *block rhythm*, not the page.
            // 排版 follows the same rule: no preset moves this 14.
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
                    // 悬浮输入框 + 它下面的 inset + 一圈呼吸空间；见 [composerSlot] 的注释。
                    bottom = listBottomClearance,
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
                    val immediateMarkdown = !streamingRow &&
                        RowHeightCache.shared.of(item.key) == null &&
                        (item.key in freshRowKeys || restoring || enteringFromAbove) &&
                        (enteringFromAbove || piMarkdownParseBudget.allow(System.nanoTime()))
                    // The row's own place in the list, for a card that is about to change
                    // its height (see `TranscriptRowAnchor`). `following` is handed over as
                    // a lambda: an armed follow re-pins to the end on every height change
                    // (pi's `updateLayout` does the same,
                    // `scroll-view.ts:189-201`), so the two must not both own the position.
                    // Read at click time, which is the only time it matters.
                    val rowAnchor = remember(listState, sliceIndex) {
                        TranscriptRowAnchor(listState, sliceIndex) { following }
                    }
                    CompositionLocalProvider(
                        LocalTranscriptRowAnchor provides rowAnchor,
                        LocalPiMarkdownImmediate provides immediateMarkdown,
                        LocalPiMarkdownParsed provides onMarkdownParsed,
                        app.pi.ui.extension.LocalToolRenderer provides remember(renderRev) {
                            app.pi.ui.extension.ToolRenderer { item, expanded -> session.toolRenderer.render(item, expanded) }
                        },
                        app.pi.ui.extension.LocalEntryRenderer provides remember(renderRev) {
                            app.pi.ui.extension.EntryRenderer { item, expanded -> session.entryRenderer.render(item, expanded) }
                        },
                        app.pi.ui.extension.LocalMarkdownTransform provides session.markdownTransform,
                        app.pi.ui.extension.LocalMessageRenderer provides remember(renderRev) {
                            app.pi.ui.extension.MessageRenderer { item, expanded -> session.messageRenderer.render(item, expanded) }
                        },
                        app.pi.ui.chat.LocalMessageActions provides messageActions,
                        app.pi.ui.blocks.LocalThinkingLabel provides state.ext.thinkingLabel
                            ?.let { app.pi.ui.extension.chromeText(it).trim() }?.takeIf { it.isNotEmpty() },
                    ) {
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
                            // The clocks on the user's own bubble and on a notice line. They
                            // used to be unconditional, which is why the 外观 switch looked
                            // broken: it reached only the date separators, so turning it off
                            // left a time on every message the user had written. Off is now
                            // the default on both sides of this parameter.
                            showTimestamps = prefs.showTimestamps,
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
                            // The coarse tool clock read beside the other `state.*` values
                            // above; only `ShellBlock` consumes it — a running `bash` is the
                            // one live duration in the transcript.
                            nowMs = nowMs,
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

            // 「回到最新」只看用户意图（`following`），不再并上 `!atBottom`。流式跟随时每个
            // token 都先把末行撑高、下一帧才钉回底：`canScrollForward` 在这一帧里是 true，
            // `atBottom` 闪成 false，箭头就随每个 token 闪一下（用户报的「一直跳动」）。
            // 跟随中的「暂时没贴底」是机器自己正在追的状态，不是读者离开了底部；读者真的
            // 往上滑，机器在手势那一帧就 `pause()`，`following` 变 false，箭头照样出现。
            val showDown = !following
            if (showUp || showDown) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 视觉 36、点击 48；两枚用 0 间距让 48 的点击框相接，
                        // 圆与圆之间仍是 12dp 的视觉缝（48 − 36）。
                        //
                        // 位置：外框底边 = clearance − 18；48 的点击框里 36 的
                        // 圆再上收 6，视觉底边正好落在「输入框（含持久卡）顶边 + 12」。
                        .padding(
                            start = 12.dp,
                            top = 12.dp,
                            // 点击框 48、圆 36：end 6 + 内圈 6 = 视觉右缘 12。
                            end = 6.dp,
                            bottom = listBottomClearance - 18.dp,
                        ),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
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

            // 顶栏下方 12dp 的页面底色渐隐：只在滚动/惯性期间出现，让移动中的内容
            // 从顶栏边缘"化"进去。只画，不拦触摸。
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

            // 悬浮底栏：队列行 / 附件条 / 命令面板 / 输入框都叠在列表之上，整层被
            // 键盘和导航条抬起。输入框自己只负责左右 12、底 8。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(dockInsets),
            ) {

        ChatDockCards(
            state = state,
            session = session,
            draft = draft,
            onDraft = { draft = it },
            attachments = attachments,
            onAttachments = { attachments = it },
            mentionPrefix = mentionPrefix,
            onPickCommand = { command, args -> pick(command, args) },
            argContext = argContext,
            onInteractWidget = { panel -> interactingWidgetKey = panel.key },
            onAboveHeight = { aboveComposerHeightPx = it },
        )

        // 悬浮输入框本体：左右 12、底 8。`onSizeChanged` 量的是 Composer 自己的高度
        // （不含左右/底部 padding），列表的底边留白用它换算。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.composerPageMargin)
                .padding(bottom = PiSpacing.composerBottomGap)
                .onSizeChanged { composerHeightPx = it.height },
        ) {
        Composer(
            draft = draft,
            // One picker for everything: `*/*`, and the callback decides which of
            // pi's two channels the file goes down (see the attachment note above).
            onPickAttachment = { attachmentPicker.launch("*/*") },
            // The reading the transcript's status row used to carry. The ring is always
            // drawn; the total falls back to the model's own window (pi's footer rule).
            contextUsage = state.stats?.contextUsage,
            onOpenContext = { perform(ChatAction.ContextUsage) },
            // 顶栏那颗 chip 挪到工具行之后，模型名和「打开模型面板」的动作从这里进。
            modelLabel = modelLabel,
            onOpenModel = { perform(ChatAction.PickModel) },
            onCycleModel = { perform(ChatAction.CycleModel) },
            onDraftChange = { draft = it },
            thinkingLevel = state.meta.thinkingLevel,
            streaming = state.streaming,
            canFollowUp = state.streaming && draft.isNotBlank(),
            // `steer` carries attachments as well as text, so it is enabled by
            // either — the same rule the send button's own "an image with no caption
            // is still a message" branch uses.
            canSteer = state.streaming && (draft.isNotBlank() || attachments.isNotEmpty()),
            onCycleThinking = { session.cycleThinkingLevel() },
            onPickThinking = { sheet = ChatSheet.Thinking },
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
                    //
                    // pi 的内建命令全部在面板里（见 `builtinRows`），所以这里只剩真正的手误：
                    // 名字不是内建、也不是扩展/模板/技能。
                    is ComposerRoute.Unknown -> session.notifyUnknownCommand(route.name)
                }
            },
            // pi's Escape (`interactive-mode.js:2416-2418`):
            // `restoreQueuedMessagesToEditor({ abort: true })` clears the queue,
            // puts the queued text **and** the current editor text back into the
            // editor (`:3891-3910`), then aborts. Dropping the queued text here is
            // what made Stop silently destroy what the user had typed.
            // 清空 and 后续 are no longer Composer parameters: the designer's overflow
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
                // (`interactive-mode.js:3653-3682`; the call is at `:3674` —
                // `session.prompt(text, { streamingBehavior: "followUp" })`). Only
                // offered while streaming, because that is the only time pi's own
                // binding queues rather than submits (`:3679-3682`).
                //
                // The attachments go with it: pi's own call hands the editor's images
                // to `_queueFollowUp` (`core/agent-session.js:1639-1642`), and F21's
                // finding in `docs/gap-disposition.md` §10 found this call dropping
                // them — the chip looked like it queued the message and the picture
                // was silently gone.
                //
                // Nothing is echoed into the transcript here — a queued message is
                // drawn in the pending row above the composer until pi actually
                // delivers it, which is what official pi does
                // (`interactive-mode.js:3873-3889`). The row appears when pi confirms
                // it. See `PiEngineSession.prompt`.
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
        }

        // `belowEditor` widgets sit after the composer's key-hint strip, which is
        // the app's footer; pi puts them between editor and footer, and the
        // difference is invisible at this density.
        ExtensionWidgetStack(
            widgets = remember(state.ext.widgets) {
                state.ext.widgets.filter { it.placement == PLACEMENT_BELOW }
            },
            onInteract = { panel -> interactingWidgetKey = panel.key },
        )
        // 扩展的 footer（`ctx.ui.setFooter`）：宿主把组件渲染成行，这里是一条细栏，点开看全部。
        state.ext.footer?.let { footer ->
            ExtensionPanelCard(
                caption = null,
                lines = footer.lines,
                collapsedRows = FOOTER_COLLAPSED_ROWS,
                modifier = Modifier.padding(horizontal = PiSpacing.composerPageMargin, vertical = 4.dp),
            )
        }
            }
        }
    }

    ChatSheetHost(sheet = sheet, onSheet = { sheet = it }, state = state, session = session)

    interactingWidgetKey?.let { activeKey ->
        val activeWidget = state.ext.widgets.firstOrNull { it.key == activeKey && it.surfaceId != null }
        if (activeWidget == null) {
            interactingWidgetKey = null
        } else {
            ExtensionWidgetSurfaceSheet(
                panel = activeWidget,
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


/**
 * The palette is open exactly while the first token is still being typed: the
 * draft starts with `/` and no space has been typed yet. Returns the query, or
 * null when the palette should be hidden.
 */
internal fun paletteQueryOf(draft: String): String? {
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

/** 参数补全的防抖：每个按键都去问扩展太吵，停 120 ms 再问；旧应答由 ViewModel 丢弃。 */
private const val ARG_COMPLETION_DEBOUNCE_MS: Long = 120L


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


/** The floating scroll arrow's visual diameter; the tap target is [SCROLL_ARROW_TAP]. */
private val SCROLL_ARROW_SIZE = PiSpacing.scrollArrowVisual

private val SCROLL_ARROW_TAP = PiSpacing.scrollArrowTap


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
            .size(SCROLL_ARROW_TAP)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SCROLL_ARROW_SIZE)
                .alpha(if (pressed) 1f else SCROLL_ARROW_IDLE_ALPHA)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, PiTheme.palette.borderMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
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


