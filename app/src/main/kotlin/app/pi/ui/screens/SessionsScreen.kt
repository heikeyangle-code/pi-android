package app.pi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.runtime.GuestWorkspacePath
import app.pi.session.PiSessionStore
import app.pi.ui.PiSeg
import app.pi.ui.PiSessionViewModel
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.PiTopBarTextAction
import app.pi.ui.chat.SessionTreeScreen
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiEmptyStateTopAnchored
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSheet
import app.pi.ui.theme.PiShapes
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
 * `03-navigation-decision.md` 的裁决：底栏是三个「地方」，而会话列表是一个**选择器**
 * （pi 自己就是把会话树覆盖在转录上）。这一屏由 `PiRoot` 的覆盖层状态承载，所以它
 * 自己**不注册** `BackHandler`：全应用只有那一处，两处会互相遮盖（那边的 KDoc 记着
 * 这条教训）。
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
 * 三段真实字段（`02-real-content.md` §1.2 的公式）折成 v2 的两行
 * (`direction-b-v2.html:1814-1822`)：
 *
 * ```
 * 第 1 行   会话名（可省略）… [当前]                相对时间
 * 第 2 行   工作区 · 模型（可省略）…                18 条 · 分支 · 已命名
 * ```
 *
 * 第二行的属性段右对齐、第一段让位给省略号：一屏要读的是「哪条、多久以前、多少条」。
 * 文案一个字没改——它们是 pi 的字段，不是排版。
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
 * @param onOpenChat 选中一行之后：关掉覆盖层，并落在对话页。
 * @param onClose 顶栏的关闭键。系统返回键走 `PiRoot` 那一处 `BackHandler`，同一个动作。
 * @param initialView 打开时停在哪个视图。`/tree` 与「分支摘要」行要求直接落在树上——
 *   一个写着「树」的命令不该让用户再点一次分段控件。宿主把这个偏好提到覆盖层之外，
 *   因为它在这一屏存在之前就已经被设定了。
 */
