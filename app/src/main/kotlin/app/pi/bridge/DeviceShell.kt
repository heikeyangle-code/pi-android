package app.pi.bridge

import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Result of one guarded shell invocation, with the backend that produced it.
 *
 * [backend] and [uid] are not decoration: without them a model cannot tell why
 * `pm list packages` worked and `dumpsys battery` did not, and would report a
 * capability the app does not really have. Shizuku / ADB wireless debugging is
 * *not* wired in this build, so the only backend today runs with the app's own
 * uid — which is a much smaller privilege than the design's uid=2000 target.
 */
data class DeviceShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val backend: String,
    val uid: Int,
    val truncated: Boolean,
    val timedOut: Boolean,
)

/**
 * A shell execution backend. The interface exists so a Shizuku/ADB backend can
 * be dropped in later without touching the policy layer.
 */
interface DeviceShellBackend {
    val id: String
    val label: String
    val available: Boolean
    fun run(command: String, timeoutMs: Int): DeviceShellResult
}

/**
 * The only backend this build ships: `/system/bin/sh` as the app's own uid
 * (`u0_aXXX`), *not* uid 2000.
 *
 * That is a deliberate, honest limitation rather than a hidden one. It can read
 * what the app can read and write into the app's own storage plus the public
 * Download collection; it cannot inspect other apps or system state that the app
 * sandbox hides. The response says so on every call.
 */
object AppUidShellBackend : DeviceShellBackend {
    override val id: String = "app-uid"
    override val label: String = "应用自身身份（非 uid=2000）"
    override val available: Boolean = true

    private const val MAX_OUTPUT_BYTES = 50 * 1024

    override fun run(command: String, timeoutMs: Int): DeviceShellResult {
        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
        builder.directory(File("/"))
        // Do not inherit the app's environment: PATH and TMPDIR are all a shell
        // needs, and passing the rest through would leak app state into stdout.
        val environment = builder.environment()
        environment.clear()
        environment["PATH"] = "/system/bin:/system/xbin:/product/bin"
        environment["TMPDIR"] = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
        environment["LANG"] = "C.UTF-8"

        val process = try {
            builder.start()
        } catch (error: Exception) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.ERROR,
                    reason = "无法启动 shell：${error::class.java.simpleName}: ${error.message}",
                ),
            )
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = Thread { readCapped(process.inputStream, stdout) }
        val errReader = Thread { readCapped(process.errorStream, stderr) }
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()

        val timeout = timeoutMs.coerceIn(500, 60_000).toLong()
        val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        outReader.join(500)
        errReader.join(500)

        return DeviceShellResult(
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            exitCode = if (finished) process.exitValue() else -1,
            backend = id,
            uid = Process.myUid(),
            truncated = stdout.length >= MAX_OUTPUT_BYTES,
            timedOut = !finished,
        )
    }

    private fun readCapped(stream: java.io.InputStream, into: StringBuilder) {
        stream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (closed: Exception) {
                    -1
                }
                if (read <= 0) break
                if (into.length < MAX_OUTPUT_BYTES) {
                    into.append(String(buffer, 0, read, Charsets.UTF_8))
                }
            }
        }
    }
}

/**
 * The policy guard for the `android_shell` capability (design §23.3).
 *
 * It is deliberately two-sided:
 *
 *  - a **command whitelist**, so an unrecognised command is refused rather than
 *    attempted ("未知命令直接拦截" — a blocklist alone is unbounded, because the
 *    device has thousands of binaries);
 *  - a **hard blocklist** of the things no user consent should be able to reach
 *    through this app: block devices and partitions, SELinux, `settings put` /
 *    `setprop`, mount/flash, and clearing or uninstalling apps.
 *
 * Chained commands are split and every segment is validated, because otherwise
 * `getprop x; mount -o rw /` would pass a naive head check. Command substitution
 * (`$(...)`, backticks) is refused outright: it can smuggle a denied command
 * past the splitter.
 */
object DeviceShellGuard {

    /** The only thing the bridge is allowed to execute with today. */
    fun backends(): List<DeviceShellBackend> = listOf(AppUidShellBackend)

    fun active(): DeviceShellBackend = AppUidShellBackend

