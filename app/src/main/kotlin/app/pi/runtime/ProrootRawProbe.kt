package app.pi.runtime

/*
 * This file holds the probe gate's two **guest-side measurements**. They are together
 * because they are the same kind of thing — a shell script built here and a pure parser
 * that turns the run's marker lines into verdicts — and because the `proroot` harness's
 * source list (`tools/run-app-pure-checks.sh`) compiles *files*:
 *
 *  1. `ProrootRawProbe` — does a syscall issued outside libc's wrappers still see the
 *     guest's filesystem?
 *  2. `ProrootExecProbe` — **can the engine's own class of binary be executed at all?**
 *     A dynamically linked glibc ELF with a `PT_INTERP`, which is what Node is. Measured
 *     2026-09-19: the gate passed on `perl` (dynamic) and `rg`/`fd` (static musl) while
 *     the engine still died with exit code 126, so the gate had no stage that spoke about
 *     the engine's own binary. `ProrootExecProbe` is that stage; its KDoc carries the
 *     evidence — **and the resolution**: that 126 was the launcher failing to `chdir` the
 *     engine's `-w`, not Node being unexecutable. The stage stays as a guard against the
 *     next defect of that class.
 *
 * Neither object touches `java.io`: a built string in, a parsed verdict out.
 */

/**
 * The **raw-syscall** half of the proroot probe gate: does a syscall issued
 * outside libc's wrappers still see the guest's filesystem?
 *
 * ## What it is protecting against
 *
 * proroot translates paths by intercepting *libc calls* in-process and by patching
 * the inline `svc` instructions it finds in a main executable. Anything that
 * reaches the kernel by another route is not translated, and the failure is
 * **silent**: `docs/proroot-research.md` §5.P0-2 measured
 * `openat("/etc/passwd")` issued as a raw syscall returning a valid fd for the
 * **host's** `/etc/passwd`, while the same path through libc returned the guest's.
 * No error is raised anywhere — a program simply reads somebody else's file. That
 * is the one outcome this app treats as disqualifying.
 *
 * ## The three measurements
 *
 * 1. **`guestpath`** — a file this probe plants *inside the guest* (`/dev/shm/…`) and
 *    then reads back with a raw `openat`. Translated → the marker comes back.
 *    Untranslated → `ENOENT`, because no such file exists at that path in the host
 *    namespace (guest `/dev/shm` is a host directory that is only reachable that way).
 *    This is the positive test for "an inline `svc` call sees the guest filesystem".
 *
 *    (`/dev/shm` rather than `/tmp` since 2026-09-23: `/tmp` is no longer a bind, so it
 *    can no longer distinguish a translated path from an untranslated one. `/dev/shm` is
 *    still bound from an app-private directory, which is exactly the shape this measures.)
 * 2. **`hostpath`** — the same file addressed by its **host** absolute path, which
 *    the app embeds into the script. Reachable means the raw layer is addressing
 *    host paths; informational only, since that is the documented gap rather than a
 *    leak (the file is ours).
 * 3. **`passwd`** — a path that exists on **both** sides with different content.
 *    Raw content equal to the libc-read guest content is a translation; raw content
 *    that differs is a **leak**, and the gate refuses proroot for it.
 *
 * ## The gate rule
 *
 * `passed = interpreter available && no leak && (translated || !REQUIRE_TRANSLATION)`
 *
 * [REQUIRE_TRANSLATION] is `true`, which is the strict reading of the requirement
 * this was built against: an inline `svc` call **must** see the guest filesystem, so
 * a run where it cannot is a run where proroot is not used. Production reaches that
 * rule through [RuntimeChoice.probeGate], which is the same rule with the seccomp档
 * as an explicit input — and the档 this app ships ([ProrootSeccomp.Seccomp]) is the
 * strict one, so the constant and the gate agree.
 *
 * ## When the launcher never got as far as the guest (the defect this closes)
 *
 * The probe's evidence used to be **only** the marker lines. That made a run in which
 * the launcher exited during argument parsing — printing
 * `[proroot] bad bind format (expected host:guest): /dev` to stderr and nothing to
 * stdout — indistinguishable from a run in which translation did not happen: both
 * parsed to an empty [Report], and an empty report's `failure` is the untranslated
 * sentence. The gate then refused proroot for a reason that was never measured (the
 * settings row said "raw/inline svc 调用没有被翻译" about a process that never reached
 * translation), and the real cause was invisible everywhere.
 *
 * So [parse] now keeps the run's **own words**: up to [MAX_LAUNCH_LINES] lines that
 * are not marker lines, bounded by [MAX_LAUNCH_CHARS], in [Report.launchLines]. When a
 * run produced no phase and no interpreter line and at least one of those lines is the
 * launcher's (`[proroot] …`), that is a **launch failure**, not a translation verdict:
 * it becomes its own phase (`launcher=failed（<the launcher's sentence>）`) and its own
 * header ([LAUNCH_FAILURE_HEADER]), so the failure sentence quotes proroot instead of
 * guessing about it. Everything else keeps the readings above.
 *
 * ## Why perl and not python3 (`docs/proroot-research.md` §6.5 ②)
 *
 * That section's probe uses `python3` + `ctypes`. `python3` is **not** in the
 * shipped payload: `tools/fetch-runtime.mjs`'s payload list has no python artefact,
 * and the pinned ubuntu-base's `minbase` set does not include one either. `perl-base`
 * is essential and always present, and Perl's `syscall` builtin calls the same libc
 * `syscall()` entry point `ctypes` does — so it exercises the same untranslated path,
 * with one fewer precondition. The interpreter check is explicit (`interpreter`
 * phase) rather than assumed: a missing Perl reads as "the probe could not run",
 * which refuses proroot rather than passing quietly.
 *
 * Android-free: it builds a shell string and parses marker lines, so the `proroot`
 * harness pins every verdict shape (translated / untranslated / leaked / mismatch /
 * missing interpreter / garbage) without a device.
 */
