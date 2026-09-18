package app.pi.runtime

// A bare-JVM harness for `GuestToolProbe`: the rg/fd "does it really run" probe.
//
// Why it is worth pinning: the probe's whole reason to exist is that a dangling
// `/usr/local/bin/{rg,fd}` and a missing tool are the same thing to every caller
// (pi's `find` and the `@` completion just return nothing — `docs/known-gaps.md`
// §K2), so the *verdict logic* is the only place the difference can be made
// visible. A parser that reports "ok" on a 127 exit, or that treats a missing
// marker line as a pass, would recreate exactly the silent failure the probe was
// added to end — and it would do it in the diagnostic report, i.e. the one place
// the user goes when something is wrong.
//
// Android-free on purpose: `GuestToolProbe` and `PiRuntime.kt` import only
// `java.io`/`java.util.concurrent` and the stdlib, so `parse()` and
// `guestCommand()` compile and run here without a device. Registered in
// `tools/run-app-pure-checks.sh` as `guest-tool-probe`.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private val TAB = GuestToolProbe.SEPARATOR

/** One marker line, spelled the way [GuestToolProbe.guestCommand] emits it. */
private fun marker(phase: String, tool: String, rc: Int, detail: String): String =
    "${GuestToolProbe.MARKER}$TAB$phase$TAB$tool$TAB$rc$TAB$detail"

/** The output of a fully healthy guest, exactly as the guest script would produce it. */
private fun healthyOutput(): String = listOf(
    marker("version", "rg", 0, "ripgrep 14.1.1 (rev 4649aa9700)"),
    marker("version", "fd", 0, "fd 10.2.0"),
    marker("search", "rg", 0, "root:x:0:0:root:/root:/bin/bash"),
    marker("search", "fd", 0, "/etc/passwd"),
).joinToString("\n")

