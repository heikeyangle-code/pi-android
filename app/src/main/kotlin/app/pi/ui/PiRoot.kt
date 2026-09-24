package app.pi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pi.ui.chat.SessionTreeScreen
import app.pi.ui.components.PiNavGlyph
import app.pi.ui.components.PiNavGlyphs
import app.pi.ui.extension.ExtensionUiHost
import app.pi.ui.screens.ChatScreen
import app.pi.ui.screens.ProjectScreen
import app.pi.ui.screens.SessionsScreen
import app.pi.ui.screens.SessionsView
import app.pi.ui.screens.TerminalScreen
import app.pi.ui.settings.PiSettingsStack
import app.pi.ui.theme.PiTheme

/**
 * The three top-level destinations, in bottom-bar order.
 *
 * There were four until the v2 refactor (`design/ui-refactor/03-navigation-decision.md`):
 * `会话` was dropped because a session *list* is a selector, not a place — pi
 * itself draws its session tree over the transcript, and the desktop habit is a
 * sheet or a drawer. Two tabs for "the session I am in" and "which session"
 * gave the same thing two homes, and the user could not tell which one they were
 * in. `终端` was dropped for the opposite reason: the terminal is a fallback
 * entry point the user has declared unusable, and the bottom bar is the most
 * visible real estate in the app — it moves to one row on the settings home in a
 * later batch.
 *
 * `ordinal` is the bottom-bar order, as before, so reordering this enum reorders
 * the bar. The start destination stays [Chat]: cold start is a new conversation.
 *
 * The icon is resolved through [icon] rather than carried as a constructor
 * property, so the enum keeps naming pi's vocabulary and the drawing stays in the
 * one place that draws: [PiNavGlyphs].
 */
enum class PiDestination(val label: String) {
    Chat("对话"),

    /**
     * The project on screen: what this session is doing in its working directory —
     * the directory itself, the files it has touched, the resources the directory
     * carries, and any command still running. It replaced a full-screen terminal,
     * which the user retired from the bar
     * (`06-v2-construction-reference.md` §5) and which now has one plain row on the
     * settings home.
     */
    Workbench("工作区"),

    Settings("设置");

    /** The bar's glyph for this destination — see [PiNavGlyphs]. */
    val icon: ImageVector
        get() = when (this) {
            Chat -> PiNavGlyphs.Chat
            Workbench -> PiNavGlyphs.Workbench
            Settings -> PiNavGlyphs.Settings
        }
}

/**
 * What is drawn **over** the current destination, if anything.
 *
 * An overlay is deliberately not a [PiDestination]: a destination is a place the
 * bottom bar goes, and both of these belong to whatever session is on screen.
 * Making the session list a fourth tab is the mistake `03-navigation-decision.md`
 * records; keeping it out of the enum means the bottom bar cannot regress into
 * listing it again.
 *
 * The list and the tree are **one** overlay with two views, not two overlays: they
 * answer the same question, swap in place behind one segmented control, and — the
 * part that decides it — Compose hands a back press to the most recently composed
 * handler, so stacking two overlays would leave only the top one closable while the
 * app keeps its single `BackHandler` promise (`PiRoot`'s own note below). The
 * full-screen terminal joins them here, from the settings row, for the same reason:
 * it is not a place either.
 */
private enum class PiOverlay {
    /** The session list — and, behind one tap, pi's `/tree`. */
    SessionList,

    /**
     * The full-screen terminal, opened from the settings home's terminal row.
     *
     * It is an overlay rather than a destination because the user retired it from
     * the bottom bar: it is a fallback for the handful of extension APIs the RPC
     * path cannot carry, not a place to spend the bar's most visible slot
     * (`03-navigation-decision.md`). Its own screen is deliberately not redesigned
     * — `ui/terminal` is out of scope for this refactor.
     */
    Terminal,
}

/**
 * Which of the two views the session overlay opens on.
 *
 * `NavRequest.SessionTree` (pi's `/tree`, and the branch-summary row's tap) has to
 * land on the tree rather than on the list — a command that says 树 must not make the
 * user tap a segmented control to reach it. It is a *preference* rather than a
 * separate overlay because there is only one overlay; see [PiOverlay].
 */
private enum class SessionViewPreference { List, Tree }

/**
 * `rememberSaveable` cannot persist an enum (it is neither a `Bundle` value nor
 * `Serializable`), so the shown overlay is stored as its ordinal and mapped back.
 * Out-of-range values land on `null` rather than throwing: a restored state from a
 * build whose overlay set was smaller must not crash the launch.
 */
private fun overlayAt(index: Int?): PiOverlay? =
    index?.let { PiOverlay.entries.getOrNull(it) }

