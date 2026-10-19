package app.pi.ui.device

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceAccessibilityService
import app.pi.bridge.DeviceApprovalLedger
import app.pi.bridge.DeviceBridgeController
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityStore
import app.pi.bridge.DeviceSafStore
import app.pi.bridge.DeviceShellGuard
import app.pi.bridge.DeviceShizuku
import app.pi.bridge.DeviceWorkspace
import app.pi.ui.PiTopBar
import app.pi.ui.PiTopBarIcon
import app.pi.ui.components.PiMixedLine
import app.pi.ui.rememberPiScreenVisible
import app.pi.ui.settings.PiSettingsCardShape
import app.pi.ui.settings.PiSettingsHairline
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSectionHeader
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设备能力页（docs/device-bridge-v2.md §5）。
 *
 * 主界面只回答两件事：**哪一组开着**、**缺的授权去哪里点**。设备桥状态、路径、
 * Shizuku 安装步骤、边界说明与审批记录全部收进底部「高级」折叠区，展开才可见。
 *
 * 开关的权威仍然是 [DeviceCapabilityStore]：桥对每个请求现读它，所以在这里改完立即
 * 生效。随包扩展注册哪些 `android_*` 工具要等下一次加载扩展才重算（`registerTool`
 * 只在扩展工厂跑的时候登记一次），这句话放在「高级」里，主界面不占篇幅。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCapabilityScreen(
    store: DeviceCapabilityStore,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val safStore = remember { DeviceSafStore.get(context) }
    // For the bridge's start button, which does file work (see its onClick).
    val scope = rememberCoroutineScope()

    // Status is polled rather than observed: the accessibility service, Shizuku and
    // the workspace can all change outside this screen while it is in the
    // foreground, and the user expects to come back and see the truth.
    var accessibilityState by remember { mutableStateOf(DeviceAccessibilityService.stateName(context)) }
    var bridgeStatus by remember { mutableStateOf(DeviceBridgeController.statusReport()) }
    var bridgeRunning by remember { mutableStateOf(DeviceBridgeController.isRunning()) }
    var revision by remember { mutableStateOf(0) }
    var grants by remember { mutableStateOf(safStore.grants()) }
    var shizuku by remember { mutableStateOf(DeviceShizuku.status(context)) }
    var workspaceHost by remember { mutableStateOf(DeviceWorkspace.shellPath()) }
    var workspaceGuest by remember { mutableStateOf(DeviceWorkspace.guestPath()) }
    var storagePermissionsNeeded by remember { mutableStateOf(!store.hasLegacyStoragePermission()) }
    var cameraPermission by remember { mutableStateOf(store.hasCameraPermission()) }
    var locationPermission by remember { mutableStateOf(store.hasLocationPermission()) }
    var notificationPermission by remember { mutableStateOf(store.hasNotificationPermission()) }
    var approvals by remember { mutableStateOf(DeviceApprovalLedger.summaryLines()) }
    var note by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val auditTail = remember(revision) { DeviceBridgeController.auditPrettyTail(5) }
    // Set at bridge start from the write-ahead trail: a request that started and
    // never produced a result is how an in-flight crash is told to the user.
    val aborted = remember(revision) { DeviceBridgeController.lastUnpairedReport() }

    // The SAF picker needs an Activity, and this screen is one. Persisting the URI
    // permission is what makes the grant survive a restart.
    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val grant = safStore.add(uri, null)
            grants = safStore.grants()
            note = if (grant == null) {
                "系统没有把这个目录的访问权限持久化（有些提供方不支持），重启后会失效。"
            } else {
                "已授权目录：${grant.name}。Agent 可以读写它里面的文件。"
            }
            revision += 1
        }
    }
    val permissionRequester = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        storagePermissionsNeeded = !store.hasLegacyStoragePermission()
        note = if (storagePermissionsNeeded) {
            "存储权限仍未授予；在这台设备上导出文件需要它。"
        } else {
            "存储权限已授予。"
        }
        revision += 1
    }

    // The endpoint-level runtime grants: camera (手电筒), the location pair, and
    // POST_NOTIFICATIONS. The cards below must be able to ask — and then re-read
    // the answer from the store instead of assuming the dialog was accepted.
    val runtimePermissionRequester = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraPermission = store.hasCameraPermission()
        locationPermission = store.hasLocationPermission()
        notificationPermission = store.hasNotificationPermission()
        val denied = result.filterValues { granted -> !granted }.keys
        note = if (denied.isEmpty()) {
            "权限已授予。"
        } else {
            "以下权限仍未授予：${denied.joinToString("、") { permissionLabel(it) }}。" +
                "系统在拒绝两次后可能不再弹窗，需要到 系统设置 → 应用 → PI → 权限 中手动打开。"
        }
        revision += 1
    }

    // Registered once per entry into this screen, separately from the refresh loop
    // below on purpose: `DeviceShizuku.addPermissionResultListener` has no removal
    // API (it appends to a list), so re-registering it on every foreground/background
    // switch would add one listener per switch. (That list already grows once per
    // visit — a pre-existing leak, recorded in `docs/lifecycle-and-timers.md` §4.)
    LaunchedEffect(Unit) {
        DeviceShizuku.addPermissionResultListener { granted ->
            note = if (granted) "Shizuku 授权成功：Shell 现在以 ADB 身份运行。" else "Shizuku 授权被拒绝。"
        }
    }

    // The read-only refresh loop. Keyed on visibility, not on the composition: while
    // the app was in the background this ran regardless — a Shizuku binder call, the
    // accessibility service check, the bridge status, a workspace re-read and the
    // approval ledger, every 1.5 s, for a screen nobody could see.
    //
    // **On `Dispatchers.IO`, because one iteration is several binder round trips and
    // file reads**, not a state read: `DeviceAccessibilityService.stateName` asks the
    // AccessibilityManager, `DeviceShizuku.status` talks to the Shizuku service,
    // `DeviceWorkspace.refresh` re-reads the workspace from `settings.json`, the
    // `store.has*Permission` calls are package-manager/permission lookups and
    // `DeviceApprovalLedger.summaryLines` reads the ledger. The writes target Compose
    // state, which is safe from any thread, so only the reads had to move.
    val visible = rememberPiScreenVisible()
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                accessibilityState = DeviceAccessibilityService.stateName(context)
                bridgeRunning = DeviceBridgeController.isRunning()
                bridgeStatus = DeviceBridgeController.statusReport()
                shizuku = DeviceShizuku.status(context)
                DeviceWorkspace.refresh(context)
                workspaceHost = DeviceWorkspace.shellPath()
                workspaceGuest = DeviceWorkspace.guestPath()
                storagePermissionsNeeded = !store.hasLegacyStoragePermission()
                cameraPermission = store.hasCameraPermission()
                locationPermission = store.hasLocationPermission()
                notificationPermission = store.hasNotificationPermission()
                approvals = DeviceApprovalLedger.summaryLines()
            }
            delay(REFRESH_INTERVAL_MS)
        }
    }

    val openAccessibility: () -> Unit = {
        val opened = runCatching {
            context.startActivity(DeviceAccessibilityService.settingsIntent())
        }.isSuccess
        if (!opened) note = "无法打开无障碍设置。"
    }

    val requestShizuku: () -> Unit = {
        when {
            !DeviceShizuku.isInstalled(context) -> note = "未安装 Shizuku。"

            !DeviceShizuku.binderAlive() ->
                note = if (openShizukuManager(context)) {
                    "请在 Shizuku 里启动服务，再回来点一下。"
                } else {
                    "无法打开 Shizuku。"
                }

            !DeviceShizuku.requestPermission() -> note = "无法发起 Shizuku 授权。"

            else -> Unit
        }
    }

    val openNotification: () -> Unit = {
        if (notificationPermission) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }.isSuccess
            if (!opened) note = "无法打开通知设置。"
        } else {
            runtimePermissionRequester.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    val openDirectoryPicker: () -> Unit = { directoryPicker.launch(null) }

    // 这一屏同样是设置面的一层（`PiSettingsStack` 的 `deviceCapabilities`），顶栏也是手绘的
    // `PiTopBar`：顶边照设置面那一套补一次，见 `settingsPageTopInset`。
    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(
            title = "设备能力",
            onBack = onBack,
            actions = {
                PiTopBarIcon(
                    onClick = { revision += 1 },
                    contentDescription = "刷新状态",
                    icon = Icons.Filled.Refresh,
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = PiSpacing.unit + contentPadding.calculateBottomPadding()),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(
                            horizontal = PiSettingsMetrics.pageHorizontal,
                            vertical = PiSettingsMetrics.notePaddingVertical,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
                ) {
                    StatusPill(
                        label = "无障碍",
                        on = accessibilityState == "connected",
                        onClick = openAccessibility,
                    )
                    StatusPill(
                        label = "Shizuku",
                        on = shizuku.optBoolean("ready"),
                        onClick = requestShizuku,
                    )
                    StatusPill(
                        label = "通知",
                        on = notificationPermission,
                        onClick = openNotification,
                    )
                    StatusPill(
                        label = "已授权目录",
                        on = grants.isNotEmpty(),
                        onClick = openDirectoryPicker,
                    )
                }
            }

            note?.let { message ->
                item { InfoNote(message) }
            }

            item {
                val state = store.state(DeviceCapability.Accessibility)
                ScreenCard(
                    enabled = state.enabled,
                    onToggle = { enabled ->
                        store.setEnabled(DeviceCapability.Accessibility, enabled)
                        revision += 1
                    },
                    onOpenSettings = openAccessibility,
                )
            }

            item {
                val basic = store.state(DeviceCapability.Basic)
                val shell = store.state(DeviceCapability.Shell)
                SystemCard(
                    basicEnabled = basic.enabled,
                    onBasicToggle = { enabled ->
                        store.setEnabled(DeviceCapability.Basic, enabled)
                        revision += 1
                    },
                    shellEnabled = shell.enabled,
                    onShellToggle = { enabled ->
                        store.setEnabled(DeviceCapability.Shell, enabled)
                        revision += 1
                    },
                )
            }

            item {
                PiSettingsSectionHeader(
                    label = "高级",
                    onClick = { advanced = !advanced },
                    trailing = if (advanced) "收起" else "展开",
                )
            }

            if (advanced) {
                item {
                    InfoNote("开关立即生效；工具清单下次加载扩展重算。")
                }

                item {
                    PiSettingsSectionHeader("设备桥")
                    DeviceBridgeCard(
                        running = bridgeRunning,
                        status = bridgeStatus,
                        auditTail = auditTail,
                        aborted = aborted,
                        onStart = {
                            // Starting the bridge mints a token, walks the shipped extension
                            // assets, rewrites the token file and binds a socket. That is file
                            // and binder work, so it happens off the frame thread.
                            scope.launch {
                                val started = withContext(Dispatchers.IO) {
                                    DeviceBridgeController.start(context)
                                }
                                bridgeStatus = started
                                bridgeRunning = DeviceBridgeController.isRunning()
                                revision += 1
                            }
                        },
                    )
                }

                item {
                    PiSettingsSectionHeader("路径与权限")
                    PathsCard(
                        workspaceHost = workspaceHost,
                        workspaceGuest = workspaceGuest,
                        grants = grants,
                        onGrantDirectory = openDirectoryPicker,
                        onRevokeDirectory = { uri ->
                            safStore.remove(uri)
                            grants = safStore.grants()
                            revision += 1
                        },
                        storagePermissionsNeeded = storagePermissionsNeeded,
                        onRequestStoragePermission = {
                            val needed = store.legacyStoragePermissions()
                            if (needed.isEmpty()) {
                                note = "这台设备的 Android 版本不需要旧式存储权限。"
                            } else {
                                permissionRequester.launch(needed.toTypedArray())
                            }
                        },
                        notificationPermission = notificationPermission,
                        cameraPermission = cameraPermission,
                        locationPermission = locationPermission,
                        onRequestPermission = { names ->
                            runtimePermissionRequester.launch(names.toTypedArray())
                        },
                    )
                }

                item {
                    PiSettingsSectionHeader("Shizuku")
                    ShizukuCard(
                        shizuku = shizuku,
                        backendLabel = DeviceShellGuard.active().label,
                        onRequest = requestShizuku,
                        onOpen = {
                            if (!openShizukuManager(context)) note = "无法打开 Shizuku。"
                        },
                    )
                }

                item {
                    PiSettingsSectionHeader("边界")
                    BoundaryCard()
                }

                item {
                    PiSettingsSectionHeader("本会话的审批")
                    // Fed from the polling loop above: this item reads no other state,
                    // so a direct `DeviceApprovalLedger.summaryLines()` call would
                    // compose once and stay frozen while the screen is open.
                    ApprovalsCard(lines = approvals)
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
fun DeviceCapabilityEntryRow(
    store: DeviceCapabilityStore,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val states = store.states()
    val usable = states.count { it.usable }
    val summary = "$usable/${states.size} 组能力可用"
    // 这是设置首页卡片里的一行，所以它自己不再画底：v2 的行是
    // `padding:10px 12px`，前置图标 16 灰，标题 15/500，尾部值 + chevron 14。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Icon(
            Icons.Filled.Security,
            contentDescription = null,
            tint = PiTheme.palette.muted,
            modifier = Modifier.size(PiSettingsMetrics.searchIconSize),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "设备能力",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "查看",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.chevronSize),
            tint = PiTheme.palette.muted,
        )
    }
}

// ---------------------------------------------------------------------------
// the two cards
// ---------------------------------------------------------------------------

@Composable
private fun ScreenCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
            )
            Spacer(Modifier.width(PiSettingsMetrics.rowGap))
            Column(Modifier.weight(1f)) {
                Text(
                    "屏幕",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "读屏、点按、输入、滑动、按键、截图",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        TextButton(onClick = onOpenSettings) { Text("无障碍设置") }
    }
}

