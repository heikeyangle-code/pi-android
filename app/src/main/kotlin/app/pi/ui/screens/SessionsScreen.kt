package app.pi.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.session.PiSessionStore
import app.pi.ui.PiSessionViewModel
import app.pi.ui.components.PiEmptyState
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

    // The directory only becomes meaningful once the runtime is unpacked, so
    // refresh when the screen appears rather than at construction.
    LaunchedEffect(Unit) { session.refreshSessions() }

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
            // `sessionFile` is the only wire field that says which session pi is
            // on, and it is a guest path — the file name is the part that matches
            // the on-disk index.
            val activeFile = state.meta.sessionFile?.substringAfterLast('/')
            if (sessions.isEmpty()) {
                PiEmptyState(
                    icon = Icons.Filled.Forum,
                    title = "还没有会话",
                    body = "pi 的每个会话都是一个 JSONL 文件，按工作目录归档。\n" +
                        "新建一个，或者把手机里的文件夹设为工作区。",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = PiSpacing.unit),
                ) {
                    items(sessions, key = { it.file.absolutePath }) { summary ->
                        SessionRow(
                            summary = summary,
                            active = activeFile != null && summary.file.name == activeFile,
                            onOpen = {
                                session.switchSession(summary)
                                onOpenChat()
                            },
                        )
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
}

@Composable
private fun SessionRow(summary: PiSessionStore.Summary, active: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
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
