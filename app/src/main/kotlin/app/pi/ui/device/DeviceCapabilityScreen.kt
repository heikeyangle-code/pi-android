package app.pi.ui.device

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.pi.bridge.DeviceAccessibilityService
import app.pi.bridge.DeviceApprovalLedger
import app.pi.bridge.DeviceBridgeController
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityState
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
 * Device capability authorization (docs/pi-android-ui-spec.md §5.6).
 *
 * One card per capability group, each showing: current state, what the agent would
 * be able to do with it, a switch, and a 「本会话暂时禁用」 quick switch. The screen
 * also carries the bridge's own status, because "the capability is on but the
 * accessibility service is not running" is a state the user must be able to see and
 * act on — that distinction is the difference between a switch that works and one
 * that only looks like it does.
 *
 * ### Why every relaxation is visible here
 *
 * The user's requirement is that nothing may be loosened without a trace. So this
 * screen is also the *policy* screen: the shell card shows the exact command
 * whitelist, the irreducible hard blocklist, the write boundary and the relaxed-mode
 * switch with its cost in one sentence; the storage card shows the granted SAF
 * directories; and the approvals card shows what the pi-side permission gate has
 * been told to stop asking about. None of that is inferred — it is read from the
 * same objects the enforcement reads ([DeviceShellGuard], [DeviceCapabilityStore],
 * [DeviceSafStore], [DeviceWorkspace], [DeviceShizuku]).
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
    val safStore = remember { DeviceSafStore.get(context) }
    // For the bridge's start button, which does file work (see its onClick).
    val scope = rememberCoroutineScope()

    // Status is polled rather than observed: the accessibility service, Shizuku and
    // the workspace can all change outside this screen while it is in the
    // foreground, and the user expects to come back and see the truth.
    var accessibilityRunning by remember { mutableStateOf(DeviceAccessibilityService.isRunning()) }
    // The tri-state spelling of the same fact: 「已启用但还没连上」 is not 「未启用」,
    // and sending the user to Settings for a service that is already on is the
    // single most common wrong instruction this screen used to give.
    var accessibilityState by remember { mutableStateOf(DeviceAccessibilityService.stateName(context)) }
    var bridgeStatus by remember { mutableStateOf(DeviceBridgeController.statusReport()) }
    var bridgeRunning by remember { mutableStateOf(DeviceBridgeController.isRunning()) }
    var revision by remember { mutableStateOf(0) }
    var grants by remember { mutableStateOf(safStore.grants()) }
    var relaxed by remember { mutableStateOf(store.isShellSyntaxRelaxed()) }
    var shizuku by remember { mutableStateOf(DeviceShizuku.status(context)) }
    var workspace by remember { mutableStateOf(DeviceWorkspace.summary()) }
    var storagePermissionsNeeded by remember { mutableStateOf(!store.hasLegacyStoragePermission()) }
    // The endpoint-level grants the cards have to state: CAMERA gates the torch
    // (DeviceCapabilityStore.kt:268-278), the location pair gates android_device_state
    // (:207-211, what="location"), POST_NOTIFICATIONS gates android_say (:239-245,
    // kind="notification"). None of them
    // makes its whole *group* unusable — which is exactly why the card, not the
    // store, has to say so, or the group's 「可用」 badge reads as "everything here
    // works" (docs/pi-android-app-design.md §21.4).
    var cameraPermission by remember { mutableStateOf(store.hasCameraPermission()) }
    var locationPermission by remember { mutableStateOf(store.hasLocationPermission()) }
    var notificationPermission by remember { mutableStateOf(store.hasNotificationPermission()) }
    var approvals by remember { mutableStateOf(DeviceApprovalLedger.summaryLines()) }
    var note by remember { mutableStateOf<String?>(null) }
    val auditTail = remember(revision) { DeviceBridgeController.auditPrettyTail(5) }
    // Set at bridge start from the write-ahead trail: a request that started and
    // never produced a result is how an in-flight crash is told to the user.
    val aborted = remember(revision) { DeviceBridgeController.lastUnpairedReport() }

    // The SAF picker is the whole reason this group is no longer documented as
    // "无": a picker needs an Activity, and this screen is one. Persisting the URI
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
    ) { result ->
        storagePermissionsNeeded = !store.hasLegacyStoragePermission()
        note = if (storagePermissionsNeeded) {
            "存储权限仍未授予；在这台设备上导出文件需要它。"
        } else {
            "存储权限已授予。"
        }
        revision += 1
    }

    // The endpoint-level runtime grants: camera (手电筒), the location pair, and
    // POST_NOTIFICATIONS. `cameraPrecondition()` and `notify()` tell the model to
    // send the user to a button on this very card (DeviceCapabilityStore.kt:275-276,
    // DeviceSystemActions.kt:103-112), so the card has to be able to ask — and then
    // re-read the answer from the store instead of assuming the dialog was accepted.
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
            // The result map is keyed by the platform's permission constants
            // (`android.permission.CAMERA` …); those are not words to show a user, so
            // they are named the way the cards name them.
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
    // (`rememberPiScreenVisible` reads the Activity's lifecycle; the whole loop is a
    // re-read of already-published facts, so stopping it costs nothing but staleness
    // for one frame after 切回前台, which the loop then fixes.)
    //
    // **On `Dispatchers.IO`, because one iteration is several binder round trips and
    // file reads**, not a state read: `DeviceAccessibilityService.stateName` asks the
    // AccessibilityManager (`getEnabledAccessibilityServiceList`),
    // `DeviceShizuku.status` talks to the Shizuku service,
    // `DeviceWorkspace.refresh` re-reads the workspace from `settings.json`, the four
    // `store.has*Permission` calls are package-manager/permission lookups and
    // `DeviceApprovalLedger.summaryLines` reads the ledger. All of it used to run on
    // the frame thread every 1.5 s, which is a long frame per interval on a screen
    // that is otherwise just a list of rows. The writes target Compose state, which is
    // safe from any thread, so only the reads had to move.
    val visible = rememberPiScreenVisible()
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                accessibilityRunning = DeviceAccessibilityService.isRunning()
                accessibilityState = DeviceAccessibilityService.stateName(context)
                bridgeRunning = DeviceBridgeController.isRunning()
                bridgeStatus = DeviceBridgeController.statusReport()
                shizuku = DeviceShizuku.status(context)
                DeviceWorkspace.refresh(context)
                workspace = DeviceWorkspace.summary()
                storagePermissionsNeeded = !store.hasLegacyStoragePermission()
                cameraPermission = store.hasCameraPermission()
                locationPermission = store.hasLocationPermission()
                notificationPermission = store.hasNotificationPermission()
                // The pi-side gate reports through POST /app/gate/report; nothing in that
                // item reads a polled value, so without this the ledger would render once
                // and never change while the screen is open.
                approvals = DeviceApprovalLedger.summaryLines()
            }
            delay(REFRESH_INTERVAL_MS)
        }
    }

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
                PiSettingsSectionHeader("设备桥")
                DeviceBridgeCard(
                    running = bridgeRunning,
                    status = bridgeStatus,
                    auditTail = auditTail,
                    aborted = aborted,
                    onStart = {
                        // Starting the bridge mints a token, walks the shipped extension
                        // assets (a SHA-256 over all of them), rewrites the token file in
                        // two places, reads the workspace out of `settings.json` and
                        // binds a socket. That is file and binder work, not a state
                        // change, so it happens off the frame thread; the button stays
                        // tappable and the card updates when it finishes.
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

            note?.let { message ->
                item { InfoNote(message) }
            }

            item {
                PiSettingsSectionHeader("能力授权")
            }

            items(DeviceCapability.entries.toList()) { capability ->
                val state = store.state(capability)
                DeviceCapabilityCard(
                    state = state,
                    accessibilityRunning = accessibilityRunning,
                    accessibilityState = accessibilityState,
                    onToggle = { enabled ->
                        store.setEnabled(capability, enabled)
                        revision += 1
                    },
                    onSessionToggle = { disabled ->
                        store.setSessionDisabled(capability, disabled)
                        revision += 1
                    },
                    onOpenSystemSettings = {
                        // A swallowed result here is a dead button: some ROMs have no
                        // activity for ACTION_ACCESSIBILITY_SETTINGS, and Android 10+
                        // can refuse a background start. Say so instead of doing nothing.
                        val opened = runCatching {
                            context.startActivity(DeviceAccessibilityService.settingsIntent())
                        }.isSuccess
                        if (!opened) {
                            // No package name: the user is looking at a list of service
                            // labels, and 「PI 设备桥」 is what it is called there.
                            note = "无法打开系统的无障碍设置页。请手动进入 系统设置 → 无障碍 → 已安装的服务，" +
                                "启用「PI 设备桥」。"
                        }
                    },
                    relaxed = relaxed,
                    onRelaxedChange = { enabled ->
                        relaxed = enabled
                        store.setShellSyntaxRelaxed(enabled)
                        revision += 1
                    },
                    shizuku = shizuku,
                    onRequestShizuku = {
                        // Two different jobs hide behind one button, and the old code
                        // only did the second: if the binder is not running there is
                        // nobody to ask, so the honest move is to open Shizuku (which
                        // is what the user has to start on Android 11+ after a reboot)
                        // instead of failing with "无法发起授权".
                        when {
                            !DeviceShizuku.binderAlive() && DeviceShizuku.isInstalled(context) -> {
                                note = if (openShizukuManager(context)) {
                                    "已打开 Shizuku。请在它里面启动服务（Android 11+ 用系统「无线调试」即可，不需要电脑），" +
                                        "回到这里再点一次「请求 Shizuku 授权」。"
                                } else {
                                    "无法打开 Shizuku：请用户自己从桌面启动它，回到这里再点「请求 Shizuku 授权」。"
                                }
                            }
                            !DeviceShizuku.requestPermission() -> {
                                note = "无法发起 Shizuku 授权：Shizuku 没有在运行，或本机没有安装它。"
                            }
                        }
                    },
                    onOpenShizuku = {
                        if (!openShizukuManager(context)) {
                            note = "无法打开 Shizuku：请用户自己从桌面启动它。"
                        }
                    },
                    grants = grants,
                    onGrantDirectory = { directoryPicker.launch(null) },
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
                    cameraPermission = cameraPermission,
                    locationPermission = locationPermission,
                    notificationPermission = notificationPermission,
                    onRequestPermission = { names ->
                        runtimePermissionRequester.launch(names.toTypedArray())
                    },
                )
            }

            item {
                PiSettingsSectionHeader("Shell 策略")
                ShellPolicyCard(relaxed = relaxed, workspace = workspace)
            }

            item {
                PiSettingsSectionHeader("本会话的审批")
                // Fed from the polling loop above: this item reads no other state, so a
                // direct `DeviceApprovalLedger.summaryLines()` call would compose once
                // and stay frozen for as long as the screen is open.
                ApprovalsCard(lines = approvals)
            }

            item {
                Spacer(Modifier.height(PiSpacing.unit))
                InfoNote(
                    "所有设备操作都会写入本地审计日志（不含内容本身）。紧急情况下关闭对应能力的开关、" +
                        "或停用系统的无障碍服务即可立刻停止。",
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
// cards
// ---------------------------------------------------------------------------

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
            StatusBadge(text = if (running) "已监听" else "未运行", positive = running)
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
            // 裁决 ②-2：混排行一律拆两段，不许整行 mono。同卡片下面的白名单/日志尾巴本就是
            // `monoSmall`，那里没有我们的话，所以不动。
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

@Composable
private fun DeviceCapabilityCard(
    state: DeviceCapabilityState,
    accessibilityRunning: Boolean,
    accessibilityState: String,
    onToggle: (Boolean) -> Unit,
    onSessionToggle: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    relaxed: Boolean,
    onRelaxedChange: (Boolean) -> Unit,
    shizuku: JSONObject,
    onRequestShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    grants: List<DeviceSafStore.Grant>,
    onGrantDirectory: () -> Unit,
    onRevokeDirectory: (String) -> Unit,
    storagePermissionsNeeded: Boolean,
    onRequestStoragePermission: () -> Unit,
    cameraPermission: Boolean,
    locationPermission: Boolean,
    notificationPermission: Boolean,
    onRequestPermission: (List<String>) -> Unit,
) {
    val capability = state.capability
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconFor(capability),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(PiSettingsMetrics.cardIconSize),
            )
            Spacer(Modifier.width(PiSettingsMetrics.rowGap))
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

        Spacer(Modifier.height(PiSpacing.inner))
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

        Spacer(Modifier.height(PiSpacing.inline))
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
                Spacer(Modifier.height(PiSpacing.inline))
                Text(
                    when (accessibilityState) {
                        "connected" -> "系统无障碍服务：运行中。"
                        "enabled_not_connected" ->
                            "系统无障碍服务：已启用，正在连接中 —— 通常一两秒内就绪，稍等再试即可，不需要改设置。"
                        else ->
                            "系统无障碍服务：未启用 —— 即使上面的开关打开，Agent 也无法读取或操作屏幕。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (accessibilityState) {
                        "connected" -> MaterialTheme.colorScheme.onSurfaceVariant
                        // Not an error the user has to fix; a warning colour would
                        // contradict the sentence right next to it.
                        "enabled_not_connected" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> PiTheme.palette.error
                    },
                )
                if (accessibilityState != "connected") {
                    TextButton(onClick = onOpenSystemSettings) { Text("前往系统设置") }
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    // The group is usable — dump/tap/input all work — but this one
                    // endpoint is not, and the switch above must not imply otherwise:
                    // `DeviceUiAutomation.screenshot` refuses with UNSUPPORTED below
                    // API 30 (DeviceUiAutomation.kt:609-616), which is what
                    // `/app/health`'s `screenshotSupported` reports (Router.kt:326).
                    Text(
                        "截屏：不可用 —— 无障碍截图需要 Android 11 及以上，" +
                            "本机是 Android ${Build.VERSION.RELEASE}；此时只有「Shell」组能截屏。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.error,
                    )
                }
            }

            DeviceCapability.Storage -> {
                Spacer(Modifier.height(PiSpacing.inline))
                for (line in DeviceSafStore.get(androidx.compose.ui.platform.LocalContext.current).summaryLines()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (grant in grants) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "· ${grant.name}",
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRevokeDirectory(grant.uri) }) { Text("撤销") }
                    }
                }
                TextButton(onClick = onGrantDirectory) { Text("授权目录") }
                if (storagePermissionsNeeded) {
                    Text(
                        "这台设备的 Android 版本还需要「存储」权限才能写公共 Download。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.warning,
                    )
                    TextButton(onClick = onRequestStoragePermission) { Text("授予存储权限") }
                }
            }

            DeviceCapability.Shell -> {
                Spacer(Modifier.height(PiSpacing.inline))
                // The backend that will run the *next* command, from the same live
                // probe the router uses (DeviceShell.kt:188-190). It must not be
                // `shizuku.backendLabel`: that field is `ShizukuShellBackend.label`,
                // whose value while Shizuku is not ready is literally
                // 「Shizuku（未安装/未运行/未授权）」(DeviceShizuku.kt:284-291) — a
                // backend that is going to run nothing, because `active()` falls back
                // to the app's own uid.
                Text(
                    "当前 Shell 后端：${DeviceShellGuard.active().label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    shizuku.optString("note"),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!shizuku.optBoolean("ready")) {
                    // The onboarding is the whole gap between "the Shizuku code
                    // exists" and "the user has uid 2000": every step that can be
                    // done on the phone itself, in the order they have to happen.
                    Spacer(Modifier.height(PiSpacing.small))
                    for (line in shizukuSteps(shizuku)) {
                        Text(
                            "· $line",
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onRequestShizuku,
                        enabled = shizuku.optBoolean("binderAlive") || shizuku.optBoolean("installed"),
                    ) { Text(if (shizuku.optBoolean("binderAlive")) "请求 Shizuku 授权" else "启动并授权 Shizuku") }
                    if (shizuku.optBoolean("installed")) {
                        TextButton(onClick = onOpenShizuku) { Text("打开 Shizuku") }
                    }
                    if (shizuku.optBoolean("ready") && shizuku.optInt("uid", -1) == 0) {
                        Text(
                            "Shizuku 以 root 运行",
                            style = PiTheme.text.meta,
                            color = PiTheme.palette.warning,
                        )
                    }
                }

                Spacer(Modifier.height(PiSpacing.gutter))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "放宽模式（命令替换与嵌套执行）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "开启后，\$() 与反引号、以及 sh/bash/eval/source 都会被允许。",
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = relaxed, onCheckedChange = onRelaxedChange)
                }
                if (relaxed) {
                    Text(
                        DeviceShellGuard.relaxedCost(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.warning,
                    )
                }
            }

            DeviceCapability.Sensors -> {
                Spacer(Modifier.height(PiSpacing.inline))
                // Two endpoint-level grants, stated as they are
                // (DeviceCapabilityStore.kt:207-211, 268-278). The camera one *must* be
                // requestable here: `cameraPrecondition()`'s hint tells the user to
                // press 「授予相机权限」 on this card (Store:275-276) and the model
                // relays that verbatim. The location grant is requested here too,
                // because until now nothing in the app ever asked for it
                // (`grep -rn ACCESS_FINE_LOCATION` outside the store and
                // `DeviceSystemActions` → no request), so the endpoint could only ever
                // answer NO_PERMISSION.
                if (locationPermission) {
                    Text(
                        "定位权限：已授予（还要系统定位开关打开、且有过一次定位结果）。",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "定位权限：未授予 —— Agent 无法读取位置；传感器与电池不受影响。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.warning,
                    )
                    TextButton(
                        onClick = {
                            onRequestPermission(
                                listOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                    ) { Text("授予定位权限") }
                }
                if (cameraPermission) {
                    Text(
                        "相机权限：已授予 —— 手电筒可用（部分设备还需要在系统里用过一次相机）。",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "相机权限：未授予 —— 手电筒不可用；位置、传感器与电池不受影响。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.warning,
                    )
                    TextButton(
                        onClick = { onRequestPermission(listOf(Manifest.permission.CAMERA)) },
                    ) { Text("授予相机权限") }
                }
            }

            DeviceCapability.Basic -> {
                Spacer(Modifier.height(PiSpacing.inline))
                // 基础 is on by default, so its badge reads 「可用」 out of the box —
                // and on API 33+ without POST_NOTIFICATIONS `android_say` is refused
                // every time (DeviceSystemActions.kt:103-112). Silence here is the one
                // thing the default-on group cannot afford.
                if (notificationPermission) {
                    Text(
                        "系统通知权限：已授予。",
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "系统通知权限：未授予 —— 发送通知会被拒绝；剪贴板、打开链接、分享、震动不受影响。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.warning,
                    )
                    TextButton(
                        onClick = { onRequestPermission(listOf(Manifest.permission.POST_NOTIFICATIONS)) },
                    ) { Text("授予通知权限") }
                }
            }
        }

        if (!state.usable && state.enabled && state.reason != null) {
            Spacer(Modifier.height(PiSpacing.inline))
            Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = PiTheme.palette.error,
            )
        }
    }
}

