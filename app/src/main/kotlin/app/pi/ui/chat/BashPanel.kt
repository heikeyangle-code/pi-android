package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.BashRun
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiTheme

/**
 * `!` / `!!` 终端命令执行浮卡：清晰区分「进入上下文（bashMode 绿）」与「仅本地（dim）」，
 * 支持实时滚动输出、一键中断与结果收起。
 */
@Composable
fun BashPanel(
    run: BashRun,
    onAbort: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val accent = if (run.excludeFromContext) palette.muted else palette.bashMode
    val scopeLabel = if (run.excludeFromContext) tr("仅本地", "Local only") else tr("进上下文", "In context")
    val status = statusText(run)
    val recessedBg = androidx.compose.ui.graphics.lerp(palette.pageBg, palette.cardBg, 0.35f)
    val cardBorder = if (run.running) accent.copy(alpha = 0.45f) else palette.borderMuted.copy(alpha = 0.60f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.cardBg,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(0.5.dp, cardBorder),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 顶部命令头：干净底色 + 语义色 `$` 前缀 + 运行转环 + 细框操作钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (run.running) {
                    app.pi.ui.components.PiSpinner(
                        size = 14.dp,
                        strokeWidth = 1.6.dp,
                        color = accent,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = "$ ${run.command}",
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = recessedBg,
                    border = BorderStroke(0.5.dp, palette.borderMuted.copy(alpha = 0.50f)),
                ) {
                    Text(
                        text = scopeLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = PiTheme.text.monoSmall,
                        color = if (run.excludeFromContext) palette.muted else accent,
                        maxLines = 1,
                    )
                }
                if (run.output.isNotEmpty()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val copyLabel = tr("复制输出", "Copy output")
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(recessedBg)
                            .clickable(onClickLabel = copyLabel) {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("bash", run.output))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = copyLabel,
                            tint = palette.muted,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                if (run.running) {
                    val stopLabel = tr("停止命令", "Stop command")
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(palette.error.copy(alpha = 0.14f))
                            .clickable(onClickLabel = stopLabel, onClick = onAbort),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stopLabel,
                            tint = palette.error,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                } else {
                    val closeLabel = tr("关闭输出", "Close output")
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(recessedBg)
                            .clickable(onClickLabel = closeLabel, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = closeLabel,
                            tint = palette.muted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            // 输出区（流式增长时自动滚底）
            if (run.output.isNotEmpty()) {
                val outputScroll = rememberScrollState()
                androidx.compose.runtime.LaunchedEffect(run.output.length) {
                    outputScroll.scrollTo(outputScroll.maxValue)
                }
                HorizontalDivider(thickness = 0.5.dp, color = palette.borderMuted.copy(alpha = 0.42f))
                Text(
                    text = run.output,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(outputScroll)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                )
            }

            // 状态尾注（运行中 / 取消 / 报错 / 截断时展示）
            if (status != null) {
                val tone = statusTone(run, palette)
                HorizontalDivider(thickness = 0.5.dp, color = palette.borderMuted.copy(alpha = 0.42f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.5.dp)
                            .clip(CircleShape)
                            .background(tone),
                    )
                    Text(
                        text = status,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun statusText(run: BashRun): String? {
    val scope = if (run.excludeFromContext) {
        tr("不进上下文", "Excluded from context")
    } else {
        tr("将随下条消息进入上下文", "Included in next message context")
    }
    if (run.running) return tr("命令执行中 · $scope", "Running · $scope")
    val parts = buildList {
        if (run.cancelled) {
            add(tr("已取消", "Cancelled"))
        } else {
            run.exitCode?.takeIf { it != 0 }?.let { add(tr("退出码 $it", "Exit code $it")) }
        }
        if (run.truncated) {
            add(
                run.fullOutputPath?.let { tr("输出被截断，完整输出：$it", "Output truncated, full output: $it") }
                    ?: run.fullOutputError?.let { tr("输出被截断，未能保存完整输出：$it", "Output truncated, could not save full output: $it") }
                    ?: tr("输出被截断", "Output truncated"),
            )
        }
    }
    if (parts.isEmpty()) return null
    return (parts + scope).joinToString(" · ")
}

private fun statusTone(run: BashRun, palette: PiPalette): Color = when {
    run.running -> palette.accent
    run.cancelled -> palette.warning
    run.exitCode != null && run.exitCode != 0 -> palette.error
    else -> palette.warning
}
