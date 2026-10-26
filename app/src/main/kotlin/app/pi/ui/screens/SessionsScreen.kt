// `fontFeatureSettings = "tnum"`：等宽数字让「11分钟前」那一列不抖。这个属性在
// Compose 的文本 API 里仍带实验标记，`PiTheme.kt` 也是同一个理由在文件级 opt-in。
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.pi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.WorkspaceChoice
import app.pi.runtime.WorkspaceStore
import app.pi.session.PiSessionStore
import app.pi.ui.PiSeg
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.chat.SessionFileRow
import app.pi.ui.chat.SessionTreeScreen
import app.pi.ui.chat.forkParentNameOf
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiEmptyStateTopAnchored
import app.pi.ui.components.PiMenu
import app.pi.ui.components.PiMenuItem
import app.pi.ui.components.PiMenuPlacement
import app.pi.ui.components.PiType
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSheet
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.util.concurrent.TimeUnit

/** 覆盖层里的两个视图，同一条分段控件切换（`03-navigation-decision.md`）。 */
enum class SessionsView(val label: String) {
    List("会话"),
    Tree("会话树"),
}

/**
 * 会话列表 —— 对话页左上角进入的**覆盖层**，不是目的地。
 *
 * ## 它为什么是覆盖层
 *
 * `03-navigation-decision.md` 的裁决：当时底栏是三个「地方」，而会话列表是一个**选择器**
 * （pi 自己就是把会话树覆盖在转录上）。这一屏由 `PiRoot` 的覆盖层状态承载，所以它
 * 自己**不注册** `BackHandler`：覆盖层、工作区、设置内部栈三条都在别处注册，且按条件
 * 互斥（那边的 KDoc 记着这条教训）。
 *
 * ## 列表 ↔ 会话树在同一层
 *
 * v2 的分段控件（`design-demos/direction-b-v2.html:1854`）把两个视图放在同一条顶栏下，
 * 所以切换视图不改变「我在哪」；关掉这一层就回到原来的目的地。树是**嵌进来**的
 * （`SessionTreeScreen(embedded = true)`），它自己的顶栏与不透明底由这里提供——两个
 * 覆盖层叠在一起时只有最上面那个能被返回键关掉。
 *
 * ## 一行的两行形态
 *
 * 三段真实字段（`02-real-content.md` §1.2 的公式）折成两行：
 *
 * ```
 * 第 1 行   会话名（可省略）…                      相对时间
 * 第 2 行   模型 · N条（可省略）…                  [已命名] [来自 X]
 * ```
 *
 * 第二行右端是只在存在时出现的两个小标签；父会话的名字从前是**第三行**，现在收进
 * 标签里，行高因此固定成两行。事实没有变：`parentSession` 是 pi 记在**文件头**里的
 * （`docs/session-format.md:72-76`），名字取不到时不猜 —— 父被删掉或落在 `list(limit)`
 * 之外时写「分支」，不拿文件名冒充会话名。
 *
 * **为什么是「来自」而不是「分叉自」**：`parentSession` 是 `/fork`、`/clone`、
 * `newSession({ parentSession })` **三种**来源共用的同一个头字段
 * （`docs/session-format.md:72`），文件里没有第二个标记能区分它们——`/clone` 在上游就是
 * `fork(leafId, { position: "at" })`（`rpc-mode.ts:619-628`），走的是同一个
 * `SessionManager.createBranchedSession`。所以这一行只许说**这条从哪儿来**，不许说**它是被
 * 分叉还是被复制**：那两个字文件里没有。
 *
 * **而且第三种来源在本应用里是一条真按钮**：会话行的长按菜单有「新建子会话」
 * （`PiSessionViewModel.newChildSession` → `newSession(parentSession)`），它写同一个字段，
 * 既不是分叉也不是复制。所以「分叉自」不只是不精确，它会把用户自己按「新建子会话」建出来的
 * 那条会话说成一条分叉 —— 界面上说文件里没有的话，正是用户那句「一点也没有安全感」的病根。
 *
 * ## 点一行做什么
 *
 * `switch_session`：pi 采纳那个文件，`session_before_switch` 扩展有机会否决，转录也只有
 * 这一条路能重建（audit §6.9）。所以是「切换会话」，不是「打开一个页面」。
 *
 * 重命名、导出、复制**故意**不放在行上：pi 的 `set_session_name` / `export_html` /
 * `clone` 都不接会话参数，只作用于当前会话（`rpc-mode.ts:600-631`、`:661-668`），放在一条
 * 非当前行上会去动另一个会话。它们在对话页的溢出菜单里，那里的目标不含糊。
 *
 * ## 底部的「＋ 新建会话」是浮动的（D58）
 *
 * 它 `align(BottomEnd)` 压在列表内容之上，**不占列表的布局高度** —— 所以列表视口一直铺到
 * 系统导航条的上沿，按钮那一层下面不会露出页面底色。v2 把这一件画成列表**下面**属于自己的
 * 一行（`flex:'none'`，`padding:'0 16px 14px'`），本应用曾经照做，代价就是按钮高度那一条
 * 全是裸页面底色（深色主题下是一条近黑带）；用户裁决回到浮动。
 *
 * 当年那个真 bug（最后几行被按钮盖住、点不到）由**列表自己的** `contentPadding` 解决：
 * 它的底是 [SESSIONS_LIST_BOTTOM_RESERVE]，最后一条会话永远能滚到按钮上方。位置与可达性
 * 分开给，两条同时成立 —— 见那段 KDoc 里两个加数各自管什么。
 *
 * @param onOpenChat 选中一行之后：关掉覆盖层，并落在对话页。
 * @param onBack 顶栏左侧的返回箭头。系统返回键走 `PiRoot` 那一处 `BackHandler`，同一个动作。
 *   返回键从右边挪到左边是用户裁决：右侧要腾给搜索/工作区/设置/「⋯」四个图标，
 *   而且它和各屏顶栏的返回箭头统一成一个位置。
 * @param initialView 打开时停在哪个视图。`/tree` 与「分支摘要」行要求直接落在树上——
 *   一个写着「树」的命令不该让用户再点一次分段控件。宿主把这个偏好提到覆盖层之外，
 *   因为它在这一屏存在之前就已经被设定了。
 */
