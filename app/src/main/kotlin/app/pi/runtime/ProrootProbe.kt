package app.pi.runtime

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The proroot **probe gate**: the one thing that decides whether proroot may be used
 * at all, and the cache that keeps it from being re-run on every launch.
 *
 * ## Why a gate exists
 *
 * proroot is closed source and cannot be audited, and its failure mode is silent
 * rather than loud (`docs/proroot-research.md` §5.P0-2). "The files are present" says
 * nothing about whether path translation works on *this* device with *these* guest
 * binaries, so the switch does not mean "prefer proroot": it means "use proroot **if a
 * real invocation proves it works here**" (§6.2 step 5, and §9.6 step 1, which is the
 * research's own recommended order — probes first, launch path second).
 *
 * ## The three stages, and why each
 *
 * 1. [ProrootRawProbe] — a syscall issued outside libc. It covers the layer proroot
 *    reaches by *patching binaries* rather than by intercepting calls, and it is where
 *    a silent host-file read would show up.
 * 2. [GuestToolProbe] run **through proroot** — the real `rg` and `fd` binaries,
 *    invoked for real, searching real guest paths. They are **musl-static** Rust
 *    binaries (`tools/fetch-runtime.mjs:190-196`), so they do not go through the
 *    dynamic linker at all, and their `--version` can look perfect while a guest path
 *    comes back empty. pi's `find`/`grep` tools and the `@` completion depend on
 *    them, so a failure here is a capability that disappears with no error anywhere
 *    (§10.5).
 * 3. [ProrootExecProbe] — `/usr/bin/env true` and **`/opt/node/bin/node --version`**, i.e.
 *    dynamically linked glibc ELFs with a `PT_INTERP`, run for real. Added 2026-09-19,
 *    after stages 1 and 2 passed on a device where every engine launch died with exit code
 *    126: the gate had no stage that spoke about the engine's own binary, so its PASS was
 *    not an answer about the process the app cannot do without.
 *
 * All three must pass. A leak fails the gate outright.
 *
 * "All three" is exactly true in the seccomp档 this app ships
 * ([RuntimeChoice.PROROOT_SECCOMP]): the raw probe has to show translation, the tool probe
 * has to show a real `rg`/`fd` invocation working, and the exec probe has to show the
 * engine's own interpreter running. The rule itself lives in [RuntimeChoice.probeGate] and
 * takes the档 as an input, so a档 that does not promise raw translation would refuse
 * proroot on a leak, on broken tools or on an engine binary that cannot be exec'd but not
 * on "untranslated" — reported either way, never hidden.
 *
 * ## The cache
 *
 * The verdict is keyed by the unpacked runtime revision, the digest of the five proroot
 * binaries **and the seccomp档** ([ProrootProbeCache.key]), and stored in the volatile
 * runtime tree ([ProrootProbeCache], [PiPaths.prorootProbeCache]). It is therefore
 * re-earned whenever any of the three things it is about changes (and by
 * [ProrootProbeCache.VERSION] whenever the *set of stages* changes), and it cannot outlive
 * the tree it describes. A **failed** verdict is cached too: the gate is a measurement,
 * not a retry loop, and re-running a probe that just failed on every launch would
 * spend seconds to learn the same thing.
 *
 * Blocking. Every caller is already off the main thread (`PiEngineHost` boots on
 * `Dispatchers.IO`, `PtyLauncher.prepare` runs its own guest probe, `GuestCommand`
 * is a batch path), and the work is bounded by [RAW_TIMEOUT_MS] +
 * [GuestToolProbe.TIMEOUT_MS] + [EXEC_TIMEOUT_MS].
 *
 * ## What else this file owns: the failure autopsy
 *
 * [autopsy] is not part of the gate. It is the **failure-side** counterpart of the third
 * stage, run by `PiEngineHost` only after a proroot engine exited with
 * [ProrootExecProbe.isLaunchFailure] — the answer to "126 says nothing; make the runtime
 * say which binary it could not run".
 */
object ProrootProbe {

    /** Bound for the raw-syscall probe: one interpreter start, no network. */
    const val RAW_TIMEOUT_MS: Long = 20_000L

    /**
     * Bound for the dynamic-binary stage: one bash start plus two small execs
     * (`/usr/bin/env true`, `/opt/node/bin/node --version`). Node's own startup is a few
     * hundred ms even under `proroot`; the bound is for a runtime that hangs, not for one
     * that is slow (`ProrootExecProbe`).
     */
    const val EXEC_TIMEOUT_MS: Long = 20_000L

    /**
     * Bound for the engine-failure autopsy ([autopsy]). Deliberately the same order as the
     * stage it re-runs: the autopsy is what the *failure* state keeps, and a diagnostic that
     * can hang is worse than no diagnostic.
     */
    const val AUTOPSY_TIMEOUT_MS: Long = 20_000L

