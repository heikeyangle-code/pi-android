package app.pi.engine

/**
 * Turn "the engine exited with code 1" plus whatever it wrote to stderr into a
 * sentence a person can act on.
 *
 * ## Why this exists
 *
 * The App already captures pi's stderr (bounded, drained - the drain is required,
 * otherwise the 64 KB pipe fills and the engine blocks), but nothing read it back
 * out: the failure screen showed a title and an empty detail, and the diagnostic
 * report said the exit code was "not recorded". Meanwhile the user's whole report
 * is one line, `rpc: engine exited with code 1`, which names neither the cause nor
 * the next step.
 *
 * ## Why matching stderr is legitimate here
 *
 * Every rule below is pi's own output, copied from a **run** - not guessed from
 * reading pi's source. The first experiments were against pi 0.85.1 (node 24.19.0)
 * and their raw stderr is in `docs/engine-exit-review.md` §2; the strings and exit
 * codes were then re-measured on the pinned baseline, where **1.1.0, 1.2.0 and 1.3.0
 * behave identically**, and re-checked against the pinned engine's source, where they
 * are unchanged: `Failed to load extension` (`core/extensions/loader.ts:578`,
 * turned into an exit 1 at `main.ts:899-909`), `Unknown option` (`cli/args.ts:242`),
 * `@file arguments are not supported` (`main.ts:645`) and `Model not found`
 * (`modes/rpc/rpc-mode.ts:476`); `heap out of memory` / `FATAL ERROR` are V8's
 * strings, not pi's. What each one means:
 *
 *  - `Failed to load extension` - pi treats an extension load failure as a startup
 *    error and exits 1 (`main.ts:899-909`). On this device the shipped bridge,
 *    permission gate and highlight extensions live in `<agentDir>/extensions`, and
 *    a partially written tree or a user-installed extension with a missing module
 *    both land here.
 *  - `Unknown option` - a long option pi does not know, and pi **exits before it
 *    answers anything** (measured: exit 1, stdout empty). There are two sources, and
 *    the caller now hands this object the first one so it can be named:
 *    1. the text the user typed into 设置 → 扩展与资源 → 扩展启动参数 (`app.extensions.args`,
 *       [`summary`]'s `extensionArgs` argument). A name no loaded extension
 *       registered lands here, and since the app started forwarding that row it is
 *       the common source. The value lives in an app-only `SharedPreferences`
 *       sidecar, so **a reinstall does not clear it** (only uninstalling the app, or
 *       clearing/editing the row, does) - which is why "reinstall the app" is the
 *       wrong answer for this branch;
 *    2. a packaged engine whose extension set no longer matches the App's launch
 *       flags, i.e. a payload change nobody reconciled (`docs/pi-contract.md`).
 *  - `not found` / `@file arguments are not supported` - the launch arguments
 *    themselves are wrong. Not reachable today (the App passes neither `--model`
 *    nor positional files), which is exactly why it is worth naming if it ever
 *    appears: it would be a regression in argument building, not user error.
 *  - `heap out of memory` / `FATAL ERROR` - V8 gave up. The exit code for that is
 *    a signal (134), never 1, so this rule is here to keep the diagnosis honest
 *    when the code is something other than 1.
 *  - `proot error` - the Linux compatibility layer failed before pi ran. Different
 *    owner, different fix.
 *
 * ## When it says nothing
 *
 * [summary] returns `null` for an exit it cannot attribute **and** a blank stderr.
 * That is deliberate: the failure screen then shows the generic sentence the
 * ViewModel already has, instead of inventing a cause. When the stderr is blank but
 * there *is* an exit code, the sentence says so and names the two things that leave
 * no trace (the compatibility layer, and a kill by the system).
 *
 * No imports on purpose: compiled and run by the bare-JVM harness in
 * `tools/run-app-pure-checks.sh`.
 */
object EngineExitCause {

    /** How much stderr is quoted back; the full text lives in the diagnostic report. */
    const val EVIDENCE_MAX_CHARS: Int = 400

