package app.pi.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.runtime.RuntimeProvisioner
import app.pi.ui.Boot
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn

/**
 * 首次安装初始化与引擎恢复页：
 *  - 品牌 `π` 徽章 + 清晰的步骤进度条与实时步骤卡片；
 *  - 失败态：结构化诊断卡片 + 一键复制错误详情 + 主按钮重试 + 危险操作独立区分。
 */
@Composable
fun BootScreen(
    boot: Boot,
    onRetry: () -> Unit,
    onRebuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val isFailed = boot is Boot.Failed
        val emblemTint = if (isFailed) palette.error else palette.accent
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(emblemTint.copy(alpha = 0.14f))
                .border(1.dp, emblemTint.copy(alpha = 0.36f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFailed) "!" else "π",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = emblemTint,
            )
        }
        Spacer(Modifier.height(20.dp))

        when (boot) {
            Boot.Idle -> {
                BootHeading(tr("准备启动本地引擎", "Ready to start local engine"))
                Spacer(Modifier.height(10.dp))
                BootParagraph(
                    tr(
                        "首次启动需要解压并配置本地 Linux 运行环境，全程在设备离线完成，无需 root。",
                        "First launch unpacks and configures the local Linux runtime offline on your device. No root required.",
                    ),
                )
                Spacer(Modifier.height(24.dp))
                BootButton(label = tr("开始准备", "Get started"), onClick = onRetry)
            }

            is Boot.Working -> {
                BootHeading(tr("正在初始化运行环境", "Initializing runtime"))
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, palette.borderMuted.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = boot.step.label,
                                modifier = Modifier.weight(1f),
                                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                                color = palette.text,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${boot.step.index + 1} / ${boot.step.total}",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(palette.accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.accent,
                            )
                        }
                        StepBar(boot.step)
                    }
                }
                Spacer(Modifier.height(14.dp))
                BootParagraph(
                    tr(
                        "仅首次安装需要数分钟，完成后日常冷启动为秒级。",
                        "First-time setup takes a few minutes; subsequent launches take seconds.",
                    ),
                )
            }

            is Boot.Failed -> {
                BootHeading(boot.failure.title)
                Spacer(Modifier.height(16.dp))
                BootErrorCard(message = boot.failure.explanation, detail = boot.failure.detail)
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BootButton(
                        label = tr("重试启动", "Retry"),
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                    if (boot.failure.rebuildOffered) {
                        BootButton(
                            label = tr("重建运行时", "Rebuild runtime"),
                            onClick = onRebuild,
                            destructive = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                BootParagraph(
                    if (boot.failure.rebuildOffered) {
                        tr(
                            "重建仅清理易失的 guest 运行时（apt/pip/npm 全局包）；你的工作区、会话、设置和登录凭证均不受影响。",
                            "Rebuilding only resets the ephemeral guest runtime (global apt/pip/npm packages). Your workspaces, sessions, settings, and credentials are preserved.",
                        )
                    } else {
                        tr(
                            "运行环境本身完好，本次为进程运行中退出（系统回收内存或扩展异常）。直接点击「重试启动」即可恢复。",
                            "The runtime environment is intact; the process exited while running (system memory reclaim or extension error). Tap Retry to recover.",
                        )
                    },
                )
            }

            Boot.Ready -> Unit
        }
    }
}

@Composable
private fun BootHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        color = PiTheme.palette.text,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BootParagraph(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = PiTheme.text.prose,
        color = PiTheme.palette.muted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BootErrorCard(message: String, detail: String?) {
    val palette = PiTheme.palette
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.toolErrorBg,
        border = BorderStroke(1.dp, palette.error.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(palette.error.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = palette.error,
                    )
                }
                Text(
                    text = tr("诊断信息", "Diagnostics"),
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.error,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.error.copy(alpha = 0.14f))
                        .clickable {
                            val dump = buildString {
                                append(message)
                                if (!detail.isNullOrBlank()) {
                                    append("\n\n")
                                    append(detail)
                                }
                            }
                            clipboard.setText(AnnotatedString(dump))
                            copied = true
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = palette.error,
                    )
                    Text(
                        text = if (copied) tr("已复制", "Copied") else tr("复制详情", "Copy details"),
                        style = PiTheme.text.meta,
                        color = palette.error,
                    )
                }
            }
            Text(
                text = message,
                style = PiTheme.text.prose,
                color = palette.text,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = PiTheme.text.monoSmall,
                    color = palette.bodyOnTool,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun BootButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    val bg = if (destructive) palette.error.copy(alpha = 0.16f) else palette.accent
    val fg = if (destructive) palette.error else palette.contrastOn(palette.accent)
    val border = if (destructive) BorderStroke(1.dp, palette.error.copy(alpha = 0.42f)) else null
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = bg,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!destructive) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = fg,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
            )
        }
    }
}

@Composable
private fun StepBar(step: RuntimeProvisioner.Step) {
    val palette = PiTheme.palette
    val total = step.total.coerceAtLeast(1)
    val reached = (step.index + 1).coerceIn(0, total)
    val targetFraction = reached.toFloat() / total.toFloat()
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(260),
        label = "bootProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(palette.borderMuted.copy(alpha = 0.4f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction.coerceIn(0.04f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(palette.accent),
        )
    }
}