@Composable
private fun SystemCard(
    basicEnabled: Boolean,
    onBasicToggle: (Boolean) -> Unit,
    shellEnabled: Boolean,
    onShellToggle: (Boolean) -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
            )
            Spacer(Modifier.width(PiSettingsMetrics.rowGap))
            Column(Modifier.weight(1f)) {
                Text(
                    "系统与文件",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "文件、剪贴板、通知、分享",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = basicEnabled, onCheckedChange = onBasicToggle)
        }

        Spacer(Modifier.height(PiSpacing.inline))
        PiSettingsHairline(inset = 0.dp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Terminal,
                contentDescription = null,
                tint = PiTheme.palette.error,
                modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
            )
            Spacer(Modifier.width(PiSettingsMetrics.rowGap))
            Column(Modifier.weight(1f)) {
                Text(
                    "Shell",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Shell 是唯一会碰到系统身份的组：命令与路径都不检查，所以这一行用
                // 红色小字把风险写在开关旁边，而不是藏在「高级」里。
                Text(
                    "危险 · 默认关",
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.error,
                )
            }
            Switch(checked = shellEnabled, onCheckedChange = onShellToggle)
        }
    }
}

// ---------------------------------------------------------------------------
// advanced: bridge, paths, Shizuku, boundary, approvals
// ---------------------------------------------------------------------------

