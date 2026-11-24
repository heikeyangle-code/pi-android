package app.pi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import app.pi.R
import app.pi.i18n.piStr
import app.pi.i18n.tr
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.extension.ExtensionUiHost
import app.pi.ui.screens.ChatScreen
import app.pi.ui.screens.ProjectScreen
import app.pi.ui.screens.SessionsScreen
import app.pi.ui.screens.SessionsView
import app.pi.ui.screens.TerminalScreen
import app.pi.ui.settings.PiSettingsStack
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
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
enum class PiDestination {
    Chat,

    /**
     * The project on screen: what this session is doing in its working directory —
     * the directory itself, the files it has touched, the resources the directory
     * carries, and any command still running. It replaced a full-screen terminal,
     * which the user retired from the bar
     * (`06-v2-construction-reference.md` §5) and which now has one plain row on the
     * settings home.
     */
    Workbench,

    Settings;

    val label: String
        get() = when (this) {
            Chat -> tr("对话", "Chat")
            Workbench -> tr("项目", "Project")
            Settings -> tr("设置", "Settings")
        }
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

/** 所有屏共用的统一顶栏规格：56dp 高，44dp 圆形热区 / 22dp 字形，与 ChatTopBar 完全一致。 */
private val TOP_BAR_HEIGHT = 56.dp
private val TOP_BAR_INSET_START_PLAIN = 14.dp
private val TOP_BAR_INSET_START_NAV = 6.dp
private val TOP_BAR_INSET_END = 8.dp
private val TOP_BAR_GAP = 6.dp
private val TOP_BAR_ACTION_GAP = 2.dp
private val TOP_BAR_ICON_BOX = 44.dp
private val TOP_BAR_ICON_GLYPH = 22.dp
private val TOP_BAR_ICON_SHAPE = CircleShape

/**
 * 共享引擎呼吸状态点（对话顶栏等处共用）。
 * 仅在 Warning（工作中 / 启动中）时启动动画，且只在 graphicsLayer 绘制阶段读取，零重组开销。
 */
@Composable
fun PiEngineStatusDot(statusLabel: String, modifier: Modifier = Modifier) {
    val palette = PiTheme.palette
    val tone = engineStateTone(statusLabel)
    val dotColor = stateToneColor(tone, palette)
    val isActiveWork = tone == StateTone.Warning
    val dotAlpha: androidx.compose.runtime.State<Float>? = if (isActiveWork) {
        rememberInfiniteTransition(label = "engine-dot").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
            label = "engine-dot-alpha",
        )
    } else {
        null
    }
    val engineDesc = tr("引擎：$statusLabel", "Engine: $statusLabel")
    Box(
        modifier = modifier
            .size(PiSpacing.chatTopBarStatusDot)
            .graphicsLayer { alpha = dotAlpha?.value ?: 1f }
            .clip(CircleShape)
            .background(dotColor)
            .semantics { contentDescription = engineDesc },
    )
}

/**
 * 全 App 统一顶栏：56dp 高、页面同色背景、44dp/22dp 圆形图标按钮、可选副标题 / 自定义标题槽、
 * 可选滚动底部分隔发丝线（与 [app.pi.ui.screens.ChatTopBar] 几何完全一致）。
 */