@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
    onOpenChat: () -> Unit,
    onBack: () -> Unit,
    /**
     * 工作区 / 设置。底栏拆掉之后这两屏只剩两条门：这一屏顶栏的图标，和对话页的「⋯」
     * 菜单。目的地归 `PiRoot` 管，这一屏只负责把点击交出去。
     */
    onOpenWorkspace: () -> Unit,
    onOpenSettings: () -> Unit,
    initialView: SessionsView = SessionsView.List,
) {
    val sessions by session.sessions.collectAsState()
    // True only while the first scan is still running with nothing to show; see
    // `PiSessionViewModel.sessionsLoading`.
    val loading by session.sessionsLoading.collectAsState()
    // 第三种状态：这一次读取失败了（见 `PiSessionViewModel.sessionsFailed`）。它**不是**
    // 「还没有会话」——那句断言在旧代码里来自一个被吞掉的异常。
    val sessionsFailed by session.sessionsFailed.collectAsState()
    val state by session.state.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var byName by rememberSaveable { mutableStateOf(false) }
    var namedOnly by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<PiSessionStore.Summary?>(null) }
    // Long-press opens pi's two per-session actions. Both exist in pi's session
    // selector; ours live here because a phone has no Ctrl+D and no `new_session`
    // argument prompt (`docs/sessions.md:48`, `rpc-types.ts:27`).
    var actions by remember { mutableStateOf<PiSessionStore.Summary?>(null) }
    var viewIndex by rememberSaveable { mutableIntStateOf(initialView.ordinal) }
    // 顶栏「⋯」。它是屏内状态，不是覆盖层：菜单开着时按返回应该先关菜单（`Popup`
    // 自己吃掉那一下），而不是把整层会话列表关掉。
    var menuOpen by remember { mutableStateOf(false) }
    // 搜索框默认收起。收起时清掉查询 —— 否则列表会被一个看不见的过滤条件筛着。
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val view = SessionsView.entries.getOrNull(viewIndex) ?: SessionsView.List
    // The host can ask for a different view *after* this screen is already composed
    // (a second `/tree` while the overlay is open): the overlay stays mounted across
    // requests, so `rememberSaveable`'s initial value would never be consulted again.
    LaunchedEffect(initialView) { viewIndex = initialView.ordinal }

    // `/import` from the sessions screen: the same action as the palette row, and the
    // same launcher. pi's TUI asks for a path in its editor (`interactive-mode.ts:6107-6119`);
    // a phone picks a document, and the ViewModel copies it into the session
    // directory and switches to it (`PiSessionViewModel.importSession`).
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) session.importSession(uri)
    }

    // The directory only becomes meaningful once the runtime is unpacked, so
    // refresh when the screen appears rather than at construction.
    LaunchedEffect(Unit) { session.refreshSessions() }

    // `sessionFile` is the only wire field that says which session pi is on, and
    // it is a guest path — the file name is the part that matches the on-disk
    // index.
    val activeFile = state.meta.sessionFile?.substringAfterLast('/')

    // 每一行「来自哪个会话」—— 有父会话的行，pi 在它的**文件头**里记着原会话的路径
    // （`parentSession`，`docs/session-format.md:72-76`），这里把它翻成会话列表里的那个显示名。
    //
    // 按 `sessions`（**全部**行，不是筛过之后的那批）算一次：关系是文件里的事实，不该随搜索框
    // 里打了什么字而消失，而每行自己线性扫一遍就是 O(n²) 的每帧开销。[forkParentNameOf]
    // 就是这条判定，纯函数，只认 pi 记在文件头里的那个字符串。
    val forkParentNames = remember(sessions) {
        // 判定要的只有两个字段，所以这里先投影一次：`SessionForkLabel.kt` 是纯文件
        // （一个 import 都没有），不认识 `PiSessionStore.Summary`，也正因如此它能在没有
        // Android 的机器上被 harness 真跑一遍。
        val rows = sessions.map { SessionFileRow(it.file.name, it.displayName) }
        sessions.associate { it.file.absolutePath to forkParentNameOf(it.parentSession, rows) }
    }

    // 工作区组标题要用的名字：目录名 → label，取自 `WorkspaceStore`（和工作区页的切换面板同一
    // 份读数）。一次设置文件读取 + 每个 label 一次目录判断，所以按组合算一次、不在每一行每一帧
    // 里算。键是 `sessions`：每次列表刷新就会重读（改名之后回到这一屏必然拿到新名字 —— 这一屏
    // 是覆盖层，关掉就整个卸载，改名只能在它关着的时候发生）。
    val context = LocalContext.current
    val workspaceLabels = remember(sessions) { WorkspaceStore.labels(context) }
    // 本应用所有工作区的 guest 根（`/workspace/pi/workspaces`）。由 `GuestWorkspacePath` 的两个
    // 常量拼出来，而不是猜一个字符串 —— 同一个对象也给出单个工作区的拼法。
    val workspacesRoot = GuestWorkspacePath.GUEST_ROOT + "/" + GuestWorkspacePath.ROOT_RELATIVE

    // 这一屏**派生**出来的两样东西（筛选/排序后的行，以及按 cwd 分好的组）在同一次
    // `remember` 里算，键就是它们真正依赖的那六个输入。
    //
    // 为什么必须包起来：分组那一段原来写在组合体里（旧的 `val groups = visible.groupBy…`），
    // 而 `state` 是在本函数顶部读的（`:153`）—— 引擎每发布一次（流式期间约 200 ms 一次）都会
    // 重组这一屏，于是每次重组都要重跑一遍 `groupBy` + `maxOf` + 排序，虽然它只取决于那六个
    // 输入。放在同一次 `remember` 里的第二个理由：`remember` 的键是**逐次比较**的，两组分开写
    // 就要对同一个 `sessions` 列表做两次 O(n) 深比较。
    //
    // 值逐字不变：同一组谓词、同一个遍历顺序、同一条排序规则；`sessionQueryTokens` 只是把
    // 「与行无关」的那一次 tokenisation 提出循环（见 `sessionMatches` 的 KDoc）。
    //
    // 这两样的**单位是 `PiSessionStore.list()` 交出来的行**，而一行就是一个 `.jsonl` ——
    // 与上游 pi 一样「一个文件一行、不按 id 去重」（`session-manager.ts:941-967`）。
    // `list()` 只在**同一个 `id` 且一份的字节是另一份的前缀**时才合并（旧快照、组目录副本、
    // `/import` 的拷贝）；**互不为前缀**的两份 —— 那正是「一段对话被劈成两个文件」的形态 ——
    // **两行都留**（`PiSessionStore.mergeSameIdRow` 的 `PrefixRelation.Disjoint`）。
    // 所以同一个 `id` 到了这一屏**可能是两行**，而下面两处计数（副行的 `"${visible.size} 条"`
    // 与每个组标题的 `rows.size`）数的就是**行**：劈开的两半算两条。那是去重规则自己的账，
    // 本屏照抄它、不在这里另立一个「几条对话」的说法（也不减、不并）。
    //
    // 这条规则也决定了第三行「来自〈会话名〉」的取法：`forkParentNameOf` 的键是**文件名**
    // （`parentSession` 记的是一条路径），**不是 `id`** —— 所以同 `id` 两行不会让它去两半之间
    // 挑一行（见 `ui/chat/SessionForkLabel.kt`）；本屏再以 `file.absolutePath` 为键查一次，
    // 于是一行画一行自己的「来自谁」，劈开的两半各画各的，谁也不会替谁说话。
    val listView = remember(sessions, query, byName, namedOnly, workspacesRoot, workspaceLabels) {
        val tokens = sessionQueryTokens(query)
        val visible = sessions
            .filter { summary -> sessionMatches(summary, tokens) && (!namedOnly || hasName(summary)) }
            .let { list ->
                if (byName) list.sortedBy { it.displayName.lowercase() } else list
            }
        SessionListView(
            visible = visible,
            groups = visible
                .groupBy { it.cwd }
                .toList()
                .sortedByDescending { (_, rows) -> rows.maxOf { it.lastActivityAt } },
        )
    }
    val visible = listView.visible

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        PiTopBar(
            // 返回键在**左**边（用户裁决）：右侧留给搜索/工作区/设置/「⋯」。
            // 它仍然是覆盖层，行为不变——只是位置从右边挪到左边，和各屏顶栏的返回箭头
            // 统一成一个位置。
            onBack = onBack,
            title = view.label,
            // 顶栏的副行就是引擎状态（v2 的 `Engine`，`06 §4` 的三重编码：符号 + 词 +
            // 色）。会话列表是「看引擎在哪」的地方，所以这一行跟着它。
            statusLabel = session.engineLabel(state),
            actions = {
                // 右侧四个位置：搜索 / 工作区 / 设置 / 「⋯」。
                if (view == SessionsView.List) {
                    PiTopBarIcon(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                        contentDescription = if (searchOpen) "收起搜索" else "搜索会话",
                        icon = Icons.Filled.Search,
                    )
                }
                // 「导入」和「刷新」原来各占一个位置、还带一个文字按钮，现在都收进
                // 「⋯」——顶栏放进五个控件就挤了，而这两件事不是每屏都用。
                PiTopBarIcon(
                    onClick = onOpenWorkspace,
                    contentDescription = "工作区",
                    icon = Icons.Filled.FolderOpen,
                )
                PiTopBarIcon(
                    onClick = onOpenSettings,
                    contentDescription = "设置",
                    icon = Icons.Filled.Settings,
                )
                Box {
                    PiTopBarIcon(
                        onClick = { menuOpen = true },
                        contentDescription = "更多",
                        icon = Icons.Filled.MoreVert,
                    )
                    if (menuOpen) PiMenu(
                        expanded = true,
                        onDismiss = { menuOpen = false },
                        placement = PiMenuPlacement.Below,
                        items = buildList {
                            // The list can only show sessions that are already in pi's
                            // directory; a session that arrived as a file needs this.
                            add(
                                PiMenuItem("导入会话") {
                                    menuOpen = false
                                    importPicker.launch("*/*")
                                },
                            )
                            add(
                                PiMenuItem(
                                    if (view == SessionsView.List) "刷新会话列表" else "刷新会话树",
                                ) {
                                    menuOpen = false
                                    if (view == SessionsView.List) {
                                        session.refreshSessions()
                                    } else {
                                        session.refreshTree()
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        // 一条分段控件服务两个视图，所以它在两者之上（v2 的 `Seg`）；同行右侧是**筛过
        // 之后**的会话条数。它在两个视图里都在：v2 的 `SessionsOverlay` 把那一句写在
        // 列表/树的 `if` **之外**，`phone20`（搜 diff → 「1 条」）与 `phone27`/`phone28`
        // （树 → 「12 条」）两张图都画着它，所以树视图里也保留。
        // 一条分段控件服务两个视图，所以它在两者之上（v2 的 `Seg`）；同行右侧是**筛过
        // 之后**的会话条数，左侧跟着列表视图的两个筛选 chip。
        //
        // **合成一行**（会话主页方案 §4）：原来是「分段 + 条数」一行、搜索框一块、筛选
        // chip 再一行，三行界面吃掉一百多 dp；现在筛选 chip 跟分段同行、搜索默认收起，
        // 列表一屏因此能多出三四条。左边那一组**会横向滚动**——1.3 倍字号下它一定放不下，
        // 滚动比截断诚实；条数钉在右端，不跟着滚。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = SESSIONS_VIEW_ROW_TOP,
                    bottom = SESSIONS_VIEW_ROW_BOTTOM,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SESSIONS_VIEW_ROW_GAP),
            ) {
                PiSeg(
                    options = SessionsView.entries.map { it.label },
                    selectedIndex = view.ordinal,
                    onSelect = { index ->
                        val item = SessionsView.entries[index]
                        viewIndex = item.ordinal
                        // 树的叶子只有 `get_tree` 说了算，切过去时读一次；列表的刷新由
                        // `refreshSessions` 在进入这一屏时做过。
                        if (item == SessionsView.Tree) session.refreshTree()
                    },
                )
                if (view == SessionsView.List) {
                    // 排序 chip 的**选中态跟着 byTime 走**，不是永远选中：v2 的
                    // `active={byTime}`，`phone26`（`byTime=false`）画的就是一个未选中的
                    // 「↕ 按名称」加一个选中的「✓ 仅命名」。E3 台的描述（冻结稿自己的 dev
                    // 目录项：`direction-b-v2.html` 的 `05` 组 `:3398`）说两个都选中，按截图。
                    PiSettingsChip(
                        text = if (byName) "按名称" else "按时间",
                        glyph = "↕",
                        active = !byName,
                        onClick = { byName = !byName },
                    )
                    PiSettingsChip(
                        text = if (namedOnly) "仅命名" else "全部",
                        glyph = if (namedOnly) "✓" else "○",
                        active = namedOnly,
                        onClick = { namedOnly = !namedOnly },
                    )
                }
            }
            Text(
                "${visible.size} 条",
                modifier = Modifier.padding(start = SESSIONS_VIEW_ROW_GAP),
                style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                color = PiTheme.palette.muted,
            )
        }

        if (view == SessionsView.Tree) {
            // 树是嵌进来的：顶栏、分段控件与其上的不透明底都由本层提供，它只画自己的
            // 筛选行 + 列表。`embedded` 是这次唯一的形态改动，树的逻辑一行没动。
            SessionTreeScreen(
                state = state,
                onFork = { entryId ->
                    session.forkFrom(entryId)
                    onBack()
                },
                // 跳转 = pi's `navigateTree`: same session file, leaf moved. The overlay
                // stays open on purpose — unlike a fork, which replaces the conversation
                // and therefore closes it, a navigation lands the user back in the chat
                // they are already looking at, and pi's own `/tree` also returns to the
                // conversation after asking its summary question
                // (`interactive-mode.ts:5293-5317`). Closing here would hide the summary
                // card the user just asked for.
                onNavigate = { entryId, choice, instructions ->
                    session.navigateTo(entryId, choice, instructions)
                },
                skipSummaryPrompt = { session.branchSummarySkipPrompt() },
                onRefresh = { session.refreshTree() },
                onClose = onBack,
                embedded = true,
                onLoadEntries = { session.refreshEntries() },
                modifier = Modifier.weight(1f),
            )
        } else {
            // **搜索框默认收起**，顶栏的放大镜展开它。它原来常驻 36 高的一块，
            // 加外边距占掉约 48dp；收起之后这一屏的界面只剩上面那一行。
            //
            // 样式也换了：填充底 `surfaceContainerHigh`、**无描边**、整圆的胶囊
            // （`PiShapes.chip`）—— 描边是「可见轮廓」，这一屏现在只留卡片和发丝线。
            AnimatedVisibility(
                visible = searchOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val focus = remember { FocusRequester() }
                // 请求焦点放在**内容里**：`AnimatedVisibility` 决定这一层是否在组合里，
                // 只有和输入框同一次组合时 `FocusRequester` 才一定已经挂上节点。
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = PiSettingsMetrics.pageHorizontal,
                            end = PiSettingsMetrics.pageHorizontal,
                            top = SESSIONS_SEARCH_TOP,
                            bottom = SESSIONS_SEARCH_BOTTOM,
                        ),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SESSIONS_SEARCH_HEIGHT),
                        shape = PiShapes.chip,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = SESSIONS_SEARCH_PADDING),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SESSIONS_SEARCH_GAP),
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(SESSIONS_SEARCH_ICON),
                                tint = PiTheme.palette.muted,
                            )
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        "搜索会话、目录、文件",
                                        style = PiTheme.text.monoSmall,
                                        color = PiTheme.palette.muted,
                                    )
                                }
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focus),
                                    singleLine = true,
                                    textStyle = PiTheme.text.monoSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(PiTheme.palette.accent),
                                )
                            }
                            if (query.isNotEmpty()) {
                                Text(
                                    "清除",
                                    modifier = Modifier.clickable { query = "" },
                                    style = PiTheme.text.meta,
                                    color = PiTheme.palette.muted,
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (loading && sessions.isEmpty()) {
                    // The scan reads every session file, so on a phone with a long
                    // history the first one takes seconds. Rendering that as "还没有会话"
                    // was a claim about the user's data that had not been checked yet.
                    // Same empty-state component, honest words — no new visual
                    // vocabulary, and it disappears the moment the list arrives.
                    PiEmptyStateTopAnchored(
                        icon = Icons.Filled.Refresh,
                        title = "正在读取会话…",
                        body = "会话文件较多时需要几秒，读完后列表会显示在这里。",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (sessionsFailed && sessions.isEmpty()) {
                    // **读不到，不等于没有。** 这一支是这次补的第三种状态：旧代码把 `list()` 的异常
                    // 吞成 `emptyList()`，于是屏幕把「这次没读到」说成了「还没有会话」。
                    // 文案不引异常类名或消息（那是设备与实现的细节）：说清发生了什么、以及下一步
                    // 能做什么（右上角的刷新就是那一件事，`session.refreshSessions()`）。
                    PiEmptyStateTopAnchored(
                        icon = Icons.Filled.Warning,
                        title = "读不到会话列表",
                        body = "这一次读取失败了，不是没有会话。点右上角的刷新可以再试一次。",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (sessions.isEmpty()) {
                    PiEmptyStateTopAnchored(
                        icon = Icons.Filled.Forum,
                        title = "还没有会话",
                        body = "会话按工作目录分组，这里会列出每一个目录的对话。",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (visible.isEmpty()) {
                    PiEmptyStateTopAnchored(
                        icon = Icons.Filled.Search,
                        title = "没有匹配的会话",
                        body = "换一个关键词，或关掉「仅命名」筛选。",
                        modifier = Modifier.fillMaxSize(),
                        // `phone22` 是放大镜：一次没搜到不是「App 是空的」。
                    )
                } else {
                    // 分组与组间排序在上面那次 `remember` 里（与 `visible` 同一组键）：
                    // 它原来写在这里，于是这一屏**每一次重组**都要重跑一遍。
                    val groups = listView.groups
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // 列表底要给浮在它上面的按钮让出空间，否则滚到底时最后一条会话停在
                        // 按钮底下（看得见、点不到）。算法与两个数的分工见
                        // [SESSIONS_LIST_BOTTOM_RESERVE] 的 KDoc。
                        contentPadding = PaddingValues(bottom = SESSIONS_LIST_BOTTOM_RESERVE),
                    ) {
                        groups.forEach { (cwd, rows) ->
                            item(key = "hdr:$cwd") {
                                GroupHeading(groupLabel(cwd, workspacesRoot, workspaceLabels), rows.size)
                            }
                            // 组内的行共用一张**卡片**（v2 的 `Card`：`surf-low` 底、圆角
                            // 10、`margin:0 14px`，行之间才有分隔线，且那条线在卡内再缩进
                            // 14）。旧实现是每行一条通栏横线、没有卡，一屏看下来就是一片
                            // 游离的线 —— 这一屏"乱"的一半来自这里。
                            itemsIndexed(rows, key = { _, s -> s.file.absolutePath }) { index, summary ->
                                SessionRow(
                                    summary = summary,
                                    active = activeFile != null && summary.file.name == activeFile,
                                    first = index == 0,
                                    last = index == rows.lastIndex,
                                    // 父会话的显示名，取不到就是 null（父不在列表里）。
                                    // 工作区名不在行里了 —— 上面的分组头已经说过这一组是哪个目录。
                                    forkedFrom = forkParentNames[summary.file.absolutePath],
                                    onOpen = {
                                        session.switchSession(summary)
                                        onOpenChat()
                                    },
                                    onLongPress = { actions = summary },
                                )
                            }
                        }
                        // 这句话在 v2 里排在列表**最后**（`padding:'16px 14px 4px'`），
                        // 而不是搜索框下面：它说的是行上的操作，读完列表才用得上。
                        item(key = "hint") {
                            Text(
                                "长按一行可删除该会话；当前会话要切换后才能删除。",
                                modifier = Modifier.padding(
                                    start = PiSettingsMetrics.pageHorizontal,
                                    end = PiSettingsMetrics.pageHorizontal,
                                    top = SESSIONS_HINT_TOP,
                                    bottom = SESSIONS_HINT_BOTTOM,
                                ),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 新建会话：v2 画的是 accent 底的胶囊（`height:42; padding:0 16px; gap:7`，
                // 图标 16 + 14/600 文字，`color:var(--page)`），不是 M3 的 FAB —— 没有
                // 阴影、没有 tonal 容器色。
                //
                // **位置（D58）：浮在列表内容之上，不占布局高度。** 这一条是用户裁决，
                // 不是稿子的画法：v2 把它画成列表**下面**为自己一行（那个 `flex:'none'`
                // 的行，`padding:'0 16px 14px'` + `justify-content:flex-end`），本应用曾经
                // 照做，代价是那一行吃掉了列表底部约 70dp 的布局高度 —— 列表在它之上就结束
                // （`Box(weight(1f))` 的下沿），按钮那一条只剩**页面底色**，深色主题下就是
                // 一条近黑的空带（用户：「原来按钮浮动到屏幕上，现在那一排全黑了」）。
                // 回到浮动之后，列表视口一直铺到这一层的下沿（也就是系统导航条的上沿），
                // 内容还有更多时卡片会一直画到底，不存在那条带子。
                //
                // **可达性不是靠"盖住"解决的。** 当年那个真 bug 不在 `align(BottomEnd)`
                // 本身，而在浮了却没给列表留空间：最后一两行被压在按钮底下，点不到、
                // 也长按不到（长按是本屏的删除动作）。所以这里**同时**给列表加了
                // `contentPadding(bottom = SESSIONS_LIST_BOTTOM_RESERVE)`，最后一条会话
                // 永远能滚到按钮上方。两条要一起成立，不许用一个换另一个。
                //
                // 它在 `Box`（列表那一层）里，而不是像"下面一行"那样做 `Column` 的兄弟：
                // 只有 `BoxScope` 的 `align` 才是"压在内容上"，`Column` 的子项必然各占一行
                // —— 这正是那条空带的来源，位置（浮/不浮）与可达性（留不留空间）也因此
                // 必须分开说。
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = SESSIONS_NEW_SESSION_INSET,
                            bottom = SESSIONS_NEW_SESSION_BOTTOM,
                        ),
                ) {
                    NewSessionButton {
                        session.newSession()
                        onOpenChat()
                    }
                }
            }
        }
    }

    // pi's session actions, on a phone: long-press. `new_session` with a parent is
    // pi's own field (`rpc-types.ts:27` → the new session's header records it,
    // `session-manager.ts:938`), and pi's selector then nests the child under this
    // session (`components/session-selector.ts:206-231`). We pass the guest path so
    // the recorded parent resolves where pi reads it.
    //
    // v2 draws these three as a **bottom sheet** (`phone23`: 抓手 + 会话名 +
    // 「对这个会话做什么？」+ 三行), not as a centred dialog, because a row's own menu
    // belongs to the row that was pressed. The sheet shell is the settings editors'
    // one (`PiSettingsSheet`), so the handle, radius and scrim are the board's.
    val acting = actions
    if (acting != null) {
        PiSettingsSheet(onDismiss = { actions = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PiSettingsMetrics.pageHorizontal,
                        end = PiSettingsMetrics.pageHorizontal,
                        top = PiSettingsMetrics.sheetHeadTop,
                        bottom = PiSettingsMetrics.sheetHeadBottom,
                    ),
            ) {
                Text(
                    acting.displayName,
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "对这个会话做什么？",
                    modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SessionActionRow(
                label = "新建子会话",
                tone = MaterialTheme.colorScheme.onSurface,
            ) {
                actions = null
                session.newChildSession(acting)
            }
            SessionActionRow(
                label = "✗ 删除",
                tone = PiTheme.palette.error,
            ) {
                actions = null
                confirming = acting
            }
            SessionActionRow(
                label = "取消",
                tone = MaterialTheme.colorScheme.onSurface,
            ) { actions = null }
        }
    }

    // pi always confirms a delete (`docs/sessions.md:48`: "delete with Ctrl+D,
    // then confirm"), and deleting the session pi is currently appending to would
    // leave the engine writing to a removed file — so the active row is refused
    // rather than confirmed.
    //
    // v2's delete confirmation is `.b-dlg` with a leading glyph — `!` in error for
    // an ordinary session, `⊘` in muted for the current one (`SessionsOverlay`:
    // `glyph={ask==='current'?'⊘':'!'}`) — and its actions are ordered
    // `[删除, 取消]`, the primary first, which is the opposite of every other
    // dialog on the board. Both are why this one composes `PiDialog` directly
    // instead of going through the cancel-first helper.
    val pending = confirming
    if (pending != null) {
        val isActive = activeFile != null && pending.file.name == activeFile
        PiDialog(onDismissRequest = { confirming = null }) {
            PiDialogTitle(
                title = "删除会话",
                glyph = if (isActive) "⊘" else "!",
                glyphTone = if (isActive) PiTheme.palette.muted else PiTheme.palette.error,
            )
            PiDialogBody(
                if (isActive) {
                    "「${pending.displayName}」是当前会话，pi 正在写入这个文件。先切换到别的会话再删除。"
                } else {
                    "删除「${pending.displayName}」？这个会话会被移除，无法恢复。"
                },
            )
            PiDialogActions {
                PiDialogAction(
                    label = "删除",
                    primary = true,
                    enabled = !isActive,
                    tone = PiTheme.palette.error,
                    onClick = {
                        session.deleteSession(pending)
                        confirming = null
                    },
                )
                PiDialogAction(
                    label = "取消",
                    primary = false,
                    onClick = { confirming = null },
                )
            }
        }
    }
}

/**
 * 长按菜单里的一行（v2 `SessionsOverlay` 的 sheet 内容：`padding:12px 14px`、
 * 行标题 `PiType.rowTitle`（t15/500）、色由调用方按语义给 —— 删除是 error）。
 */
@Composable
private fun SessionActionRow(label: String, tone: Color, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = SESSIONS_ACTION_ROW_PADDING,
            ),
        style = PiType.rowTitle,
        color = tone,
    )
}

/**
 * 「+ 新建会话」：胶囊（`height:42; padding:0 16px; border-radius:999; gap:7`，
 * 图标 16 + 14/600 文字）。
 *
 * 底色是**主题容器色** `primaryContainer`（= pi 的 `selectedBg`，用户裁决）：原来的
 * 实心 accent 比主题色更深更重，压过了列表；容器色仍然是这一屏最显眼的一件动作，
 * 但安静一档。文字与图标用它的 `on*` 对色。仍然**没有阴影**（`06 §5`）。
 */
@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(SESSIONS_NEW_SESSION_HEIGHT)
            .clip(PiShapes.badge)
            // **主题容器色**（用户裁决）：`primaryContainer` = pi 的 `selectedBg`，
            // 也就是 accent 的低饱和底，比原来的实心 accent 安静一档，而仍然读成
            // 「这一屏最显眼的那一件动作」。文字与图标用它的 `on*` 对色。
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = SESSIONS_NEW_SESSION_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SESSIONS_NEW_SESSION_GAP),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(SESSIONS_NEW_SESSION_ICON),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            "新建会话",
            style = PiTheme.text.prose.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

// ------------------------------------------------------- v2 的会话覆盖层取值
//
// 每个数都指到 `design-demos/direction-b-v2.html` 的 `SessionsOverlay` 那一处。

/** `SessionsOverlay` 分段控件行：`padding:10px 14px 6px`，`gap:8`。 */
private val SESSIONS_VIEW_ROW_TOP = 10.dp
private val SESSIONS_VIEW_ROW_BOTTOM = 6.dp
private val SESSIONS_VIEW_ROW_GAP = 8.dp

/** `SessionsOverlay` 搜索块：`padding:4px 14px 8px`；框高 36、内 10、gap 8、图标 15。 */
private val SESSIONS_SEARCH_TOP = 4.dp
private val SESSIONS_SEARCH_BOTTOM = 8.dp
private val SESSIONS_SEARCH_HEIGHT = 36.dp
private val SESSIONS_SEARCH_PADDING = 10.dp
private val SESSIONS_SEARCH_GAP = 8.dp
private val SESSIONS_SEARCH_ICON = 15.dp


/**
 * `SessionsOverlay` 列表底：`paddingBottom:14` —— 列表**内容**与底边之间的最小间距。
 *
 * 与 [SESSIONS_LIST_BOTTOM_RESERVE] 是两件事，别混：这一条管"内容离底边至少留 14"
 * （稿子的值），那一条管"最后一条不被浮在上面的按钮盖住"（可达性的下界）。列表的
 * `contentPadding` 把两个数**相加**，谁也不替谁。
 */
private val SESSIONS_LIST_BOTTOM = 14.dp
private val SESSIONS_HINT_TOP = 16.dp
private val SESSIONS_HINT_BOTTOM = 4.dp

/** `SessionsOverlay` 新建按钮：`height:42`、`padding:0 16px`、gap 7、图标 16。 */
private val SESSIONS_NEW_SESSION_HEIGHT = 42.dp
private val SESSIONS_NEW_SESSION_PADDING = 16.dp
private val SESSIONS_NEW_SESSION_GAP = 7.dp
private val SESSIONS_NEW_SESSION_ICON = 16.dp

/** 浮动按钮那一条：`padding:0 16px 14px` 只剩右边与下边两个数（横向不再占一行，见 D58）。 */
private val SESSIONS_NEW_SESSION_INSET = 16.dp
private val SESSIONS_NEW_SESSION_BOTTOM = 14.dp

/**
 * 列表底要给浮在它上面的按钮让出的空间 = [SESSIONS_NEW_SESSION_BOTTOM]（按钮离底边 14）
 * + [SESSIONS_NEW_SESSION_HEIGHT]（胶囊 42）+ [SESSIONS_LIST_BOTTOM]（v2 的列表底 14）
 * = **70dp**。
 *
 * 取值的依据是两件必须同时成立的事，不是"看着够"：
 *  - **不被穿模**：`contentPadding` 的底必须 ≥ 胶囊高 + 它自己的下沿 = 56dp，否则滚到底时
 *    最后一条会话会停在按钮底下 —— 看得见、点不到（长按删除也够不着）。56 是这一条的**下界**。
 *  - **列表底仍保留 v2 的 14**：浮动没有取消 `SESSIONS_LIST_BOTTOM`，所以在下界之上再加它，
 *    滚到底时最后一条会话与按钮之间还有那 14 的呼吸。两件事因此是两个加数，不是二选一。
 *
 * **底部 inset 不在这里加，也不该在这里加。** 这一屏的根 `Column` 已经
 * `.padding(contentPadding)`，而这个 `contentPadding` 的底边**就是**系统导航条 inset
 * （手势条约 24、三键约 48；底栏拆掉之前还要再加 56 的底栏）—— 浮层画在 scaffold 的
 * 内容盒里、不替换它。列表与按钮都活在那个**已经缩进过**的盒子里，inset 再加一次就是
 * 双份，会凭空多出一条空白。同一个理由在会话树上已经量过一次
 * （`07-construction-decisions.md` D36：「被框住」不是缺底部 inset，再加一次会变成最后
 * 一行下面的**死带**），这里不重复走那条错路。
 *
 * 声明位置必须在三个被加数**之后**：Kotlin 的顶层属性按文件里的声明顺序初始化，写在前面会
 * 读到 `Dp` 的零值。
 */
private val SESSIONS_LIST_BOTTOM_RESERVE =
    SESSIONS_NEW_SESSION_BOTTOM + SESSIONS_NEW_SESSION_HEIGHT + SESSIONS_LIST_BOTTOM

/** sheet 里的一行：`padding:12px 14px`。 */
private val SESSIONS_ACTION_ROW_PADDING = 12.dp

/** `SessionsOverlay` 的分组头：`padding:14px 14px 6px`、`gap:8`，组名 12/500，`letter-spacing:.02em`。 */
private val SESSIONS_GROUP_HEADING_TOP = 14.dp
private val SESSIONS_GROUP_HEADING_BOTTOM = 6.dp
private val SESSIONS_GROUP_HEADING_GAP = 8.dp

/**
 * 「来自〈会话名〉」标签里名字的字数上限。超过就只写「分支」。
 *
 * 8 是这一行还能放得下两个标签（「已命名」+「来自 X」）的经验值：再长就会把左边的
 * 「模型 · N 条」压成省略号，而那个字段比父会话名更常读。
 */
private const val SESSIONS_TAG_NAME_MAX = 8

/** `.02em` 在 12sp 上是 0.24sp；`letterSpacing` 只吃绝对值。 */
private val SESSIONS_GROUP_TRACKING = 0.24.sp

/** 会话行第一行 `gap:7`、第二行 `gap:8`（v2 的 `SwipeRow` 两行各自的 `gap`）。 */
private val SESSIONS_ROW_LINE_GAP = 7.dp
private val SESSIONS_ROW_PROP_GAP = 8.dp




/**
 * 分组头：组名 + 本组条数。
 *
 * 取值是 v2 `SessionsOverlay` 那句 `padding:'14px 14px 6px', gap:8`：组名 `t12 w5`
 * 外加 `letter-spacing:.02em`，条数 `t12 c-muted`。旧实现借的是设置页的分组头取值
 * （顶上 10、底下 7、间距 7），三处都比稿子小，所以分组头贴着上一组的卡、读起来像那张
 * 卡的一部分。
 *
 * 组名来自 pi 记录的 cwd——本应用自己的工作区叫「工作区」，其余取目录末段
 * （[groupLabel]）。
 */
@Composable
private fun GroupHeading(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = SESSIONS_GROUP_HEADING_TOP,
                bottom = SESSIONS_GROUP_HEADING_BOTTOM,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SESSIONS_GROUP_HEADING_GAP),
    ) {
        Text(
            label,
            style = PiTheme.text.meta.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = SESSIONS_GROUP_TRACKING,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "$count",
            style = PiTheme.text.meta,
            color = PiTheme.palette.muted,
        )
    }
}

/**
 * 一行会话，**两行**（设计稿 §6）：
 *
 * ```
 * 第 1 行  会话名（15/500，当前会话 SemiBold，可省略）…   11分钟前（12，等宽数字）
 * 第 2 行  模型 · 9条（12 muted，可省略）…                [已命名] [来自 X]
 * ```
 *
 * 与上一版的差别，三件：
 *
 *  1. **工作区名不在行里了。** 分组头已经说过这一组是哪个目录，每行再印一遍只是把
 *     同一个词印 N 次。第二行的第一段因此从「工作区 · 模型」变成「模型」。
 *  2. **「条数」从右端挪到左段**，右端让给只在存在时出现的小标签（[SessionTag]）：
 *     「18 条 · 已命名 · 分支」那种靠间隔点串起来的尾巴，读起来是一串字段而不是
 *     一条信息。父会话的「来自 X」也从**第三行**收进标签，行高因此固定成两行。
 *  3. **当前会话不再用描边胶囊**（`CurrentBadge` 已删）：整行 accent 12% 底 +
 *     行左缘一条 3dp 竖条 + 标题 SemiBold，见下面 `drawBehind` 那段。
 *
 * 行内边距 `10px 12px`、两行间距 3、第一行 `gap:7`、第二行 `gap:8`。
 *
 * **行的底与分隔线是那张卡的**（v2 的 `Card`：`surf-low`、圆角 10、`margin:0 14px`，
 * 线只画在行与行之间、再向内缩 14）：所以这里按 [first]/[last] 只圆该圆的两个角，线画在
 * 下一行的顶上而不是每一行的下沿——一组的最后一行下面没有线，下一组的头才接得上。
 *
 * 相对时间用 `meta` + `tnum`（12 档 + 等宽数字）：v2 是 `mono t12 c-muted`，等宽保证
 * 滚动时那一列数字不抖；单位与数字之间**不留空格**（`11分钟前`）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    summary: PiSessionStore.Summary,
    active: Boolean,
    first: Boolean,
    last: Boolean,
    /**
     * 父会话的显示名，取不到就是 null（父不在列表里）。
     *
     * 它现在画在**第二行右端的小标签**里（「来自 X」），不再是单独一行：设计稿把
     * 「已命名 / 来自」都收成 12sp 的小标签，行高因此固定成两行，而分组头已经说过
     * 工作区，行里不必再说一遍。
     */
    forkedFrom: String?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal)
            .clip(
                RoundedCornerShape(
                    topStart = if (first) PiSettingsMetrics.cardRadius else 0.dp,
                    topEnd = if (first) PiSettingsMetrics.cardRadius else 0.dp,
                    bottomStart = if (last) PiSettingsMetrics.cardRadius else 0.dp,
                    bottomEnd = if (last) PiSettingsMetrics.cardRadius else 0.dp,
                ),
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (!first) {
            // 卡内行间线：**0.5dp 的 `outlineVariant`（`borderMuted` 的 55%）**，左侧
            // 缩进到文字列。它属于这一张卡，不是页面上的分栏线 —— 之前用 1dp 的不透明
            // `outline`，在深色主题下读起来是一条压过卡片的黑线（用户：「顶满全宽的深色
            // 分隔线」）。线只画在下一行的顶上：一组最后一行下面没有线。
            HorizontalDivider(
                modifier = Modifier.padding(start = PiSettingsMetrics.dividerInset),
                thickness = PiSpacing.hairlineThin,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        // 当前会话：**行底色 + 左侧 3dp 主色竖条**，不再是描边胶囊。竖条用
        // `drawBehind` 画在行自己的左缘、上下各内缩 12（设计稿 §6），这样它
        // 不会把行撑高，也不会被 `combinedClickable` 的 ripple 盖住。
        val accent = MaterialTheme.colorScheme.primary
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 底色先画、竖条后画：modifier 链上**外层先画**，所以 `background` 写在
                // `drawBehind` 前面，竖条才不会被那层 tint 盖住。
                .then(
                    if (active) {
                        Modifier.background(PiTheme.palette.selectedBg.copy(alpha = 0.35f))
                    } else {
                        Modifier
                    },
                )
                .drawBehind {
                    if (!active) return@drawBehind
                    val inset = PiSpacing.cardPadding.toPx()
                    val width = PiSpacing.stripe.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, inset),
                        size = Size(width, (size.height - inset * 2).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(width / 2f),
                    )
                }
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(
                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                    vertical = PiSettingsMetrics.rowPaddingVertical,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SESSIONS_ROW_LINE_GAP),
            ) {
                Text(
                    summary.displayName,
                    modifier = Modifier.weight(1f),
                    // 15/500：v2 的 `t15 w5`，行标题那一档 —— `PiType.rowTitle`。
                    // 当前会话升到 SemiBold（设计稿 §6），除了竖条之外的第二层标记。
                    style = if (active) {
                        PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        PiType.rowTitle
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    relativeTime(summary.lastActivityAt),
                    // 数字等宽：滚动时那一列不抖（`tnum`）。
                    style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                    color = PiTheme.palette.muted,
                )
            }
            Row(
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SESSIONS_ROW_PROP_GAP),
            ) {
                // 第二行左：**模型 · 条数**。工作区名不再重复 —— 分组头已经说过是哪一组，
                // 每行再印一遍只是把同一个词印 N 次（设计稿 §6）。
                Text(
                    buildString {
                        summary.model?.takeIf { it.isNotBlank() }?.let {
                            append(it).append(" · ")
                        }
                        append(summary.messageCount).append(" 条")
                    },
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 第二行右：只在存在时出现的小标签。两者都显示 12sp（App 的最小字号档），
                // 不再借 11sp —— 那一档低于 v2 的 12 下限。
                if (hasName(summary)) SessionTag("已命名")
                if (forkedFrom != null) {
                    // 父会话名太长时只写「分支」：标签是 12sp、没有省略号的余地，而
                    // 「来自一个被截断的名字」比「分支」更难读。
                    SessionTag(if (forkedFrom.length > SESSIONS_TAG_NAME_MAX) "分支" else "来自 $forkedFrom")
                }
            }
        }
    }
}

