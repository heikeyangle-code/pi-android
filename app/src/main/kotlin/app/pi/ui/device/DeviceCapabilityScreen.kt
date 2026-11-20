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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
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
import app.pi.bridge.DeviceSafStore
import app.pi.bridge.DeviceShizuku
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
 * 设备能力控制台：系统就绪仪表盘、上下文 Token 占用实时状态、3 组精细化能力开关（支持本会话暂停）、
 * SAF 授权目录管理与危险操作审批账本。
 */
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
    var ledgerLines by remember { mutableStateOf(DeviceApprovalLedger.summaryLines()) }
    var revision by remember { mutableIntStateOf(0) }

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            safStore.add(uri, null)
            grants = safStore.grants()
        }
    }
    val notificationRequester = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notification = store.hasNotificationPermission()
    }
    val storageRequester = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision += 1
    }

    val visible = rememberPiScreenVisible()
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                accessibility = DeviceAccessibilityService.stateName(context)
                shizuku = DeviceShizuku.status(context)
                notification = store.hasNotificationPermission()
                grants = safStore.grants()
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
            contentPadding = PaddingValues(
                top = PiSpacing.inline,
                bottom = 24.dp + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(PiSettingsMetrics.groupGap),
        ) {
            // 1. 上下文占用实时横幅：让「关闭即 0 Token」可见可验证
            item(key = "context-footprint") {
                ContextFootprintBanner(
                    usableCount = usableCount,
                    enabledCount = enabledCount,
                    totalCount = allStates.size,
                )
            }

            // 2. 系统就绪度 2×2 仪表盘
            item(key = "readiness") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel("系统就绪状态")
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
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReadinessTile(
                                icon = Icons.Filled.Notifications,
                                title = "通知权限",
                                status = if (notification) "已开启" else "点击授权",
                                ready = notification,
                                accent = palette.success,
                                onClick = openNotification,
                                modifier = Modifier.weight(1f),
                            )
                            ReadinessTile(
                                icon = Icons.Filled.Folder,
                                title = "SAF 目录",
                                status = if (grants.isEmpty()) "点击添加" else "${grants.size} 个已授权",
                                ready = grants.isNotEmpty(),
                                accent = palette.accent,
                                onClick = { directoryPicker.launch(null) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 3. 三组能力控制卡（含独立本会话暂停 & 前置一键跳转）
            item(key = "capabilities") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel("能力开关 · 按组动态挂载工具")
                    PiSettingsCard {
                        CapabilityCardRow(
                            icon = Icons.Filled.TouchApp,
                            iconTint = palette.accent,
                            title = "屏幕自动化",
                            description = "读屏、锚点智能点击、输入、滑动、编号标注截图",
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
                                if (accessibility == "enabled_not_connected") "无障碍服务正在重连，请稍候" else "需要开启系统无障碍服务"
                            } else {
                                null
                            },
                            actionLabel = "前往开启",
                            onAction = openAccessibility,
                        )
                        PiSettingsHairline()
                        val needLegacyStorage = basic.enabled && basic.enabledForSession && !basic.usable &&
                            store.legacyStoragePermissions().isNotEmpty()
                        CapabilityCardRow(
                            icon = Icons.Filled.FolderOpen,
                            iconTint = palette.success,
                            title = "系统与文件",
                            description = "SAF 授权目录读写、剪贴板、系统通知、分享与下载",
                            state = basic,
                            onToggle = {
                                store.setEnabled(DeviceCapability.Basic, it)
                                revision += 1
                            },
                            onToggleSessionPause = { paused ->
                                store.setSessionDisabled(DeviceCapability.Basic, paused)
                                revision += 1
                            },
                            issueHint = if (needLegacyStorage) "旧版 Android 导出文件需要存储权限" else null,
                            actionLabel = "授予权限",
                            onAction = {
                                val perms = store.legacyStoragePermissions().toTypedArray()
                                if (perms.isNotEmpty()) storageRequester.launch(perms)
                            },
                        )
                        PiSettingsHairline()
                        CapabilityCardRow(
                            icon = Icons.Filled.Terminal,
                            iconTint = palette.error,
                            title = "设备 Shell",
                            description = "Shizuku / ADB 级命令行（高危 · 每次会话需审批）",
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
                                "Shizuku 未就绪，仅限应用沙箱内命令"
                            } else {
                                null
                            },
                            actionLabel = if (DeviceShizuku.isInstalled(context)) "去授权" else "未安装",
                            onAction = requestShizuku,
                        )
                    }
                }
            }

            // 4. 已授权 SAF 目录管理
            item(key = "saf-grants") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已授权目录（SAF）",
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.muted,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            onClick = { directoryPicker.launch(null) },
                            shape = RoundedCornerShape(50),
                            color = palette.accent.copy(alpha = 0.14f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Filled.Add, null, tint = palette.accent, modifier = Modifier.size(14.dp))
                                Text(
                                    "添加目录",
                                    style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                                    color = palette.accent,
                                )
                            }
                        }
                    }
                    PiSettingsCard {
                        if (grants.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                        vertical = PiSettingsMetrics.rowPaddingVertical,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "尚未授权任何外部文件夹",
                                    style = PiType.rowTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "授权后，Agent 可在「系统与文件」开启时读写所选目录内的文件（不支持删除操作）。",
                                    style = PiTheme.text.meta,
                                    color = palette.muted,
                                )
                            }
                        } else {
                            grants.forEachIndexed { index, grant ->
                                if (index > 0) PiSettingsHairline()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                            vertical = 10.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(palette.accent.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = palette.accent,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            grant.name,
                                            style = PiType.rowTitle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "读写权限 · 持久化",
                                            style = PiTheme.text.meta,
                                            color = palette.muted,
                                            maxLines = 1,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(palette.error.copy(alpha = 0.10f))
                                            .clickable {
                                                safStore.remove(grant.uri)
                                                grants = safStore.grants()
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "移除授权",
                                            tint = palette.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. 危险操作审批账本
            item(key = "approval-ledger") {
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel("会话安全审批账本")
                    PiSettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = PiSettingsMetrics.rowPaddingHorizontal,
                                    vertical = PiSettingsMetrics.rowPaddingVertical,
                                ),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
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

            // 6. 权限与零上下文指引卡（可折叠）
            item(key = "guide") {
                var guideOpen by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.small)) {
                    DeviceSectionLabel("权限与架构说明")
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
                                    "免 ADB 授权流程与 0 Token 机制",
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
                                    "• 屏幕自动化：仅需开启系统无障碍服务，无需电脑或 ADB。\n" +
                                        "• 系统与文件：通过 Android SAF 授权指定目录读写，无需 ADB。\n" +
                                        "• 设备 Shell：默认以沙箱身份运行；安装 Shizuku 并通过本机无线调试激活后，即可免电脑提权至 ADB 级（uid=2000）。\n" +
                                        "• 零上下文占用：每轮对话前实时同步此处开关，全部关闭后自动摘除全部设备工具与提示词，严格 0 Token 占用，无需重启引擎。",
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
}

/**
 * 设置首页入口行（保持原有函数签名）。
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
                if (usable == 0) "已停用 · 0 Token 上下文占用" else "$usable/${states.size} 组就绪 · 屏幕、文件与 Shell",
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
                    text = if (zeroToken) {
                        "0 Token 上下文占用"
                    } else {
                        "已激活 $usableCount / $totalCount 组 · 极简提示模式"
                    },
                    style = PiType.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = tone,
                )
                Text(
                    text = when {
                        zeroToken && enabledCount == 0 ->
                            "所有设备能力已关停，不向模型暴露任何设备工具、系统提示或技能，下一条消息实时生效。"
                        zeroToken ->
                            "开关已开但系统前置未就绪，当前仍保持 0 Token 上下文占用。"
                        else ->
                            "仅挂载已开启组的精简工具与极短路由提示；随时关闭即可在下一轮零成本释放。"
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = statusTone,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    description,
                    style = PiTheme.text.meta,
                    color = if (danger && state.enabled) palette.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PiSettingsSwitch(checked = state.enabled, onCheckedChange = onToggle)
        }

        if (state.enabled || issueHint != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 50.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.enabled) {
                    PiSettingsChip(
                        text = if (sessionPaused) "恢复本会话" else "本会话暂停",
                        active = sessionPaused,
                        onClick = { onToggleSessionPause(!sessionPaused) },
                    )
                }
                if (issueHint != null) {
                    Text(
                        issueHint,
                        style = PiTheme.text.meta,
                        color = palette.warning,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (actionLabel.isNotEmpty()) {
                        PiSettingsChip(text = actionLabel, active = true, onClick = onAction)
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
        style = PiTheme.text.meta.copy(fontWeight = FontWeight.SemiBold),
        color = PiTheme.palette.muted,
        modifier = Modifier.padding(horizontal = PiSettingsMetrics.pageHorizontal + PiSpacing.small),
    )
}

private fun openShizukuManager(context: android.content.Context): Boolean = runCatching {
    val intent = context.packageManager.getLaunchIntentForPackage(DeviceShizuku.MANAGER_PACKAGE) ?: return false
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)
