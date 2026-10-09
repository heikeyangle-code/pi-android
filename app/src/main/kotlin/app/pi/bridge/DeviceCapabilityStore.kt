package app.pi.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Collections

/**
 * A refusal with a machine-readable code and a human sentence.
 *
 * [code] deliberately mirrors the DSH bridge semantics the design doc asks for
 * (design §21.4: 沿用 DISABLED/NO_PERMISSION 语义), and the model is instructed to
 * relay [reason] verbatim rather than invent an explanation.
 */
data class DeviceDenial(
    val code: String,
    val reason: String,
    val hint: String? = null,
    /**
     * True when calling the exact same endpoint again, unchanged, has a real
     * chance of succeeding. This is the one field that tells the model the
     * difference between 「重试」 and 「找人」, so it is a property of the code
     * rather than something each call site has to remember to state:
     * `BUSY`, `NOT_CONNECTED` and a dropped bridge are retryable; a disabled
     * capability, a policy block and a bad parameter never are.
     */
    val retryable: Boolean = false,
) {
    companion object {
        const val DISABLED = "DISABLED"
        const val NO_PERMISSION = "NO_PERMISSION"
        const val UNSUPPORTED = "UNSUPPORTED"
        const val BAD_REQUEST = "BAD_REQUEST"
        const val NOT_FOUND = "NOT_FOUND"
        const val UNAUTHORIZED = "UNAUTHORIZED"
        const val ERROR = "ERROR"
        const val BLOCKED_BY_POLICY = "BLOCKED_BY_POLICY"

        /**
         * Another gesture is already in flight on the accessibility channel, or
         * the platform cancelled ours. Nothing about the request is wrong: wait
         * ~1s and send it again. Distinct from [UNSUPPORTED] on purpose — the old
         * code returned UNSUPPORTED for both, which reads as "this device cannot
         * do it" and sends the model (and the user) down the wrong path.
         */
        const val BUSY = "BUSY"

        /**
         * The accessibility service is enabled in Settings but the platform has
         * not (re)bound it yet — the normal state for a second or two after the
         * user flips the switch, after a reboot, or after the app is updated.
         * Not a settings problem, so the wording must not send the user back to
         * Settings: it is a "wait 1s and retry" state.
         */
        const val NOT_CONNECTED = "NOT_CONNECTED"

        /**
         * `startActivity` returned without throwing (Android 10+ does not throw
         * for a dropped background start) and the foreground package never
         * changed. The request was not delivered, so this must never be reported
         * as a success.
         */
        const val BLOCKED_BACKGROUND = "BLOCKED_BACKGROUND"

        /**
         * The engine could not reach the loopback bridge at all: the app process
         * is gone, the bridge is stopped, or the token is stale after a restart.
         */
        const val BRIDGE_DOWN = "BRIDGE_DOWN"
    }

    fun toMessage(): String = buildString {
        append('[').append(code).append("] ").append(reason)
        if (hint != null) append("\n提示：").append(hint)
    }
}

/**
 * The opt-in state behind the authorization page (UI spec §5.6).
 *
 * Two layers, on purpose:
 *  - **persisted** — the card's switch. Stored in SharedPreferences, survives
 *    restarts, and is the user's standing decision.
 *  - **session** — the card's 「本会话暂时禁用」 quick switch. In memory only, so
 *    a user who is nervous about one task can cut a capability without having to
 *    remember to turn it back on afterwards.
 *
 * The store also folds in the Android-side preconditions (the accessibility
 * service being enabled, a runtime permission being granted) so that a group can
 * be switched *on* and still report a precise reason why it cannot work yet.
 * That distinction is the whole point: "关闭" and "已开启但系统未授权" need
 * different actions from the user, so they must not read the same.
 *
 * A single instance per process is shared with the UI (see [get]) so the
 * in-memory session overrides are the same set the HTTP server consults.
 */
class DeviceCapabilityStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionDisabled: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** The user's standing decision for [capability], ignoring the session switch. */
    fun isPersistentlyEnabled(capability: DeviceCapability): Boolean =
        prefs.getBoolean(prefKey(capability), capability.defaultEnabled)

    fun isSessionDisabled(capability: DeviceCapability): Boolean =
        sessionDisabled.contains(capability.id)

    /** Persisted decision **and** not cut for this session. */
    fun isEnabled(capability: DeviceCapability): Boolean =
        isPersistentlyEnabled(capability) && !isSessionDisabled(capability)

    fun setEnabled(capability: DeviceCapability, enabled: Boolean) {
        prefs.edit().putBoolean(prefKey(capability), enabled).apply()
        // Switching a group off clears its session override, so turning it back
        // on cannot leave a stale "暂时禁用" behind.
        if (!enabled) sessionDisabled.remove(capability.id)
    }

    fun setSessionDisabled(capability: DeviceCapability, disabled: Boolean) {
        if (disabled) sessionDisabled.add(capability.id) else sessionDisabled.remove(capability.id)
    }

    fun state(capability: DeviceCapability): DeviceCapabilityState {
        val persisted = isPersistentlyEnabled(capability)
        val sessionOff = isSessionDisabled(capability)
        val denial = androidPrecondition(capability)
        val usable = persisted && !sessionOff && denial == null
        val reason = when {
            !persisted ->
                "设备能力「${capability.title}」已关闭。请让用户在 pi-android 的「设置 → 设备能力」中打开「${capability.title}」后重试。"
            sessionOff ->
                "设备能力「${capability.title}」在本会话中被用户暂时禁用（App 里的「本会话暂时禁用」开关）。请让用户在本会话内重新打开它，或开一个新会话。"
            denial != null -> denial.reason
            else -> null
        }
        return DeviceCapabilityState(
            capability = capability,
            enabled = persisted,
            enabledForSession = !sessionOff,
            usable = usable,
            reason = reason,
        )
    }

    fun states(): List<DeviceCapabilityState> = DeviceCapability.entries.map { state(it) }

    /**
     * The gate every endpoint goes through. Returns `null` when the capability is
     * usable, otherwise a [DeviceDenial] carrying the reason to relay.
     */
    fun check(capability: DeviceCapability): DeviceDenial? {
        val state = state(capability)
        if (state.usable) return null
        val denial = androidPrecondition(capability)
        if (denial != null && denial.retryable && state.enabled && state.enabledForSession) {
            // A transient system-side state (the accessibility service is still
            // rebinding) must keep its own code: reporting it as NO_PERMISSION
            // reads as "go to Settings", which is exactly what the user should
            // *not* do. `state.reason` is the same sentence, kept single-sourced.
            return denial.copy(reason = state.reason ?: denial.reason)
        }
        val code = if (!state.enabled || !state.enabledForSession) {
            DeviceDenial.DISABLED
        } else {
            DeviceDenial.NO_PERMISSION
        }
        return DeviceDenial(
            code = code,
            reason = state.reason ?: "设备能力不可用。",
            hint = denial?.hint,
        )
    }

    /**
     * Android-side precondition for a group. The accessibility group is the one
     * that needs a system service rather than a permission; getting this wrong in
     * either direction produces the worst UX in the app (a switch that looks on
     * and does nothing), so it is checked here rather than at call time.
     */
    private fun androidPrecondition(capability: DeviceCapability): DeviceDenial? = when (capability) {
        DeviceCapability.Accessibility -> when (DeviceAccessibilityService.state(appContext)) {
            DeviceAccessibilityService.State.CONNECTED -> null

            // Enabled in Settings but not bound yet. Two opposite mistakes live
            // here and both were made before: telling the user to go to Settings
            // (they already did), or reporting a flat failure (it recovers by
            // itself). It is a retry state, so it says so and carries a code the
            // caller can act on.
            DeviceAccessibilityService.State.ENABLED_NOT_CONNECTED -> DeviceDenial(
                code = DeviceDenial.NOT_CONNECTED,
                reason = "无障碍已启用但系统未连上（正在重连）。",
                hint = "等约 1 秒重试；仍失败让用户重开「设置 → 无障碍 → PI 设备桥」。",
                retryable = true,
            )

            DeviceAccessibilityService.State.NOT_ENABLED -> DeviceDenial(
                code = DeviceDenial.NO_PERMISSION,
                reason = "无障碍服务未启用，无法操作屏幕。",
                hint = "让用户在「设置 → 设备能力 → 无障碍」启用 PI 设备桥。",
            )
        }

        DeviceCapability.Shell ->
            if (DeviceShellGuard.backends().any { it.available }) {
                null
            } else {
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "Shell 已开启但没有可用后端。",
                    hint = "应用自身身份后端（uid=${android.os.Process.myUid()}）；Shizuku 未接入。",
                )
            }

        // 原「位置 · 传感器 · 相机」组并入 基础：那一组本来就没有组级前置（位置与
        // 手电筒都是端点级，见 [cameraPrecondition]），所以合并后唯一带过来的组级
        // 检查是旧「存储」组的 pre-API-29 存储权限。
        DeviceCapability.Basic ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ has MediaStore, which needs no storage permission for the
                // app's own exports; SAF grants cover everything else.
                null
            } else if (hasLegacyStoragePermission()) {
                null
            } else {
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "Android ${Build.VERSION.RELEASE} 导出需要存储权限。",
                    hint = "让用户在「设置 → 设备能力 → 基础」点「授予存储权限」。",
                )
            }

        // 输入法：组级问两件事 —— 系统里启用了吗、它是不是当前输入法。两者必须分开说，
        // 因为用户要做的动作不同：前者去系统设置里启用，后者只要在键盘切换器里切一下。
        //
        // 「已启用但不是当前输入法」当 denial，而不是放行，理由与无障碍那条一样：这一组
        // 的每个读/写动作都先得有当前输入框，放行只会换来一串「没有可写的输入框」
        // （PiInputMethodService.withConnection），而那句话说不清用户该做什么。注意它与
        // 无障碍的 NOT_CONNECTED 不同：这里不会自己好，必须有人在键盘选择器里点一下，
        // 所以不是 retryable。
        DeviceCapability.Ime -> when {
            !PiInputMethodService.available(appContext) -> DeviceDenial(
                code = DeviceDenial.NO_PERMISSION,
                reason = "本应用的输入法还没在系统里启用，读不到也改不了输入框。",
                hint = "让用户在「设置 → 系统 → 语言和输入法」里启用「PI 设备桥」，再把它选为当前输入法。",
            )

            !PiInputMethodService.isDefaultInputMethod(appContext) -> DeviceDenial(
                code = DeviceDenial.NO_PERMISSION,
                reason = "本应用的输入法已启用，但当前输入法不是它：输入框还没交给它。",
                hint = "让用户在键盘切换器里切到「PI 设备桥」（输入框弹出键盘时下方那颗键盘图标），再重试。",
            )

            else -> null
        }

        // 管理员：契约指定的前置就是 Device Owner。[DeviceAdmin] 里那张表说明为什么：
        // 能改策略的动作（应用隐藏/挂起、CA 证书、常驻 VPN、Lock Task、更新策略、权限
        // 授予状态、擦除）需要 Owner 身份，状态栏/锁屏/重启只有 Device Owner 能做，普通
        // 设备管理员身份只够当成升级到 DO/PO 的前置。所以组级先把「不是 DO」拦下，并
        // 按契约说清只剩读的部分（身份与可执行性仍从 /app/capabilities 的 admin 条目
        // 与 App 的能力页读得到）。
        //
        // 代价写在明处：Profile Owner（工作资料）设备上，这一组也会被这条前置拦住 ——
        // 包括 PO 真能做的隐藏/挂起应用。要让 PO 也放行，得把这条前置改成
        // [DeviceAdmin.available]，并把差异交给每个端点的身份检查（DeviceAdmin 的
        // ownerDenial / deviceOwnerDenial），那是名单另一档的事。
        DeviceCapability.Admin ->
            // 三档身份都算「可用」：普通设备管理员只需要用户在一个系统弹窗上点确认就能拿到，
            // 而 DeviceAdmin.available() 就是那个宽松判定（deviceOwner / profileOwner /
            // adminActive）。以前这里只认 Device Owner，后果是连「我现在是哪一档身份」都
            // 问不出来 —— 而本组 allows 的第一行写的就是「读取当前身份下每项策略能不能执行」。
            // 诊断被自己的前置挡住，前后矛盾，也让人以为这组整个废了。
            if (DeviceAdmin.available(appContext)) {
                null
            } else {
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "本应用没有任何一档设备管理身份（普通设备管理员 / Profile Owner / Device Owner）。" +
                        "两档 owner 身份才能改策略；普通设备管理员只能改声明过的那两条（禁用摄像头、禁用锁屏功能）。",
                    hint = "/app/admin/status 与 /app/admin/capabilities 在任何身份下都能调，先用它们看现状。" +
                        "普通设备管理员不需要 adb：在 App 的「设置 → 设备能力 → 管理员」里点激活，系统会弹确认框。" +
                        "Device Owner 才要 adb：`adb shell dpm set-device-owner " +
                        "${appContext.packageName}/${PiDeviceAdminReceiver::class.java.name}`" +
                        "（设备必须没有已登录账号、且从未设过 Device Owner —— 日常在用的手机通常有账号，" +
                        "所以这条路多半要恢复出厂才能走通）。",
                )
            }
    }

    /** True when the app already holds the location runtime permission. */
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * The pre-API-29 storage path.
     *
     * The manifest declares `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="29"` and
     * `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`, so both are requestable
     * exactly where they still mean something and invisible above that. Before this
     * the code told the user to add the permission to the manifest — the reason
     * `android_download` simply could not work on Android 8/9,
     * which `minSdk 26` says this app supports.
     */
    fun hasLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        val read = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE)
        if (read != PackageManager.PERMISSION_GRANTED) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val write = ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return write == PackageManager.PERMISSION_GRANTED
    }

    /** The permissions the storage card should request on this API level. */
    fun legacyStoragePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True when the app may post notifications (always true below API 33). */
    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** True when the app holds the VIBRATE permission (a normal permission). */
    fun hasVibratePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.VIBRATE) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the app holds CAMERA, which `setTorchMode` has required since API 23. */
    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The camera precondition, checked per *endpoint* rather than per group.
     *
     * The 基础 group also carries location, the sensor list, the battery reading and
     * the clipboard, none of which need CAMERA. So a missing grant must not
     * make the whole group unusable — but it must be reported as `NO_PERMISSION` with
     * the camera permission named, and never as "this device has no flash". Before the
     * manifest declared CAMERA, that mis-reporting was guaranteed on every ROM that
     * enforces the permission.
     */
    fun cameraPrecondition(): DeviceDenial? =
        if (hasCameraPermission()) {
            null
        } else {
            DeviceDenial(
                code = DeviceDenial.NO_PERMISSION,
                reason = "控制手电筒需要 CAMERA 权限（API 23+ 要求）。",
                hint = "让用户在「设置 → 设备能力 → 基础」点「授予相机权限」；部分设备需先用一次相机。",
            )
        }

    companion object {
        private const val PREFS_NAME = "pi-device-capabilities"

        @Volatile
        private var instance: DeviceCapabilityStore? = null

        fun get(context: Context): DeviceCapabilityStore {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: DeviceCapabilityStore(context).also { instance = it }
            }
        }

        private fun prefKey(capability: DeviceCapability): String = "enabled." + capability.id
    }
}