@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
    onOpenChat: () -> Unit,
    onClose: () -> Unit,
    initialView: SessionsView = SessionsView.List,
) {
    val sessions by session.sessions.collectAsState()
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

    val visible = remember(sessions, query, byName, namedOnly) {
        sessions
            .filter { summary -> sessionMatches(summary, query) && (!namedOnly || hasName(summary)) }
            .let { list ->
                if (byName) list.sortedBy { it.displayName.lowercase() } else list
            }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        PiTopBar(
            title = view.label,
            // 顶栏的副行就是引擎状态（v2 的 `Engine`，`06 §4` 的三重编码：符号 + 词 +
            // 色）。会话列表是「看引擎在哪」的地方，所以这一行跟着它。
            engineMeta = session.engineLabel(state),
            actions = {
                // v2 的顶栏在列表视图给「导入」+ 刷新图标，在树视图给「刷新」
                // （`SessionsOverlay` 的 `right`），所以两个视图的右侧不完全相同。
                if (view == SessionsView.List) {
                    // The list can only show sessions that are already in pi's
                    // directory; a session that arrived as a file needs this.
                    PiTopBarTextAction("导入") { importPicker.launch("*/*") }
                    PiTopBarIcon(
                        onClick = { session.refreshSessions() },
                        contentDescription = "刷新会话列表",
                        icon = Icons.Filled.Refresh,
                    )
                } else {
                    PiTopBarTextAction("刷新") { session.refreshTree() }
                }
                PiTopBarIcon(
                    onClick = onClose,
                    contentDescription = "关闭会话列表",
                    icon = Icons.Filled.Close,
                )
            },
        )
        // 一条分段控件服务两个视图，所以它在两者之上（v2 的 `Seg`）；同行右侧是**筛过
        // 之后**的会话条数。它在两个视图里都在：v2 的 `SessionsOverlay` 把那一句写在
        // 列表/树的 `if` **之外**，`phone20`（搜 diff → 「1 条」）与 `phone27`/`phone28`
        // （树 → 「12 条」）两张图都画着它，所以树视图里也保留。
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
            Spacer(Modifier.weight(1f))
            Text(
                "${visible.size} 条",
                style = PiTheme.text.meta,
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
                    onClose()
                },
                onRefresh = { session.refreshTree() },
                onClose = onClose,
                embedded = true,
                modifier = Modifier.weight(1f),
            )
        } else {
            // 搜索块：v2 把「方框 + 筛选行」包在**一个** `padding:'4px 14px 8px'` 的块里，
            // 而筛选行自己在块内 `marginTop:8`。所以 4 / 8 不是两次留白，是那一个块的上沿
            // 与它两半之间；块的 8 下沿落在筛选行**下面**（见 `SESSIONS_CHIP_TOP`）。
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
                // 搜索框：v2 在会话覆盖层里是 36 高的方框（`SessionsOverlay` 的
                // `height:36`；设置首页那个才是 40），圆角 9、`surf-low` 底、
                // 1px `borderMuted`、内 `padding:0 10px`、图标 15、文本 12 等宽，
                // 有输入时右侧出现「清除」。
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SESSIONS_SEARCH_HEIGHT),
                    shape = RoundedCornerShape(PiSettingsMetrics.searchFieldRadius),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(SESSIONS_HAIRLINE, MaterialTheme.colorScheme.outline),
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
                                    "搜索名称 / 目录 / 文件名",
                                    style = PiTheme.text.monoSmall,
                                    color = PiTheme.palette.muted,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
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
                // 两个筛选 chip 与右侧那句提示同一行（v2 的 `rw`：`gap:7; marginTop:8`）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = SESSIONS_CHIP_TOP),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SESSIONS_CHIP_GAP),
                ) {
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
                    Spacer(Modifier.weight(1f))
                    // The delete action is a long press, so the row has to say so: an
                    // action nobody can find is the same complaint as one that does not
                    // exist. pi reaches it with Ctrl+D (`docs/sessions.md:48`), which a
                    // phone has no key for.
                    Text(
                        "长按一行可删除该会话",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (sessions.isEmpty()) {
                    PiEmptyStateTopAnchored(
                        icon = Icons.Filled.Forum,
                        title = "还没有会话",
                        body = "会话按工作目录分组，这里会列出每一个目录的对话。",
                        modifier = Modifier.fillMaxSize(),
                        // `phone21` 画的是对话气泡，不是 π 字形：π 是 App 自己那几面
                        // 空态（对话页）的标识，这里画的是「这个列表里没有东西」。
                        markPi = false,
                    )
                } else if (visible.isEmpty()) {
                    PiEmptyStateTopAnchored(
                        icon = Icons.Filled.Search,
                        title = "没有匹配的会话",
                        body = "换一个关键词，或关掉「仅命名」筛选。",
                        modifier = Modifier.fillMaxSize(),
                        // `phone22` 是放大镜：一次没搜到不是「App 是空的」。
                        markPi = false,
                    )
                } else {
                    val groups = visible
                        .groupBy { it.cwd }
                        .toList()
                        .sortedByDescending { (_, rows) -> rows.maxOf { it.lastActivityAt } }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = SESSIONS_LIST_BOTTOM),
                    ) {
                        groups.forEach { (cwd, rows) ->
                            item(key = "hdr:$cwd") { GroupHeading(groupLabel(cwd), rows.size) }
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
            }
            // 新建会话：v2 画的是 accent 底的胶囊（`height:42; padding:0 16px; gap:7`，
            // 图标 16 + 14/600 文字，`color:var(--page)`），不是 M3 的 FAB —— 没有
            // 阴影、没有 tonal 容器色。
            //
            // 而且它是**列表下面自己的一行**（v2 里那个 `flex:'none'` 的行，
            // `padding:'0 16px 14px'` + `justify-content:flex-end`），不是浮在内容上的
            // 覆盖件：旧实现用 `align(BottomEnd)` 把它压在列表上，最后一两行永远被按钮
            // 盖住、点不到，这是这一屏真实存在的点击目标 bug。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SESSIONS_NEW_SESSION_INSET,
                        end = SESSIONS_NEW_SESSION_INSET,
                        bottom = SESSIONS_NEW_SESSION_BOTTOM,
                    ),
                horizontalArrangement = Arrangement.End,
            ) {
                NewSessionButton {
                    session.newSession()
                    onOpenChat()
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
                    style = MaterialTheme.typography.titleSmall,
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
 * 长按菜单里的一行（v2 `SessionsOverlay` 的 sheet 内容：`padding:12px 14px`、t14、
 * 色由调用方按语义给 —— 删除是 error）。
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
        style = MaterialTheme.typography.bodyMedium,
        color = tone,
    )
}

/**
 * 「+ 新建会话」：v2 的胶囊（`height:42; padding:0 16px; border-radius:999;
 * background:var(--accent); color:var(--page); gap:7`，图标 16 + 14/600 文字）。
 *
 * 用 accent 而不是 M3 FAB 的 `primaryContainer`，是因为 v2 把它画成这一屏上最显眼
 * 的那一件动作，而且明确没有阴影（`06 §5`：没有阴影）。
 */
