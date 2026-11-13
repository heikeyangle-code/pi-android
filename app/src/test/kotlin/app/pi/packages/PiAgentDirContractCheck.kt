package app.pi.packages

// `PiAgentDirContract` 的纯逻辑检查（裸 JVM 跑，见 tools/run-app-pure-checks.sh）。
// App 与引擎必须对「guest 里 pi 的 agent 目录」达成一致；这里验证那条判据本身。

import app.pi.packages.PiAgentDirContract

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
    // ------------------------------------- agent dir contract (the move, not a bind)
    //
    // Until 2026-09-23 the agreement was "every launch path binds the *same* host
    // directory over guest /root/.pi/agent". A guest command that omitted the bind wrote a
    // different settings.json than the running engine read, exited 0, and changed nothing —
    // so the agreement was a checked value. The directory itself **is** the guest path now
    // (`<rootfs>/root/.pi/agent`), so there is nothing to bind, and the only way left to
    // break the agreement is to bind *something else* onto that guest path.
    check("the contract guest path is pi's guest agent dir", PiAgentDirContract.GUEST_PATH, "/root/.pi/agent")
    check("session dir is <agentDir>/sessions", PiAgentDirContract.sessionDir("/root/.pi/agent"), "/root/.pi/agent/sessions")
    check(
        "the launch paths' bind list is clean",
        PiAgentDirContract.misleadingAgentDirBinds(
            listOf("/files/pi/workspace-1" to "/workspace/pi/workspaces/workspace-1"),
        ),
        emptyList<Pair<String, String>>(),
    )
    check(
        "binding anything onto the agent path is reported",
        PiAgentDirContract.misleadingAgentDirBinds(
            listOf("/files/pi/runtime/rootfs/root/.pi/agent" to "/root/.pi/agent"),
        ),
        listOf("/files/pi/runtime/rootfs/root/.pi/agent" to "/root/.pi/agent"),
    )
    check(
        "so is binding something *under* it",
        PiAgentDirContract.misleadingAgentDirBinds(listOf("/files/x" to "/root/.pi/agent/sessions")),
        listOf("/files/x" to "/root/.pi/agent/sessions"),
    )
    check(
        "a bind to another guest path is irrelevant",
        PiAgentDirContract.misleadingAgentDirBinds(listOf("/files/pi/.pi/agent" to "/root/.pi")),
        emptyList<Pair<String, String>>(),
    )
    check(
        "and a guest path that merely starts with the same letters does not match",
        PiAgentDirContract.misleadingAgentDirBinds(listOf("/files/x" to "/root/.pi/agentX")),
        emptyList<Pair<String, String>>(),
    )
    if (failures > 0) {
        println("\nharness: FAILED ($failures)")
        kotlin.system.exitProcess(1)
    }
    println("\nharness: OK (all checks passed)")
}
