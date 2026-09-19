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
    // The guest runs Node, and Node's native-addon cache publishes with link()+unlink(),
    // which dangles the first time under `--link2symlink` — the flag this recipe must
    // always pass. Its absence is the same "silent on the Node side" shape as the
    // dangling tool link above, which is why it is pinned rather than left to a device.
    check("the guest's native-addon cache is disabled", env["NARB_DISABLE_NATIVE_CACHE"], "1")
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

    // --------------------- 6. the project config directory pi joins onto its cwd
    // Every project-scoped thing pi reads lives under `<cwd>/.pi`: settings.json,
    // skills/, prompts/, themes/, extensions/, SYSTEM.md, APPEND_SYSTEM.md and
    // `pi install -l`'s scope. The app resolves those paths itself (it cannot ask pi
    // for the name over RPC), so the name is a transcription of
    // `CONFIG_DIR_NAME = pkg.piConfig?.configDir || ".pi"` — and a transcription that
    // drifts is a whole directory the app reads and pi never opens. The value is
    // pinned against the pinned engine by `checkProjectConfigDir` in
    // `tools/pi-contract.mjs`; what this section pins is the *shape* the app builds
    // from it, and the containment rule that keeps a future caller from escaping it.
    check("the project config directory is pi's default", PiProjectConfig.DIRECTORY, ".pi")
    check(
        "the project config root is <workspace>/.pi",
        PiProjectConfig.root(java.io.File("$FILES/${GuestWorkspacePath.RELATIVE}")).path,
        "$FILES/${GuestWorkspacePath.RELATIVE}/.pi",
    )
    check(
        "project settings resolve to pi's projectSettingsPath",
        PiProjectConfig.settingsFile(java.io.File("$FILES/${GuestWorkspacePath.RELATIVE}")).path,
        "$FILES/${GuestWorkspacePath.RELATIVE}/.pi/settings.json",
    )
    check(
        "project themes resolve to <workspace>/.pi/themes",
        PiProjectConfig.themesDir(java.io.File("$FILES/${GuestWorkspacePath.RELATIVE}")).path,
        "$FILES/${GuestWorkspacePath.RELATIVE}/.pi/themes",
    )
    // The three resource directories pi discovers by walking (`resource-loader.ts:819-822`).
    check(
        "project extensions / skills / prompts sit under the same root",
        listOf(
            PiProjectConfig.extensionsDir(java.io.File("/ws")).path,
            PiProjectConfig.skillsDir(java.io.File("/ws")).path,
            PiProjectConfig.promptsDir(java.io.File("/ws")).path,
        ),
        listOf("/ws/.pi/extensions", "/ws/.pi/skills", "/ws/.pi/prompts"),
    )
    // `under` is the only path into `.pi` that may ever take a name the user or the
    // model supplied, so the escapes have to be refused here rather than at a call
    // site that forgot. `File(workspace, "../x")` is string concatenation: it never
    // throws, and it silently names a directory outside the workspace.
    check(
        "a relative path inside .pi resolves",
        PiProjectConfig.under(java.io.File("/ws"), "themes/mine.json")?.path,
        "/ws/.pi/themes/mine.json",
    )
    check("a parent-directory escape is refused", PiProjectConfig.under(java.io.File("/ws"), "../outside"), null)
    check(
        "an escape buried inside the path is refused too",
        PiProjectConfig.under(java.io.File("/ws"), "themes/../../outside"),
        null,
    )
    check("a backslash escape is refused", PiProjectConfig.under(java.io.File("/ws"), "..\\..\\outside"), null)
    check(
        "an absolute path is contained rather than followed (it names nothing real, and it is inside)",
        PiProjectConfig.under(java.io.File("/ws"), "/etc/passwd")?.path,
        "/ws/.pi/etc/passwd",
    )
    check("an empty relative path is refused", PiProjectConfig.under(java.io.File("/ws"), ""), null)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}