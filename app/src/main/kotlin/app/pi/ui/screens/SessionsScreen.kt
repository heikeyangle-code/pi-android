package app.pi.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.session.PiSessionStore
import app.pi.ui.PiSessionViewModel
import app.pi.ui.components.PiEmptyState
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.util.concurrent.TimeUnit

/**
 * Sessions list (destination 1).
 *
 * pi reaches this surface through `/resume`; on a phone it is the home screen.
 * There is no RPC command that lists sessions — pi can only switch to a path you
 * already know — so the list comes from scanning pi's own session directory, the
 * same thing the desktop picker does (docs/pi-android-app-design.md §4.5, §18.3).
 *
 * A tap **switches the session** rather than merely opening the chat destination:
 * `switch_session` is what makes pi adopt the file, is what lets a
 * `session_before_switch` extension veto it, and is the only way the transcript
 * can be rebuilt for another session (audit §6.9).
 *
 * The picker's controls mirror pi's own (`docs/sessions.md:43-46`): search by
 * typing, a sort toggle (Ctrl+S), a named-only filter (Ctrl+N) and delete with a
 * confirmation step (Ctrl+D). Rows are grouped by working directory because that
 * is how pi stores them — one directory per cwd under the session root
 * (`session-manager.ts:479`) — so the grouping mirrors the store rather than
 * inventing one.
 *
 * Rename, export and clone are deliberately **not** offered per row. pi's
 * `set_session_name`, `export_html` and `clone` take no session argument — they
 * act on the active session (`rpc-mode.ts:600-631`, `:661-668`) — so offering
 * them on a non-active row would silently operate on a different session than the
 * one the user pointed at. They live on the Chat screen's overflow, where the
 * target is unambiguous. pi's own TUI has the same constraint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    onOpenChat: () -> Unit,
    session: PiSessionViewModel,
) {
    val sessions by session.sessions.collectAsState()
    val state by session.state.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var byName by rememberSaveable { mutableStateOf(false) }
    var namedOnly by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<PiSessionStore.Summary?>(null) }

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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("会话") },
                actions = {
                    IconButton(onClick = { session.refreshSessions() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新会话列表")
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
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
            if (sessions.isEmpty()) {
                PiEmptyState(
                    icon = Icons.Filled.Forum,
                    title = "还没有会话",
                    body = "会话按工作目录分组。\n" +
                        "新建一个，或者把手机里的文件夹设为工作区。",
                    modifier = Modifier.weight(1f),
                )
            } else if (visible.isEmpty()) {
                PiEmptyState(
                    icon = Icons.Filled.Forum,
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
                        item(key = "hdr:$cwd") { PiSectionHeader(shortenPath(cwd)) }
                        items(rows, key = { it.file.absolutePath }) { summary ->
                            SessionRow(
                                summary = summary,
                                active = activeFile != null && summary.file.name == activeFile,
                                onOpen = {
                                    session.switchSession(summary)
                                    onOpenChat()
                                },
                                onLongPress = { confirming = summary },
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
                    end = PiSpacing.screen,
                    bottom = contentPadding.calculateBottomPadding() + PiSpacing.unit,
                ),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("新建会话") },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    summary: PiSessionStore.Summary,
    active: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = PiSpacing.screen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.displayName,
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
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = PiShapes.badge, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "当前",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(shortenPath(summary.cwd))
                    summary.model?.let { append(" · ").append(it) }
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(summary.messageCount).append(" 条")
                    if (summary.parentSession != null) append(" · 分支")
                    if (!summary.name.isNullOrBlank()) append(" · 已命名")
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            relativeTime(summary.lastActivityAt),
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tail-relative, which is what stays meaningful for a long phone path. */
private fun shortenPath(path: String): String =
    if (path.length <= 40) path else "…" + path.takeLast(39)

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
