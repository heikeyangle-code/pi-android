package app.pi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.components.PiEmptyState
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel

/**
 * The transcript — the app's main stage (destination 2).
 *
 * Structure follows pi's own viewport stack, with the footer moved to the top
 * because the keyboard would otherwise cover it
 * (docs/pi-android-ui-spec.md §4.1):
 *
 *   AppBar          session name · reload · overflow
 *   StatusRow       cwd · branch · ↑input ↓output · context ring · model
 *   Transcript      14 block kinds
 *   QueueChips      Steering / Follow-up
 *   Composer        border colour = thinking level, `!` = bash mode
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(contentPadding: PaddingValues) {
    var draft by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(PiThinkingLevel.Medium) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("未打开会话", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "本地引擎未启动",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重载扩展、技能与主题")
                }
                IconButton(onClick = { }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
            },
        )

        PiEmptyState(
            icon = Icons.Filled.ChatBubble,
            title = "从这里开始",
            body = "pi 会读写你选定的工作区、执行命令、改代码。\n" +
                "所有推理与工具调用都会出现在这条时间线上。",
            modifier = Modifier.weight(1f),
        )

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            thinking = thinking,
            onCycleThinking = { thinking = PiThinkingLevel.next(thinking) },
            bottomInset = contentPadding.calculateBottomPadding(),
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    thinking: PiThinkingLevel,
    onCycleThinking: () -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen)
                .heightIn(min = 56.dp, max = 160.dp),
            placeholder = { Text("输入消息，或 / 执行命令") },
            shape = PiShapes.inputMultiline,
            // The border colour is a state channel, not decoration: pi paints the
            // editor with the current thinking level's token and switches to
            // `bashMode` when the line starts with `!`.
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PiTheme.palette.thinking(thinking.wire),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            trailingIcon = {
                IconButton(onClick = { }) {
                    Icon(Icons.Filled.Send, contentDescription = "发送")
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.screen),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(percent = 50),
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyHint("esc")
                KeyHint("tab")
                KeyHint("/")
                KeyHint("@")
                KeyHint("!")
                Spacer(Modifier.size(8.dp))
                /*
                 * Thinking-level chip. Its colour is the app's signature: pi
                 * encodes the level as a colour temperature and this is the one
                 * element that carries that language into the GUI.
                 */
                Surface(
                    shape = PiShapes.badge,
                    color = PiTheme.palette.thinking(thinking.wire).copy(alpha = 0.18f),
                ) {
                    Text(
                        "◐ ${thinking.label}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = PiTheme.palette.thinking(thinking.wire),
                    )
                }
            }
        }
        Spacer(Modifier.height(bottomInset + 8.dp))
    }
}

@Composable
private fun KeyHint(label: String) {
    Text(
        label,
        modifier = Modifier.padding(end = 10.dp),
        style = PiTheme.text.monoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