@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(SESSIONS_NEW_SESSION_HEIGHT)
            .clip(PiShapes.badge)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = SESSIONS_NEW_SESSION_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SESSIONS_NEW_SESSION_GAP),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(SESSIONS_NEW_SESSION_ICON),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            "新建会话",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
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

/** `SessionsOverlay` 筛选行：块内 `marginTop:8`、`gap:7`。 */
private val SESSIONS_CHIP_TOP = 8.dp
private val SESSIONS_CHIP_GAP = 7.dp

/** `SessionsOverlay` 列表底：`paddingBottom:14`；那句提示 `16px 14px 4px`。 */
private val SESSIONS_LIST_BOTTOM = 14.dp
private val SESSIONS_HINT_TOP = 16.dp
private val SESSIONS_HINT_BOTTOM = 4.dp

/** `SessionsOverlay` 新建按钮：`height:42`、`padding:0 16px`、gap 7、图标 16。 */
private val SESSIONS_NEW_SESSION_HEIGHT = 42.dp
private val SESSIONS_NEW_SESSION_PADDING = 16.dp
private val SESSIONS_NEW_SESSION_GAP = 7.dp
private val SESSIONS_NEW_SESSION_ICON = 16.dp

/** 按钮那一行：`padding:0 16px 14px`。 */
private val SESSIONS_NEW_SESSION_INSET = 16.dp
private val SESSIONS_NEW_SESSION_BOTTOM = 14.dp

/** sheet 里的一行：`padding:12px 14px`。 */
private val SESSIONS_ACTION_ROW_PADDING = 12.dp

/** `SessionsOverlay` 的分组头：`padding:14px 14px 6px`、`gap:8`，组名 12/500，`letter-spacing:.02em`。 */
private val SESSIONS_GROUP_HEADING_TOP = 14.dp
private val SESSIONS_GROUP_HEADING_BOTTOM = 6.dp
private val SESSIONS_GROUP_HEADING_GAP = 8.dp

/** `.02em` 在 12sp 上是 0.24sp；`letterSpacing` 只吃绝对值。 */
private val SESSIONS_GROUP_TRACKING = 0.24.sp

/** 会话行第一行 `gap:7`、第二行 `gap:8`（v2 的 `SwipeRow` 两行各自的 `gap`）。 */
private val SESSIONS_ROW_LINE_GAP = 7.dp
private val SESSIONS_ROW_PROP_GAP = 8.dp

/**
 * 「当前」徽标（v2 的 `Badge`：`direction-b-v2.html:594`）——透明底、1px
 * `borderMuted` 圆环、`padding:1px 7px 1px 6px`、`gap:5`，里面是「accent 色块（5×5
 * 圆角 1）+ accent 的 `●` + 正文色的文字」。`phone19` 的徽标就是这三个元素。
 *
 * 它替掉的是原来的 `primaryContainer` 实底胶囊：那是一个 M3 容器色（`selectedBg`），
 * 而 v2 的徽标是描边 + 色块，两者在深色下完全不同。文字也不再借 `labelSmall`——那个
 * 角色是 11.5sp，低于 v2 的 12 档下限（`PiTheme.kt` 的 `PiTextStyles` KDoc）。
 */
private val SESSIONS_BADGE_GLYPH = "●"

/** v2 的 `Badge` 把字与符号的行高钉在 16（`lineHeight:'16px'`），不是默认的 18。 */
private val SESSIONS_BADGE_LINE_HEIGHT = 16.sp

