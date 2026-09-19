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

    /**
     * The last successful enumeration, and when it was taken.
     *
     * `getInstalledApplications(GET_META_DATA)` is a full PackageManager walk —
     * binder round trips plus a record parse per installed package, 150–500 ms on a
     * phone with a few hundred apps — and the model reaches `/app/apps` several times
     * in one task ("find the app", "check it is there", "list again after launching").
     * A short TTL turns those into one walk.
     *
     * ## Why a TTL and not a correctness-critical cache
     *
     * An app installed or uninstalled during the window is reported one query late.
     * That is acceptable for a *listing* (the result is advisory: the model picks a
     * package name and then `/app/apps/launch` acts on it, and a launch of a package
     * that is gone fails loudly on its own). It would **not** be acceptable for
     * [stop]'s safety check, so that path still asks the manager directly for the
     * single package it is about to kill.
     *
     * Only a **successful** walk is cached: caching the empty list a failed call
     * produces would turn a transient PackageManager error into 30 s of "no apps".
     */
    private class InstalledApps(val at: Long, val apps: List<ApplicationInfo>)

    /** How long one enumeration serves `/app/apps` queries. See [installedApplications]. */
    private const val INSTALLED_CACHE_TTL_MS = 30_000L

    @Volatile
    private var installedCache: InstalledApps? = null

    private fun installedApplications(manager: PackageManager): List<ApplicationInfo> {
        val now = android.os.SystemClock.elapsedRealtime()
        installedCache?.let { cached ->
            if (now - cached.at <= INSTALLED_CACHE_TTL_MS) return cached.apps
        }
        val fresh = runCatching {
            manager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrNull() ?: return emptyList()
        installedCache = InstalledApps(now, fresh)
        return fresh
    }

    fun list(context: Context, query: String?, includeSystem: Boolean, limit: Int): JSONObject {
        val manager = context.packageManager
        val all = installedApplications(manager)

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

    /**
     * Launch [packageName] **and check that it actually came to the front**.
     *
     * `startActivity` returning without throwing is not evidence of anything on
     * Android 10+: a background activity start is dropped by the system with no
     * exception, no log line the app can see and no callback. That made this
     * endpoint the worst kind of silent failure — a 200 whose list of effects is
     * empty. Three mechanisms now cover it, in order of cost:
     *
     *  1. `startActivity`, then verify the foreground package within 500 ms
     *     (only possible while the accessibility service is up; without it the
     *     reply says `verified: false` rather than pretending).
     *  2. If the foreground package did not change and Shizuku is ready,
     *     `am start -n pkg/cls` under uid 2000 — the ADB identity is exempt from
     *     the background-activity-start restriction, which is exactly why the
     *     Shizuku path is the one that makes this reliable.
     *  3. Otherwise a notification carrying the same launch intent as a
     *     `PendingIntent`: a tap is user-initiated, so the system allows it. The
     *     reply then fails with `BLOCKED_BACKGROUND` and says the notification is
     *     waiting, instead of reporting a launch that did not happen.
     */
    fun launch(context: Context, packageName: String): JSONObject {
        requireExactPackage(packageName)
        val manager = context.packageManager

        // Launching ourselves is not a launch: the app is already the thing
        // running this request, and `startActivity` on our own launcher activity
        // can restart the task that owns the engine. Bring our task forward
        // instead, and never touch the activity stack.
        if (packageName == context.packageName) {
            return JSONObject().apply {
                put("packageName", packageName)
                put("mode", "self")
                put("movedToFront", moveOwnTaskToFront(context))
                put("verified", true)
                put("note", "目标就是 PI 自身；已把 PI 的任务切到前台，没有重新启动任何界面。")
            }
        }

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
                    hint = "先用 android_app（action=\"list\"）查可启动项再传 packageName。",
                ),
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val foregroundBefore = foregroundPackage(context)
        // Verification needs the accessibility service; without it, "the foreground
        // did not change" is unknowable, and treating an unknown as a failure would
        // break launching in the common case (基础 capability on, 无障碍 off).
        val canVerify = DeviceAccessibilityService.isRunning()
        val error = try {
            context.startActivity(intent)
            null
        } catch (exception: Exception) {
            exception
        }
        if (error == null) {
            if (!canVerify) {
                return launchedPayload(
                    packageName,
                    intent,
                    mode = "startActivity",
                    foreground = null,
                    verified = false,
                ).put(
                    "note",
                    "已调用 startActivity，但无障碍服务没有运行，无法验证前台是否真的切换。" +
                        "要确认结果，让用户打开「设备能力 → 无障碍」，或先用 android_bridge_status 看「前台」行。",
                )
            }
            verifiedForeground(context, packageName)?.let { seen ->
                return launchedPayload(packageName, intent, mode = "startActivity", foreground = seen, verified = true)
            }
            // Shizuku: uid 2000 may start activities from the background.
            amStart(packageName, intent)?.let {
                verifiedForeground(context, packageName)?.let { seen ->
                    return launchedPayload(
                        packageName,
                        intent,
                        mode = "am_start_uid2000",
                        foreground = seen,
                        verified = true,
                    )
                }
            }
        }

        val notified = notifyLaunchFallback(context, packageName, intent)
        val detail = when {
            error != null -> "startActivity 抛出了 ${error::class.java.simpleName}: ${error.message}"
            foregroundBefore == null ->
                "系统没有抛出异常，但读不到前台包名（无障碍服务不可用），无法确认是否启动"
            else ->
                "系统没有抛出异常，但前台仍然是「$foregroundBefore」"
        }
        throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.BLOCKED_BACKGROUND,
                reason = "启动「$packageName」没有生效：$detail。",
                hint = buildString {
                    append("下一步按顺序试：")
                    append("① 让用户把 pi-android 切到前台后重试；")
                    append("② 在「设置 → 设备能力 → Shell」启用 Shizuku（ADB 身份可以后台启动界面）；")
                    if (notified) {
                        append("③ 已发一条通知，用户可以点它直接打开「$packageName」。")
                    } else {
                        append("③ 或者让用户自己从桌面打开「$packageName」。")
                    }
                },
            ),
        )
    }

    private fun launchedPayload(
        packageName: String,
        intent: Intent,
        mode: String,
        foreground: String?,
        verified: Boolean,
    ): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("component", intent.component?.flattenToShortString() ?: "")
        put("mode", mode)
        put("foreground", foreground ?: JSONObject.NULL)
        put("verified", verified)
        if (mode == "am_start_uid2000") {
            put("note", "startActivity 被系统丢弃，已改用 Shizuku 的 ADB 身份（uid=2000）执行 am start 并确认前台已切换。")
        }
    }

    /**
     * The foreground package, or null when it cannot be observed.
     *
     * Only the accessibility service can answer this without a special
     * permission (`getRunningTasks`/`getRunningAppProcesses` return only this app
     * on every supported API level), so an absent service means an honest null —
     * never a guess.
     */
    private fun foregroundPackage(context: Context): String? = runCatching {
        DeviceAccessibilityService.running()?.let { service ->
            service.rootInActiveWindow?.packageName?.toString()
                ?: service.windows?.firstNotNullOfOrNull { it.root?.packageName?.toString() }
        }
    }.getOrNull()

    /** Poll up to [timeoutMs] for [packageName] to become the foreground package. */
    private fun verifiedForeground(context: Context, packageName: String, timeoutMs: Long = 500): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = foregroundPackage(context)
        while (System.currentTimeMillis() < deadline) {
            if (seen == packageName) return seen
            runCatching { Thread.sleep(60) }
            seen = foregroundPackage(context) ?: seen
        }
        return if (seen == packageName) seen else null
    }

    /**
     * `am start` under the ADB identity. Returns the shell result only when the
     * backend really is uid 2000 and the command completed; `null` otherwise, so
     * the caller can fall through to the notification.
     */
    private fun amStart(packageName: String, intent: Intent): DeviceShellResult? {
        val backend = DeviceShellGuard.active()
        if (backend.id != ShizukuShellBackend.id) return null
        val component = intent.component?.flattenToShortString() ?: return null
        // Validated rather than escaped: the component goes into a command string
        // and `am` has no quoting of its own. Package/class names are [A-Za-z0-9_.$].
        if (!Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$").matches(component)) return null
        val result = runCatching { backend.run("am start -n $component", 15_000) }.getOrNull() ?: return null
        if (result.exitCode != 0) return null
        if (result.stdout.contains("Error", ignoreCase = true)) return null
        return result
    }

    /** Bring our own task forward without restarting any activity. */
    private fun moveOwnTaskToFront(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val task = manager.appTasks.firstOrNull() ?: return false
        task.moveToFront()
        true
    }.getOrDefault(false)

    /**
     * The user-initiated escape hatch: a notification whose content intent is the
     * launch intent that was dropped. Returns false when notifications cannot be
     * posted, so the hint never promises a notification that does not exist.
     */
    private fun notifyLaunchFallback(context: Context, packageName: String, intent: Intent): Boolean =
        runCatching {
            DeviceSystemActions.notifyIntent(
                context = context,
                title = "点一下打开「$packageName」",
                text = "PI 尝试启动「$packageName」，但系统拦截了后台启动。点这条通知即可打开。",
                id = (packageName.hashCode() and 0x7fffffff) % 100000 + 1,
                intent = intent,
            )
            true
        }.getOrDefault(false)

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
                    reason = "拒绝结束 PI 自身：会中断当前会话。",
                    hint = "要停止引擎，让用户用通知栏的「停止」按钮。",
                ),
            )
        }
        if (packageName in criticalPackages) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "拒绝结束关键系统包「$packageName」。",
                    hint = "系统应用与关键进程不能通过设备桥结束。",
                ),
            )
        }
        val info = runCatching {
            manager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: throw DeviceActionException(
            DeviceDenial(
                code = DeviceDenial.NOT_FOUND,
                reason = "找不到包「$packageName」，未执行结束。",
                hint = "先用 android_app（action=\"list\"）刷新再传精确包名。",
            ),
        )
        val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (system) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "「$packageName」是系统应用，不结束。",
                    hint = "只能结束用户安装的应用；请让用户在系统设置里处理。",
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
                    reason = "本机拒绝结束「$packageName」：未允许结束后台进程。",
                    hint = "不要声称已结束；让用户在 系统设置 → 应用 里强制停止。",
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
                    reason = "「$packageName」不是合法精确包名。",
                    hint = "不接受模糊匹配；用 android_app（action=\"list\"）。",
                ),
            )
        }
    }

    private fun isInstalled(manager: PackageManager, packageName: String): Boolean =
        runCatching { manager.getApplicationInfo(packageName, 0) }.isSuccess
}
