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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceAccessibilityService
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityStore
import app.pi.bridge.DeviceSafStore
import app.pi.bridge.DeviceShizuku
import app.pi.ui.PiTopBar
import app.pi.ui.rememberPiScreenVisible
import app.pi.ui.settings.PiSettingsCardShape
import app.pi.ui.settings.PiSettingsHairline
import app.pi.ui.settings.PiSettingsMetrics
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

                    Card {
                        CapabilityRow(
                            title = "屏幕",
                            description = "看、点、输入、滑动、截图",
                            checked = screen.enabled,
                            onCheckedChange = {
                                store.setEnabled(DeviceCapability.Accessibility, it)
                                revision += 1
                            },
                            hint = if (screen.enabled && !screen.usable) "无障碍未开启" else null,
                            actionLabel = "去设置",
                            onAction = openAccessibility,
                        )
                        PiSettingsHairline(inset = 0.dp)
                        CapabilityRow(
                            title = "系统与文件",
                            description = "文件、剪贴板、通知、分享",
                            checked = basic.enabled,
                            onCheckedChange = {
                                store.setEnabled(DeviceCapability.Basic, it)
                                revision += 1
                            },
                        )
                        PiSettingsHairline(inset = 0.dp)
                        CapabilityRow(
                            title = "Shell",
                            description = "命令行（危险）",
                            checked = shell.enabled,
                            onCheckedChange = {
                                store.setEnabled(DeviceCapability.Shell, it)
                                revision += 1
                            },
                            danger = true,
                            hint = if (shell.enabled && !shizuku.optBoolean("ready")) "Shizuku 未就绪" else null,
                            actionLabel = "去授权",
                            onAction = requestShizuku,
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
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal, vertical = PiSettingsMetrics.rowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Icon(Icons.Filled.Security, null, tint = PiTheme.palette.muted, modifier = Modifier.size(PiSettingsMetrics.searchIconSize))
        Column(Modifier.weight(1f)) {
            Text(
                "设备能力",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${states.count { it.usable }}/${states.size} 组能力可用",
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("查看", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.Filled.KeyboardArrowRight, null, modifier = Modifier.size(PiSettingsMetrics.chevronSize), tint = PiTheme.palette.muted)
    }
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

@Composable
private fun CapabilityRow(
    title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    danger: Boolean = false, hint: String? = null, actionLabel: String = "", onAction: () -> Unit = {},
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (danger) PiTheme.palette.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (hint != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hint, style = PiTheme.text.meta, color = PiTheme.palette.warning)
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = PiSettingsMetrics.pageHorizontal,
            vertical = PiSettingsMetrics.notePaddingVertical,
        ),
        shape = PiSettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(PiSettingsMetrics.cardPadding)) { content() }
    }
}

private fun openShizukuManager(context: android.content.Context): Boolean = runCatching {
    val intent = context.packageManager.getLaunchIntentForPackage(DeviceShizuku.MANAGER_PACKAGE) ?: return false
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)