    /** How much of the autopsy run's output is read before the pipe is abandoned. */
    const val AUTOPSY_MAX_OUTPUT_CHARS: Int = 16_000

    /**
     * How much of a probe run's output is read. Generous: the raw probe's script prints a
     * handful of lines, and this bound exists so a guest program that streams megabytes
     * cannot grow the app's heap — every caller reads to EOF otherwise.
     */
    const val MAX_PROBE_OUTPUT_CHARS: Int = 256_000

    /** A gate verdict, cached or freshly measured. */
    data class Verdict(
        val passed: Boolean,
        /** [ProrootProbeCache.key] this verdict is valid for. */
        val key: String,
        /** The evidence lines, in the order they should be read. */
        val detail: List<String>,
        /** True when it came off disk rather than from a run. */
        val cached: Boolean,
        val raw: ProrootRawProbe.Report? = null,
        val tools: GuestToolProbe.Report? = null,
        val exec: ProrootExecProbe.Report? = null,
    )

    /**
     * The digest that identifies the proroot binaries an app update may have replaced
     * without touching the runtime tree.
     *
     * File names are hashed in `RuntimeChoice.REQUIRED_FILES` order alongside their
     * bytes, so a rename and a content swap both change the digest, and a missing file
     * cannot collide with an empty one.
     */
    /**
     * Cache of the last digest, keyed by the directory it describes.
     *
     * Why caching is sound rather than an optimisation with a stale-data risk: the five
     * binaries live in `nativeLibraryDir`, which is a property of the **installed APK**.
     * Nothing in the app can write there, and an app update kills this process, so
     * within one process those bytes cannot change — while `plan()` is on the `@`
     * mention-scan path, i.e. it can run several times per turn, and hashing ~600 KB of
     * `.so` each time is pure waste. Keyed by the directory path so two different
     * installs (or a test fixture) cannot share an entry.
     */
    @Volatile
    private var digestCache: Pair<String, String>? = null

    fun digestOf(paths: PiPaths): String {
        val key = paths.nativeLib.path
        digestCache?.let { if (it.first == key) return it.second }
        val digest = computeDigest(paths)
        digestCache = key to digest
        return digest
    }

