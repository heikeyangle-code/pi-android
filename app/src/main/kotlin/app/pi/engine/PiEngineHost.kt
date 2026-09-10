package app.pi.engine

import android.content.Context
import app.pi.bridge.DeviceBridgeController
import app.pi.runtime.PiPaths
import app.pi.runtime.ProotCommand
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.RuntimeSelfCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Brings up one pi engine and hands back a session the UI can talk to.
 *
 * The pipeline is: unpack the runtime (once) → build proot's argv → spawn pi in
 * `--mode rpc` → wrap its stdio in [PiEngineSession]. Nothing here translates the
 * wire format; pi's own JSONL is the protocol (docs/pi-android-app-design.md §7).
 *
 * Engines are per *working directory*, because that is pi's own model: a session
 * belongs to a cwd, and `PI_CODING_AGENT_SESSION_DIR` groups sessions by it. One
 * process per cwd keeps the two from sharing state they should not.
 */
class PiEngineHost(private val appContext: Context) {

    private val paths = PiPaths(
        filesDir = appContext.filesDir,
        nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir),
    )

    private val provisioner = RuntimeProvisioner(paths, appContext.assets)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Why the device bridge failed to start on the last boot, if it did. Kept
     * rather than logged into the void: 设置 → 设备能力 shows it, so a bridge that
     * silently never came up is visible instead of merely absent.
     */
    var lastBridgeError: String? = null
        private set

    sealed interface Boot {
        data class Ready(val session: PiEngineSession) : Boot
        data class NeedProvisioning(val progress: RuntimeProvisioner.Step) : Boot
        data class Failed(val message: String, val detail: String? = null) : Boot
    }

    /** Where the guest keeps pi's home. Mirrors a desktop install exactly. */
    val guestAgentDir: String = "/root/.pi/agent"

    /**
     * @param workspaceProvider returns the host path of the workspace directory
     *        that will become the guest's cwd. Called lazily so first launch can
     *        create one.
     */
    suspend fun boot(
        revision: String = RuntimeProvisioner.RUNTIME_REVISION,
        workspaceProvider: () -> File,
        onStep: (RuntimeProvisioner.Step) -> Unit = {},
    ): Boot = withContext(Dispatchers.IO) {
        // 1. Runtime payload.
        val provisioned = provisioner.ensureReady(revision, onStep)
        provisioned.exceptionOrNull()?.let {
            return@withContext Boot.Failed("运行时解包失败", it.message)
        }

        // 2. Prove the runtime can actually execute before pretending it can.
        //    Cheap, and it converts an inscrutable mid-turn failure into a clear
        //    diagnosis (see RuntimeSelfCheck for why this cannot be assumed).
        val check = RuntimeSelfCheck(paths).run(storage = android.os.Environment.getExternalStorageDirectory())
        if (!check.ok) {
            return@withContext Boot.Failed(
                message = check.detail,
                detail = check.stderr.takeIf { it.isNotBlank() },
            )
        }

        // 3. The device bridge, before pi rather than after it: the `pi-android-bridge`
        //    extension reads its bearer token from the guest filesystem while the
        //    engine loads, so a bridge that came up later would stay invisible to
        //    the model until the next boot. Starting it here also means the token
        //    is re-published on every boot, which is the documented recovery path
        //    after a rootfs re-extract. Idempotent and non-fatal by design — a
        //    device-bridge failure must not cost the user their agent.
        runCatching { DeviceBridgeController.start(appContext) }
            .onFailure { lastBridgeError = it.message }

        // 4. Spawn pi inside the guest.
        val workspace = workspaceProvider()
        workspace.mkdirs()
        val guestWorkspace = guestPathFor(workspace)

        val cli = "$ENGINE_GUEST_ROOT/node_modules/$PI_PACKAGE/dist/cli.js"
        if (!File(paths.rootfs, cli.removePrefix("/")).isFile) {
            return@withContext Boot.Failed(
                "引擎未安装",
                "找不到 $cli —— 打包运行时需要包含 pi 引擎（tools/fetch-runtime.mjs）",
            )
        }

        val guestCommand = buildString {
            append("exec /opt/node/bin/node ").append(cli)
            append(" --mode rpc")
            append(" --session-dir ").append(guestAgentDir).append("/sessions")
        }

        val argv = ProotCommand.build(
            paths = paths,
            guestCommand = guestCommand,
            // pi works relative to its cwd and stores sessions per cwd, so the
            // guest must start in the workspace rather than at /.
            cwd = guestWorkspace,
            storage = android.os.Environment.getExternalStorageDirectory(),
            extraBinds = listOf(workspace.absolutePath to guestWorkspace),
        )
        val env = ProotCommand.environment(
            paths,
            extra = mapOf(
                // pi's file surface is 1:1 with a desktop install, so settings,
                // skills, extensions and themes are interchangeable with one.
                "PI_CODING_AGENT_DIR" to guestAgentDir,
                "PI_CODING_AGENT_SESSION_DIR" to "$guestAgentDir/sessions",
                // The app owns update checks; pi's own would be a surprise network
                // call from inside a phone app.
                "PI_SKIP_VERSION_CHECK" to "1",
                // Where the device-bridge extension finds its bearer token. The
                // extension also probes this path and $PI_CODING_AGENT_DIR on its
                // own, so this is the explicit form of a contract that already
                // works — kept because an env var is visible in `env` output when
                // someone has to debug why the bridge looks absent.
                "PI_ANDROID_BRIDGE_FILE" to "/root/.pi/device-bridge.json",
            ),
        )

        runCatching {
            val session = PiEngineSession.spawn(
                argv = argv,
                env = env,
                cwd = paths.runtime,
                scope = scope,
            )
            Boot.Ready(session)
        }.getOrElse { error ->
            Boot.Failed(
                "无法启动 pi 引擎",
                "${error::class.java.simpleName}: ${error.message}",
            )
        }
    }

    fun paths(): PiPaths = paths

    /**
     * Map a host path under <files> to where it appears inside the guest.
     *
     * The workspace lives in app-private storage for speed (a cwd on `/sdcard`
     * goes through FUSE and makes an `npm install` several times slower), and is
     * bind-mounted into the guest under `/workspace`.
     */
    private fun guestPathFor(host: File): String {
        val root = appContext.filesDir.absolutePath
        val rel = host.absolutePath.removePrefix(root).trimStart('/')
        return if (rel.isEmpty()) "/workspace" else "/workspace/$rel"
    }

    companion object {
        /** Where the packaged engine lands inside the rootfs. */
        const val ENGINE_GUEST_ROOT = "/opt/pi"

        /** The npm package name pi ships as. */
        const val PI_PACKAGE = "@earendil-works/pi-coding-agent"
    }
}
