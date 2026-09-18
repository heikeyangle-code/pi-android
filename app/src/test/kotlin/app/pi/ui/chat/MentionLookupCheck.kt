package app.pi.ui.chat

// A bare-JVM harness for the `@` lookup's failure classification. Registered in
// `tools/run-app-pure-checks.sh` as `mentions-unavailable`.
//
// Why this is worth pinning: the composer draws nothing for an empty candidate list, which
// is pi's own answer for "no suggestions" (`autocomplete.ts:305`) — and it was also the
// answer for "the runtime is not provisioned", "proot would not start", "the run timed
// out" and "the process was killed". Three of those four are failures the user was never
// told about, and the distinction is a decision about `GuestCommand.Outcome`'s fields that
// nothing else in the repository checks. The most important assertion here is the `null`:
// a non-zero exit code is a **real answer** (fd matched nothing) and must not become a
// notice.

var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

/** One call, with the defaults a healthy run has. */
private fun sentence(
    runtimeReady: Boolean = true,
    timedOut: Boolean = false,
    exitCode: Int? = 0,
    launchError: String? = null,
    stderr: String = "",
): String? = mentionUnavailableSentence(runtimeReady, timedOut, exitCode, launchError, stderr)

fun main() {
    // --- the answer that must stay silent ------------------------------------------
    //
    // fd ran and exited non-zero because it matched nothing: pi's "no candidates"
    // (`autocomplete.ts:196-200`). Turning this into a notice would put a warning on the
    // screen every time a user types an `@` prefix nothing matches.
    check("a non-zero exit is an answer, not a failure", sentence(exitCode = 1) == null)
    check("exit 0 with no output is an answer", sentence(exitCode = 0) == null)
    check("exit 127 (fd missing) is still pi's no-candidates", sentence(exitCode = 127) == null)

    // --- the four failures that used to be silent ----------------------------------
    val unavailable = sentence(runtimeReady = false, exitCode = null)
    check("a runtime that is not provisioned is reported", unavailable != null, "$unavailable")
    check(
        "and it names the runtime rather than fd's exit",
        unavailable?.contains("运行时") == true && unavailable.contains("fd"),
        "$unavailable",
    )

    val launch = sentence(exitCode = null, launchError = "proot: /proc/self/exe: Permission denied")
    check("a runtime that would not start is reported", launch != null, "$launch")
    check(
        "and it carries the launch error as the reason",
        launch?.contains("Permission denied") == true,
        "$launch",
    )
    check(
        "a launch error with a blank message falls back to stderr",
        sentence(exitCode = null, launchError = "  ", stderr = "sh: fd: not found")
            ?.contains("sh: fd: not found") == true,
    )
    check(
        "a launch error with nothing to show says so rather than ending in ()",
        sentence(exitCode = null, launchError = "", stderr = "")?.contains("原因未知") == true,
    )
    check(
        "a long launch error is bounded to one line",
        (sentence(exitCode = null, launchError = "x".repeat(500))?.length ?: 0) < 300,
    )

    check("a timeout is reported", sentence(exitCode = null, timedOut = true)?.contains("超时") == true)
    check("a killed process is reported", sentence(exitCode = null, timedOut = false) != null)

    // --- ordering: the more specific cause wins ------------------------------------
    //
    // A run cannot time out before it starts, but the fields are independent in
    // `GuestCommand.Outcome`, and the sentence must name the cause a user can act on:
    // "the runtime is missing" before "proot failed" before "it timed out".
    check(
        "a missing runtime wins over the others",
        sentence(runtimeReady = false, timedOut = true, exitCode = null, launchError = "boom")
            ?.contains("还没就绪") == true,
    )
    check(
        "a launch failure wins over a timeout",
        sentence(timedOut = true, exitCode = null, launchError = "boom")?.contains("没能启动") == true,
    )

    // Every reported sentence is a message a snackbar can draw: non-blank, one line.
    val all = listOf(
        sentence(runtimeReady = false, exitCode = null),
        sentence(exitCode = null, launchError = "boom"),
        sentence(timedOut = true, exitCode = null),
        sentence(exitCode = null),
    ).filterNotNull()
    check(
        "every sentence is a non-empty single line",
        all.all { it.isNotBlank() && !it.contains('\n') },
    )
    check("the four failure shapes are four distinct sentences", all.toSet().size == all.size)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