    /** Heads a read-only device query may start with. */
    private val allowedHeads: Set<String> = setOf(
        "getprop", "dumpsys", "logcat", "pm", "am", "cmd", "settings",
        "ls", "cat", "head", "tail", "wc", "stat", "file", "readlink", "realpath",
        "df", "du", "ps", "top", "free", "uptime", "date", "uname", "id", "whoami",
        "echo", "printf", "which", "type", "env", "printenv", "pwd",
        "find", "grep", "sort", "uniq", "cut", "tr", "sed", "xargs", "basename", "dirname",
        "ip", "ifconfig", "netstat", "ping", "getevent", "screencap", "input",
        "sleep", "true", "false", "test", "[",
    )

    /** Commands whose *only* purpose here would be to escape the policy. */
    private val forbidden: List<Pair<Regex, String>> = listOf(
        Regex("(^|[\\s;&|])mount(\\s|$)") to "挂载/卸载文件系统",
        Regex("(^|[\\s;&|])umount(\\s|$)") to "挂载/卸载文件系统",
        Regex("\\bsetenforce\\b") to "修改 SELinux 状态",
        Regex("\\bgetenforce\\s+0\\b") to "修改 SELinux 状态",
        Regex("/sys/fs/selinux") to "访问 SELinux 控制面",
        Regex("\\bsetprop\\b") to "修改系统属性（setprop）",
        Regex("\\bsettings\\s+(put|delete|reset)\\b") to "修改系统设置（settings put/delete）",
        Regex("\\bpm\\s+(clear|uninstall|disable|enable|hide|suspend|restore)\\b") to "清除/卸载/禁用应用数据",
        Regex("\\bcmd\\s+package\\s+(clear|uninstall|disable|enable|suspend)\\b") to "清除/卸载/禁用应用数据",
        Regex("\\bcmd\\s+settings\\s+(put|delete|reset)\\b") to "修改系统设置",
        Regex("\\bcmd\\s+(device_admin|role|wifi|bluetooth_manager|telecom|netpolicy)\\b") to "修改系统服务状态",
        Regex("\\bmknod\\b") to "创建设备节点",
        Regex("\\bdd\\b") to "裸块写入（dd）",
        Regex("\\bmkfs(\\.|\\s|$)") to "格式化分区",
        Regex("\\bfastboot\\b") to "刷机",
        Regex("\\breboot\\b") to "重启设备",
        Regex("\\brecovery\\b") to "进入恢复模式",
        Regex("\\b(insmod|rmmod|modprobe)\\b") to "加载/卸载内核模块",
        Regex("(^|[\\s;&|])(su|sudo|magisk)\\b") to "提权（root）",
        Regex("(^|[\\s;&|])eval\\b") to "动态求值（eval）",
        Regex("(^|[\\s;&|])exec\\b") to "替换进程（exec）",
        Regex("(^|[\\s;&|])(source|\\.)\\s") to "加载脚本（source）",
        Regex("\\bkillall\\b") to "批量结束进程",
        Regex("\\bkill\\s+-9\\s+-1\\b") to "结束所有进程",
        Regex("\\bwipe\\b") to "擦除数据",
        Regex("\\bflash(er)?\\b") to "刷写分区",
        // No leading \b: there is no word boundary between a space and a slash, so
        // Regex("\\b/dev/block\\b") could never match. (The TS mirror of this policy
        // had the same bug; the runtime harness caught it.)
        Regex("/dev/block\\b") to "访问块设备",
        Regex("/dev/mem\\b") to "访问物理内存",
        Regex("\\bshutdown\\b") to "关机",
    )

    /** Paths the app may not write to, regardless of how it got there. */
    private val protectedWriteRoots: List<String> = listOf(
        "/dev/block", "/dev/mem", "/proc", "/sys", "/system", "/vendor", "/apex",
        "/data/data", "/data/system", "/sdcard/DCIM", "/sdcard/Pictures",
        "/storage/emulated/0/DCIM", "/storage/emulated/0/Pictures",
        "/sdcard/Android/data", "/sdcard/Android/obb",
        "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb",
    )

    private val writeTokens = listOf(
        ">", ">>", "tee", "cp ", "mv ", "rm ", "rmdir", "mkdir", "touch",
        "chmod", "chown", "truncate", "sed -i", "ln ", "install ",
    )

