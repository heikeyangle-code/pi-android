// A bare-JVM harness for the guest-to-host path mapping behind A3
// (`bridge/GuestImageBytes.kt` -> `bridge/GuestPathMapping.kt`).
//
// **NOT RUN BY CI.** Nothing executes this file today:
//   * `.github/workflows/ci.yml` runs `:rpc:test` only, never `:app:test`;
//   * `assembleRelease` does not compile `app/src/test` at all;
//   * `tools/typecheck.sh` compiles `app/src/main/kotlin` only.
// It therefore has the same standing as `packages/PackagesPureLogicCheck.kt`: a
// hand-run check that must be run by whoever touches the mapping. Until someone
// wires it into CI, a green build says nothing about this file, and a compile
// error in it will not be caught by anything either.
//
// Run it by hand:
//
//   cd /root/pi-android
//   KOTLINC_CP="$(find build/typecheck/kotlinc -name '*.jar' | tr '\n' ':')"
//   G="$HOME/.gradle/caches/modules-2/files-2.1"
//   STDLIB="$(find "$G/org.jetbrains.kotlin/kotlin-stdlib/2.2.21" -name 'kotlin-stdlib-2.2.21.jar')"
//   java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 \
//     -classpath "$STDLIB" -d /tmp/bridgecheck/out \
//     app/src/test/kotlin/app/pi/bridge/GuestPathMappingCheck.kt \
//     app/src/main/kotlin/app/pi/bridge/GuestPathMapping.kt
//   java -cp "/tmp/bridgecheck/out:$STDLIB" app.pi.bridge.GuestPathMappingCheckKt
//
// What it pins, and why it is worth pinning: the candidate *order* is the only
// part of A3 with a security argument (see `GuestPathMapping`'s KDoc). Wrong order
// means an image silently renders the wrong file — the guest's `/etc/hosts` shown
// from the phone instead of from the rootfs, or a guest `/tmp/x.png` resolved to a
// different directory that merely shares the name.
package app.pi.bridge

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** The three roots as they look on a real install, spelled out so the diff is obvious. */
private fun roots(workspaceHost: String? = "/data/user/0/app.pi/files/pi/workspaces/workspace-1") =
    GuestPathRoots(
        filesDir = "/data/user/0/app.pi/files",
        rootfs = "/data/user/0/app.pi/files/pi/runtime/rootfs",
        tmp = "/data/user/0/app.pi/files/pi/runtime/tmp",
        agentDir = "/data/user/0/app.pi/files/pi/.pi/agent",
        workspaceHost = workspaceHost,
        storage = "/storage/emulated/0",
    )

private fun candidates(path: String, roots: GuestPathRoots = roots()): List<String> =
    GuestPathMapping.candidates(listOf(path), roots)

fun main() {
    val files = "/data/user/0/app.pi/files"
    val rootfs = "$files/pi/runtime/rootfs"
    val tmp = "$files/pi/runtime/tmp"
    val agent = "$files/pi/.pi/agent"
    val ws = "$files/pi/workspaces/workspace-1"

    // ------------------------------------------------ guest /workspace, two spellings
    // Under the engine, guest /workspace mirrors <filesDir>; under the terminal tab it
    // IS the workspace directory. Both are tried, engine first, rootfs last.
    check(
        "a /workspace link tries the engine spelling, then the terminal spelling, then the rootfs",
        candidates("/workspace/chart.png"),
        listOf("$files/chart.png", "$ws/chart.png", "$rootfs/workspace/chart.png"),
    )
    // The engine's own cwd spelling of a file inside the workspace.
    check(
        "the engine's full workspace spelling resolves to the same host file",
        candidates("/workspace/pi/workspaces/workspace-1/chart.png").first(),
        "$files/pi/workspaces/workspace-1/chart.png",
    )
    // pi's cwd is the workspace, so a relative link is workspace-relative.
    check(
        "a relative link is the workspace's, not <filesDir>'s",
        candidates("chart.png"),
        listOf("$ws/chart.png", "$files/chart.png", "chart.png"),
    )

    // ------------------------------------------------ the pi-grounded case: /tmp paste
    // pi's TUI writes /tmp/pi-clipboard-<uuid>.png and inserts the path as text
    // (src/modes/interactive/interactive-mode.ts:2934-2946). /tmp is its own bind.
    check(
        "/tmp binds to runtime/tmp and never to <rootfs>/tmp",
        candidates("/tmp/pi-clipboard-abc.png"),
        listOf("$tmp/pi-clipboard-abc.png", "$rootfs/tmp/pi-clipboard-abc.png"),
    )

    // ------------------------------------------------ agent dir, shared storage
    check(
        "/root/.pi/agent binds to the app-side agent dir first",
        candidates("/root/.pi/agent/sessions/x/y.png"),
        listOf("$agent/sessions/x/y.png", "$rootfs/root/.pi/agent/sessions/x/y.png"),
    )
    check(
        "/sdcard maps onto shared storage, keeping the literal spelling as a fallback",
        candidates("/sdcard/DCIM/a.jpg"),
        listOf("/storage/emulated/0/DCIM/a.jpg", "/sdcard/DCIM/a.jpg"),
    )
    check(
        "/storage/emulated/0 maps onto the same directory",
        candidates("/storage/emulated/0/Download/b.png"),
        listOf("/storage/emulated/0/Download/b.png"),
    )
    check(
        "/storage/self/primary is the third spelling of shared storage",
        candidates("/storage/self/primary/Download/b.png"),
        listOf("/storage/emulated/0/Download/b.png", "/storage/self/primary/Download/b.png"),
    )

    // ------------------------------------------------ the safety rule, in both directions
    // An unknown absolute path is a GUEST path, so the rootfs wins. The phone's own
    // /etc/hosts exists too, and rendering it would show content the model never named.
    check(
        "an unknown absolute path resolves to the rootfs before the host's own file",
        candidates("/etc/hosts"),
        listOf("$rootfs/etc/hosts", "/etc/hosts"),
    )
    // A host path that really is a host path: the rootfs probe cannot match, so the
    // literal path is what is left. It must still be present, or every app-side path
    // would be unreachable.
    check(
        "a host absolute path keeps the literal spelling as the last candidate",
        candidates("$ws/out.png").last(),
        "$ws/out.png",
    )

    // ------------------------------------------------ robustness
    // The runtime may not be unpacked yet; a missing workspace must not throw.
    check(
        "no workspace host still yields candidates",
        candidates("/workspace/chart.png", roots(workspaceHost = null)),
        listOf("$files/chart.png", "$rootfs/workspace/chart.png"),
    )
    check("an empty path yields nothing", candidates(""), emptyList<String>())
    // Percent-escapes survive the markdown parser, so both spellings are tried and the
    // result is de-duplicated.
    check(
        "two spellings of one link are both tried, without duplicates",
        GuestPathMapping.candidates(listOf("/workspace/a%20b.png", "/workspace/a b.png"), roots()),
        listOf("$files/a%20b.png", "$ws/a%20b.png", "$rootfs/workspace/a%20b.png", "$files/a b.png", "$ws/a b.png", "$rootfs/workspace/a b.png"),
    )
    // Guard the constant the mapping and the health payload both spell.
    check("the workspace mount point is /workspace", GuestPathMapping.WORKSPACE, "/workspace")

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
