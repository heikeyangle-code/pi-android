package app.pi.runtime

// The bare-JVM harness for **what the settings row says when proroot is refused**, to be
// registered in `tools/run-app-pure-checks.sh` as `proroot-status-text`.
//
// Why it exists: the row used to say "已回退 proot：探针未通过" and stop, so the only way
// to learn *which* of the gate's two probes refused — and on which phase — was to export
// the diagnostic report. The data was already on disk; only the sentence was short. That
// sentence is now assembled by `ProrootProbeNarrative.summary`, and this harness pins it:
//
//  1. the base sentence is reused, not retyped (a rewrite cannot drift from
//     `RuntimeChoice.describe`), and every *other* fallback is passed through untouched
//     — a switch that is off must not grow a probe story it does not have;
//  2. the stage is the probe's own record: the lines fed here are produced by
//     `ProrootRawProbe.Report.describe` / `GuestToolProbe.Report.describe`, not typed by
//     hand, so a reworded verdict or header fails this harness rather than silently
//     degrading the row to "缓存里没有逐阶段记录";
//  3. a refusal with no recognised evidence says exactly that instead of inventing a
//     stage — the default-value-in-a-reading's-clothes shape this repository keeps fixing;
//  4. the row draws this with `maxLines = 1`, so the sentence must stay one line and the
//     quote must be bounded **without** truncating the stage name away;
//  5. the sentence carries the **seccomp档** in force, because "the raw syscall was not
//     translated" is a verdict about a promise and which promise was in force is part of
//     the answer (added 2026-09-19);
//  6. a run proroot's own launcher killed before the guest existed is named as its own
//     stage (`proroot 启动`) and quoted verbatim — reporting it as "raw syscall 未翻译"
//     was the misdiagnosis that made the settings row unable to explain itself (added
//     2026-09-19).
//
// Android-free by construction: the closure is Kotlin stdlib plus `java.io` (through
// `ProrootRawProbe`/`GuestToolProbe`, neither of which is started here — this harness only
// calls their pure parsers). If any file in the closure ever grows an Android import, this
// harness fails to compile, loudly, on purpose.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun rawMarker(phase: String, verdict: String, detail: String): String =
    ProrootRawProbe.MARKER + ProrootRawProbe.SEPARATOR + phase + ProrootRawProbe.SEPARATOR +
        verdict + ProrootRawProbe.SEPARATOR + detail

private fun toolMarker(phase: String, tool: String, rc: Int, detail: String): String =
    GuestToolProbe.MARKER + GuestToolProbe.SEPARATOR + phase + GuestToolProbe.SEPARATOR +
        tool + GuestToolProbe.SEPARATOR + rc + GuestToolProbe.SEPARATOR + detail

/** What `ProrootProbe.run` records for the raw half, through the production `describe()`. */
private fun rawEvidence(vararg markerLines: String): List<String> =
    ProrootRawProbe.parse(markerLines.joinToString("\n")).describe()

/** What it records for the rg/fd half, through `GuestToolProbe`'s own `describe()`. */
private fun toolEvidence(vararg markerLines: String): List<String> =
    GuestToolProbe.parse(markerLines.joinToString("\n")).describe().map { "  $it" }

private const val BASE = "已回退 proot：探针未通过"

/** The seccomp档 the sentence has to name, from the production constant. */
private const val MODE = "默认档"

