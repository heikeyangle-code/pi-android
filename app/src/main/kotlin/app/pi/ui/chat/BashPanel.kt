package app.pi.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.ui.BashRun
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * The `!` / `!!` bash panel.
 *
 * pi renders a running command in the chat container with a `$ command` header
 * painted in the `bashMode` token, the output in `dim` when the run was excluded
 * from context, and a completion line with the exit code
 * (`modes/interactive/components/bash-execution.ts:37`, `:138`). The same facts
 * are carried here from the other side of the RPC boundary: the command and the
 * exit status come from the `bash` response, the output from
 * `bash_execution_update` events (`agent-session.ts:3027`) because pi emits no
 * event for the completed result.
 *
 * `maxLines`-free scrolling rather than a transcript row on purpose: this is
 * *pending* state — pi only folds the run into the conversation on the next
 * prompt (`recordBashResult` → `_flushPendingBashMessages`), so drawing it inside
 * the transcript would show context the model has not received yet.
 */
@Composable
fun BashPanel(
    run: BashRun,
    onAbort: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    // pi: an excluded run (`!!`) is dimmed, a context-carrying one uses bashMode.
    val accent = if (run.excludeFromContext) palette.dim else palette.bashMode
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.cardBg,
        shape = PiShapes.cardInner,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$ ${run.command}",
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.mono,
                    color = accent,
                )
                if (run.running) {
                    IconButton(onClick = onAbort) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "停止命令",
                            tint = palette.error,
                        )
                    }
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭输出",
                            tint = palette.muted,
                        )
                    }
                }
            }
            if (run.output.isNotEmpty()) {
                Text(
                    text = run.output,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    style = PiTheme.text.monoSmall,
                    color = palette.toolOutput,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusLine(run),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    run.running -> palette.muted
                    run.cancelled -> palette.warning
                    run.exitCode == 0 || run.exitCode == null -> palette.success
                    else -> palette.error
                },
            )
        }
    }
}

/**
 * pi's completion line, in the app's words.
 *
 * The three cases are pi's own: a cancelled run has no exit code
 * (`BashResult.exitCode` is null, `core/bash-executor.ts`), a truncated one names
 * the file holding the rest (`fullOutputPath`), and everything else reports the
 * code. `excludeFromContext` is spelled out because it changes whether the output
 * will reach the model on the next prompt.
 */
private fun statusLine(run: BashRun): String {
    val scope = if (run.excludeFromContext) "不进上下文" else "进入上下文"
    if (run.running) return "运行中 · $scope"
    val status = when {
        run.cancelled -> "已取消"
        run.exitCode == null -> "已结束"
        run.exitCode == 0 -> "成功"
        else -> "退出码 ${run.exitCode}"
    }
    val truncated = if (run.truncated) {
        run.fullOutputPath?.let { " · 输出被截断，完整输出：$it" } ?: " · 输出被截断"
    } else {
        ""
    }
    return "$status$truncated · $scope"
}
