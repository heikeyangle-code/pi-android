package app.pi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import app.pi.ui.components.PiType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pi.ui.chat.SessionTreeScreen
import app.pi.ui.extension.ExtensionUiHost
import app.pi.ui.screens.ChatScreen
import app.pi.ui.screens.ProjectScreen
import app.pi.ui.screens.SessionsScreen
import app.pi.ui.screens.SessionsView
import app.pi.ui.screens.TerminalScreen
import app.pi.ui.settings.PiSettingsStack
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.engineStateGlyph
import app.pi.ui.theme.engineStateTone
import app.pi.ui.theme.stateToneColor

/**
 * The three top-level destinations.
 *
 * There were four until the v2 refactor (`design/ui-refactor/03-navigation-decision.md`):
 * `会话` was dropped because a session *list* is a selector, not a place — pi
 * itself draws its session tree over the transcript, and the desktop habit is a
 * sheet or a drawer. `终端` was dropped for the opposite reason: the terminal is a
 * fallback entry point the user has declared unusable.
 *
 * **底栏在「会话主页」那一批里拆掉了**（用户裁决）：所有页面都不再有 `bottomBar`，
 * 工作区/设置改成顶栏入口 —— 会话页顶栏两个图标，对话页「⋯」菜单两项。这个枚举
 * 留着，因为目的地仍然只有三个，切目的地走的还是 `destinationName`。
 *
 * The start destination stays [Chat]: cold start is a new conversation.
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
}


/**
 * Which of the two views the session overlay opens on.
 *
 * `NavRequest.SessionTree` (pi's `/tree`, and the branch-summary row's tap) has to
 * land on the tree rather than on the list — a command that says 树 must not make the
 * user tap a segmented control to reach it. It is a *preference* rather than a
 * separate overlay because there is only one overlay; see
 * [PiSessionViewModel.overlay] for the one owner of it.
 */

/**
 * `rememberSaveable` cannot persist an enum (it is neither a `Bundle` value nor
 * `Serializable`), so the shown overlay is stored as its ordinal and mapped back.
 * Out-of-range values land on `null` rather than throwing: a restored state from a
 * build whose overlay set was smaller must not crash the launch.
 */

/**
 * The bottom inset assumed while an overlay covers the screen, when the
 * `Scaffold` reports none.
 *
 * 底栏拆掉之后 `Scaffold` 的底距是系统导航条 inset（手势条约 24、三键约 48），
 * 覆盖层画在 scaffold 的内容盒里、不替换它，所以那个数永远不为零。这个常数只是
 * 「再也报告不出来」时的兜底：那一下 snackbar 会落到系统导航条下面，看不见。
 * 取 32 是手势条 inset 加同一处 8dp 的呼吸 —— 三键导航下略高一点，但看得见。
 */
private val OVERLAY_SNACKBAR_INSET = 32.dp

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

/** 所有屏共用的顶栏高度：56（对话页顶栏同值）。 */
private val TOP_BAR_HEIGHT = 56.dp

/** `06 §2`「水平 14」. */
private val TOP_BAR_INSET = 14.dp

/** The back chevron's press box and glyph: 48 热区 / 24 字形，与右侧图标同一套。 */
private val TOP_BAR_BACK_BOX = 48.dp
private val TOP_BAR_BACK_GLYPH = 24.dp

/**
 * `TopBar`'s `margin-left:-6` on the back box: the chevron sits 8 from the edge
 * while the rest of the row still measures from 14. Reproduced by pulling the row's
 * leading inset in by the same amount only when there *is* a back box, so a screen
 * without one keeps the plain 14 (which is what `phone4` shows).
 */
private val TOP_BAR_BACK_NUDGE = 6.dp

/** The row's own gap between the back box, the title block and the actions. */
private val TOP_BAR_GAP = 10.dp

/** 右侧图标的间隔：0，48dp 热区本身已经给出 24dp 的视觉间隔。 */
private val TOP_BAR_ACTION_GAP = 0.dp

/** One action's press box and glyph: 48 热区 / 24 字形。 */
private val TOP_BAR_ACTION_BOX = 48.dp
private val TOP_BAR_ACTION_GLYPH = 24.dp

/** The text action's own padding (`TopBar`: `5px 9px`). */
private val TOP_BAR_TEXT_ACTION_PADDING = 9.dp
private val TOP_BAR_TEXT_ACTION_PADDING_VERTICAL = 5.dp

/** 文字按钮的按压缩形状（图标按钮用圆形，见 [TOP_BAR_ICON_SHAPE]）。 */
private val TOP_BAR_PRESS_SHAPE = RoundedCornerShape(8.dp)

