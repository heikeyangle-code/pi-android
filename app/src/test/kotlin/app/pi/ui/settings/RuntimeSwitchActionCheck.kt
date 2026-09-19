package app.pi.ui.settings

// A bare-JVM harness for `RuntimeSwitchAction.kt`, the pure "the switch was just
// written — what happens next?" decision behind `app.runtime.proroot`.
//
// Why it exists: this decision is the whole of the user's request
// (「只要在开关里点开开关，就自动重启切换」), and every way it can go wrong is
// silent — a half switch-over, a probe result thrown away, or the switch and the
// engine ending up on different runtimes (「开关说 proroot，跑的是 proot」). The
// code that *executes* it (`PiSessionViewModel`) imports Android and Compose and
// cannot be compiled here, so the rule was extracted into a pure object and is
// executed by this file for real, not asserted about a copy of it.
//
// What is pinned:
//
//   1. the first step after a write, both directions;
//   2. the step after the probe, including the stale case (the user flipped the
//      switch back while the probe was still running);
//   3. the settle rule, and — the part that matters — the invariant "the switch
//      value and the engine's runtime are the same answer whenever an engine is
//      running", over every (value before, result) combination;
//   4. the status line, because it is the only thing on screen that says the
//      probe (tens of seconds) is running, and a missing line is invisible.
//
// `RuntimeSwitchAction.kt` is deliberately Android-free (no imports at all). If
// it ever grows one, this compile fails — which is the point.
//
//   runtime-switch   app.pi.ui.settings.RuntimeSwitchActionCheckKt

import app.pi.ui.settings.RuntimeSwitchAction.RestartResult
import app.pi.ui.settings.RuntimeSwitchAction.Step
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

