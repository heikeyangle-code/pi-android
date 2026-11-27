package app.pi.ui.device

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceAccessibilityService
import app.pi.bridge.DeviceBridgeController
import app.pi.bridge.DeviceBridgeRouter
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityState
import app.pi.bridge.DeviceCapabilityStore
import app.pi.bridge.DeviceShizuku
import app.pi.bridge.PiNotificationListener
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.components.PiDialogBody
import app.pi.ui.components.PiDialogTitle
import app.pi.ui.components.PiType
import app.pi.ui.rememberPiScreenVisible
import app.pi.ui.settings.PiSettingsCard
import app.pi.ui.settings.PiSettingsCardShape
import app.pi.ui.settings.PiSettingsChip
import app.pi.ui.settings.PiSettingsHairline
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.settings.PiSettingsSwitch
import app.pi.ui.settings.settingsPageTopInset
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 手机设备能力控制台（统一重构版）：
 *  - 顶部状态卡：实时 0-Token / 已挂载工具数 + 本地桥接状态 + 一键全部关闭
 *  - 系统服务与权限 2×2 就绪面板（全部 Binder / PackageManager IPC 仅在 Dispatchers.IO 执行）
 *  - 3 组能力开关（屏幕自动化 / 系统交互与硬件 / 设备 Shell），清晰展示每组实际挂载的 `android_*` 工具与本会话暂停控制
 *  - 底部可折叠「桥接服务与审计日志」诊断卡：查看本地桥接状态、一键重启桥接与最近调用审计
 */
