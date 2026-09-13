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
 * Every rule below was reproduced against the pinned engine (pi 0.85.1, node
 * 24.19.0) and the strings are pi's own output, copied from the run - not guessed
 * from reading pi's source. The experiments and their raw stderr are in
 * `docs/engine-exit-review.md` §2. What each one means:
 *
 *  - `Failed to load extension` - pi treats an extension load failure as a startup
 *    error and exits 1 (`main.ts:897-906`). On this device the shipped bridge,
 *    permission gate and highlight extensions live in `<agentDir>/extensions`, and
 *    a partially written tree or a user-installed extension with a missing module
 *    both land here.
 *  - `Unknown option` - a long option pi does not know. On this device that means
 *    the packaged engine and the App's launch flags disagree, i.e. a payload
 *    change nobody reconciled (`docs/pi-contract.md`).
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

    /** One sentence for the failure screen, or null when nothing is attributable. */
    fun summary(exitCode: Int?, stderr: String?): String? {
        classify(stderr)?.let { return it }
        if (exitCode == null) return null
        return when {
            exitCode == 0 -> null
            exitCode > 128 ->
                "引擎被系统终止（退出码 $exitCode），最常见的来源是内存不足。请关掉其它 App、" +
                    "少开大会话后重试。"
            exitCode == 1 ->
                "引擎以退出码 1 退出，但这次没有向 stderr 写下原因。这类无输出的退出最常见的是" +
                    "Linux 兼容层启动失败，或引擎被系统杀死。"
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
            .firstOrNull { it.isNotEmpty() && classify(it) != null }
            ?: return null
        return if (line.length <= EVIDENCE_MAX_CHARS) line else line.take(EVIDENCE_MAX_CHARS) + "…"
    }

    /**
     * What the failure screen renders as the detail: the App's sentence, then the
     * engine's own words when there are any. `null` when there is nothing to say
     * beyond the caller's generic message.
     */
    fun detail(exitCode: Int?, stderr: String?): String? {
        val sentence = summary(exitCode, stderr) ?: return null
        val evidence = evidence(stderr) ?: return sentence
        return sentence + "\n引擎写到 stderr 的那一行：" + evidence
    }

    /**
     * The rule table, in order. `contains` rather than regex: the strings are
     * literal pi output and a regex would only add ways to be wrong.
     */
    private fun classify(text: String?): String? {
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
                "引擎不认识的启动选项（通常是运行时载荷与 App 版本不一致）。重装 App 一般能恢复；" +
                    "请把下面这一行原文一起发回。"
            t.contains("@file arguments are not supported") ->
                "启动参数里带了引擎不接受的输入。请把下面这一行原文发回。"
            t.contains("not found") && t.contains("Model") ->
                "启动参数里指定的模型不存在。请把下面这一行原文发回。"
            t.contains("heap out of memory") || t.contains("FATAL ERROR") ->
                "引擎内存不足被系统终止。请关掉其它 App、少开大会话后重试。"
            t.contains("proot error") ->
                "Linux 兼容层启动失败，问题不在引擎本身。请把下面这一行原文发回。"
            else -> null
        }
    }
}