fun main() {
    // ------------------------------------------------------- 1. 写入后的第一步
    check(
        "turning the switch on probes first",
        RuntimeSwitchAction.onWrite(nowEnabled = true) == Step.Probing,
    )
    check(
        "turning the switch off restarts straight away (nothing to measure)",
        RuntimeSwitchAction.onWrite(nowEnabled = false) == Step.RestartingToProot,
    )

    // ------------------------------------------------------- 2. 探针结论之后
    check(
        "a passing probe restarts the engine onto proroot",
        RuntimeSwitchAction.afterProbe(nowEnabled = true, probePassed = true) == Step.RestartingToProroot,
    )
    check(
        "a refusing probe restarts nothing — the engine is already on proot",
        RuntimeSwitchAction.afterProbe(nowEnabled = true, probePassed = false) == Step.Idle,
    )
    check(
        "a stale write (switch flipped off while the probe ran) restarts nothing",
        RuntimeSwitchAction.afterProbe(nowEnabled = false, probePassed = true) == Step.Idle,
    )
    check(
        "a stale write that also failed the probe restarts nothing",
        RuntimeSwitchAction.afterProbe(nowEnabled = false, probePassed = false) == Step.Idle,
    )
    // 只有 probePassed 能决定用不用 proroot：文件在、开关开着都不够。
    check(
        "a refusing probe can never reach proroot, whatever the switch says",
        listOf(true, false).none {
            RuntimeSwitchAction.afterProbe(nowEnabled = it, probePassed = false) == Step.RestartingToProroot
        },
    )

    // ---------------------------------------------------------- 3. settle 规则
    check(
        "Ok keeps the written value",
        RuntimeSwitchAction.settle(nowEnabled = true, result = RestartResult.Ok) &&
            !RuntimeSwitchAction.settle(nowEnabled = false, result = RestartResult.Ok),
    )
    check(
        "Refused rolls the switch back to the value before the write",
        !RuntimeSwitchAction.settle(nowEnabled = true, result = RestartResult.Refused) &&
            RuntimeSwitchAction.settle(nowEnabled = false, result = RestartResult.Refused),
    )
    check(
        "Failed keeps the written value — no engine is running to disagree with it",
        RuntimeSwitchAction.settle(nowEnabled = true, result = RestartResult.Failed) &&
            !RuntimeSwitchAction.settle(nowEnabled = false, result = RestartResult.Failed),
    )
    check(
        "the rollback target is the negation of the written value, both directions",
        RuntimeSwitchAction.rollbackValue(true) == false &&
            RuntimeSwitchAction.rollbackValue(false) == true,
    )
    check(
        "Refused settles on exactly the rollback value, not a second rule",
        RestartResult.entries.all { result ->
            result != RestartResult.Refused ||
                listOf(true, false).all { now ->
                    RuntimeSwitchAction.settle(now, result) == RuntimeSwitchAction.rollbackValue(now)
                }
        },
    )

    // ------------------------------------------- 4. 全有或全无（开关 == 引擎）
    // 把「运行时」也写成一个布尔：true = proroot，false = proot。
    // 引擎在三种结论下分别落在哪里：
    //   Ok      → 用户要的那个（就是写入后的开关值）
    //   Refused → 没动，还是写入前那个
    //   Failed  → 没有引擎（null）
    // 不变量：只要还有引擎在跑，开关的值就必须等于它的运行时。
    val combinations = buildList {
        for (before in listOf(false, true)) {
            for (result in RestartResult.entries) add(before to result)
        }
    }
    val violations = combinations.mapNotNull { (before, result) ->
        val written = !before
        val engine: Boolean? = when (result) {
            RestartResult.Ok -> written
            RestartResult.Refused -> before
            RestartResult.Failed -> null
        }
        val settled = RuntimeSwitchAction.settle(written, result)
        if (engine != null && settled != engine) {
            "before=$before written=$written $result → switch=$settled engine=$engine"
        } else {
            null
        }
    }
    check(
        "no combination leaves the switch and the running engine on different runtimes",
        violations.isEmpty(),
        violations.joinToString("\n  "),
    )
    // 反空泛：两个方向 × 三种结论都在上面那张表里，且三种结论各自都出现过。
    check(
        "the invariant was checked over every (direction, outcome) combination",
        combinations.size == 6 && combinations.map { it.second }.toSet() == RestartResult.entries.toSet(),
        combinations.joinToString(),
    )

    // ------------------------------------------------------------ 5. 状态行
    check(
        "Idle shows the real reading, not a sentence",
        RuntimeSwitchAction.statusLine(Step.Idle) == null,
    )
    check(
        "every in-flight step has a status line, and every Idle one does not",
        Step.entries.all { it.inFlight == (RuntimeSwitchAction.statusLine(it) != null) },
        Step.entries.joinToString { "$it inFlight=${it.inFlight} line=${RuntimeSwitchAction.statusLine(it)}" },
    )
    check(
        "the probing status line says the probe is running",
        RuntimeSwitchAction.statusLine(Step.Probing)?.contains("探针") == true &&
            RuntimeSwitchAction.PROBING_STATUS.contains("正在"),
    )
    check(
        "the two restart lines name the runtime they are switching to",
        RuntimeSwitchAction.statusLine(Step.RestartingToProot)?.let {
            it.contains("proot") && !it.contains("proroot")
        } == true &&
            RuntimeSwitchAction.statusLine(Step.RestartingToProroot)?.contains("proroot") == true,
    )
    check(
        "every status line is one bounded line (the row draws maxLines = 1)",
        Step.entries.mapNotNull { RuntimeSwitchAction.statusLine(it) }
            .all { it.none { c -> c == '\n' || c == '\r' } && it.length <= 40 },
        Step.entries.mapNotNull { RuntimeSwitchAction.statusLine(it) }.joinToString { "${it.length}:$it" },
    )
    check(
        "the status lines are distinct, so the row cannot show the wrong one",
        Step.entries.mapNotNull { RuntimeSwitchAction.statusLine(it) }.toSet().size ==
            Step.entries.count { it.inFlight },
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
