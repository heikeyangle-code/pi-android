package app.pi.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import app.pi.BuildConfig
import app.pi.bridge.DeviceActionException
import app.pi.bridge.DeviceSystemActions
import app.pi.runtime.PiPaths
import app.pi.runtime.RuntimeProvisioner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Facts about the engine's **last exit**, as only the code that owns the process
 * can see them.
 *
 * ## Why this type exists
 *
 * `PiEngineSession` drains pi's stderr into a bounded `StringBuilder`
 * (`MAX_STDERR_CHARS`, 64 KB) and drops the process's exit code into a sentence
 * that only in-flight requests ever see ("engine exited with code N"). When the
 * engine dies, the ViewModel clears its reference to the session, so the captured
 * stderr becomes unreachable at exactly the moment it is the only evidence left.
 * This type is the carrier that keeps it: the owner reads it **while the session
 * is still reachable** and hands a copy to the diagnostic report.
 *
 * `exitCode` is a separate field rather than part of a message because a number
 * can be compared between two reports and a sentence cannot.
 */
data class EngineDiagnostics(
    /** `PiEngineSession.EngineState.name` at the moment of capture. */
    val state: String?,
    /**
     * The process's exit status, or null when the engine does not publish one.
     *
     * Null is a real answer here, not a placeholder: today `PiEngineSession`
     * keeps the code inside its `waitJob` and never stores it, so a report that
     * showed a plausible number would be inventing one.
     */
    val exitCode: Int?,
    /** Everything drained from stderr, already truncated by the engine. */
    val stderr: String?,
    /** `PiEngineSession.lastServingMs` — the startup measurement, in ms. */
    val startupMs: Long?,
)

/**
 * The plain-text diagnostic report: one string that carries everything this app
 * can still see after the engine has exited.
 *
 * ## Why a file and not the transcript
 *
 * The report is written for a person and for a log, never for the model. It is
 * therefore never handed to `PiEngineApi`, never mirrored into the transcript and
 * never sent as a prompt: it goes to Download or to the system share sheet
 * ([DiagnosticsExport]). The one thing it must not do is consume the context
 * window of the very session it is trying to explain.
 *
 * ## What it answers
 *
 * The device report this exists for is "一聊天就卡死 / 偶尔闪退，界面只有 rpc:
 * engine exited with code 1". The exit code and stderr are the first section, and
 * everything else is the context needed to interpret them: the packaged runtime
 * revision versus the one actually unpacked, whether every payload is present and
 * how large, the versions read out of the runtime tree, the paths that were used,
 * and the failures the app has already recorded.
 *
 * ## Honesty rules
 *
 *  - Every value is read, or the line says why it could not be read. No line
 *    falls back to a plausible-looking default ([RuntimeFacts] makes the same
 *    promise for the settings rows).
 *  - The whole report is passed through [redact] before it is returned, so a
 *    token that reached stderr cannot leave the device through this channel.
 *  - Reading is blocking IO (a tree walk for the runtime size, an asset read per
 *    payload); callers run [build] off the main thread.
 */
object DiagnosticsReport {

    /** How much of the engine's captured stderr the report carries, from the tail. */
    private const val STDERR_TAIL_CHARS = 20_000

    /** Upper bound on the whole report, so a share intent stays well under Binder. */
    private const val MAX_REPORT_CHARS = 120_000

