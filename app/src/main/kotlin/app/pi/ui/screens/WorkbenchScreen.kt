package app.pi.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.components.PiEmptyState
import app.pi.ui.extension.ExtensionUiHost
import app.pi.ui.terminal.TerminalPane
import app.pi.ui.theme.PiSpacing

/**
 * The workbench (destination 3): terminal · files · git · tasks.
 *
 * These share one context — the current working directory — which is why they
 * are segments of one destination rather than separate destinations
 * (docs/pi-android-ui-spec.md §5.3).
 *
 * The **terminal** segment is the one that is load-bearing rather than a
 * convenience: it runs the *original* pi TUI in a real PTY, which is the only way
 * the extension APIs that draw terminal cells (`ctx.ui.custom()`, overlays,
 * custom footers, `registerMessageRenderer`, `renderCall`/`renderResult`) stay
 * reachable. That is why this app never has to claim "we reimplemented 95% of the
 * API" — see [TerminalPane].
 *
 * It also mounts [ExtensionUiHost]. This screen has no `session` parameter and
 * the host resolves the Activity-scoped ViewModel itself, which is deliberate:
 * `ctx.ui.confirm()` blocks the *RPC* engine, and the RPC engine keeps running
 * while the user watches the terminal — so a dialog that only appeared on the
 * Chat destination would leave pi blocked behind a screen the user is not
 * looking at. The terminal's own PTY is a different pi process with its own TUI,
 * so nothing is duplicated by showing the RPC dialogs here as well.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(contentPadding: PaddingValues) {
    var segment by rememberSaveable { mutableStateOf(0) }
    val labels = listOf("终端", "文件", "Git", "任务")
    val icons = listOf(
        Icons.Filled.Terminal,
        Icons.Filled.Folder,
        Icons.Filled.AccountTree,
        Icons.Filled.TaskAlt,
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TopAppBar(title = { Text("工作区") })
            SingleChoiceSegmentedButtonRow(
                Modifier.padding(horizontal = PiSpacing.screen),
            ) {
                labels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = segment == index,
                        onClick = { segment = index },
                        shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                        icon = { Icon(icons[index], contentDescription = null) },
                    ) { Text(label) }
                }
            }
            if (segment == 0) {
                // The terminal takes the rest of the screen: a TUI wants every row it
                // can get, and the key bar sits at its bottom edge.
                TerminalPane(Modifier.weight(1f))
            } else {
                val (title, body) = when (segment) {
                    1 -> "没有工作区" to "把手机里的一个文件夹设为工作区后，文件树会出现在这里。"
                    2 -> "不是 Git 仓库" to "工作区是 Git 仓库时，这里显示变更、diff 与检查点。"
                    else -> "没有后台任务" to "长时间运行的回合与命令会出现在这里，可以随时停止。"
                }
                PiEmptyState(
                    icon = icons[segment],
                    title = title,
                    body = body,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ExtensionUiHost(
            modifier = Modifier.fillMaxSize(),
            snackbarBottomPadding = contentPadding.calculateBottomPadding() + 8.dp,
        )
    }
}