object ProrootRawProbe {

    /**
     * The three headers [Report.describe] writes, as constants rather than literals.
     *
     * They are read back by [ProrootProbeNarrative], which has to name the failing
     * stage of a **cached** verdict: the cache stores these human lines and nothing
     * else, so a header is the only place a stage name survives a process restart.
     * Sharing the constants is what keeps a reworded header from silently degrading
     * the settings row to "缓存里没有逐阶段记录"; both the `proroot` and the
     * `proroot-probe-detail` harnesses build lines through [Report.describe] and read
     * them back through the narrative, so the pairing is executed rather than asserted.
     */
    const val PASS_HEADER = "✓ raw syscall 探针通过"
    const val FAILURE_HEADER = "✗ raw syscall 探针未通过"
    const val NOT_RUN_HEADER = "raw syscall 探针未运行"

    /**
     * The header for a run the launcher killed before the guest existed. Its own header
     * and not [FAILURE_HEADER]: "the raw syscall was not translated" is a statement about
     * a measurement, and no measurement happened.
     */
    const val LAUNCH_FAILURE_HEADER = "✗ proroot 启动器未通过"

    /** `header：detail` — the separator all three headers above use. */
    const val HEADER_SEPARATOR = "："

    /** Every output line starts with this, so shell noise is ignored. */
    const val MARKER = "PI-PROROOT-RAW"

    /** Field separator inside a marker line. */
    const val SEPARATOR = "\t"

    /**
     * The line a run that did not return is reported with, built from the two constants
     * above so the producer (`ProrootProbe.runGuest`) and the readers
     * ([ProrootExecProbe.parse] and this probe's own [parse]) cannot spell it differently.
     * A timed-out run produced no measurement, and the dynamic-binary stage has to be able to
     * say *that* rather than "the launcher or bash did not start".
     */
    const val TIMEOUT_MARKER = "$MARKER$SEPARATOR" + "interpreter$SEPARATOR" + "timeout"

    /** The planted marker file's name; also what the host side deletes afterwards. */
    const val PLANTED_NAME = "pi-proroot-raw-probe"

    /** Its **guest** spelling — this is what the raw `openat` must resolve. */
    const val PLANTED_GUEST_PATH = "/dev/shm/$PLANTED_NAME"

    /**
     * Whether a run in which the raw syscall is *not* translated may pass. See the
     * class KDoc: `true` is the strict rule, and the constant exists so the rule is
     * named in one place and can be cited by the docs and the device checklist.
     */
    const val REQUIRE_TRANSLATION = true

    /**
     * The phase verdicts the guest script emits and this parser compares against, as
     * constants: [ProrootProbeNarrative] names the failing phase of a **cached** verdict
     * from these tokens, so a token spelled twice would let the two disagree.
     *
     * [LEAKED] and [MISMATCH] are the two spellings of `Report.leaked`;
     * [UNTRANSLATED] is the token behind `!Report.translated`, and [TRANSLATED] the one
     * behind it.
     */
    const val TRANSLATED = "translated"
    const val UNTRANSLATED = "untranslated"
    const val LEAKED = "leaked"
    const val MISMATCH = "mismatch"

    /**
     * The synthetic phase [parse] adds when the run never reached the guest: the launcher
     * exited first, and the sentence it printed is the whole evidence.
     */
    const val LAUNCHER_PHASE = "launcher"
    const val LAUNCHER_FAILED = "failed"

    /**
     * Every line proroot's launcher writes about itself starts with this — the one prefix
     * that lets [parse] tell "proroot said something and died" from "the guest ran and
     * said nothing".
     */
    const val LAUNCHER_PREFIX = "[proroot]"

    /** How many of the run's own (non-marker) lines are kept as evidence. */
    const val MAX_LAUNCH_LINES = 4

    /** Bound for one of those lines, so a pathological message cannot flood the cache. */
    const val MAX_LAUNCH_CHARS = 240

    /** One measured phase. */
    data class Phase(val verdict: String, val detail: String)