@Composable
private fun PathsCard(
    workspaceHost: String?,
    workspaceGuest: String,
    grants: List<DeviceSafStore.Grant>,
    onGrantDirectory: () -> Unit,
    onRevokeDirectory: (String) -> Unit,
    storagePermissionsNeeded: Boolean,
    onRequestStoragePermission: () -> Unit,
    notificationPermission: Boolean,
    cameraPermission: Boolean,
    locationPermission: Boolean,
    onRequestPermission: (List<String>) -> Unit,
) {
    Card {
        PathRow("工作区", workspaceHost ?: "尚未确定")
        PathRow("guest 内", workspaceGuest)
        PathRow("共享存储", "/sdcard")

        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            "已授权目录",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (grants.isEmpty()) {
            Text(
                "无。点「授权目录」添加。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (grant in grants) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    grant.name,
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { onRevokeDirectory(grant.uri) }) { Text("撤销") }
            }
        }
        TextButton(onClick = onGrantDirectory) { Text("授权目录") }

        if (storagePermissionsNeeded) {
            Text(
                "还需要存储权限写 Download。",
                style = MaterialTheme.typography.bodyMedium,
                color = PiTheme.palette.warning,
            )
            TextButton(onClick = onRequestStoragePermission) { Text("授予存储权限") }
        }

        Spacer(Modifier.height(PiSpacing.inline))
        PermissionRow(
            label = "通知",
            granted = notificationPermission,
            grantLabel = "授予通知权限",
            onGrant = { onRequestPermission(listOf(Manifest.permission.POST_NOTIFICATIONS)) },
        )
        PermissionRow(
            label = "相机",
            granted = cameraPermission,
            grantLabel = "授予相机权限",
            onGrant = { onRequestPermission(listOf(Manifest.permission.CAMERA)) },
        )
        PermissionRow(
            label = "定位",
            granted = locationPermission,
            grantLabel = "授予定位权限",
            onGrant = {
                onRequestPermission(
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        )
    }
}

@Composable
private fun PathRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PiSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    grantLabel: String,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PiSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (granted) "已授予" else "未授予",
                style = PiTheme.text.meta,
                color = if (granted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    PiTheme.palette.warning
                },
            )
        }
        if (!granted) {
            TextButton(onClick = onGrant) { Text(grantLabel) }
        }
    }
}

