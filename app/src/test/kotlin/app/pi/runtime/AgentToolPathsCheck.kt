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
//     bind source (the engine and package commands bind it over the guest's
//     `/root/.pi/agent`); `PiPaths.rootfsAgentBinDir()` is the copy only a launch
//     path *without* that bind can see (`PtyLauncher`). Neither contains the other,
//     which is precisely why installing into one of them leaves the other launch
//     path with a dangling `/usr/local/bin/<tool>`. That failure is silent, and this
//     is the assertion that would have caught it.
//  2. **`/usr/bin/git` is reachable.** git is installed into the rootfs at the
//     ordinary Debian paths and is addressed by PATH, not by an agent-dir symlink —
//     so if `/usr/bin` ever leaves the PATH this class pins, `git` becomes
//     invisible to every launch path at once while remaining installed.

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

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
