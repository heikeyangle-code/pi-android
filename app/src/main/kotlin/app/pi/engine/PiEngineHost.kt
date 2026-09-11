package app.pi.engine

import android.content.Context
import app.pi.bridge.DeviceBridgeController
import app.pi.runtime.PiPaths
import app.pi.runtime.ProotCommand
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.RuntimeSelfCheck
import app.pi.rpc.PiLaunchOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * process per cwd keeps the two from sharing state they should not — which is why
 * [restart] closes the old process *before* starting the new one.
 *
 * ## Why this file owns a `restart`
 *
 * pi loads extensions, skills, prompt templates and themes **into the running
 * process** at startup, and there are exactly two ways to make it re-read them:
 * `/reload`, or a fresh process. `/reload` is not reachable for this app:
 *
 *  - it is a *built-in* command (`slash-commands.ts:41`), and built-ins are excluded
 *    from `get_commands` (`docs/rpc.md:853`) and are not dispatched by `prompt`
 *    either — `_tryExecuteExtensionCommand` only looks in the **extension** runner
 *    (`agent-session.ts:1331-1343`);
 *  - there is no `reload` command in the protocol at all: the `RpcCommand` union
 *    (`modes/rpc/rpc-types.ts:20-74`) has none;
 *  - the reload plumbing *is* wired for RPC, but only for extensions:
 *    `rpc-mode.ts:341-343` supplies `reload: async () => { await session.reload() }`
 *    as an extension `commandContextActions` entry, i.e. it is reachable from an
 *    extension command's `ctx.reload()` — which requires an extension the app owns
 *    to already be loaded, and none of the app's shipped extensions expose one.
 *
 * So for this app a restart **is** the reload. Everything that installs or edits a
 * resource therefore has to be able to ask for one, and the answer has to be
 * explicit: a restart kills any in-flight turn. `ExtensionLifecycle` in
 * `app.pi.packages` is the state machine that decides *whether* to ask; this method
 * is the thing it calls, and it refuses on its own authority as well, so a caller
 * that skips the state machine still cannot silently interrupt a turn.
 */
class PiEngineHost(private val appContext: Context) {

