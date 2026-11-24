package app.pi.ui.resources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.packages.PiProjectTrust
import app.pi.ui.PiSessionViewModel
import app.pi.ui.settings.PiSettingsDialog
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.launch

/**
 * pi 的「信任这个项目吗？」弹窗（官方 `project-trust` 选择器）。
 *
 * 项目里有要信任才加载的东西（`.pi` 的设置 / 扩展 / 技能 / 提示 / 主题 / 系统提示 / `mcp.json`，或
 * `.agents/skills`）、没人表过态、默认策略又是 `ask` 时，宿主发 `project.trust.required`，ViewModel 把
 * 状态存成 [PiSessionViewModel.trustPrompt]；这里挂在根上，无论用户在哪一屏都会被问到。
 * 以前这个事件没人接，所以用户**从来没被问过**，项目资源一直安静地没加载。
 *
 * 选项与官方一一对应（[PiProjectTrust.promptOptions]）。「先不决定」只是这次不问：不写信任库，
 * 项目仍按未信任处理，下次打开还会问。
 */
@Composable
internal fun ProjectTrustHost(session: PiSessionViewModel) {
    val prompt by session.trustPrompt.collectAsStateWithLifecycle()
    val state = prompt ?: return
    val scope = rememberCoroutineScope()
    var error by remember(state.cwd) { mutableStateOf<String?>(null) }
    var busy by remember(state.cwd) { mutableStateOf(false) }

    val options = if (state.undecided) {
        PiProjectTrust.promptOptions(state)
    } else {
        PiProjectTrust.adjustments(state)
    }
    PiSettingsDialog(
        onDismissRequest = { if (!busy) session.dismissTrustPrompt() },
        title = if (state.undecided) tr("信任这个项目吗？", "Trust this project?") else tr("项目信任设置", "Project Trust"),
        sub = state.cwd,
        body = PiProjectTrust.explanation(state),
        content = {
            Column {
                TrustOptionList(
                    options = options,
                    enabled = !busy,
                    onPick = { option ->
                        busy = true
                        error = null
                        scope.launch {
                            val failure = session.answerTrust(option)
                            if (failure != null) {
                                error = failure
                                busy = false
                            }
                        }
                    },
                )
                error?.let {
                    Text(it, style = PiTheme.text.meta, color = PiTheme.palette.error, modifier = Modifier.padding(top = PiSpacing.small))
                }
            }
        },
        dismissalLabel = tr("先不决定", "Not Now"),
        onDismissButton = { if (!busy) session.dismissTrustPrompt() },
    )
}

/** 一列选项：标题 + 一行说明；「信任」类用 accent，让人看清这是在放行项目里的代码。 */
@Composable
internal fun TrustOptionList(
    options: List<PiProjectTrust.Option>,
    enabled: Boolean,
    onPick: (PiProjectTrust.Option) -> Unit,
) {
    val palette = PiTheme.palette
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            Surface(
                onClick = { onPick(option) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (option.grants) {
                    palette.accent.copy(alpha = 0.10f)
                } else {
                    palette.cardBg
                },
                border = BorderStroke(
                    width = PiSpacing.hairlineThin,
                    color = if (option.grants) {
                        palette.accent.copy(alpha = 0.44f)
                    } else {
                        palette.borderMuted
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        option.label,
                        style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                        color = if (option.grants) palette.accent else palette.text,
                    )
                    option.hint?.let {
                        Text(it, style = PiTheme.text.meta, color = palette.muted)
                    }
                }
            }
        }
    }
}
