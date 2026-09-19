package app.pi.runtime

// The bare-JVM harness for **the evidence block under the 运行时（实际生效）row**, to be
// registered in `tools/run-app-pure-checks.sh` as `proroot-probe-detail`.
//
// Why it exists: the probe gate records four to six lines, one per measured phase, and
// those lines were reachable only by exporting the diagnostic report. The row now shows
// them (`ProrootProbeNarrative.detailLines` / `boundedDetailLines`, rendered by
// `SettingsGroupScreen`), so two properties have to hold that nothing else can check:
//
//  1. **the row and the report show the same lines.** The report prints the unbounded
//     form, the row prints a capped prefix plus a sentence saying how many lines it left
//     out; both come from one list, and this harness pins prefix equality, the cap, the
//     note, and that a cache round trip (render → parse → read) reproduces the lines the
//     probe wrote. A row that quietly dropped or reworded a line would be showing a
//     reading that does not match the evidence.
//  2. **"no evidence" is stated, not filled in.** A verdict with no recorded lines, or a
//     probe that has never run, yields a sentence saying exactly that — never an empty
//     block where a reading is expected, and never a stage made up here.
//  3. **a run proroot's launcher killed before the guest existed is readable here too**
//     (added 2026-09-19). Its header, its phase and the launcher's own sentence are what
//     make "why is this not using proroot" answerable from the settings screen, and the
//     cache round trip has to reproduce them exactly.
//  4. **a verdict earned in the other seccomp档 is not ours** (added 2026-09-19). The key
//     carries the档, so a stale file from before the fix (or from a different
//     configuration) reads as "no verdict" instead of being trusted.
//
// The lines fed in below are produced by the production `describe()`s
// (`ProrootRawProbe.Report`, `GuestToolProbe.Report`, `ProrootProbeCache`), so a reworded
// line is a harness failure rather than a silent mismatch with the exported report.
// Android-free: Kotlin stdlib plus `java.io` through the closure, no device, no process.

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

/** The raw half of a refused gate, through the production `describe()`. */
private val refusedRaw: List<String> = ProrootRawProbe.parse(
    listOf(
        rawMarker("guestpath", "untranslated", "errno=2"),
        rawMarker("hostpath", "unreachable", "errno=2"),
        rawMarker("passwd", "unreadable", "errno=13"),
    ).joinToString("\n"),
).describe()

/** The rg/fd half of the same run, through `GuestToolProbe`'s own `describe()`. */
private val refusedTools: List<String> = GuestToolProbe.parse(
    listOf(
        toolMarker("version", "rg", 127, "/usr/local/bin/rg: No such file or directory"),
        toolMarker("search", "rg", 127, "No such file or directory"),
        toolMarker("version", "fd", 127, "/usr/local/bin/fd: No such file or directory"),
        toolMarker("search", "fd", 127, "No such file or directory"),
    ).joinToString("\n"),
).describe().map { "  $it" }

/**
 * What `ProrootProbe.run` writes into the cache for this run. The only line here that is
 * not a production call is the two-space indent of the tools half, which is copied from
 * `run()`; the narrative trims it, so the copy cannot change what the row shows.
 */
private val recordedFailure: List<String> = refusedRaw + refusedTools

private fun markers(count: Int): List<String> =
    (1..count).map { "  合成第 $it 行：探针逐阶段判读" }

