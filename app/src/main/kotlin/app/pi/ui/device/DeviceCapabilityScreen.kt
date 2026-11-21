package app.pi.ui.device

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.bridge.DeviceAccessibilityService
import app.pi.bridge.DeviceApprovalLedger
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityState
import app.pi.bridge.DeviceCapabilityStore
import app.pi.bridge.DeviceShizuku
import app.pi.bridge.PiNotificationListener
import app.pi.i18n.tr
import app.pi.ui.PiTopBar
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
import kotlinx.coroutines.withContext

/**
 * 设备能力控制台（精简重构版）：
 *  - 顶部 0-Token 上下文占用横幅（开启任意能力时支持「全部关闭」一键归零）
 *  - 2×2 系统服务与权限就绪面板（无障碍、通知使用权、Shizuku 提权、通知发送权限）
 *  - 3 组清晰能力开关（屏幕自动化、系统交互与硬件、设备 Shell），均支持本会话暂停
 *  - 已彻底移除冗余的 SAF 目录代理与 `android_files`（工作区与 `/sdcard` 已可直访）
 */
@Composable
fun DeviceCapabilityScreen(
    store: DeviceCapabilityStore,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    var accessibility by remember { mutableStateOf(DeviceAccessibilityService.stateName(context)) }
    var notifyListener by remember { mutableStateOf(PiNotificationListener.stateName(context)) }
    var shizuku by remember { mutableStateOf(DeviceShizuku.status(context)) }
    var notification by remember { mutableStateOf(store.hasNotificationPermission()) }
    var hasLocation by remember { mutableStateOf(store.hasLocationPermission()) }
    var hasCamera by remember { mutableStateOf(store.hasCameraPermission()) }
    var ledgerLines by remember { mutableStateOf(DeviceApprovalLedger.summaryLines()) }
    var revision by remember { mutableIntStateOf(0) }

    val permissionRequester = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notification = store.hasNotificationPermission()
        hasLocation = store.hasLocationPermission()
        hasCamera = store.hasCameraPermission()
        revision += 1
    }

    val visible = rememberPiScreenVisible()
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                accessibility = DeviceAccessibilityService.stateName(context)
                notifyListener = PiNotificationListener.stateName(context)
                shizuku = DeviceShizuku.status(context)
                notification = store.hasNotificationPermission()
                hasLocation = store.hasLocationPermission()
                hasCamera = store.hasCameraPermission()
                ledgerLines = DeviceApprovalLedger.summaryLines()
            }
            delay(1500L)
        }
    }

    val screen = remember(revision, accessibility) { store.state(DeviceCapability.Accessibility) }
    val basic = remember(revision) { store.state(DeviceCapability.Basic) }
    val shell = remember(revision, shizuku) { store.state(DeviceCapability.Shell) }
    val allStates = listOf(screen, basic, shell)
    val usableCount = allStates.count { it.usable }
    val enabledCount = allStates.count { it.enabled && it.enabledForSession }
    val palette = PiTheme.palette

    val openAccessibility: () -> Unit = {
        runCatching { context.startActivity(DeviceAccessibilityService.settingsIntent()) }
    }
    val openNotificationListener: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
    val requestShizuku: () -> Unit = {
        when {
            !DeviceShizuku.isInstalled(context) -> Unit
            !DeviceShizuku.binderAlive() -> openShizukuManager(context)
            else -> DeviceShizuku.requestPermission()
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
            permissionRequester.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    Column(Modifier.fillMaxSize().settingsPageTopInset(contentPadding)) {
        PiTopBar(title = tr("设备能力", "Device Capabilities"), onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = PiSpacing.inline,
                bottom = 24.dp + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. 0 Token 占用横幅 + 一键全关
            item(key = "context-footprint") {
                ContextFootprintBanner(
                    usableCount = usableCount,
                    enabledCount = enabledCount,
                    totalCount = allStates.size,
                    onDisableAll = {
                        DeviceCapability.entries.forEach { store.setEnabled(it, false) }
                        revision += 1
                    },
                )
            }

            // 2. 系统服务与权限 2×2 就绪面板（均为真服务/真权限）
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
                                status = when (accessibility) {
                                    "connected" -> tr("已连接", "Connected")
                                    "enabled_not_connected" -> tr("重连中", "Reconnecting")
                                    else -> tr("未开启", "Off")
                                },
                                ready = accessibility == "connected",
                                accent = palette.accent,
                                onClick = openAccessibility,
                                modifier = Modifier.weight(1f),
                            )
                            ReadinessTile(
                                icon = Icons.Filled.NotificationsActive,
                                title = tr("通知使用权", "Notification Access"),
                                status = when (notifyListener) {
                                    "connected" -> tr("已连接", "Connected")
                                    "enabled_not_connected" -> tr("重连中", "Reconnecting")
                                    else -> tr("未开启", "Off")
                                },
                                ready = notifyListener == "connected",
                                accent = palette.mdLink,
                                onClick = openNotificationListener,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val shizukuReady = shizuku.optBoolean("ready")
                            val shizukuInstalled = DeviceShizuku.isInstalled(context)
                            ReadinessTile(
                                icon = Icons.Filled.AdminPanelSettings,
                                title = tr("Shizuku 提权", "Shizuku"),
                                status = when {
                                    shizukuReady -> tr("已授权", "Authorized")
                                    !shizukuInstalled -> tr("未安装", "Not Installed")
                                    !DeviceShizuku.binderAlive() -> tr("未启动", "Not Running")
                                    else -> tr("待授权", "Needs Permission")
                                },
                                ready = shizukuReady,
                                accent = palette.warning,
                                onClick = requestShizuku,
                                modifier = Modifier.weight(1f),
                            )
                            ReadinessTile(
                                icon = Icons.Filled.Notifications,
                                title = tr("发送通知", "Post Notifications"),
                                status = if (notification) tr("已开启", "Allowed") else tr("点击授权", "Tap to Allow"),
                                ready = notification,
                                accent = palette.success,
                                onClick = openNotification,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 3. 三组能力控制卡（按组动态热同步挂载工具）
            item(key = "capabilities") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel(tr("能力开关 · 按组动态挂载", "Capabilities · Dynamic Per-Group Mounting"))
                    PiSettingsCard {
                        CapabilityCardRow(
                            icon = Icons.Filled.TouchApp,
                            iconTint = palette.accent,
                            title = tr("屏幕自动化", "Screen Automation"),
                            description = tr("读屏、增量对比、编号标注截图、锚点点击、输入与滑动", "Screen reading, diffing, numbered screenshots, anchor taps, typing & swiping"),
                            state = screen,
                            onToggle = {
                                store.setEnabled(DeviceCapability.Accessibility, it)
                                revision += 1
                            },
                            onToggleSessionPause = { paused ->
                                store.setSessionDisabled(DeviceCapability.Accessibility, paused)
                                revision += 1
                            },
                            issueHint = if (screen.enabled && screen.enabledForSession && !screen.usable) {
                                if (accessibility == "enabled_not_connected") tr("无障碍服务正在重连", "Accessibility service is reconnecting") else tr("需先开启系统无障碍服务", "Enable Android Accessibility Service first")
                            } else {
                                null
                            },
                            actionLabel = tr("前往开启", "Open Settings"),
                            onAction = openAccessibility,
                        )
                        PiSettingsHairline()
                        CapabilityCardRow(
                            icon = Icons.Filled.PhoneAndroid,
                            iconTint = palette.success,
                            title = tr("系统交互与硬件", "System & Hardware"),
                            description = tr("启动应用、剪贴板、通知收发/回复、分享、语音、电量/手电筒/定位", "App launch, clipboard, notifications/replies, share, TTS, battery/flashlight/location"),
                            state = basic,
                            onToggle = {
                                store.setEnabled(DeviceCapability.Basic, it)
                                revision += 1
                            },
                            onToggleSessionPause = { paused ->
                                store.setSessionDisabled(DeviceCapability.Basic, paused)
                                revision += 1
                            },
                            extraFooter = if (basic.enabled && basic.enabledForSession) {
                                {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        PiSettingsChip(
                                            text = if (hasLocation) tr("定位已授", "Location Granted") else tr("授予定位", "Grant Location"),
                                            active = hasLocation,
                                            onClick = {
                                                if (!hasLocation) {
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
                                            text = if (hasCamera) tr("手电筒已授", "Flashlight Granted") else tr("授予相机(手电筒)", "Grant Camera (Flashlight)"),
                                            active = hasCamera,
                                            onClick = {
                                                if (!hasCamera) {
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
                            iconTint = palette.error,
                            title = tr("设备 Shell", "Device Shell"),
                            description = tr("Shizuku / 本机 Shell（高危 · 危险命令需每次会话审批）", "Shizuku / local shell (high risk · dangerous commands require per-session approval)"),
                            state = shell,
                            danger = true,
                            onToggle = {
                                store.setEnabled(DeviceCapability.Shell, it)
                                revision += 1
                            },
                            onToggleSessionPause = { paused ->
                                store.setSessionDisabled(DeviceCapability.Shell, paused)
                                revision += 1
                            },
                            issueHint = if (shell.enabled && shell.enabledForSession && !shizuku.optBoolean("ready")) {
                                tr("Shizuku 未就绪，仅以应用沙箱身份运行", "Shizuku is not ready; running in app sandbox only")
                            } else {
                                null
                            },
                            actionLabel = if (DeviceShizuku.isInstalled(context)) tr("去授权", "Authorize") else tr("未安装", "Not Installed"),
                            onAction = requestShizuku,
                        )
                    }
                }
            }

            // 4. 会话审批账本
            item(key = "approval-ledger") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("会话审批记录", "Session Approval Log"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                            modifier = Modifier.weight(1f),
                        )
                        if (ledgerLines.size > 1) {
                            Text(
                                tr("清空", "Clear"),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        DeviceApprovalLedger.clear()
                                        ledgerLines = DeviceApprovalLedger.summaryLines()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.accent,
                            )
                        }
                    }
                    PiSettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = PiSettingsMetrics.rowPaddingVertical,
                                ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ledgerLines.forEach { line ->
                                Text(
                                    line,
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 5. 极简折叠说明
            item(key = "guide") {
                var guideOpen by remember { mutableStateOf(false) }
                PiSettingsCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { guideOpen = !guideOpen }
                            .padding(
                                horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                vertical = PiSettingsMetrics.rowPaddingVertical,
                            ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                tr("免 ADB 与 0 Token 说明", "No-ADB & 0-Token Notes"),
                                style = PiType.rowTitle.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (guideOpen) tr("收起", "Collapse") else tr("展开", "Expand"),
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.accent,
                            )
                        }
                        if (guideOpen) {
                            Text(
                                tr(
                                    "• 屏幕自动化与通知收发仅需开启对应系统服务，全程免电脑免 ADB。\n" +
                                        "• 设备文件与 `/sdcard` 已可由模型自带工具直接访问，不再需要 SAF 目录代理。\n" +
                                        "• 全部关闭后下一条消息自动摘除全部 `android_*` 工具与提示，严格 0 Token。",
                                    "• Screen automation and notification access only require enabling the corresponding Android services — no PC or ADB needed.\n" +
                                        "• Device files and `/sdcard` are directly accessible to built-in model tools without SAF directory proxies.\n" +
                                        "• Turning all groups off removes every `android_*` tool and prompt on the next turn for strictly 0 tokens.",
                                ),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置入口行（保持原有函数签名）。
 */
@Composable
fun DeviceCapabilityEntryRow(store: DeviceCapabilityStore, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val states = store.states()
    val usable = states.count { it.usable }
    val palette = PiTheme.palette
    val activeTint = if (usable > 0) palette.accent else palette.success
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PiSettingsMetrics.rowPaddingHorizontal, vertical = PiSettingsMetrics.rowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(activeTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Security,
                contentDescription = null,
                tint = activeTint,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                tr("设备能力", "Device Capabilities"),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (usable == 0) tr("已停用 · 0 Token 上下文占用", "Disabled · 0 Token context footprint") else tr("$usable/${states.size} 组就绪 · 屏幕、交互与 Shell", "$usable/${states.size} ready · Screen, System & Shell"),
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = activeTint.copy(alpha = 0.14f),
        ) {
            Text(
                if (usable == 0) "0 Token" else tr("$usable 组开启", "$usable Active"),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                color = activeTint,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(PiSettingsMetrics.chevronSize),
            tint = palette.muted,
        )
    }
}

@Composable
private fun ContextFootprintBanner(
    usableCount: Int,
    enabledCount: Int,
    totalCount: Int,
    onDisableAll: () -> Unit,
) {
    val palette = PiTheme.palette
    val zeroToken = usableCount == 0
    val tone = if (zeroToken) palette.success else palette.accent
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PiSettingsMetrics.pageHorizontal),
        shape = PiSettingsCardShape,
        color = tone.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tone.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (zeroToken) tr("0 Token 上下文占用", "0 Token Context Footprint") else tr("已激活 $usableCount / $totalCount 组 · 极简提示", "$usableCount / $totalCount Active · Compact Prompt"),
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = tone,
                )
                Text(
                    text = when {
                        zeroToken && enabledCount == 0 -> tr("全部能力已停用，不注入任何设备工具或提示词。", "All capabilities are off; no device tools or prompts are injected.")
                        zeroToken -> tr("开关已开但系统服务未就绪，当前仍保持 0 Token。", "Enabled, but required system services are not ready — still 0 tokens.")
                        else -> tr("仅按组热挂载所需工具，关闭后下一轮自动归零。", "Only tools for enabled groups are hot-mounted; disabling drops them on the next turn.")
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabledCount > 0) {
                PiSettingsChip(
                    text = tr("全部关闭", "Disable All"),
                    onClick = onDisableAll,
                )
            }
        }
    }
}

@Composable
private fun ReadinessTile(
    icon: ImageVector,
    title: String,
    status: String,
    ready: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val tone = if (ready) palette.success else accent
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
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
                    .background(tone.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
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
                            .background(if (ready) palette.success else palette.muted),
                    )
                    Text(
                        status,
                        style = PiTheme.text.meta,
                        color = if (ready) palette.success else palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityCardRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
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
                    .background(iconTint.copy(alpha = if (state.enabled) 0.16f else 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (state.enabled) iconTint else palette.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusTone.copy(alpha = 0.14f),
                    ) {
                        Text(
                            statusText,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = PiTheme.text.monoSmall,
                            color = statusTone,
                            maxLines = 1,
                        )
                    }
                    if (danger) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = palette.error.copy(alpha = 0.14f),
                        ) {
                            Text(
                                tr("高危", "High Risk"),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = PiTheme.text.monoSmall,
                                color = palette.error,
                                maxLines = 1,
                            )
                        }
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

private fun openShizukuManager(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
