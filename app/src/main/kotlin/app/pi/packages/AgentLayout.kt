package app.pi.packages

import android.content.Context
import app.pi.engine.PiEngineHost
import app.pi.runtime.PiPaths
import java.io.File

/**
 * Where pi's user files actually are, on the host and in the guest.
 *
 * This is the one place in the app that has to know about a mismatch that is easy
 * to get wrong and silent when you do.
 *
 * ## The mismatch, verified from code
 *
 * `PiEngineHost` sets `PI_CODING_AGENT_DIR=/root/.pi/agent` (`PiEngineHost.kt:51`,
 * `:123`) and binds **only the workspace** into the guest (`:116`). So inside the
 * guest, `/root/.pi/agent` is a real directory of the rootfs:
 *
 *     <files>/pi/runtime/rootfs/root/.pi/agent      <- what pi reads and writes
 *
 * whereas `PiPaths.agentDir` is
 *
 *     <files>/pi/.pi/agent                          <- a *host-side mirror*
 *
 * `docs/extension-compatibility.md:523-524` says the agent dir is bind-mounted.
 * It is not — `PiEngineHost.kt:109-117` binds `workspace.absolutePath to
 * guestWorkspace` and nothing else. `DeviceBridgeController` already works around
 * this by writing to **both** locations and commenting the host one as "guest path
 * only if the engine binds it" (`DeviceBridgeController.kt:182-188`, `:230-233`).
 * This class makes that workaround explicit and reusable instead of incidental.
 *
 * ## Why it matters for this layer
 *
 *  - `trust.json` is read by pi at `<agentDir>/trust.json`
 *    (`trust-manager.ts:212-214`). Written only to the mirror, trust would never
 *    take effect. Written only to the rootfs, it would be **destroyed by the next
 *    runtime update**: `RuntimeProvisioner.wipe()` deletes `paths.runtime`
 *    wholesale (`RuntimeProvisioner.kt:85-91`) and only re-creates an empty
 *    `/root/.pi/agent` (`:185`). So it must be written to both, and the rootfs
 *    copy re-published after provisioning.
 *  - `pi install` writes `packages` into `<agentDir>/settings.json` and installs
 *    into `<agentDir>/npm`, both inside the volatile tree. The app cannot fix that
 *    from here (it would take the engine bind), but it can and does say so instead
 *    of reporting a durable install.
 *
 * ## What the app should do instead, exactly
 *
 * One line in `PiEngineHost.boot` makes the mirror authoritative and removes the
 * whole class of bug. It is not this package's file to edit, so it is reported
 * rather than applied:
 *
 * ```kotlin
 * val argv = ProotCommand.build(
 *     paths = paths,
 *     guestCommand = guestCommand,
 *     cwd = guestWorkspace,
 *     storage = android.os.Environment.getExternalStorageDirectory(),
 *     extraBinds = listOf(
 *         workspace.absolutePath to guestWorkspace,
 *         paths.agentDir.absolutePath to guestAgentDir,   // <- add this
 *     ),
 * )
 * ```
 *
 * Until that exists, [TrustRepository] keeps both copies in step.
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

    /** pi's `PI_CODING_AGENT_DIR` inside the guest (`PiEngineHost.kt:51`). */
    val guestAgentDir: String = "/root/.pi/agent"

    /**
     * `$HOME` inside the guest. `ProotCommand.environment` pins it to `/root`
     * (`PiRuntime.kt:142`), and `hasTrustRequiringProjectResources` compares
     * against `$HOME/.agents/skills` to decide which skills directory is the
     * trusted user one (`trust-manager.ts:186-188`), so the value has to come from
     * there rather than from Android's notion of home.
     */
    val guestHome: String = "/root"

    /**
     * The guest's own home, on the host filesystem. proot does not translate paths
     * for us: `--rootfs=<rootfs>` makes `<files>/pi/runtime/rootfs` appear as `/`,
     * so guest `/root/.pi/agent` is exactly [agentTruthDir].
     */
    val agentTruthDir: File = File(paths.rootfs, "root/.pi/agent")

    /**
     * The durable host copy. `PiPaths.agentDir` is `<files>/pi/.pi/agent`
     * (`PiRuntime.kt:19`); it survives a runtime re-extract because it is not under
     * `paths.runtime`.
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