    /**
     * The parsed probe. Phase verdicts are kept as strings exactly as emitted, so the
     * diagnostic report can print the measurement rather than a summary of it.
     */
    data class Report(
        val phases: Map<String, Phase>,
        /** Non-null when the probe's own interpreter was not available. */
        val interpreter: String?,
        /**
         * What the run said that the probe's markers do not account for: the launcher's
         * own `[proroot] …` lines, and any other non-marker output. Bounded. Empty for a
         * healthy run — which is information too, because it is what makes the two
         * failure modes tellable apart.
         */
        val launchLines: List<String> = emptyList(),
    ) {
        private fun verdict(phase: String): String? = phases[phase]?.verdict

        /** The planted guest file came back through the raw syscall. */
        val guestPathTranslated: Boolean get() = verdict("guestpath") == TRANSLATED

        /** The raw syscall reached the file by its host absolute path. */
        val hostPathReachable: Boolean get() = verdict("hostpath") == "reachable"

        /** The raw syscall read a *different* file than libc did for the same path. */
        val leaked: Boolean
            get() = verdict("passwd") == LEAKED || verdict("guestpath") == MISMATCH

        /** proroot translated at least one raw path read. */
        val translated: Boolean
            get() = guestPathTranslated || verdict("passwd") == TRANSLATED

        /**
         * The launcher exited before the guest ran, and this is the sentence it printed.
         * Null when the guest was reached — including when it was reached and failed.
         */
        val launcherFailure: String?
            get() = phases[LAUNCHER_PHASE]
                ?.takeIf { it.verdict == LAUNCHER_FAILED }
                ?.detail

        /** True when there is nothing to measure because proroot refused to start. */
        val launcherFailed: Boolean get() = launcherFailure != null

        val passed: Boolean
            get() = interpreter == null && !leaked && (translated || !REQUIRE_TRANSLATION)

        /** Why it did not pass, in the user's terms; null exactly when [passed]. */
        val failure: String?
            get() = when {
                interpreter != null -> "探针没能运行：guest 里没有 $interpreter"
                leaked -> "raw syscall 读到了宿主文件（libc 与 raw 对同一路径给出不同内容）——" +
                    "这属于静默越界，proroot 不使用"

                // Before the translation branch, because nothing was translated *or not
                // translated*: the guest process never existed. Quoting the launcher is the
                // only honest answer, and it is the line the user can act on.
                launcherFailed -> "proroot 启动器在运行 guest 之前就退出了：" +
                    "$launcherFailure——本次启动没有产生任何 guest 输出。"

                !translated -> "raw/inline svc 调用没有被翻译（看不到 guest 文件系统）——" +
                    "proroot 不使用"

                else -> null
            }

        /**
         * The evidence lines, for the diagnostic report.
         *
         * A launch failure gets its own header ([LAUNCH_FAILURE_HEADER]) and the launcher's
         * sentence as its phase, so neither the row nor the report can render it as a
         * translation verdict.
         */
        fun describe(): List<String> {
            if (interpreter != null) {
                return listOf("$NOT_RUN_HEADER$HEADER_SEPARATOR" + "guest 里没有 $interpreter")
            }
            val header = when {
                launcherFailed -> "$LAUNCH_FAILURE_HEADER$HEADER_SEPARATOR$failure"
                passed -> PASS_HEADER
                else -> "$FAILURE_HEADER$HEADER_SEPARATOR$failure"
            }
            val order = listOf("guestpath", "hostpath", "passwd", LAUNCHER_PHASE)
            val lines = order.mapNotNull { phase ->
                phases[phase]?.let { "  $phase=${it.verdict}（${it.detail}）" }
            }
            // Anything else the run said, verbatim: a `[proroot]` line that did not stop
            // the guest (so it is not the phase above) is still the reason to look here.
            val extra = launchLines
                .filter { it != launcherFailure }
                .map { "  未识别输出：$it" }
            return listOf(header) + lines + extra
        }
    }