/**
 * 行内的小标签（设计稿 §6：「已命名」「来自 X」）：`surfaceContainerHighest` 底、
 * 6dp 圆角、12sp。
 *
 * 内边距用 `PiSpacing` 的两个既有档（6 / 2），不再新造数值 —— `screens/` 下的裸 dp
 * 字面量是受守卫脚本冻结的。
 */
@Composable
private fun SessionTag(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(PiShapes.tag)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = PiSpacing.gutter, vertical = PiSpacing.tiny),
        style = PiTheme.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * 一组会话的标题（以及行内第二行的第一个字段），给定 pi 记在会话头里的工作目录。
 *
 * 规则本身不在这里：它是 `WorkspaceChoice.sessionGroupLabel` 的 **一个** 实现，与工作区页的
 * ① 卡、切换面板、行内第二行共用同一套名字。这个函数只是把这一屏手上的三个输入递给它。
 *
 * 为什么这件事必须做（而不是好看）：这个标题原来把**当前**工作区那一组印成通用词「工作区」、
 * 其余组印目录名，于是同一个工作区在会话页叫「工作区」、在工作区页叫「我的项目」；而这里以前
 * 的注释承认它是 `ProjectResources.workspaceName` 的第二份拷贝（「两个屏幕属于不同批次」）。
 * 现在**所有**工作区组都用工作区页那套名字。
 *
 * 原始值是一个 **guest** 路径（本应用的工作区是 `/workspace/pi/workspaces/workspace-1`，终端里
 * 自己起的 pi 是 `/root`，见 `PiSessionStore`），直接印既没人能读，也是全应用唯一会露出内部
 * 目录的地方。不是本应用工作区的 cwd 仍取末段：两个同名目录在标题里看起来一样，这比一条谁也
 * 用不上的路径问题小。
 */