    private fun computeDigest(paths: PiPaths): String {
        val md = MessageDigest.getInstance("SHA-256")
        RuntimeChoice.REQUIRED_FILES.forEachIndexed { index, name ->
            md.update(name.toByteArray())
            md.update(0)
            val file = paths.prorootComponents()[index]
            if (file.isFile) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        md.update(buffer, 0, read)
                    }
                }
            } else {
                md.update("<missing>".toByteArray())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The cache key for a revision, a digest and the seccomp档 in effect. */
    fun key(
        revision: String,
        digest: String,
        mode: ProrootSeccomp = RuntimeChoice.PROROOT_SECCOMP,
    ): String = ProrootProbeCache.key(revision, digest, mode.tag)

    /** The cached verdict for this revision + digest, or null when there is none. */
    fun cached(paths: PiPaths, revision: String, digest: String): Verdict? {
        val key = key(revision, digest)
        val text = runCatching { paths.prorootProbeCache().readText() }.getOrNull()
        val parsed = ProrootProbeCache.parse(text, key) ?: return null
        return Verdict(passed = parsed.passed, key = key, detail = parsed.detail, cached = true)
    }

    /**
     * Run all **three** stages and write the verdict. Blocking; see the class KDoc.
     *
     * ## Why the tool probe runs even when the raw one did not pass
     *
     * It used to run only when the raw half had produced an *interpreter* line — i.e. only
     * when the absence of a result was explainable by a missing `perl`. Any other raw
     * failure short-circuited it, which is how a report came to say
     * `rg: 没有输出 rg --version 的结果行（探针没跑到，或这个命令没被执行）` about a probe
     * that had never been started. The three stages answer different questions — "is an
     * inline `svc` translated", "do the real tools work", and "can the engine's own binary
     * be executed" — and every one of them has to be a real invocation: a probe that was
     * never run is not a pass, and a probe that was skipped must not be reported as one
     * either.
     *
     * ## Why the third stage is here at all (2026-09-19)
     *
     * Because the first two passed on a device where the engine could not start at all:
     * `guestpath=translated` and `✓ rg: ripgrep 15.2.0` were both true while every engine
     * launch exited **126**. Nothing in the two stages spoke about `/opt/node/bin/node`,
     * so the gate's PASS was an answer about a runtime that had never been asked to run the
     * one process the app cannot do without (`ProrootExecProbe`). It runs **last** so the
     * earlier stages' evidence keeps its position in the report, and its verdict is a gate
     * input like the others ([RuntimeChoice.probeGate]).
     *
     * The planted probe file is deleted on the way out in every path — it is the
     * app's own file in the app's own tmp directory.
     */
    fun run(paths: PiPaths, storage: File?, revision: String, digest: String): Verdict {
        val mode = RuntimeChoice.PROROOT_SECCOMP
        val key = key(revision, digest, mode)
        val plantedHost = File(paths.tmp, ProrootRawProbe.PLANTED_NAME)
        val token = UUID.randomUUID().toString()

        val rawOutput = runCatching {
            runGuest(
                paths = paths,
                engine = GuestEngine.Proroot,
                guestCommand = ProrootRawProbe.guestCommand(paths.tmp.path, token),
                storage = storage,
                timeoutMs = RAW_TIMEOUT_MS,
            )
        }.getOrElse { error -> "proroot 启动探针失败：${error::class.java.simpleName}: ${error.message}" }
        runCatching { plantedHost.delete() }

        val raw = ProrootRawProbe.parse(rawOutput)
        val detail = mutableListOf<String>()
        detail += raw.describe()

        val tools = runCatching { GuestToolProbe.run(paths, storage, GuestEngine.Proroot) }
            .getOrElse { error ->
                GuestToolProbe.Report(
                    emptyList(),
                    launchError = "proroot 工具链探针抛了异常：${error::class.java.simpleName}: ${error.message}",
                )
            }
        detail += tools.describe().map { "  $it" }

        val exec = runExecStage(paths, storage, EXEC_TIMEOUT_MS)
        detail += exec.describe().map { "  $it" }

        // The gate itself: `RuntimeChoice.probeGate` owns the rule, pure, so the harness
        // executes production's own combination instead of a copy. A leak refuses proroot in
        // every档; the tools and the engine-class binary must really have run in every档; raw
        // translation is required only where it was promised (`mode`).
        val passed = RuntimeChoice.probeGate(
            mode = mode,
            rawVetoed = raw.leaked,
            rawTranslated = raw.translated,
            toolsOk = tools.ok,
            execOk = exec.ok,
        )
        val verdict = Verdict(
            passed = passed,
            key = key,
            detail = detail,
            cached = false,
            raw = raw,
            tools = tools,
            exec = exec,
        )
        // Only a run writes the cache, and it writes failures too: the next launch
        // must not pay for the same measurement.
        runCatching {
            paths.runtime.mkdirs()
            paths.prorootProbeCache().writeText(ProrootProbeCache.render(key, passed, detail))
        }
        // The planted file lives in the guest's /tmp; if translation did not work it
        // may not exist at all, and deleting a file that is not there is fine. This
        // is the app's own file in the app's own tmp directory (`PiPaths.tmp`).
        runCatching { plantedHost.delete() }
        return verdict
    }

    /**
     * Run the **dynamic-binary** stage once through proroot and parse its verdict.
     *
     * A launch that throws is turned into a synthetic `[proroot] …` sentence rather than
     * being swallowed: `ProrootExecProbe.parse` keeps launcher lines as evidence, so the
     * resulting report says "the process could not be started, and this is the error"
     * instead of coming back as a bare set of missing rows.
     *
     * Blocking and never called on a happy path: [run]'s third stage and [autopsy] are the
     * only callers.
     */
    private fun runExecStage(paths: PiPaths, storage: File?, timeoutMs: Long): ProrootExecProbe.Report {
        val output = runCatching {
            runGuest(
                paths = paths,
                engine = GuestEngine.Proroot,
                guestCommand = ProrootExecProbe.guestCommand(),
                storage = storage,
                timeoutMs = timeoutMs,
            )
        }.getOrElse { error ->
            "${ProrootRawProbe.LAUNCHER_PREFIX} 启动探针失败：${error::class.java.simpleName}: ${error.message}"
        }
        return ProrootExecProbe.parse(output)
    }

    /**
     * The **engine failure autopsy**: re-run the dynamic-binary stage under the same
     * proroot launch shape and render the bounded evidence block
     * ([ProrootExecProbe.autopsyLines]).
     *
     * ## Rules this obeys, each for a reason
     *
     *  - **Only after a failure.** [ProrootExecProbe.isLaunchFailure] is the caller's gate,
     *    and this function is not on any launch path: a normal start pays nothing.
     *  - **Bounded.** [AUTOPSY_TIMEOUT_MS] and [AUTOPSY_MAX_OUTPUT_CHARS]; the rendered
     *    block is capped by [ProrootExecProbe.MAX_AUTOPSY_LINES] and one line is capped by
     *    [ProrootExecProbe.MAX_DETAIL_CHARS].
     *  - **No environment dump.** Only this stage's own marker lines and the lines proroot's
     *    launcher writes are ever quoted ([ProrootExecProbe.launcherLinesFrom]); the guest
     *    command's other output and the caller's whole stderr never travel.
     *  - **Never silent.** If the autopsy run itself cannot start, the block says that
     *    instead of coming back empty (`probeNote`).
     *  - **The engine's own launch shape** (`cwd`, `extraBinds`). The gate's third stage runs
     *    with the probe's shape (`-w /`, no extra binds), and the engine's shape is exactly
     *    what the gate does *not* cover — a guest working directory that does not exist, a
     *    bind whose host path is missing, a bind value the launcher refuses. The autopsy is
     *    the one place that can repeat the engine's own shape, because it is not cached and
     *    has no verdict to keep honest. Both are named in the rendered header, so nobody has
     *    to guess which shape produced the evidence.
     *
     * @param exitCode the engine's exit code, as `PiEngineSession.lastExitCode` recorded it.
     * @param launcherSource free text that may contain proroot's own lines — the engine's
     *        captured stderr is the caller's source. Only `[proroot] …` lines are kept.
     * @param cwd the **guest** working directory the engine was launched with; null for the
     *        probe's own `/`.
     * @param extraBinds the engine's own extra binds (`host to guest`), so a bind problem is
     *        reproduced rather than missed. Defaults to none, i.e. the probe's shape.
     * @return the lines to record in the failure state and the diagnostic report.
     */
    fun autopsy(
        paths: PiPaths,
        storage: File?,
        exitCode: Int?,
        launcherSource: String?,
        cwd: String? = null,
        extraBinds: List<Pair<String, String>> = emptyList(),
        timeoutMs: Long = AUTOPSY_TIMEOUT_MS,
    ): List<String> {
        val fromCaller = ProrootExecProbe.launcherLinesFrom(launcherSource)
        val output = runCatching {
            runGuest(
                paths = paths,
                engine = GuestEngine.Proroot,
                guestCommand = ProrootExecProbe.guestCommand(),
                storage = storage,
                timeoutMs = timeoutMs,
                maxChars = AUTOPSY_MAX_OUTPUT_CHARS,
                cwd = cwd ?: "/",
                extraBinds = extraBinds,
            )
        }.getOrElse { error ->
            return ProrootExecProbe.autopsyLines(
                exitCode = exitCode,
                report = null,
                launcherLines = fromCaller,
                probeNote = "${error::class.java.simpleName}: ${error.message}",
                cwd = cwd,
            )
        }
        val report = ProrootExecProbe.parse(output)
        return ProrootExecProbe.autopsyLines(
            exitCode = exitCode,
            report = report,
            // The launcher's live lines come first: they are what proroot said about *this*
            // attempt, and the autopsy run's own lines are about the re-run.
            launcherLines = (fromCaller + ProrootExecProbe.launcherLinesFrom(output)).distinct(),
            cwd = cwd,
        )
    }

    /**
     * One blocking guest command through `engine`, with stderr merged, a hard timeout, and
     * a **bounded** read.
     *
     * The bound is not an optimisation: `readText()` on a guest program that streams output
     * grows the app's heap, and this helper is used by a probe whose subject is a binary
     * that may be misbehaving. Reading stops at [maxChars]; the truncation is visible in
     * the returned text so no caller can mistake a clipped run for a complete one.
     */
    private fun runGuest(
        paths: PiPaths,
        engine: GuestEngine,
        guestCommand: String,
        storage: File?,
        timeoutMs: Long,
        maxChars: Int = MAX_PROBE_OUTPUT_CHARS,
        cwd: String = "/",
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): String {
        val argv = GuestCommandLine.build(paths, engine, guestCommand, cwd = cwd, storage = storage, extraBinds = extraBinds)
        val env = GuestCommandLine.environment(paths, engine)
        val process = ProcessBuilder(argv)
            .directory(paths.runtime)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()
        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            val partial = readBounded(process, maxChars)
            // The timeout is reported in the raw probe's marker vocabulary on purpose:
            // `ProrootRawProbe.parse` already knows how to render it, `ProrootExecProbe.parse`
            // reads the same line, and every stage therefore reports "the runtime did not
            // return" with one spelling (`ProrootRawProbe.TIMEOUT_MARKER`).
            return partial + "\n" + ProrootRawProbe.TIMEOUT_MARKER + "\t" +
                "proroot 探针 ${timeoutMs / 1000} 秒没有返回"
        }
        return readBounded(process, maxChars)
    }

    /** Read at most [maxChars] of the process's merged output, then stop reading. */
    private fun readBounded(process: Process, maxChars: Int): String = runCatching {
        val buffer = CharArray(8 * 1024)
        val out = StringBuilder()
        process.inputStream.reader().use { reader ->
            while (out.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length))
                if (read < 0) break
                out.appendRange(buffer, 0, read)
            }
        }
        if (out.length >= maxChars) out.append("\n（输出超过 $maxChars 字符，已截断）")
        out.toString()
    }.getOrDefault("")
}
