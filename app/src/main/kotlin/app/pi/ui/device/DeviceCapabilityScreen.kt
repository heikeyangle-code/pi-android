package app.pi.ui.device

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Keyboard
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
import app.pi.bridge.DeviceAdmin
import app.pi.bridge.DeviceApprovalLedger
import app.pi.bridge.DeviceBridgeController
import app.pi.bridge.DeviceCapability
import app.pi.bridge.DeviceCapabilityState
import app.pi.bridge.DeviceCapabilityStore
import app.pi.bridge.DeviceSafStore
import app.pi.bridge.DeviceShellGuard
import app.pi.bridge.DeviceShizuku
import app.pi.bridge.DeviceWorkspace
import app.pi.bridge.PiCaptureService
import app.pi.bridge.PiInputMethodService
import app.pi.bridge.PiScreenCapture
import app.pi.bridge.PiVpnService
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
 * screen is also the *policy* screen: the shell card shows the hard blocklist (the one
 * rule left, currently empty) and says plainly that the command filter is gone; the
 * 基础 card shows the granted SAF
 * directories; and the approvals card shows what the pi-side permission gate has
 * been told to stop asking about. None of that is inferred — it is read from the
 * same objects the enforcement reads ([DeviceShellGuard], [DeviceCapabilityStore],
 * [DeviceSafStore], [DeviceWorkspace], [DeviceShizuku]).
 *
 * The authority is [DeviceCapabilityStore]: it persists the switches and the HTTP
 * server re-reads it on every request, so a change here takes effect immediately —
 * no restart, no reload. There is deliberately no "apply" button.
 *
 * ### 「立即生效」只对执行侧成立
 *
 * 随包扩展 `pi-android-bridge` 在**加载时**按这一屏的同一份能力状态决定注册哪些 `android_*`
 * 工具（读 `/app/health` 的 `capabilities[].usable`），而 pi 的 `registerTool` 只在扩展工厂
 * 跑的时候登记一次，没有“每次请求重算工具表”的钩子。所以关掉一组能力后：桥立刻开始拒绝那一组
 * 的端点（上面那句仍然成立），但**模型提示词里的那些工具**要等下一次加载扩展才消失 —— 开一个
 * 新会话、`/device-reload` 重载扩展或重启引擎都会重新加载扩展。用户看到的是「能力授权」标题下
 * 那份 [InfoNote]，两处说的是同一件事。
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
    var shizuku by remember { mutableStateOf(DeviceShizuku.status(context)) }
    var workspace by remember { mutableStateOf(DeviceWorkspace.summary()) }
    var storagePermissionsNeeded by remember { mutableStateOf(!store.hasLegacyStoragePermission()) }
    // 输入法与设备管理器的三态读数。两者都不是「开关打开就能用」的能力：输入法要用户
    // 在系统里启用并切成当前输入法，管理员要一档身份 —— 卡片必须能把缺的那一步说出来，
    // 否则一个「待授权」徽标等于什么都没说。这里的三个布尔与
    // `DeviceCapabilityStore.androidPrecondition` 读的是同一组事实，所以卡片与端点不会分叉。
    var imeEnabled by remember { mutableStateOf(PiInputMethodService.available(context)) }
    var imeDefault by remember { mutableStateOf(PiInputMethodService.isDefaultInputMethod(context)) }
    var imeRunning by remember { mutableStateOf(PiInputMethodService.running() != null) }
    // 整份身份自述（哪一档身份、每项策略能不能做）由组件给，卡片不自己拼。
    var admin by remember { mutableStateOf(DeviceAdmin.status(context)) }
    // VPN 与投屏这两条授权只能由界面发起，所以这一屏得知道它们当前是不是在跑。
    var vpnRunning by remember { mutableStateOf(PiVpnService.available(context)) }
    var capturing by remember { mutableStateOf(PiScreenCapture.isCapturing()) }
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

    // VPN 与投屏的授权对话框**只能**由一个 Activity 拉起：`VpnService.prepare()` 与
    // `createScreenCaptureIntent()` 返回的就是那个系统对话框的 Intent，而波1 的两个
    // 后台组件（PiVpnService / PiScreenCapture）明确不持有 Activity。
    // 没有这两个 launcher，`/app/vpn/start` 与 `/app/capture/grab` 在真机上永远拿不到
    // 授权 —— 那正是它们此前只会回 NO_PERMISSION 的原因。
    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        note = if (result.resultCode == Activity.RESULT_OK) {
            if (PiVpnService.start(context)) "VPN 已启动。" else "系统已授权，但隧道没起 —— 原因见上面的状态行。"
        } else {
            "VPN 授权被拒绝；用户可以在需要时再点一次「授权 VPN」。"
        }
        vpnRunning = PiVpnService.available(context)
        revision += 1
    }
    val captureConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // 顺序不能反：Android 14 要求 mediaProjection 类型的前台服务在该次
        // getMediaProjection() 之前就已经在跑。所以先起 PiCaptureService，再接授权。
        note = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            PiCaptureService.start(context)
            val attached = PiScreenCapture.attach(context, result.resultCode, result.data)
            if (attached.optBoolean("ok", false)) "投屏已开始。" else "投屏失败：${attached.optString("error")}"
        } else {
            "投屏授权被拒绝；用户可以在需要时再点一次「开始投屏」。"
        }
        capturing = PiScreenCapture.isCapturing()
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
                imeEnabled = PiInputMethodService.available(context)
                imeDefault = PiInputMethodService.isDefaultInputMethod(context)
                imeRunning = PiInputMethodService.running() != null
                admin = DeviceAdmin.status(context)
                vpnRunning = PiVpnService.available(context)
                capturing = PiScreenCapture.isCapturing()
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

            item {
                // 开关对桥的**执行侧**立即生效（`DeviceCapabilityStore.check()` 每个请求现读），
                // 但随包扩展注册哪些 android_* 工具是在扩展加载时定下的：pi 的 `registerTool`
                // 只在扩展工厂跑的时候登记一次，没有“每次请求重算工具表”的钩子。这是该对用户
                // 说的生效口径 —— 和 `pi-android-bridge/index.ts` 注册循环上方的注释是同一件事。
                InfoNote(
                    "这些开关立即改变设备桥允许做什么；模型看到的 android_* 工具清单要等下次加载扩展才重算 —— " +
                        "开一个新会话（或重启引擎）之后，关掉的那组工具才会从提示词里消失。",
                )
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
                    imeEnabled = imeEnabled,
                    imeDefault = imeDefault,
                    imeRunning = imeRunning,
                    // 与无障碍那张卡同一个套路：用户要做的动作在系统设置里，那就得给一扇门，
                    // 而门打不开时要说话 —— 有些 ROM 没有对应的设置页，静默失败等于按钮是死的。
                    onOpenInputMethodSettings = {
                        val opened = runCatching {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }.isSuccess
                        if (!opened) {
                            note = "无法打开系统的输入法设置页。请手动进入 系统设置 → 系统 → 语言和输入法，" +
                                "启用「PI 设备桥」并把它选为当前输入法。"
                        }
                    },
                    admin = admin,
                    vpnRunning = vpnRunning,
                    onRequestVpn = {
                        // `prepare()` 返回 null 有两种含义：已经授权，或系统没有 VPN 服务。
                        // 两种都不需要弹窗，直接尝试启动并让状态行说实话。
                        val consent = PiVpnService.consentIntent(context)
                        if (consent == null) {
                            note = if (PiVpnService.start(context)) "VPN 已启动。" else "VPN 已授权，但启动失败。"
                            vpnRunning = PiVpnService.available(context)
                            revision += 1
                        } else {
                            vpnConsent.launch(consent)
                        }
                    },
                    capturing = capturing,
                    onStartCapture = {
                        val consent = PiScreenCapture.consentIntent(context)
                        if (consent == null) {
                            note = "系统没有投屏服务（MediaProjectionManager 不可用）。"
                        } else {
                            captureConsent.launch(consent)
                        }
                    },
                    onOpenDeviceAdminSettings = {
                        // 公开 API 里没有「设备管理应用列表」这个 action，安全设置页是它所在的那一层
                        // （AOSP 在 安全 → 更多安全设置 → 设备管理应用）。
                        val opened = runCatching {
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        }.isSuccess
                        if (!opened) {
                            note = "无法打开系统的安全设置页。请手动进入 系统设置 → 安全 → 设备管理应用，" +
                                "激活「PI 设备桥」。"
                        }
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
                ShellPolicyCard(workspace = workspace)
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
    imeEnabled: Boolean,
    imeDefault: Boolean,
    imeRunning: Boolean,
    onOpenInputMethodSettings: () -> Unit,
    admin: JSONObject,
    onOpenDeviceAdminSettings: () -> Unit,
    vpnRunning: Boolean,
    onRequestVpn: () -> Unit,
    capturing: Boolean,
    onStartCapture: () -> Unit,
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
                // 这里原本是「放宽模式」开关。它控制的两件事 —— 命令替换检查与白名单 ——
                // 在 DeviceShellGuard.inspect 里都已取消（用户要求「没有白名单这一说，
                // 只剩一个黑名单」），所以开关留着也拨不动任何东西。一个拨了没反应的
                // 开关比没有开关更糟，因此换成一句现状说明。
                //
                // relaxed / onRelaxedChange 保留在签名里不动，免得改动 Shell 这一屏的
                // 调用点（它们现在没人读，只剩下编译器的一条未使用警告）。
                Text(
                    "命令替换与嵌套执行：已无条件放行",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "\$() 与反引号不再被检查；sh/bash/eval/source 也不再需要单独开关。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DeviceCapability.Ime -> {
                Spacer(Modifier.height(PiSpacing.inline))
                // 三种状态，不是一个布尔：在系统里启用、被选为当前输入法、服务被绑定，是三件
                // 不同的事，缺哪一件用户要做的动作都不同（启用→去系统设置；切换→去键盘选择器；
                // 绑定→等一两秒）。读数与 DeviceCapabilityStore 的组级前置同一组事实。
                val ready = imeEnabled && imeDefault
                Text(
                    when {
                        !imeEnabled ->
                            "输入法：未在系统中启用 —— 即使上面的开关打开，Agent 也读不到、改不了你正在输入的内容。"
                        !imeDefault ->
                            "输入法：已启用，但当前输入法不是它 —— 读取或改写输入框前，需要先把它切成当前输入法。"
                        imeRunning -> "输入法：已是当前输入法，服务已连上。"
                        else -> "输入法：已是当前输入法，服务正在连上（通常一两秒内就绪，稍等重试即可）。"
                    },
                    style = PiTheme.text.meta,
                    // 未就绪不是错误，只是还差用户一步；用错误色会把「就差一步」读成「出故障了」。
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "密码框一律不读内容（只留一条「发生过输入」的记录）；输入历史只留在内存里，进程结束即消失。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!ready) {
                    TextButton(onClick = onOpenInputMethodSettings) { Text("前往输入法设置") }
                }
            }

            DeviceCapability.Admin -> {
                Spacer(Modifier.height(PiSpacing.inline))
                // 身份那一句直接由 DeviceAdmin.status(context) 给：它按当前是哪一档身份
                // （Device Owner / Profile Owner / 普通设备管理员 / 都没有）说哪一档能做什么。
                // 卡片再抄一遍就一定会跟它分叉。
                Text(
                    admin.optString("note"),
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!admin.optBoolean("owner")) {
                    Text(
                        "现在只能读状态：改策略需要 Device Owner 或 Profile Owner（隐藏/挂起应用、CA 证书、" +
                            "常驻 VPN、Lock Task、更新策略、权限授予状态、擦除），状态栏、锁屏与重启只有 Device Owner 能做。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiTheme.palette.warning,
                    )
                }
                TextButton(onClick = onOpenDeviceAdminSettings) { Text("打开系统设备管理设置") }
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

                // 文件（原「存储」组并入 基础）。The SAF picker is the whole reason this
                // group is no longer documented as "无": a picker needs an Activity, and
                // this screen is one. Persisting the URI permission is what makes the
                // grant survive a restart.
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

                // 定位与相机（原「位置 · 传感器 · 相机」组并入 基础）：两者都是端点级授权，
                // 缺了不影响整组 —— 传感器、电池、剪贴板照常。The camera one *must* be
                // requestable here: `cameraPrecondition()`'s hint tells the user to press
                // 「授予相机权限」 on this card and the model relays that verbatim. The
                // location grant is requested here too, because nothing else in the app
                // ever asks for it (`grep -rn ACCESS_FINE_LOCATION` outside the store and
                // `DeviceSystemActions` → no request), so the endpoint could only ever
                // answer NO_PERMISSION.
                Spacer(Modifier.height(PiSpacing.inline))
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

                // VPN 与投屏（并入 基础）：两者的授权都只能在这里发起 —— 系统对话框
                // 需要一个 Activity，而波1 的后台组件不持有 Activity。这两个按钮是
                // `/app/vpn/start` 与 `/app/capture/grab` 在真机上唯一的授权入口。
                Spacer(Modifier.height(PiSpacing.inline))
                Text(
                    if (vpnRunning) {
                        "本地 VPN：运行中（DNS 拦截与流量可见性生效）。"
                    } else {
                        "本地 VPN：未运行 —— 先点「授权 VPN」过一次系统对话框。"
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRequestVpn) { Text("授权 VPN") }
                Spacer(Modifier.height(PiSpacing.small))
                Text(
                    if (capturing) {
                        "投屏：进行中。"
                    } else {
                        "投屏：未开始 —— 先点「开始投屏」过一次系统对话框。"
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onStartCapture) { Text("开始投屏") }
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
 * the user cannot see is not allowed**. It is short now because there is almost
 * nothing left to show: one hard blocklist (empty) and the plain fact that the
 * command filter is gone. Everything is read from [DeviceShellGuard], never
 * retyped here.
 */
@Composable
private fun ShellPolicyCard(workspace: String) {
    Card {
        Text(
            "工作区",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            workspace,
            // `DeviceWorkspace.summary()` is a machine line and nothing else:
            // 「工作区：/data/user/0/app.pi/files/workspace（guest 内：/root/pi；
            // 终端标签页：/root）」. Three absolute paths in the UI face was the one place on
            // this screen where a path was not already mono — the audit log one card above
            // (`审计日志：$logPath`) is `monoSmall` for the same reason, which is rule #7
            // applied to a path.
            style = PiTheme.text.monoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "· 这不再是写入边界：Shell 不限制写哪里，工作区只是 agent 的默认落点。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            "Shell 命令过滤：已取消",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "· 命令名不检查（su / mount / dd / 任意二进制都能发出去）、写入不限路径、\$() 与反引号不检查。\n" +
                "· 成败只看身份：装了 Shizuku 是 ADB 级 uid 2000，否则是应用自身身份（pm、input、dumpsys 会失败）。\n" +
                "· 要改回来：往 DeviceShellGuard.hardBlocks 里加规则，旧的白名单已删。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(PiSpacing.inline))
        Text(
            "无论谁授权都不会执行的命令（当前：无）：",
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
    DeviceCapability.Accessibility -> Icons.Filled.TouchApp
    DeviceCapability.Shell -> Icons.Filled.Terminal
    // 输入法用键盘、管理员用盾牌：两张卡在列表里紧挨着，图标不一样才不用读标题。
    DeviceCapability.Ime -> Icons.Filled.Keyboard
    DeviceCapability.Admin -> Icons.Filled.AdminPanelSettings
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

