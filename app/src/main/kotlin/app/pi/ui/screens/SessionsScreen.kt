package app.pi.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pi.ui.components.PiEmptyState
import app.pi.ui.theme.PiSpacing

/**
 * Sessions list (destination 1).
 *
 * pi reaches this surface through `/resume`; on a phone it is the home screen.
 * Grouping, filters and the long-press menu follow pi's resume picker verbatim
 * (path display, sort mode, named-only filter, rename, delete) —
 * docs/pi-android-ui-spec.md §5.1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    onOpenChat: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("会话") },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索会话")
                    }
                },
            )
            PiEmptyState(
                icon = Icons.Filled.Forum,
                title = "还没有会话",
                body = "pi 的每个会话都是一个 JSONL 文件，按工作目录归档。\n新建一个，或者把手机里的文件夹设为工作区。",
                modifier = Modifier.weight(1f),
            )
        }
        ExtendedFloatingActionButton(
            onClick = onOpenChat,
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
