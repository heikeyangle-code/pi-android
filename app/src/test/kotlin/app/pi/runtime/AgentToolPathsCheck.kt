package app.pi.runtime

// A bare-JVM harness for the two "where does the guest actually see this file"
// invariants that runtime provisioning depends on. Android-free on purpose: it
// compiles and runs with the Kotlin stdlib alone, because `PiRuntime.kt` imports
// nothing but `java.io.File`. Registered in `tools/run-app-pure-checks.sh` as
// `agent-tool-paths`.
//
// What it pins, and why each one is worth pinning:
//
//  1. **The two tool directories are different.** `PiPaths.agentBinDir()` is the
//     bind source — the engine, the package commands and (since the terminal became
//     "a shell where you type `pi`") `PtyLauncher` all bind it over the guest's
//     `/root/.pi/agent`; `PiPaths.rootfsAgentBinDir()` is the copy that answers only
//     in the window between `RuntimeProvisioner.wipe()` and the next successful
//     provision. Neither contains the other, which is precisely why `installTool`
//     writes both and `ensureToolsVisible` repairs both: a `/usr/local/bin/<tool>`
//     that dangles is indistinguishable from "the tool was never installed", and that
//     failure is silent. This is the assertion that would have caught it.
//  2. **`/usr/bin/git` is reachable.** git is installed into the rootfs at the
//     ordinary Debian paths and is addressed by PATH, not by an agent-dir symlink —
//     so if `/usr/bin` ever leaves the PATH this class pins, `git` becomes
//     invisible to every launch path at once while remaining installed.
//  3. **The workspace's guest spelling comes from one function.** Four launch paths
//     bind one host directory and each used to work out the guest path itself; the
//     rule now lives in `GuestWorkspacePath` (compiled into this harness, which is
//     why the file is listed alongside `PiRuntime.kt`). The terminal's *different*
//     mount of the same directory is pinned too — it is deliberate, and a change to
//     either spelling has to be a deliberate change here.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val FILES = "/data/user/0/app.pi/files"

private fun paths() = PiPaths(
    filesDir = java.io.File(FILES),
    nativeLibDir = java.io.File("/data/app/app.pi/lib/arm64"),
)

fun main() {
    val p = paths()

    // ------------------------------------- 1. the two directories a guest path can mean
    check("the bind source is <files>/pi/.pi/agent", p.agentDir.path, "$FILES/pi/.pi/agent")
    check("the tools' bind-source dir is agentDir/bin", p.agentBinDir().path, "$FILES/pi/.pi/agent/bin")
    check(
        "the rootfs copy is the shadowed <rootfs>/root/.pi/agent",
        p.rootfsAgentBinDir().path,
        "$FILES/pi/runtime/rootfs/root/.pi/agent/bin",
    )
    // The whole point: they are not the same directory, and neither is inside the
    // other, so no single install satisfies both launch paths.
    check(
        "the two tool directories are distinct",
        p.agentBinDir() == p.rootfsAgentBinDir(),
        false,
    )
    check(
        "the bind source does not contain the rootfs copy",
        p.rootfsAgentBinDir().path.startsWith(p.agentBinDir().path + "/"),
        false,
    )
    check(
        "the rootfs copy does not contain the bind source",
        p.agentBinDir().path.startsWith(p.rootfsAgentBinDir().path + "/"),
        false,
    )
    // The guest spelling the /usr/local/bin symlink uses must be the bind source.
    // This is the string RuntimeProvisioner.GUEST_AGENT_BIN spells; it is private
    // there, so it is re-spelled here deliberately — a mismatch is a dangling link.
    check(
        "the guest spelling /root/.pi/agent/bin maps to the bind source",
        p.agentBinDir().path,
        p.agentDir.resolve("bin").path,
    )

    // ------------------------------------ 2. /usr/bin is on PATH, so git is reachable
    val env = ProotCommand.environment(p)
    val path = env["PATH"] ?: ""
    check("PATH contains /usr/bin (where git is installed)", path.split(":").contains("/usr/bin"), true)
    check("PATH contains /usr/local/bin", path.split(":").contains("/usr/local/bin"), true)
    check("HOME is /root", env["HOME"], "/root")

    // ------------------------------------ 3. the CA bundle is named, not looked up
    check("the CA bundle path is the one the payload installs", ProotCommand.GUEST_CA_BUNDLE, "/etc/ssl/certs/ca-certificates.crt")
    check("git is told where the CA bundle is", env["GIT_SSL_CAINFO"], ProotCommand.GUEST_CA_BUNDLE)
    check("the other TLS consumers are told too", env["SSL_CERT_FILE"], ProotCommand.GUEST_CA_BUNDLE)

    // ------------------------- 4. proot's link2symlink store exists and is in the rootfs
    // proot turns a guest `link()` into a symlink stored in `PROOT_L2S_DIR`, and
    // returns -ENOENT for the guest when it cannot open that directory. It never
    // creates it. So this is not a tidiness check: a store that is not there is
    // every hard link in the guest failing, which is what stops `dpkg` from
    // replacing a file that is already installed.
    check("PROOT_L2S_DIR is the store under the rootfs", env["PROOT_L2S_DIR"], "${p.rootfs.path}/.l2s")
    check(
        "the store is inside the rootfs, because its intermediates are bound into the guest",
        p.l2s.path.startsWith(p.rootfs.path + "/"),
        true,
    )
    check("PROOT_TMP_DIR is the app's own tmp", env["PROOT_TMP_DIR"], p.tmp.path)
    val argv = ProotCommand.build(p, "true", "/root", null)
    check(
        "the store is bound at its own absolute path",
        argv.zipWithNext().any { (flag, bind) -> flag == "-b" && bind == "${p.l2s.path}:${p.l2s.path}" },
        true,
    )

    // --------------------- 5. the workspace's guest spelling has one implementation
    // Four launch paths bind this one host directory (the engine, the terminal's
    // pty, the package commands and the `@` completion's `fd`), and each used to
    // spell the guest path itself. Two shipped bugs came out of that shape - a
    // session file path and an agent-dir bind - so the rule now lives in
    // `GuestWorkspacePath` and every call site reads it. These checks pin the rule
    // and, just as importantly, pin the *disagreement* the rule cannot remove:
    // the terminal mounts the same directory at a different guest path.
    check("the workspace lives under the files directory", GuestWorkspacePath.RELATIVE, "pi/workspaces/workspace-1")
    check(
        "the workspace directory is <files>/pi/workspaces/workspace-1",
        GuestWorkspacePath.host(java.io.File(FILES)).path,
        "$FILES/pi/workspaces/workspace-1",
    )
    val engineSpelling = GuestWorkspacePath.under(FILES, "$FILES/${GuestWorkspacePath.RELATIVE}")
    check(
        "the engine's guest spelling of the workspace",
        engineSpelling,
        "/workspace/pi/workspaces/workspace-1",
    )
    check("the files directory itself is the mount root", GuestWorkspacePath.under(FILES, FILES), "/workspace")
    check(
        "a path outside the files directory keeps its own shape (matches guestPathFor)",
        GuestWorkspacePath.under(FILES, "/sdcard/ws"),
        "/workspace/sdcard/ws",
    )
    check(
        "the terminal mounts the same directory at its own path",
        GuestWorkspacePath.TERMINAL_GUEST_PATH,
        "/workspace",
    )
    check(
        "the two spellings really are different for today's workspace",
        engineSpelling == GuestWorkspacePath.TERMINAL_GUEST_PATH,
        false,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}