private fun groupLabel(path: String, workspacesRoot: String, labels: Map<String, String>): String =
    WorkspaceChoice.sessionGroupLabel(path, workspacesRoot, labels)

/**
 * Coarse on purpose: a precise timestamp on every row is noise, and the list is
 * sorted newest-first anyway.
 */
private fun relativeTime(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    if (delta < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 60 * 24 -> "${minutes / 60}小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}天前"
        else -> "${minutes / (60 * 24 * 7)}周前"
    }
}

/** pi's `hasSessionName` (`session-selector-search.ts:36-38`): a *trimmed* non-empty name. */
private fun hasName(summary: PiSessionStore.Summary): Boolean = !summary.name.isNullOrBlank()

/** `\s+` — pi's own tokenizer, both here and in the tree selector. */
private val SESSION_SEARCH_WHITESPACE = Regex("\\s+")

/**
 * Does this session match the search box? **pi's rule, not a substring of the whole box.**
 *
 * pi splits the query on whitespace and requires **every** token to match
 * (`session-selector-search.ts:135-183`: `matchSession` returns `{matches:false}` on the
 * first token that fails, so the tokens are ANDed). The app used to hand the whole box
 * to one `contains`, so `read file` matched nothing in a session whose text says
 * "file … read" — and a two-word search behaved differently here than in pi for no
 * reason. The tree view already tokenises this way
 * (`SessionTreeScreen.kt`'s `flattenTree` filter), so the two search boxes in one
 * overlay no longer disagree about what a space means.
 *
 * The text searched is pi's too, as far as this app can see it: pi matches
 * `id + name + allMessagesText + cwd` (`:31-33`). `allMessagesText` does not come over
 * the wire — [PiSessionStore.Summary] carries no such field, and the store deliberately
 * stops scanning at 1 MiB per file rather than streaming every message — so this
 * searches the fields that *are* here: the name pi recorded, the first-message title
 * behind it (pi searches both; the app used to search only the display name, so a
 * renamed session could no longer be found by its own opening line), the cwd, the
 * file name and the session id.
 *
 * **Deviation, named:** pi matches each token with `fuzzyMatch` from pi-tui (a
 * subsequence match that also *scores*, which is what pi's `relevance` sort mode is
 * built on), where this uses `contains`. Substring is the stricter test — nothing pi
 * would find is hidden, but a token whose letters are merely in order somewhere is not
 * found here. Matching pi's matcher would mean porting its scoring, and the tree
 * selector already made the same call.
 *
 * **The tokens come in already split** ([sessionQueryTokens]). They are a property of the
 * *query*, not of a row, so splitting them once per filter pass instead of once per row is
 * the same list — and the filter pass runs on the frame thread on every keystroke and on
 * every session-list refresh. The row-dependent half (which text is searched, and the
 * lowercase of it) stays here, because lowercasing the pieces separately is *not* the same
 * string as lowercasing their concatenation (the JVM's `lowercase()` is context-sensitive:
 * a final capital sigma lowercases differently at the end of a word).
 */
