package app.pi.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.runtime.GuestWorkspacePath
import app.pi.session.PiSessionStore
import app.pi.ui.PiSessionViewModel
import app.pi.ui.chat.SessionTreeScreen
import app.pi.ui.components.PiEmptyState
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.numeric
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
@OptIn(ExperimentalMaterial3Api::class)
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
            .filter { summary ->
                val hit = query.isBlank() ||
                    summary.displayName.contains(query, ignoreCase = true) ||
                    summary.cwd.contains(query, ignoreCase = true) ||
                    summary.file.name.contains(query, ignoreCase = true)
                hit && (!namedOnly || !summary.name.isNullOrBlank())
            }
            .let { list ->
                if (byName) list.sortedBy { it.displayName.lowercase() } else list
            }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(view.label) },
            actions = {
                if (view == SessionsView.List) {
                    // The list can only show sessions that are already in pi's
                    // directory; a session that arrived as a file needs this.
                    TextButton(onClick = { importPicker.launch("*/*") }) { Text("导入") }
                    IconButton(onClick = { session.refreshSessions() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新会话列表")
                    }
                } else {
                    TextButton(onClick = { session.refreshTree() }) { Text("刷新") }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭会话列表")
                }
            },
        )
        // 一条分段控件服务两个视图，所以它在两者之上（v2 的 `Seg`）。
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(
                start = PiSettingsMetrics.pageHorizontal,
                end = PiSettingsMetrics.pageHorizontal,
                top = PiSettingsMetrics.rowPaddingVertical,
                bottom = PiSettingsMetrics.groupHeaderGap,
            ),
        ) {
            SessionsView.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = view == item,
                    onClick = {
                        viewIndex = item.ordinal
                        // 树的叶子只有 `get_tree` 说了算，切过去时读一次；列表的刷新由
                        // `refreshSessions` 在进入这一屏时做过。
                        if (item == SessionsView.Tree) session.refreshTree()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, SessionsView.entries.size),
                ) { Text(item.label) }
            }
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(
                            horizontal = PiSettingsMetrics.pageHorizontal,
                            vertical = 4.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
                                    tint = PiTheme.palette.muted,
                                )
                            },
                            placeholder = { Text("搜索名称 / 目录 / 文件名") },
                            textStyle = PiTheme.text.mono,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { byName = !byName }) {
                            Text(if (byName) "按名称" else "按时间")
                        }
                        TextButton(onClick = { namedOnly = !namedOnly }) {
                            Text(if (namedOnly) "仅命名" else "全部")
                        }
                    }
                    // The delete action is a long press, so the row has to say so: an
                    // action nobody can find is the same complaint as one that does not
                    // exist. pi reaches it with Ctrl+D (`docs/sessions.md:48`), which a
                    // phone has no key for.
                    Text(
                        "长按一行可删除该会话；当前会话要切换后才能删除。",
                        modifier = Modifier.padding(
                            horizontal = PiSettingsMetrics.pageHorizontal,
                            vertical = 2.dp,
                        ),
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sessions.isEmpty()) {
                        PiEmptyState(
                            icon = Icons.Filled.Forum,
                            title = "还没有会话",
                            body = "会话按工作目录分组，这里会列出每一个目录的对话。",
                            modifier = Modifier.weight(1f),
                        )
                    } else if (visible.isEmpty()) {
                        PiEmptyState(
                            icon = Icons.Filled.Search,
                            title = "没有匹配的会话",
                            body = "换一个关键词，或关掉「仅命名」筛选。",
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        val groups = visible
                            .groupBy { it.cwd }
                            .toList()
                            .sortedByDescending { (_, rows) -> rows.maxOf { it.lastActivityAt } }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = PiSpacing.unit),
                        ) {
                            groups.forEach { (cwd, rows) ->
                                item(key = "hdr:$cwd") { GroupHeading(groupLabel(cwd), rows.size) }
                                items(rows, key = { it.file.absolutePath }) { summary ->
                                    SessionRow(
                                        summary = summary,
                                        active = activeFile != null && summary.file.name == activeFile,
                                        onOpen = {
                                            session.switchSession(summary)
                                            onOpenChat()
                                        },
                                        onLongPress = { actions = summary },
                                    )
                                }
                            }
                        }
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        session.newSession()
                        onOpenChat()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = PiSettingsMetrics.pageHorizontal,
                            bottom = PiSettingsMetrics.groupGap,
                        ),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新建会话") },
                )
            }
        }
    }

    // pi's session actions, on a phone: long-press. `new_session` with a parent is
    // pi's own field (`rpc-types.ts:27` → the new session's header records it,
    // `session-manager.ts:938`), and pi's selector then nests the child under this
    // session (`components/session-selector.ts:206-231`). We pass the guest path so
    // the recorded parent resolves where pi reads it.
    val acting = actions
    if (acting != null) {
        AlertDialog(
            onDismissRequest = { actions = null },
            title = { Text(acting.displayName) },
            text = { Text("对这个会话做什么？") },
            confirmButton = {
                TextButton(onClick = {
                    actions = null
                    session.newChildSession(acting)
                }) { Text("新建子会话") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        actions = null
                        confirming = acting
                    }) { Text("删除") }
                    TextButton(onClick = { actions = null }) { Text("取消") }
                }
            },
        )
    }

    // pi always confirms a delete (`docs/sessions.md:48`: "delete with Ctrl+D,
    // then confirm"), and deleting the session pi is currently appending to would
    // leave the engine writing to a removed file — so the active row is refused
    // rather than confirmed.
    val pending = confirming
    if (pending != null) {
        val isActive = activeFile != null && pending.file.name == activeFile
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("删除会话") },
            text = {
                Text(
                    if (isActive) {
                        "「${pending.displayName}」是当前会话，pi 正在写入这个文件。先切换到别的会话再删除。"
                    } else {
                        "删除「${pending.displayName}」？这个会话会被移除，无法恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        session.deleteSession(pending)
                        confirming = null
                    },
                    enabled = !isActive,
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 分组头：组名 + 本组条数（v2：`padding:14px 14px 6px`，组名 12 正文、条数 12 灰）。
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
                top = PiSettingsMetrics.rowPaddingVertical,
                bottom = PiSettingsMetrics.groupHeaderGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.titleGap),
    ) {
        Text(
            label,
            style = PiTheme.text.meta,
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
 * 一行会话，v2 的**两行**形态（`direction-b-v2.html:1814-1822`）：
 *
 * ```
 * 第 1 行  会话名（15/500，可省略）…  [当前]        相对时间（12 等宽）
 * 第 2 行  工作区 · 模型（12 muted，可省略）…       18 条 · 分支 · 已命名（12 muted）
 * ```
 *
 * 行内边距 `10px 12px`、两行间距 3——与设置页的行同一套取值。把三段挤回三行会让每一行
 * 比它承载的信息更高，而这一屏要能一眼扫完。
 *
 * 相对时间用 `numeric`（= 等宽）：一列时间在滚动时会因为数字宽度不同而抖动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    summary: PiSessionStore.Summary,
    active: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(
                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                    vertical = PiSettingsMetrics.rowPaddingVertical,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Spacer(Modifier.width(PiSettingsMetrics.badgeGap))
                    Surface(shape = PiShapes.badge, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "当前",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(PiSettingsMetrics.badgeGap))
                Text(
                    relativeTime(summary.lastActivityAt),
                    style = PiTheme.text.numeric,
                    color = PiTheme.palette.muted,
                )
            }
            Row(
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                verticalAlignment = Alignment.CenterVertically,
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
                Spacer(Modifier.width(PiSettingsMetrics.badgeGap))
                Text(
                    buildString {
                        append(summary.messageCount).append(" 条")
                        if (summary.parentSession != null) append(" · 分支")
                        if (!summary.name.isNullOrBlank()) append(" · 已命名")
                    },
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.muted,
                )
            }
        }
        InsetHairline()
    }
}

/** 行之间那根 inset hairline（`06 §2`「列表分隔线左侧 inset 14px」）。 */
@Composable
private fun InsetHairline() {
    HorizontalDivider(
        modifier = Modifier.padding(
            start = PiSettingsMetrics.pageHorizontal + PiSettingsMetrics.dividerInset,
        ),
        thickness = PiSettingsMetrics.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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
