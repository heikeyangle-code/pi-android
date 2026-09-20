package app.pi.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import app.pi.BuildConfig
import app.pi.bridge.DeviceActionException
import app.pi.bridge.DeviceSystemActions
import app.pi.packages.PiBuiltinExtension
import app.pi.packages.PiExtensionLoadErrors
import app.pi.runtime.BootAudit
import app.pi.runtime.DurableLayout
import app.pi.runtime.GuestEngine
import app.pi.runtime.GuestToolProbe
import app.pi.runtime.PiPaths
import app.pi.runtime.ProrootConfigSweep
import app.pi.runtime.ProrootProbeNarrative
import app.pi.runtime.RuntimeChoice
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.RuntimeSelection
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
    /**
     * Events the engine's reader thread could not hand to the app's collector
     * (`PiEngineSession.droppedEvents`).
     *
     * A number rather than a flag because the failure it describes is a *backlog*:
     * the flow's buffer filled because the main-thread collector was held up, and
     * the events after that are lost until it catches up. Zero is the expected
     * value; anything else means the transcript may be missing deltas.
     */
    val droppedEvents: Long = 0L,
    /**
     * Events whose fold into the transcript threw (`PiEngineSession.reducerFailures`).
     *
     * Separate from [droppedEvents] on purpose: a drop is a delivery problem, a throw
     * is a bug in the projection, and the two need different words.
     */
    val reducerFailures: Long = 0L,
    /** The newest of either, as one sentence, or null when there has been none. */
    val lastRecordProblem: String? = null,
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
 *    payload, and one bounded guest invocation for the tool-chain section); callers
 *    run [build] off the main thread.
 *  - The tool-chain section is a **real** invocation, not a file-existence check,
 *    and it is bounded: [`GuestToolProbe.TIMEOUT_MS`][GuestToolProbe.TIMEOUT_MS].
 *    A tool that cannot be executed must appear here as a failure with its exit
 *    code, because the callers that lose their backend when it is missing (pi's
 *    `find` and the `@` completion) fail silently.
 */
object DiagnosticsReport {

    /** How much of the engine's captured stderr the report carries, from the tail. */
    private const val STDERR_TAIL_CHARS = 20_000

    /**
     * How much of the boot audit's text the report prints, from the tail.
     *
     * The audit file is itself bounded ([BootAudit.MAX_LINES] lines, each one small), so
     * this rarely trims anything; it exists so a file written by some other build cannot
     * push the report past [MAX_REPORT_CHARS]. When it does trim, the report says so.
     */
    private const val AUDIT_TAIL_CHARS = 24_000

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
        /**
         * Which of pi's two entries the engine was last launched on
         * (`PiEngineHost.EngineEntry`), or null when no engine has been launched in this
         * process. A plain string because this layer must not depend on the engine package;
         * the caller passes `EngineEntry.label`, which names the relative path.
         *
         * It is printed **outside** the exit section on purpose: the whole point is to say
         * which entry is in use *while the engine is running*, so that a startup time in this
         * report is not an ambiguous reading (2.0 s bundled against 18–20 s unpacked).
         */
        engineEntry: String? = null,
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
            appendLine("PI 诊断报告")
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

