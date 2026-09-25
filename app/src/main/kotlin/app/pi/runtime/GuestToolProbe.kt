package app.pi.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Do `rg` and `fd` actually run?" — answered by **really invoking them**, not by
 * looking for a file.
 *
 * ## Why existence is the wrong question
 *
 * `/usr/local/bin/{rg,fd}` is a symlink into the guest's `/root/.pi/agent/bin/<tool>`,
 * and which host directory that guest path lands in depends on who launches proot
 * (see [PiPaths.agentBinDir]). A **dangling** link is indistinguishable from "this
 * tool was never installed" to every caller — pi's `find` tool and the `@` mention
 * completion simply produce nothing, with no error anywhere. `docs/known-gaps.md`
 * §K2 records that this really happened.
 *
 * So this probe runs a **real invocation** and checks three things:
 *
 *  1. `rg --version` / `fd --version` exit 0 **and** print something — the binary
 *     can be executed at all. A dangling symlink fails right here (exit 127,
 *     "No such file or directory");
 *  2. then a **real search** of a guest file for each tool, again requiring exit 0
 *     and non-empty output;
 *  3. anything short of that is reported with the exit code and the tool's own
 *     first output line — never silently downgraded to "no results".
 *
 * Step 2 exists for a different risk than step 1: when path translation is bypassed
 * (`proroot`'s documented raw/inline-syscall gap, `docs/proroot-research.md`
 * §5.P0-2), the binary executes and `--version` looks perfect while a *guest path*
 * comes back `ENOENT` or resolves to a **host** file. Only an actual search sees
 * that.
 *
 * ## Which copy it resolves
 *
 * There is only one. `/root/.pi/agent` **is** `PiPaths.agentDir` since 2026-09-23 — a
 * rootfs path — so this probe (which passes no `extraBinds`) and every launch path that
 * passes some resolve to the same directory by construction. `AgentToolPathsCheck` pins
 * that at build time, and `RuntimeProvisioner.ensureToolsVisible()` re-publishes the tools
 * on every boot.
 *
 * It used to matter: the probe saw the rootfs copy while the engine saw a *bound* one, and
 * a second spelling of the guest path `/root/.pi/agent` was how the original dangling link
 * happened.
 *
 * ## Android-free
 *
 * Only `java.io`, `java.util.concurrent` and the stdlib — so
 * `tools/run-app-pure-checks.sh` can compile [guestCommand] and [parse] into a
 * bare-JVM harness. Only [run] starts a process.
 */
object GuestToolProbe {

    /** The two tools pi's `find`/`grep` tools and the `@` completion depend on. */
    val TOOLS: List<String> = listOf("rg", "fd")

    /** Guest file the rg probe reads. Present in every Ubuntu base. */
    private const val SEARCH_FILE = "/etc/passwd"

    /** Guest directory the fd probe walks. */
    private const val SEARCH_DIR = "/etc"

    /**
     * What the searches must match. Chosen so a healthy guest produces exactly one
     * obvious hit; a miss reports the exit code rather than passing quietly.
     */
    private const val RG_PATTERN = "^root"
    private const val FD_PATTERN = "^passwd$"

    /** Every probe line starts with this, so the parser can ignore shell noise. */
    const val MARKER: String = "PI-TOOL"

    /** Field separator inside a marker line. */
    const val SEPARATOR: String = "\t"

    /** One tool's verdict, with the raw evidence that produced it. */
    data class ToolResult(
        val tool: String,
        /** True only when **both** the version call and the search call succeeded. */
        val ok: Boolean,
        /** First line of `--version` output, or null when it produced none. */
        val version: String?,
        val versionExit: Int?,
        val searchExit: Int?,
        val searchOutput: String?,
        /** Why this is not ok; null exactly when [ok] is true. */
        val reason: String?,
    ) {
        /**
         * One line for the diagnostic report. A success line only ever claims what
         * was observed (exit codes and real output), and a failure line carries the
         * tool's own message.
         */
        fun describe(): String = if (ok) {
            "✓ $tool：${version.orEmpty()}（真实搜索退出码 0，输出非空）"
        } else {
            "✗ $tool：$reason"
        }
    }