@Composable
fun DeviceCapabilityScreen(
    store: DeviceCapabilityStore,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val palette = PiTheme.palette

    var revision by remember { mutableIntStateOf(0) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var restartingBridge by remember { mutableStateOf(false) }
    var showShizukuHelp by remember { mutableStateOf(false) }

    var snapshot by remember {
        mutableStateOf(
            DeviceCapPollSnapshot(
                accessibility = "checking",
                notifyListener = "checking",
                shizuku = JSONObject(),
                notification = false,
                hasLocation = false,
                hasCamera = false,
                screen = DeviceCapabilityState(DeviceCapability.Accessibility, false, true, false, null),
                basic = DeviceCapabilityState(DeviceCapability.Basic, false, true, false, null),
                shell = DeviceCapabilityState(DeviceCapability.Shell, false, true, false, null),
                bridgeRunning = DeviceBridgeController.isRunning(),
                bridgeReport = DeviceBridgeController.statusReport(),
                tokenPresent = DeviceBridgeController.currentToken() != null,
                lastUnpaired = DeviceBridgeController.lastUnpairedReport(),
                auditTail = emptyList(),
            ),
        )
    }

    fun syncSnapshotFile() {
        scope.launch(Dispatchers.IO) {
            DeviceBridgeController.refreshTokenSnapshot(context)
        }
    }

    val permissionRequester = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        syncSnapshotFile()
        revision += 1
    }

    val visible = rememberPiScreenVisible()
    LaunchedEffect(visible, revision, diagnosticsExpanded) {
        if (!visible) return@LaunchedEffect
        while (true) {
            val fresh = withContext(Dispatchers.IO) {
                DeviceCapPollSnapshot(
                    accessibility = DeviceAccessibilityService.stateName(context),
                    notifyListener = PiNotificationListener.stateName(context),
                    shizuku = DeviceShizuku.status(context),
                    notification = store.hasNotificationPermission(),
                    hasLocation = store.hasLocationPermission(),
                    hasCamera = store.hasCameraPermission(),
                    screen = store.state(DeviceCapability.Accessibility),
                    basic = store.state(DeviceCapability.Basic),
                    shell = store.state(DeviceCapability.Shell),
                    bridgeRunning = DeviceBridgeController.isRunning(),
                    bridgeReport = DeviceBridgeController.statusReport(),
                    tokenPresent = DeviceBridgeController.currentToken() != null,
                    lastUnpaired = DeviceBridgeController.lastUnpairedReport(),
                    auditTail = if (diagnosticsExpanded) DeviceBridgeController.auditPrettyTail(8) else emptyList(),
                )
            }
            snapshot = fresh
            delay(1500L)
        }
    }

    val allStates = listOf(snapshot.screen, snapshot.basic, snapshot.shell)
    val usableCount = allStates.count { it.usable }
    val enabledCount = allStates.count { it.enabled && it.enabledForSession }
    val mountedToolCount = (if (snapshot.screen.usable) 1 else 0) +
        (if (snapshot.basic.usable) 3 else 0) +
        (if (snapshot.shell.usable) 1 else 0) +
        (if (usableCount > 0) 1 else 0) // android_status

    val shizukuReady = snapshot.shizuku.optBoolean("ready")
    val shizukuInstalled = snapshot.shizuku.optBoolean("installed")
    val shizukuBinder = snapshot.shizuku.optBoolean("binderAlive")
    val shizukuUid = snapshot.shizuku.optInt("uid", -1)

    val openAccessibility: () -> Unit = {
        runCatching { context.startActivity(DeviceAccessibilityService.settingsIntent()) }
    }
    val openNotificationListener: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
    val requestShizuku: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            val action = withContext(Dispatchers.IO) {
                val installed = DeviceShizuku.isInstalled(context)
                val alive = DeviceShizuku.binderAlive()
                val intent = if (installed && !alive) DeviceShizuku.launchManagerIntent(context) else null
                Triple(installed, alive, intent)
            }
            val (installed, alive, launchIntent) = action
            when {
                !installed -> showShizukuHelp = true
                !alive -> {
                    if (launchIntent != null) {
                        runCatching { context.startActivity(launchIntent) }
                    } else {
                        showShizukuHelp = true
                    }
                }
                else -> {
                    withContext(Dispatchers.IO) { DeviceShizuku.requestPermission() }
                    revision += 1
                }
            }
        }
    }
    val openNotification: () -> Unit = {
        if (snapshot.notification) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        } else {
            permissionRequester.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    val listState = rememberLazyListState()
    val scrolled by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = tr("手机设备能力", "Device Capabilities"), onBack = onBack, scrolled = scrolled)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = PiSpacing.inline,
                bottom = 28.dp + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. 顶部能力与上下文占用状态卡
            item(key = "hero-status") {
                CapabilityHeroCard(
                    usableCount = usableCount,
                    enabledCount = enabledCount,
                    totalCount = allStates.size,
                    mountedToolCount = mountedToolCount,
                    bridgeRunning = snapshot.bridgeRunning,
                    onDisableAll = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        DeviceCapability.entries.forEach { store.setEnabled(it, false) }
                        syncSnapshotFile()
                        revision += 1
                    },
                )
            }

            // 2. 系统服务与权限 2×2 就绪面板
            item(key = "readiness") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel(tr("系统服务与权限", "System Services & Permissions"))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReadinessTile(
                                icon = Icons.Filled.AccessibilityNew,
                                title = tr("无障碍服务", "Accessibility"),
                                status = when (snapshot.accessibility) {
                                    "connected" -> tr("已连接", "Connected")
                                    "enabled_not_connected" -> tr("重连中", "Reconnecting")
                                    "checking" -> tr("检测中", "Checking")
                                    else -> tr("未开启", "Off")
                                },
                                ready = snapshot.accessibility == "connected",
                                warning = snapshot.accessibility == "enabled_not_connected",
                                onClick = openAccessibility,
                                modifier = Modifier.weight(1f),
                            )
                            ReadinessTile(
                                icon = Icons.Filled.NotificationsActive,
                                title = tr("通知使用权", "Notification Access"),
                                status = when (snapshot.notifyListener) {
                                    "connected" -> tr("已连接", "Connected")
                                    "enabled_not_connected" -> tr("重连中", "Reconnecting")
                                    "checking" -> tr("检测中", "Checking")
                                    else -> tr("未开启", "Off")
                                },
                                ready = snapshot.notifyListener == "connected",
                                warning = snapshot.notifyListener == "enabled_not_connected",
                                onClick = openNotificationListener,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReadinessTile(
                                icon = Icons.Filled.AdminPanelSettings,
                                title = tr("Shizuku 提权", "Shizuku"),
                                status = when {
                                    shizukuReady && shizukuUid == 0 -> tr("已授权 · Root", "Ready · Root")
                                    shizukuReady && shizukuUid == 2000 -> tr("已授权 · ADB", "Ready · ADB")
                                    shizukuReady -> tr("已授权", "Authorized")
                                    !shizukuInstalled -> tr("未安装", "Not Installed")
                                    !shizukuBinder -> tr("未启动", "Not Running")
                                    else -> tr("待授权", "Needs Grant")
                                },
                                ready = shizukuReady,
                                warning = shizukuInstalled && !shizukuReady,
                                onClick = requestShizuku,
                                modifier = Modifier.weight(1f),
                            )
                            ReadinessTile(
                                icon = Icons.Filled.Notifications,
                                title = tr("发送通知", "Post Notifications"),
                                status = if (snapshot.notification) {
                                    tr("已开启", "Allowed")
                                } else {
                                    tr("点击授权", "Tap to Allow")
                                },
                                ready = snapshot.notification,
                                warning = false,
                                onClick = openNotification,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 3. 三组能力控制卡（按组动态热同步挂载工具，无需冗余技能）
            item(key = "capabilities") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel(tr("能力分组 · 按需热挂载工具", "Capability Groups · Hot-Mounted Tools"))
                    PiSettingsCard {
                        CapabilityCardRow(
                            icon = Icons.Filled.TouchApp,
                            title = tr("屏幕自动化", "Screen Automation"),
                            description = tr(
                                "读屏、增量变化对比、编号标注截图、锚点点击、输入、滑动与返回控制",
                                "Screen reading, diffing, numbered screenshots, anchor taps, typing, swiping & navigation",
                            ),
                            tools = listOf("android_screen"),
                            state = snapshot.screen,
                            onToggle = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                store.setEnabled(DeviceCapability.Accessibility, enabled)
                                syncSnapshotFile()
                                revision += 1
                            },
                            onToggleSessionPause = { paused ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                store.setSessionDisabled(DeviceCapability.Accessibility, paused)
                                syncSnapshotFile()
                                revision += 1
                            },
                            issueHint = if (snapshot.screen.enabled && snapshot.screen.enabledForSession && !snapshot.screen.usable) {
                                if (snapshot.accessibility == "enabled_not_connected") {
                                    tr("无障碍服务正在重连，请稍候或在系统设置中重新开关", "Accessibility service is reconnecting")
                                } else {
                                    tr("需先开启系统无障碍服务", "Enable Android Accessibility Service first")
                                }
                            } else {
                                null
                            },
                            actionLabel = tr("前往开启", "Open Settings"),
                            onAction = openAccessibility,
                        )
                        PiSettingsHairline()
                        CapabilityCardRow(
                            icon = Icons.Filled.PhoneAndroid,
                            title = tr("系统交互与硬件", "System & Hardware"),
                            description = tr(
                                "应用启停、设备与网络信息、音量/电量/手电筒/定位、剪贴板、通知收发与日历日程",
                                "Apps, device & network info, volume/battery/flashlight/location, clipboard, notifications & calendar",
                            ),
                            tools = listOf("android_app", "android_device", "android_comm"),
                            state = snapshot.basic,
                            onToggle = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                store.setEnabled(DeviceCapability.Basic, enabled)
                                syncSnapshotFile()
                                revision += 1
                            },
                            onToggleSessionPause = { paused ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                store.setSessionDisabled(DeviceCapability.Basic, paused)
                                syncSnapshotFile()
                                revision += 1
                            },
                            extraFooter = if (snapshot.basic.enabled && snapshot.basic.enabledForSession) {
                                {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        PiSettingsChip(
                                            text = if (snapshot.hasLocation) tr("定位已授", "Location Granted") else tr("授予定位", "Grant Location"),
                                            active = snapshot.hasLocation,
                                            onClick = {
                                                if (!snapshot.hasLocation) {
                                                    permissionRequester.launch(
                                                        arrayOf(
                                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                        PiSettingsChip(
                                            text = if (snapshot.hasCamera) tr("手电筒已授", "Flashlight Granted") else tr("相机(手电筒)", "Camera (Flashlight)"),
                                            active = snapshot.hasCamera,
                                            onClick = {
                                                if (!snapshot.hasCamera) {
                                                    permissionRequester.launch(arrayOf(Manifest.permission.CAMERA))
                                                }
                                            },
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                        PiSettingsHairline()
                        CapabilityCardRow(
                            icon = Icons.Filled.Terminal,
                            title = tr("设备 Shell", "Device Shell"),
                            description = tr(
                                "通过 Shizuku（ADB/Root）或应用沙箱执行 Android 宿主 Shell 命令（按需开启）",
                                "Execute Android host shell via Shizuku (ADB/Root) or app sandbox (enable as needed)",
                            ),
                            tools = listOf("android_shell"),
                            state = snapshot.shell,
                            danger = true,
                            onToggle = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                store.setEnabled(DeviceCapability.Shell, enabled)
                                syncSnapshotFile()
                                revision += 1
                            },
                            onToggleSessionPause = { paused ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                store.setSessionDisabled(DeviceCapability.Shell, paused)
                                syncSnapshotFile()
                                revision += 1
                            },
                            issueHint = if (snapshot.shell.enabled && snapshot.shell.enabledForSession && !shizukuReady) {
                                tr("Shizuku 未就绪，当前仅以应用沙箱身份运行（无法执行 ADB 级命令）", "Shizuku not ready; running in app sandbox only")
                            } else {
                                null
                            },
                            actionLabel = when {
                                !shizukuInstalled -> tr("配置指引", "Setup Guide")
                                !shizukuBinder -> tr("打开 Shizuku", "Open Shizuku")
                                else -> tr("去授权", "Authorize")
                            },
                            onAction = requestShizuku,
                        )
                    }
                }
            }

            // 4. 桥接服务与审计日志（可折叠）
            item(key = "bridge-diagnostics") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel(tr("桥接诊断", "Bridge Diagnostics"))
                    BridgeDiagnosticsCard(
                        expanded = diagnosticsExpanded,
                        onToggleExpand = { diagnosticsExpanded = !diagnosticsExpanded },
                        running = snapshot.bridgeRunning,
                        report = snapshot.bridgeReport,
                        tokenPresent = snapshot.tokenPresent,
                        lastUnpaired = snapshot.lastUnpaired,
                        auditTail = snapshot.auditTail,
                        restarting = restartingBridge,
                        onRestartBridge = {
                            if (restartingBridge) return@BridgeDiagnosticsCard
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            restartingBridge = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    DeviceBridgeController.start(context)
                                }
                                restartingBridge = false
                                revision += 1
                            }
                        },
                        onRefresh = { revision += 1 },
                    )
                }
            }
        }
    }

    if (showShizukuHelp) {
        PiDialog(onDismissRequest = { showShizukuHelp = false }) {
            PiDialogTitle(title = tr("如何启用 Shizuku 提权", "How to Enable Shizuku"))
            PiDialogBody(
                tr(
                    "Shizuku 为设备 Shell 提供 ADB（uid 2000）或 Root 级权限，非必须——未安装时仍可使用普通应用沙箱 Shell。\n\n" +
                        "1. 安装官方 Shizuku 应用（GitHub / Google Play）\n" +
                        "2. 在 Shizuku 中通过「无线调试」或 Root 启动服务\n" +
                        "3. 返回本页再次点击「Shizuku 提权」完成一次性授权",
                    "Shizuku grants ADB (uid 2000) or Root privileges for Device Shell. Optional — standard sandbox Shell works without it.\n\n" +
                        "1. Install the official Shizuku app (GitHub / Google Play)\n" +
                        "2. Start the Shizuku service via Wireless Debugging or Root\n" +
                        "3. Return here and tap Shizuku again to grant permission",
                ),
            )
            PiDialogActions {
                PiDialogAction(
                    label = tr("知道了", "Got It"),
                    primary = true,
                    onClick = { showShizukuHelp = false },
                )
            }
        }
    }
}