/**
 * The bottom inset assumed while an overlay covers the screen, when the
 * `Scaffold` reports none.
 *
 * [BOTTOM_BAR_HEIGHT] is 56dp, so the scaffold's bottom padding is 56dp whenever
 * the bar is drawn — which it always is, because the overlay draws *inside* the
 * scaffold's content box rather than replacing it. The fallback is only for the
 * case where that stops being true: a snackbar with a zero inset sits under the
 * system navigation bar, unseen. 56 + 8 is the same 8dp the snackbar host below
 * has always asked for.
 */
private val OVERLAY_SNACKBAR_INSET = 64.dp

// ------------------------------------------------------------------ the top bar
//
// v2's `TopBar` (`design-demos/direction-b-v2.html:489`), value for value: 48 high
// on the dim surface with a 1px bottom rule, 14 horizontal, a 17/600 title with an
// optional 12 meta line under it, a 32x32 radius-8 press box for the back chevron
// (an 18 glyph), and 30x30 press boxes with 17 glyphs for the actions.
//
// Hand-drawn rather than `material3.TopAppBar` because none of the three values M3
// fixes can be reached through it without a fight: its height is
// `TopAppBarExpandedHeight` (64, and the `expandedHeight` parameter is the only way
// out), its container is `surface` rather than the board's `surfaceDim`, it draws no
// bottom rule at all, and its `navigationIcon`/`actions` slots wrap every child in a
// 48dp `IconButton` with a 24dp glyph — visibly larger than the board's 32/18 and
// 30/17 boxes. Every screen in this half goes through this one function instead, so
// the chrome cannot drift screen by screen.

/** `06 §2`「顶栏：高 48」. */
private val TOP_BAR_HEIGHT = 48.dp

/** `06 §2`「水平 14」. */
private val TOP_BAR_INSET = 14.dp

/** The back chevron's press box and glyph (`TopBar`: 32x32 radius 8, icon 18). */
private val TOP_BAR_BACK_BOX = 32.dp
private val TOP_BAR_BACK_GLYPH = 18.dp

/**
 * `TopBar`'s `margin-left:-6` on the back box: the chevron sits 8 from the edge
 * while the rest of the row still measures from 14. Reproduced by pulling the row's
 * leading inset in by the same amount only when there *is* a back box, so a screen
 * without one keeps the plain 14 (which is what `phone4` shows).
 */
private val TOP_BAR_BACK_NUDGE = 6.dp

/** The row's own gap between the back box, the title block and the actions. */
private val TOP_BAR_GAP = 10.dp

/** `TopBar` right cluster: `gap:4`. */
private val TOP_BAR_ACTION_GAP = 4.dp

/** One action's press box and glyph (`TopBar` right icons: 30x30, icon 17). */
private val TOP_BAR_ACTION_BOX = 30.dp
private val TOP_BAR_ACTION_GLYPH = 17.dp

/** The text action's own padding (`TopBar`: `5px 9px`). */
private val TOP_BAR_TEXT_ACTION_PADDING = 9.dp
private val TOP_BAR_TEXT_ACTION_PADDING_VERTICAL = 5.dp

/** The radius both press boxes use. */
private val TOP_BAR_PRESS_SHAPE = RoundedCornerShape(8.dp)

/**
 * The app bar every screen in this half draws.
 *
 * @param meta the second line: v2's 12 muted text, one `margin-top:1` under the
 *   title.
 * @param engineMeta the second line for a screen whose second line *is* the engine
 *   state — the session overlay (`phone19`) does this. It wins over [meta] when both
 *   are given, and it is the triple-encoded spelling (`✓ 就绪`), not a bare word:
 *   see [PiTopBarEngineMeta].
 * @param onBack null on the three top-level destinations; when it is set the
 *   chevron is the *first* child and the title block starts 50 from the left edge,
 *   which is where the board puts it.
 * @param actions the right cluster. The caller supplies its own press boxes so a
 *   text action (v2's 导入 / 刷新 / 保存到 Download) and an icon action can sit
 *   side by side as the board draws them.
 */