    /**
     * The guest script.
     *
     * @param hostShmPath the **host** path of [PLANTED_GUEST_PATH] — `PiPaths.shm` in the
     *        app, which is what `/dev/shm` is bound to. Passed in because the guest
     *        script cannot work out the host-side spelling of its own `/dev/shm`, and the
     *        third measurement is exactly about that spelling.
     * @param token the marker content; a random-ish value so a stale file from a
     *        previous run cannot satisfy the check.
     */
    fun guestCommand(hostShmPath: String, token: String): String {
        val tab = SEPARATOR
        // Single-quoted Perl, so nothing inside may contain a single quote. The host
        // path and the token are embedded as double-quoted Perl literals.
        val perl = buildString {
            appendLine("my \$planted = \"$PLANTED_GUEST_PATH\";")
            appendLine("my \$hostpath = \"$hostShmPath/$PLANTED_NAME\";")
            appendLine("sub raw { my (\$p) = @_; my \$fd = syscall(56, -100, \$p, 0, 0);")
            appendLine("  return (undef, 0 + \$!) if \$fd < 0;")
            appendLine("  my \$d = \"\"; my \$b = \"\\0\" x 65536;")
            appendLine("  while (1) { my \$n = syscall(63, \$fd, \$b, 65536); last if !defined(\$n) || \$n <= 0;")
            appendLine("    \$d .= substr(\$b, 0, \$n); }")
            appendLine("  syscall(57, \$fd); return (\$d, 0); }")
            appendLine("sub libc { my (\$p) = @_; return undef unless open(my \$fh, \"<\", \$p);")
            appendLine("  binmode(\$fh); local \$/; my \$d = <\$fh>; close(\$fh); return \$d; }")
            appendLine("sub emit { my (\$phase, \$verdict, \$detail) = @_;")
            appendLine("  print \"$MARKER$tab\$phase$tab\$verdict$tab\$detail\\n\"; }")
            appendLine("my \$want = libc(\$planted);")
            appendLine("if (!defined(\$want)) { emit(\"guestpath\", \"missing\", \"无法读取种植的探针文件\"); }")
            appendLine("else {")
            appendLine("  my (\$r, \$e) = raw(\$planted);")
            appendLine("  if (!defined(\$r)) { emit(\"guestpath\", \"untranslated\", \"errno=\$e\"); }")
            appendLine("  elsif (\$r eq \$want) { emit(\"guestpath\", \"$TRANSLATED\", \"len=\" . length(\$r)); }")
            appendLine("  else { emit(\"guestpath\", \"mismatch\", \"raw=\" . length(\$r) . \" libc=\" . length(\$want)); }")
            appendLine("  my (\$h, \$he) = raw(\$hostpath);")
            appendLine("  if (defined(\$h)) { emit(\"hostpath\", \"reachable\", \"len=\" . length(\$h)); }")
            appendLine("  else { emit(\"hostpath\", \"unreachable\", \"errno=\$he\"); }")
            appendLine("}")
            appendLine("my \$guest = libc(\"/etc/passwd\");")
            appendLine("my (\$pr, \$pe) = raw(\"/etc/passwd\");")
            appendLine("if (!defined(\$pr)) { emit(\"passwd\", \"unreadable\", \"errno=\$pe\"); }")
            appendLine("elsif (defined(\$guest) && \$pr eq \$guest) { emit(\"passwd\", \"$TRANSLATED\", \"len=\" . length(\$pr)); }")
            appendLine("elsif (defined(\$guest)) { emit(\"passwd\", \"leaked\", \"raw=\" . length(\$pr) . \" libc=\" . length(\$guest)); }")
            appendLine("else { emit(\"passwd\", \"guest-unreadable\", \"len=\" . length(\$pr)); }")
        }
        return listOf(
            "# proroot raw-syscall 探针（见 ProrootRawProbe 的 KDoc）",
            "printf '%s\\n' ${ShellQuote.quote(token)} > $PLANTED_GUEST_PATH 2>/dev/null",
            "if ! command -v perl >/dev/null 2>&1; then",
            "  echo \"$MARKER${tab}interpreter${tab}missing${tab}perl\"",
            "else",
            "perl -e ${ShellQuote.quote(perl)}",
            "fi",
        ).joinToString("\n")
    }

    /**
     * Turn the probe's stdout (with stderr merged) into a [Report]. Pure.
     *
     * A missing marker line is not a pass: an empty or truncated run parses to an
     * empty report, whose `passed` is false because nothing was translated and (with
     * [REQUIRE_TRANSLATION]) nothing else can rescue it.
     *
     * The run's non-marker output is **kept** rather than discarded
     * ([Report.launchLines]), and a run that produced no probe result at all but did
     * produce the launcher's own `[proroot] …` sentence is classified as a launch
     * failure — see the class KDoc for the defect that closes.
     */
    fun parse(output: String): Report {
        var interpreter: String? = null
        val phases = LinkedHashMap<String, Phase>()
        val launch = mutableListOf<String>()
        val prefix = "$MARKER$SEPARATOR"
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (!line.startsWith(prefix)) {
                // Not a probe result. The launcher's own diagnostics arrive here, and so
                // does anything the shell said before the markers started.
                val text = line.trim()
                if (text.isNotEmpty() && launch.size < MAX_LAUNCH_LINES) {
                    launch += if (text.length <= MAX_LAUNCH_CHARS) {
                        text
                    } else {
                        text.take(MAX_LAUNCH_CHARS - 1).trimEnd() + "…"
                    }
                }
                return@forEach
            }
            val fields = line.split(SEPARATOR)
            if (fields.size < 4) return@forEach
            val phase = fields[1]
            val verdict = fields[2]
            val detail = fields.drop(3).joinToString(SEPARATOR).trim()
            if (phase == "interpreter") {
                interpreter = detail.ifBlank { "interpreter" }
                return@forEach
            }
            phases[phase] = Phase(verdict, detail)
        }
        // Nothing was measured and proroot said why: that is a launch failure, and it gets
        // a phase of its own so every reader (`describe`, the narrative, the report) can
        // name it without re-deciding what it means.
        if (phases.isEmpty() && interpreter == null) {
            launch.firstOrNull { it.startsWith(LAUNCHER_PREFIX) }?.let { sentence ->
                phases[LAUNCHER_PHASE] = Phase(LAUNCHER_FAILED, sentence)
            }
        }
        return Report(phases = phases, interpreter = interpreter, launchLines = launch)
    }
}

