package app.pi.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceAccessibilityService
import app.pi.bridge.DeviceBridgeController
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityState
import app.pi.bridge.DeviceCapabilityStore
import app.pi.bridge.DeviceShellGuard
import app.pi.ui.components.PiSectionHeader
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.delay

/**
 * Device capability authorization (docs/pi-android-ui-spec.md §5.6).
 *
 * One card per capability group, each showing: current state, what the agent would
 * be able to do with it, a switch, and a 「本会话暂时禁用」 quick switch. The screen
 * also carries the bridge's own status, because "the capability is on but the
 * accessibility service is not running" is a state the user must be able to see and
 * act on — that distinction is the difference between a switch that works and one
 * that only looks like it does.
 *
 * The authority is [DeviceCapabilityStore]: it persists the switches and the HTTP
 * server re-reads it on every request, so a change here takes effect immediately —
 * no restart, no reload. There is deliberately no "apply" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCapabilityScreen(
    store: DeviceCapabilityStore,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current

    // Status is polled rather than observed: the accessibility service can be
    // toggled in the system settings while this screen is in the foreground, and
    // the user expects to come back and see the truth.
    var accessibilityRunning by remember { mutableStateOf(DeviceAccessibilityService.isRunning()) }
    var bridgeStatus by remember { mutableStateOf(DeviceBridgeController.statusReport()) }
    var bridgeRunning by remember { mutableStateOf(DeviceBridgeController.isRunning()) }
    var revision by remember { mutableStateOf(0) }
    val auditTail = remember(revision) { DeviceBridgeController.auditTail(5) }

    LaunchedEffect(Unit) {
        while (true) {
            accessibilityRunning = DeviceAccessibilityService.isRunning()
            bridgeRunning = DeviceBridgeController.isRunning()
            bridgeStatus = DeviceBridgeController.statusReport()
            delay(1500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设备能力") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { revision += 1 }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新状态")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PiSpacing.unit + contentPadding.calculateBottomPadding()),
        ) {
            item {
                PiSectionHeader("设备桥")
                DeviceBridgeCard(
                    running = bridgeRunning,
                    status = bridgeStatus,
                    auditTail = auditTail,
                    onStart = {
                        bridgeStatus = DeviceBridgeController.start(context)
                        bridgeRunning = DeviceBridgeController.isRunning()
                        revision += 1
                    },
                )
            }

            item {
                PiSectionHeader("能力授权")
                InfoNote(
                    "能力默认关闭，逐组授权。「基础」组默认开启，因为它只做用户看得见的事" +
                        "（剪贴板、通知、打开链接、分享）。被关闭的能力不会静默失效：Agent 会收到明确原因，" +
                        "并被要求把原因和开启位置原样告诉你。",
                )
            }

            items(DeviceCapability.entries.toList()) { capability ->
                val state = store.state(capability)
                DeviceCapabilityCard(
                    state = state,
                    accessibilityRunning = accessibilityRunning,
                    onToggle = { enabled ->
                        store.setEnabled(capability, enabled)
                        revision += 1
                    },
                    onSessionToggle = { disabled ->
                        store.setSessionDisabled(capability, disabled)
                        revision += 1
                    },
                    onOpenSystemSettings = {
                        runCatching { context.startActivity(DeviceAccessibilityService.settingsIntent()) }
                    },
                )
            }

            item {
                PiSectionHeader("Shell 策略")
                ShellPolicyCard()
            }

            item {
                Spacer(Modifier.height(PiSpacing.unit))
                InfoNote(
                    "所有设备操作都会写入本地审计日志（不含内容本身），可在上面的状态卡里看到最近几条。" +
                        "紧急情况下可以直接关闭对应能力的开关，或停用系统的无障碍服务 —— 两者都会立刻生效。",
                )
            }
        }
    }
}

/**
 * A settings-page entry row for L0-12, so the settings stack can point at this
 * screen without knowing anything about it.
 */