    private val paths = PiPaths(
        filesDir = appContext.filesDir,
        nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir),
    )

    private val provisioner = RuntimeProvisioner(paths, appContext.assets)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises boot/restart/shutdown. Two concurrent restarts would leave two
     * proot trees running on one cwd — both writing the same session JSONL — which
     * is precisely the state the class exists to prevent.
     */
    private val lifecycleLock = Mutex()

    /**
     * The engine currently attached, or null when nothing is running.
     *
     * This is the part that keeps a subscriber from holding a **dead** object
     * across a restart. The UI must collect this and rebuild its `PiEngineApi` from
     * the new value rather than keeping the session it got handed at first boot:
     *
     * ```kotlin
     * viewModelScope.launch {
     *     host.session.collect { live ->
     *         session = live            // null while restarting or after a failure
     *         api = live?.let { PiEngineApi(it) }
     *         _state.value = _state.value.copy(engineReady = live != null)
     *     }
     * }
     * ```
     *
     * A `null` value is a real state, not a gap: it is what the UI shows while a
     * restart is in progress, and it is also the honest outcome of a failed restart
     * (a dead `PiEngineSession` must never be left in place pretending to work).
     */
    private val _session = MutableStateFlow<PiEngineSession?>(null)
    val session: StateFlow<PiEngineSession?> = _session.asStateFlow()

    /** Non-suspending read of the same value, for decisions taken in one frame. */
    val live: PiEngineSession? get() = _session.value

    /**
     * True while pi is in the middle of a turn.
     *
     * pi's own state machine drives this: `agent_start` → `Busy`,
     * `agent_settled`/`agent_end` → `Ready` (`PiEngineSession.handle`, from pi's
     * `toJsonEvent` event names). `Starting` is deliberately not "running": nothing
     * has been asked of the engine yet, so interrupting it loses no work.
     */
    val turnRunning: Boolean
        get() = _session.value?.state?.value == PiEngineSession.EngineState.Busy

    /**
     * Why the device bridge failed to start on the last boot, if it did. Kept
     * rather than logged into the void: 设置 → 设备能力 shows it, so a bridge that
     * silently never came up is visible instead of merely absent.
     */
    var lastBridgeError: String? = null
        private set

    /**
     * What the one-time agent-dir migration did, if anything. Kept for the same
     * reason as [lastBridgeError]: a move that silently skipped half its entries
     * looks identical to one that worked, and the difference matters to the user.
     */
    var lastAgentDirMigration: String? = null
        private set

    sealed interface Boot {
        data class Ready(val session: PiEngineSession) : Boot
        data class NeedProvisioning(val progress: RuntimeProvisioner.Step) : Boot
        data class Failed(val message: String, val detail: String? = null) : Boot
    }

    /**
     * The outcome of [restart]. Four shapes, and the two failure shapes are distinct
     * on purpose — "we declined and nothing changed" and "we tried and it broke" need
     * different words and different recovery.
     */
    sealed interface Restart {

        /**
         * A fresh engine is running.
         *
         * @param replaced false when there was no engine to stop (a restart with
         *        nothing running is just a boot, and the caller should say so).
         */
        data class Ok(val session: PiEngineSession, val replaced: Boolean) : Restart

        /**
         * **Nothing was changed.** A turn is in flight and the caller did not pass
         * `allowInterrupt = true`. The old engine is still running and still usable.
         */
        data class RefusedTurnRunning(val detail: String) : Restart

        /**
         * The runtime itself needs provisioning, so this was not a restart.
         * Deliberately refused rather than performed: `RuntimeProvisioner.wipe()`
         * deletes the whole runtime tree, which destroys the guest's
         * `/root/.pi/agent` — where pi keeps `trust.json`, `packages` and installed
         * npm/git packages. A "reload my extensions" button must never do that.
         */
        data class RefusedNeedsProvisioning(val detail: String) : Restart

        /** The old engine is stopped and the new one could not be started. */
        data class Failed(val message: String, val detail: String? = null) : Restart
    }

    /** Where the guest keeps pi's home. Mirrors a desktop install exactly. */
    val guestAgentDir: String = "/root/.pi/agent"

    /**
     * The pre-spawn options the current engine was started with, replayed by
     * [restart].
     *
     * pi's process configuration (`PI_OFFLINE`, `PI_CACHE_RETENTION`,
     * `--system-prompt`, …) is only readable when the process starts, so a
     * restart that dropped these would silently change the user's engine.
     */
    private var launchOptions: PiLaunchOptions = PiLaunchOptions()

    /**
     * @param workspaceProvider returns the host path of the workspace directory
     *        that will become the guest's cwd. Called lazily so first launch can
     *        create one.
     * @param launch pi's pre-spawn configuration — see [PiLaunchOptions]. The
     *        default reproduces the previous hard-wired launch byte for byte.
     */
    suspend fun boot(
        revision: String = RuntimeProvisioner.RUNTIME_REVISION,
        workspaceProvider: () -> File,
        launch: PiLaunchOptions = PiLaunchOptions(),
        // `onStep` must stay LAST: callers pass it as a trailing lambda
        // (`boot { step -> ... }`), and a trailing lambda always binds to the
        // final parameter. Adding `launch` after it silently rebound every such
        // call to `launch` and broke the build — if you add another parameter,
        // put it before this one.
        onStep: (RuntimeProvisioner.Step) -> Unit = {},
    ): Boot = lifecycleLock.withLock {
        bootLocked(revision, workspaceProvider, onStep, launch)
    }

    /**
     * [boot] without the lock, for callers that already hold it.
     *
     * `Mutex` is not reentrant, so `restart` — which holds the lock for the whole
     * stop-then-start sequence — cannot call the public [boot] without deadlocking.
     * Splitting the body out is what keeps the sequence atomic: nothing else can
     * boot or shut down between the old engine dying and the new one appearing.
     */
    private suspend fun bootLocked(
        revision: String,
        workspaceProvider: () -> File,
        onStep: (RuntimeProvisioner.Step) -> Unit,
        launch: PiLaunchOptions,
    ): Boot =
        withContext(Dispatchers.IO) {
            // 0. Rescue the guest's own agent dir *before* provisioning, not after:
            //    `ensureReady` wipes the whole runtime tree when the revision stamp
            //    changes (RuntimeProvisioner.wipe), and `<rootfs>/root/.pi/agent` is
            //    inside it. Migrating first is what makes this a fix rather than a
            //    one-release reprieve.
            lastAgentDirMigration = runCatching { migrateGuestAgentDir() }
                .getOrElse { error -> "agent 目录迁移失败：${error.message ?: error::class.java.simpleName}" }

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

            launchOptions = launch

            val guestCommand = buildString {
                append("exec /opt/node/bin/node ").append(cli)
                append(" --mode rpc")
                append(" --session-dir ").append(guestAgentDir).append("/sessions")
                // pi's pre-spawn flags. The suffix is already shell-quoted because
                // `ProotCommand.build` hands this string to `bash -lc` inside the
                // rootfs; empty when no option is set.
                append(launch.commandLineSuffix())
            }

            val argv = ProotCommand.build(
                paths = paths,
                guestCommand = guestCommand,
                // pi works relative to its cwd and stores sessions per cwd, so the
                // guest must start in the workspace rather than at /.
                cwd = guestWorkspace,
                storage = android.os.Environment.getExternalStorageDirectory(),
                extraBinds = listOf(
                    workspace.absolutePath to guestWorkspace,
                    // The agent dir was the one thing *not* bound, which meant pi read
                    // and wrote `<rootfs>/root/.pi/agent` while the app addressed
                    // `PiPaths.agentDir` (`<files>/pi/.pi/agent`). Every app-side
                    // reader — settings, sessions, and this package's trust.json and
                    // auth.json/models.json — was therefore looking at a directory pi
                    // never touches, and, worse, one that `RuntimeProvisioner.wipe()`
                    // deletes on every runtime revision bump.
                    paths.agentDir.absolutePath to guestAgentDir,
                ),
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
                    // The user-chosen pre-spawn knobs. `PiLaunchOptions.environment`
                    // only ever adds keys — pi tests some of them for presence, so a
                    // "0" would be worse than omitting them.
                ) + launch.environment(),
            )

            runCatching {
                val session = PiEngineSession.spawn(
                    argv = argv,
                    env = env,
                    cwd = paths.runtime,
                    scope = scope,
                )
                // A boot that succeeds while an engine is already attached must not
                // leave the old process alive: two pi processes on one cwd would both
                // append to the same session file and both hold the same settings.
                publish(session)
                Boot.Ready(session)
            }.getOrElse { error ->
                // Fail closed: whoever was holding the previous session must not keep
                // talking to a process that is no longer the engine.
                publish(null)
                Boot.Failed(
                    "无法启动 pi 引擎",
                    "${error::class.java.simpleName}: ${error.message}",
                )
            }
        }

    /**
     * Stop the running engine and bring a fresh one up — the app's substitute for
     * `/reload` (see the class KDoc for why `/reload` is unreachable over RPC).
     *
     * Ordering matters and is not negotiable: the old process is closed **before**
     * the new one is spawned, because two engines on one cwd both write the same
     * session JSONL and both load the same extensions into two processes.
     *
     * @param reason free text for the failure messages, e.g. "安装 npm:foo 后重载扩展".
     * @param allowInterrupt the user has explicitly accepted that the in-flight turn
     *        will be killed. Without it, a running turn is a refusal, never an
     *        interruption.
     */
    suspend fun restart(
        reason: String,
        workspaceProvider: () -> File,
        allowInterrupt: Boolean = false,
        revision: String = RuntimeProvisioner.RUNTIME_REVISION,
        onStep: (RuntimeProvisioner.Step) -> Unit = {},
    ): Restart = lifecycleLock.withLock {
        val current = _session.value

        // Refuse before touching anything. `turnRunning` is pi's own Busy state; a
        // turn that is mid-tool-call can be minutes long, and killing it loses the
        // model call, the tool result and the bash process, not just a spinner.
        if (current != null && current.state.value == PiEngineSession.EngineState.Busy && !allowInterrupt) {
            return@withLock Restart.RefusedTurnRunning(
                "有回合正在运行（$reason）。重启会中断模型调用与正在执行的工具，" +
                    "所以本次没有重启，引擎仍在运行并可以继续使用。",
            )
        }

        // Refuse a restart that would silently become a re-provision. `ensureReady`
        // is only cheap when the stamp matches; otherwise it wipes the whole runtime
        // tree (RuntimeProvisioner.wipe), taking the guest's /root/.pi/agent with it —
        // trust.json, settings.json's `packages`, and every installed npm/git package.
        // That is a different operation from "reload my extensions" and must be a
        // different button.
        if (!stampMatches(revision)) {
            return@withLock Restart.RefusedNeedsProvisioning(
                "运行时需要重新解包（stamp 与 $revision 不一致），这不是一次重启。" +
                    "重新解包会清空 guest 的 /root/.pi/agent，连带删掉 trust.json、" +
                    "settings.json 里已安装的资源包和它们下载的文件。请先走首次启动的" +
                    "boot() 流程，用户需要知道这一点。",
            )
        }

        if (current != null) {
            runCatching { current.close() }
            // Publish null *before* the new engine exists, so a UI collecting the
            // flow shows "restarting" instead of holding the dead object.
            _session.value = null
        }

        // Replay the options the running engine was started with: pi reads its
        // process configuration only at startup, so a restart that dropped them
        // would change the engine behind the user's back.
        return@withLock when (val boot = bootLocked(revision, workspaceProvider, onStep, launchOptions)) {
            is Boot.Ready -> Restart.Ok(boot.session, replaced = current != null)
            is Boot.Failed -> Restart.Failed(
                message = "重启失败：${boot.message}",
                detail = boot.detail,
            )
            is Boot.NeedProvisioning -> Restart.Failed(
                message = "重启失败：运行时尚未就绪",
                detail = boot.progress.label,
            )
        }
    }

    /**
     * Stop the engine and publish null. Idempotent; returns true when something was
     * actually stopped.
     */
    suspend fun shutdown(): Boolean = lifecycleLock.withLock {
        val current = _session.value ?: return@withLock false
        runCatching { current.close() }
        _session.value = null
        true
    }

    fun paths(): PiPaths = paths

    // ------------------------------------------------------------- internals

    /**
     * Move whatever pi has already written into `<rootfs>/root/.pi/agent` into
     * [PiPaths.agentDir], which is about to be bind-mounted **over** that path.
     *
     * ## Why this is needed at all
     *
     * Until now the agent dir was not bound, so pi's sessions, `settings.json`,
     * `auth.json`, extensions and installed packages all live on the rootfs side.
     * Binding the host dir over it, without this step, would make every one of them
     * invisible at once — "绑上去之后旧的会话和设置看起来消失了", which is worse than
     * the inconsistency being fixed.
     *
     * ## Properties, each deliberate
     *
     *  - **Idempotent.** A marker file records the run; a second boot does nothing.
     *  - **Non-destructive.** Entries are *moved*, never overwritten: a target that
     *    already exists is skipped and counted, so the durable copy can never be
     *    clobbered by a stale rootfs copy. The source directory itself is kept, so
     *    it still serves as proot's mount point and as a fallback if the bind fails.
     *  - **Fast.** Source and target are both under `<files>/pi`, so a same-filesystem
     *    rename is a metadata operation — this matters because `npm/` and `git/` can
     *    hold a full `node_modules` tree and a recursive copy would be minutes of
     *    boot time. A cross-device rename fallback copies and then deletes.
     *  - **Logged.** [lastAgentDirMigration] carries the counts, because "moved 0,
     *    skipped 9" and "moved 9" are very different news and look the same from the
     *    outside.
     */
    private fun migrateGuestAgentDir(): String {
        val src = File(paths.rootfs, "root/.pi/agent")
        val dst = paths.agentDir
        if (!src.isDirectory) {
            return "无需迁移：guest 侧没有 ${src.absolutePath}"
        }
        dst.mkdirs()
        val marker = File(dst, MIGRATION_MARKER)
        if (marker.isFile) {
            return "已迁移过：${marker.readText().trim().ifEmpty { marker.absolutePath }}"
        }

        var moved = 0
        var skipped = 0
        var failed = 0
        val failures = mutableListOf<String>()
        for (child in src.listFiles().orEmpty()) {
            val target = File(dst, child.name)
            if (target.exists()) {
                skipped++
                continue
            }
            val renamed = runCatching { child.renameTo(target) }.getOrDefault(false)
            if (renamed) {
                moved++
                continue
            }
            // Cross-device, or a filesystem that refuses the rename.
            val copied = runCatching { child.copyRecursively(target, overwrite = false) }.isSuccess
            if (copied) {
                runCatching { child.deleteRecursively() }
                moved++
            } else {
                failed++
                if (failures.size < 5) failures += child.name
            }
        }

        val summary = buildString {
            append("agent 目录迁移：搬入 $moved 项，跳过 $skipped 项（目标已存在），失败 $failed 项")
            append("（$src → $dst）")
            if (failures.isNotEmpty()) append("；失败项：${failures.joinToString(", ")}")
        }
        // The marker is written only when nothing failed, so a partial move is
        // retried on the next boot instead of being frozen as "done". `skipped` does
        // not block it: a skipped entry already exists in the target, which is the
        // whole point.
        if (failed == 0) {
            runCatching {
                marker.writeText("moved=$moved skipped=$skipped failed=0 at=${System.currentTimeMillis()}\n")
            }
        }
        // Recreate the mount point: moving the children leaves the directory, but a
        // failed move of a top-level entry must not leave proot without a target.
        runCatching { src.mkdirs() }
        return summary
    }

    /**
     * Swap the attached session, closing the previous one.
     *
     * Closing here rather than at each call site is what makes "the UI never holds a
     * dead engine" a property of this class: there is exactly one field that can
     * hold an engine, and every transition goes through this method.
     */
    private fun publish(next: PiEngineSession?) {
        // Not `getAndSet`: every caller is already inside `lifecycleLock`, so a
        // read-then-write cannot interleave, and staying off the extension function
        // keeps this file's imports to the flow types it actually names.
        val previous = _session.value
        _session.value = next
        if (previous != null && previous !== next) {
            runCatching { previous.close() }
        }
    }

    /**
     * Does the unpacked runtime already match [revision]?
     *
     * Mirrors `RuntimeProvisioner.isStampCurrent`, which is private. Both halves are
     * public — `PiPaths.stampFile()` and `RuntimeProvisioner.RUNTIME_REVISION` — so
     * this reads the same two facts without duplicating the unpacking logic. If the
     * two ever disagree the consequence is a refused restart, not a wrong wipe.
     */
    private fun stampMatches(revision: String): Boolean {
        if (!paths.rootfs.isDirectory) return false
        val stamp = paths.stampFile()
        if (!stamp.isFile) return false
        return runCatching { stamp.readText().trim() == revision }.getOrDefault(false)
    }

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

        /**
         * Marker recording that the rootfs-side agent dir was moved into the bind
         * target. Deliberately inside the *target*: the source is inside the
         * volatile runtime tree, so a marker there would vanish exactly when the
         * question "did the move already happen?" matters most.
         */
        private const val MIGRATION_MARKER = ".pi-android-agent-migrated"
    }
}