            appendLine("── 引擎入口（本次进程最后一次启动）──")
            appendLine(
                "入口：${engineEntry ?: "还没有启动过引擎（本次进程）"}",
            )
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
                // The two reader-thread losses, printed even when they are zero: a
                // report that only shows them on failure cannot tell "never happened"
                // apart from "this build does not count them".
                appendLine(
                    if (engine.droppedEvents == 0L && engine.reducerFailures == 0L) {
                        "事件投递：无丢失（事件流未溢出，转录投影未抛异常）"
                    } else {
                        "事件投递：丢失 ${engine.droppedEvents} 个事件，" +
                            "转录投影失败 ${engine.reducerFailures} 次"
                    },
                )
                engine.lastRecordProblem?.takeIf { it.isNotBlank() }?.let {
                    appendLine("最近一条：$it")
                }
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
            pathLine(this, "workspaces", paths.workspaces)
            pathLine(this, "persist", paths.persist)
            pathLine(this, "runtime", paths.runtime)
            pathLine(this, "rootfs", paths.rootfs)
            pathLine(this, "engines", paths.engines)
            pathLine(this, "nativeLib", paths.nativeLib)
            pathLine(this, "proot 二进制", paths.prootBinary())
            pathLine(this, "proot loader", paths.prootLoader())
            pathLine(this, "stamp", paths.stampFile())
            // The structural promise behind "an upgrade cannot delete the workspace": the
            // three durable directories are outside the volatile tree. `PiPaths`' own
            // construction asserts the same thing and throws, so this line is what a
            // report shows when the assertion is not in the build the user has. Empty =
            // correct; anything else names the directory and the tree it fell inside.
            val layoutViolations = DurableLayout.violations(paths.home, paths.runtime)
            appendLine(
                "耐久目录是否落在易失树内：" +
                    if (layoutViolations.isEmpty()) {
                        "否（工作区、agentDir、persist 都在 ${paths.runtime.name}/ 之外）"
                    } else {
                        layoutViolations.joinToString("；")
                    },
            )
            appendLine()

            appendLine("── 启动审计（耐久，一次升级一行）──")
            val auditFile = BootAudit.file(paths.persist)
            appendLine(
                "文件：${auditFile.absolutePath}" +
                    "（每次启动检查一次，只有 APK 的 lastUpdateTime/versionCode 或运行时 revision " +
                    "相对上次记录发生变化时才追加一行；只保留最近 ${BootAudit.MAX_LINES} 行，" +
                    "被挤掉的条数写在文件头部）",
            )
            val auditText = BootAudit.readTail(auditFile)
            when {
                auditText == null && !auditFile.isFile ->
                    appendLine("（还没有记录：这台设备上还没有出现过一次「升级后的首次启动」。）")
                auditText == null ->
                    appendLine("（读取失败：文件在，但内容读不出来——不是「没有记录」。）")
                auditText.isBlank() ->
                    appendLine("（读取成功，但文件是空的。）")
                else -> {
                    if (auditText.length > AUDIT_TAIL_CHARS) {
                        appendLine("（已截断，只打印最后 $AUDIT_TAIL_CHARS 字符）")
                        appendLine(auditText.takeLast(AUDIT_TAIL_CHARS))
                    } else {
                        appendLine(auditText.trimEnd())
                    }
                }
            }
            appendLine()