    /**
     * One sentence for the failure screen, or null when nothing is attributable.
     *
     * [extensionArgs] is the text of `app.extensions.args` for **this launch** — the
     * one launch input the user types freely, read by the caller through the same
     * app-only sidecar the launch itself uses (`ExtensionArgsStore`); `null` when the
     * row is empty or unreadable. It exists because the `Unknown option` rule has two
     * sources with two different ways out, and only this one is something the user can
     * check: a flag no loaded extension registered is almost always the text in that
     * row, and the row survives a reinstall. Passing the **string** (rather than a
     * boolean, and rather than reading storage here) is what keeps this object
     * Android-free — see the class KDoc.
     */
    fun summary(exitCode: Int?, stderr: String?, extensionArgs: String? = null): String? {
        classify(stderr, extensionArgs)?.let { return it }
        if (exitCode == null) return null
        return when {
            exitCode == 0 -> null
            exitCode > 128 ->
                "引擎被系统终止（退出码 $exitCode），最常见的来源是内存不足。请关掉其它 App、" +
                    "少开大会话后重试。"
            exitCode == 1 ->
                "引擎以退出码 1 退出，但这次没有向 stderr 写下原因。这类无输出的退出最常见的是" +
                    "Linux 兼容层启动失败，或引擎被系统杀死。"
            // ---- 126 / 127: the process never ran (2026-09-19) -----------------------
            // Both are POSIX shell codes for "found but cannot be executed" and "command
            // not found", and both are also `libproroot.so`'s own child-failure codes
            // (`_exit(126)` / `_exit(127)` in its `run_child_exec`). The literals are used
            // rather than `ProrootExecProbe.EXIT_CANNOT_EXEC` on purpose: this object is
            // compiled alone by the `engine-exit-cause` harness, which must not depend on
            // the runtime package (see the class KDoc).
            exitCode == 126 ->
                "引擎以退出码 126 退出：要执行的命令存在，但**没法执行它**（exec 失败）——" +
                    "这一层失败发生在 Node 起来之前，所以没有任何 pi 的输出。" +
                    "最常见的原因是一个二进制没有执行权限，或 proroot 的装载器没能把它装起来。" +
                    "哪个二进制、退出码和原话都在「设置 → 诊断报告」的 proroot 取证里。"
            exitCode == 127 ->
                "引擎以退出码 127 退出：要执行的命令**找不到**。" +
                    "运行时里的 Node 可能没有装好或被移走了；" +
                    "「设置 → 诊断报告」里有路径清单和这次启动的 proroot 取证。"
            else -> "引擎以退出码 $exitCode 退出。"
        }
    }

