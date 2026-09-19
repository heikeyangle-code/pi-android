package app.pi.runtime

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
 * 1. **`guestpath`** — a file this probe plants *inside the guest* (`/tmp/…`) and
 *    then reads back with a raw `openat`. Translated → the marker comes back.
 *    Untranslated → `ENOENT`, because no such file exists at that path in the host
 *    namespace (guest `/tmp` is a host directory that is only reachable that way).
 *    This is the positive test for "an inline `svc` call sees the guest filesystem".
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
 * a run where it cannot is a run where proroot is not used. That makes the whole
 * opt-in inert on a build whose raw path is untranslated — deliberately, because the
 * alternative is a silent host-file read somewhere in pi's toolchain, and the user
 * can always fall back to proot. The flag exists so that this is a decision with a
 * name rather than a condition buried in a parser, and so a device test can tell the
 * two failure modes apart.
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

    /** `header：detail` — the separator all three headers above use. */
    const val HEADER_SEPARATOR = "："

    /** Every output line starts with this, so shell noise is ignored. */
    const val MARKER = "PI-PROROOT-RAW"

    /** Field separator inside a marker line. */
    const val SEPARATOR = "\t"

    /** The planted marker file's name; also what the host side deletes afterwards. */
    const val PLANTED_NAME = "pi-proroot-raw-probe"

    /** Its **guest** spelling — this is what the raw `openat` must resolve. */
    const val PLANTED_GUEST_PATH = "/tmp/$PLANTED_NAME"

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

        val passed: Boolean
            get() = interpreter == null && !leaked && (translated || !REQUIRE_TRANSLATION)

        /** Why it did not pass, in the user's terms; null exactly when [passed]. */
        val failure: String?
            get() = when {
                interpreter != null -> "探针没能运行：guest 里没有 $interpreter"
                leaked -> "raw syscall 读到了宿主文件（libc 与 raw 对同一路径给出不同内容）——" +
                    "这属于静默越界，proroot 不使用"

                !translated -> "raw/inline svc 调用没有被翻译（看不到 guest 文件系统）——" +
                    "proroot 不使用"

                else -> null
            }

        /** The evidence lines, for the diagnostic report. */
        fun describe(): List<String> {
            if (interpreter != null) {
                return listOf("$NOT_RUN_HEADER$HEADER_SEPARATOR" + "guest 里没有 $interpreter")
            }
            val order = listOf("guestpath", "hostpath", "passwd")
            val lines = order.mapNotNull { phase ->
                phases[phase]?.let { "  $phase=${it.verdict}（${it.detail}）" }
            }
            return listOf(
                if (passed) PASS_HEADER else "$FAILURE_HEADER$HEADER_SEPARATOR$failure",
            ) + lines
        }
    }

    /**
     * The guest script.
     *
     * @param hostTmpPath the **host** path of [PLANTED_GUEST_PATH] — `<runtime>/tmp`
     *        in the app, which is what `/tmp` is bound to. Passed in because the guest
     *        script cannot work out the host-side spelling of its own `/tmp`, and the
     *        third measurement is exactly about that spelling.
     * @param token the marker content; a random-ish value so a stale file from a
     *        previous run cannot satisfy the check.
     */
    fun guestCommand(hostTmpPath: String, token: String): String {
        val tab = SEPARATOR
        // Single-quoted Perl, so nothing inside may contain a single quote. The host
        // path and the token are embedded as double-quoted Perl literals.
        val perl = buildString {
            appendLine("my \$planted = \"$PLANTED_GUEST_PATH\";")
            appendLine("my \$hostpath = \"$hostTmpPath/$PLANTED_NAME\";")
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
     * Turn the probe's stdout into a [Report]. Pure.
     *
     * A missing marker line is not a pass: an empty or truncated run parses to an
     * empty report, whose `passed` is false because nothing was translated and (with
     * [REQUIRE_TRANSLATION]) nothing else can rescue it.
     */
    fun parse(output: String): Report {
        var interpreter: String? = null
        val phases = LinkedHashMap<String, Phase>()
        val prefix = "$MARKER$SEPARATOR"
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (!line.startsWith(prefix)) return@forEach
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
        return Report(phases = phases, interpreter = interpreter)
    }
}
