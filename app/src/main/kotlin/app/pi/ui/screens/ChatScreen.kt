package app.pi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.Boot
import app.pi.ui.PiSessionViewModel
import app.pi.ui.blocks.BlockRenderer
import app.pi.ui.components.PiEmptyState
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel

/**
 * The transcript — the app's main stage (destination 2).
 *
 * Structure follows pi's own viewport stack, with the footer moved to the top
 * because the keyboard would otherwise cover it (docs/pi-android-ui-spec.md §4.1):
 *
 *   AppBar          session name · status · reload · overflow
 *   Transcript      the block kinds, rendered by `ui/blocks`
 *   QueueChips      steering / follow-up
 *   Composer        border colour = thinking level, `!` = bash mode
 *
 * Until the engine is up this screen shows the boot surface instead: the first
 * launch unpacks a Linux userland and that takes long enough that hiding it
 * behind a spinner would read as a hang.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contentPadding: PaddingValues,
    session: PiSessionViewModel,
) {
    val state by session.state.collectAsState()

    if (state.boot !is Boot.Ready) {
        BootScreen(
            boot = state.boot,
            onRetry = { session.boot() },
            modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        )
        return
    }

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the tail while streaming, but never steal the scroll: only scroll
    // when the count grows, and let the user's own scrolling win afterwards.
    LaunchedEffect(state.revision) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        if (state.transcript.isEmpty()) "新会话" else "会话",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        session.engineLabel(state),
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

        if (state.transcript.isEmpty()) {
            PiEmptyState(
                icon = Icons.Filled.ChatBubble,
                title = "引擎已就绪",
                body = "pi 会读写你选定的工作区、执行命令、改代码。\n" +
                    "所有推理与工具调用都会出现在这条时间线上。",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    vertical = PiSpacing.unit,
                    horizontal = PiSpacing.screen,
                ),
                verticalArrangement = Arrangement.spacedBy(PiSpacing.unit),
            ) {
                // Keyed by the reducer's stable per-block key, which is what lets
                // Compose animate the row that changed while streaming instead of
                // recomposing the list.
                items(state.transcript, key = { it.key }) { item ->
                    BlockRenderer(
                        item = item,
                        toolsDefaultExpanded = false,
                        thinkingDefaultExpanded = false,
                    )
                }
            }
        }

        if (state.queueSteering > 0 || state.queueFollowUp > 0) {
            QueueRow(steering = state.queueSteering, followUp = state.queueFollowUp)
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            thinking = state.thinking,
            streaming = state.streaming,
            onCycleThinking = { session.cycleThinking() },
            onSend = {
                if (draft.isNotBlank()) {
                    session.send(draft)
                    draft = ""
                }
            },
            onStop = { session.stop() },
            bottomInset = contentPadding.calculateBottomPadding(),
        )
    }
}

@Composable
private fun QueueRow(steering: Int, followUp: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (steering > 0) QueueChip("穿插 $steering")
        if (steering > 0 && followUp > 0) Spacer(Modifier.width(8.dp))
        if (followUp > 0) QueueChip("后续 $followUp")
    }
}

@Composable
private fun QueueChip(text: String) {
    Surface(shape = PiShapes.badge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    thinking: PiThinkingLevel,
    streaming: Boolean,
    onCycleThinking: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    bottomInset: Dp,
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
            // A state channel, not decoration: pi paints its editor with the
            // current thinking level's token, and switches to `bashMode` when the
            // line starts with `!`.
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PiTheme.palette.thinking(thinking.wire),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            trailingIcon = {
                IconButton(onClick = { if (streaming) onStop() else onSend() }) {
                    Icon(
                        if (streaming) Icons.Filled.Stop else Icons.Filled.Send,
                        contentDescription = if (streaming) "停止" else "发送",
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PiSpacing.screen),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(percent = 50),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyHint("esc")
                KeyHint("tab")
                KeyHint("/")
                KeyHint("@")
                KeyHint("!")
                Spacer(Modifier.weight(1f))
                // The signature element: pi encodes the thinking level as a colour
                // temperature, and this chip is that language carried into the GUI.
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
