package app.pi.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.R
import app.pi.i18n.tr
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.RuntimeProvisioner.InstallMode
import app.pi.ui.Boot
import app.pi.ui.components.PiSpinner
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.contrastOn
import kotlin.math.roundToInt

/**
 * 首次安装、增量更新、无损修复与运行时重建引导页：
 *  - 与对话空白态一脉相承的双层边框品牌徽章 + 克制呼吸光晕；
 *  - 根据 [InstallMode] 展示准确语境文案（首次配置 / 增量更新 / 无损修复 / 重建环境）；
 *  - 连续丝滑进度条（含细粒度条数进度 + 柔和流光）与 4 阶段里程碑清单，覆盖从校验到引擎就绪全链路；
 *  - 失败态：结构化诊断卡 + 一键复制详情（含触觉反馈）+ 主操作重试与高危重建区分。
 */
@Composable
fun BootScreen(
    boot: Boot,
    onRetry: () -> Unit,
    onRebuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val haptic = LocalHapticFeedback.current
    val isDark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BootEmblem(
                working = boot is Boot.Working,
                failed = boot is Boot.Failed,
                isDark = isDark,
            )
            Spacer(Modifier.height(22.dp))

            when (boot) {
                Boot.Idle -> {
                    BootHeading(tr("准备本地 Pi 运行环境", "Ready to set up Pi"))
                    Spacer(Modifier.height(8.dp))
                    BootSubtitle(
                        tr(
                            "将在设备离线部署本地 Linux 容器与官方 Pi 引擎，无需 Root 权限。",
                            "Unpacks the local Linux container and official Pi engine offline on your device. No root required.",
                        ),
                    )
                    Spacer(Modifier.height(24.dp))
                    BootButton(
                        label = tr("开始准备", "Get started"),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRetry()
                        },
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                    )
                }

                is Boot.Working -> {
                    val step = boot.step
                    val (heading, subtitle) = modeCopy(step.mode)
                    BootHeading(heading)
                    Spacer(Modifier.height(6.dp))
                    BootSubtitle(subtitle)
                    Spacer(Modifier.height(22.dp))
                    ProvisionProgressCard(step = step)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = tr(
                            "本地离线运行 · 无需 Root · 请保持前台直至就绪",
                            "100% local & offline · No root · Keep app in foreground",
                        ),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                        textAlign = TextAlign.Center,
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
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRetry()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (boot.failure.rebuildOffered) {
                            BootButton(
                                label = tr("重建运行时", "Rebuild"),
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRebuild()
                                },
                                destructive = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    BootSubtitle(
                        if (boot.failure.rebuildOffered) {
                            tr(
                                "重建仅清理易失的容器运行时（apt/pip/npm 全局包）；你的工作区、会话、设置和登录凭证均完整保留。",
                                "Rebuilding only resets the ephemeral runtime (global apt/pip/npm packages). Your workspaces, sessions, settings, and credentials stay intact.",
                            )
                        } else {
                            tr(
                                "运行环境本身完好，本次为进程运行中退出（系统回收内存或扩展异常）。点击「重试启动」即可恢复。",
                                "The runtime environment is intact; the process exited while running. Tap Retry to recover.",
                            )
                        },
                    )
                }

                Boot.Ready -> Unit
            }
        }
    }
}

@Composable
private fun BootEmblem(
    working: Boolean,
    failed: Boolean,
    isDark: Boolean,
) {
    val palette = PiTheme.palette
    val outerShape = RoundedCornerShape(22.dp)
    val innerShape = RoundedCornerShape(19.dp)

    if (failed) {
        Surface(
            shape = outerShape,
            color = palette.error.copy(alpha = 0.10f),
            border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
            modifier = Modifier.size(68.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = palette.error,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        return
    }

    val pulseTransition = rememberInfiniteTransition(label = "boot-emblem-pulse")
    val haloScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo-scale",
    )
    val haloAlpha by pulseTransition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo-alpha",
    )

    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (working) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .graphicsLayer {
                        scaleX = haloScale
                        scaleY = haloScale
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        width = 1.5.dp,
                        color = palette.accent.copy(alpha = haloAlpha * 2.2f),
                        shape = RoundedCornerShape(24.dp),
                    )
                    .background(palette.accent.copy(alpha = haloAlpha)),
            )
        }
        Surface(
            shape = outerShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = if (isDark) 0.dp else 5.dp,
            border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Image(
                painter = painterResource(id = R.drawable.pi_welcome_emblem),
                contentDescription = tr("Pi 徽章", "Pi emblem"),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(3.dp)
                    .size(66.dp)
                    .clip(innerShape),
            )
        }
    }
}

