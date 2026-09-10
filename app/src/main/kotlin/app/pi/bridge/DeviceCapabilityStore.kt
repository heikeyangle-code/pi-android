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
        val code = if (!state.enabled || !state.enabledForSession) {
            DeviceDenial.DISABLED
        } else {
            DeviceDenial.NO_PERMISSION
        }
        val denial = androidPrecondition(capability)
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
        DeviceCapability.Accessibility ->
            if (DeviceAccessibilityService.isRunning()) {
                null
            } else {
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "无障碍能力已开启，但系统的无障碍服务没有在运行，因此无法读取或操作屏幕。",
                    hint = "请让用户打开 pi-android，在「设置 → 设备能力 → 无障碍」点「前往系统设置」并启用「pi 设备桥」，然后重试。",
                )
            }

        DeviceCapability.Sensors -> sensorsPrecondition()

        DeviceCapability.Storage ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                null
            } else {
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "这台设备（Android ${Build.VERSION.RELEASE}）导出文件需要存储权限，当前未授予。",
                    hint = "请让用户在系统设置中为 pi-android 授予存储权限，或在应用内改用支持 MediaStore 的路径。",
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