    /** The whole probe: one verdict per tool, or the reason no verdict exists. */
    data class Report(
        val results: List<ToolResult>,
        /**
         * Non-null when the probe process itself never produced a verdict (runtime
         * not unpacked, proot refused to start, timeout). Separate from a per-tool
         * failure on purpose: "the probe could not run" and "the tool is broken"
         * have different fixes.
         */
        val launchError: String? = null,
        /** The guest command's exit status, for the record. */
        val exitCode: Int? = null,
    ) {
        val ok: Boolean get() = launchError == null && results.isNotEmpty() && results.all { it.ok }

        /** The report's lines, in tool order, or the one line saying why there are none. */
        fun describe(): List<String> =
            launchError?.let { listOf("工具链探针没能运行：$it") } ?: results.map { it.describe() }
    }

    /** Wall-clock bound for the whole probe (four small guest commands). */
    const val TIMEOUT_MS: Long = 20_000L

    /**
     * The guest script.
     *
     * Plain POSIX-shaped shell that the selected runtime's builder hands to
     * `/bin/bash -c` (`GuestCommandLine`).
     * Each tool emits two marker lines — `version` then `search` — and the parser
     * treats a missing line as a failure, so a script that dies half-way cannot be
     * mistaken for a pass.
     */
    fun guestCommand(): String {
        val tab = SEPARATOR
        return listOf(
            "# rg/fd 真实调用探针。每条标记行：<MARKER> <phase> <tool> <rc> <detail>",
            "for tool in ${TOOLS.joinToString(" ")}; do",
            "  out=\$(\"\$tool\" --version 2>&1); rc=\$?",
            "  first=\$(printf '%s' \"\$out\" | head -n 1)",
            "  echo \"$MARKER${tab}version${tab}\$tool${tab}\$rc${tab}\$first\"",
            "done",
            "out=\$(rg -N '$RG_PATTERN' $SEARCH_FILE 2>&1); rc=\$?",
            "first=\$(printf '%s' \"\$out\" | head -n 1)",
            "echo \"$MARKER${tab}search${tab}rg${tab}\$rc${tab}\$first\"",
            "out=\$(fd -H '$FD_PATTERN' $SEARCH_DIR 2>&1); rc=\$?",
            "first=\$(printf '%s' \"\$out\" | head -n 1)",
            "echo \"$MARKER${tab}search${tab}fd${tab}\$rc${tab}\$first\"",
        ).joinToString("\n")
    }