@Composable
private fun ShizukuCard(
    shizuku: JSONObject,
    backendLabel: String,
    onRequest: () -> Unit,
    onOpen: () -> Unit,
) {
    Card {
        Text(
            "当前后端：$backendLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!shizuku.optBoolean("ready")) {
            Spacer(Modifier.height(PiSpacing.inline))
            for (line in shizukuSteps(shizuku)) {
                Text(
                    "· $line",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val note = shizuku.optString("note")
        if (note.isNotEmpty()) {
            Text(
                note,
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onRequest,
                enabled = shizuku.optBoolean("binderAlive") || shizuku.optBoolean("installed"),
            ) { Text(if (shizuku.optBoolean("binderAlive")) "请求授权" else "启动并授权") }
            if (shizuku.optBoolean("installed")) {
                TextButton(onClick = onOpen) { Text("打开 Shizuku") }
            }
            if (shizuku.optBoolean("ready") && shizuku.optInt("uid", -1) == 0) {
                Text(
                    "以 root 运行",
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.warning,
                )
            }
        }
    }
}

@Composable
private fun BoundaryCard() {
    Card {
        for (line in BOUNDARY_LINES) {
            Text(
                "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            "硬性禁用：${DeviceShellGuard.blockedSummary().size} 条",
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// small pieces
// ---------------------------------------------------------------------------

@Composable
private fun StatusPill(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(PiShapes.badge)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSpacing.small),
    ) {
        Box(
            Modifier
                .size(PiSpacing.errorDot)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (on) MaterialTheme.colorScheme.primary else PiTheme.palette.muted),
        )
        Text(
            label,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * v2 的卡片（`06 §2`：圆角 10、`surfaceContainerLow` 底、无描边无阴影、左右 14px
 * 页边）。[loose] 给「设备桥卡」那一档 14px 内边距，其余卡是 12px。
 */
@Composable
private fun Card(loose: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.notePaddingVertical,
            ),
        shape = PiSettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(
                if (loose) PiSettingsMetrics.cardPaddingLoose else PiSettingsMetrics.cardPadding,
            ),
        ) { content() }
    }
}

@Composable
private fun InfoNote(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = PiSettingsMetrics.pageHorizontal,
                vertical = PiSettingsMetrics.notePaddingVertical,
            ),
        shape = PiSettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text,
            modifier = Modifier.padding(PiSettingsMetrics.cardPadding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceBridgeCard(
    running: Boolean,
    status: String,
    auditTail: List<String>,
    aborted: String?,
    onStart: () -> Unit,
) {
    Card(loose = true) {
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
                    "仅本机可访问，不对局域网开放。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusText(text = if (running) "已监听" else "未运行", positive = running)
        }
        if (!running) {
            Spacer(Modifier.height(PiSpacing.inline))
            TextButton(onClick = onStart) { Text("启动设备桥") }
        }
        if (aborted != null) {
            Spacer(Modifier.height(PiSpacing.inline))
            Text(
                "上次执行中异常终止：$aborted",
                style = MaterialTheme.typography.bodyMedium,
                color = PiTheme.palette.warning,
            )
        }
        val logPath = DeviceBridgeController.auditLogPath()
        if (logPath != null) {
            Spacer(Modifier.height(PiSpacing.gutter))
            // 两段声音（规则 #7）：「审计日志：」是我们的标注 → 系统字；路径是机器值 → 等宽。
            PiMixedLine(
                prefix = "审计日志：",
                machine = logPath,
                suffix = "",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (auditTail.isNotEmpty()) {
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                auditTail.joinToString("\n"),
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 设备桥卡的状态字；不带徽标框，避免高级区里再出现一颗独立的状态胶囊。 */
@Composable
private fun StatusText(text: String, positive: Boolean) {
    Text(
        text,
        style = PiTheme.text.meta,
        color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the pi-side permission gate reported for this session.
 *
 * [lines] is passed in rather than read here on purpose: this card is a
 * `LazyColumn` item and reads no other state, so calling
 * `DeviceApprovalLedger.summaryLines()` inside it would be composed once and never
 * re-evaluated — the ledger changes only when the guest extension POSTs to
 * `/app/gate/report`, which nothing in this composable observes. The polling loop
 * in [DeviceCapabilityScreen] is the signal, and it costs one volatile read.
 */
@Composable
private fun ApprovalsCard(lines: List<String>) {
    Card {
        for (line in lines) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The name a permission goes by on this screen.
 *
 * The platform reports its answer keyed by constants such as
 * `android.permission.POST_NOTIFICATIONS`; those are for logs, not for the reader,
 * so the one place a denial is reported back names them the way the cards do.
 * Anything unmapped falls back to the constant's last segment, which is still
 * better than the whole string and can never be empty.
 */
private fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.CAMERA -> "相机"
    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> "位置信息"
    Manifest.permission.POST_NOTIFICATIONS -> "通知"
    else -> permission.substringAfterLast('.')
}

/** Open the Shizuku manager app, if this device has one. */
private fun openShizukuManager(context: android.content.Context): Boolean = runCatching {
    val intent = context.packageManager.getLaunchIntentForPackage(DeviceShizuku.MANAGER_PACKAGE) ?: return false
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

/**
 * The self-serve Shizuku onboarding, one short line per step.
 *
 * Android 11+ is the reason this is onboarding and not a README: 无线调试 lets the
 * user start Shizuku on the phone itself, with no computer and no ADB cable —
 * which is the difference between "uid 2000 is possible" and "uid 2000 is what
 * this app actually gets". 每行 ≤20 字，展开「高级」才可见。
 */
private fun shizukuSteps(shizuku: JSONObject): List<String> {
    if (!shizuku.optBoolean("installed")) {
        return listOf(
            "装 Shizuku（不需要 root）",
            "装好回这里点授权",
        )
    }
    if (!shizuku.optBoolean("binderAlive")) {
        return listOf(
            "打开 Shizuku，点无线调试启动",
            "打开系统无线调试并配对",
            "回到这里请求 Shizuku 授权",
            "重启手机后需再启动一次",
        )
    }
    if (!shizuku.optBoolean("permissionGranted")) {
        return listOf("点「请求 Shizuku 授权」")
    }
    if (shizuku.optBoolean("preV11")) {
        return listOf("Shizuku 版本过旧，请升级")
    }
    return emptyList()
}

/** 边界说明：每行 ≤20 字，只在「高级」展开时出现。 */
private val BOUNDARY_LINES = listOf(
    "命令与路径都不检查",
    "成败只看身份（uid 2000）",
    "否则用应用自身身份",
    "危险操作第一次会确认",
    "工作区只是默认落点",
)

/**
 * How often the visible screen re-reads the facts that have no change channel.
 *
 * 1.5 s and only while visible: the values are all cheap reads (one binder call to
 * Shizuku, a few file stats), but they are reads, and none of them is worth doing
 * for a screen that is not on screen. There is no push channel for any of them —
 * `DeviceAccessibilityService`, the Shizuku binder and the bridge's audit file are
 * external to this app — which is why this is a poll and not an observer.
 */
private const val REFRESH_INTERVAL_MS = 1500L
