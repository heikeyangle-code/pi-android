package app.pi.bridge

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备管理员 / Device Owner 能力的统一封装 —— 「输入法 + 设备管理员」里权限最重的一块。
 *
 * 这里**只做封装，不接端点**：波2 会把下面这些函数挂到 `/app/device/…` 下的端点，并在真正调用
 * 前走 [DeviceCapabilityStore] 的开关与审批。之所以把「调用」和「判定」分开写，是因为
 * 这些 API 失败时会抛 `SecurityException` / `IllegalArgumentException`，而这套桥接的
 * 约定是「失败返回明确原因，不要抛」（design §21.4）——所以每个函数先自查可用性，再用
 * `runCatching` 兜底，把异常翻译成 `{ok:false, reason:...}`。
 *
 * ## 身份决定能力
 *
 *  - **Device Owner / Profile Owner**：装/隐藏/挂起应用、阻止卸载、装 CA 证书、
 *    常驻 VPN、Lock Task、系统更新策略、改运行时权限授予状态、禁用相机、擦除；
 *  - **仅 Device Owner**：状态栏、锁屏、重启（Profile Owner 做不到）；
 *  - **普通设备管理员（admin active）**：现代策略几乎没有，只能作为升级成 DO/PO 的
 *    前置身份。
 *
 * 所以每个入口都先声明自己需要哪一档身份，缺哪一档就如实说缺哪一档；
 * [capabilities] 把「当前身份下每项动作能不能做」一次性列出来。
 */
object DeviceAdmin {

    // ------------------------------------------------------------- 身份查询 ----

    /**
     * 最近一次收到 Context 时记住的 applicationContext。
     *
     * 只为满足对接面里写的无参 `status()`：DevicePolicyManager 的每个查询都要
     * Context，而接口同时要求 `status(context)` 和无参版本，所以带参版本顺带记一下，
     * 无参版本才有东西可用。只持有 applicationContext，不会泄漏 Activity。
     */
    @Volatile
    private var rememberedContext: Context? = null