/**
 * The **dynamic-binary** stage of the proroot probe gate: *can the engine's own class of
 * binary be executed under this runtime at all?*
 *
 * ## The defect this stage exists for (measured 2026-09-19)
 *
 * `ProrootProbe` ran two measurements and both passed on the device:
 *
 *  - `ProrootRawProbe`, whose guest script runs **`perl`** — a dynamically linked glibc
 *    binary with `PT_INTERP=/lib/ld-linux-aarch64.so.1`, so the loader *was* exercised —
 *    and whose marker lines came back (`guestpath=translated`, `passwd=translated`);
 *  - `GuestToolProbe`, whose `rg`/`fd` are **static musl** binaries.
 *
 * …and the engine still exited **126** ("command found but cannot be executed"). So the
 * gate's verdict was about the right device and the wrong question: nothing in it asked
 * about `/opt/node/bin/node`, the one binary the app cannot do without, and no cached
 * verdict ever said so. `docs/proroot-research.md` §5.P0-2 already warns that proroot's
 * failures are silent; this one was silent *through* the gate.
 *
 * ## 结案（2026-09-19 实测）：那次 126 不是 Node 起不来，是启动器 `chdir` 不了 `-w`
 *
 * 上面那段对**门禁覆盖范围**的判断成立，对**成因**的判断是错的。实测结论：
 *
 *  - **Node 能在钉住的 v1.2.8 下 exec。** 本开发容器本身就是 proroot 客户机；用
 *    `qemu-aarch64-static` 驱动钉住的 `libproroot.so`（bionic 二进制在客户机里不能直接
 *    exec），套一份从 `build/downloads/ubuntu-base-24.04.3-base-arm64.tar.gz` 解出的 rootfs
 *    并把钉住的 `node-v24.19.0-linux-arm64` 放成 `<rootfs>/opt/node`（连同
 *    `/usr/local/bin/node -> /opt/node/bin/node` 的软链），用 **本 App 一模一样的 argv 形状**
 *    跑：`/usr/bin/env true` → 0、`/opt/node/bin/node --version` → `v24.19.0`、
 *    `perl` → 0。`/opt/node/bin/node` 是 **ET_EXEC（非 PIE）**，`perl`/`env` 是 ET_DYN ——
 *    这是"perl 行、node 不行"这种形状天然的来源，但实测它在这台机器上不成立。
 *  - **那次 126 是启动器级的 `chdir` 失败。** proroot v1.2.8 的 `-w` **只按 rootfs 解析**，
 *    不看 `-b` 绑定表；`<rootfs>/<cwd>` 不存在时启动器打印一行、子进程以 126 退出：
 *
 *    ```
 *    [proroot] chdir workdir failed: /workspace/pi/workspaces/workspace-1: No such file or directory
 *    [proroot] child exited with code 126
 *    ```
 *
 *    同一条命令只在 rootfs 里补上那个目录，就 `cwd=/workspace/pi/workspaces/workspace-1`
 *    并全部退出 0。App 的装机路径只建 `<rootfs>/workspace`（`RuntimeProvisioner.kt:687`），
 *    而引擎与装包命令的 cwd 是 `<rootfs>/workspace/pi/workspaces/<名>`
 *    （`GuestWorkspacePath.under`、`PiEngineHost.kt:437`），差的正是这一层。修法在
 *    `ProrootCommand.ensureWorkdir`（连同"只在有绑定覆盖、且那条绑定的 host 目录存在时
 *    才补目录"的纪律）。
 *  - **本阶段自己的形状从来没复现过那个 126**：它的 `-w` 是 `/`（rootfs 自己），上面三组
 *    目标在 lab 里都退出 0。真机上若本阶段报 126，那是另一个成因，本轮的复现里没有它。
 *
 * 本阶段因此**保留**：它是唯一对"引擎这一类二进制"说话的测量，而"引擎自己的形状"
 *（`-w <guest workspace>` + 它自己的 `-b`）由 `ProrootProbe.autopsy` 在一次真实启动失败
 * 之后原样重跑 —— 那次 `chdir` 失败正是先在那条路径上留下了 proroot 的原话。
 *
 * ## What it measures, and why these two targets
 *
 *  1. **`/usr/bin/env true`** — the smallest dynamically linked glibc ELF in the pinned
 *     Ubuntu base (68 KB). It isolates "a dynamic binary can be exec'd here" from "Node
 *     specifically can be exec'd here". Note what it measures: `env` execs `true`, which
 *     exits 0 and writes **nothing**, so this target passes on silence
 *     ([Target.expectsOutput] `false`) — the output rule below applies to the engine's
 *     binary, not to this one.
 *  2. **`/opt/node/bin/node --version`** — the engine's own interpreter, invoked for
 *     real. This is the measurement that matters: a runtime that cannot start this
 *     binary cannot start the engine, and a gate that passes anyway hands the user a
 *     switch that produces `引擎以退出码 126 退出`.
 *
 * Each target also reports an **existence/permission state** before it is run, because
 * "missing", "present but not executable" (+x never applied by an unpack step) and
 * "exec failed anyway" have three different fixes and would otherwise all look like one
 * non-zero exit code.
 *
 * ## Why a failure here refuses proroot **entirely**
 *
 * The one process the app cannot run without is the engine. A runtime that can start
 * `bash`, `rg`, `fd` and `perl` but not Node is not "proroot with a hole in it" — it is a
 * runtime on which the whole engine fails, and the app's rule is all-or-nothing: the
 * whole runtime falls back to proot rather than half of the launch paths using proroot
 * (`RuntimeChoice.probeGate`). The install/maintenance exception is the pre-existing,
 * explicit `allowProroot = false` line, not a second semantics.
 *
 * ## Bounds
 *
 * [parse] keeps one bounded line per target ([MAX_DETAIL_CHARS]); [guestCommand] writes
 * nothing else. The run itself is bounded by the caller's timeout
 * (`ProrootProbe.EXEC_TIMEOUT_MS`). [launcherLinesFrom] keeps **only** the lines proroot's
 * own launcher writes (`[proroot] …`), which is what stops a diagnostic from dumping an
 * environment or a guest program's output into the failure state.
 */
