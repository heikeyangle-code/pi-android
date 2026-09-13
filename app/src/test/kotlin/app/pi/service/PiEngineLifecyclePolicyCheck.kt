package app.pi.service

// A bare-JVM harness for the foreground service's three lifecycle decisions
// (`service/PiEngineLifecyclePolicy.kt`), compiled and run by
// `tools/run-app-pure-checks.sh`.
//
// Why a harness and not a device: all three defects this pins are silent.
//
//  * `START_STICKY` re-creates a service whose process died; the notification then
//    says "PI 正在运行" and the wake lock is re-acquired while no engine exists.
//  * A wake lock held for the service's whole lifetime expires after six hours in
//    the middle of a turn, and keeps the CPU awake for hours while pi is idle.
//  * A countdown polling five times a second shows the same number five times.
//
// Nothing here touches Android (the policy object imports nothing at all), which is
// what lets the truth tables run on this machine. The Android halves — the service
// and the ViewModel — are *not* covered here; what they still need from a device is
// listed in `docs/lifecycle-and-timers.md`.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // ------------------------------------------------------------- start commands
    // `PiEngineService.onStartCommand`: the three inputs a service can be handed.
    run {
        check(
            "A1 the notification's stop action stops",
            PiEngineLifecyclePolicy.startCommand(PiEngineLifecyclePolicy.ACTION_STOP, systemRestart = false),
            PiEngineLifecyclePolicy.StartCommand.StopEngine,
        )
        check(
            "A2 an ordinary start starts",
            PiEngineLifecyclePolicy.startCommand(null, systemRestart = false),
            PiEngineLifecyclePolicy.StartCommand.StartEngine,
        )
        // The defect: this used to post a notification and re-acquire the lock.
        check(
            "A3 a system restart (null intent, no engine in the process) does not claim to run",
            PiEngineLifecyclePolicy.startCommand(null, systemRestart = true),
            PiEngineLifecyclePolicy.StartCommand.StopEngine,
        )
        // A null *action* with a real intent is not a restart: an app that started the
        // service without naming an action still means "run".
        check(
            "A4 an empty action on a real intent still starts",
            PiEngineLifecyclePolicy.startCommand("app.pi.action.SOMETHING_ELSE", systemRestart = false),
            PiEngineLifecyclePolicy.StartCommand.StartEngine,
        )
        // The action string is the service's own constant; it may not drift from here.
        check(
            "A5 the policy owns the action string",
            PiEngineLifecyclePolicy.ACTION_STOP,
            "app.pi.action.STOP_ENGINE",
        )
    }

    // --------------------------------------------------------------- the wake lock
    // The four combinations of "is something CPU-bound happening".
    run {
        check("B1 idle: no lock", PiEngineLifecyclePolicy.shouldHoldWakeLock(booting = false, turnRunning = false), false)
        check("B2 booting: lock", PiEngineLifecyclePolicy.shouldHoldWakeLock(booting = true, turnRunning = false), true)
        check("B3 a turn: lock", PiEngineLifecyclePolicy.shouldHoldWakeLock(booting = false, turnRunning = true), true)
        check("B4 both: lock", PiEngineLifecyclePolicy.shouldHoldWakeLock(booting = true, turnRunning = true), true)
        // The property that matters on a phone: an engine that is merely alive is not a
        // reason to keep the CPU awake. That is why the signature has no "the service is
        // running" input at all — the lock is a consequence of work, not of existence.
        // (Deliberately not asserted by reflection: this harness runs without
        // kotlin-reflect, by design — see the classpath note in the run script.)
    }

    // ----------------------------------------------------------- the service itself
    run {
        check(
            "C1 the user's switch off wins over everything",
            PiEngineLifecyclePolicy.shouldServiceRun(keepAlive = false, engineAttached = true, bootInProgress = true),
            false,
        )
        check(
            "C2 a boot in progress keeps the service up before any engine exists",
            PiEngineLifecyclePolicy.shouldServiceRun(keepAlive = true, engineAttached = false, bootInProgress = true),
            true,
        )
        check(
            "C3 an attached engine keeps it up",
            PiEngineLifecyclePolicy.shouldServiceRun(keepAlive = true, engineAttached = true, bootInProgress = false),
            true,
        )
        // The defect: after pi exited on its own, the service used to keep running with
        // its notification. Nothing is left to keep alive.
        check(
            "C4 nothing attached and nothing booting: the service should not exist",
            PiEngineLifecyclePolicy.shouldServiceRun(keepAlive = true, engineAttached = false, bootInProgress = false),
            false,
        )
    }

    // ----------------------------------------------------------------- the countdown
    run {
        check("D1 nothing left: do not sleep at all", PiEngineLifecyclePolicy.nextCountdownDelayMs(0), 0L)
        check("D2 a negative remainder is the same", PiEngineLifecyclePolicy.nextCountdownDelayMs(-5), 0L)
        check("D3 exactly on a boundary: one full second", PiEngineLifecyclePolicy.nextCountdownDelayMs(1000), 1000L)
        check("D4 just past a boundary: to the next one", PiEngineLifecyclePolicy.nextCountdownDelayMs(1001), 1L)
        check("D5 half a second over: the last half second", PiEngineLifecyclePolicy.nextCountdownDelayMs(1500), 500L)
        check("D6 inside the final second: the rest of it", PiEngineLifecyclePolicy.nextCountdownDelayMs(999), 999L)
        check("D7 the last millisecond is a millisecond", PiEngineLifecyclePolicy.nextCountdownDelayMs(1), 1L)
        // A whole day must still wake up within a second: `coerceIn`'s upper bound.
        check(
            "D8 a clock that jumped cannot freeze the countdown",
            PiEngineLifecyclePolicy.nextCountdownDelayMs(86_400_000),
            PiEngineLifecyclePolicy.MAX_COUNTDOWN_SLEEP_MS,
        )

        // The loop property, swept: the sleeps sum to exactly the deadline (so the
        // countdown resolves on time rather than up to one tick late) and there is
        // exactly one wake-up per displayed second — one per second, not five.
        var worstWakeUps = 0
        var exact = true
        var bounded = true
        for (deadline in 1..5_000) {
            var remaining = deadline.toLong()
            var slept = 0L
            var wakeUps = 0
            while (remaining > 0) {
                val delay = PiEngineLifecyclePolicy.nextCountdownDelayMs(remaining)
                if (delay <= 0 || delay > PiEngineLifecyclePolicy.MAX_COUNTDOWN_SLEEP_MS) bounded = false
                slept += delay
                remaining -= delay
                wakeUps++
                if (wakeUps > 5_001) break
            }
            if (slept != deadline.toLong()) exact = false
            // ceil(ms / 1000): the number of distinct numbers the display shows.
            val seconds = (deadline + 999) / 1000
            if (wakeUps != seconds) exact = false
            if (wakeUps > worstWakeUps) worstWakeUps = wakeUps
        }
        check("E1 every deadline 1..5000 ms is reached exactly on time", exact, true)
        check("E2 and no delay is ever outside 1..1000 ms", bounded, true)
        check("E3 so a 5 s countdown costs 5 wake-ups (the old fixed 200 ms poll cost 25)", worstWakeUps, 5)
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