/** 图标按钮/返回键的圆形热区，与对话页顶栏一致。 */
private val TOP_BAR_ICON_SHAPE = CircleShape

/**
 * The app bar every screen in this half draws — now the same geometry as the chat top
 * bar: 56 high, page-coloured and opaque, 16 SemiBold title, 48/24 icon actions.
 *
 * 顶栏不再画常驻分隔线；对话页的 0.5dp 发丝线由它自己的滚动状态控制。
 *
 * @param statusLabel when set, a 7dp engine-state dot is drawn before the title
 *    (colour from [stateToneColor]); the session overlay uses it.
 * @param onBack null on the top-level destinations; when set the circular back box
 *    is the first child.
 * @param actions the right cluster; callers pass [PiTopBarIcon] or a text action.
 */
@Composable
fun PiTopBar(
    title: String,
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            // 不透明：状态栏、顶栏、页面同色，内容不会从下面透出半截。
            .background(MaterialTheme.colorScheme.background),
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
                        .clip(TOP_BAR_ICON_SHAPE)
                        .clickable(onClickLabel = "返回", onClick = onBack),
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
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (statusLabel != null) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                stateToneColor(engineStateTone(statusLabel), PiTheme.palette),
                            )
                            .semantics { contentDescription = "引擎：$statusLabel" },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = PiType.topBarTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TOP_BAR_ACTION_GAP),
                    content = actions,
                )
            }
        }
    }
}

/**
 * One icon action's press box for [PiTopBar]: 48dp 圆形热区 / 24dp 字形，和对话页
 * 顶栏的 [app.pi.ui.screens.ChatTopBarAction] 同一套尺寸。
 */