object ProrootExecProbe {

    /** The stage name the settings row, the report and the narrative all render. */
    const val LABEL = "动态二进制"

    /**
     * The stage's mark prefixes. [LABEL] is shared with `ProrootProbeNarrative`, which reads
     * a **cached** verdict's lines and has to name the failing stage from them — the cache
     * holds these human lines and nothing else, so the mark they start with is the only place
     * the stage's identity survives a process restart.
     */
    const val PASS_MARK = "✓ $LABEL"
    const val FAIL_MARK = "✗ $LABEL"

    /** Every result line starts with this, so shell noise is ignored. */
    const val MARKER = "PI-EXEC"

    /** Field separator inside a marker line. */
    const val SEPARATOR = "\t"

    /** The existence phase: `<exe>` is present, absent, or present without `+x`. */
    const val PHASE_EXISTS = "exists"

    /** The execution phase: the target was run and this is its exit code and first line. */
    const val PHASE_RUN = "run"

    /** The three states [PHASE_EXISTS] can report. */
    const val STATE_EXEC = "exec"
    const val STATE_NOEXEC = "noexec"
    const val STATE_MISSING = "missing"

    /** Bound for one recorded output line, so a hostile binary cannot fill the cache. */
    const val MAX_DETAIL_CHARS = 240

    /**
     * Exit codes that mean "this process never ran" rather than "this process ran and
     * returned". `126` is `libproroot.so`'s own code for a child that could not be set up
     * (`_exit(126)` at `0xb5cc` in `run_child_exec` and at `0xaa54` in
     * `launcher_child_wait_for_foreground`, v1.2.8, sha256 `a4e74d75…`) and also POSIX
     * shell's "found but cannot be executed"; `127` is the launcher's other failure code
     * (`0xb534`, `0xb590`) and the shell's "command not found". Both are the runtime
     * failing, not the guest program.
     */
    const val EXIT_CANNOT_EXEC = 126
    const val EXIT_NOT_FOUND = 127

    /**
     * Whether an engine exit code belongs to the *runtime* rather than to the engine.
     *
     * Deliberately exact rather than `!= 0`: an engine that started and then failed
     * (a broken extension, an unknown flag, `pi` exiting on its own) uses 1 or a signal
     * code and is a **result**, and counting those would abandon proroot because a user's
     * extension was broken. 126/127 mean the exec never happened.
     */
    fun isLaunchFailure(exitCode: Int?): Boolean =
        exitCode == EXIT_CANNOT_EXEC || exitCode == EXIT_NOT_FOUND

    /**
     * One binary this stage really executes.
     *
     * @param expectsOutput whether a **successful** run of this command prints anything.
     *
     *   It is a property of the *command*, not a switch that relaxes the verdict, and it
     *   exists because one of the two targets is `/usr/bin/env true` — whose whole purpose
     *   is to exit 0 and print nothing. Without this field the rule "exit 0 with no output
     *   is a failure" (which is exactly what a wrapper that silently does nothing looks
     *   like) made the gate **unsatisfiable**: the user's report shows the stage failing
     *   with `退出码 0 但没有任何输出` on a device where proroot, the raw-syscall probe, `rg`
     *   and `fd` had all passed — so proroot could never be selected, no matter what the
     *   user did. `/opt/node/bin/node --version` keeps `true`: it must really print a
     *   version, and a version-less node is still reported as a failure.
     */
    data class Target(val exe: String, val args: String, val expectsOutput: Boolean = true) {
        /** For the report and the harness: the exact command line that was run. */
        val commandLine: String get() = if (args.isEmpty()) exe else "$exe $args"
    }

    /**
     * The two targets, in report order.
     *
     * `/opt/node/bin/node` is the engine's own path and not the `/usr/local/bin/node`
     * symlink: the symlink is what PATH resolves to, but the engine **execs** the real path,
     * and a probe that tested a different spelling would not be an answer about the engine.
     * The same string is spelled in three other places — `PiEngineHost`'s guest command
     * (`exec /opt/node/bin/node …`), `AgentLayout.guestNode`, and the symlink
     * `RuntimeProvisioner` creates — so the literal is deliberate rather than an oversight:
     * this object lives in `runtime/` and is compiled by a bare-JVM harness that has none of
     * those files, and a probe that reached into the packages layer would stop being pure.
     * If the payload ever moves Node, the probe fails loudly with
     * `不存在（探针读不到这个文件）` rather than passing quietly.
     */
    val TARGETS: List<Target> = listOf(
        // `true` prints nothing **by design** — `expectsOutput = false` is what says so.
        Target("/usr/bin/env", "true", expectsOutput = false),
        // Node must print its version: this one stays strict.
        Target("/opt/node/bin/node", "--version", expectsOutput = true),
    )

