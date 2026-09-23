package app.pi.bridge

// A bare-JVM harness for the guest-to-host path mapping behind A3
// (`bridge/GuestImageBytes.kt` -> `bridge/GuestPathMapping.kt`).
//
// **RUN BY CI since 2026-09-11**, by `tools/run-app-pure-checks.sh` from the `pure-checks`
// job in `.github/workflows/ci.yml` (no Android SDK, no Gradle, no AAPT2 - the closure
// imports nothing from `android.*`). Before that it was decoration: `:rpc:test` never
// touched it, `assembleRelease` does not compile `app/src/test`, and `tools/typecheck.sh`
// compiles `app/src/main/kotlin` only.
//
// The first CI run reported ClassNotFoundException for this class. That was a symptom, not
// the cause: the compiler itself had thrown (its own classpath was missing
// kotlinx-coroutines), and the runner of that day had no guard for "the compile produced
// nothing", so it reported a missing class instead. The `package` line was never missing -
// this file has always declared it, further down.
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

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** The roots as they look on a real install, spelled out so the diff is obvious. */
private fun roots(workspaceHost: String? = "$ROOTFS/workspace/pi/workspaces/workspace-1") =
    GuestPathRoots(
        workspaceBase = "$ROOTFS/workspace",
        rootfs = ROOTFS,
        agentDir = "$ROOTFS/root/.pi/agent",
        workspaceHost = workspaceHost,
        storage = "/storage/emulated/0",
    )

private const val FILES = "/data/user/0/app.pi/files"
private const val ROOTFS = "$FILES/pi/runtime/rootfs"

private fun candidates(path: String, roots: GuestPathRoots = roots()): List<String> =
    GuestPathMapping.candidates(listOf(path), roots)

fun main() {
    val rootfs = ROOTFS
    val wsBase = "$rootfs/workspace"
    val agent = "$rootfs/root/.pi/agent"
    val ws = "$wsBase/pi/workspaces/workspace-1"

    // ------------------------------------------------ guest /workspace, two spellings
    // Under the engine, guest /workspace mirrors `PiPaths.workspaceBase`
    // (`<rootfs>/workspace`; it was `<filesDir>` until 2026-09-23); under the terminal tab
    // it IS the workspace directory. Both are tried, engine first — and the rootfs
    // candidate is the same string as the engine's, so it de-duplicates away.
    check(
        "a /workspace link tries the engine spelling, then the terminal spelling",
        candidates("/workspace/chart.png"),
        listOf("$wsBase/chart.png", "$ws/chart.png"),
    )
    // The engine's own cwd spelling of a file inside the workspace.
    check(
        "the engine's full workspace spelling resolves to the same host file",
        candidates("/workspace/pi/workspaces/workspace-1/chart.png").first(),
        "$wsBase/pi/workspaces/workspace-1/chart.png",
    )
    // pi's cwd is the workspace, so a relative link is workspace-relative.
    check(
        "a relative link is the workspace's, not the base's",
        candidates("chart.png"),
        listOf("$ws/chart.png", "$wsBase/chart.png", "chart.png"),
    )

    // ------------------------------------------------ the pi-grounded case: /tmp paste
    // pi's TUI writes /tmp/pi-clipboard-<uuid>.png and inserts the path as text
    // (src/modes/interactive/interactive-mode.ts:2934-2946). `/tmp` stopped being a bind on
    // 2026-09-23 (see `GuestRecipe`: a proroot bind sourced from an app-private directory
    // breaks `mkstemp`/`scandir` in its whole subtree), so the guest's `/tmp` is now the
    // rootfs's — and that candidate must therefore come first, not second.
    check(
        "/tmp resolves inside the rootfs, not to a bound host directory",
        candidates("/tmp/pi-clipboard-abc.png"),
        listOf("$rootfs/tmp/pi-clipboard-abc.png", "/tmp/pi-clipboard-abc.png"),
    )

    // ------------------------------------------------ agent dir, shared storage
    // The agent dir **is** the guest's `/root/.pi/agent` since 2026-09-23 (a rootfs path,
    // no bind), so both candidates are the same string and de-duplicate to one.
    check(
        "/root/.pi/agent resolves to the agent dir itself, once",
        candidates("/root/.pi/agent/sessions/x/y.png"),
        listOf("$agent/sessions/x/y.png"),
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
        listOf("$wsBase/chart.png"),
    )
    check("an empty path yields nothing", candidates(""), emptyList<String>())
    // Percent-escapes survive the markdown parser, so both spellings are tried and the
    // result is de-duplicated.
    check(
        "two spellings of one link are both tried, without duplicates",
        GuestPathMapping.candidates(listOf("/workspace/a%20b.png", "/workspace/a b.png"), roots()),
        listOf("$wsBase/a%20b.png", "$ws/a%20b.png", "$wsBase/a b.png", "$ws/a b.png"),
    )
    // Guard the constant the mapping and the health payload both spell.
    check("the workspace mount point is /workspace", GuestPathMapping.WORKSPACE, "/workspace")

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