fun main() {
    // ------------------------------------------------- 1. the command is a real call
    val command = GuestToolProbe.guestCommand()
    check("the command loops over both tools", command.contains("for tool in rg fd"), true)
    check("it asks for --version", command.contains("--version"), true)
    check("it also really searches (rg)", command.contains("rg -N '^root' /etc/passwd"), true)
    check("it also really searches (fd)", command.contains("fd -H '^passwd$' /etc"), true)
    check(
        "every emitted line carries the marker and the tab separator",
        command.split("\n").count { it.trimStart().startsWith("echo \"${GuestToolProbe.MARKER}$TAB") },
        // one version line inside the loop, one search line per tool
        3,
    )
    // The report is user-visible text; the strings that reach it must not carry
    // internal document paths or section numbers (`docs/known-gaps.md` §H7).
    check(
        "no document path or section marker in the guest command",
        command.contains("docs/") || command.contains("§"),
        false,
    )

    // ---------------------------------------------- 2. a healthy guest parses as ok
    val healthy = GuestToolProbe.parse(healthyOutput(), exitCode = 0)
    check("healthy: two verdicts, one per tool", healthy.results.size, 2)
    check("healthy: report ok", healthy.ok, true)
    check("healthy: no launch error", healthy.launchError, null)
    check("healthy: rg version is carried through", healthy.results[0].version, "ripgrep 14.1.1 (rev 4649aa9700)")
    check("healthy: fd search hit is carried through", healthy.results[1].searchOutput, "/etc/passwd")
    check("healthy: rg line says which tool it is", healthy.results[0].describe().startsWith("✓ rg："), true)
    check("healthy: exit code is recorded", healthy.exitCode, 0)

    // ------------------------- 3. a dangling symlink / missing binary is a failure
    val dangling = GuestToolProbe.parse(
        listOf(
            marker("version", "rg", 127, "bash: /usr/local/bin/rg: No such file or directory"),
            marker("search", "rg", 127, "bash: rg: command not found"),
            marker("version", "fd", 0, "fd 10.2.0"),
            marker("search", "fd", 0, "/etc/passwd"),
        ).joinToString("\n"),
    )
    check("dangling rg: report is not ok", dangling.ok, false)
    check("dangling rg: only rg fails", dangling.results.count { it.ok }, 1)
    check("dangling rg: exit code is in the reason", dangling.results[0].reason!!.contains("127"), true)
    check(
        "dangling rg: the tool's own message is in the reason",
        dangling.results[0].reason!!.contains("No such file or directory"),
        true,
    )
    check("dangling rg: a failure line never claims success", dangling.results[0].describe().startsWith("✗ rg："), true)

    // ----------------------------------- 4. exit 0 with no output is still a failure
    val silent = GuestToolProbe.parse(marker("version", "rg", 0, "") + "\n" + marker("version", "fd", 0, "fd 10.2.0"))
    check("silent rg: not ok", silent.ok, false)
    check("silent rg: reason names the empty output", silent.results[0].reason!!.contains("没有任何输出"), true)

    // ------------------------------------ 5. a missing marker line is not a pass
    val missing = GuestToolProbe.parse(marker("search", "fd", 0, "/etc/passwd"))
    check("missing markers: not ok", missing.ok, false)
    check("missing markers: both tools are reported", missing.results.size, 2)
    check(
        "missing markers: the reason says the line is absent",
        missing.results[0].reason!!.contains("没有输出"),
        true,
    )

    // ------------------- 6. a search that runs but finds nothing is a failure too
    // This is `fd . <known-dir>` returning nothing: the binary works, so a
    // version-only probe would call it healthy, while pi's `find` gets an empty list.
    val noMatch = GuestToolProbe.parse(
        listOf(
            marker("version", "rg", 0, "ripgrep 14.1.1"),
            marker("search", "rg", 1, ""),
            marker("version", "fd", 0, "fd 10.2.0"),
            marker("search", "fd", 0, "/etc/passwd"),
        ).joinToString("\n"),
    )
    check("no-match rg: not ok", noMatch.ok, false)
    check("no-match rg: exit code 1 is named", noMatch.results[0].reason!!.contains("真实搜索退出码 1"), true)
    check("no-match rg: version still recorded as evidence", noMatch.results[0].version, "ripgrep 14.1.1")

    val emptySearch = GuestToolProbe.parse(
        listOf(
            marker("version", "rg", 0, "ripgrep 14.1.1"),
            marker("search", "rg", 0, " "),
            marker("version", "fd", 0, "fd 10.2.0"),
            marker("search", "fd", 0, "/etc/passwd"),
        ).joinToString("\n"),
    )
    check("empty search output: not ok", emptySearch.ok, false)
    check(
        "empty search output: the reason distinguishes it from a non-zero exit",
        emptySearch.results[0].reason!!.contains("退出码 0 但没有任何输出"),
        true,
    )

    // --------------------------------- 7. noise, CRLF and tabs inside a detail field
    val noisy = GuestToolProbe.parse(
        "bash: cannot set terminal process group\n" +
            "PI-TOOL-BUT-NOT-A-RESULT\n" +
            healthyOutput().replace("\n", "\r\n") +
            "\nPI-TOOL\tversion\trg\n", // too few fields: not a result, must not overwrite
    )
    check("noise and CRLF do not break parsing", noisy.ok, true)
    check("a truncated marker line is ignored", noisy.results[0].version, "ripgrep 14.1.1 (rev 4649aa9700)")

    val tabby = GuestToolProbe.parse(
        listOf(
            marker("version", "rg", 0, "ripgrep\t14.1.1"),
            marker("search", "rg", 0, "root:x:0:0"),
            marker("version", "fd", 0, "fd 10.2.0"),
            marker("search", "fd", 0, "/etc/passwd"),
        ).joinToString("\n"),
    )
    check("a tab inside the detail is preserved, not truncated", tabby.results[0].version, "ripgrep\t14.1.1")
    check("and it is still a pass", tabby.ok, true)

    // ------------------------------------------- 8. an empty / absent run is a failure
    check("empty output: not ok", GuestToolProbe.parse("").ok, false)
    val launchFailure = GuestToolProbe.Report(emptyList(), launchError = "运行时尚未解包")
    check("launch failure: not ok", launchFailure.ok, false)
    check("launch failure: exactly one line, the reason", launchFailure.describe().size, 1)
    check("launch failure: the line says the probe did not run", launchFailure.describe()[0].startsWith("工具链探针没能运行："), true)
    check("launch failure: no tool line is invented", launchFailure.describe()[0].contains("✓"), false)

    // ----------------------------------------------------- 9. the marker format itself
    check("the separator is a real tab", GuestToolProbe.SEPARATOR, "\t")
    check("the marker contains no separator", GuestToolProbe.MARKER.contains(TAB), false)
    check("the tools are exactly pi's hard dependencies", GuestToolProbe.TOOLS, listOf("rg", "fd"))

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