fun main() {
    // ================================================= 1. 基础句子与其它回退原因
    check(
        "a refusal with no evidence says so instead of naming a stage",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, emptyList()),
        "$BASE（$MODE，缓存里没有逐阶段记录）",
    )
    check(
        "the refusal sentence is the base sentence plus a bracket",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, emptyList())
            .startsWith(RuntimeChoice.describe(EngineFallback.ProbeNotPassed)),
        true,
    )
    check(
        "the production mode is the one the sentence names",
        RuntimeChoice.PROROOT_SECCOMP.shortLabel,
        MODE,
    )
    check(
        "every other reason is passed through verbatim",
        EngineFallback.entries
            .filter { it != EngineFallback.ProbeNotPassed }
            .all { ProrootProbeNarrative.summary(it, listOf("  guestpath=untranslated（errno=2）")) == RuntimeChoice.describe(it) },
        true,
    )
    check(
        "尚未运行 keeps the three-launch clarification",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotRun, emptyList())
            .contains("下一次启动 guest 会跑一次，那一次仍用 proot，再下一次才可能接管"),
        true,
    )

    // ================================================= 2. 阶段名来自探针自己的记录
    // The realistic untranslated run: the planted guest file cannot be reached by the raw
    // syscall, and `/etc/passwd` is not translated either, so `Report.passed` is false with
    // the `!translated` arm of `Report.failure` — not the leak arm.
    val untranslated = rawEvidence(
        rawMarker("guestpath", "untranslated", "errno=2"),
        rawMarker("hostpath", "unreachable", "errno=2"),
        rawMarker("passwd", "unreadable", "errno=13"),
    )
    check(
        "an untranslated raw syscall names the raw stage and the phase line",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, untranslated),
        "$BASE（$MODE·raw syscall：guestpath=untranslated（errno=2））",
    )
    check(
        "the stage it names is the one the report blames",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, untranslated)
            .contains(ProrootRawProbe.UNTRANSLATED),
        true,
    )
    // The 档 is not decoration: it is part of the refusal's meaning. "The raw syscall was
    // not translated" is a verdict about a promise, so the sentence says which promise was
    // in force — and the value comes from the production constant, not from the recorded
    // lines (the cache key already pins a valid verdict to a 档).
    check(
        "the sentence names the mode in force",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, untranslated)
            .contains(RuntimeChoice.PROROOT_SECCOMP.shortLabel),
        true,
    )
    check(
        "both modes produce different sentences",
        ProrootProbeNarrative.summary(
            EngineFallback.ProbeNotPassed,
            untranslated,
            ProrootSeccomp.Seccomp,
        ) == ProrootProbeNarrative.summary(
            EngineFallback.ProbeNotPassed,
            untranslated,
            ProrootSeccomp.NoSeccomp,
        ),
        false,
    )

    // A leak is the disqualifying outcome, and `Report.failure` reports it before the
    // untranslated arm — so it is the stage the sentence has to name even when both hold.
    val leakedBoth = rawEvidence(
        rawMarker("guestpath", "mismatch", "raw=7 libc=42"),
        rawMarker("hostpath", "reachable", "len=42"),
        rawMarker("passwd", "leaked", "raw=7 libc=42"),
    )
    check(
        "a leak outranks an untranslated phase",
        ProrootProbeNarrative.failedStage(leakedBoth),
        ProrootProbeNarrative.FailedStage(ProrootProbeNarrative.STAGE_RAW, "guestpath=mismatch（raw=7 libc=42）"),
    )
    val leakedPasswd = rawEvidence(
        rawMarker("guestpath", "translated", "len=42"),
        rawMarker("hostpath", "unreachable", "errno=2"),
        rawMarker("passwd", "leaked", "raw=7 libc=42"),
    )
    check(
        "a leak on /etc/passwd is quoted as the passwd phase",
        ProrootProbeNarrative.failedStage(leakedPasswd)?.quote,
        "passwd=leaked（raw=7 libc=42）",
    )

    // No perl in the guest: the probe never ran, and that is the stage.
    val noInterpreter = rawEvidence(rawMarker("interpreter", "missing", "perl"))
    check(
        "a probe that could not run says why, named as the raw stage",
        ProrootProbeNarrative.failedStage(noInterpreter),
        ProrootProbeNarrative.FailedStage(ProrootProbeNarrative.STAGE_RAW, "guest 里没有 perl"),
    )

    // The run proroot itself killed before the guest existed (2026-09-19): the launcher's
    // argument parser refuses the invocation and prints its sentence to stderr, so there is
    // no measurement at all. The row must name *that* stage — reporting it as "raw syscall
    // 未翻译" is a verdict about a process that never reached translation, which is exactly
    // the misdiagnosis the fix closes.
    val launcherDied = ProrootRawProbe.parse(
        "[proroot] bad bind format (expected host:guest): /dev\n" +
            "Usage: libproroot.so [-r rootfs] [-0] [--link2symlink] [-b host:guest] command\n",
    ).describe()
    check(
        "a launcher that never reached the guest is its own stage",
        ProrootProbeNarrative.failedStage(launcherDied),
        ProrootProbeNarrative.FailedStage(
            ProrootProbeNarrative.STAGE_LAUNCH,
            "[proroot] bad bind format (expected host:guest): /dev",
        ),
    )
    check(
        "the launcher stage is quoted verbatim in the sentence",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, launcherDied)
            .contains("[proroot] bad bind format"),
        true,
    )
    check(
        "the launcher stage does not borrow the raw stage's name",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, launcherDied)
            .contains(ProrootProbeNarrative.STAGE_LAUNCH),
        true,
    )
    check(
        "the launcher stage outranks the headers",
        ProrootProbeNarrative.failedStage(launcherDied)?.label,
        ProrootProbeNarrative.STAGE_LAUNCH,
    )

    // A refusal whose phases carry no verdict this object knows (the planted file could
    // not be read at all): quote the probe's own prose rather than a phase line, and never
    // a token invented here.
    val noKnownPhase = rawEvidence(
        rawMarker("guestpath", "missing", "无法读取种植的探针文件"),
        rawMarker("passwd", "unreadable", "errno=13"),
    )
    check(
        "a refusal with no rankable phase quotes the probe's own failure line",
        ProrootProbeNarrative.failedStage(noKnownPhase)?.quote,
        noKnownPhase.first().substringAfter(ProrootRawProbe.HEADER_SEPARATOR),
    )

    // The second half of the gate: raw passes, but the real `rg`/`fd` invocation fails.
    val rawPassing = rawEvidence(
        rawMarker("guestpath", "translated", "len=42"),
        rawMarker("hostpath", "reachable", "len=42"),
        rawMarker("passwd", "translated", "len=42"),
    )
    check("a passing raw half has no stage to blame", ProrootProbeNarrative.failedStage(rawPassing), null)
    val toolsFailing = toolEvidence(
        toolMarker("version", "rg", 127, "/usr/local/bin/rg: No such file or directory"),
        toolMarker("version", "fd", 127, "/usr/local/bin/fd: No such file or directory"),
    )
    val toolsStage = ProrootProbeNarrative.failedStage(rawPassing + toolsFailing)
    check(
        "a failing rg/fd invocation names the tools stage",
        toolsStage?.label,
        ProrootProbeNarrative.STAGE_TOOLS,
    )
    check("the tools quote is the tool's own line", toolsStage?.quote?.startsWith("rg："), true)
    check(
        "the tools quote keeps the exit code it was refused on",
        toolsStage?.quote?.contains("退出码 127"),
        true,
    )
    val toolsNeverRan = GuestToolProbe.Report(
        results = emptyList(),
        launchError = "运行时尚未解包（缺少 rootfs 或 libproroot.so）",
    ).describe().map { "  $it" }
    check(
        "a tools probe that never ran is still the tools stage",
        ProrootProbeNarrative.failedStage(rawPassing + toolsNeverRan),
        ProrootProbeNarrative.FailedStage(
            ProrootProbeNarrative.STAGE_TOOLS,
            "运行时尚未解包（缺少 rootfs 或 libproroot.so）",
        ),
    )

    // None of these is a stage, and the sentence must not invent one from them.
    check("no evidence means no stage", ProrootProbeNarrative.failedStage(emptyList()), null)
    check(
        "unrecognised lines mean no stage",
        ProrootProbeNarrative.failedStage(listOf("  ", "RAW 探针挂了", "  passwd: leaked")),
        null,
    )
    // A healthy record has nothing to blame: if the narrative ever named a stage here it
    // would be inventing one, which is exactly what the row must never do.
    val toolsPassing = toolEvidence(
        toolMarker("version", "rg", 0, "ripgrep 14.1.0"),
        toolMarker("search", "rg", 0, "root:x:0:0:root:/root:/bin/bash"),
        toolMarker("version", "fd", 0, "fd 9.0.0"),
        toolMarker("search", "fd", 0, "passwd"),
    )
    check(
        "a pass record is not a failure",
        ProrootProbeNarrative.failedStage(rawPassing + toolsPassing),
        null,
    )

    // ================================================= 3. 有界：单行、引文先截断
    val longTools = GuestToolProbe.parse(
        toolMarker(
            "version",
            "rg",
            127,
            "/usr/local/bin/rg: No such file or directory, and the loader said a great deal more than this",
        ) + "\n" + toolMarker("search", "rg", 127, "No such file or directory"),
    ).describe().map { "  $it" }
    val longSummary = ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, rawPassing + longTools)
    val truncatedQuote = longSummary
        .substringAfter("${ProrootProbeNarrative.STAGE_TOOLS}${ProrootRawProbe.HEADER_SEPARATOR}")
        .removeSuffix("）")
    check("a long quote is truncated", truncatedQuote.endsWith("…"), true)
    check(
        "the quote is capped at the declared bound",
        truncatedQuote.length,
        ProrootProbeNarrative.MAX_QUOTE_CHARS,
    )
    check(
        "the truncated sentence keeps the stage name",
        longSummary.contains(ProrootProbeNarrative.STAGE_TOOLS),
        true,
    )
    check(
        "the sentence is capped at the declared bound",
        longSummary.length <= ProrootProbeNarrative.MAX_SUMMARY_CHARS,
        true,
    )
    check("the sentence is one line", longSummary.none { it == '\n' || it == '\r' }, true)
    check(
        "every reason is one line",
        EngineFallback.entries.all { fallback ->
            val text = ProrootProbeNarrative.summary(fallback, untranslated)
            text.none { it == '\n' || it == '\r' } && text.length <= ProrootProbeNarrative.MAX_SUMMARY_CHARS
        },
        true,
    )
    check(
        "newlines recorded in a quote cannot split the sentence",
        ProrootProbeNarrative.summary(
            EngineFallback.ProbeNotPassed,
            listOf("${ProrootRawProbe.NOT_RUN_HEADER}${ProrootRawProbe.HEADER_SEPARATOR}guest 里\n没有 perl"),
        ).none { it == '\n' },
        true,
    )

    // ================================================= 4. 词表与 guest 脚本同源
    // The narrative ranks these four tokens; the guest script is what emits them. A reword
    // on the wire that does not move the constants would make every cached verdict
    // unreadable, so the pairing is executed here rather than assumed.
    val wire = ProrootRawProbe.guestCommand("/data/tmp", "token")
    check(
        "the guest script emits every token the narrative ranks",
        listOf(
            ProrootRawProbe.TRANSLATED,
            ProrootRawProbe.UNTRANSLATED,
            ProrootRawProbe.LEAKED,
            ProrootRawProbe.MISMATCH,
        ).all { wire.contains("\"$it\"") },
        true,
    )
    check(
        "the three headers are the ones describe() writes",
        ProrootRawProbe.parse(rawMarker("interpreter", "missing", "perl")).describe().first()
            .startsWith(ProrootRawProbe.NOT_RUN_HEADER),
        true,
    )
    check(
        "a failing raw record starts with the failure header",
        untranslated.first().startsWith(ProrootRawProbe.FAILURE_HEADER),
        true,
    )
    // A fourth header, added 2026-09-19: a launch failure is not a translation verdict, so it
    // must not wear the translation verdict's header either.
    check(
        "a launch failure starts with its own header",
        launcherDied.first().startsWith(ProrootRawProbe.LAUNCH_FAILURE_HEADER),
        true,
    )
    check(
        "the launch header is not the translation header",
        ProrootRawProbe.LAUNCH_FAILURE_HEADER == ProrootRawProbe.FAILURE_HEADER,
        false,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
