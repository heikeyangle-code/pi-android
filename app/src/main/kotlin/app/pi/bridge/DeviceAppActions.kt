package app.pi.bridge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The 「应用」 surface: list, launch, stop.
 *
 * `stop` carries the strictest guard in the bridge because it is the one action
 * that can destroy a user's unsaved work, so it mirrors the rule the DSH device
 * bridge uses: refresh the installed-app list first, split it into user and system
 * groups, accept only an exact package name (never a fuzzy match), and refuse
 * system apps and the process's own package outright.
 */
object DeviceAppActions {

    /** Packages that must never be stopped even if they are not marked system. */
    private val criticalPackages: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.android.providers.telephony",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
    )

    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun list(context: Context, query: String?, includeSystem: Boolean, limit: Int): JSONObject {
        val manager = context.packageManager
        val all = runCatching {
            manager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val needle = query?.trim()?.lowercase()
        val array = JSONArray()
        var matched = 0
        var withLaunchIntent = 0
        val packages = all.sortedBy { it.packageName }
        for (info in packages) {
            val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && system) continue
            val label = runCatching { manager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            if (!needle.isNullOrEmpty()) {
                val haystack = (label + " " + info.packageName).lowercase()
                if (!haystack.contains(needle)) continue
            }
            matched++
            if (matched > limit.coerceIn(1, 500)) break
            val launchable = manager.getLaunchIntentForPackage(info.packageName) != null
            if (launchable) withLaunchIntent++
            array.put(
                JSONObject().apply {
                    put("label", label)
                    put("packageName", info.packageName)
                    put("system", system)
                    put("uid", info.uid)
                    put("launchable", launchable)
                },
            )
        }
        return JSONObject().apply {
            put("count", array.length())
            put("matched", matched)
            put("truncated", matched > array.length())
            put("includeSystem", includeSystem)
            put("apps", array)
            put(
                "note",
                "Android 11 起，未声明 QUERY_ALL_PACKAGES 的应用只能看到自己可见的包（通常是可启动的应用）。" +
                    "如果列表明显不完整，请告诉用户这是系统可见性限制。",
            )
        }
    }

    fun launch(context: Context, packageName: String): JSONObject {
        requireExactPackage(packageName)
        val manager = context.packageManager
        val intent: Intent? = manager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            val known = isInstalled(manager, packageName)
            throw DeviceActionException(
                DeviceDenial(
                    code = if (known) DeviceDenial.UNSUPPORTED else DeviceDenial.NOT_FOUND,
                    reason = if (known) {
                        "「$packageName」已安装，但没有可启动的界面（它可能是服务或小组件）。"
                    } else {
                        "找不到包「$packageName」。"
                    },
                    hint = "请先用 android_apps 列出可启动的应用，再传它给出的 packageName。",
                ),
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val error = try {
            context.startActivity(intent)
            null
        } catch (exception: Exception) {
            exception
        }
        if (error != null) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.UNSUPPORTED,
                    reason = "启动「$packageName」失败：${error::class.java.simpleName}: ${error.message}",
                    hint = "Android 10+ 限制后台启动界面，请让用户先切到 pi-android 前台后重试。",
                ),
            )
        }
        return JSONObject().apply {
            put("packageName", packageName)
            put("component", intent.component?.flattenToShortString() ?: "")
        }
    }

    /**
     * Stop a **user** app after refreshing the classification.
     *
     * `killBackgroundProcesses` only kills background processes and needs
     * `android.permission.KILL_BACKGROUND_PROCESSES`, which the manifest declares
     * (`AndroidManifest.xml:34`). When the platform still refuses — an OEM can
     * restrict it, and the permission alone is no guarantee — the caller gets that
     * exact sentence rather than a success message for something that did not
     * happen.
     */
    fun stop(context: Context, packageName: String): JSONObject {
        requireExactPackage(packageName)
        val manager = context.packageManager
        if (packageName == context.packageName) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "拒绝结束 pi-android 自身：那会连同当前会话一起中断。",
                    hint = "如果要停止引擎，请让用户使用通知栏里的「停止」按钮。",
                ),
            )
        }
        if (packageName in criticalPackages) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "拒绝结束关键系统包「$packageName」（会导致系统界面或电话功能异常）。",
                    hint = "系统应用与关键进程不允许通过设备桥结束。",
                ),
            )
        }
        val info = runCatching {
            manager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.NOT_FOUND,
                reason = "找不到包「$packageName」，未执行任何结束操作。",
                hint = "请先调用 android_apps 刷新应用清单，再传入精确的包名。",
            ),
        )
        val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (system) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "「$packageName」是系统应用，设备桥不会结束系统应用。",
                    hint = "只有用户安装的应用可以被结束；请让用户自己在系统设置里处理系统应用。",
                ),
            )
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.UNSUPPORTED, "系统没有 ActivityManager。"))
        try {
            activityManager.killBackgroundProcesses(packageName)
        } catch (error: SecurityException) {
            // The permission *is* declared (`AndroidManifest.xml:34`), so a
            // SecurityException here means the OEM or the system policy refused it —
            // not a missing declaration. The old text sent the reader to add a
            // permission that has been there for a while.
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.NO_PERMISSION,
                    reason = "本机拒绝了结束「$packageName」：系统未允许本应用结束后台进程。",
                    hint = "不要向用户声称应用已被结束；请让用户自己在系统设置 → 应用里强制停止，或先用 android_apps 确认状态。",
                ),
            )
        }
        return JSONObject().apply {
            put("packageName", packageName)
            put("system", false)
            put("mode", "killBackgroundProcesses")
            put(
                "note",
                "该调用只结束后台进程，前台界面与已保存的数据不受影响；系统也可能稍后重新拉起该应用。",
            )
        }
    }

    private fun requireExactPackage(packageName: String) {
        if (!packageNamePattern.matches(packageName)) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BAD_REQUEST,
                    reason = "「$packageName」不是合法的精确包名。",
                    hint = "设备桥不接受模糊匹配或通配；请先用 android_apps 查到精确的 packageName。",
                ),
            )
        }
    }

    private fun isInstalled(manager: PackageManager, packageName: String): Boolean =
        runCatching { manager.getApplicationInfo(packageName, 0) }.isSuccess
}