@Composable
private fun ProvisionProgressCard(step: RuntimeProvisioner.Step) {
    val palette = PiTheme.palette
    val targetProgress = step.fraction.coerceIn(0.02f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "boot-progress",
    )
    val percent = (animatedProgress * 100f).roundToInt().coerceIn(0, 100)
    val activeStage = stageIndexFor(step)

    val stages = listOf(
        tr("校验运行包与存储空间", "Verify packages & storage"),
        tr("解压 Linux 环境与工具链", "Unpack Linux & toolchain"),
        tr("部署官方 Pi SDK 引擎", "Deploy official Pi SDK"),
        tr("环境自检并启动引擎", "Self-check & launch engine"),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 当前活跃步骤 + 百分比
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = step.label,
                        style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!step.detail.isNullOrBlank()) {
                        Text(
                            text = step.detail,
                            style = PiTheme.text.monoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = "$percent%",
                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.accent,
                )
            }

            // 2. 丝滑连续进度条（带柔和流光）
            ContinuousProgressBar(
                progress = animatedProgress,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                fillColor = palette.accent,
            )

            HorizontalDivider(
                thickness = PiSpacing.hairlineThin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            )

            // 3. 四阶段清晰里程碑清单
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                stages.forEachIndexed { idx, title ->
                    MilestoneRow(
                        title = title,
                        completed = idx < activeStage,
                        active = idx == activeStage,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinuousProgressBar(
    progress: Float,
    trackColor: Color,
    fillColor: Color,
) {
    val sheenTransition = rememberInfiniteTransition(label = "boot-bar-sheen")
    val sheenOffset by sheenTransition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheen-offset",
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp)),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = radius,
        )
        val fillWidth = (size.width * progress.coerceIn(0f, 1f)).coerceAtLeast(size.height)
        drawRoundRect(
            color = fillColor,
            size = Size(fillWidth, size.height),
            cornerRadius = radius,
        )
        if (fillWidth > size.height * 2f) {
            val shimmerCenter = fillWidth * sheenOffset
            val shimmerHalf = (fillWidth * 0.24f).coerceAtLeast(24f)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.28f),
                        Color.Transparent,
                    ),
                    startX = shimmerCenter - shimmerHalf,
                    endX = shimmerCenter + shimmerHalf,
                ),
                size = Size(fillWidth, size.height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun MilestoneRow(
    title: String,
    completed: Boolean,
    active: Boolean,
) {
    val palette = PiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                completed -> {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(palette.success.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = palette.success,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
                active -> {
                    PiSpinner(
                        size = 14.dp,
                        strokeWidth = 1.7.dp,
                        color = palette.accent,
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.meta.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = when {
                active -> MaterialTheme.colorScheme.onSurface
                completed -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> palette.muted.copy(alpha = 0.72f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (active) {
            Text(
                text = tr("进行中", "Active"),
                style = PiTheme.text.monoSmall,
                color = palette.accent,
            )
        }
    }
}

private fun stageIndexFor(step: RuntimeProvisioner.Step): Int = when (step.phaseKey) {
    "audit", "migrate", "prepare", "rebuild" -> 0
    "ubuntu", "node", "rg", "fd", "git", "runtime" -> 1
    "engine", "configure", "finalize" -> 2
    "selfcheck", "launch" -> 3
    else -> when {
        step.fraction < 0.20f -> 0
        step.fraction < 0.62f -> 1
        step.fraction < 0.86f -> 2
        else -> 3
    }
}

private fun modeCopy(mode: InstallMode): Pair<String, String> = when (mode) {
    InstallMode.FirstInstall -> tr("正在配置 Pi 本地环境", "Setting up Pi") to tr(
        "首次启动会在本机离线解压 Linux 与官方 Pi 引擎，完成后日常启动只需数秒。",
        "First launch unpacks local Linux and the official Pi engine offline. Future launches take seconds.",
    )
    InstallMode.Update -> tr("正在更新 Pi 运行环境", "Updating Pi runtime") to tr(
        "仅增量覆盖变更组件，你的工作区、会话记录与自定义环境均完整保留。",
        "Incrementally updating changed components while preserving all workspaces, sessions, and packages.",
    )
    InstallMode.Repair -> tr("正在无损修复运行环境", "Repairing runtime") to tr(
        "重新校验并补齐系统与引擎文件，不会删除你的用户数据或已装工具。",
        "Restoring runtime files safely without deleting your workspaces, sessions, or user packages.",
    )
    InstallMode.Rebuild -> tr("正在重建运行环境", "Rebuilding runtime") to tr(
        "清空易失运行时并重新部署；工作区、会话、设置与登录凭证均不受影响。",
        "Rebuilding ephemeral runtime cleanly. Workspaces, sessions, settings, and credentials remain intact.",
    )
}

@Composable
private fun BootHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BootSubtitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = PiTheme.text.prose,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BootErrorCard(message: String, detail: String?) {
    val palette = PiTheme.palette
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.35f)),
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(palette.error.copy(alpha = 0.16f)),
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
                        .background(palette.error.copy(alpha = 0.12f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val dump = buildString {
                                append(message)
                                if (!detail.isNullOrBlank()) {
                                    append("\n\n")
                                    append(detail)
                                }
                            }
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("Pi Boot Diagnostics", dump))
                            copied = true
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = PiTheme.text.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
    icon: ImageVector? = Icons.Filled.Refresh,
) {
    val palette = PiTheme.palette
    val shape = RoundedCornerShape(12.dp)
    val bg = if (destructive) palette.error.copy(alpha = 0.12f) else palette.accent
    val fg = if (destructive) palette.error else palette.contrastOn(palette.accent)
    val border = if (destructive) BorderStroke(PiSpacing.hairlineThin, palette.error.copy(alpha = 0.38f)) else null
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
            if (icon != null && !destructive) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = fg,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
