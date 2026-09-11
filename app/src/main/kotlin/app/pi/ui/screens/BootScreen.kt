package app.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.runtime.RuntimeProvisioner
import app.pi.ui.Boot
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * First-launch and boot-failure surface.
 *
 * This screen exists instead of a spinner because the first launch genuinely
 * takes tens of seconds (unpacking a Linux userland) and because one step can
 * honestly fail: Android 10+ refuses to execute files in the app's own data
 * directory, and this app's entire runtime lives there. When that happens the
 * user needs to know *which* step failed and what it means, not a generic error.
 */
@Composable
fun BootScreen(
    boot: Boot,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PiSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                Icons.Filled.Memory,
                contentDescription = null,
                modifier = Modifier.padding(20.dp).size(30.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(PiSpacing.unit))

        when (boot) {
            Boot.Idle -> {
                Text("准备启动本地引擎", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "首次启动要准备 Linux 运行环境，需要几分钟。不需要 root。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PiSpacing.unit))
                Button(onClick = onRetry) { Text("开始") }
            }

            is Boot.Working -> {
                Text("正在安装运行时", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    boot.step.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(PiSpacing.unit))
                StepBar(boot.step)
                Spacer(Modifier.height(6.dp))
                Text(
                    "步骤 ${boot.step.index + 1} / ${boot.step.total}",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PiSpacing.unit))
                Text(
                    "只在首次启动时做一次，之后冷启动是秒级。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is Boot.Failed -> {
                Text("引擎没能启动", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = PiShapes.card,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(PiSpacing.card)) {
                        Text(
                            boot.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (!boot.detail.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                boot.detail,
                                style = PiTheme.text.monoSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PiSpacing.unit))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("重试")
                    }
                }
                Spacer(Modifier.height(PiSpacing.unit))
                Text(
                    "如果反复失败，把这个提示连同它下面的文字发给我。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Boot.Ready -> Unit
        }
    }
}

/**
 * Hand-drawn progress bar rather than `LinearProgressIndicator`.
 *
 * Deliberate: the indicator's `progress` parameter changed shape between Compose
 * releases, and this app is built somewhere that cannot compile-check locally on
 * every machine. A Box pair has no such surface area.
 */
@Composable
private fun StepBar(step: RuntimeProvisioner.Step) {
    val fraction = step.fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}
