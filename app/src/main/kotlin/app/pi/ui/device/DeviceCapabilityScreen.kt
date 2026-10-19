package app.pi.ui.device

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceAccessibilityService
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityStore
import app.pi.bridge.DeviceSafStore
import app.pi.bridge.DeviceShizuku
import app.pi.ui.PiTopBar
import app.pi.ui.rememberPiScreenVisible
import app.pi.ui.settings.PiSettingsCard
import app.pi.ui.settings.PiSettingsEntryRow
import app.pi.ui.settings.PiSettingsHairline
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsToggleRow
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 设备能力页（docs/device-bridge-v2.md §5）：4 个状态点、3 行能力开关。开关的权威仍是 [DeviceCapabilityStore]。 */
@Composable
fun DeviceCapabilityScreen(
    store: DeviceCapabilityStore,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val safStore = remember { DeviceSafStore.get(context) }
    var accessibility by remember { mutableStateOf(DeviceAccessibilityService.stateName(context)) }
    var shizuku by remember { mutableStateOf(DeviceShizuku.status(context)) }
    var notification by remember { mutableStateOf(store.hasNotificationPermission()) }
    var grants by remember { mutableStateOf(safStore.grants()) }
    var revision by remember { mutableStateOf(0) }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            safStore.add(uri, null)
            grants = safStore.grants()
        }
    }
    val notificationRequester = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notification = store.hasNotificationPermission()
    }

    // 三态读数会由系统在屏幕外改动，所以只在可见时轮询；开关本身由 revision 驱动重组。
    val visible = rememberPiScreenVisible()
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                accessibility = DeviceAccessibilityService.stateName(context)
                shizuku = DeviceShizuku.status(context)
                notification = store.hasNotificationPermission()
                grants = safStore.grants()
            }
            delay(1500L)
        }
    }

    val screen = remember(revision, accessibility) { store.state(DeviceCapability.Accessibility) }
    val basic = remember(revision) { store.state(DeviceCapability.Basic) }
    val shell = remember(revision, shizuku) { store.state(DeviceCapability.Shell) }

    val openAccessibility: () -> Unit = {
        runCatching { context.startActivity(DeviceAccessibilityService.settingsIntent()) }
    }
    val requestShizuku: () -> Unit = {
        when {
            !DeviceShizuku.isInstalled(context) -> Unit
            !DeviceShizuku.binderAlive() -> {
                openShizukuManager(context)
            }
            else -> {
                DeviceShizuku.requestPermission()
            }
        }
    }
    val openNotification: () -> Unit = {
        if (notification) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        } else {
            notificationRequester.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = "设备能力", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp + contentPadding.calculateBottomPadding()),
        ) {
            item {
                Column {
                    Row(
                        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StatusDot("无障碍", accessibility == "connected", openAccessibility)
                        StatusDot("Shizuku", shizuku.optBoolean("ready"), requestShizuku)
                        StatusDot("通知", notification, openNotification)
                        StatusDot("目录", grants.isNotEmpty()) { directoryPicker.launch(null) }
                    }

                    // 与设置面**同一张卡、同一套行**（`PiSettingsCard` + 布尔行）：
                    // 标题 15/500、说明 12 灰一行、开关贴右，行间 1px hairline。
                    // 缺权限时那句提示并进说明行（警示色），不再有第二行与行内按钮 ——
                    // 去系统设置的入口就是上面那 4 个状态点。
                    PiSettingsCard {
                        PiSettingsToggleRow(
                            title = "屏幕",
                            supporting = if (screen.enabled && !screen.usable) {
                                "无障碍未开启 · 点上方圆点去设置"
                            } else {
                                "看、点、输入、滑动、截图"
                            },
                            supportingTone = if (screen.enabled && !screen.usable) {
                                PiTheme.palette.warning
                            } else {
                                null
                            },
                            checked = screen.enabled,
                            onCheckedChange = {
                                store.setEnabled(DeviceCapability.Accessibility, it)
                                revision += 1
                            },
                        )
                        PiSettingsHairline()
                        PiSettingsToggleRow(
                            title = "系统与文件",
                            supporting = "文件、剪贴板、通知、分享",
                            checked = basic.enabled,
                            onCheckedChange = {
                                store.setEnabled(DeviceCapability.Basic, it)
                                revision += 1
                            },
                        )
                        PiSettingsHairline()
                        PiSettingsToggleRow(
                            title = "Shell",
                            supporting = if (shell.enabled && !shizuku.optBoolean("ready")) {
                                "Shizuku 未就绪 · 点上方圆点去授权"
                            } else {
                                "命令行（危险）"
                            },
                            supportingTone = if (shell.enabled && !shizuku.optBoolean("ready")) {
                                PiTheme.palette.warning
                            } else {
                                null
                            },
                            checked = shell.enabled,
                            onCheckedChange = {
                                store.setEnabled(DeviceCapability.Shell, it)
                                revision += 1
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A settings-page entry row for L0-12, so the settings stack can point at this
 * screen without knowing anything about it.
 */
@Composable
fun DeviceCapabilityEntryRow(store: DeviceCapabilityStore, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val states = store.states()
    // 首页入口行与统一行同一个构件：图标 + 标题 + 一行说明 + chevron，
    // 没有「查看」这类文字尾巴。
    PiSettingsEntryRow(
        title = "设备能力",
        supporting = "${states.count { it.usable }}/${states.size} 组能力可用",
        onClick = onClick,
        modifier = modifier,
        leadingIcon = Icons.Filled.Security,
    )
}

@Composable
private fun StatusDot(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.primary else PiTheme.palette.muted),
        )
    }
}

private fun openShizukuManager(context: android.content.Context): Boolean = runCatching {
    val intent = context.packageManager.getLaunchIntentForPackage(DeviceShizuku.MANAGER_PACKAGE) ?: return false
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)
