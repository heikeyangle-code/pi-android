package app.pi.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import app.pi.BuildConfig
import app.pi.bridge.DeviceActionException
import app.pi.bridge.DeviceSystemActions
import app.pi.i18n.tr
import app.pi.runtime.BootAudit
import app.pi.runtime.DurableLayout
import app.pi.runtime.GuestEngine
import app.pi.runtime.GuestToolProbe
import app.pi.runtime.PiCrashStore
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
            appendLine(tr("PI 诊断报告", "PI Diagnostics Report"))
            appendLine(tr("生成时间：${timestamp()}", "Generated: ${timestamp()}"))
            appendLine()
            appendLine(
                tr(
                    "说明：本文件是纯文本诊断材料，用于在引擎已经退出的情况下把可诊断的信息带到别处。",
                    "Note: Plain-text diagnostic bundle for troubleshooting even after the engine has exited.",
                ),
            )
            appendLine(
                tr(
                    "它不会被交给模型，也不会写进对话上下文。",
                    "Never sent to any model or written to conversation context.",
                ),
            )
            appendLine(
                tr(
                    "已做脱敏：疑似 token / API Key / 密码的值替换为 <已脱敏>，家目录里的用户名替换为 <用户>。",
                    "Redacted: suspected tokens/API keys/passwords replaced with <redacted>, home usernames with <user>.",
                ),
            )
            appendLine(
                tr(
                    "报告仍可能包含普通文件路径与错误原文。",
                    "Report may still contain ordinary file paths and raw error text.",
                ),
            )
            appendLine()

            appendLine(tr("── App 与设备 ──", "-- App & Device --"))
            appendLine(
                tr(
                    "App 版本：${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})",
                    "App version: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})",
                ),
            )
            appendLine(tr("包名：${context.packageName}", "Package: ${context.packageName}"))
            appendLine(
                tr(
                    "Android：${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）",
                    "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                ),
            )
            appendLine(
                tr(
                    "设备：${Build.MANUFACTURER} ${Build.MODEL}（device ${Build.DEVICE}）",
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL} (device ${Build.DEVICE})",
                ),
            )
            appendLine(tr("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}", "ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}"))
            appendLine(tr("应用私有目录可用存储：${storageLine(context)}", "App storage: ${storageLine(context)}"))
            appendLine(tr("内存：${memoryLine(context)}", "Memory: ${memoryLine(context)}"))
            appendLine()

            appendLine(tr("── 引擎入口（本次进程最后一次启动）──", "-- Engine Entry (Last Launch) --"))
            appendLine(
                tr(
                    "入口：${engineEntry ?: "还没有启动过引擎（本次进程）"}",
                    "Entry: ${engineEntry ?: "Engine has not launched in this process"}",
                ),
            )
            appendLine()
            appendLine(tr("── 引擎最后一次退出 ──", "-- Last Engine Exit --"))
            if (engine == null) {
                appendLine(
                    tr(
                        "还没有记录：本次进程里引擎尚未退出过，或记录发生在本次改动之前。",
                        "No exit recorded in this process.",
                    ),
                )
            } else {
                appendLine(tr("状态：${engine.state ?: "未知"}", "State: ${engine.state ?: "unknown"}"))
                appendLine(
                    if (engine.exitCode != null) {
                        tr("退出码：${engine.exitCode}", "Exit code: ${engine.exitCode}")
                    } else {
                        tr(
                            "退出码：未记录（当前版本没有把退出码写进引擎状态，只有 stderr 被捕获）",
                            "Exit code: not recorded (only stderr captured)",
                        )
                    },
                )
                appendLine(tr("最近一次启动耗时：${formatDuration(engine.startupMs)}", "Last startup: ${formatDuration(engine.startupMs)}"))
                // The two reader-thread losses, printed even when they are zero: a
                // report that only shows them on failure cannot tell "never happened"
                // apart from "this build does not count them".
                appendLine(
                    if (engine.droppedEvents == 0L && engine.reducerFailures == 0L) {
                        tr(
                            "事件投递：无丢失（事件流未溢出，转录投影未抛异常）",
                            "Event delivery: no losses (no buffer overflow, no reducer throws)",
                        )
                    } else {
                        tr(
                            "事件投递：丢失 ${engine.droppedEvents} 个事件，转录投影失败 ${engine.reducerFailures} 次",
                            "Event delivery: ${engine.droppedEvents} dropped events, ${engine.reducerFailures} reducer failures",
                        )
                    },
                )
                engine.lastRecordProblem?.takeIf { it.isNotBlank() }?.let {
                    appendLine(tr("最近一条：$it", "Last issue: $it"))
                }
                appendLine()
                appendLine(
                    tr(
                        "已捕获的 stderr（引擎侧上限 64 KB，这里取尾部最多 $STDERR_TAIL_CHARS 字符）：",
                        "Captured stderr (64 KB engine cap; last $STDERR_TAIL_CHARS chars):",
                    ),
                )
                val stderr = engine.stderr?.trim()
                if (stderr.isNullOrBlank()) {
                    appendLine(tr("（空：引擎退出前没有向 stderr 写任何内容）", "(empty: nothing written to stderr)"))
                } else {
                    if (stderr.length > STDERR_TAIL_CHARS) {
                        appendLine(
                            tr(
                                "（已截断，只保留最后 $STDERR_TAIL_CHARS 字符）",
                                "(truncated to last $STDERR_TAIL_CHARS chars)",
                            ),
                        )
                    }
                    appendLine(stderr.takeLast(STDERR_TAIL_CHARS))
                }
            }
            appendLine()

            appendLine(tr("── 运行时事实 ──", "-- Runtime Facts --"))
            appendLine(
                tr(
                    "运行时已解包：${if (facts.runtimeUnpacked) "是" else "否"}",
                    "Runtime unpacked: ${if (facts.runtimeUnpacked) "yes" else "no"}",
                ),
            )
            appendLine(tr("已解包的 revision（stamp）：${readStamp(paths)}", "Unpacked revision (stamp): ${readStamp(paths)}"))
            appendLine(tr("APK 内的 revision：${RuntimeProvisioner.packagedRevision(context.assets)}", "Packaged revision: ${RuntimeProvisioner.packagedRevision(context.assets)}"))
            appendLine(tr("RUNTIME_REVISION 兜底常量：${RuntimeProvisioner.RUNTIME_REVISION}", "RUNTIME_REVISION fallback: ${RuntimeProvisioner.RUNTIME_REVISION}"))
            appendLine(tr("pi 版本：${facts.piVersion ?: "读不到"}", "pi version: ${facts.piVersion ?: "unreadable"}"))
            appendLine(tr("Node 版本：${facts.nodeVersion ?: "读不到"}", "Node version: ${facts.nodeVersion ?: "unreadable"}"))
            appendLine(tr("运行时占用：${facts.runtimeUsage ?: "读不到"}", "Runtime disk usage: ${facts.runtimeUsage ?: "unreadable"}"))
            appendLine(
                tr(
                    "唤醒锁：${if (facts.wakeLockHeld) "持有中" else "未持有"}",
                    "Wake lock: ${if (facts.wakeLockHeld) "held" else "not held"}",
                ),
            )
            appendLine(
                tr(
                    "引擎前台服务：${if (facts.serviceRunning) "运行中" else "未运行"}",
                    "Engine foreground service: ${if (facts.serviceRunning) "running" else "stopped"}",
                ),
            )
            appendLine()
            appendLine(
                tr(
                    "内置载荷（逐个测量；缺失或不可读会写明原因）：",
                    "Bundled payloads (measured individually; missing/unreadable noted):",
                ),
            )
            if (payloads.isEmpty()) {
                appendLine(
                    tr(
                        "（没能列出载荷：${payloadInventoryError ?: "APK 里没有 assets/runtime"}）",
                        "(could not list payloads: ${payloadInventoryError ?: "no assets/runtime in APK"})",
                    ),
                )
            } else {
                payloads.forEach { info ->
                    append("  ${info.asset}  ")
                    if (info.ok) {
                        append(formatBytes(info.bytes))
                        if (info.via != null) append(" via ${info.via}")
                    } else {
                        append(tr("缺失/不可读", "missing/unreadable"))
                        if (info.error != null) {
                            val first = info.error.lineSequence().first()
                            append(tr("（$first）", " ($first)"))
                        }
                    }
                    if (!info.required) append(tr("（可选）", " (optional)"))
                    appendLine()
                }
            }
            appendLine()

            appendLine(tr("── 关键路径 ──", "-- Key Paths --"))
            pathLine(this, "home", paths.home)
            pathLine(this, "agentDir", paths.agentDir)
            pathLine(this, "projectsRoot", paths.projectsRoot)
            pathLine(this, "persist", paths.persist)
            pathLine(this, "runtime", paths.runtime)
            pathLine(this, "rootfs", paths.rootfs)
            pathLine(this, "engines", paths.engines)
            pathLine(this, "nativeLib", paths.nativeLib)
            pathLine(this, tr("proot 二进制", "proot binary"), paths.prootBinary())
            pathLine(this, "proot loader", paths.prootLoader())
            pathLine(this, "stamp", paths.stampFile())
            val layoutViolations = DurableLayout.violations(paths.home, paths.runtime)
            appendLine(
                tr("耐久目录是否落在无保护的易失区：", "Durable dirs in unprotected volatile area: ") +
                    if (layoutViolations.isEmpty()) {
                        tr(
                            "否。projectsRoot（root/projects）与 agentDir（root/.pi/agent）在 rootfs 内（${paths.runtime.name}/rootfs/ 之下），" +
                                "由「重建运行时」删树前先搬出、删完再搬回的 DurablePreserve 保护；" +
                                "persist 在 ${paths.runtime.name}/ 之外，任何删除都碰不到。" +
                                "三者都不在易失树内、rootfs 之外的位置。",
                            "No. projectsRoot (root/projects) and agentDir (root/.pi/agent) live under ${paths.runtime.name}/rootfs/ and are preserved by DurablePreserve during rebuilds; persist lives outside ${paths.runtime.name}/.",
                        )
                    } else {
                        layoutViolations.joinToString(tr("；", "; "))
                    },
            )
            appendLine()

            appendLine(tr("── 启动审计（耐久，一次升级一行）──", "-- Boot Audit (Durable, 1 Line/Upgrade) --"))
            val auditFile = BootAudit.file(paths.persist)
            appendLine(
                tr(
                    "文件：${auditFile.absolutePath}" +
                        "（每次启动检查一次，只有 APK 的 lastUpdateTime/versionCode 或运行时 revision " +
                        "相对上次记录发生变化时才追加一行；只保留最近 ${BootAudit.MAX_LINES} 行，" +
                        "被挤掉的条数写在文件头部）",
                    "File: ${auditFile.absolutePath} (checked on launch; appends on APK or runtime revision change; keeps last ${BootAudit.MAX_LINES} lines)",
                ),
            )
            val auditText = BootAudit.readTail(auditFile)
            when {
                auditText == null && !auditFile.isFile ->
                    appendLine(tr("（还没有记录：这台设备上还没有出现过一次「升级后的首次启动」。）", "(no entries yet)"))
                auditText == null ->
                    appendLine(tr("（读取失败：文件在，但内容读不出来——不是「没有记录」。）", "(read failed: file exists but could not be read)"))
                auditText.isBlank() ->
                    appendLine(tr("（读取成功，但文件是空的。）", "(file is empty)"))
                else -> {
                    if (auditText.length > AUDIT_TAIL_CHARS) {
                        appendLine(tr("（已截断，只打印最后 $AUDIT_TAIL_CHARS 字符）", "(truncated to last $AUDIT_TAIL_CHARS chars)"))
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
            appendLine(tr("── 运行时选择 ──", "-- Runtime Selection --"))
            val runtimeSelection = RuntimeSelection.of(context, paths)
            val runtimeStatus = runCatching { runtimeSelection.status() }.getOrNull()
            if (runtimeStatus == null) {
                appendLine(tr("（读不到运行时选择状态）", "(runtime selection status unreadable)"))
            } else {
                // 句子本身已经先写运行时（`RuntimeChoice.describe`），所以这里不再前置
                // `runtimeStatus.engine` 的枚举名 —— 那会印成
                // 「实际生效：Proot（proot（探针未通过）…）」，同一个答案说两遍，还一遍是
                // Kotlin 枚举名。开关的状态在下一行单独报。
                appendLine(tr("  实际生效：${runtimeStatus.summary}", "  Active: ${runtimeStatus.summary}"))
                appendLine(
                    tr(
                        "  选择：${runtimeStatus.selected.name.lowercase()} · 连续失败：${runtimeStatus.failures}/${RuntimeChoice.MAX_CONSECUTIVE_FAILURES}",
                        "  Selected: ${runtimeStatus.selected.name.lowercase()} · Consecutive failures: ${runtimeStatus.failures}/${RuntimeChoice.MAX_CONSECUTIVE_FAILURES}",
                    ),
                )
                // The 档 in force, named here rather than only implied by a probe verdict:
                // it is what the probe's rules are relative to ("is an untranslated raw
                // syscall disqualifying?") and what the cache key identifies. The `tag` is
                // the machine-readable half and the disclosure is the user-readable one, so
                // a report can be matched to a `ProrootProbeCache` file without guessing.
                if (runtimeStatus.selected == app.pi.runtime.GuestEngine.Proroot) {
                    appendLine(
                        tr(
                            "  proroot 档：${runtimeStatus.mode.tag}（${runtimeStatus.mode.disclosure}）",
                            "  proroot mode: ${runtimeStatus.mode.tag} (${runtimeStatus.mode.disclosure})",
                        ),
                    )
                }
                appendLine(
                    tr(
                        "  ${runtimeStatus.selected.name.lowercase()} 探针：",
                        "  ${runtimeStatus.selected.name.lowercase()} probe: ",
                    ) + when (runtimeStatus.probePassed) {
                        true -> tr("已通过（缓存）", "passed (cached)")
                        false -> tr("未通过（缓存）", "failed (cached)")
                        null -> tr("尚未运行", "not run yet")
                    },
                )
                if (runtimeStatus.missingComponents.isNotEmpty()) {
                    appendLine(
                        tr(
                            "  缺少运行时文件：${runtimeStatus.missingComponents.joinToString("、")}",
                            "  Missing runtime files: ${runtimeStatus.missingComponents.joinToString(", ")}",
                        ),
                    )
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
            pathLine(this, "tawcroot", paths.tawcrootBinary())
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
            appendLine(tr("  .proroot-config 现存 $prorootConfigs 份", "  .proroot-config count: $prorootConfigs"))
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
                appendLine(
                    tr(
                        "  proroot 引擎失败取证（${forensicsFile.name}）：",
                        "  proroot failure forensics (${forensicsFile.name}):",
                    ),
                )
                runCatching { forensicsFile.readLines() }
                    .getOrElse { error ->
                        listOf(
                            tr(
                                "（读不到取证文件：${error::class.java.simpleName}: ${error.message}）",
                                "(could not read forensics file: ${error::class.java.simpleName}: ${error.message})",
                            ),
                        )
                    }
                    .forEach { appendLine("    $it") }
            } else {
                appendLine(
                    tr(
                        "  proroot 引擎失败取证：没有（本运行时树里还没有 proroot 引擎以 126/127 退出的记录）",
                        "  proroot failure forensics: none",
                    ),
                )
            }
            appendLine()

            appendLine(tr("── 工具链自检（真调用） ──", "-- Toolchain Self-Check (Live Invocation) --"))
            appendLine(
                tr(
                    "说明：这里不看文件是否存在，而是在 guest 里真的运行一次 rg / fd：" +
                        "先 --version，再真的搜索一个 guest 文件，两次都要求退出码 0 且有输出。" +
                        "软链悬空、二进制跑不起来、或它读不到 guest 路径，都会在这里变成一条 ✗。",
                    "Note: Runs rg / fd inside the guest (--version and a real search); broken binaries or paths show as ✗.",
                ),
            )
            // Probed through the runtime that would actually run, so the report shows
            // the same answer a real tool call would get. When proroot is in effect
            // this doubles as the second half of the gate's evidence; `GuestToolProbe`
            // takes the engine as a parameter for exactly this reason.
            val probeEngine = runtimeStatus?.engine ?: GuestEngine.Proot
            appendLine(tr("  （本次通过 ${probeEngine} 调用）", "  (invoked via ${probeEngine})"))
            val toolProbe = runCatching {
                GuestToolProbe.run(paths, android.os.Environment.getExternalStorageDirectory(), probeEngine)
            }.getOrElse { error ->
                GuestToolProbe.Report(
                    emptyList(),
                    launchError = tr(
                        "探针本身抛了异常：${error::class.java.simpleName}: ${error.message}",
                        "Probe threw: ${error::class.java.simpleName}: ${error.message}",
                    ),
                )
            }
            toolProbe.describe().forEach { appendLine("  $it") }
            if (toolProbe.exitCode != null) {
                appendLine(tr("  （guest 命令退出码 ${toolProbe.exitCode}）", "  (guest exit code ${toolProbe.exitCode})"))
            }
            appendLine()
            appendLine(tr("── 最近的失败与错误 ──", "-- Recent Failures & Errors --"))
            if (failures.isEmpty()) {
                appendLine(tr("（App 目前没有记录到失败或错误）", "(no failures or errors recorded)"))
            } else {
                failures.forEach { appendLine("- $it") }
            }

            // Durable cross-process crash / exit telemetry (persisted under <files>/pi/persist/).
            val persistedExit = PiCrashStore.readLastEngineExit(paths)
            if (engine == null && !persistedExit.isNullOrBlank()) {
                appendLine()
                appendLine(tr("── 上次引擎异常退出记录（持久化）──", "-- Persisted Last Engine Exit --"))
                appendLine(persistedExit)
            }
            val exitReasons = PiCrashStore.readHistoricalExitReasons(context)
            if (exitReasons.isNotEmpty()) {
                appendLine()
                appendLine(tr("── 系统进程退出记录（ApplicationExitInfo）──", "-- System Process Exit Reasons (ApplicationExitInfo) --"))
                exitReasons.forEach { appendLine("- $it") }
            }
            val crashLog = PiCrashStore.readCrashLog(paths)
            if (!crashLog.isNullOrBlank()) {
                appendLine()
                appendLine(tr("── Android 未捕获崩溃日志 ──", "-- Android Uncaught Crash Log --"))
                appendLine(crashLog)
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
        val redactedTag = tr("<已脱敏>", "<redacted>")
        val userTag = tr("<用户>", "<user>")
        var out = text
        out = SECRET_ASSIGNMENT.replace(out) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$redactedTag"
        }
        out = SK_KEY.replace(out, "sk-$redactedTag")
        out = BEARER.replace(out) { match -> "${match.groupValues[1]} $redactedTag" }
        out = POSIX_HOME.replace(out) { match -> "/${match.groupValues[1]}/$userTag" }
        out = WINDOWS_HOME.replace(out) { match -> "\\Users\\$userTag" }
        return out
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    private fun readStamp(paths: PiPaths): String = runCatching {
        val stamp = paths.stampFile()
        if (stamp.isFile) {
            stamp.readText().trim().ifBlank { tr("（空）", "(empty)") }
        } else {
            tr("（没有 stamp 文件）", "(no stamp file)")
        }
    }.getOrElse { tr("读不到：${it::class.java.simpleName}", "unreadable: ${it::class.java.simpleName}") }

    private fun storageLine(context: Context): String = runCatching {
        val stat = StatFs(context.filesDir.path)
        tr(
            "${formatBytes(stat.availableBytes)} 可用 / 共 ${formatBytes(stat.totalBytes)}",
            "${formatBytes(stat.availableBytes)} free / ${formatBytes(stat.totalBytes)} total",
        )
    }.getOrElse { tr("读不到：${it::class.java.simpleName}", "unreadable: ${it::class.java.simpleName}") }

    private fun memoryLine(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (manager == null) {
            tr("读不到：系统没有 ActivityManager", "unreadable: no ActivityManager")
        } else {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            tr(
                "${formatBytes(info.availMem)} 可用 / 共 ${formatBytes(info.totalMem)}" +
                    "（低内存状态：${if (info.lowMemory) "是" else "否"}，阈值 ${formatBytes(info.threshold)}）",
                "${formatBytes(info.availMem)} free / ${formatBytes(info.totalMem)} total " +
                    "(lowMemory=${info.lowMemory}, threshold ${formatBytes(info.threshold)})",
            )
        }
    }.getOrElse { tr("读不到：${it::class.java.simpleName}", "unreadable: ${it::class.java.simpleName}") }

    private fun pathLine(builder: StringBuilder, label: String, file: File) {
        builder.appendLine(
            tr(
                "$label：${file.path}（${if (file.exists()) "存在" else "不存在"}）",
                "$label: ${file.path} (${if (file.exists()) "exists" else "missing"})",
            ),
        )
    }

    private fun formatDuration(ms: Long?): String = when {
        ms == null -> tr("未测量", "not measured")
        ms < 1000L -> tr("$ms 毫秒", "$ms ms")
        else -> tr(String.format(Locale.US, "%.1f 秒", ms / 1000.0), String.format(Locale.US, "%.1f s", ms / 1000.0))
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
        tr("已保存到 Download/$name", "Saved to Downloads/$name")
    } catch (error: DeviceActionException) {
        tr("保存到 Download 失败：", "Failed to save to Downloads: ") + error.denial.toMessage()
    } catch (error: Exception) {
        tr(
            "保存到 Download 失败：${error::class.java.simpleName}: ${error.message}",
            "Failed to save to Downloads: ${error::class.java.simpleName}: ${error.message}",
        )
    }

    /** Open the share sheet with the whole report; returns the sentence to show. */
    fun share(context: Context, text: String, name: String): String = try {
        DeviceSystemActions.share(context = context, text = text, subject = name, url = null)
        tr("已打开分享", "Share sheet opened")
    } catch (error: DeviceActionException) {
        tr("分享失败：", "Share failed: ") + error.denial.toMessage()
    } catch (error: Exception) {
        tr(
            "分享失败：${error::class.java.simpleName}: ${error.message}",
            "Share failed: ${error::class.java.simpleName}: ${error.message}",
        )
    }
}