    /** One target's measured outcome. */
    data class TargetResult(
        val exe: String,
        val args: String,
        /** [STATE_EXEC], [STATE_NOEXEC], [STATE_MISSING], or null when no line arrived. */
        val state: String?,
        val exitCode: Int?,
        /** The run's first output line (stdout and stderr merged), bounded. */
        val firstLine: String?,
        /** Why this is not ok; null exactly when the target passed. */
        val reason: String?,
    ) {
        val ok: Boolean get() = reason == null

        /** The exact command line this result is about, as the script ran it. */
        val commandLine: String get() = if (args.isEmpty()) exe else "$exe $args"

        /** One line for the report, the row and the autopsy. */
        fun describe(): String = if (ok) {
            // A target that is silent **by design** has nothing to put in the parenthesis, and
            // an empty `（）` reads like a missing reading. Say the fact instead of nesting
            //括号：`退出码 0（该目标本来就不输出）`。
            val detail = firstLine?.takeIf { it.isNotBlank() } ?: "该目标本来就不输出"
            "$PASS_MARK $commandLine：退出码 0（$detail）"
        } else {
            "$FAIL_MARK $commandLine：$reason"
        }
    }

    /**
     * The whole stage: one verdict per target, plus whatever proroot's launcher said.
     *
     * [results] always has exactly [TARGETS].size entries — a target whose result line
     * never arrived is a **failure**, not a missing row, so a script that died half-way
     * cannot be read as "the first target passed".
     */
    data class Report(
        val results: List<TargetResult>,
        /** Only `[proroot] …` lines, bounded; see [launcherLinesFrom]. */
        val launchLines: List<String> = emptyList(),
    ) {
        /**
         * True only when every target was found executable, ran, and exited 0 — and printed
         * what it was supposed to print ([Target.expectsOutput]).
         */
        val ok: Boolean get() = results.isNotEmpty() && results.all { it.ok }

        /** The recorded evidence lines, in target order. */
        fun describe(): List<String> = buildList {
            if (results.isEmpty()) {
                add("$FAIL_MARK：探针没有输出任何结果行（proroot 启动器或 guest 的 bash 没跑到）")
            } else {
                results.forEach { add(it.describe()) }
            }
            launchLines.forEach { add("  启动器原话：$it") }
        }
    }

    /**
     * The guest script: probe existence and `+x`, then **really run** each target with
     * stderr merged, and emit one marker line per phase.
     *
     * `set -- $spec` splits a literal from [TARGETS] on purpose (word splitting and no
     * globbing hazard: the strings are fixed here, hold no spaces, and are passed to a
     * command whose arguments are its own).
     */
    fun guestCommand(targets: List<Target> = TARGETS): String {
        val tab = SEPARATOR
        val specs = targets.joinToString(" ") { "\"${it.commandLine}\"" }
        return listOf(
            "# proroot 动态二进制探针（见 ProrootExecProbe 的 KDoc）",
            "for spec in $specs; do",
            "  set -- \$spec",
            "  exe=\$1; shift",
            "  if [ ! -e \"\$exe\" ]; then state=$STATE_MISSING",
            "  elif [ ! -x \"\$exe\" ]; then state=$STATE_NOEXEC",
            "  else state=$STATE_EXEC",
            "  fi",
            "  echo \"$MARKER$tab$PHASE_EXISTS$tab\$exe$tab\$state\"",
            "  out=\$(\"\$exe\" \"\$@\" 2>&1); rc=\$?",
            "  first=\$(printf '%s' \"\$out\" | head -n 1)",
            "  echo \"$MARKER$tab$PHASE_RUN$tab\$exe$tab\$rc$tab\$first\"",
            "done",
        ).joinToString("\n")
    }