@Composable
fun PiTopBarIcon(onClick: () -> Unit, contentDescription: String, icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(TOP_BAR_ACTION_BOX)
            .clip(TOP_BAR_ICON_SHAPE)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
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
 * or the engine would read differently on two screens. That rule is now enforced by
 * **one** table rather than by this KDoc plus two hand-kept copies: the glyph is
 * `engineStateGlyph` and the tone is `engineStateTone`, both in `ui/theme/PiStateChip.kt`
 * (the columns above are that table's rows). Before the merge the two copies had already
 * drifted — 启动中, 排队中 and 引擎已停止 read differently here than on the chat page.
 */
@Composable
fun PiTopBarEngineMeta(word: String) {
    val glyph = engineStateGlyph(word)
    val tone = stateToneColor(engineStateTone(word), PiTheme.palette)
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
            // 去描边（与 chip 同一批）：填充底 + 选中段 `selectedBg` 就够分层了，
            // 一圈 `borderMuted` 只会让这一行比内容更响。
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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

    // 目的地之间的跳转 lambda，`remember` 一次定死 identity。
    //
    // 底栏拆掉之后，工作区/设置只剩两条门：会话页顶栏的图标，和对话页「⋯」菜单。
    // 两条门都要能跳，所以这两个 lambda 在这里定义、往里传，而不是在各个屏里现写 ——
    // 这些屏的参数按 `===` 比较（strong skipping），而这个组合每收到一次 `UiState`
    // 发布就重组一次（流式期间约 200ms），字面量 lambda 会让整屏跟着重算。
    val openChat: () -> Unit = remember { { destinationName = PiDestination.Chat.name } }
    // 会话列表是覆盖层，不是目的地：从它的顶栏点工作区/设置时，只换目的地会把新屏
    // 盖在覆盖层下面，用户得先返回一次才看得到（「点设置划两下返回才进去」）。
    // 所以这两个门先关掉覆盖层，再换目的地 —— `closeOverlay` 对没有覆盖层的调用是
    // 幂等的，对话页顶栏那两枚图标也走同一个 lambda。
    val openWorkspace: () -> Unit = remember {
        {
            session.closeOverlay()
            destinationName = PiDestination.Workbench.name
        }
    }
    val openSettings: () -> Unit = remember {
        {
            session.closeOverlay()
            destinationName = PiDestination.Settings.name
        }
    }

    // The one overlay (the session list — with pi's `/tree` as its second view — and
    // the terminal) is **not** a destination: it belongs to whatever session is on
    // screen. It is owned by the ViewModel
    // (`PiSessionViewModel.overlay`), not by a value remembered here: `closeOverlay()`
    // must be final — "点叉就必须退出，不管你打断还是怎么的" — and a request that lands
    // a frame late must not be able to raise it again. 返回键也在这里处理：下面两条
    // handler（覆盖层 / 工作区）加上设置栈自己那条，三条**按条件互斥**，不靠组合顺序抢。


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

    /**
     * 关掉覆盖层 —— **不管打断还是怎么的，关掉之后不许它自己回来。**
     *
     * 两件事一起做，而且都由 ViewModel 拥有（见 [PiSessionViewModel.closeOverlay]）：
     * 「覆盖层开着」这一条状态**不在组合层**，所以晚到的状态写、过期快照、restored
     * state 都碰不到它；同时作废排队中的导航请求（请求的效果体要等下一帧才跑，一条已
     * 经点下去的 `SessionTree` 会在 ✕ 之后才轮到 —— 那正是用户看到的"点出去 1 秒内又
     * 被拉回来"）。之后只有**新的**手势能再把它举起来。
     */
    val closeOverlay: () -> Unit = remember { { session.closeOverlay() } }

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
    // **覆盖层开着与否，读的是 ViewModel 自己那条流，不是 `UiState`、也不是
    // `rememberSaveable`。** 用户的要求是硬的：「点叉就必须退出，不管打断还是怎么的。」
    // 这条流只有 `raiseOverlay`/`closeOverlay` 两个写者，任何晚到的 `_state` 写、任何
    // restored state 都碰不到它 —— 于是"关掉之后又被举起来"在结构上不可能。
    val overlayState by session.overlay.collectAsState()
    val navRequest = uiState.navRequest
    LaunchedEffect(navRequest, uiState.navSeq) {
        // **先领处理权，再执行。** 请求的身份是 uiState.navSeq：关掉覆盖层时
        // discardPendingNav() 已把高水位推过去，排队到现在才跑到的请求在这里被拒。
        if (navRequest == null || !session.claimNav(uiState.navSeq)) return@LaunchedEffect
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
                session.raiseOverlay(OverlaySlot.SessionList, OverlayView.List)
            }
            NavRequest.Chat -> {
                closeOverlay()
                destinationName = PiDestination.Chat.name
            }
            NavRequest.Settings -> {
                closeOverlay()
                destinationName = PiDestination.Settings.name
            }
            is NavRequest.SettingsFocus -> {
                closeOverlay()
                settingsFocus = navRequest.key
                destinationName = PiDestination.Settings.name
            }
            // pi's `/tree`: the *same* overlay as the session list, opened on its
            // second view. There is no separate tree overlay — one slot — so
            // this both raises the layer and picks the view.
            NavRequest.SessionTree -> {
                session.raiseOverlay(OverlaySlot.SessionList, OverlayView.Tree)
                session.refreshTree()
            }
            // The terminal raises the overlay layer for the same reason the list
            // does: it is not a place. This is the *only* way in now — the chat
            // composer's chip and the overflow entry are gone (the user retired
            // the terminal), so the settings home's row is the one door.
            NavRequest.Terminal -> {
                session.raiseOverlay(OverlaySlot.Terminal, OverlayView.List)
            }
            null -> Unit
        }
    }

    // 底栏已经拆掉，所以这里不再有「开机页也留着底栏」那条取舍。覆盖层（会话列表 /
    // 会话树 / 终端）仍然画在 Scaffold 的内容盒里、不替换目的地，因此 `padding` 的底边
    // 就是系统导航条 inset；首装解包那几分钟由 `ChatScreen` 自己决定画 `BootScreen`，
    // 与这里的目的地切换无关。

    // 对话页的 IME 由输入框自己接管：输入框悬浮在键盘上方，列表要铺到键盘后面，
    // 所以 Scaffold 不能再整体缩一次（缩了列表就跟着短，键盘前面就没有"满屏"了）。
    // 其它目的地和覆盖层（会话列表 / 会话树 / 终端）仍按原来的方式在 Scaffold 上抬升，
    // 它们没有悬浮输入框，也不该为了对话页的布局改自己的 inset。
    val chatOwnsIme = current == PiDestination.Chat && overlayState == null
    Scaffold(
        // `imePadding()` is what makes the soft keyboard *displace* the UI instead of
        // covering it. The activity is `enableEdgeToEdge()` (`MainActivity.kt:19`) and
        // the manifest asks for `adjustResize` (`AndroidManifest.xml:118`), but with
        // edge-to-edge the framework stops resizing the window for the IME and reports
        // it as an inset instead — so `adjustResize` alone lifts nothing, and the
        // composer, its toolbar and the bottom bar all ended up behind the keyboard
        // (seen on device, docs/known-gaps.md §M2).
        //
        // 对话页不再走这里：`ChatScreen` 的 dock 自己有 `windowInsetsPadding(ime ∪
        // navigationBars)`，Scaffold 再 pad 一次会把整块内容抬两次。其余目的地保持
        // 原行为（终端也仍然靠这一层）。
        modifier = if (chatOwnsIme) Modifier else Modifier.imePadding(),
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
                            session.raiseOverlay(OverlaySlot.SessionList, OverlayView.List)
                        },
                        // 底栏拆掉之后，工作区/设置只在会话页顶栏；从对话页过去要先开会话
                        // 覆盖层再点，多一层。这两条把入口直接放进对话页的「⋯」菜单。
                        onOpenWorkspace = openWorkspace,
                        onOpenSettings = openSettings,
                    )
                }

                // The project's own screen. It reads pi's session directory, the
                // transcript and the workspace's `.pi`, so it takes the session —
                // the terminal it replaced took nothing.
                PiDestination.Workbench -> saveableStateHolder.SaveableStateProvider(current.name) {
                    ProjectScreen(
                        contentPadding = padding,
                        session = session,
                        onBack = openChat,
                    )
                }

                PiDestination.Settings -> saveableStateHolder.SaveableStateProvider(current.name) {
                    PiSettingsStack(
                        contentPadding = padding,
                        onBack = openChat,
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
                    // All of them are gone: a settings row whose only content is "go
                    // somewhere else" is not a setting, and the OAuth one is now a real
                    // feature (「订阅账号」 card, `ui/settings/SubscriptionLogin.kt`).
                    // The registry rows those signposts answered for were deleted with
                    // them — G_MODEL has no group page, so nothing rendered them.
                    onRunAction = { setting ->
                        when (setting.key) {
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
            val shown = overlayState?.slot
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
                        OverlaySlot.SessionList -> {
                            // **两个回调的 identity 固定下来。** 它们只调 `session.closeOverlay()` /
                            // 写 `destinationName` 这个 `MutableState`（在本组合的整个生命周期里是
                            // 同一个对象），所以 `remember` 之后**行为逐字不变** —— 而这一屏的
                            // 父级每收到一次 `UiState` 发布（流式期间约 200 ms 一次）就重组，从前的
                            // 字面量 lambda 每次都换新实例，`SessionsScreen` 的参数按 `===` 比较
                            // 因此不等，整屏（与列表里的行）都得跟着重算。同一条机制与证据写在
                            // `ChatScreen` 的 `onForkFromMessage` 上（`docs/scroll-perf-list.md` §2.1）。
                            val onOpenChat: () -> Unit = remember {
                                {
                                    closeOverlay()
                                    destinationName = PiDestination.Chat.name
                                }
                            }
                            // 关掉这一层就走 [closeOverlay]：它同时把「打开哪一屏」复位成列表，
                            // 所以下一次进来不会又被拉回会话树（见那个 lambda 的 KDoc）。
                            val onCloseSessionList: () -> Unit = remember { closeOverlay }
                            SessionsScreen(
                                contentPadding = padding,
                                session = session,
                                // A pick switches the session and lands on it. The
                                // overlay closes because otherwise the user stays on
                                // the list they just answered.
                                onOpenChat = onOpenChat,
                                onBack = onCloseSessionList,
                                onOpenWorkspace = openWorkspace,
                                onOpenSettings = openSettings,
                                // Which view to open on: `/tree` and the branch-summary
                                // row ask for the tree, everything else for the list.
                                initialView = if (overlayState?.view == OverlayView.Tree) {
                                    SessionsView.Tree
                                } else {
                                    SessionsView.List
                                },
                            )
                        }

                        // The full-screen terminal, unchanged from when it was a
                        // destination: this batch moves the *door*, not the room
                        // (`05-compose-migration-plan.md` §3.8).
                        OverlaySlot.Terminal -> TerminalScreen(
                            contentPadding = padding,
                            onBack = closeOverlay,
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
            // 目的地级返回：没有覆盖层时，工作区退回对话（工作区没有内部层级）。
            //
            // **优先级靠条件互斥，不靠组合顺序。** 这一条要求 `overlayState == null`，
            // 而下面那条覆盖层 handler 要求 `!= null`，两者永远不会同时启用；设置页
            // 内部那一栈由 `PiSettingsStack` 自己的 handler 管（内层 → 上一级 → 首页 →
            // 对话），它在 `when (current)` 分支里组合得更早，覆盖层开着时也会被下面
            // 这条先接到（组合顺序在后 → `OnBackPressedDispatcher` 的 LIFO）。
            BackHandler(enabled = overlayState == null && current == PiDestination.Workbench) {
                openChat()
            }

            BackHandler(enabled = overlayState != null) { closeOverlay() }

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
            // 底距必须活过 `calculateBottomPadding()` 返回 0 的情况：正常时它等于
            // 系统导航条 inset（底栏拆掉之后不再有 56dp），再加 8dp 的呼吸；
            // `maxOf` 兜住「再也报告不出来」那一下，否则 snackbar 落到手势条下面。
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