    /**
     * Assemble the report. [engine] and [failures] are supplied by the caller
     * because only the engine's owner can see them; everything else is read here
     * from the filesystem, the APK and the platform.
     */
    fun build(
        context: Context,
        paths: PiPaths,
        engine: EngineDiagnostics?,
        failures: List<String>,
    ): String {
        val facts = RuntimeFacts(paths).read()
        // A local, not state on this object: a report is a snapshot, and an error
        // from one call must not leak into the next report's payload section.
        var payloadInventoryError: String? = null
        val payloads = runCatching {
            RuntimeProvisioner(paths, context.assets).payloadInventory()
        }.getOrElse { error ->
            // Reported as a line, not swallowed: "the inventory itself failed" is
            // itself a finding about this APK.
            payloadInventoryError = "${error::class.java.simpleName}: ${error.message}"
            emptyList()
        }

        val body = buildString {
            appendLine("pi-android 诊断报告")
            appendLine("生成时间：${timestamp()}")
            appendLine()
            appendLine("说明：本文件是纯文本诊断材料，用于在引擎已经退出的情况下把可诊断的信息带到别处。")
            appendLine("它不会被交给模型，也不会写进对话上下文。")
            appendLine("已做脱敏：疑似 token / API Key / 密码的值替换为 <已脱敏>，家目录里的用户名替换为 <用户>。")
            appendLine("报告仍可能包含普通文件路径与错误原文。")
            appendLine()

            appendLine("── App 与设备 ──")
            appendLine("App 版本：${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
            appendLine("包名：${context.packageName}")
            appendLine("Android：${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}（device ${Build.DEVICE}）")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("应用私有目录可用存储：${storageLine(context)}")
            appendLine("内存：${memoryLine(context)}")
            appendLine()

            appendLine("── 引擎最后一次退出 ──")
            if (engine == null) {
                appendLine("还没有记录：本次进程里引擎尚未退出过，或记录发生在本次改动之前。")
            } else {
                appendLine("状态：${engine.state ?: "未知"}")
                appendLine(
                    if (engine.exitCode != null) {
                        "退出码：${engine.exitCode}"
                    } else {
                        "退出码：未记录（当前版本没有把退出码写进引擎状态，只有 stderr 被捕获）"
                    },
                )
                appendLine("最近一次启动耗时：${formatDuration(engine.startupMs)}")
                appendLine()
                appendLine("已捕获的 stderr（引擎侧上限 64 KB，这里取尾部最多 $STDERR_TAIL_CHARS 字符）：")
                val stderr = engine.stderr?.trim()
                if (stderr.isNullOrBlank()) {
                    appendLine("（空：引擎退出前没有向 stderr 写任何内容）")
                } else {
                    if (stderr.length > STDERR_TAIL_CHARS) {
                        appendLine("（已截断，只保留最后 $STDERR_TAIL_CHARS 字符）")
                    }
                    appendLine(stderr.takeLast(STDERR_TAIL_CHARS))
                }
            }
            appendLine()

            appendLine("── 运行时事实 ──")
            appendLine("运行时已解包：${if (facts.runtimeUnpacked) "是" else "否"}")
            appendLine("已解包的 revision（stamp）：${readStamp(paths)}")
            appendLine("APK 内的 revision：${RuntimeProvisioner.packagedRevision(context.assets)}")
            appendLine("RUNTIME_REVISION 兜底常量：${RuntimeProvisioner.RUNTIME_REVISION}")
            appendLine("pi 版本：${facts.piVersion ?: "读不到"}")
            appendLine("Node 版本：${facts.nodeVersion ?: "读不到"}")
            appendLine("运行时占用：${facts.runtimeUsage ?: "读不到"}")
            appendLine("唤醒锁：${if (facts.wakeLockHeld) "持有中" else "未持有"}")
            appendLine("引擎前台服务：${if (facts.serviceRunning) "运行中" else "未运行"}")
            appendLine()
            appendLine("内置载荷（逐个测量；缺失或不可读会写明原因）：")
            if (payloads.isEmpty()) {
                appendLine("（没能列出载荷：${payloadInventoryError ?: "APK 里没有 assets/runtime"}）")
            } else {
                payloads.forEach { info ->
                    append("  ${info.asset}  ")
                    if (info.ok) {
                        append(formatBytes(info.bytes))
                        if (info.via != null) append(" via ${info.via}")
                    } else {
                        append("缺失/不可读")
                        if (info.error != null) append("（${info.error.lineSequence().first()}）")
                    }
                    if (!info.required) append("（可选）")
                    appendLine()
                }
            }
            appendLine()

            appendLine("── 关键路径 ──")
            pathLine(this, "home", paths.home)
            pathLine(this, "agentDir", paths.agentDir)
            pathLine(this, "runtime", paths.runtime)
            pathLine(this, "rootfs", paths.rootfs)
            pathLine(this, "engines", paths.engines)
            pathLine(this, "nativeLib", paths.nativeLib)
            pathLine(this, "proot 二进制", paths.prootBinary())
            pathLine(this, "proot loader", paths.prootLoader())
            pathLine(this, "stamp", paths.stampFile())
            appendLine()

            appendLine("── 最近的失败与错误 ──")
            if (failures.isEmpty()) {
                appendLine("（App 目前没有记录到失败或错误）")
            } else {
                failures.forEach { appendLine("- $it") }
            }
        }

        return redact(body).take(MAX_REPORT_CHARS)
    }

    /**
     * The one redaction pass, applied to the whole report.
     *
     * A denylist over free text is never complete, which is why the report also
     * says on its own first lines that redaction is best-effort. It does cover the
     * shapes that actually reach stderr and error messages here: an assignment to
     * a key-ish name (`token=…`, `"apiKey": "…"`), an `sk-…` key, a bearer token,
     * and a POSIX or Windows home directory whose segment is a person's name.
     */
    internal fun redact(text: String): String {
        var out = text
        out = SECRET_ASSIGNMENT.replace(out) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<已脱敏>"
        }
        out = SK_KEY.replace(out, "sk-<已脱敏>")
        out = BEARER.replace(out) { match -> "${match.groupValues[1]} <已脱敏>" }
        out = POSIX_HOME.replace(out) { match -> "/${match.groupValues[1]}/<用户>" }
        out = WINDOWS_HOME.replace(out) { match -> "\\Users\\<用户>" }
        return out
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    private fun readStamp(paths: PiPaths): String = runCatching {
        val stamp = paths.stampFile()
        if (stamp.isFile) stamp.readText().trim().ifBlank { "（空）" } else "（没有 stamp 文件）"
    }.getOrElse { "读不到：${it::class.java.simpleName}" }

    private fun storageLine(context: Context): String = runCatching {
        val stat = StatFs(context.filesDir.path)
        "${formatBytes(stat.availableBytes)} 可用 / 共 ${formatBytes(stat.totalBytes)}"
    }.getOrElse { "读不到：${it::class.java.simpleName}" }

    private fun memoryLine(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (manager == null) {
            "读不到：系统没有 ActivityManager"
        } else {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            "${formatBytes(info.availMem)} 可用 / 共 ${formatBytes(info.totalMem)}" +
                "（低内存状态：${if (info.lowMemory) "是" else "否"}，阈值 ${formatBytes(info.threshold)}）"
        }
    }.getOrElse { "读不到：${it::class.java.simpleName}" }

    private fun pathLine(builder: StringBuilder, label: String, file: File) {
        builder.appendLine("$label：${file.path}（${if (file.exists()) "存在" else "不存在"}）")
    }

    private fun formatDuration(ms: Long?): String = when {
        ms == null -> "未测量"
        ms < 1000L -> "$ms 毫秒"
        else -> String.format(Locale.US, "%.1f 秒", ms / 1000.0)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

    private val SECRET_ASSIGNMENT = Regex(
        """(?i)(api[_-]?key|apikey|access[_-]?key|token|secret|password|passwd|authorization|bearer)(\s*[:=]\s*)"?([^\s",;}]{6,})""",
    )

    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{8,}""")

    private val BEARER = Regex("""(?i)\b(bearer)\s+[A-Za-z0-9\-_.=]{10,}""")

    private val POSIX_HOME = Regex("""(?<![A-Za-z0-9])/(home|Users)/[^/\s"'\\]+""")

    private val WINDOWS_HOME = Regex("""[A-Za-z]:\\Users\\[^\\\s]+""")
}

/**
 * Where the report goes, and what to tell the user when it cannot get there.
 *
 * Both sinks are deliberate:
 *
 *  - **Download** through `DeviceSystemActions.export`, which is the app's one
 *    existing writer to a user-visible location (MediaStore on API 29+, the
 *    legacy directory below it). It needs no new permission on modern Android.
 *  - **The system share sheet** through `DeviceSystemActions.share`, which works
 *    even where Download does not (pre-API-29 without the storage grant, or a
 *    locked-down work profile) — and it is the path that actually gets the file
 *    to whoever is going to read it.
 *
 * Neither call touches the engine, so both keep working when the engine is dead —
 * which is the only situation where this feature earns its keep.
 */
object DiagnosticsExport {

    /** `pi-android-diagnostics-20260913-103000.txt` — sortable, no spaces. */
    fun fileName(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return "pi-android-diagnostics-$stamp.txt"
    }

    /** Save to the public Download folder; returns the sentence to show the user. */
    fun saveToDownloads(context: Context, text: String, name: String): String = try {
        DeviceSystemActions.export(
            context = context,
            name = name,
            text = text,
            base64 = null,
            mimeType = "text/plain",
        )
        "已保存到 Download/$name"
    } catch (error: DeviceActionException) {
        "保存到 Download 失败：" + error.denial.toMessage()
    } catch (error: Exception) {
        "保存到 Download 失败：${error::class.java.simpleName}: ${error.message}"
    }

    /** Open the share sheet with the whole report; returns the sentence to show. */
    fun share(context: Context, text: String, name: String): String = try {
        DeviceSystemActions.share(context = context, text = text, subject = name, url = null)
        "已打开分享"
    } catch (error: DeviceActionException) {
        "分享失败：" + error.denial.toMessage()
    } catch (error: Exception) {
        "分享失败：${error::class.java.simpleName}: ${error.message}"
    }
}