@Composable
private fun CapabilityHeroCard(
    usableCount: Int,
    enabledCount: Int,
    totalCount: Int,
    mountedToolCount: Int,
    bridgeRunning: Boolean,
    onDisableAll: () -> Unit,
) {
    val palette = PiTheme.palette
    val zeroToken = usableCount == 0
    val statusColor = when {
        zeroToken -> palette.success
        bridgeRunning -> palette.accent
        else -> palette.warning
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = PiSettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant),
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (zeroToken) {
                            tr("0 Token 上下文占用", "0 Token Context Footprint")
                        } else {
                            tr("已挂载 $mountedToolCount 个工具 · $usableCount/$totalCount 组就绪", "$mountedToolCount Tools Mounted · $usableCount/$totalCount Ready")
                        },
                        style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (bridgeRunning) palette.success else palette.warning),
                        )
                        Text(
                            text = if (bridgeRunning) {
                                tr("本地桥接 127.0.0.1:${DeviceBridgeRouter.DEFAULT_PORT} 运行中", "Local bridge 127.0.0.1:${DeviceBridgeRouter.DEFAULT_PORT} running")
                            } else {
                                tr("本地桥接未启动", "Local bridge stopped")
                            },
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (enabledCount > 0) {
                    PiSettingsChip(
                        text = tr("全部关闭", "Disable All"),
                        onClick = onDisableAll,
                    )
                }
            }
            Text(
                text = when {
                    zeroToken && enabledCount == 0 -> tr(
                        "所有能力均已关闭，不注入任何设备工具或系统提示段；随时可按需开启。",
                        "All capabilities are off — zero device tools or system prompt sections are injected.",
                    )
                    zeroToken -> tr(
                        "开关已开启但所需系统服务未就绪，当前仍保持 0 Token 占用。",
                        "Enabled, but required system services are not ready yet — still 0 tokens.",
                    )
                    else -> tr(
                        "仅按组热挂载所需工具与精简系统段（含共享诊断工具 android_status），关闭后下一轮自动归零。",
                        "Only enabled groups and a compact system section (plus android_status) are hot-mounted; disabling zeroes them on the next turn.",
                    )
                },
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadinessTile(
    icon: ImageVector,
    title: String,
    status: String,
    ready: Boolean,
    warning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val dotColor = when {
        ready -> palette.success
        warning -> palette.warning
        else -> palette.muted
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = PiSpacing.hairlineThin,
            color = if (ready) palette.success.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (ready) palette.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        status,
                        style = PiTheme.text.meta,
                        color = if (ready) palette.success else if (warning) palette.warning else palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityCardRow(
    icon: ImageVector,
    title: String,
    description: String,
    tools: List<String>,
    state: DeviceCapabilityState,
    onToggle: (Boolean) -> Unit,
    onToggleSessionPause: (Boolean) -> Unit,
    danger: Boolean = false,
    issueHint: String? = null,
    actionLabel: String = "",
    onAction: () -> Unit = {},
    extraFooter: (@Composable () -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val sessionPaused = state.enabled && !state.enabledForSession
    val statusText = when {
        !state.enabled -> tr("已关闭", "Off")
        sessionPaused -> tr("本会话暂停", "Paused")
        state.usable -> tr("已就绪", "Ready")
        else -> tr("待授权", "Needs Setup")
    }
    val statusTone = when {
        !state.enabled -> palette.muted
        sessionPaused -> palette.warning
        state.usable -> palette.success
        else -> palette.warning
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onToggle(!state.enabled) })
            .padding(
                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                vertical = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        width = PiSpacing.hairlineThin,
                        color = if (state.usable) palette.success.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = when {
                        state.usable -> if (danger) palette.error else MaterialTheme.colorScheme.onSurface
                        state.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> palette.muted
                    },
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(text = statusText, tone = statusTone)
                    if (danger) {
                        StatusPill(text = tr("高危", "High Risk"), tone = palette.error)
                    }
                }
                Text(
                    description,
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PiSettingsSwitch(checked = state.enabled, onCheckedChange = onToggle)
        }

        // 工具挂载徽标：清晰告知本组暴露哪些官方注册的工具
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 50.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tools.forEach { toolName ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (state.usable) {
                        palette.success.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        width = PiSpacing.hairlineThin,
                        color = if (state.usable) {
                            palette.success.copy(alpha = 0.32f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        },
                    ),
                ) {
                    Text(
                        text = toolName,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = PiTheme.text.monoSmall,
                        color = if (state.usable) palette.success else palette.muted,
                        maxLines = 1,
                    )
                }
            }
        }

        if (state.enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 50.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PiSettingsChip(
                    text = if (sessionPaused) tr("恢复本会话", "Resume for Session") else tr("本会话暂停", "Pause for Session"),
                    active = sessionPaused,
                    onClick = { onToggleSessionPause(!sessionPaused) },
                )
                if (issueHint != null && actionLabel.isNotEmpty()) {
                    PiSettingsChip(
                        text = actionLabel,
                        active = true,
                        onClick = onAction,
                    )
                }
            }
            if (extraFooter != null) {
                Box(Modifier.padding(start = 50.dp)) {
                    extraFooter()
                }
            }
            if (issueHint != null) {
                Text(
                    text = issueHint,
                    modifier = Modifier.padding(start = 50.dp),
                    style = PiTheme.text.meta,
                    color = palette.warning,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, tone: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = tone.copy(alpha = 0.13f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = tone,
            maxLines = 1,
        )
    }
}

@Composable
private fun BridgeDiagnosticsCard(
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    running: Boolean,
    report: String,
    tokenPresent: Boolean,
    lastUnpaired: String?,
    auditTail: List<String>,
    restarting: Boolean,
    onRestartBridge: () -> Unit,
    onRefresh: () -> Unit,
) {
    val palette = PiTheme.palette
    PiSettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(
                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                    vertical = PiSettingsMetrics.rowPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (running) palette.success else palette.warning),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tr("本地桥接服务与审计日志", "Local Bridge & Audit Log"),
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (running) {
                        tr("127.0.0.1:${DeviceBridgeRouter.DEFAULT_PORT} · 令牌已就绪", "127.0.0.1:${DeviceBridgeRouter.DEFAULT_PORT} · Token ready")
                    } else {
                        tr("未运行 · 点击展开排查或重启", "Stopped · Tap to inspect or restart")
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            PiSettingsHairline()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                        vertical = PiSettingsMetrics.rowPaddingVertical,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = buildString {
                        append(tr("桥接状态：", "Bridge: "))
                        append(report)
                        append("\n")
                        append(
                            if (tokenPresent) {
                                tr("鉴权令牌：已就绪（仅本机可读）", "Bearer token: ready (local only)")
                            } else {
                                tr("鉴权令牌：未生成", "Bearer token: missing")
                            },
                        )
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!lastUnpaired.isNullOrBlank()) {
                    Text(
                        text = tr("上次异常中断请求：$lastUnpaired", "Last interrupted request: $lastUnpaired"),
                        style = PiTheme.text.meta,
                        color = palette.warning,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PiSettingsChip(
                        text = if (restarting) tr("重启中…", "Restarting…") else tr("重启本地桥接", "Restart Bridge"),
                        active = !running,
                        onClick = onRestartBridge,
                    )
                    PiSettingsChip(
                        text = tr("刷新日志", "Refresh Log"),
                        onClick = onRefresh,
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(PiSpacing.hairlineThin, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    if (auditTail.isEmpty()) {
                        Text(
                            text = tr("暂无设备桥接调用记录", "No recent device bridge calls"),
                            modifier = Modifier.padding(10.dp),
                            style = PiTheme.text.monoSmall,
                            color = palette.muted,
                        )
                    } else {
                        Text(
                            text = auditTail.joinToString("\n"),
                            modifier = Modifier.padding(10.dp),
                            style = PiTheme.text.monoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(
            start = PiSettingsMetrics.pageHorizontal + PiSpacing.small,
            end = PiSettingsMetrics.pageHorizontal,
        ),
        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
        color = PiTheme.palette.muted,
    )
}

private data class DeviceCapPollSnapshot(
    val accessibility: String,
    val notifyListener: String,
    val shizuku: JSONObject,
    val notification: Boolean,
    val hasLocation: Boolean,
    val hasCamera: Boolean,
    val screen: DeviceCapabilityState,
    val basic: DeviceCapabilityState,
    val shell: DeviceCapabilityState,
    val bridgeRunning: Boolean,
    val bridgeReport: String,
    val tokenPresent: Boolean,
    val lastUnpaired: String?,
    val auditTail: List<String>,
)