/** `06 §2`「线宽：全篇只有 1px」。 */
private val SESSIONS_HAIRLINE = 1.dp

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
 * 一行会话，v2 的**两行**形态（`direction-b-v2.html:1814-1822`；`board` 的 D1 台写作
 * 「三行字段压成两行」）：
 *
 * ```
 * 第 1 行  会话名（15/500，可省略）…  [当前]        相对时间（12 等宽）
 * 第 2 行  工作区 · 模型（12 muted，可省略）…       18 条 · 分支 · 已命名（12 muted）
 * ```
 *
 * 行内边距 `10px 12px`、两行间距 3、第一行 `gap:7`、第二行 `gap:8`。
 *
 * **行的底与分隔线是那张卡的**（v2 的 `Card`：`surf-low`、圆角 10、`margin:0 14px`，
 * 线只画在行与行之间、再向内缩 14）：所以这里按 [first]/[last] 只圆该圆的两个角，线画在
 * 下一行的顶上而不是每一行的下沿——一组的最后一行下面没有线，下一组的头才接得上。
 *
 * 相对时间用 `monoSmall`（12 等宽）：v2 是 `mono t12 c-muted`，等宽保证滚动时那一列
 * 数字不抖；原来的 `numeric` 是 13 档。
 *
 * 会话名不因「当前」而变色：v2 的标题是 `t15 w5`（正文色），当前是靠**徽标**说的
 * （`phone19`）。pi 自己会把当前会话那一行染成 accent（`session-selector.ts` 的
 * `isCurrent → accent`），但那一档的呈现是外观，按 `11-designer-adjudication.md`
 * 的规则（外观与版式以 v2 为准）交给徽标。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    summary: PiSessionStore.Summary,
    active: Boolean,
    first: Boolean,
    last: Boolean,
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
            // `.div` 是 `borderMuted` **不透明**（`.hair` 才是 55% 那根），所以这里是
            // `outline` 而不是 `outlineVariant`：卡内的行间线在稿子里比页面上的 hair 更实。
            HorizontalDivider(
                modifier = Modifier.padding(start = PiSettingsMetrics.dividerInset),
                thickness = PiSettingsMetrics.hairline,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    // 15/500：v2 的 `t15 w5`。`bodyLarge` 是同一档字号但 400，所以只补字重。
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) CurrentBadge()
                Text(
                    relativeTime(summary.lastActivityAt),
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.muted,
                )
            }
            Row(
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SESSIONS_ROW_PROP_GAP),
            ) {
                Text(
                    buildString {
                        append(groupLabel(summary.cwd))
                        summary.model?.let { append(" · ").append(it) }
                    },
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(summary.messageCount).append(" 条")
                        if (summary.parentSession != null) append(" · 分支")
                        if (hasName(summary)) append(" · 已命名")
                    },
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
            }
        }
    }
}

/** v2 的 `Badge`，`tone="var(--accent)"`, `glyph="●"`, `text="当前"`（见上面那句 KDoc）。 */
@Composable
private fun CurrentBadge() {
    Row(
        modifier = Modifier
            .clip(PiShapes.badge)
            .border(
                PiSettingsMetrics.hairline,
                MaterialTheme.colorScheme.outline,
                PiShapes.badge,
            )
            .padding(
                start = PiSettingsMetrics.badgePaddingStart,
                end = PiSettingsMetrics.badgePaddingEnd,
                top = PiSettingsMetrics.badgePaddingVertical,
                bottom = PiSettingsMetrics.badgePaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        Box(
            Modifier
                .size(PiSettingsMetrics.badgeDot)
                .clip(RoundedCornerShape(PiSettingsMetrics.badgeDotRadius))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            SESSIONS_BADGE_GLYPH,
            style = PiTheme.text.monoSmall.copy(lineHeight = SESSIONS_BADGE_LINE_HEIGHT),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            "当前",
            style = PiTheme.text.meta.copy(lineHeight = SESSIONS_BADGE_LINE_HEIGHT),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * The heading a group of sessions is listed under, given the working directory pi
 * recorded in each session's header.
 *
 * The raw value is a **guest** path (`/workspace/pi/workspaces/workspace-1` for this
 * app's own workspace, `/root` for a session started by typing `pi` in the terminal —
 * see `PiSessionStore`), and printing it was both wrong for a person to read and the
 * only place in this app that showed an internal directory. So the app's own
 * workspace is named, and anything else is shown as its last segment: two projects
 * with the same folder name look alike in the heading, which is a smaller problem
 * than a path nobody can act on.
 *
 * `ProjectResources.kt` carries the same rule for the project screen; the two copies
 * exist because the screens are owned by different batches and a shared helper would
 * couple them.
 */
private fun groupLabel(path: String): String {
    val clean = path.trimEnd('/')
    if (clean.isEmpty()) return "工作目录未记录"
    val workspacePrefix = GuestWorkspacePath.GUEST_ROOT + "/" + GuestWorkspacePath.RELATIVE
    if (clean == workspacePrefix) return "工作区"
    return clean.substringAfterLast('/').ifEmpty { clean }
}

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
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
        else -> "${minutes / (60 * 24 * 7)} 周前"
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
 */
private fun sessionMatches(summary: PiSessionStore.Summary, query: String): Boolean {
    val tokens = query.lowercase().split(SESSION_SEARCH_WHITESPACE).filter { it.isNotEmpty() }
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