            // ---- 运行时选择（proroot）--------------------------------------
            // The effective runtime and *why*, plus the five binaries whose presence
            // is only one of the three conditions. `status()` reads the cached gate
            // verdict and never runs the gate: a report must not be the thing that
            // starts the closed-source runtime, and it must not spend seconds
            // probing on the way out of a crash.
            appendLine("── 运行时选择 ──")
            val runtimeSelection = RuntimeSelection.of(context, paths)
            val runtimeStatus = runCatching { runtimeSelection.status() }.getOrNull()
            if (runtimeStatus == null) {
                appendLine("（读不到运行时选择状态）")
            } else {
                // 句子本身已经先写运行时（`RuntimeChoice.describe`），所以这里不再前置
                // `runtimeStatus.engine` 的枚举名 —— 那会印成
                // 「实际生效：Proot（proot（探针未通过）…）」，同一个答案说两遍，还一遍是
                // Kotlin 枚举名。开关的状态在下一行单独报。
                appendLine("  实际生效：${runtimeStatus.summary}")
                appendLine("  开关：${if (runtimeStatus.enabled) "开" else "关"}" +
                    " · 连续失败：${runtimeStatus.failures}/${RuntimeChoice.MAX_CONSECUTIVE_FAILURES}")
                // The 档 in force, named here rather than only implied by a probe verdict:
                // it is what the probe's rules are relative to ("is an untranslated raw
                // syscall disqualifying?") and what the cache key identifies. The `tag` is
                // the machine-readable half and the disclosure is the user-readable one, so
                // a report can be matched to a `ProrootProbeCache` file without guessing.
                appendLine("  proroot 档：${runtimeStatus.mode.tag}（${runtimeStatus.mode.disclosure}）")
                appendLine(
                    "  proroot 探针：" + when (runtimeStatus.probePassed) {
                        true -> "已通过（缓存）"
                        false -> "未通过（缓存）"
                        null -> "尚未运行"
                    },
                )
                if (runtimeStatus.missingComponents.isNotEmpty()) {
                    appendLine("  缺少运行时文件：${runtimeStatus.missingComponents.joinToString("、")}")
                }
                // The same assembly the settings row uses (`ProrootProbeNarrative`): the
                // report prints it **unbounded**, because the export is where the whole
                // evidence belongs, while the row shows a capped prefix and says how many
                // lines it left out. Two renderings of one list, so a line here and a line
                // under the row cannot disagree, and the "尚未运行/读不到" cases say so in
                // both places instead of printing nothing.
                ProrootProbeNarrative.detailLines(
                    fallback = runtimeStatus.fallback,
                    probePassed = runtimeStatus.probePassed,
                    probeDetail = runtimeStatus.probeDetail,
                ).forEach { appendLine("  $it") }
            }
            pathLine(this, "proroot launcher", paths.prorootLauncher())
            pathLine(this, "proroot runtime", paths.prorootRuntimeHook())
            pathLine(this, "proroot linker", paths.prorootLinker())
            pathLine(this, "proroot trampoline", paths.prorootBridge())
            pathLine(this, "proroot stub loader", paths.prorootStubLoader())
            // `prorootTmpDir`, not `prorootTmp`: reading a report must not create a
            // directory, or every proot-only user would grow an empty `proroot-tmp`
            // just by exporting diagnostics.
            pathLine(this, "proroot tmp", paths.prorootTmpDir())
            // What proroot leaves behind: one fixed-size table per launch, named for
            // the launcher's pid. Counted here because the sweep only runs before a
            // proroot launch, so a device that stopped using proroot keeps whatever it
            // had until then.
            val prorootConfigs = runCatching {
                paths.prorootTmpDir().listFiles()
                    ?.count { it.name.startsWith(ProrootConfigSweep.PREFIX) } ?: 0
            }.getOrDefault(0)
            appendLine("  .proroot-config 现存 $prorootConfigs 份")
            // The engine-failure autopsy: written by `PiEngineHost` when a proroot engine
            // exited 126/127, and read here **from the file** because the session that
            // produced it is gone by the time a report is exported — publishing null is what
            // a failed boot does, so an in-memory-only copy would make this section empty
            // exactly when it matters (`PiPaths.prorootEngineForensics`).
            //
            // An absent file is stated with its meaning ("no proroot engine died this way"),
            // not left blank: a missing block and an empty block are the two things a reader
            // cannot tell apart, and the whole point of this section is that 126 should stop
            // being a bare number.
            val forensicsFile = paths.prorootEngineForensics()
            if (forensicsFile.isFile) {
                appendLine("  proroot 引擎失败取证（${forensicsFile.name}）：")
                runCatching { forensicsFile.readLines() }
                    .getOrElse { error -> listOf("（读不到取证文件：${error::class.java.simpleName}: ${error.message}）") }
                    .forEach { appendLine("    $it") }
            } else {
                appendLine("  proroot 引擎失败取证：没有（本运行时树里还没有 proroot 引擎以 126/127 退出的记录）")
            }
            appendLine()

