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
//   3. the settle rule (directional: a refused restart may never leave the switch saying
//      proroot, and the off direction never rolls back — the defect reported as
//      「打开之后关不掉」), the interrupt rule per direction, and the invariant "a switch
//      that says proroot means the running engine is on proroot", over every (value
//      before, result) combination;
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

/**
 * One row of §6's table: what was written, the verdict it was answered with (null when the
 * probe was not consulted), the step the object produced, and the runtime the next launch
 * would use — the settings row's 「运行时（实际生效）」.
 */
private class SwitchRow(
    val written: Boolean,
    val verdict: Boolean?,
    val step: Step,
    val runtime: String,
)

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
    // **`Refused` 两个方向都落在 `false`**，而旧规则是「退回写入前的值」。这一格是用户
    // 当场报的缺陷：「这开关打开之后就没关闭不了了」。关掉那一次写入被拒（回合在跑、旧代码
    // 的 `allowInterrupt = false` 让 `PiEngineHost.restart` 直接拒绝）之后，旧规则把开关
    // **写成 `true`** —— 用户写下的 `false` 被这次回滚吞掉，屏幕上就是「关不掉」。
    // 现在守的是唯一要守的那一面：被拒绝的重启绝不能留下一个说 proroot 的开关。
    check(
        "Refused never leaves the switch saying proroot — the off direction lands",
        !RuntimeSwitchAction.settle(nowEnabled = true, result = RestartResult.Refused) &&
            !RuntimeSwitchAction.settle(nowEnabled = false, result = RestartResult.Refused),
    )
    check(
        "关掉时被拒也不回滚：用户写下的 false 必须留下来（「关不掉」那条缺陷的判据）",
        RuntimeSwitchAction.settle(nowEnabled = false, result = RestartResult.Refused) == false,
    )
    check(
        "打开时被拒才回滚到关闭",
        RuntimeSwitchAction.settle(nowEnabled = true, result = RestartResult.Refused) ==
            RuntimeSwitchAction.rollbackValue(true),
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
    // 反过来钉住「回滚只属于打开那一侧」：关掉这一侧的任何结论都不许等于 `rollbackValue(false)`
    // （也就是都不许把开关写成 true）。
    check(
        "关掉这一侧永不回滚成开（三种结论逐一）",
        RestartResult.entries.all { result ->
            RuntimeSwitchAction.settle(nowEnabled = false, result) != RuntimeSwitchAction.rollbackValue(false)
        },
    )
    // 打断规则：关掉必须当场落地（允许中断），打开不许为了说 proroot 去杀一个回合。
    check(
        "关掉可以中断正在跑的回合，打开时不可以",
        RuntimeSwitchAction.allowInterrupt(nowEnabled = false) &&
            !RuntimeSwitchAction.allowInterrupt(nowEnabled = true),
    )

    // ------------------------------------------- 4. 方向性的「全有或全无」
    // 把「运行时」也写成一个布尔：true = proroot，false = proot。
    // 引擎在三种结论下分别落在哪里：
    //   Ok      → 用户要的那个（就是写入后的开关值）
    //   Refused → 没动，还是写入前那个
    //   Failed  → 没有引擎（null）
    //
    // 不变量是**方向性**的：**只要开关说 proroot，引擎就必须在 proroot 上**（或没有引擎）。
    // 反过来那一格——开关已经关掉、引擎还在 proroot 上跑——是**过渡态**，不是谎话：开关是用户
    // 的意愿（「从现在起别用它」），「运行时（实际生效）」那一行说的是引擎此刻在跑什么，两者
    // 本来就可以不同（探针未通过时那一行也正是靠这个分工说真话，见 `RuntimeSwitchAction` 类 KDoc）。
    //
    // 旧的不变量是「只要还有引擎在跑，开关的值就必须等于它的运行时」，它在**关掉被拒**这一格上
    // 与用户的当场裁决冲突：「这开关打开之后就没关闭不了了」——旧规则把开关回滚成 `true`，
    // 用户写下的 `false` 被这次回滚吞掉。现在 [RuntimeSwitchAction.allowInterrupt] 让关掉这一侧
    // 可以中断正在跑的回合（所以那条路基本到不了 `Refused`），而回滚只属于打开那一侧（§3）。
    val combinations = buildList {
        for (before in listOf(false, true)) {
            for (result in RestartResult.entries) add(before to result)
        }
    }
    val falseProrootClaims = combinations.mapNotNull { (before, result) ->
        val written = !before
        val settled = RuntimeSwitchAction.settle(written, result)
        val engine: Boolean? = when (result) {
            RestartResult.Ok -> written
            RestartResult.Refused -> before
            RestartResult.Failed -> null
        }
        // 开关说 proroot 而引擎在 proot 上 ⇒ 唯一不可接受的那一格。
        if (settled && engine == false) "written=$written $result → switch=$settled engine=$engine" else null
    }
    check(
        "不变量（方向性）：只要开关说 proroot，引擎就在 proroot 上（或没有引擎）",
        falseProrootClaims.isEmpty(),
        falseProrootClaims.joinToString("\n  "),
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

    // ----------------------------------------------- 6. 整张表：一格只有一个答案
    //
    // The promise has three rows, and pinning each of them separately above still lets a
    // future edit trade one row for another. This section states the whole table once, and
    // adds the two properties a table makes visible and per-row checks do not:
    //
    //   - **only `on + passed` reaches proroot**, so no cell can switch to a runtime its own
    //     verdict did not choose (the "never a hybrid" half);
    //   - **`on + refused` is not a rollback and not a restart**: the user's intent stands
    //     (the switch keeps the value they wrote — only a *refused restart in the ON
    //     direction* rolls it back, section 3), the engine is left where it is (proot, in
    //     the case this row exists for), and the reason is the status row's
    //     (`RuntimeSelection.status().summary` → `proot（探针未通过：…）`, pinned by
    //     `ProrootCheck` §2 and `ProrootStatusTextCheck`). This object's answer for that cell
    //     is `Idle`: nothing to do, because there is nothing to move.
    //
    // `runtime` is the runtime the *next* launch would use after the write — the value the
    // settings row 「运行时（实际生效）」 shows. It is the harness's model of the three rows,
    // written out literally so a change to any cell has to change this file.
    val table = listOf(
        SwitchRow(written = false, verdict = null, step = RuntimeSwitchAction.onWrite(false), runtime = "proot"),
        SwitchRow(written = true, verdict = true, step = RuntimeSwitchAction.afterProbe(true, true), runtime = "proroot"),
        SwitchRow(written = true, verdict = false, step = RuntimeSwitchAction.afterProbe(true, false), runtime = "proot"),
    )
    check("T1 off → proot", table[0].step == Step.RestartingToProot && table[0].runtime == "proot")
    check("T2 on + passed → proroot", table[1].step == Step.RestartingToProroot && table[1].runtime == "proroot")
    check(
        "T3 on + refused → proot, with nothing restarted (the reason is the row's)",
        table[2].step == Step.Idle && table[2].runtime == "proot",
    )
    check("T4 only the on+passed cell reaches proroot", table.count { it.runtime == "proroot" } == 1)
    check(
        "T5 proroot is only ever reached by restarting onto it",
        table.filter { it.runtime == "proroot" }.all { it.step == Step.RestartingToProroot },
    )
    check(
        "T6 the probe is consulted exactly on the rows where the switch was written on",
        table.all { (it.verdict != null) == it.written },
    )
    check(
        "T7 no cell switches anywhere without the verdict to back it",
        table.none { it.verdict == false && it.step == Step.RestartingToProroot },
    )
    check(
        "T8 turning the switch on never restarts before the probe answers",
        RuntimeSwitchAction.onWrite(true) == Step.Probing,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