    /**
     * Turn the guest's stdout into verdicts. Pure, so the bare-JVM harness can feed
     * it real and hostile outputs.
     *
     * A `--version` success requires exit 0 **and** a non-empty first line; a search
     * success requires the same. The detail is only the tool's **first** line, which
     * is what makes a single-line marker possible — enough to identify a version
     * string or a first hit, and enough to tell "empty output" from "output".
     */
    fun parse(output: String, exitCode: Int? = null): Report {
        val versions = LinkedHashMap<String, Pair<Int?, String>>()
        val searches = LinkedHashMap<String, Pair<Int?, String>>()
        val prefix = "$MARKER$SEPARATOR"
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (!line.startsWith(prefix)) return@forEach
            val fields = line.split(SEPARATOR)
            // MARKER, phase, tool, rc, detail... — a short line is not a probe result.
            if (fields.size < 5) return@forEach
            val observed = fields[3].toIntOrNull() to fields.drop(4).joinToString(SEPARATOR).trim()
            when (fields[1]) {
                "version" -> versions[fields[2]] = observed
                "search" -> searches[fields[2]] = observed
            }
        }
        val results = TOOLS.map { tool ->
            val version = versions[tool]
            val search = searches[tool]
            when {
                version == null -> failure(
                    tool,
                    version = null,
                    versionExit = null,
                    searchExit = search?.first,
                    searchOutput = search?.second,
                    reason = "没有输出 `$tool --version` 的结果行（探针没跑到，或这个命令没被执行）",
                )

                version.first != 0 -> failure(
                    tool,
                    version = version.second.ifBlank { null },
                    versionExit = version.first,
                    searchExit = search?.first,
                    searchOutput = search?.second,
                    reason = "`$tool --version` 退出码 ${version.first}" + suffix(version.second),
                )

                version.second.isBlank() -> failure(
                    tool,
                    version = null,
                    versionExit = 0,
                    searchExit = search?.first,
                    searchOutput = search?.second,
                    reason = "`$tool --version` 退出码 0 但没有任何输出",
                )

                search == null -> failure(
                    tool,
                    version = version.second,
                    versionExit = 0,
                    searchExit = null,
                    searchOutput = null,
                    reason = "没有输出 $tool 的搜索探针结果行",
                )

                search.first != 0 -> failure(
                    tool,
                    version = version.second,
                    versionExit = 0,
                    searchExit = search.first,
                    searchOutput = search.second.ifBlank { null },
                    reason = "$tool 真实搜索退出码 ${search.first}" + suffix(search.second),
                )

                search.second.isBlank() -> failure(
                    tool,
                    version = version.second,
                    versionExit = 0,
                    searchExit = 0,
                    searchOutput = null,
                    reason = "$tool 真实搜索退出码 0 但没有任何输出",
                )

                else -> ToolResult(
                    tool = tool,
                    ok = true,
                    version = version.second,
                    versionExit = 0,
                    searchExit = 0,
                    searchOutput = search.second,
                    reason = null,
                )
            }
        }
        return Report(results = results, launchError = null, exitCode = exitCode)
    }

    /**
     * Run the probe inside the guest and return what actually happened.
     *
     * Blocking; callers run it off the main thread. Every failure mode produces a
     * [Report] with a stated reason — this function does not throw for a broken
     * runtime, because "the report says the tool chain is broken" is the point.
     *
     * @param engine which runtime the tools are invoked through. It is a parameter
     *        rather than a constant because this **same** probe is what decides
     *        whether the opt-in runtime may be used: `docs/proroot-research.md`
     *        §10.5 and §5.P0-2 make `rg`/`fd` the decisive test, since both are
     *        musl-static Rust binaries whose inline `svc` calls are reachable only
     *        through proroot's `--static-loader` path. Defaults to proot, which is
     *        the question every caller asked before the second runtime existed.
     */
    fun run(
        paths: PiPaths,
        storage: File?,
        engine: GuestEngine = GuestEngine.Proot,
        timeoutMs: Long = TIMEOUT_MS,
    ): Report {
        val launcher = when (engine) {
            GuestEngine.Proot -> paths.prootBinary()
            GuestEngine.Proroot -> paths.prorootLauncher()
            GuestEngine.Bxroot -> paths.bxrootLauncher()
        }
        if (!paths.rootfs.isDirectory || !launcher.exists()) {
            return Report(
                emptyList(),
                launchError = "运行时尚未解包（缺少 rootfs 或 ${launcher.name}）",
            )
        }
        val argv = GuestCommandLine.build(paths, engine, guestCommand(), cwd = "/", storage = storage)
        val env = GuestCommandLine.environment(paths, engine)
        val process = try {
            ProcessBuilder(argv)
                .directory(paths.runtime)
                // stderr merged: a missing tool says why there, and that text is the
                // detail the report needs. One stream also rules out a two-pipe
                // deadlock, which a concurrent-reader-free caller cannot fix.
                .redirectErrorStream(true)
                .also { it.environment().putAll(env) }
                .start()
        } catch (error: Throwable) {
            return Report(
                emptyList(),
                launchError = "无法启动探针进程：${error::class.java.simpleName}: ${error.message}",
            )
        }

        // Wait before reading. The output is a handful of short lines, far below the
        // pipe buffer, so a process that is going to finish does so without a reader;
        // and a process that hangs is bounded here instead of in `readText()`.
        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            val partial = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            val tail = partial.trim().takeLast(200)
            return Report(
                emptyList(),
                launchError = "探针 ${timeoutMs / 1000} 秒没有返回（proot 或 guest 卡住）" +
                    if (tail.isBlank()) "" else "；已收到的输出：$tail",
            )
        }
        val exitCode = runCatching { process.exitValue() }.getOrNull()
        val text = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            return Report(
                emptyList(),
                launchError = "读不到探针输出：${error::class.java.simpleName}: ${error.message}",
                exitCode = exitCode,
            )
        }
        return parse(text, exitCode)
    }

    private fun failure(
        tool: String,
        version: String?,
        versionExit: Int?,
        searchExit: Int?,
        searchOutput: String?,
        reason: String,
    ): ToolResult = ToolResult(
        tool = tool,
        ok = false,
        version = version,
        versionExit = versionExit,
        searchExit = searchExit,
        searchOutput = searchOutput,
        reason = reason,
    )

    /** `：<first line>` when the tool said something, nothing when it did not. */
    private fun suffix(detail: String): String =
        if (detail.isBlank()) "" else "：${detail.lineSequence().first()}"
}