    /**
     * Turn the run's stdout into verdicts. Pure.
     *
     * A missing marker line for a target is reported as a failure (see [Report]); a blank
     * first output line on a 0 exit is a failure **only for a target that is supposed to
     * print something** ([Target.expectsOutput]) — "exit 0 and said nothing" is exactly what
     * a wrapper script that silently does nothing looks like, but it is also exactly what
     * `/usr/bin/env true` *is*. Applying the rule to both made this stage unsatisfiable and
     * refused proroot on every device; see [TARGETS].
     */
    fun parse(output: String, targets: List<Target> = TARGETS): Report {
        val states = LinkedHashMap<String, String>()
        val runs = LinkedHashMap<String, Pair<Int?, String?>>()
        val launch = mutableListOf<String>()
        // A run that hit the caller's timeout: the process was killed, so *no* target has a
        // result line. Reported as a timeout rather than as "the launcher never started",
        // because the two have different fixes and the same missing marker lines.
        val timeout = output.lineSequence()
            .map { it.trimEnd('\r') }
            .firstOrNull { it.startsWith(ProrootRawProbe.TIMEOUT_MARKER) }
            ?.substringAfter(ProrootRawProbe.TIMEOUT_MARKER)
            ?.trimStart(ProrootRawProbe.SEPARATOR.single())
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val prefix = "$MARKER$SEPARATOR"
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (!line.startsWith(prefix)) {
                // The launcher's own diagnostics — and anything else the shell said.
                val text = line.trim()
                if (text.isNotEmpty() && launch.size < ProrootRawProbe.MAX_LAUNCH_LINES &&
                    text.startsWith(ProrootRawProbe.LAUNCHER_PREFIX)
                ) {
                    launch += bound(text)
                }
                return@forEach
            }
            val fields = line.split(SEPARATOR)
            if (fields.size < 4) return@forEach
            val phase = fields[1]
            val exe = fields[2]
            when (phase) {
                PHASE_EXISTS -> states[exe] = fields[3].trim()

                PHASE_RUN -> {
                    val rc = fields[3].trim().toIntOrNull()
                    val detail = fields.drop(4).joinToString(SEPARATOR).trim()
                    runs[exe] = rc to detail.ifBlank { null }
                }

                else -> Unit
            }
        }
        val results = targets.map { target ->
            val state = states[target.exe]
            val run = runs[target.exe]
            val reason = when {
                state == null && timeout != null -> "探针超时（$timeout）"
                state == null -> "没有输出这个目标的探测行（proroot 启动器或 guest 的 bash 没跑到）"
                state == STATE_MISSING -> "`${target.exe}` 在 guest 里不存在（探针读不到这个文件）"
                state == STATE_NOEXEC -> "`${target.exe}` 存在但没有执行位（+x 未置位）"
                run == null -> "没有输出这个目标的运行结果行（探测中途死了）"
                // The mark prefix and the command line already name the target, so the reason
                // starts at the exit code: `✗ 动态二进制 /opt/node/bin/node --version：退出码 126：…`.
                run.first != 0 -> "退出码 ${run.first}" + suffix(run.second)
                // Silence is a failure **only for a command that is supposed to speak** —
                // see [Target.expectsOutput]. `/usr/bin/env true` exiting 0 silently is the
                // target working, not the runtime hiding something.
                run.second == null && target.expectsOutput -> "退出码 0 但没有任何输出"
                else -> null
            }
            TargetResult(
                exe = target.exe,
                args = target.args,
                state = state,
                exitCode = run?.first,
                firstLine = run?.second,
                reason = reason,
            )
        }
        return Report(results = results, launchLines = launch)
    }

    /**
     * The launcher's own lines, bounded. **The only** free text this stage ever quotes:
     * a diagnostic that quoted a run's whole output would be a way to dump an environment
     * or a program's data into a failure state, and the launcher's lines are the ones that
     * name the stage that failed (`[proroot] child: stage=… target=… errno=…`).
     */
    fun launcherLinesFrom(output: String?): List<String> = output.orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(ProrootRawProbe.LAUNCHER_PREFIX) }
        .take(ProrootRawProbe.MAX_LAUNCH_LINES)
        .map { bound(it) }
        .toList()

    /** How many lines the engine-failure autopsy writes at most. */
    const val MAX_AUTOPSY_LINES = 8

    /**
     * The **failure-side** rendering of this stage: the block `PiEngineHost` records when
     * a proroot engine exited with [EXIT_CANNOT_EXEC]/[EXIT_NOT_FOUND].
     *
     * It is a rendering and not a decision — it states the exit code, what the same
     * dynamic-binary probe saw on a fresh run, and proroot's own sentence; nothing here
     * guesses a cause. `report == null` means the autopsy itself could not run, and that is
     * said out loud ([probeNote]) rather than left as an empty block.
     */
    fun autopsyLines(
        exitCode: Int?,
        report: Report?,
        launcherLines: List<String> = emptyList(),
        probeNote: String? = null,
        cwd: String? = null,
        limit: Int = MAX_AUTOPSY_LINES,
    ): List<String> {
        require(limit >= 2) { "the autopsy must be able to hold a header and one result" }
        val lines = buildList {
            // `cwd` is named when the caller repeated the engine's own working directory and
            // binds: the first thing a reader has to be able to tell is whether this was a
            // probe-shaped run or a launch-shaped one, because the engine's shape is exactly
            // what the probe gate does *not* cover (`ProrootProbe.autopsy`).
            val shape = cwd?.let { "，cwd=$it，同一组 bind" } ?: ""
            add(
                "proroot 引擎启动失败取证：用同一种 proroot 启动方式重跑动态二进制$shape" +
                    "（引擎退出码 ${exitCode?.toString() ?: "未记录"}）",
            )
            if (report == null) {
                add("  取证没能跑起来：${probeNote ?: "探针没有返回任何结果"}")
            } else {
                addAll(report.describe())
            }
            launcherLines.forEach { add("  启动器原话：$it") }
        }
        if (lines.size <= limit) return lines
        return lines.take(limit - 1) + "……还有 ${lines.size - (limit - 1)} 行，导出诊断报告可看全文"
    }

    /** One line, bounded to [MAX_DETAIL_CHARS], with the cut marked. */
    private fun bound(text: String): String {
        val clean = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (clean.length <= MAX_DETAIL_CHARS) clean else clean.take(MAX_DETAIL_CHARS - 1) + "…"
    }

    private fun suffix(output: String?): String =
        output?.let { "：" + bound(it) } ?: "（没有任何输出）"
}