    private fun manager(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private fun remember(context: Context) {
        rememberedContext = context.applicationContext ?: context
    }

    /** 本应用注册的那个设备管理员组件；所有这些 API 都以它作为 admin 入参。 */
    fun admin(context: Context): ComponentName =
        ComponentName(context, PiDeviceAdminReceiver::class.java)

    /** 本应用是否是这台设备的 Device Owner。 */
    fun isDeviceOwner(context: Context): Boolean =
        manager(context)?.isDeviceOwnerApp(context.packageName) == true

    /** 本应用是否是某个工作资料的 Profile Owner。 */
    fun isProfileOwner(context: Context): Boolean =
        manager(context)?.isProfileOwnerApp(context.packageName) == true

    /** 本接收器是否已被系统记为 active 的设备管理员。 */
    fun isAdminActive(context: Context): Boolean =
        manager(context)?.isAdminActive(admin(context)) == true

    /**
     * 是否有任何一档管理身份可用。
     *
     * 「可用」在这里是宽松的：普通设备管理员也算，因为用户可能刚在设置里激活、还没
     * 升级成 Device Owner。具体某个动作能不能做，由它自己的前置检查决定。
     */
    fun available(context: Context): Boolean {
        remember(context)
        return manager(context) != null &&
            (isDeviceOwner(context) || isProfileOwner(context) || isAdminActive(context))
    }

    /**
     * 当前身份下每项动作的可执行性。
     *
     * 这个表是给波2 的端点做「先问再做」用的：模型问「能不能隐藏应用」，答案由身份
     * 决定，而不是由一次失败的调用倒推。表里的每一项与下面各函数的前置检查一一对应。
     */
    fun capabilities(context: Context): JSONObject {
        remember(context)
        val owner = isDeviceOwner(context) || isProfileOwner(context)
        val deviceOwner = isDeviceOwner(context)
        return JSONObject().apply {
            put("deviceOwner", deviceOwner)
            put("profileOwner", isProfileOwner(context))
            put("adminActive", isAdminActive(context))
            put("owner", owner)
            put("setPermissionGrantState", owner)
            put("setApplicationHidden", owner)
            put("setPackagesSuspended", owner)
            put("setUninstallBlocked", owner)
            put("installCaCert", owner)
            put("setAlwaysOnVpn", owner)
            put("setLockTaskPackages", owner)
            put("setSystemUpdatePolicy", owner)
            put("setCameraDisabled", owner)
            put("wipeData", owner)
            put("setStatusBarDisabled", deviceOwner)
            put("setKeyguardDisabled", deviceOwner)
            put("reboot", deviceOwner)
        }
    }

    fun status(context: Context): JSONObject {
        remember(context)
        val deviceOwner = isDeviceOwner(context)
        val profileOwner = isProfileOwner(context)
        val active = isAdminActive(context)
        return JSONObject().apply {
            put("available", manager(context) != null && (deviceOwner || profileOwner || active))
            put("deviceOwner", deviceOwner)
            put("profileOwner", profileOwner)
            put("adminActive", active)
            put("owner", deviceOwner || profileOwner)
            put("packageName", context.packageName)
            put("component", admin(context).flattenToString())
            put(
                "note",
                when {
                    deviceOwner -> "本应用是 Device Owner：应用隐藏/挂起、CA 证书、常驻 VPN、Lock Task、更新策略、权限授予状态、状态栏/锁屏/重启都可用。"
                    profileOwner -> "本应用是 Profile Owner：工作资料内的策略可用；状态栏、锁屏、重启需要 Device Owner。"
                    active -> "本应用只是普通设备管理员：现代策略（隐藏/挂起应用、VPN 等）需要 Device Owner / Profile Owner。"
                    else -> "本应用还没有设备管理员身份；先用 adb set-device-owner 或系统设置里的设备管理员流程激活。"
                },
            )
            put("capabilities", capabilities(context))
        }
    }

    /** 无参重载，对应对接面里写的 `status(): JSONObject`；没有 Context 时如实说明。 */
    fun status(): JSONObject {
        val context = rememberedContext
            ?: return JSONObject()
                .put("available", false)
                .put("reason", "需要 Context 才能查询设备管理员状态；请调用 DeviceAdmin.status(context)。")
        return status(context)
    }

    // --------------------------------------------------------------- 前置检查 ----

    private fun denied(reason: String, hint: String? = null): JSONObject {
        val json = JSONObject().put("ok", false).put("reason", reason)
        if (hint != null) json.put("hint", hint)
        return json
    }

    private fun deviceOwnerHint(context: Context): String =
        "用 `adb shell dpm set-device-owner " +
            "${context.packageName}/${PiDeviceAdminReceiver::class.java.name}` 把本应用设为 Device Owner" +
            "（设备必须没有已登录的账号、且从未设置过 Device Owner）。"

    /** 需要 Device Owner 或 Profile Owner 的动作。返回 null 表示检查通过。 */
    private fun ownerDenial(context: Context, action: String): JSONObject? {
        if (manager(context) == null) return denied("系统没有设备策略服务（DevicePolicyManager）。")
        if (!isDeviceOwner(context) && !isProfileOwner(context)) {
            return denied(
                "$action 需要 Device Owner 或 Profile Owner 身份，当前应用两者都不是。",
                deviceOwnerHint(context),
            )
        }
        return null
    }

    /** 需要 Device Owner 的动作（Profile Owner 不够）。返回 null 表示检查通过。 */
    private fun deviceOwnerDenial(context: Context, action: String): JSONObject? {
        if (manager(context) == null) return denied("系统没有设备策略服务（DevicePolicyManager）。")
        if (!isDeviceOwner(context)) {
            return denied(
                "$action 只有 Device Owner 才能执行，当前应用不是 Device Owner。",
                "Profile Owner 或普通设备管理员做不到这一项。${deviceOwnerHint(context)}",
            )
        }
        return null
    }

    // -------------------------------------------------------------- 应用策略 ----

    /**
     * 修改某个运行时权限的授予状态。
     *
     * @param state 取值同 [DevicePolicyManager.PERMISSION_GRANT_STATE_*]：
     *   0 默认（交回系统）、1 授予、2 拒绝。
     */
    fun setPermissionGrantState(context: Context, pkg: String, perm: String, state: Int): JSONObject {
        if (state != DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT &&
            state != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED &&
            state != DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
        ) {
            return denied("state 只能是 0（默认）/1（授予）/2（拒绝），收到 $state。")
        }
        ownerDenial(context, "修改运行时权限授予状态")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            val applied = dpm.setPermissionGrantState(admin(context), pkg, perm, state)
            if (applied) {
                JSONObject().put("ok", true).put("package", pkg).put("permission", perm).put("state", state)
            } else {
                denied("系统没有接受这次修改（目标可能不是运行时权限，或应用缺少该权限声明）。")
            }
        }.getOrElse { denied("修改权限授予状态失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 隐藏 / 恢复一个应用（被隐藏的应用对用户不可见，但数据保留）。 */
    fun setApplicationHidden(context: Context, pkg: String, hidden: Boolean): JSONObject {
        ownerDenial(context, "隐藏/恢复应用")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            val applied = dpm.setApplicationHidden(admin(context), pkg, hidden)
            if (applied) {
                JSONObject().put("ok", true).put("package", pkg).put("hidden", hidden)
            } else {
                denied("系统拒绝了这次应用隐藏/恢复（目标应用可能不存在）。")
            }
        }.getOrElse { denied("隐藏/恢复应用失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * 挂起 / 恢复若干应用。
     *
     * 挂起与隐藏不同：被挂起的应用仍可见，但会被强制停止、通知被抑制、无法启动
     * （Android 7.0+）。系统会返回一个「没能挂起」的包名列表，非空即部分失败。
     */
    fun setPackagesSuspended(context: Context, pkgs: List<String>, suspended: Boolean): JSONObject {
        ownerDenial(context, "挂起/恢复应用")?.let { return it }
        if (pkgs.isEmpty()) return denied("包名列表为空。")
        val dpm = manager(context)!!
        return runCatching {
            val failed = dpm.setPackagesSuspended(admin(context), pkgs.toTypedArray(), suspended)
            val failedList = failed?.toList().orEmpty()
            JSONObject()
                .put("ok", failedList.isEmpty())
                .put("suspended", suspended)
                .put("packages", JSONArray(pkgs))
                .put("failed", JSONArray(failedList))
        }.getOrElse { denied("挂起/恢复应用失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 阻止 / 允许用户卸载一个应用。 */
    fun setUninstallBlocked(context: Context, pkg: String, blocked: Boolean): JSONObject {
        ownerDenial(context, "阻止/允许卸载应用")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.setUninstallBlocked(admin(context), pkg, blocked)
            JSONObject().put("ok", true).put("package", pkg).put("blocked", blocked)
        }.getOrElse { denied("设置卸载限制失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------- 安全与连接 ----

    /**
     * 安装一张 CA 证书。
     *
     * 可用于让设备信任自签的根证书（中间人排查、内网服务）。证书一旦装入是**系统级
     * 且持久**的，卸载应用也不会移除 —— 这是一个需要用户明确知情的能力。
     */
    fun installCaCert(context: Context, bytes: ByteArray): JSONObject {
        if (bytes.isEmpty()) return denied("CA 证书内容是空的。")
        ownerDenial(context, "安装 CA 证书")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            val applied = dpm.installCaCert(admin(context), bytes)
            if (applied) {
                JSONObject().put("ok", true).put("bytes", bytes.size)
            } else {
                denied("系统拒绝了这张 CA 证书（内容无法解析，或设备已有更严格的策略）。")
            }
        }.getOrElse { denied("安装 CA 证书失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * 设置常驻（Always-on）VPN。[pkg] 传 null 表示关闭常驻 VPN。
     *
     * [lockdown] 打开后，VPN 断开时设备会阻断所有网络，直到 VPN 恢复。
     */
    fun setAlwaysOnVpn(context: Context, pkg: String?, lockdown: Boolean = false): JSONObject {
        ownerDenial(context, "设置常驻 VPN")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.setAlwaysOnVpnPackage(admin(context), pkg, lockdown)
            JSONObject()
                .put("ok", true)
                .put("package", pkg ?: JSONObject.NULL)
                .put("lockdown", lockdown)
        }.getOrElse { denied("设置常驻 VPN 失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 设置 Lock Task（专用设备/自助终端）允许启动的应用清单；空列表即关闭。 */
    fun setLockTaskPackages(context: Context, pkgs: List<String>): JSONObject {
        ownerDenial(context, "设置 Lock Task 应用清单")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.setLockTaskPackages(admin(context), pkgs.toTypedArray())
            JSONObject().put("ok", true).put("count", pkgs.size).put("packages", JSONArray(pkgs))
        }.getOrElse { denied("设置 Lock Task 清单失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * 设置系统更新策略。
     *
     * @param mode `automatic`（自动装）、`postpone`（推迟）、`windowed`（允许安装的
     *   每日时间窗）、`clear`（恢复系统默认）。`windowed` 需要 [windowStartMinutes] /
     *   [windowEndMinutes]，均为「当天 0 点起的分钟数」，取值 [0, 1440)。
     */
    fun setSystemUpdatePolicy(
        context: Context,
        mode: String,
        windowStartMinutes: Int = -1,
        windowEndMinutes: Int = -1,
    ): JSONObject {
        val normalized = mode.trim().lowercase()
        val policy: SystemUpdatePolicy?
        when (normalized) {
            "", "clear", "none", "off" -> policy = null
            "automatic", "auto" -> policy = SystemUpdatePolicy.createAutomaticInstallPolicy()
            "postpone" -> policy = SystemUpdatePolicy.createPostponeInstallPolicy()
            "windowed", "window" -> {
                if (windowStartMinutes !in 0 until 1440 ||
                    windowEndMinutes !in 0..1440 ||
                    windowEndMinutes < windowStartMinutes
                ) {
                    return denied(
                        "windowed 模式需要 windowStartMinutes ∈ [0,1440)、" +
                            "windowEndMinutes ∈ [start,1440)，收到 $windowStartMinutes/$windowEndMinutes。",
                    )
                }
                policy = SystemUpdatePolicy.createWindowedInstallPolicy(windowStartMinutes, windowEndMinutes)
            }
            else -> return denied("未知的更新策略模式：$mode（可用 automatic/postpone/windowed/clear）。")
        }
        ownerDenial(context, "设置系统更新策略")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.setSystemUpdatePolicy(admin(context), policy)
            JSONObject().put("ok", true).put("mode", normalized.ifEmpty { "clear" })
        }.getOrElse { denied("设置系统更新策略失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 界面与硬件 ----

    /** 禁用 / 恢复状态栏（通知和快捷设置会被挡住）。仅 Device Owner。 */
    fun setStatusBarDisabled(context: Context, disabled: Boolean): JSONObject {
        deviceOwnerDenial(context, "禁用状态栏")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.setStatusBarDisabled(admin(context), disabled)
            JSONObject().put("ok", true).put("disabled", disabled)
        }.getOrElse { denied("设置状态栏失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 禁用 / 恢复锁屏。仅 Device Owner。 */
    fun setKeyguardDisabled(context: Context, disabled: Boolean): JSONObject {
        deviceOwnerDenial(context, "禁用锁屏")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.setKeyguardDisabled(admin(context), disabled)
            JSONObject().put("ok", true).put("disabled", disabled)
        }.getOrElse { denied("设置锁屏失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 禁用 / 恢复相机（设备级）。Device Owner 或 Profile Owner。 */
    fun setCameraDisabled(context: Context, disabled: Boolean): JSONObject {
        ownerDenial(context, "禁用相机")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.setCameraDisabled(admin(context), disabled)
            JSONObject().put("ok", true).put("disabled", disabled)
        }.getOrElse { denied("设置相机策略失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 重启设备。仅 Device Owner；调用后设备会立即重启。 */
    fun reboot(context: Context): JSONObject {
        deviceOwnerDenial(context, "重启设备")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            dpm.reboot(admin(context))
            JSONObject().put("ok", true).put("note", "重启指令已交给系统。")
        }.getOrElse { denied("重启失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * **擦除设备（Device Owner）或工作资料（Profile Owner）—— 不可逆，会清掉数据。**
     *
     * Device Owner 下这是恢复出厂设置；Profile Owner 下只清工作资料。执行后系统会立即
     * 重启，应用进程不复存在。这个函数保留给波2 接端点时**必须**走最高等级的人工确认，
     * 刻意不做任何「防误触」以外的建议：能力本身没错，错的是没人确认。
     *
     * @param flags 0 表示默认；可叠加 [DevicePolicyManager.WIPE_EXTERNAL_STORAGE]
     *   （API 29 起仅系统可用）、[DevicePolicyManager.WIPE_RESET_PROTECTION_DATA] 等。
     */
    fun wipeData(context: Context, flags: Int = 0): JSONObject {
        ownerDenial(context, "擦除设备/工作资料")?.let { return it }
        val dpm = manager(context)!!
        return runCatching {
            val result = dpm.wipeData(flags)
            JSONObject().put("ok", true).put("accepted", result).put("flags", flags)
        }.getOrElse { denied("擦除失败：${it.message ?: it.javaClass.simpleName}") }
    }
}
