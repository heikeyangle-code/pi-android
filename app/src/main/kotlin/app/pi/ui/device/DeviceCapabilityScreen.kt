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
        PiTopBar(title = "设备能力", onBack = onBack)
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
                    DeviceSectionLabel("系统服务与权限")
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
                                title = "无障碍服务",
                                status = when (accessibility) {
                                    "connected" -> "已连接"
                                    "enabled_not_connected" -> "重连中"
                                    else -> "未开启"
                                },
                                ready = accessibility == "connected",
                                accent = palette.accent,
                                onClick = openAccessibility,
                                modifier = Modifier.weight(1f),
                            )
                            ReadinessTile(
                                icon = Icons.Filled.NotificationsActive,
                                title = "通知使用权",
                                status = when (notifyListener) {
                                    "connected" -> "已连接"
                                    "enabled_not_connected" -> "重连中"
                                    else -> "未开启"
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
                                title = "Shizuku 提权",
                                status = when {
                                    shizukuReady -> "已授权"
                                    !shizukuInstalled -> "未安装"
                                    !DeviceShizuku.binderAlive() -> "未启动"
                                    else -> "待授权"
                                },
                                ready = shizukuReady,
                                accent = palette.warning,
                                onClick = requestShizuku,
                                modifier = Modifier.weight(1f),
                            )
                            ReadinessTile(
                                icon = Icons.Filled.Notifications,
                                title = "发送通知",
                                status = if (notification) "已开启" else "点击授权",
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
                    DeviceSectionLabel("能力开关 · 按组动态挂载")
                    PiSettingsCard {
                        CapabilityCardRow(
                            icon = Icons.Filled.TouchApp,
                            iconTint = palette.accent,
                            title = "屏幕自动化",
                            description = "读屏、增量对比、编号标注截图、锚点点击、输入与滑动",
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
                                if (accessibility == "enabled_not_connected") "无障碍服务正在重连" else "需先开启系统无障碍服务"
                            } else {
                                null
                            },
                            actionLabel = "前往开启",
                            onAction = openAccessibility,
                        )
                        PiSettingsHairline()
                        CapabilityCardRow(
                            icon = Icons.Filled.PhoneAndroid,
                            iconTint = palette.success,
                            title = "系统交互与硬件",
                            description = "启动应用、剪贴板、通知收发/回复、分享、语音、电量/手电筒/定位",
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
                                            text = if (hasLocation) "定位已授" else "授予定位",
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
                                            text = if (hasCamera) "手电筒已授" else "授予相机(手电筒)",
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
                            title = "设备 Shell",
                            description = "Shizuku / 本机 Shell（高危 · 危险命令需每次会话审批）",
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
                                "Shizuku 未就绪，仅以应用沙箱身份运行"
                            } else {
                                null
                            },
                            actionLabel = if (DeviceShizuku.isInstalled(context)) "去授权" else "未安装",
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
                            "会话审批记录",
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                            modifier = Modifier.weight(1f),
                        )
                        if (ledgerLines.size > 1) {
                            Text(
                                "清空",
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
                                "免 ADB 与 0 Token 说明",
                                style = PiType.rowTitle.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (guideOpen) "收起" else "展开",
                                style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                color = palette.accent,
                            )
                        }
                        if (guideOpen) {
                            Text(
                                "• 屏幕自动化与通知收发仅需开启对应系统服务，全程免电脑免 ADB。\n" +
                                    "• 设备文件与 `/sdcard` 已可由模型自带工具直接访问，不再需要 SAF 目录代理。\n" +
                                    "• 全部关闭后下一条消息自动摘除全部 `android_*` 工具与提示，严格 0 Token。",
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
                "设备能力",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (usable == 0) "已停用 · 0 Token 上下文占用" else "$usable/${states.size} 组就绪 · 屏幕、交互与 Shell",
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
                if (usable == 0) "0 Token" else "$usable 组开启",
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
                    text = if (zeroToken) "0 Token 上下文占用" else "已激活 $usableCount / $totalCount 组 · 极简提示",
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = tone,
                )
                Text(
                    text = when {
                        zeroToken && enabledCount == 0 -> "全部能力已停用，不注入任何设备工具或提示词。"
                        zeroToken -> "开关已开但系统服务未就绪，当前仍保持 0 Token。"
                        else -> "仅按组热挂载所需工具，关闭后下一轮自动归零。"
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabledCount > 0) {
                PiSettingsChip(
                    text = "全部关闭",
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
        !state.enabled -> "已关闭"
        sessionPaused -> "本会话暂停"
        state.usable -> "已就绪"
        else -> "待授权"
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
                                "高危",
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
                    text = if (sessionPaused) "恢复本会话" else "本会话暂停",
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