fun main() {
    // ================================================= 1. 原始判读：逐字、不过滤
    check(
        "the report form is the recorded lines, trimmed and verbatim",
        ProrootProbeNarrative.detailLines(EngineFallback.ProbeNotPassed, false, recordedFailure),
        recordedFailure.map { it.trim() },
    )
    check(
        "the report form keeps every line (no cap)",
        ProrootProbeNarrative.detailLines(EngineFallback.ProbeNotPassed, false, markers(9)).size,
        9,
    )
    check(
        "the report form drops padding and blank lines only",
        ProrootProbeNarrative.detailLines(EngineFallback.ProbeNotPassed, false, listOf("  a  ", "", "   ", "b")),
        listOf("a", "b"),
    )
    check(
        "the joined form is one line per entry",
        ProrootProbeNarrative.detailText(EngineFallback.ProbeNotPassed, false, recordedFailure)
            .split("\n").size,
        recordedFailure.size,
    )

    // ================================================= 2. 行内：有界 + 截断说明
    check(
        "under the cap the block is the report's lines",
        ProrootProbeNarrative.boundedDetailLines(EngineFallback.ProbeNotPassed, false, markers(6)),
        markers(6).map { it.trim() },
    )
    val capped = ProrootProbeNarrative.boundedDetailLines(EngineFallback.ProbeNotPassed, false, markers(9))
    check("over the cap the block stops at the cap", capped.size, ProrootProbeNarrative.MAX_DETAIL_LINES + 1)
    check(
        "the block is the report's prefix",
        capped.take(ProrootProbeNarrative.MAX_DETAIL_LINES),
        markers(9).map { it.trim() }.take(ProrootProbeNarrative.MAX_DETAIL_LINES),
    )
    check(
        "the truncation line names how many were left out",
        capped.last().contains("还有 ${9 - ProrootProbeNarrative.MAX_DETAIL_LINES} 行"),
        true,
    )
    check("the truncation line says where the whole evidence is", capped.last().contains("诊断报告"), true)
    check(
        "an explicit cap is executed, not described",
        ProrootProbeNarrative.boundedDetailLines(EngineFallback.ProbeNotPassed, false, markers(9), limit = 2)
            .size,
        3,
    )
    check(
        "a cap of zero is refused rather than silently yielding nothing",
        runCatching { ProrootProbeNarrative.boundedDetailLines(EngineFallback.ProbeNotPassed, false, markers(9), limit = 0) }
            .isFailure,
        true,
    )
    check(
        "the bounded text has exactly the bounded lines",
        ProrootProbeNarrative.boundedDetailText(EngineFallback.ProbeNotPassed, false, markers(9))
            .split("\n").size,
        capped.size,
    )

    // ================================================= 3. 取不到就如实说
    check(
        "a probe that has never run says so",
        ProrootProbeNarrative.detailLines(EngineFallback.ProbeNotRun, null, emptyList()),
        listOf(ProrootProbeNarrative.DETAIL_NOT_RUN),
    )
    check(
        "the not-run block names the state in words a user reads",
        ProrootProbeNarrative.detailText(EngineFallback.ProbeNotRun, null, emptyList()).contains("尚未运行"),
        true,
    )
    check(
        "a verdict with no evidence says it has none",
        ProrootProbeNarrative.detailLines(EngineFallback.ProbeNotPassed, false, emptyList()),
        listOf(ProrootProbeNarrative.DETAIL_NOT_PASSED_WITHOUT_EVIDENCE),
    )
    check(
        "the no-evidence block says it cannot read the stages",
        ProrootProbeNarrative.detailText(EngineFallback.ProbeNotPassed, false, emptyList()).contains("读不到"),
        true,
    )
    check(
        "a passing verdict with no evidence still says so",
        ProrootProbeNarrative.detailLines(EngineFallback.None, true, emptyList()),
        listOf(ProrootProbeNarrative.DETAIL_PASSED_WITHOUT_EVIDENCE),
    )
    check(
        "reasons that are not the probe add no block",
        listOf(
            EngineFallback.SwitchOff,
            EngineFallback.RuntimeFilesMissing,
            EngineFallback.FailureStreak,
            EngineFallback.None,
            EngineFallback.InstallPath,
        ).all { ProrootProbeNarrative.boundedDetailText(it, null, emptyList()).isEmpty() },
        true,
    )
    check(
        "recorded lines are shown even when the switch is off now",
        ProrootProbeNarrative.detailLines(EngineFallback.SwitchOff, false, recordedFailure),
        recordedFailure.map { it.trim() },
    )

    // ================================================= 4. 缓存往返：行不会变
    // The row reads a verdict that a **previous process** wrote. If the round trip
    // through the cache file changed a line, the row and the exported report would
    // disagree while both looked right in isolation.
    val key = ProrootProbeCache.key("2026-06-17.3", "digest", RuntimeChoice.PROROOT_SECCOMP.tag)
    val text = ProrootProbeCache.render(key, passed = false, detail = recordedFailure)
    val cached = ProrootProbeCache.parse(text, key)
    check("the cached verdict is read back", cached?.passed, false)
    check(
        "the cached lines are the ones the probe wrote",
        cached?.detail,
        recordedFailure,
    )
    check(
        "what the row shows after a restart is what the report shows",
        ProrootProbeNarrative.detailLines(
            fallback = EngineFallback.ProbeNotPassed,
            probePassed = cached?.passed,
            probeDetail = cached?.detail.orEmpty(),
        ),
        ProrootProbeNarrative.detailLines(EngineFallback.ProbeNotPassed, false, recordedFailure),
    )
    check(
        "and the sentence names the same stage as the first recorded line",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, cached?.detail.orEmpty())
            .contains(ProrootProbeNarrative.STAGE_RAW) &&
            ProrootProbeNarrative.detailLines(EngineFallback.ProbeNotPassed, false, recordedFailure)
                .first().startsWith(ProrootRawProbe.FAILURE_HEADER),
        true,
    )
    check(
        "a stale-key cache yields no verdict, so the row falls back to 尚未运行",
        ProrootProbeCache.parse(text, ProrootProbeCache.key("another-revision", "digest", RuntimeChoice.PROROOT_SECCOMP.tag)),
        null,
    )
    // ... and a verdict earned in the *other* seccomp档 is equally not ours (2026-09-19).
    check(
        "a verdict from the other mode is not read back",
        ProrootProbeCache.parse(text, ProrootProbeCache.key("2026-06-17.3", "digest", ProrootSeccomp.NoSeccomp.tag)),
        null,
    )

    // ================================================= 5. 启动失败也逐字进详情
    // The run proroot's launcher killed before the guest existed is now its own evidence
    // shape: its header, its phase and the launcher's own sentence. The row's detail block
    // and the exported report are the same list, so "why is it not using proroot" is
    // answerable without leaving the settings screen.
    val launchFailure: List<String> = ProrootRawProbe.parse(
        "[proroot] bad bind format (expected host:guest): /dev\n" +
            "Usage: libproroot.so [-r rootfs] [-0] [--link2symlink] [-b host:guest] command\n",
    ).describe()
    check(
        "the launch failure detail starts with its own header",
        launchFailure.first().startsWith(ProrootRawProbe.LAUNCH_FAILURE_HEADER),
        true,
    )
    check(
        "the launcher's sentence is in the detail block",
        launchFailure.any { it.contains("[proroot] bad bind format (expected host:guest): /dev") },
        true,
    )
    check(
        "the launcher's second line is too",
        launchFailure.any { it.contains("Usage: libproroot.so") },
        true,
    )
    check(
        "the launch failure travels through the cache unchanged",
        ProrootProbeCache.parse(
            ProrootProbeCache.render(key, false, launchFailure),
            key,
        )?.detail,
        launchFailure,
    )
    check(
        "and the row names the launch stage, not the raw one",
        ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, launchFailure)
            .contains(ProrootProbeNarrative.STAGE_LAUNCH) &&
            !ProrootProbeNarrative.summary(EngineFallback.ProbeNotPassed, launchFailure)
                .contains(ProrootProbeNarrative.STAGE_RAW),
        true,
    )
    check(
        "the unparseable-output shape is bounded before it reaches the row",
        ProrootProbeNarrative.boundedDetailLines(EngineFallback.ProbeNotPassed, false, launchFailure)
            .all { it.length <= ProrootRawProbe.MAX_LAUNCH_CHARS + 16 },
        true,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
