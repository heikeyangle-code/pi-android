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

    /**
     * 放宽模式: the opt-in that lets the shell guard accept command substitution
     * (`$(...)`, backticks) and the nesting heads (`sh`, `eval`, `source`, …).
     *
     * One stored boolean, read by three consumers: the Kotlin guard
     * ([DeviceShellGuard.inspect]), the authorization page, and the pi-side
     * permission gate — the gate reads it from `/app/health`. That single source is
     * the point: a mode only one side honoured would be worse than no mode, because
     * the disagreement between "the dialog let it through" and "the guard refuses"
     * is invisible.
     *
     * Default OFF, and it is not part of any capability group: turning 「Shell」 on
     * must not silently widen what shell *syntax* is allowed.
     */
    fun isShellSyntaxRelaxed(): Boolean = prefs.getBoolean(KEY_RELAXED_SHELL, false)

    fun setShellSyntaxRelaxed(relaxed: Boolean) {
        prefs.edit().putBoolean(KEY_RELAXED_SHELL, relaxed).apply()
    }

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
                reason = "无障碍服务已启用，但系统还没有把它连上（正在重连，通常一两秒内完成）。",
                hint = "请稍等约 1 秒后重试同一次调用；不需要改任何设置。若持续如此，再让用户关闭并重新打开「设置 → 无障碍 → PI 设备桥」。",
                retryable = true,
            )

            DeviceAccessibilityService.State.NOT_ENABLED -> DeviceDenial(
                code = DeviceDenial.NO_PERMISSION,
                reason = "无障碍能力已开启，但系统的无障碍服务没有启用，因此无法读取或操作屏幕。",
                hint = "请让用户打开 PI，在「设置 → 设备能力 → 无障碍」点「前往系统设置」并启用「PI 设备桥」，然后重试。",
            )
        }

        DeviceCapability.Sensors -> sensorsPrecondition()

        DeviceCapability.Storage ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ has MediaStore, which needs no storage permission for the
                // app's own exports; SAF grants cover everything else.
                null
            } else if (hasLegacyStoragePermission()) {
                null
            } else {
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "这台设备（Android ${Build.VERSION.RELEASE}）导出文件需要存储权限，当前未授予。",
                    hint = "请让用户在「设置 → 设备能力 → 存储」点「授予存储权限」，或在系统设置里为本应用打开存储权限。",
                )
            }

        DeviceCapability.Shell ->
            if (DeviceShellGuard.backends().any { it.available }) {
                null
            } else {
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "Shell 能力已开启，但可用的执行后端不存在。",
                    hint = "本版本只带应用自身身份（uid=${android.os.Process.myUid()}）的后端；Shizuku / ADB 无线调试配对尚未接入。",
                )
            }

        DeviceCapability.Basic -> null
    }

    /**
     * Location needs two things at once (the group switch and a runtime grant),
     * but the group also carries sensors and the torch, which need neither. A
     * missing grant must therefore degrade the *endpoint*, not the whole group.
     */
    private fun sensorsPrecondition(): DeviceDenial? = null

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
     * The 「位置 · 传感器 · 相机」 group also carries location, the sensor list and
     * the battery reading, none of which need CAMERA. So a missing grant must not
     * make the whole group unusable (the same reasoning as
     * [sensorsPrecondition]) — but it must be reported as `NO_PERMISSION` with the
     * camera permission named, and never as "this device has no flash". Before the
     * manifest declared CAMERA, that mis-reporting was guaranteed on every ROM that
     * enforces the permission.
     */
    fun cameraPrecondition(): DeviceDenial? =
        if (hasCameraPermission()) {
            null
        } else {
            DeviceDenial(
                code = DeviceDenial.NO_PERMISSION,
                reason = "控制手电筒需要相机权限（Android 6 起 CameraManager.setTorchMode 要求 CAMERA），当前未授予。",
                hint = "请让用户在「设置 → 设备能力 → 位置·传感器·相机」点「授予相机权限」，" +
                    "或在系统设置 → 应用 → PI → 权限 中打开相机权限；部分设备还需要先在系统里用过一次相机。",
            )
        }

    companion object {
        private const val PREFS_NAME = "pi-device-capabilities"
        private const val KEY_RELAXED_SHELL = "shell.relaxed-syntax"

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