            appendLine("── 工具链自检（真调用） ──")
            appendLine(
                "说明：这里不看文件是否存在，而是在 guest 里真的运行一次 rg / fd：" +
                    "先 --version，再真的搜索一个 guest 文件，两次都要求退出码 0 且有输出。" +
                    "软链悬空、二进制跑不起来、或它读不到 guest 路径，都会在这里变成一条 ✗。",
            )
            // Probed through the runtime that would actually run, so the report shows
            // the same answer a real tool call would get. When proroot is in effect
            // this doubles as the second half of the gate's evidence; `GuestToolProbe`
            // takes the engine as a parameter for exactly this reason.
            val probeEngine = runtimeStatus?.engine ?: GuestEngine.Proot
            appendLine("  （本次通过 ${probeEngine} 调用）")
            val toolProbe = runCatching {
                GuestToolProbe.run(paths, android.os.Environment.getExternalStorageDirectory(), probeEngine)
            }.getOrElse { error ->
                GuestToolProbe.Report(
                    emptyList(),
                    launchError = "探针本身抛了异常：${error::class.java.simpleName}: ${error.message}",
                )
            }
            toolProbe.describe().forEach { appendLine("  $it") }
            if (toolProbe.exitCode != null) appendLine("  （guest 命令退出码 ${toolProbe.exitCode}）")
            appendLine()

            // ---- 扩展发现 ------------------------------------------------------
            // 用户报过的形状：「随包扩展注册的工具一个都不在」。它有两半，只有一半会留下
            // 日志：pi 在 RPC 模式下把「扩展加载失败」打到 stderr 并以退出码 1 结束（那半
            // 由 `PiExtensionLoadErrors` 从已捕获的 stderr 里解析），而**目录遍历**失败
            // （`readdir` 出错）被 pi 的 `catch` 吃掉，一个字节都不留 —— 那一半只能靠宿主
            // 读数 + 一条给用户的手工命令分辨。两半都写在这里，就是为了让读者按顺序排除。
            appendLine("── 扩展发现 ──")
            appendLine(
                "说明：pi 的扩展/技能/提示词/主题都靠「列目录」发现，而列目录失败时它**不打印任何东西**；" +
                    "只有模块加载失败才会打到 stderr 并以退出码 1 结束。两半在这里分开报。",
            )
            val loadErrors = PiExtensionLoadErrors.parse(engine?.stderr)
            loadErrors.describe().forEach { appendLine(it) }
            if (engine == null) {
                appendLine("  （本进程还没有引擎退出的记录，所以没有可解析的 stderr —— 上面这句只针对已捕获的输出）")
            } else if (loadErrors.failures.isEmpty()) {
                appendLine("  （有 stderr 可解析，里面没有这一句 ⇒ 不是模块加载失败）")
            }
            appendLine("  随包扩展在两个 agent 目录里的存在性（宿主读数，与引擎是否看得见无关）：")
            PiBuiltinExtension.SHIPPED.forEach { shipped ->
                val inEngineDir = File(paths.agentDir, "extensions/${shipped.entryUnderExtensions}").isFile
                val inRootfs = File(paths.rootfs, "root/.pi/agent/extensions/${shipped.entryUnderExtensions}").isFile
                val presence = when (shipped.presenceIn(inEngineDir, inRootfs)) {
                    PiBuiltinExtension.Presence.EngineAgentDir -> "在（引擎读的那一份）"
                    PiBuiltinExtension.Presence.RootfsCopyOnly -> "只在运行时副本里（引擎读的是另一份）"
                    PiBuiltinExtension.Presence.Missing -> "两份都没有"
                }
                appendLine("    ${shipped.name}：$presence")
            }
            appendLine("  如果上面是「在」，而 pi 的工具列表里没有它们，就在终端里真的列一次目录（不是看文件在不在）：")
            appendLine("    ls -A /root/.pi/agent/extensions; ls -A /root/.pi/agent/skills")
            appendLine("  这两条报 No such file or directory 或输出为空、而上面显示文件在 ⇒ 目录读不出来（运行时环境问题）；")
            appendLine("  能列出来 ⇒ 是模块加载问题，回到上面看有没有 stderr 那一句。")
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