@Composable
fun PiTopBar(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    engineMeta: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            // `surfaceDim` is the design's `--surf-dim`, the same chrome tone the
            // bottom bar uses: one step below the content's own background.
            .background(MaterialTheme.colorScheme.surfaceDim),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_BAR_HEIGHT)
                .padding(
                    start = if (onBack != null) TOP_BAR_INSET - TOP_BAR_BACK_NUDGE else TOP_BAR_INSET,
                    end = TOP_BAR_INSET,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TOP_BAR_GAP),
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(TOP_BAR_BACK_BOX)
                        .clip(TOP_BAR_PRESS_SHAPE)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(TOP_BAR_BACK_GLYPH),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (engineMeta != null) {
                    PiTopBarEngineMeta(engineMeta)
                } else if (meta != null) {
                    Text(
                        meta,
                        // `TopBar`: `marginTop:1`.
                        modifier = Modifier.padding(top = 1.dp),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TOP_BAR_ACTION_GAP),
                    content = actions,
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * One icon action's press box for [PiTopBar]: the board's 30x30 radius-8 box with a
 * 17 glyph, so a top bar does not have to carry its own copy of those numbers.
 */
@Composable
fun PiTopBarIcon(onClick: () -> Unit, contentDescription: String, icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(TOP_BAR_ACTION_BOX)
            .clip(TOP_BAR_PRESS_SHAPE)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(TOP_BAR_ACTION_GLYPH),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One text action's press box for [PiTopBar] — v2's 导入 / 刷新 / 保存到 Download
 * (`TopBar` right cluster: `padding:5px 9px`, `border-radius:8`, `t13`).
 *
 * The label is `bodyMedium` (14) rather than the board's 13: the app's own type scale
 * has no 13 non-monospace role, and this text is a control rather than machine
 * output, so it takes the body role the rest of the chrome uses. The one-step
 * difference is recorded in this batch's deviation list rather than invented here.
 */
@Composable
fun PiTopBarTextAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(TOP_BAR_PRESS_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = TOP_BAR_TEXT_ACTION_PADDING, vertical = TOP_BAR_TEXT_ACTION_PADDING_VERTICAL),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * The app bar's **engine status line**, triple-encoded (symbol + word + tone) as
 * `06 §4` requires: `✓ 就绪` success, `… 工作中` warning, `◌ 启动中` warning,
 * `≡ 排队中` warning, `✗ 引擎已退出` / `■ 引擎已停止` error.
 *
 * The words are not this file's: they are [PiSessionViewModel.engineLabel]'s, which
 * returns pi's own vocabulary and also passes through a *busy verb* ("读取会话树")
 * while a request is in flight. Every one of those verbs is a wait, so the default
 * arm is `… warning` — the same rule `06 §4` states for busy verbs.
 *
 * It lives beside [PiTopBar] because the top bar is the only place that draws it: the
 * chat's app bar is the other caller, and both must spell the six states the same way
 * or the engine would read differently on two screens.
 */
@Composable
fun PiTopBarEngineMeta(word: String) {
    val (glyph, tone) = when (word) {
        "就绪" -> "✓" to PiTheme.palette.success
        "引擎已退出", "引擎已停止" -> "✗" to PiTheme.palette.error
        else -> "…" to PiTheme.palette.warning
    }
    Row(
        modifier = Modifier.padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TOP_BAR_ENGINE_GAP),
    ) {
        Text(
            glyph,
            style = PiTheme.text.monoSmall,
            color = tone,
        )
        Text(
            word,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `Engine` 的符号到词之间：`gap:5`。 */
private val TOP_BAR_ENGINE_GAP = 5.dp

/**
 * v2's `Seg` (`design-demos/direction-b-v2.html:644`) — the segmented control the
 * session overlay switches 会话 / 会话树 with, and the one the tree filters with.
 *
 * `gap:2`, `surf-high` fill, 1px `borderMuted`, radius 8, `padding:2`; each item is
 * 24 high, `padding:0 10`, radius 6, 12 at weight 500/400, the selected one on
 * `--selected-bg` in the body colour, the others transparent in `muted`.
 *
 * Hand-drawn because M3's `SegmentedButton` cannot be talked out of three things the
 * board does not have: a 40dp item, a per-item outline, and the check glyph it draws
 * inside the selected item (the app's callers were passing `shape = itemShape(...)`
 * only, so the ticks were being drawn on device).
 *
 * It sits beside [PiTopBar] rather than in `ui/components/` because that package
 * belongs to the other half of this refactor (only `PiDialog.kt` is this half's new
 * file there); it should move next to `PiDialog` when the split is next opened.
 */
@Composable
fun PiSeg(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(SEG_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outline, SEG_SHAPE)
            .padding(SEG_PADDING),
        horizontalArrangement = Arrangement.spacedBy(SEG_ITEM_GAP),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(SEG_ITEM_HEIGHT)
                    .clip(SEG_ITEM_SHAPE)
                    .background(
                        if (selected) PiTheme.palette.selectedBg else Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = SEG_ITEM_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        PiTheme.palette.muted
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** `Seg` 的取值：外圆角 8、`padding:2`、项高 24、`padding:0 10`、项圆角 6、gap 2。 */
private val SEG_SHAPE = RoundedCornerShape(8.dp)
private val SEG_ITEM_SHAPE = RoundedCornerShape(6.dp)
private val SEG_PADDING = 2.dp
private val SEG_ITEM_GAP = 2.dp
private val SEG_ITEM_HEIGHT = 24.dp
private val SEG_ITEM_PADDING = 10.dp

// ------------------------------------------------------------------ the bottom bar
//
// v2's `TabBar` (`design-demos/direction-b-v2.html`), value for value: 56 high on
// the dim surface, a 1px top rule, a 20 icon over a 12 label with 3 between them,
// accent for the selected entry, and an 18x1 accent rule under it. These are
// literals rather than members of `ui/theme/PiTheme.kt`'s spacing object because
// that file is being edited by another agent in this same round; a later batch
// folds them in.

/** The bar's height, and (with [OVERLAY_SNACKBAR_INSET]) the scaffold's bottom inset. */
private val BOTTOM_BAR_HEIGHT = 56.dp

/** Gap between an entry's glyph and its label. */
private val BOTTOM_BAR_ITEM_GAP = 3.dp

/** The glyph's box, which is also the design's 20px icon size. */
private val BOTTOM_BAR_ICON = 20.dp

/** The label's size — the design's smallest type step. */
private val BOTTOM_BAR_LABEL_SIZE = 12.sp

/** The selected entry's underline: width, height, and distance from the bar's bottom. */
private val BOTTOM_BAR_UNDERLINE_WIDTH = 18.dp
private val BOTTOM_BAR_UNDERLINE_HEIGHT = 1.dp
private val BOTTOM_BAR_UNDERLINE_INSET = 6.dp

@Composable
fun PiRoot() {
    // The destination is saved by **name**, not by ordinal. The ordinal was safe
    // while the enum only grew, but this refactor removed values from the front
    // of it, and a restored `0` would then land on a different destination than
    // the one the user left (`会话` is gone, so an old `0` must not become `对话`
    // silently — it must become the start destination). A name that no longer
    // exists falls back to the start destination on its own.
    var destinationName by rememberSaveable { mutableStateOf(PiDestination.Chat.name) }
    val current = PiDestination.entries.firstOrNull { it.name == destinationName }
        ?: PiDestination.Chat

    // One overlay slot for the whole app. See [PiOverlay] for why it is not a
    // destination; see the single `BackHandler` below for why it lives here and
    // nowhere else.
    var overlayIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val overlay = overlayAt(overlayIndex)

    // Which view the session overlay should open on. It is hoisted here, rather than
    // left to `SessionsScreen`'s own `rememberSaveable`, because `/tree` and the
    // branch-summary row have to open the overlay **on the tree**
    // ([SessionViewPreference]); state inside the screen could not be set before the
    // screen exists.
    var sessionView by rememberSaveable { mutableStateOf(SessionViewPreference.List.name) }

    // The per-destination state that has to survive a destination switch.
    //
    // `when (current)` below *removes* the other destinations from the
    // composition, so everything they remembered goes with them: the transcript's
    // `rememberLazyListState` (the scroll offset), the follow machine (`tail`),
    // the render window, the in-chat search term and cursor. Coming back to 对话
    // used to land at the top of the stream with the follow paused — the reported
    // "切回来不会停在最底下".
    //
    // `SaveableStateHolder` is exactly the mechanism a navigation library uses for
    // this: each destination's `rememberSaveable` values are saved under its own
    // key when its branch leaves the composition and restored when it returns.
    // Plain `remember` is **not** covered (it is not saveable by definition), so
    // this preserves what was already `rememberSaveable` — which in `ChatScreen` is
    // the scroll state, the follow machine, the window, the search term/cursor, the
    // expansion flag and the paused/armed counters; the composer draft, `sheet` and
    // `overflow` stay plain `remember` and still reset on a switch.
    val saveableStateHolder = rememberSaveableStateHolder()

    // A settings key a command asked to open (pi's `/scoped-models` — see
    // `NavRequest.SettingsFocus`). It lives here rather than inside the stack
    // because the stack is composed only while 设置 is the current destination,
    // so the request has to survive that switch.
    var settingsFocus by rememberSaveable { mutableStateOf<String?>(null) }

    // One engine for the whole app. Owned by the ViewModel rather than an
    // Activity so that a running turn survives the user leaving the screen, and
    // started here because the app has no "connect" concept: opening it starts
    // the local engine.
    val session: PiSessionViewModel = viewModel()
    LaunchedEffect(Unit) { session.boot() }

    // The theme picker lists what pi would load, not only what the `themes`
    // setting names, and the active theme's caveats are shown with it. Both come
    // from the ViewModel, which is the one place that reads pi's theme files.
    val theme by session.theme.collectAsState()
    val themeEntries by session.themeEntries.collectAsState()

    // 「运行时加速（实验性）」开关当场生效的进度。拨完开关探针要跑 10~60 秒，设置页那一行
    // 必须在那段时间里说它在跑 —— 这是这个状态唯一的用途（见 `RuntimeSwitchAction`）。
    val runtimeSwitch by session.runtimeSwitch.collectAsState()

    // Commands that need a destination are requested by the ViewModel through
    // state, because they finish inside a coroutine after an RPC answer — by then
    // there is no composable left to call back into. Consuming the request here
    // keeps the ViewModel free of Compose navigation knowledge.
    //
    // Consuming it *inside* the effect is what lets the effect be keyed on the
    // request: the handler writes the request back to `null`, so the effect runs
    // once for the request and once for the `null`, and the second run is how the
    // same request can be made again (two `/tree` commands in a row).
    val uiState by session.state.collectAsState()
    val navRequest = uiState.navRequest
    LaunchedEffect(navRequest) {
        when (navRequest) {
            // The session list is an overlay, so the request only raises the
            // layer; it must not also move the destination. Landing on 对话
            // happens when a row is picked, which is what the user asked for by
            // picking it.
            //
            // This is what `/resume` does too: the palette row is typed in the
            // composer, so the user is already on 对话 with the session they want to
            // leave in front of them. Switching destinations first would rebuild the
            // transcript and move the ground under the list they are about to read,
            // for no gain. If they pick a row, the pick moves them then.
            NavRequest.SessionList -> {
                sessionView = SessionViewPreference.List.name
                overlayIndex = PiOverlay.SessionList.ordinal
            }
            NavRequest.Chat -> {
                overlayIndex = null
                destinationName = PiDestination.Chat.name
            }
            NavRequest.Settings -> {
                overlayIndex = null
                destinationName = PiDestination.Settings.name
            }
            is NavRequest.SettingsFocus -> {
                overlayIndex = null
                settingsFocus = navRequest.key
                destinationName = PiDestination.Settings.name
            }
            // pi's `/tree`: the *same* overlay as the session list, opened on its
            // second view. There is no separate tree overlay — see [PiOverlay] — so
            // this both raises the layer and picks the view.
            NavRequest.SessionTree -> {
                sessionView = SessionViewPreference.Tree.name
                overlayIndex = PiOverlay.SessionList.ordinal
                session.refreshTree()
            }
            // The terminal raises the overlay layer for the same reason the list
            // does: it is not a place. This is the *only* way in now — the chat
            // composer's chip and the overflow entry are gone (the user retired
            // the terminal), so the settings home's row is the one door.
            NavRequest.Terminal -> {
                overlayIndex = PiOverlay.Terminal.ordinal
            }
            null -> Unit
        }
        session.consumeNav()
    }

    // **The bottom bar is always there.** v2's Boot screens (`phone59`–`phone61`) are a
    // full-screen takeover with no bar (HTML:3242 draws `.b-boot` with no `TabBar`
    // outside it), and this app used to hide its bar to match while the chat page was
    // taken over. It does not any more: a first install unpacks the whole runtime and
    // that takes minutes, and a bar the user cannot use to leave the waiting screen is
    // worse than a bar v2 did not draw. The only thing the takeover still changes is
    // *inside* the chat destination (`screens/ChatScreen.kt`), which decides for itself
    // whether to draw `BootScreen` in place of the transcript.

    Scaffold(
        // `imePadding()` is what makes the soft keyboard *displace* the UI instead of
        // covering it. The activity is `enableEdgeToEdge()` (`MainActivity.kt:19`) and
        // the manifest asks for `adjustResize` (`AndroidManifest.xml:118`), but with
        // edge-to-edge the framework stops resizing the window for the IME and reports
        // it as an inset instead — so `adjustResize` alone lifts nothing, and the
        // composer, its toolbar and the bottom bar all ended up behind the keyboard
        // (seen on device, docs/known-gaps.md §M2). Applied here, once, for every
        // destination: the terminal used to carry its own `imePadding()` and would now
        // pad twice, so that one was removed.
        modifier = Modifier.imePadding(),
        bottomBar = {
            PiBottomBar(
                current = current,
                onSelect = { destinationName = it.name },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (current) {
                // One provider per destination, keyed by the enum constant's name:
                // the keys are distinct by construction (an enum has no duplicate
                // names) and stable across a switch, which is what the holder
                // requires. No `removeState` is needed here — the set of keys is the
                // closed set of three destinations, not a growing stack, so nothing
                // can be orphaned. (A future sub-page stack would have to call it.)
                PiDestination.Chat -> saveableStateHolder.SaveableStateProvider(current.name) {
                    ChatScreen(
                    contentPadding = padding,
                    session = session,
                    // The session name in the app bar is the way into the session
                    // list. It raises the overlay only — the destination stays 对话,
                    // because the user is picking a session *for this screen*, and
                    // moving them first would rebuild the transcript underneath the
                    // list they are about to read.
                        onOpenSessions = {
                            sessionView = SessionViewPreference.List.name
                            overlayIndex = PiOverlay.SessionList.ordinal
                        },
                    )
                }

                // The project's own screen. It reads pi's session directory, the
                // transcript and the workspace's `.pi`, so it takes the session —
                // the terminal it replaced took nothing.
                PiDestination.Workbench -> saveableStateHolder.SaveableStateProvider(current.name) {
                    ProjectScreen(
                        contentPadding = padding,
                        session = session,
                    )
                }

                PiDestination.Settings -> saveableStateHolder.SaveableStateProvider(current.name) {
                    PiSettingsStack(
                        contentPadding = padding,
                    // The real store: pi's own settings.json files, merged the way
                    // pi merges them. Without it the stack would read and write an
                    // in-memory map and the app would appear to accept changes
                    // that never reach the engine.
                    //
                    // Wrapped for the one row whose value must **not** go to pi's file:
                    // `app.extensions.args` is extension CLI flags, and pi has no
                    // settings key for them (`ExtensionArgsStoreDecorator` delegates
                    // every other key unchanged).
                    store = remember(session) { session.settingsStoreForSettingsUi() },
                    knownThemes = themeEntries,
                    themeNotes = theme.notes,
                    themeError = theme.error,
                    // A written setting the app itself consumes (appearance, the
                    // transcript's thinking toggle, the theme) has to be noticed:
                    // the store is pi's file, and it has no change notification.
                    onSettingWritten = { key -> session.onSettingWritten(key) },
                    // The Action rows the engine has to run. The settings stack
                    // implements the ones that need a screen of its own (the
                    // credential form, `PiSettingsStack.hostActions`); everything
                    // else arrives here.
                    //
                    // There used to be a `TERMINAL_ONLY_ACTIONS` map holding three
                    // signposts to pi's own TUI (`/login`, `/import`, `/changelog`).
                    // All three are gone now: a settings row whose only content is "go
                    // somewhere else" is not a setting. The OAuth row stays, because no
                    // other surface in this app can start it — and its answer now names
                    // the one that can (工作区 → 终端), instead of saying "本应用没有入口",
                    // which contradicted the terminal page's own header and was a claim
                    // this app could not back up.
                    onRunAction = { setting ->
                        when (setting.key) {
                            // pi keeps OAuth in its own interactive shell
                            // (`interactive-mode.ts:3052-3055` → `handleLoginCommand`
                            // `:5485`) and the `RpcCommand` union has no login command
                            // (`modes/rpc/rpc-types.ts:20-74`), so no screen in this app
                            // can start it. The terminal page can: `PtyLauncher` hands the
                            // guest shell the same `PI_CODING_AGENT_DIR` the engine uses
                            // (`runtime/PtyLauncher.kt:396-404`), so `/login` there writes
                            // the same `auth.json` the credential page reads. Say where,
                            // and say what this app supports natively (an API key, on the
                            // row above). Protocol detail stays in the KDoc, not on screen.
                            "app.credentials.oauth" -> {
                                session.notifyUser(
                                    "本应用没有内建登录表单。订阅登录要走 pi 的原版 TUI：" +
                                        "到 工作区 → 终端 里输入 pi 回车，再运行 /login。" +
                                        "用 API Key 的厂商可以在上面的「API Key」里配置。",
                                    warning = true,
                                )
                            }
                            // pi's `/compact` — the RPC command is `compact`
                            // (`modes/rpc/rpc-types.ts:44`). The custom-instruction
                            // form of `/compact` needs a text field and lives in the
                            // chat palette (`ChatScreen.kt:341`), which this row's
                            // description now names; this entry triggers the bare one.
                            "app.compaction.runNow" -> session.compact()
                            // pi's Escape, as a button: `clear_queue` + `abort`
                            // (`PiEngineSession.stopAndDrainQueue` `:480-486`) plus
                            // `abort_bash` (`PiEngineApi.kt:262`), which aborts
                            // *every* running bash command, not just one
                            // (`agent-session.ts:3073-3077`). Nothing is killed
                            // outside those RPC commands, which is exactly what the
                            // row's description promises.
                            "app.security.emergencyStop" -> {
                                session.stop()
                                session.abortBash()
                            }
                            // Reachable for no `PiRowKind.Action` row in the registry
                            // today: the credential rows are handled by
                            // `PiSettingsStack.hostActions`, and the three above are
                            // the ones this host runs. It stays as a regression guard
                            // so a row added later without a handler is loud instead of
                            // silent — the failure `docs/known-gaps.md` §I2 records.
                            // The text names no internal cause on purpose: a user
                            // cannot act on "this row has no handler".
                            else -> session.notifyUser(
                                "「${setting.title}」当前不可用，请把这一步报告给我们。",
                                warning = true,
                            )
                        }
                    },
                    // The engine restart the package and credential screens ask
                    // for. `allowInterrupt` stays whatever the coordinator passes
                    // (always false); a turn can never be killed by a settings tap.
                    restartEngine = { reason, allowInterrupt ->
                        session.restartEngine(reason, allowInterrupt)
                    },
                    isTurnRunning = { session.isTurnRunning() },
                    // 导出诊断报告的两个事实来源。都是 lambda：报告必须在**打开那一屏
                    // 的时刻**读到最新的抓取，而引擎可能在那一屏开着的时候死掉。
                    // `engineDiagnostics` 来自引擎退出瞬间的捕获（ViewModel 在丢弃死引擎
                    // 之前存下的退出码与 stderr），`recentFailures` 来自 App 已经记下的失败。
                    // 没有这两条接线，报告里那两节永远只会写"还没有记录"。
                    engineDiagnostics = { session.engineDiagnostics() },
                    engineEntry = { session.engineEntryLabel() },
                    recentFailures = { session.recentFailures() },
                    // `get_available_models`: the only model list pi exposes over
                    // RPC, used to mark a scanned id as pi metadata or as an app
                    // default (`ModelProviderScreen`).
                    availableModels = uiState.models,
                    onLoadAvailableModels = { session.refreshModels() },
                    // `/scoped-models`: open the group and highlight the key.
                    focusKey = settingsFocus,
                    onFocusConsumed = { settingsFocus = null },
                    // 「运行时加速（实验性）」那一行拨完之后的进度：探针在跑的时候，「运行时
                    // （实际生效）」那一行显示「正在测探针」而不是上一次的旧结论。
                    runtimeSwitch = runtimeSwitch,
                    // The credential form writes settings.json through the packages
                    // layer, which invalidates a store instance of its own; the store
                    // this app reads belongs to the ViewModel, so dropping its cache
                    // is the ViewModel's job.
                    onExternalSettingsWrite = { session.invalidateSettingsCache() },
                    // The terminal is the settings home's one row now, and the
                    // screen it opens belongs to this overlay layer — so the stack
                    // hands the tap back up rather than opening it itself.
                        onOpenTerminal = { session.requestNav(NavRequest.Terminal) },
                    )
                }
            }

            // The overlays draw over whatever destination is active. They are
            // checked after the destination switch so they win the draw order, and
            // before the extension host so that a blocking dialog stays on top of
            // them: an extension waiting on an answer must be answerable from the
            // session list too (see the note on the single host below).
            val shown = overlay
            if (shown != null) {
                // Opaque, not translucent: the transcript underneath must not
                // read as part of this layer (the ask that produced this batch's
                // device checklist, §7.2 item 10). `Surface` with the scheme's
                // background is the same backdrop the destinations themselves
                // draw on — the same call `SessionTreeScreen.kt:100` makes.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (shown) {
                        PiOverlay.SessionList -> {
                            // **两个回调的 identity 固定下来。** 它们只捕获 `overlayIndex` 与
                            // `destinationName` 这两个 `MutableState`（在本组合的整个生命周期里是
                            // 同一个对象），所以 `remember` 之后**行为逐字不变** —— 而这一屏的
                            // 父级每收到一次 `UiState` 发布（流式期间约 200 ms 一次）就重组，从前的
                            // 字面量 lambda 每次都换新实例，`SessionsScreen` 的参数按 `===` 比较
                            // 因此不等，整屏（与列表里的行）都得跟着重算。同一条机制与证据写在
                            // `ChatScreen` 的 `onForkFromMessage` 上（`docs/scroll-perf-list.md` §2.1）。
                            val onOpenChat: () -> Unit = remember {
                                {
                                    overlayIndex = null
                                    destinationName = PiDestination.Chat.name
                                }
                            }
                            val onCloseSessionList: () -> Unit = remember { { overlayIndex = null } }
                            SessionsScreen(
                                contentPadding = padding,
                                session = session,
                                // A pick switches the session and lands on it. The
                                // overlay closes because otherwise the user stays on
                                // the list they just answered.
                                onOpenChat = onOpenChat,
                                onClose = onCloseSessionList,
                                // Which view to open on: `/tree` and the branch-summary
                                // row ask for the tree, everything else for the list.
                                initialView = if (sessionView == SessionViewPreference.Tree.name) {
                                    SessionsView.Tree
                                } else {
                                    SessionsView.List
                                },
                            )
                        }

                        // The full-screen terminal, unchanged from when it was a
                        // destination: this batch moves the *door*, not the room
                        // (`05-compose-migration-plan.md` §3.8).
                        PiOverlay.Terminal -> TerminalScreen(
                            contentPadding = padding,
                            onBack = { overlayIndex = null },
                        )
                    }
                }
            }

            // The app's **only** overlay-back handler, and the reason it exists:
            // before it, the back key left the app while an overlay was open —
            // the handler the overlay would otherwise need did not exist
            // (`00-screen-inventory.md` §1.4).
            //
            // It is one call site by construction. Compose keeps every
            // `BackHandler` alive at once and hands the press to the
            // **most recently composed** one, so a second handler inside an
            // overlay would silently shadow this one, and two handlers that both
            // claim to close "the overlay" are the exact defect this batch is
            // told to avoid: the press goes to the wrong one and the app exits.
            // It is placed after the overlay in composition order for that same
            // reason — if the destination under an overlay ever registers a
            // handler of its own (the settings stack already has one, for its
            // internal levels), this one still gets the press.
            //
            // `PiSettingsStack.kt:269` keeps its own handler: it is a
            // *navigation* back (search → group → out), not an overlay back, and
            // it is enabled only while an inner level is open, which the overlay
            // covers anyway.
            BackHandler(enabled = overlay != null) { overlayIndex = null }

            // Mounted once, above the destination switch, because an extension
            // dialog is not chat-specific: `notify` and `extension_error` arrive
            // wherever the user happens to be, and a dialog nobody can see is a
            // blocked engine — `editor` has no timer on pi's side at all
            // (packages/coding-agent/src/modes/rpc-mode.ts:254-271).
            //
            // It must not also be mounted by a screen. Two hosts would render
            // two dialogs for one request, and two snackbar hosts would race for
            // the same notice queue; the screens' KDocs say so for that reason.
            //
            // The padding has to survive `calculateBottomPadding()` returning 0.
            // The scaffold knows the bottom bar's height and reports it, so the
            // normal `+ 8.dp` is the bar plus a margin; a `maxOf` with a constant
            // keeps the snackbar above the bar even if that stops being reported.
            ExtensionUiHost(
                session = session,
                snackbarBottomPadding = maxOf(
                    padding.calculateBottomPadding() + 8.dp,
                    OVERLAY_SNACKBAR_INSET,
                ),
            )
        }
    }
}

/**
 * The bottom bar: the app's three destinations, drawn to v2's `TabBar`.
 *
 * Hand-drawn rather than `material3.NavigationBar` because the Material component
 * cannot be talked out of two of its parts: the selection **indicator** pill
 * behind the icon (v2 has none) and its own icon/label tinting rules. All three
 * entries are siblings in one `selectableGroup`, so they are announced as one
 * choice of three and the selected state is `Role.Tab`; the entries are ~137dp
 * wide and the full [BOTTOM_BAR_HEIGHT] tall, which is the design's layout and
 * also comfortably past the 48dp touch minimum at any font scale.
 */
@Composable
private fun PiBottomBar(current: PiDestination, onSelect: (PiDestination) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(BOTTOM_BAR_HEIGHT)
            // `surfaceDim` is the design's `--surf-dim`: the chrome tone, one step
            // below the destinations' own background, which is what makes the bar
            // read as chrome rather than as content.
            .background(MaterialTheme.colorScheme.surfaceDim),
    ) {
        // `outline` is pi's `borderMuted` — the design's `1px solid var(--border-muted)`.
        // Drawn as a real divider rather than a border modifier so it is exactly
        // one hairline and takes no sub-pixel rounding from the bar's edges.
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // One tab stop and one "selected" announcement per entry, instead
                // of three unrelated buttons.
                .selectableGroup(),
        ) {
            PiDestination.entries.forEach { item ->
                PiBottomBarItem(
                    item = item,
                    selected = item == current,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

/**
 * One entry: glyph over label, both accent when selected, with the design's 18x1
 * accent rule under the selected one.
 *
 * The rule is a zero-height placeholder that grows to a hairline when selected,
 * not an absolutely positioned overlay (which Compose has no modifier for): v2
 * places it at the bar's bottom without taking layout space, and a zero-height
 * sibling does the same, so selecting an entry cannot push its label up.
 */
@Composable
private fun RowScope.PiBottomBarItem(
    item: PiDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The design's one accent use on this bar: the entry you are on. The glyph is
    // accent, the label is accent and one weight heavier; an unselected glyph is
    // muted while its label keeps the body colour, so the label stays readable.
    val glyphColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val labelColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val labelWeight = if (selected) FontWeight.Medium else FontWeight.Normal

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // Selection, not a plain click: a tab strip that does not announce
            // which tab is current is the accessibility half of the same fact the
            // accent colour states visually.
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        PiNavGlyph(
            vector = item.icon,
            color = glyphColor,
            modifier = Modifier.size(BOTTOM_BAR_ICON),
        )
        Spacer(Modifier.height(BOTTOM_BAR_ITEM_GAP))
        Text(
            text = item.label,
            color = labelColor,
            fontSize = BOTTOM_BAR_LABEL_SIZE,
            fontWeight = labelWeight,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        // Zero height when unselected, so the hairline costs nothing until it is
        // drawn; the label above it does not move either way, because the box
        // stays in the layout at both heights.
        Box(
            Modifier
                .height(if (selected) BOTTOM_BAR_UNDERLINE_HEIGHT else 0.dp)
                .width(BOTTOM_BAR_UNDERLINE_WIDTH)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.height(BOTTOM_BAR_UNDERLINE_INSET))
    }
}