private fun sessionMatches(summary: PiSessionStore.Summary, tokens: List<String>): Boolean {
    if (tokens.isEmpty()) return true
    val text = buildString {
        append(summary.name.orEmpty()).append('\n')
        append(summary.title).append('\n')
        append(summary.cwd).append('\n')
        append(summary.file.name).append('\n')
        append(summary.id)
    }.lowercase()
    return tokens.all { text.contains(it) }
}

/**
 * The search box as pi tokenises it (`session-selector-search.ts:135-183`): lowercased,
 * split on `\s+`, empties dropped. `SESSION_SEARCH_WHITESPACE` is pi's own tokenizer, shared
 * with the tree selector.
 *
 * One call per filter pass, not one per row — see [sessionMatches]. The expression is the
 * one that used to live inside [sessionMatches], so the tokens are the same list.
 */
private fun sessionQueryTokens(query: String): List<String> =
    query.lowercase().split(SESSION_SEARCH_WHITESPACE).filter { it.isNotEmpty() }

/**
 * This screen's two derived readings of [PiSessionStore.Summary], computed together so that
 * one key set (and therefore one O(n) `remember` comparison) covers both.
 *
 * @param visible the rows after the search box and the two filter chips, and after the
 *   sort chip — the list the count in the view row and the empty state read.
 * @param groups [visible] by `cwd`, ordered by each group's newest activity, with each group
 *   keeping [visible]'s order inside it.
 */
private data class SessionListView(
    val visible: List<PiSessionStore.Summary>,
    val groups: List<Pair<String, List<PiSessionStore.Summary>>>,
)