/**
 * The shell policy, in full.
 *
 * This card exists because the user asked for one thing above all: **a relaxation
 * the user cannot see is not allowed**. So it prints the whitelist, the hard
 * blocklist with a reason per entry, the write boundary, and where the syntax
 * policy stands — all read from [DeviceShellGuard], never retyped here.
 */
@Composable
private fun ShellPolicyCard(relaxed: Boolean, workspace: String) {
    Card {
        Text(
            "写入边界",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            workspace,
            // `DeviceWorkspace.summary()` is a machine line and nothing else:
            // 「写入边界 = 工作区：/data/user/0/app.pi/files/workspace（guest 内：/root/pi；
            // 终端标签页：/root）」. Three absolute paths in the UI face was the one place on
            // this screen where a path was not already mono — the audit log one card above
            // (`审计日志：$logPath`) and `DeviceShellGuard.allowedSummary()` below both are
            // (`monoSmall`), which is rule #7 applied to a path.
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (line in DeviceShellGuard.writeBoundarySummary()) {
            Text(
                "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            "语法策略",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        for (line in DeviceShellGuard.syntaxSummary(relaxed)) {
            Text(
                "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = if (relaxed) PiTheme.palette.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            "无论谁授权，下面这些都不会执行：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        for (line in DeviceShellGuard.blockedSummary()) {
            Text(
                "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            "命令白名单（未知命令一律拒绝）：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            DeviceShellGuard.allowedSummary(),
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(PiSpacing.gutter))
        Text(
            "危险操作（结束应用、Shell、分享、打开链接、向输入框写入、裸按键注入、跨沙箱读写文件）" +
                "第一次会请求确认，确认框里有「同意并记住本次会话」。",
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

// ---------------------------------------------------------------------------
// small pieces
// ---------------------------------------------------------------------------

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

/**
 * 状态徽标：形状照 `06 §2` 的徽标表（圆角 999、1px `borderMuted` 描边、
 * 色块 5×5、文字 12 正文色）。颜色仍只来自 M3 槽位，本批不改取色。
 */
@Composable
private fun StatusBadge(text: String, positive: Boolean) {
    val color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(PiShapes.badge)
            .border(PiSettingsMetrics.hairline, MaterialTheme.colorScheme.outline, PiShapes.badge)
            .padding(
                start = PiSettingsMetrics.badgePaddingStart,
                end = PiSettingsMetrics.badgePaddingEnd,
                top = PiSettingsMetrics.badgePaddingVertical,
                bottom = PiSettingsMetrics.badgePaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.badgeGap),
    ) {
        Box(
            Modifier
                .size(PiSettingsMetrics.badgeDot)
                .clip(RoundedCornerShape(PiSettingsMetrics.badgeDotRadius))
                .background(color),
        )
        Text(
            text,
            style = PiTheme.text.meta,
            color = MaterialTheme.colorScheme.onSurface,
        )
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

private fun iconFor(capability: DeviceCapability): ImageVector = when (capability) {
    DeviceCapability.Basic -> Icons.Filled.PhoneAndroid
    DeviceCapability.Storage -> Icons.Filled.Folder
    DeviceCapability.Accessibility -> Icons.Filled.TouchApp
    DeviceCapability.Sensors -> Icons.Filled.Security
    DeviceCapability.Shell -> Icons.Filled.Terminal
}

/** Open the Shizuku manager app, if this device has one. */
private fun openShizukuManager(context: android.content.Context): Boolean = runCatching {
    val intent = context.packageManager.getLaunchIntentForPackage(DeviceShizuku.MANAGER_PACKAGE) ?: return false
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

/**
 * The self-serve Shizuku onboarding, derived from the live status object rather
 * than written once: the steps a user still has to take are exactly the fields
 * that are not there yet (installed → running → granted).
 *
 * Android 11+ is the reason this is onboarding and not a README: 无线调试 lets
 * the user start Shizuku on the phone itself, with no computer and no ADB cable —
 * which is the difference between "uid 2000 is possible" and "uid 2000 is what
 * this app actually gets".
 */
private fun shizukuSteps(shizuku: JSONObject): List<String> {
    if (!shizuku.optBoolean("installed")) {
        return listOf(
            "装 Shizuku：从应用商店或它的 GitHub Releases 安装「Shizuku」；它本身不需要 root。",
            "装好后回到这里，下面会出现「启动并授权 Shizuku」。",
        )
    }
    if (!shizuku.optBoolean("binderAlive")) {
        return listOf(
            "打开 Shizuku，在它的界面里点「通过无线调试启动」（Android 11+，全程在这台手机上，不需要电脑）。",
            "系统设置 → 开发者选项 → 无线调试：打开它，再在 Shizuku 里按提示配对。",
            "Shizuku 启动后回到这里，点「请求 Shizuku 授权」。",
            "重启手机后 Shizuku 会停止，需要再做一次这一步。",
        )
    }
    if (!shizuku.optBoolean("permissionGranted")) {
        return listOf("点「请求 Shizuku 授权」，在 Shizuku 自己的弹窗里选择允许。")
    }
    if (shizuku.optBoolean("preV11")) {
        return listOf("这台设备上的 Shizuku 是 v11 之前的版本，API 不支持；请升级 Shizuku。")
    }
    return emptyList()
}

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

