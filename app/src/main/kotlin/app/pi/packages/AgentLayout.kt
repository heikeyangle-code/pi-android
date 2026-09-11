package app.pi.packages

import android.content.Context
import app.pi.engine.PiEngineHost
import app.pi.runtime.PiPaths
import java.io.File

/**
 * Where pi's user files actually are, on the host and in the guest.
 *
 * ## The bind that decides everything
 *
 * `PiEngineHost` binds **two** things into the guest (`PiEngineHost.kt:285-294`) — the
 * workspace, and `PiPaths.agentDir` over `/root/.pi/agent` — and pins
 * `PI_CODING_AGENT_DIR` to that same guest path (`:302-303`). So while the engine
 * runs:
 *
 *     <files>/pi/.pi/agent                     bound to   /root/.pi/agent  (pi reads)
 *     <files>/pi/runtime/rootfs/root/.pi/agent            shadowed by that bind
 *
 * This file used to describe the opposite (the rootfs copy as "what pi reads", the
 * durable copy as a mirror nothing reads), and the inversion is load-bearing rather
 * than cosmetic:
 *
 *  - [agentMirrorDir] is the **authoritative** agent dir. It is what pi reads and
 *    writes, it is what the app's own settings store addresses
 *    (`ui/PiSessionViewModel.kt:325-328`), and it survives a runtime re-extract
 *    (`RuntimeProvisioner.wipe()` deletes `paths.runtime` wholesale,
 *    `RuntimeProvisioner.kt:85-91`, and only re-creates an empty
 *    `/root/.pi/agent`).
 *  - [agentTruthDir] is the rootfs copy. It is a real directory and this package
 *    still keeps it in step, but pi does not read it while the bind is in place. It
 *    is the fallback for a run whose proot argv omits the bind — which is why every
 *    process started from here must add it, and why [GuestCommand] does:
 *    [PiPackageService]'s `pi install` had been writing a `settings.json` the engine
 *    never read.
 *
 * Server-side note for whoever finishes the job: the names [agentTruthDir] and
 * [agentMirrorDir] predate the bind and are kept only because three files reference
 * them. Read them as "rootfs copy" and "engine's agent dir".
 */
class AgentLayout(
    context: Context,
    /** Host workspace directory; the guest spelling is derived from it. */
    val hostWorkspace: File,
) {

    val paths = PiPaths(
        filesDir = context.filesDir,
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
    )

    /** pi's `PI_CODING_AGENT_DIR` inside the guest (`PiEngineHost.kt:170`, `:302`). */
    val guestAgentDir: String = PiAgentDirContract.GUEST_PATH

    /**
     * `$HOME` inside the guest. `ProotCommand.environment` pins it to `/root`
     * (`PiRuntime.kt:142`), and `hasTrustRequiringProjectResources` compares
     * against `$HOME/.agents/skills` to decide which skills directory is the
     * trusted user one (`trust-manager.ts:186-188`), so the value has to come from
     * there rather than from Android's notion of home.
     */
    val guestHome: String = "/root"

    /**
     * The rootfs copy, `<rootfs>/root/.pi/agent`. **Shadowed by the engine's bind**
     * while the engine runs (see the class note); kept in step as a fallback, and it
     * is what `RuntimeProvisioner` re-creates after a wipe.
     */
    val agentTruthDir: File = File(paths.rootfs, "root/.pi/agent")

    /**
     * The durable, **authoritative** agent dir: `PiPaths.agentDir`
     * (`<files>/pi/.pi/agent`). `PiEngineHost` binds this over the guest's
     * `/root/.pi/agent` (`:285-294`), so it is the directory pi actually reads.
     */
    val agentMirrorDir: File = paths.agentDir

    /**
     * The guest spelling of the workspace, computed **by the same rule** as
     * `PiEngineHost.guestPathFor` (`:162-166`): strip the `<files>` prefix, then
     * mount under `/workspace`. `PiSessionViewModel` duplicates the same string
     * (`:331`) and calls the mapping "a contract, not an implementation detail".
     *
     * It matters here because `pi install -l` writes `<cwd>/.pi/settings.json` and
     * `hasTrustRequiringProjectResources(cwd)` inspects `<cwd>/.pi`, both using the
     * **guest** cwd — which is also the key pi writes into `trust.json`.
     */
    val guestWorkspace: String = run {
        val root = paths.home.parentFile?.absolutePath ?: paths.home.absolutePath
        val rel = hostWorkspace.absolutePath.removePrefix(root).trimStart('/')
        if (rel.isEmpty()) "/workspace" else "/workspace/$rel"
    }

    /** The one bind this layer needs when it runs a command in the workspace. */
    fun workspaceBind(): Pair<String, String> = hostWorkspace.absolutePath to guestWorkspace

    /**
     * The bind that makes [agentMirrorDir] the agent dir a guest command sees, in the
     * exact shape `PiEngineHost` uses (`PiEngineHost.kt:294`:
     * `paths.agentDir.absolutePath to guestAgentDir`). Pair this with
     * [ensureAgentMirrorDir] and with the environment in
     * [app.pi.packages.PiAgentDirContract]: the bind and the variable are the two
     * halves of the same statement.
     *
     * Without it a command writes whichever `settings.json`, `npm/` tree and
     * `extensions/` sit in the rootfs while the engine reads the bound directory —
     * an install that reports success and changes nothing.
     */
    fun agentDirBind(): Pair<String, String> = agentMirrorDir.absolutePath to guestAgentDir

    /**
     * Create the durable agent dir if it is missing, so [agentDirBind] has a source:
     * proot will not bind a host path that does not exist, and a package command can
     * run before any engine boot has created it (`PiEngineHost.migrateGuestAgentDir`
     * normally does, `:449-460`).
     */
    fun ensureAgentMirrorDir(): Boolean = agentMirrorDir.isDirectory || agentMirrorDir.mkdirs()

    /** The engine's cli.js, exactly as `PiEngineHost` computes it (`:95`). */
    val guestEngineCli: String
        get() = "${PiEngineHost.ENGINE_GUEST_ROOT}/node_modules/${PiEngineHost.PI_PACKAGE}/dist/cli.js"

    /** Node inside the guest (`RuntimeProvisioner.extractNode`, `:115`). */
    val guestNode: String get() = "/opt/node/bin/node"

    /** `<cwd>/.pi/settings.json`, resolved to a host path for a guest workspace. */
    fun hostProjectConfigDir(): File = File(hostWorkspace, ".pi")

    /** True when the runtime is unpacked far enough to run anything. */
    fun runtimeReady(): Boolean = paths.rootfs.isDirectory && paths.prootBinary().isFile && paths.prootLoader().isFile

    /** True when the engine cli.js the guest will exec exists on the host side. */
    fun engineInstalled(): Boolean = File(paths.rootfs, guestEngineCli.removePrefix("/")).isFile
}