@Composable
fun PiTopBar(
    title: String = "",
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    navigationIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    navigationContentDescription: String? = null,
    scrolled: Boolean = false,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val hairlineColor = palette.borderMuted.copy(alpha = 0.55f)
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                if (scrolled) {
                    val strokePx = PiSpacing.hairlineThin.toPx()
                    drawLine(
                        color = hairlineColor,
                        start = Offset(0f, size.height - strokePx / 2f),
                        end = Offset(size.width, size.height - strokePx / 2f),
                        strokeWidth = strokePx,
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_BAR_HEIGHT)
                .padding(
                    start = if (onBack != null) TOP_BAR_INSET_START_NAV else TOP_BAR_INSET_START_PLAIN,
                    end = if (actions != null) TOP_BAR_INSET_END else TOP_BAR_INSET_START_PLAIN,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TOP_BAR_GAP),
        ) {
            if (onBack != null) {
                val backLabel = navigationContentDescription ?: piStr(R.string.nav_back)
                PiTopBarIcon(
                    onClick = onBack,
                    contentDescription = backLabel,
                    icon = navigationIcon,
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (titleContent != null) {
                    titleContent()
                } else {
                    if (statusLabel != null) {
                        PiEngineStatusDot(statusLabel = statusLabel)
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            title,
                            style = PiType.topBarTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = PiTheme.text.meta,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
    }
}

/**
 * 统一顶栏图标按钮：44dp 圆形热区 / 22dp 字形，支持高亮态（搜索打开等场景）。
 */
@Composable
fun PiTopBarIcon(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    highlighted: Boolean = false,
) {
    val accent = PiTheme.palette.accent
    val bg by animateColorAsState(
        targetValue = if (highlighted) accent.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(150),
        label = "topbar-icon-bg",
    )
    Box(
        modifier = Modifier
            .size(TOP_BAR_ICON_BOX)
            .clip(TOP_BAR_ICON_SHAPE)
            .background(bg)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(TOP_BAR_ICON_GLYPH),
            tint = if (highlighted) accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 统一分段切换控件：支持平滑色变与真正的禁用态（`disabledIndices`，彻底消除点禁用页无反应的困惑）。
 */
@Composable
fun PiSeg(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    disabledIndices: Set<Int> = emptySet(),
) {
    val haptic = LocalHapticFeedback.current
    val isDark = PiTheme.isDark
    val segSpring = spring<Color>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val trackBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val activeBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.White
    }
    Row(
        modifier = modifier
            .clip(SEG_SHAPE)
            .background(trackBg)
            .border(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), SEG_SHAPE)
            .padding(SEG_PADDING),
        horizontalArrangement = Arrangement.spacedBy(SEG_ITEM_GAP),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val enabled = index !in disabledIndices
            val bg by animateColorAsState(
                targetValue = if (selected) activeBg else Color.Transparent,
                animationSpec = segSpring,
                label = "seg-bg-$index",
            )
            val fg by animateColorAsState(
                targetValue = when {
                    !enabled -> PiTheme.palette.dim
                    selected -> MaterialTheme.colorScheme.onSurface
                    else -> PiTheme.palette.muted
                },
                animationSpec = segSpring,
                label = "seg-fg-$index",
            )
            Box(
                modifier = Modifier
                    .height(SEG_ITEM_HEIGHT)
                    .alpha(if (enabled) 1f else 0.48f)
                    .clip(SEG_ITEM_SHAPE)
                    .background(bg)
                    .then(
                        if (selected && !isDark) {
                            Modifier.border(
                                PiSpacing.hairlineThin,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                SEG_ITEM_SHAPE,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable(enabled = enabled) {
                        if (!selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(horizontal = SEG_ITEM_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}

private val SEG_SHAPE = RoundedCornerShape(10.dp)
private val SEG_ITEM_SHAPE = RoundedCornerShape(8.dp)
private val SEG_PADDING = 3.dp
private val SEG_ITEM_GAP = 3.dp
private val SEG_ITEM_HEIGHT = 30.dp
private val SEG_ITEM_PADDING = 12.dp

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

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    var suppressStaleImePadding by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx == 0 && suppressStaleImePadding) {
            suppressStaleImePadding = false
        }
    }
    LaunchedEffect(suppressStaleImePadding) {
        if (suppressStaleImePadding) {
            delay(450L)
            suppressStaleImePadding = false
        }
    }
    val dismissIme: () -> Unit = {
        if (imeBottomPx > 0) suppressStaleImePadding = true
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        rootView.windowToken?.let { token ->
            val imm = rootView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(token, 0)
        }
    }
    val latestDismissIme by rememberUpdatedState(dismissIme)

    // 目的地之间的跳转 lambda，`remember` 一次定死 identity。
    //
    // 底栏拆掉之后，工作区/设置只剩两条门：会话页顶栏的图标，和对话页「⋯」菜单。
    // 两条门都要能跳，所以这两个 lambda 在这里定义、往里传，而不是在各个屏里现写 ——
    // 这些屏的参数按 `===` 比较（strong skipping），而这个组合每收到一次 `UiState`
    // 发布就重组一次（流式期间约 200ms），字面量 lambda 会让整屏跟着重算。
    val openChat: () -> Unit = remember {
        {
            latestDismissIme()
            destinationName = PiDestination.Chat.name
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
    val closeOverlay: () -> Unit = remember {
        {
            latestDismissIme()
            session.closeOverlay()
        }
    }

    // 会话列表是覆盖层，不是目的地：从它的顶栏点工作区/设置时，只换目的地会把新屏
    // 盖在覆盖层下面，用户得先返回一次才看得到（「点设置划两下返回才进去」）。
    // 所以这两个门先关覆盖层，再换目的地 —— `closeOverlay` 对没有覆盖层的调用是
    // 幂等的，对话页顶栏那两枚图标也走同一个 lambda。
    val openWorkspace: () -> Unit = remember {
        {
            latestDismissIme()
            closeOverlay()
            destinationName = PiDestination.Workbench.name
        }
    }
    val openSettings: () -> Unit = remember {
        {
            latestDismissIme()
            closeOverlay()
            destinationName = PiDestination.Settings.name
        }
    }

    // 会话列表选中一行 / 新建会话之后走这一支：`SessionsScreen.onOpenChat` 的约定就是
    // 「关掉覆盖层，并落在对话页」（`SessionsScreen.kt:136`），而列表本身是覆盖层，所以
    // 只换目的地会把对话页盖在它下面 —— 与上面两个门同一套动作，`closeOverlay` 幂等。
    // 必须 `remember`：父级每收到一次 `UiState` 发布就重组（流式期间约 200 ms 一次），
    // 字面量 lambda 每次都换新实例，`SessionsScreen` 的参数按 `===` 比较，整屏（含列表行）
    // 都得跟着重算。
    val openChatFromOverlay: () -> Unit = remember {
        {
            latestDismissIme()
            closeOverlay()
            destinationName = PiDestination.Chat.name
        }
    }

    // The theme picker lists what pi would load, not only what the `themes`
    // setting names, and the active theme's caveats are shown with it. Both come
    // from the ViewModel, which is the one place that reads pi's theme files.
    val theme by session.theme.collectAsStateWithLifecycle()
    val themeEntries by session.themeEntries.collectAsStateWithLifecycle()

    // 「运行时加速（实验性）」开关当场生效的进度。拨完开关探针要跑 10~60 秒，设置页那一行
    // 必须在那段时间里说它在跑 —— 这是这个状态唯一的用途（见 `RuntimeSwitchAction`）。
    val runtimeSwitch by session.runtimeSwitch.collectAsStateWithLifecycle()

    // Commands that need a destination are requested by the ViewModel through
    // state, because they finish inside a coroutine after an RPC answer — by then
    // there is no composable left to call back into. Consuming the request here
    // keeps the ViewModel free of Compose navigation knowledge.
    //
    // Consuming it *inside* the effect is what lets the effect be keyed on the
    // request: the handler writes the request back to `null`, so the effect runs
    // once for the request and once for the `null`, and the second run is how the
    // same request can be made again (two `/tree` commands in a row).
    // 按需切片订阅 session.state，彻底避免流式输出每 token / 1Hz 工具计时引发整个 PiRoot 重组。
    val navEvent by remember(session) {
        session.state.map { it.navRequest to it.navSeq }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = session.state.value.navRequest to session.state.value.navSeq)
    val availableModels by remember(session) {
        session.state.map { it.models }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = session.state.value.models)
    val overlayState by session.overlay.collectAsStateWithLifecycle()
    var pendingTerminalCwd by remember { mutableStateOf<String?>(null) }
    var navImeInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(current, overlayState?.slot) {
        if (navImeInitialized) {
            latestDismissIme()
        } else {
            navImeInitialized = true
        }
    }
    val (navRequest, navSeq) = navEvent
    LaunchedEffect(navRequest, navSeq) {
        // **先领处理权，再执行。** 请求的身份是 navSeq：关掉覆盖层时
        // discardPendingNav() 已把高水位推过去，排队到现在才跑到的请求在这里被拒。
        if (navRequest == null || !session.claimNav(navSeq)) return@LaunchedEffect
        latestDismissIme()
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
                pendingTerminalCwd = null
                session.raiseOverlay(OverlaySlot.Terminal, OverlayView.List)
            }
            is NavRequest.TerminalAt -> {
                pendingTerminalCwd = navRequest.guestCwd
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
    // 对话页与全屏终端页各自接管 IME ∪ navigationBars，避免 Scaffold 的 imePadding() 与
    // contentPadding.calculateBottomPadding() 叠加在软键盘上方留出多余空白带。
    // 切屏瞬间若上一页软键盘尚在收起动画中（suppressStaleImePadding），暂缓挂载 Scaffold.imePadding()，
    // 彻底避免目标页（如设置页）先被退场中的输入法顶起、再猛地落回原位。
    val ownIme = (current == PiDestination.Chat && overlayState == null) ||
        overlayState?.slot == OverlaySlot.Terminal
    Scaffold(
        modifier = if (ownIme || suppressStaleImePadding) Modifier else Modifier.imePadding(),
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val slideSpec = spring<IntOffset>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    val fadeSpec = spring<Float>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                    if (forward) {
                        (slideInHorizontally(slideSpec) { it / 6 } + fadeIn(fadeSpec))
                            .togetherWith(slideOutHorizontally(slideSpec) { -it / 12 } + fadeOut(fadeSpec))
                    } else {
                        (slideInHorizontally(slideSpec) { -it / 12 } + fadeIn(fadeSpec))
                            .togetherWith(slideOutHorizontally(slideSpec) { it / 5 } + fadeOut(fadeSpec))
                    }
                },
                label = "root-destination",
            ) { dest ->
                // 每个目的地自绘不透明底色，避免水平滑动过渡期间前后两屏互相透底发闪。
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                when (dest) {
                    PiDestination.Chat -> saveableStateHolder.SaveableStateProvider(dest.name) {
                        ChatScreen(
                            contentPadding = padding,
                            session = session,
                            onOpenSessions = {
                                latestDismissIme()
                                session.raiseOverlay(OverlaySlot.SessionList, OverlayView.List)
                            },
                            onOpenWorkspace = openWorkspace,
                            onOpenSettings = openSettings,
                        )
                    }

                    PiDestination.Workbench -> saveableStateHolder.SaveableStateProvider(dest.name) {
                        ProjectScreen(
                            contentPadding = padding,
                            session = session,
                            onBack = openChat,
                        )
                    }

                    PiDestination.Settings -> saveableStateHolder.SaveableStateProvider(dest.name) {
                        PiSettingsStack(
                            contentPadding = padding,
                            onBack = openChat,
                            extensionsAccess = session::extensionsAccess,
                            store = remember(session) { session.settingsStoreForSettingsUi() },
                            knownThemes = themeEntries,
                            themeNotes = theme.notes,
                            themeError = theme.error,
                            onSettingWritten = { key -> session.onSettingWritten(key) },
                            onRunAction = { setting ->
                                when (setting.key) {
                                    "app.compaction.runNow" -> session.compact()
                                    else -> session.notifyUser(
                                        tr(
                                            "「${setting.title}」当前不可用，请把这一步报告给我们。",
                                            "\"${setting.title}\" is currently unavailable. Please report this step.",
                                        ),
                                        warning = true,
                                    )
                                }
                            },
                            restartEngine = { reason, allowInterrupt ->
                                session.restartEngine(reason, allowInterrupt)
                            },
                            isTurnRunning = { session.isTurnRunning() },
                            engineDiagnostics = { session.engineDiagnostics() },
                            engineEntry = { session.engineEntryLabel() },
                            recentFailures = { session.recentFailures() },
                            availableModels = availableModels,
                            onLoadAvailableModels = { session.refreshModels() },
                            advanced = { session.advanced() },
                            modelsHost = { session.modelsHost() },
                            focusKey = settingsFocus,
                            onFocusConsumed = { settingsFocus = null },
                            runtimeSwitch = runtimeSwitch,
                            activeRuntimeEngine = { session.activeRuntimeEngine() },
                            liveEngineState = { session.liveEngineState() },
                            onSelectRuntimeEngine = { engine -> session.selectRuntimeEngine(engine) },
                            onStartEngine = { session.boot() },
                            onRepairRuntime = { session.repairRuntime() },
                            onRebuildRuntime = { session.boot(rebuild = true) },
                            onExternalSettingsWrite = { session.invalidateSettingsCache() },
                            onOpenTerminal = { session.requestNav(NavRequest.Terminal) },
                        )
                    }
                }
                }
            }

            // 覆盖层平滑过渡：保留离场帧的 lastOverlay 播放退场动效，同时 closeOverlay() 保持立即终结。
            var lastOverlay by remember { mutableStateOf(overlayState) }
            if (overlayState != null) lastOverlay = overlayState
            val renderOverlay = overlayState ?: lastOverlay
            val isSessionDrawer = renderOverlay?.slot == OverlaySlot.SessionList
            val ovSlideSpec = spring<IntOffset>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
            val ovFadeSpec = spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            )
            AnimatedVisibility(
                visible = overlayState != null,
                enter = if (isSessionDrawer) {
                    slideInHorizontally(ovSlideSpec) { -it / 4 } + fadeIn(ovFadeSpec)
                } else {
                    slideInVertically(ovSlideSpec) { it / 6 } + fadeIn(ovFadeSpec)
                },
                exit = if (isSessionDrawer) {
                    slideOutHorizontally(ovSlideSpec) { -it / 4 } + fadeOut(ovFadeSpec)
                } else {
                    slideOutVertically(ovSlideSpec) { it / 6 } + fadeOut(ovFadeSpec)
                },
                label = "root-overlay",
            ) {
                val active = renderOverlay ?: return@AnimatedVisibility
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (active.slot) {
                        OverlaySlot.SessionList -> {
                            val onCloseSessionList: () -> Unit = remember { closeOverlay }
                            SessionsScreen(
                                contentPadding = padding,
                                session = session,
                                onOpenChat = openChatFromOverlay,
                                onBack = onCloseSessionList,
                                initialView = if (active.view == OverlayView.Tree) {
                                    SessionsView.Tree
                                } else {
                                    SessionsView.List
                                },
                            )
                        }

                        OverlaySlot.Terminal -> TerminalScreen(
                            contentPadding = padding,
                            onBack = closeOverlay,
                            initialGuestCwd = pendingTerminalCwd,
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
            // 工作区与设置页都自带内部返回栈：
            // - `ProjectScreen`：关闭筛选 → 回到上一层目录 → 已在项目根时退回对话；
            // - `PiSettingsStack`：内层 → 上一级 → 首页 → 对话；
            // 两者都在 `when (dest)` 分支里组合得更早，覆盖层开着时下面这条 handler
            // 会因组合顺序在后（`OnBackPressedDispatcher` LIFO）优先接管返回键。
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
            app.pi.ui.resources.ProjectTrustHost(session)
            CwdMissingHost(session)
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
 * 全局「原工作目录不可用」弹窗宿主：
 * 同时服务会话列表切换与对话页 `/import`，不再依赖某单一覆盖层处于挂载状态。
 */
@Composable
private fun CwdMissingHost(session: PiSessionViewModel) {
    val missingPrompt by session.cwdMissing.collectAsStateWithLifecycle()
    if (missingPrompt != null) {
        val prompt = missingPrompt!!
        PiDialog(onDismissRequest = { session.dismissCwdMissing() }) {
            PiDialogTitle(
                title = tr("原工作目录不可用", "Original Directory Unavailable"),
                glyph = "!",
                glyphTone = PiTheme.palette.warning,
            )
            PiDialogBody(
                buildString {
                    append(prompt.reason)
                    prompt.issue?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
                    append(tr("\n\n可在当前项目继续打开，或分支一份新会话到当前项目。", "\n\nYou can resume it in the current project, or fork a new session here."))
                },
            )
            PiDialogActions {
                PiDialogAction(label = tr("取消", "Cancel"), primary = false, onClick = { session.dismissCwdMissing() })
                PiDialogAction(label = tr("继续打开", "Resume"), primary = false, onClick = { session.resolveCwdMissingContinue() })
                PiDialogAction(label = tr("分支到此", "Fork here"), primary = true, onClick = { session.resolveCwdMissingFork() })
            }
        }
    }
}