@Composable
fun DeviceCapabilityEntryRow(
    store: DeviceCapabilityStore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val states = store.states()
    val usable = states.count { it.usable }
    val summary = "$usable/${states.size} 组能力可用"
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = PiSpacing.screen, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("设备能力", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "查看",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// cards
// ---------------------------------------------------------------------------

@Composable
private fun DeviceBridgeCard(
    running: Boolean,
    status: String,
    auditTail: List<String>,
    onStart: () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "设备桥",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "仅监听 127.0.0.1（不对局域网开放），调用方需要 guest 内 token 文件中的令牌。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(text = if (running) "已监听" else "未运行", positive = running)
        }
        if (!running) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onStart) { Text("启动设备桥") }
        }
        val logPath = DeviceBridgeController.auditLogPath()
        if (logPath != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "审计日志：$logPath",
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (auditTail.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                auditTail.joinToString("\n"),
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCapabilityCard(
    state: DeviceCapabilityState,
    accessibilityRunning: Boolean,
    onToggle: (Boolean) -> Unit,
    onSessionToggle: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val capability = state.capability
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconFor(capability),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(capability.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    capability.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(
                text = when {
                    !state.enabled -> "已关闭"
                    !state.enabledForSession -> "本会话禁用"
                    state.usable -> "可用"
                    else -> "待授权"
                },
                positive = state.usable,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.enabled) "Agent 现在可以使用这组能力" else "Agent 无法使用这组能力",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = onToggle)
        }

        if (state.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "本会话暂时禁用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "只影响当前会话，重启后按上面的开关执行。",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = !state.enabledForSession,
                    onCheckedChange = onSessionToggle,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "这组能力让 Agent 能做什么：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        for (line in capability.allows) {
            Text(
                "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (capability) {
            DeviceCapability.Accessibility -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (accessibilityRunning) {
                        "系统无障碍服务：运行中。"
                    } else {
                        "系统无障碍服务：未运行 —— 即使上面的开关打开，Agent 也无法读取或操作屏幕。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (accessibilityRunning) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                TextButton(onClick = onOpenSystemSettings) { Text("前往系统设置") }
                InfoNote(
                    "无障碍服务能看到当前屏幕上的所有文本（包括密码框以外的输入内容与通知），并代替你点按。" +
                        "只在你需要 Agent 操作手机时开启，用完可以关闭。",
                )
            }

            DeviceCapability.Storage -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已授权目录：无（本版本用 MediaStore 写入公共 Download，不需要 SAF 授权，也读不到其他应用的文件）",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DeviceCapability.Shell -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前后端：应用自身身份（非 uid=2000）。Shizuku / ADB 无线调试配对尚未接入，" +
                        "因此读不到其他应用与系统私有状态。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DeviceCapability.Sensors -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "定位还需要系统授予「位置信息」权限；手电筒在部分设备上需要相机权限。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DeviceCapability.Basic -> Unit
        }

        if (!state.usable && state.enabled && state.reason != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ShellPolicyCard() {
    Card {
        Text(
            "无论谁授权，下面这些都不会执行：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        for (line in DeviceShellGuard.policySummary()) {
            Text(
                "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "危险操作（结束应用、Shell、分享、打开链接、向输入框写入、跨沙箱读写文件）每次都会请求确认；" +
                "没有确认通道时会直接拒绝，而不是默认允许。",
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// small pieces
// ---------------------------------------------------------------------------

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.screen, vertical = 6.dp),
        shape = PiShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun StatusBadge(text: String, positive: Boolean) {
    val color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = PiShapes.badge, color = color.copy(alpha = 0.16f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun InfoNote(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSpacing.screen, vertical = 6.dp),
        shape = PiShapes.cardInner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconFor(capability: DeviceCapability): ImageVector = when (capability) {
    DeviceCapability.Basic -> Icons.Filled.PhoneAndroid
    DeviceCapability.Storage -> Icons.Filled.Folder
    DeviceCapability.Accessibility -> Icons.Filled.TouchApp
    DeviceCapability.Sensors -> Icons.Filled.Security
    DeviceCapability.Shell -> Icons.Filled.Terminal
}
