package app.pi.engine

import android.content.Context
import android.util.Log
import app.pi.bridge.DeviceBridgeController
import app.pi.runtime.BootAudit
import app.pi.runtime.GuestTreeReaper
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.PiPaths
import app.pi.runtime.ProrootExecProbe
import app.pi.runtime.ProrootLaunchHandle
import app.pi.runtime.ProrootProbe
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.RuntimeSelection
import app.pi.runtime.RuntimeSelfCheck
import app.pi.runtime.WorkspaceStore
import app.pi.rpc.PiLaunchOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    // The lock is process-wide (the companion's `PROCESS_LOCK`), not a field of this
    // instance: the resource it protects — one cwd, one session JSONL, one guest agent
    // dir — is process-wide, and a host is created per ViewModel.
    private val lifecycleLock: Mutex get() = PROCESS_LOCK

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
     * The proroot launch behind the engine currently attached, when there is one.
     *
     * Kept so that stopping the engine can stop **its guest tree**: proroot rejects
     * `--kill-on-exit` (`docs/proroot-research.md` §4.2) and `PiEngineSession.close()`
     * destroys only the direct child, so without this every restart or exit would
     * orphan `bash` and a Node engine, each still holding the session file and the
     * agent directory. Null on proot, where the launcher takes its children with it.
     */
    @Volatile
    private var engineLaunch: ProrootLaunchHandle? = null

    /**
     * Freeze the engine's guest tree **now**, while the launcher is still alive.
     *
     * Must be called **before** whatever stops the engine: `closeAfterSettling()` ends in
     * `process.destroy()`, and killing the launcher reparents its children to init, after
     * which nothing can distinguish them from any other process
     * ([GuestTreeReaper.capture]). The capture is a `/proc` read, so a caller that is
     * about to await a settle can afford it.
     */
    private fun captureEngineTree(launch: ProrootLaunchHandle?): GuestTreeReaper.Snapshot? {
        val handle = launch ?: return null
        val pid = handle.launcherPid ?: return null
        return runCatching { GuestTreeReaper.capture(pid, handle.launcherStartTime) }.getOrNull()
    }

    /**
     * Turn "the proroot engine ended with 126/127" into bounded, readable evidence.
     *
     * ## What it does, in order
     *
     *  1. re-runs the dynamic-binary stage through the **same** proroot launch shape
     *     ([ProrootProbe.autopsy]) — `/usr/bin/env true` and
     *     `/opt/node/bin/node --version`, with a timeout and an output cap;
     *  2. keeps the result in memory ([lastProrootForensics]) and in
     *     [PiPaths.prorootEngineForensics] so the report and the settings row can read it
     *     after this host (and its session) is gone;
     *  3. logs it, because a device with no UI access still has logcat;
     *  4. counts the failure in the runtime's three-strike budget **only when the engine
     *     really exited** — the spawn-time failure is already counted by the caller's own
     *     `recordProrootFailure`, and counting one launch twice would abandon proroot after
     *     two attempts instead of three.
     *
     * ## What it deliberately does not do
     *
     * It never runs on a healthy start (only a launch-failure exit code gets here), it never
     * quotes anything but the probe's own marker lines and proroot's `[proroot] …` lines
     * ([ProrootExecProbe.launcherLinesFrom]), and it never throws: a broken autopsy is a
     * line of evidence saying so, not a second failure.
     */
    private fun autopsyProrootFailure(
        selection: RuntimeSelection,
        storage: File?,
        exitCode: Int?,
        launcherSource: String?,
        cwd: String? = null,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ) {
        val lines = runCatching {
            // The engine's own `-w` and its own extra binds, not the probe's shape: a guest
            // working directory that does not exist or a bind the launcher refuses is a
            // launch-shape failure the gate cannot see, and this is the only place that can
            // reproduce it without slowing a healthy start.
            ProrootProbe.autopsy(
                paths = paths,
                storage = storage,
                exitCode = exitCode,
                launcherSource = launcherSource,
                cwd = cwd,
                extraBinds = extraBinds,
            )
        }.getOrElse { error ->
            listOf(
                "proroot 引擎启动失败取证没能运行：" +
                    "${error::class.java.simpleName}: ${error.message}",
            )
        }
        lastProrootForensics = lines
        lines.forEach { Log.w(TAG, "proroot 引擎失败取证：$it") }
        runCatching {
            val file = paths.prorootEngineForensics()
            file.parentFile?.mkdirs()
            file.writeText(lines.joinToString("\n") + "\n")
        }
        if (ProrootExecProbe.isLaunchFailure(exitCode)) {
            selection.recordProrootFailure(
                "引擎启动失败（退出码 $exitCode）：${lines.firstOrNull().orEmpty()}",
            )
        }
    }

    /**
     * Stop a captured engine tree, without blocking the caller.
     *
     * Asynchronous on purpose: `restart` is driven from a UI coroutine, and a reap that
     * waits for TERM then KILL would block the main thread for up to five seconds
     * (`GuestTreeReaper.reapInBackground`). Everything is best effort and logged — by
     * the time this runs the engine has already stopped, so the log line and the
     * diagnostic report are the only places the outcome can go.
     */
    private fun reapEngineTree(
        captured: GuestTreeReaper.Snapshot?,
        launch: ProrootLaunchHandle?,
        reason: String,
    ) {
        val handle = launch
        if (captured == null) {
            if (handle != null) {
                Log.w(TAG, "引擎停止（$reason）：没有可回收的 proroot guest 树（未捕获到 launcher）")
                handle.deleteConfig()
            }
            return
        }
        runCatching {
            GuestTreeReaper.reapInBackground(captured) { report ->
                when {
                    report.skippedReason != null ->
                        Log.i(TAG, "引擎停止（$reason）未回收 guest 树：${report.skippedReason}")

                    !report.clean ->
                        Log.w(TAG, "引擎停止（$reason）后仍有 guest 进程存活：${report.survivors.joinToString()}")

                    else -> Log.i(TAG, "引擎停止（$reason）：已回收 guest 进程树（pid ${captured.rootPid}）")
                }
                handle?.deleteConfig()
            }
        }.onFailure { handle?.deleteConfig() }
    }

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

    /**
     * Why the durable boot audit ([BootAudit]) could not write, or null when it did (or
     * had nothing to write because this boot is not the first after an upgrade).
     *
     * Kept for the same reason as [lastBridgeError]: the audit exists to be evidence
     * after an update, and a write that failed silently is exactly the case where the
     * user would later say "no evidence was recorded" and mean it. It is also logged at
     * write time, so the failure is readable in `logcat` even if nothing reads this field.
     */
    @Volatile
    var lastBootAuditError: String? = null
        private set

    /**
     * The **engine-failure autopsy** of the most recent proroot launch that ended with
     * `126`/`127`, or null when there has not been one.
     *
     * ## Why the number alone was not enough
     *
     * On the reference device the whole report was `引擎以退出码 126 退出。` — a sentence that
     * names no binary, no stage and no next step, produced by a runtime whose own components
     * write exactly that information to stderr (`[proroot] child: stage=… target=… errno=…`)
     * and by a shell that names the file it could not execute. Neither line reached a surface
     * a user could read: `EngineExitCause` did not know the shapes, and the boot had already
     * returned `Ready` by the time the process died.
     *
     * So a proroot engine that dies with a *launch* code ([ProrootExecProbe.isLaunchFailure])
     * gets one bounded re-run of the dynamic-binary stage under the same proroot launch shape
     * ([ProrootProbe.autopsy]), and this field holds what it found. It is written to
     * [PiPaths.prorootEngineForensics] as well, because the failure outlives the session that
     * produced it (`DiagnosticsReport` reads the file).
     *
     * Null is a real state — "no proroot launch failure to explain" — not "not collected yet".
     */
    @Volatile
    var lastProrootForensics: List<String>? = null
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
         * Deliberately refused rather than performed, because it would turn a "reload my
         * extensions" tap into minutes of payload extraction — the payloads whose bytes
         * changed are extracted at engine boot, not by a restart call.
         *
         * It is **not** refused because of data loss: per-payload provisioning extracts
         * over the tree and deletes nothing the user installed. The one operation that
         * still deletes the guest environment is the explicit repair path
         * (`RuntimeProvisioner.ensureReady(rebuild = true)`), which no restart ever takes.
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
     *        create one. The default reads [WorkspaceStore] — the process-wide
     *        "which workspace" authority — so a caller that does not care about the
     *        workspace cannot accidentally pin an old one; a caller that *is*
     *        moving the engine into a specific directory (a workspace switch) passes
     *        that directory explicitly, because then the engine must follow the
     *        target and not whatever the settings file currently says.
     * @param launch pi's pre-spawn configuration — see [PiLaunchOptions]. The
     *        default reproduces the previous hard-wired launch byte for byte.
     */
    suspend fun boot(
        // Derived from the packaged payloads, not hand-written: see
        // `RuntimeProvisioner.packagedRevision`. A default that read the constant
        // instead is exactly how a changed payload would keep every device on the
        // tree it already unpacked, silently.
        revision: String = RuntimeProvisioner.packagedRevision(appContext.assets),
        workspaceProvider: () -> File = { WorkspaceStore.currentHost(appContext) },
        launch: PiLaunchOptions = PiLaunchOptions(),
        /**
         * The explicit repair decision: delete the volatile runtime tree and extract
         * every payload again. Forwarded to `RuntimeProvisioner.ensureReady`, which is
         * the only thing in this app that may delete the guest environment, and which
         * never reaches that code path from a digest comparison — see its KDoc.
         *
         * Pass true only when the tree is known to be broken or incomplete (a failed
         * boot-time self-check, a missing rootfs, a user asking for a repair). The cost
         * is everything the user installed inside the guest; the workspace, sessions,
         * settings and credentials are outside the deleted tree and survive.
         */
        rebuild: Boolean = false,
        // `onStep` must stay LAST: callers pass it as a trailing lambda
        // (`boot { step -> ... }`), and a trailing lambda always binds to the
        // final parameter. Adding `launch` after it silently rebound every such
        // call to `launch` and broke the build — if you add another parameter,
        // put it before this one.
        onStep: (RuntimeProvisioner.Step) -> Unit = {},
    ): Boot = lifecycleLock.withLock {
        bootLocked(revision, workspaceProvider, onStep, launch, rebuild)
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
        rebuild: Boolean = false,
    ): Boot =
        withContext(Dispatchers.IO) {
            // 0. Rescue the guest's own agent dir *before* provisioning, not after.
            //    This used to be load-bearing because `ensureReady` wiped the whole
            //    runtime tree whenever the revision stamp changed, and
            //    `<rootfs>/root/.pi/agent` is inside it. Provisioning is per payload now
            //    and deletes nothing, so the migration is no longer the difference
            //    between keeping and losing that directory — but it still runs first, and
            //    it is still worth running: the explicit repair path
            //    (`rebuild = true`) does delete the tree, and a device upgrading from a
            //    build that predates this migration has its agent dir on the rootfs side
            //    only.
            lastAgentDirMigration = runCatching { migrateGuestAgentDir() }
                .getOrElse { error -> "agent 目录迁移失败：${error.message ?: error::class.java.simpleName}" }

            // 1. Runtime payload.
            val provisioned = provisioner.ensureReady(revision, rebuild, onStep)
            val outcome = provisioned.getOrNull()

            // 1b. The durable boot audit (BootAudit). Recorded on the background scope,
            //     never awaited: it walks the workspace root and the agent dir's direct
            //     children, and even that bounded work must not sit between the user and
            //     the first frame. It fires only when this boot is the first after an
            //     upgrade (the APK's lastUpdateTime/versionCode or the runtime revision
            //     changed since the last line), so an ordinary cold start only reads one
            //     small state file. A failure is logged as one readable sentence; it
            //     never fails a boot.
            scope.launch {
                val reason = runCatching {
                    BootAudit.recordIfUpgraded(
                        persistDir = paths.persist,
                        apk = apkStamp(),
                        revision = revision,
                        payloads = payloadDigestSummary(),
                        reextracted = outcome?.let { done ->
                            if (done.reextracted.isEmpty()) {
                                if (done.rebuilt) "rebuild" else "none"
                            } else {
                                done.reextracted.joinToString(",")
                            }
                        } ?: "provision-failed",
                        workspaceRoot = WorkspaceStore.root(appContext),
                        agentDir = paths.agentDir,
                    )
                }.getOrElse { error -> "启动审计异常：${error::class.java.simpleName}: ${error.message}" }
                if (reason != null) {
                    lastBootAuditError = reason
                    Log.w(TAG, reason)
                }
            }

            provisioned.exceptionOrNull()?.let {
                return@withContext Boot.Failed("运行时解包失败", it.message)
            }

            // 2. Prove the runtime can actually execute before pretending it can.
            //    Cheap, and it converts an inscrutable mid-turn failure into a clear
            //    diagnosis (see RuntimeSelfCheck for why this cannot be assumed).
            val selfCheck = RuntimeSelfCheck(paths)
            val check = selfCheck.run(storage = android.os.Environment.getExternalStorageDirectory())
            if (!check.ok) {
                // `summarize` is the check's own rendering of an outcome — it carries
                // the ✗/· marker that says whether proot failed or the guest binary was
                // refused, the sentence naming the cause, and the last 12 lines of what
                // the probe actually wrote. This is its only caller: the boot failure
                // card is the one place a user can act on any of that, and its
                // `message`/`detail` pair is exactly the headline-plus-body shape
                // `BootErrorCard` renders. Using it here also removes the hand-rolled
                // `check.stderr.takeIf { … }` that could only ever show the stderr and
                // never the marker or the reason.
                return@withContext Boot.Failed(
                    message = "运行时自检未通过",
                    detail = selfCheck.summarize(check),
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

            // The two engines pi ships, in preference order.
            //
            // `dist/bundle/rpc-entry.js` first: it is the entry upstream *promises*
            // for this use (`package.json` maps `exports["./rpc-entry"]` to it) and it
            // forces `--mode rpc` itself. It is also the **packed** build, and that
            // is where the startup time comes from — measured on this workstation
            // (node 24.19.0, no proot): `dist/bundle/cli.js --version` 1.0 s, and a
            // bundled `get_state` round trip 2.0 s, against ~18–20 s for the
            // unpacked entry, which loads thousands of modules at boot.
            //
            // It is preferred, not assumed. This is a dependency the *user* upgrades
            // (`tools/fetch-runtime.mjs` pins the version, CI's contract run re-checks
            // it), so an upstream rename or a dropped bundle must degrade to "slower",
            // never to "the engine cannot start". Hence the fallback — the unpacked
            // entry, which needs `--mode rpc` spelled out — and a fatal failure only
            // when neither exists, with both candidates named so the report says what
            // was looked for.
            //
            // **Keep shipping the whole `dist/` tree.** The bundle is not
            // self-contained: `config.ts:389-431` (`getExportTemplateDir()`,
            // `getThemesDir()`) resolves the package root from `__dirname` and then
            // reads `dist/core/export-html` and its siblings. Trimming the payload to
            // `dist/bundle/**` would break those paths at runtime, in the export and
            // theme features rather than at startup.
            val engineRoot = "$ENGINE_GUEST_ROOT/node_modules/$PI_PACKAGE"
            val entryCandidates = listOf(
                "$engineRoot/dist/bundle/rpc-entry.js" to false,
                "$engineRoot/dist/cli.js" to true,
            )
            val chosen = entryCandidates.firstOrNull { (candidate, _) ->
                File(paths.rootfs, candidate.removePrefix("/")).isFile
            } ?: return@withContext Boot.Failed(
                "引擎未安装",
                "找不到引擎入口，试过这两个：\n" +
                    entryCandidates.joinToString("\n") { "  · ${it.first}" } +
                    "\n打包运行时需要包含 pi 引擎（tools/fetch-runtime.mjs）",
            )
            val cli = chosen.first
            // Only the unpacked entry needs the flag; `rpc-entry.js` injects it.
            val needsModeFlag = chosen.second

            launchOptions = launch

            val guestCommand = buildString {
                append("exec /opt/node/bin/node ").append(cli)
                if (needsModeFlag) append(" --mode rpc")
                append(" --session-dir ").append(guestAgentDir).append("/sessions")
                // pi's pre-spawn flags. The suffix is already shell-quoted because
                // the argv builder hands this string to `bash -c` inside the
                // rootfs; empty when no option is set.
                append(launch.commandLineSuffix())
            }

            // The engine is the largest and most latency-sensitive guest process —
            // Node plus pi, and the thing the user waits for on every cold start — so
            // it is the first caller that takes the optional proroot runtime
            // (`RuntimeSelection`). The install/maintenance paths deliberately do not;
            // see that class's KDoc for where the line is drawn and why.
            val selection = RuntimeSelection.of(appContext, paths)
            val extraBinds = listOf(
                workspace.absolutePath to guestWorkspace,
                // The agent dir was the one thing *not* bound, which meant pi read
                // and wrote `<rootfs>/root/.pi/agent` while the app addressed
                // `PiPaths.agentDir` (`<files>/pi/.pi/agent`). Every app-side
                // reader — settings, sessions, and this package's trust.json and
                // auth.json/models.json — was therefore looking at a directory pi
                // never touches, and, worse, one that an update could delete: that
                // path is inside the volatile tree, and the old provisioning wiped the
                // whole tree on every revision bump. Binding it is what puts pi's home
                // in the durable directory instead.
                paths.agentDir.absolutePath to guestAgentDir,
            )
            val extraEnv = mapOf(
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
                // **Deliberately absent: `NODE_COMPILE_CACHE`.** It was added here
                // on the theory that pi's startup is V8 compiling its modules, and
                // measured *no* effect in this environment: with a real provider
                // key and an idle container, pi answered a queued prompt at 20.8 s
                // cold / 20.7 s warm / 19.1 s warm again, and 13.9–18.7 s across
                // later runs regardless (docs/startup-latency.md). The cost is
                // node's module *loader* (969 module loads, ~4000 file syscalls
                // through proot), which a bytecode cache does not avoid. It is
                // written down instead of deleted because "we tried it and it did
                // not help" is the thing that stops it being tried again.
                // The user-chosen pre-spawn knobs. `PiLaunchOptions.environment`
                // only ever adds keys — pi tests some of them for presence, so a
                // "0" would be worse than omitting them.
            ) + launch.environment()

            // pi works relative to its cwd and stores sessions per cwd, so the guest
            // must start in the workspace rather than at /.
            val storage = android.os.Environment.getExternalStorageDirectory()
            val firstPlan = selection.plan(
                guestCommand = guestCommand,
                cwd = guestWorkspace,
                storage = storage,
                extraBinds = extraBinds,
                extraEnv = extraEnv,
            )
            // Armed **before** the process exists: the handle identifies this launch
            // by the config table proroot writes afterwards, matched to this plan's
            // token, which is the only way to learn the launcher's pid on this platform
            // (`ProrootLaunchHandle`). Without it the engine's guest tree could not be
            // reaped at all — proroot rejects `--kill-on-exit` and
            // `PiEngineSession.close()` only destroys the direct child, so every stop
            // would leave `bash` and a Node engine behind.
            val engineHandle = if (firstPlan.usingProroot) {
                ProrootLaunchHandle.arm(paths.prorootTmpDir(), firstPlan.launchToken)
            } else {
                null
            }
            val first = runCatching {
                PiEngineSession.spawn(
                    argv = firstPlan.argv,
                    env = firstPlan.environment,
                    cwd = paths.runtime,
                    scope = scope,
                )
            }
            // Fallback layer ②: proroot could not even start the process. The gate
            // has already proven that proroot *can* run a guest here, so this is a
            // genuinely new failure — count it, and bring the engine up on proot
            // rather than failing the boot. `spawn` throwing means the process never
            // existed, which is the only engine-side failure this layer can attribute
            // without reading `PiEngineSession`'s internals (deliberately out of scope).
            //
            // Kept rather than only logged: this is the one proroot failure that still
            // produces a `Boot.Failed`, and the autopsy below is what turns it into
            // evidence a user can read (`lastProrootForensics`).
            var prorootSpawnError: Throwable? = null
            val spawned = first.recoverCatching { error ->
                if (!firstPlan.usingProroot) throw error
                prorootSpawnError = error
                selection.recordProrootFailure("${error::class.java.simpleName}: ${error.message}")
                val retry = selection.plan(
                    guestCommand = guestCommand,
                    cwd = guestWorkspace,
                    storage = storage,
                    extraBinds = extraBinds,
                    extraEnv = extraEnv,
                    allowProroot = false,
                )
                PiEngineSession.spawn(
                    argv = retry.argv,
                    env = retry.environment,
                    cwd = paths.runtime,
                    scope = scope,
                )
            }

            spawned.fold(
                onSuccess = { session ->
                    if (firstPlan.usingProroot) selection.recordProrootSuccess()
                    // The engine's own record-level problems (an event the flow could
                    // not deliver, a transcript fold that threw) are counted inside the
                    // session and read back by the diagnostic report; this is where they
                    // also reach logcat. `PiEngineSession` is deliberately Android-free
                    // (no `android.util.Log`), so the host owns the line — and the
                    // session rate-limits the overflow case, which arrives as a burst.
                    session.onRecordProblem = { message -> Log.w(TAG, message) }
                    engineHandle?.resolveLauncherPid()
                    engineLaunch = engineHandle
                    if (engineHandle?.launcherPid != null) {
                        // The engine can also end **without** anyone asking: pi exits on
                        // its own (`Stopped`) or dies (`Failed`). `state` is the session's
                        // own published flow, so this needs nothing from
                        // `PiEngineSession`'s internals, and `first` releases the
                        // collector as soon as it fires.
                        scope.launch {
                            val end = session.state.first {
                                it == PiEngineSession.EngineState.Stopped ||
                                    it == PiEngineSession.EngineState.Failed
                            }
                            // Best effort, and honestly so: by the time the session
                            // publishes `Stopped` the launcher has usually exited, and a
                            // dead launcher's children can no longer be identified as
                            // ours. When the capture comes back empty the log says so —
                            // `docs/known-gaps.md` §N2 records the residual case. The
                            // capture stays **before** the autopsy for that reason.
                            //
                            // A proroot engine that ends with 126/127 never ran the guest
                            // command: the number is the whole failure, and it is the one
                            // shape the boot-time `Boot.Failed` cannot carry (the boot had
                            // already returned `Ready`). This is where it becomes evidence —
                            // bounded, after the fact, never on a healthy start
                            // (`autopsyProrootFailure`).
                            val tree = captureEngineTree(engineHandle)
                            reapEngineTree(tree, engineHandle, "引擎自行结束（$end）")
                            if (session.lastExitCode?.let { ProrootExecProbe.isLaunchFailure(it) } == true) {
                                autopsyProrootFailure(
                                    selection = selection,
                                    storage = android.os.Environment.getExternalStorageDirectory(),
                                    exitCode = session.lastExitCode,
                                    launcherSource = session.stderr.toString(),
                                    cwd = guestWorkspace,
                                    extraBinds = extraBinds,
                                )
                            }
                            if (engineLaunch === engineHandle) engineLaunch = null
                        }
                    }
                    // A boot that succeeds while an engine is already attached must not
                    // leave the old process alive: two pi processes on one cwd would both
                    // append to the same session file and both hold the same settings.
                    publish(session)
                    // Spawned is not the same as serving, and the difference is what the
                    // user sees as "发消息不回复": pi does not read its stdin until its
                    // startup is over. Ask it a question so `PiEngineSession.state` can
                    // leave `Starting` on evidence instead of on the process existing
                    // (docs/known-gaps.md §M1). Deliberately not awaited here — the
                    // caller gets the session now and the UI reports 启动中 until the
                    // answer arrives, which is also what lets a first message be typed
                    // during the wait instead of being rejected.
                    session.probeServing()
                    Boot.Ready(session)
                },
                onFailure = { error ->
                    // Fail closed: whoever was holding the previous session must not keep
                    // talking to a process that is no longer the engine.
                    publish(null)
                    // If the first attempt was proroot and it never produced a process, run
                    // the autopsy here: this is the `Boot.Failed` the failure card renders,
                    // and "无法启动 pi 引擎 / Java 异常名" alone says nothing about *why* the
                    // runtime refused.
                    if (firstPlan.usingProroot && prorootSpawnError != null) {
                        autopsyProrootFailure(
                            selection = selection,
                            storage = storage,
                            exitCode = null,
                            launcherSource = prorootSpawnError?.message,
                            cwd = guestWorkspace,
                            extraBinds = extraBinds,
                        )
                    }
                    Boot.Failed(
                        "无法启动 pi 引擎",
                        buildString {
                            append("${error::class.java.simpleName}: ${error.message}")
                            lastProrootForensics?.let { forensics ->
                                append("\n\n")
                                forensics.forEach { line -> append(line).append('\n') }
                            }
                        }.trimEnd(),
                    )
                },
            )
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
     * @param launch the process configuration for the **new** engine. Null replays
     *        the set the running one was started with, which is what a restart
     *        asked for by an install wants. A caller that changed one of pi's
     *        process knobs must pass the new set here: those knobs are read only
     *        when the process starts ([PiLaunchOptions]), so replaying the old set
     *        would silently keep the old behaviour and report success.
     * @param workspaceProvider the workspace for the **new** engine. Defaults to
     *        [WorkspaceStore]'s current one, exactly like [boot]. A workspace
     *        switch passes the target directory here, so the engine's new cwd is
     *        the caller's decision rather than something read back out of a
     *        settings file that might not have been written yet.
     */
    suspend fun restart(
        reason: String,
        workspaceProvider: () -> File = { WorkspaceStore.currentHost(appContext) },
        allowInterrupt: Boolean = false,
        // Same derived value as `boot`, for the same reason — and it matters more
        // here: `restart` compares it before deciding, so a stale constant would
        // refuse a restart that is in fact needed.
        revision: String = RuntimeProvisioner.packagedRevision(appContext.assets),
        // Before `onStep`, deliberately: callers pass `onStep` as a trailing lambda
        // and that only binds to the last *parameter*.
        launch: PiLaunchOptions? = null,
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

        // Refuse a restart that would silently become a re-provision. `ensureReady` is
        // cheap only while the stamp matches; when it does not, this boot would first
        // read and compare every payload's state and then extract the payloads whose
        // bytes changed — minutes on a big payload, in the middle of an action the user
        // read as "reload my extensions". The refusal is about *surprise*, not about
        // data loss: since provisioning became per payload, a re-provision extracts over
        // the tree and deletes nothing the user installed (only the explicit repair path
        // does, and that is a different button). The sentence says both, because the old
        // one claimed the agent dir would be emptied and that is no longer true.
        if (!stampMatches(revision)) {
            return@withLock Restart.RefusedNeedsProvisioning(
                "运行时需要按载荷更新（stamp 与 $revision 不一致），这不是一次重启：" +
                    "更新会重解变化的载荷，可能要几分钟。请先走首次启动的 boot() 流程。" +
                    "更新只覆盖内置载荷，不删除工作区、会话、设置或 guest 里你自己装的东西。",
            )
        }

        if (current != null) {
            // `closeAfterSettling`, not `close`: `allowInterrupt` is the caller
            // accepting that this restart stops a running turn, and pi only writes
            // the turn it is in when that turn ends — `close()` shuts stdin and pi
            // exits on a closed stdin without waiting for the agent, so a hard close
            // here would drop the turn from the conversation on disk.
            // `PiEngineSession.closeAfterSettling` carries the file:line chain.
            // Captured while the old engine is still alive; see `captureEngineTree`.
            val previousLaunch = engineLaunch
            val previousTree = captureEngineTree(previousLaunch)
            runCatching { current.closeAfterSettling() }
            // The old engine's guest tree is this app's to stop (proroot has no
            // `--kill-on-exit`). Doing it here — after the session settled, before the new
            // engine exists — is what keeps a restart from leaving one orphaned Node
            // engine per attempt.
            reapEngineTree(previousTree, previousLaunch, "引擎重启（$reason）")
            engineLaunch = null
            // Publish null *before* the new engine exists, so a UI collecting the
            // flow shows "restarting" instead of holding the dead object.
            _session.value = null
        }

        // Replay the options the running engine was started with: pi reads its
        // process configuration only at startup, so a restart that dropped them
        // would change the engine behind the user's back.
        return@withLock when (val boot = bootLocked(revision, workspaceProvider, onStep, launch ?: launchOptions)) {
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
     *
     * Settles the running turn first, exactly like [restart]: this is a *stop*, not
     * a kill, and the conversation the user just had is the thing they expect to
     * find again (see `PiEngineSession.closeAfterSettling`).
     */
    suspend fun shutdown(): Boolean = lifecycleLock.withLock {
        val current = _session.value ?: return@withLock false
        // Same as `restart`, and for the same reason the capture comes first: the settle
        // ends in `process.destroy()`, which destroys the edges the tree is found by.
        val launch = engineLaunch
        val tree = captureEngineTree(launch)
        runCatching { current.closeAfterSettling() }
        reapEngineTree(tree, launch, "引擎停止")
        engineLaunch = null
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
     * public — `PiPaths.stampFile()` and `RuntimeProvisioner.packagedRevision` — so
     * this reads the same two facts without duplicating the unpacking logic, and it is
     * also the fast path's own comparison: a matching stamp is what lets a boot read no
     * per-payload state at all. If the two ever disagree the consequence is a refused
     * restart, never a deletion.
     */
    private fun stampMatches(revision: String): Boolean {
        if (!paths.rootfs.isDirectory) return false
        val stamp = paths.stampFile()
        if (!stamp.isFile) return false
        return runCatching { stamp.readText().trim() == revision }.getOrDefault(false)
    }

    /**
     * This APK's identity for the boot audit's upgrade trigger: last update time plus
     * versionCode.
     *
     * `lastUpdateTime` is the fact that actually changes on an install/update, and it is
     * read here rather than inside [BootAudit] because that object is Android-free by
     * design (a bare-JVM harness compiles it). A failure is a **readable sentence**, not
     * an empty string: `BootAudit.shouldRecord` treats an unreadable value as "record",
     * so a device that cannot answer still gets its line.
     */
    private fun apkStamp(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${info.lastUpdateTime}-${info.versionCode}"
    }.getOrElse { error -> "读不到：${error::class.java.simpleName}" }

    /**
     * The per-payload digests currently recorded in `<files>/pi/runtime/.payloads`, as
     * one compact string for the audit line.
     *
     * Read from the state files rather than from a list in this class, so the line says
     * what is actually on disk — including "nothing yet", which is itself the migration
     * evidence. Deliberately not sorted into the payload's canonical order: a stable
     * alphabetical order is enough to compare two lines, and re-deriving the payload
     * list here would be a second spelling of it.
     */
    private fun payloadDigestSummary(): String {
        val files = paths.payloadStateDir().listFiles()?.filter { it.isFile && it.name.endsWith(".digest") }
            ?: return "无（还没有按载荷状态）"
        if (files.isEmpty()) return "无（还没有按载荷状态）"
        return files.sortedBy { it.name }.joinToString(",") { file ->
            val digest = runCatching { file.readText().trim() }.getOrNull() ?: "读不到"
            "${file.name.removeSuffix(".digest")}:$digest"
        }
    }

    /**
     * Map a host path under <files> to where it appears inside the guest.
     *
     * The workspace lives in app-private storage for speed (a cwd on `/sdcard`
     * goes through FUSE and makes an `npm install` several times slower), and is
     * bind-mounted into the guest at the path [GuestWorkspacePath] derives. The
     * rule itself is that object's — it used to be written out here as well, and
     * a second copy of it is how the session-file path and the terminal's cwd
     * drifted apart before.
     */
    private fun guestPathFor(host: File): String =
        GuestWorkspacePath.under(appContext.filesDir.absolutePath, host.absolutePath)

    companion object {
        /** Log tag for the proroot launch/reap bookkeeping; nothing else in here logs. */
        private const val TAG = "PiEngineHost"

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

        /**
         * Serialises boot/restart/shutdown **for the whole process**.
         *
         * Two concurrent restarts would leave two proot trees running on one cwd —
         * both writing the same session JSONL — which is precisely the state the
         * class exists to prevent. It used to be an instance field, and that was one
         * host too narrow: a host is created per `PiSessionViewModel`, so the window
         * where a finished Activity's engine is still being settled by
         * `onCleared`'s teardown while the next ViewModel boots a new one on the same
         * cwd was governed by **two different locks**. That is the one window in
         * which two engines can genuinely overlap, and it is exactly the case the
         * per-instance lock could not see.
         *
         * The lock is process-wide; the *host* is not, and nothing else about this
         * class changes: [publish], the "the UI never holds a dead engine" rule and
         * [restart]'s stop-then-start ordering all stay per-host.
         *
         * A restart that is in flight still closes the old engine *before* the new
         * one is spawned, so the two are never live at once; the residual window is a
         * teardown that begins after a new boot won the lock — see
         * `docs/lifecycle-and-timers.md` §4.
         */
        private val PROCESS_LOCK = Mutex()
    }
}