    /**
     * @return `null` when [command] may run, otherwise the denial to hand back.
     */
    fun inspect(command: String): DeviceDenial? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return DeviceDenial(DeviceDenial.BAD_REQUEST, "命令为空。")
        }
        if (trimmed.length > 4000) {
            return DeviceDenial(
                code = DeviceDenial.BAD_REQUEST,
                reason = "命令过长（${trimmed.length} 字符，上限 4000）。",
            )
        }
        if (trimmed.contains('`')) {
            return DeviceDenial(
                code = DeviceDenial.BLOCKED_BY_POLICY,
                reason = "命令包含反引号命令替换，已按策略拦截。",
                hint = "请把每一步拆成独立的只读命令分别执行。",
            )
        }
        if (trimmed.contains("\$(")) {
            return DeviceDenial(
                code = DeviceDenial.BLOCKED_BY_POLICY,
                reason = "命令包含 \$(...) 命令替换，已按策略拦截。",
                hint = "请把每一步拆成独立的只读命令分别执行。",
            )
        }

        for ((pattern, reason) in forbidden) {
            if (pattern.containsMatchIn(trimmed)) {
                return DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "命令被设备策略拦截：$reason。这是硬性限制，无法通过用户授权放行。",
                    hint = "请改用只读查询，或直接告诉用户这一步需要另外的方式完成。",
                )
            }
        }

        // Split on shell separators and validate every segment's head token, so
        // `getprop x; rm -rf /sdcard/DCIM` cannot ride on an allowed first head.
        val segments = trimmed
            .replace("&&", ";")
            .replace("||", ";")
            .replace("|", ";")
            .replace("\n", ";")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            return DeviceDenial(DeviceDenial.BAD_REQUEST, "命令为空。")
        }
        for (segment in segments) {
            val head = segment.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
            val normalized = head.substringAfterLast('/')
            if (normalized.isEmpty() || normalized !in allowedHeads) {
                return DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "命令「$head」不在设备 Shell 的白名单内，未知命令默认拦截。",
                    hint = "可用的是只读设备查询（getprop、dumpsys、pm list、logcat、ls、cat、df、ps 等）。" +
                        "需要别的操作时，请改用无障碍或专用工具。",
                )
            }
        }

        val writes = writeTokens.any { trimmed.contains(it) } ||
            Regex("(^|[\\s;&])>").containsMatchIn(trimmed)
        if (writes) {
            val target = protectedWriteRoots.firstOrNull { trimmed.contains(it) }
            if (target != null) {
                return DeviceDenial(
                    code = DeviceDenial.BLOCKED_BY_POLICY,
                    reason = "命令试图写入受保护目录 $target，已按策略拦截（这些目录只读）。",
                    hint = "可以写入应用自己的目录，或使用 android_export 写入公共 Download。",
                )
            }
        }
        return null
    }

    /** The human-readable policy, surfaced by the diagnostics card. */
    fun policySummary(): List<String> = listOf(
        "白名单之外的命令一律拒绝",
        "禁止块设备与分区操作（/dev/block、dd、mkfs、fastboot）",
        "禁止修改 SELinux（setenforce、/sys/fs/selinux）",
        "禁止 setprop / settings put",
        "禁止 mount / umount",
        "禁止清除或卸载应用（pm clear/uninstall）",
        "禁止提权（su、sudo、magisk）与命令替换（\$(...)、反引号）",
        "DCIM、Pictures、Android/data、Android/obb 只读",
    )

    /** Renders a result for the model, keeping the limitation visible. */
    fun toJson(result: DeviceShellResult): JSONObject = JSONObject().apply {
        put("stdout", result.stdout)
        put("stderr", result.stderr)
        put("exitCode", result.exitCode)
        put("timedOut", result.timedOut)
        put("truncated", result.truncated)
        put("backend", result.backend)
        put("uid", result.uid)
        put("backendLabel", AppUidShellBackend.label)
        put(
            "note",
            "本版本没有接入 Shizuku / ADB 无线调试，命令以应用自身身份（uid=${result.uid}）执行，" +
                "因此读不到其他应用与系统私有状态。需要 uid=2000 的能力时请告知用户这一点。",
        )
    }
}