    /**
     * The stderr line that produced the verdict, trimmed and bounded, or null when
     * no line matched. Kept separate so the failure screen can show the engine's own
     * words under the App's sentence.
     */
    fun evidence(stderr: String?): String? {
        val line = stderr.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && classify(it, null) != null }
            ?: return null
        return if (line.length <= EVIDENCE_MAX_CHARS) line else line.take(EVIDENCE_MAX_CHARS) + "…"
    }

    /**
     * What the failure screen renders as the detail: the App's sentence, then the
     * engine's own words when there are any. `null` when there is nothing to say
     * beyond the caller's generic message.
     *
     * [extensionArgs] is forwarded to [summary]; see there for what it is and why it
     * is a parameter.
     */
    fun detail(exitCode: Int?, stderr: String?, extensionArgs: String? = null): String? {
        val sentence = summary(exitCode, stderr, extensionArgs) ?: return null
        val evidence = evidence(stderr) ?: return sentence
        return sentence + "\n引擎写到 stderr 的那一行：" + evidence
    }

    /**
     * The rule table, in order. `contains` rather than regex: the strings are
     * literal pi output and a regex would only add ways to be wrong.
     *
     * [extensionArgs] is only consulted by the `Unknown option` rule; every other
     * rule is a fact about the text pi printed and must not change with it.
     */
    private fun classify(text: String?, extensionArgs: String?): String? {
        val t = text.orEmpty()
        if (t.isBlank()) return null
        return when {
            t.contains("Failed to load extension") ->
                // 这里原本指向「工作区 → 终端」的扩展目录。终端不是可用面，那句指引做不到；
                // 而且扩展目录是应用私有存储，任何文件管理器都打不开它。所以只留用户能执行的
                // 动作：把最近装过或改过的扩展移走，再重试。设置 → 扩展 → 扩展包与项目信任
                // 列出了引擎会加载的扩展，用户可以在那里认出是哪一个。
                "有一个扩展没能加载，引擎因此在启动时直接退出。" +
                    "把最近安装或最近改动过的那个扩展移走后重试；随包自带的扩展会在下次启动时自动重新安装。"
            t.contains("Unknown option") ->
                // Two sources, two sentences, and the row is the one the user can act
                // on. Measured: an unregistered flag exits 1 with stdout empty, so this
                // stderr line is the *only* thing that says why — and the earlier text
                // ("通常是运行时载荷与 App 版本不一致。重装 App 一般能恢复") sent the user to
                // a reinstall for the one cause a reinstall cannot fix: the value lives
                // in `ExtensionArgsStore`'s SharedPreferences file.
                if (!extensionArgs.isNullOrBlank()) {
                    "引擎因为不认识的启动选项退出了。「设置 → 扩展与资源 → 扩展启动参数」那一行里写的参数，" +
                        "引擎没有对应的扩展去声明它 —— 这一行正是最可能的来源。" +
                        "改正那一行（或者把它清空）后重试即可。" +
                        "注意这一行的值保存在应用自己的存储里，重装 App **不会**清掉它；" +
                        "请把下面这一行原文一起发回。"
                } else {
                    "引擎不认识的启动选项。最常见的原因是「设置 → 扩展与资源 → 扩展启动参数」那一行里" +
                        "写进了引擎没有的扩展参数；这次那一行是空的（或读不到），" +
                        "所以更可能是运行时载荷与 App 版本不一致。重装 App 一般能恢复；" +
                        "请把下面这一行原文一起发回。"
                }
            t.contains("@file arguments are not supported") ->
                "启动参数里带了引擎不接受的输入。请把下面这一行原文发回。"
            t.contains("not found") && t.contains("Model") ->
                "启动参数里指定的模型不存在。请把下面这一行原文发回。"
            t.contains("heap out of memory") || t.contains("FATAL ERROR") ->
                "引擎内存不足被系统终止。请关掉其它 App、少开大会话后重试。"
            t.contains("proot error") ->
                "Linux 兼容层启动失败，问题不在引擎本身。请把下面这一行原文发回。"
            // ---- the runtime's and the shell's own words (2026-09-19) ----------------
            // The device's failure was reported as `引擎以退出码 126 退出。` and nothing else,
            // while the process had in fact written one of the lines below to stderr:
            // proroot's launcher prints the stage it died at, and the `bash` it starts names
            // the file it could not execute. Keeping only pi-shaped patterns threw both away
            // and left the one failure nobody could act on. These rules are ordered after
            // pi's own so a real pi message always wins, and each quotes the line verbatim
            // through `evidence()`.
            t.contains("[proroot] child: stage=") ->
                "proroot 启动器没能运行 guest 命令：下面这一行是它自己写的失败阶段与 errno。" +
                    "「设置 → 诊断报告」里有这次启动的取证。"
            t.contains("[proroot] launcher: failure_stage=") ->
                "proroot 启动器报出了失败阶段（下面是它的原话）。" +
                    "「设置 → 诊断报告」里有这次启动的取证。"
            t.startsWith("[proroot]") ->
                "proroot 启动器自己报了错，下面这一行是它的原话。" +
                    "「设置 → 诊断报告」里有这次启动的取证。"
            t.contains("cannot execute binary file") || t.contains("Exec format error") ->
                "要执行的二进制存在，但系统拒绝执行它（文件格式或解释器不对）。" +
                    "下面这一行是当时的原话。"
            t.contains("bash:") && t.contains("Permission denied") ->
                "有一个要执行的二进制没有执行权限，或被执行策略拒绝。下面这一行是当时的原话。"
            t.contains("bash:") && t.contains("No such file or directory") ->
                "要执行的二进制或它的解释器不在了。下面这一行是当时的原话。"
            else -> null
        }
    }
}
