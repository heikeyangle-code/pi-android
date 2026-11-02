package app.pi.engine

// Bare-JVM harness for `EngineCrashRecovery.kt` — "引擎中途死掉之后要不要静默重启".
//
// The failure this pins is not a crash: it is the *screen* the user gets after a crash.
// Every rule here decides whether the transcript stays on screen (silent restart) or is
// replaced by the boot-failure page — and that page's only destructive button deletes
// everything installed inside the guest. Getting this wrong is what produced the
// "两个小时一直弹" loop: a killed engine (LMK) was treated as a broken runtime, the user
// was pushed to 重建运行时, the rebuild threw away their tools, and the next kill started
// it over.
//
// The other direction matters too: a launch-configuration failure (`Unknown option`,
// `Failed to load extension`) must NOT be retried twice, because the second and third
// attempts produce exactly the same exit and only delay the one screen that carries the
// sentence explaining it.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun decide(attempts: Int, code: Int?, stderr: String?) =
    EngineCrashRecovery.decide(attempts, code, stderr)

fun main() {
    val restart = EngineCrashRecovery.Decision.Restart
    val giveUp = EngineCrashRecovery.Decision.GiveUp

    // ============================================ A. the budget is the first rule
    run {
        // A crash with no evidence is the case that has to be retried: the reader
        // cannot tell a kill from a real fault, and a kill is by far the common one.
        check("A1 first crash is restarted", decide(0, null, null), restart)
        check("A2 second crash is restarted", decide(1, 137, null), restart)
        // The budget is a *budget*: one more would be a loop with no end.
        check("A3 the third attempt gives up", decide(EngineCrashRecovery.MAX_AUTO_RESTARTS, null, null), giveUp)
        check("A4 a spent budget stays spent", decide(EngineCrashRecovery.MAX_AUTO_RESTARTS + 5, 137, null), giveUp)
        check("A5 the budget is two", EngineCrashRecovery.MAX_AUTO_RESTARTS, 2)
    }

    // ================================= B. configuration failures are not retried
    run {
        // pi's own sentences, verbatim (the harness in `engine-exit-cause` pins the
        // same strings; here they decide "do not retry" instead of "what to say").
        check("B1 unknown option", decide(0, 1, "error: Unknown option '--nope'"), giveUp)
        check("B2 failed extension", decide(0, 1, "Failed to load extension: /x/index.ts"), giveUp)
        check("B3 @file arguments", decide(0, 1, "@file arguments are not supported"), giveUp)
        check("B4 model not found", decide(0, 1, "Model not found: foo"), giveUp)
        check("B5 missing node module", decide(0, 1, "Error: Cannot find module 'x'"), giveUp)
        // The fence: the same text in a *different* position still matches (it is a
        // substring rule), and a lookalike that is not pi's wording must not.
        check("B6 the marker is matched anywhere", decide(0, 1, "x\nUnknown option --a\nmore"), giveUp)
        check("B7 a lookalike does not count", decide(0, 1, "unknown option (lowercase)"), restart)
    }

    // ========================================= C. signal deaths are the target case
    run {
        // 137 = SIGKILL (LMK), 143 = SIGTERM, 134 = SIGABRT; V8 writes FATAL ERROR on
        // the last one. All three are "the machine did it", so retry.
        check("C1 SIGKILL", decide(0, 137, null), restart)
        check("C2 SIGTERM", decide(0, 143, null), restart)
        check("C3 V8 out of memory", decide(0, 134, "FATAL ERROR: Reached heap limit"), restart)
        check("C4 an unattributed exit code 1", decide(0, 1, null), restart)
        check("C5 blank stderr", decide(0, 1, "   \n "), restart)
    }

    // ==================================================== D. the label is honest
    run {
        check("D1 a code is quoted", EngineCrashRecovery.causeLabel(1, null), "退出码 1")
        check("D2 a signal gives no code", EngineCrashRecovery.causeLabel(null, null), "没有退出码（进程被系统结束）")
        check("D3 stderr without a code says 未知", EngineCrashRecovery.causeLabel(null, "boom"), "退出码 未知")
        // The detailed sentence lives in `EngineExitCause`; this label must never
        // swallow it, so it stays short even when stderr is long.
        check(
            "D4 the label stays one clause with stderr present",
            EngineCrashRecovery.causeLabel(137, "x".repeat(500)),
            "退出码 137",
        )
    }

    // ===================================================== E. the constant itself
    run {
        // The delay is part of the design (long enough to let the killed tree be
        // reaped, short enough that two attempts do not read as a hang), so it is
        // pinned rather than left to drift.
        check("E1 the restart delay is 800 ms", EngineCrashRecovery.RESTART_DELAY_MS, 800L)
        check(
            "E2 two attempts fit inside a user's patience",
            EngineCrashRecovery.MAX_AUTO_RESTARTS * EngineCrashRecovery.RESTART_DELAY_MS <= 2_000L,
            true,
        )
    }

    println(if (failures == 0) "\nharness: OK ($checks checks)" else "\nharness: FAILED ($failures of $checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
