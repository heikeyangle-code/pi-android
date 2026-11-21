package app.pi.engine

import android.content.Context
import android.util.Log
import app.pi.bridge.DeviceBridgeController
import app.pi.runtime.BootAudit
import app.pi.runtime.GuestCommandLine
import app.pi.runtime.GuestTreeReaper
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.PiPaths
import app.pi.runtime.ProrootExecProbe
import app.pi.runtime.ProrootLaunchHandle
import app.pi.runtime.ProrootProbe
import app.pi.runtime.RuntimeProvisioner
import app.pi.runtime.RuntimeSelection
import app.pi.runtime.RuntimeSelfCheck
import app.pi.runtime.RuntimeTreeAudit
import app.pi.runtime.WorkspaceStore
import app.pi.host.toOpenArgs
import app.pi.protocol.PiLaunchOptions
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
 * Brings up the guest Node.js SDK Host (`sdk-host/main.mjs`) inside the active
 * Linux runtime (PRoot / Proroot / TAWCROOT) and hands back a [PiEngineSession].
 *
 * The pipeline is: unpack the runtime (once) → install fast-path stamps →
 * spawn `node sdk-host/main.mjs` → negotiate `host.hello` & `session.open` over
 * typed JSONL frames (`docs/sdk-host/protocol.md`).
 *
 * Resource changes (extensions, skills, prompts, themes, packages) reload in-place
 * via `session.reload` without killing the process; [restart] is reserved for
 * process-level launch changes (runtime engine switch, workspace root switch,
 * `PI_OFFLINE`, `PI_CACHE_RETENTION`, `--no-*` flags, or crash recovery).
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
                // The autopsy re-runs the same launch shape, so it can time out the same way;
                // handing the launcher back to the Android-side reaper here is what keeps a
                // timed-out autopsy from leaking the tree it started.
                onTimeoutTree = GuestTreeReaper::reapTimeoutedProbe,
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
    /** 本次引擎进程启动时挂进 guest 的外部目录（宿主路径）。不在其中的外部工作区要重启才可达。 */
    @Volatile
    var boundExternalHostPaths: Set<String> = emptySet()
        private set

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
     * Whether the `scandir(3)` fallback module reached the guest on the last boot, and why
     * not when it did not.
     *
     * `ProrootCommand` injects it through `NODE_OPTIONS` **only when the file exists**, so a
     * write that failed leaves proroot exactly as broken as before while looking like a boot
     * that simply did not help. That is the shape this field exists to make visible — the
     * same reason as [lastBridgeError] one screen up.
     */
    @Volatile
    var lastScandirFix: String? = null

    /**
     * 上一次把 sdk-host 装进 rootfs 的结果：`null` 成功，否则是一句话（装不上不致命 —— 现在
     * 引擎入口还是旧的；**切到 sdk-host 之后（migration.md 步骤 3）它就是致命的**，那时这句话要进
     * 启动失败卡片）。每次启动都装，理由与 [installScandirFix] 相同：目标在易失树里。
     */
    @Volatile
    var lastSdkHostInstall: String? = null
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

    /**
     * The entry [boot]/[restart] last launched this host's engine on, or null before the
     * first launch. Written by the launch path, read by the diagnostic report — see
     * [EngineEntry] for why the reading exists at all.
     */
    @Volatile
    var lastEngineEntry: EngineEntry? = null
        private set

    /** The container runtime actually hosting the live engine process, or null when stopped. */
    @Volatile
    var activeEngine: app.pi.runtime.GuestEngine? = null
        private set

    /**
     * Which of pi's two SDK entries this process was launched on.
     *
     * Matches `tools/sdk-host/sdk.mjs`: `dist/bundle/index.js` first (packed SDK entry)
     * and `dist/index.js` fallback (package `exports["."]`).
     */
    enum class EngineEntry(val relativePath: String, val label: String) {
        /** Packed SDK entry loaded first by `sdk-host/sdk.mjs`. */
        Bundled("dist/bundle/index.js", "打包 SDK 入口 dist/bundle/index.js"),

        /** Unpacked package entry (`exports["."]`) used as fallback. */
        Unpacked("dist/index.js", "解包兜底入口 dist/index.js（慢）"),
    }

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
     * `--no-context-files`, the four `--no-*` suppressions, the extension
     * pass-through) is only readable when the process starts, so a
     * restart that dropped these would silently change the user's engine. The two
     * prompt values are deliberately not part of it any more: they are files pi
     * re-reads per session (`app.pi.ui.PromptFileSettingsStore`), not process
     * arguments.
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
        revision: String? = null,
        workspaceProvider: () -> File = { WorkspaceStore.currentHost(appContext) },
        launch: PiLaunchOptions = PiLaunchOptions(),
        rebuild: Boolean = false,
        repair: Boolean = false,
        onStep: (RuntimeProvisioner.Step) -> Unit = {},
    ): Boot = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val resolvedRevision = revision ?: RuntimeProvisioner.packagedRevision(appContext.assets)
            bootLocked(resolvedRevision, workspaceProvider, onStep, launch, rebuild, repair)
        }
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
        repair: Boolean = false,
    ): Boot =
        withContext(Dispatchers.IO) {
            // 1. Runtime payload.
            val provisioned = provisioner.ensureReady(revision, rebuild, repair, onStep)
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
                // The tree description is appended, and only on failure: proot's `not
                // found` cannot say whether the file is absent, the `/bin` link is
                // dangling, or the whole rootfs is empty, and that difference decides
                // whether the next step is 「重建运行时」 or a bug report. Four `stat`s on a
                // boot that has already failed. ([RuntimeTreeAudit] is what the provisioner
                // checks too, so the two agree on what "the guest cannot run" means.)
                return@withContext Boot.Failed(
                    message = "运行时自检未通过",
                    detail = selfCheck.summarize(check) + "\n\n" +
                        RuntimeTreeAudit.describe(paths.rootfs),
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

            // 3b. The `scandir(3)` fallback module, which is what makes proroot usable at
            //     all (see `PiPaths.scandirFix()`): proroot's `scandir` returns ENOENT for
            //     the paths it rewrites, and Node's `fs.readdir*` goes through `scandir`, so
            //     every directory listing inside pi — `ls`, extensions, skills, themes,
            //     prompts, sessions — fails on a bound path while `bash`'s `ls` keeps
            //     working. `ProrootCommand` injects it through `NODE_OPTIONS` **only when
            //     this file is present**, so the write has to happen before the spawn and
            //     its failure must be visible rather than silent: a missing module is not a
            //     degraded proroot, it is the same broken proroot as before.
            lastScandirFix = installScandirFix(revision)

            // 3c. sdk-host（docs/sdk-host/）：新协议的宿主。与 APK 版本 + 运行时 revision 对账，
            //     版本未变且入口存在时跳过全目录 60+ 次 AssetManager / 磁盘比对。
            lastSdkHostInstall = installSdkHost(revision)
            if (lastSdkHostInstall != null && !File(paths.rootfs, "${SdkHostLaunch.GUEST_DIR.removePrefix("/")}/main.mjs").isFile) {
                return@withContext Boot.Failed("sdk-host 安装失败", lastSdkHostInstall)
            }

            // Background maintenance: sweep stale `proot-*` scratch dirs in PROOT_TMP_DIR.
            scope.launch { runCatching { paths.sweepStaleProotTmp() } }

            // 4. Spawn pi inside the guest.
            val workspace = workspaceProvider()
            workspace.mkdirs()
            val guestWorkspace = guestPathFor(workspace)

            // The two SDK entries pi ships, matching `sdk-host/sdk.mjs`:
            // `dist/bundle/index.js` first (packed SDK build) and `dist/index.js` fallback.
            // **Keep shipping the whole `dist/` tree.** The bundle is not self-contained
            // (templates, themes, extensions, and internal modules resolve relative to dist/).
            val engineRoot = "$ENGINE_GUEST_ROOT/node_modules/$PI_PACKAGE"
            val entryCandidates = listOf(
                EngineEntry.Bundled to "$engineRoot/${EngineEntry.Bundled.relativePath}",
                EngineEntry.Unpacked to "$engineRoot/${EngineEntry.Unpacked.relativePath}",
            )
            val chosen = entryCandidates.firstOrNull { (_, candidate) ->
                File(paths.rootfs, candidate.removePrefix("/")).isFile
            } ?: return@withContext Boot.Failed(
                "引擎未安装",
                "找不到引擎入口，试过这两个：\n" +
                    entryCandidates.joinToString("\n") { "  · ${it.second}" } +
                    "\n打包运行时需要包含 pi 引擎（tools/fetch-runtime.mjs）",
            )
            val entry = chosen.first
            // Recorded here rather than threaded through `Boot.Ready`/`Restart.Ok`: it is a
            // property of *this host's* last launch, and the diagnostic report asks for it
            // while the engine is running (not only at the exit it captures). Written before
            // the process exists, so a run that dies immediately still names its entry.
            lastEngineEntry = entry

            launchOptions = launch

            // 启动 sdk-host（不再是 pi 的 RPC 入口）。proot 与 proroot 共用这一条命令
            // （docs/sdk-host/runtimes.md）；用户的启动前设置不再拼成命令行 flag，而是在握手后
            // 以结构化参数交给 `session.open`（见下面的 `startHost`、`PiLaunchOptions.toOpenArgs`）。
            // `entry`（打包版/解包版）只用来确认引擎已安装：宿主自己的 `loadSdk` 优先用 bundle、
            // 缺了回退 dist（`sdkVia`），不需要 App 再替它挑。
            val guestCommand = SdkHostLaunch.command(
                cwd = guestWorkspace,
                agentDir = guestAgentDir,
                sessionDir = "$guestAgentDir/sessions",
                piDir = engineRoot,
            )

            // The engine is the largest and most latency-sensitive guest process —
            // Node plus pi, and the thing the user waits for on every cold start — so
            // it is the first caller that takes the optional proroot runtime
            // (`RuntimeSelection`). The install/maintenance paths deliberately do not;
            // see that class's KDoc for where the line is drawn and why.
            val selection = RuntimeSelection.of(appContext, paths)
            // **内部工作区没有任何额外 bind** (2026-09-23)。引擎的 cwd
            // (`/workspace/pi/workspaces/<name>`) 与 pi 的家 (`/root/.pi/agent`) 的拼法
            // 就是它们在 rootfs 里的宿主目录，靠根前缀就能到，不需要挂载。那两个 bind
            // 正是让 `git` 在 proroot 下不可用的东西：`scandir`/`mkstemp`/`mkdtemp` 在
            // 任何"宿主源是本应用私有目录"的 bind 下都返回 `ENOENT`
            // (`docs/proroot-scandir-defect.md`)，而 `.git/tXXXXXX` 就创建在工作区里。
            //
            // **外部工作区（用户在手机上选的 `/storage/...` 目录）相反：必须有 bind。**
            // `GuestWorkspacePath.under` 把它们映射成 `/workspace/storage/emulated/0/Foo`，
            // 而那个路径在 rootfs 里并不存在 —— 没有 bind 时 pi 的 `chdir` 失败、进程立刻
            // 退出，界面就是「切换工作区失败」，而且**重试永远同一结果**（映射是确定的）。
            // 2026-11-02 用户报的就是这个：选手机目录 → 引擎起不来 → 以为只能重建。
            //
            // 挂载点必须先在 rootfs 里存在：proot/proroot 不会替我们创建 `-b` 的目标。
            // 内部项目和 `/storage/emulated/0`（`GuestRecipe.binds` 已在启动时挂好）都无需额外 bind；
            // 只有真正未挂载的外部卷（例如可移除 SD 卡）才需要加 `-b` 挂载。
            val externalEntries = runCatching { WorkspaceStore.list(appContext) }.getOrDefault(emptyList())
                .filter { it.external && it.available && it.host.isDirectory }
            val extraBinds = buildList {
                if (GuestWorkspacePath.needsBind(paths.rootfs, workspace, guestWorkspace)) {
                    add(workspace.absolutePath to guestWorkspace)
                }
                for (entry in externalEntries) {
                    if (!GuestWorkspacePath.needsBind(paths.rootfs, entry.host, entry.guestPath)) continue
                    val pair = entry.host.absolutePath to entry.guestPath
                    if (pair !in this) add(pair)
                }
                forEach { pair -> File(paths.rootfs, pair.second.removePrefix("/")).mkdirs() }
            }
            boundExternalHostPaths = extraBinds.map { it.first }.toSet()
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
                // **打包入口（`dist/bundle/index.js`）上启用 `NODE_COMPILE_CACHE`。**
                // 早期在未打包的 `dist/cli.js`（969 个模块）上测过无效，因为当时瓶颈是 proot 下
                // 4000 次路径翻译 syscall 而非 V8 编译。切到单文件 `dist/bundle/index.js` 后，
                // 实测热缓存能省掉 ~12–15% 的 bundle 解析编译耗时（ARM64 移动端收益更大）。
                // 放在 `<rootfs>/tmp/` 内：跨冷启动持久化、重建运行时自动随易失树清空，不污染用户 agentDir。
                "NODE_COMPILE_CACHE" to "/tmp/pi-node-compile-cache",
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
            var engineHandle = firstPlan.armHandle(paths)
            val first = runCatching {
                PiEngineSession.spawn(
                    argv = firstPlan.argv,
                    env = firstPlan.environment,
                    cwd = GuestCommandLine.hostCwd(paths, firstPlan.engine),
                    scope = scope,
                )
            }
            var prorootSpawnError: Throwable? = null
            var actualEngine = firstPlan.engine
            val spawned = first.recoverCatching { error ->
                if (!firstPlan.usingExperimental) throw error
                prorootSpawnError = error
                // Clear handle on fallback to proot so we never stall 1.5 s polling for a dead launcher PID.
                engineHandle = null
                selection.recordEngineFailure("${error::class.java.simpleName}: ${error.message}")
                val retry = selection.plan(
                    guestCommand = guestCommand,
                    cwd = guestWorkspace,
                    storage = storage,
                    extraBinds = extraBinds,
                    extraEnv = extraEnv,
                    allowProroot = false,
                )
                actualEngine = retry.engine
                PiEngineSession.spawn(
                    argv = retry.argv,
                    env = retry.environment,
                    cwd = GuestCommandLine.hostCwd(paths, retry.engine),
                    scope = scope,
                )
            }

            spawned.fold(
                onSuccess = { session ->
                    activeEngine = actualEngine
                    if (firstPlan.usingExperimental && actualEngine == firstPlan.engine) {
                        selection.recordEngineSuccess()
                    }
                    session.onRecordProblem = { message -> Log.w(TAG, message) }
                    // O(1) direct PID fast path from Process; never stalls 1.5 s on fast-exiting launches.
                    val handleForSession = engineHandle
                    handleForSession?.resolveLauncherPid(process = session.rawProcess)
                    engineLaunch = handleForSession
                    exitWatcherJob?.cancel()
                    exitWatcherJob = scope.launch {
                        val end = session.state.first {
                            it == PiEngineSession.EngineState.Stopped ||
                                it == PiEngineSession.EngineState.Failed
                        }
                        if (end == PiEngineSession.EngineState.Failed) {
                            app.pi.runtime.PiCrashStore.recordEngineExit(
                                paths = paths,
                                engineName = actualEngine.name,
                                exitCode = session.lastExitCode,
                                state = end.name,
                                stderrTail = session.stderrSnapshot(),
                            )
                        }
                        if (handleForSession?.launcherPid != null && engineLaunch === handleForSession) {
                            val tree = captureEngineTree(handleForSession)
                            reapEngineTree(tree, handleForSession, "引擎自行结束（$end）")
                            if (session.lastExitCode?.let { ProrootExecProbe.isLaunchFailure(it) } == true) {
                                autopsyProrootFailure(
                                    selection = selection,
                                    storage = android.os.Environment.getExternalStorageDirectory(),
                                    exitCode = session.lastExitCode,
                                    launcherSource = session.stderrSnapshot(),
                                    cwd = guestWorkspace,
                                    extraBinds = extraBinds,
                                )
                            }
                            if (engineLaunch === handleForSession) engineLaunch = null
                        }
                    }
                    publish(session)
                    session.startHost(launch.toOpenArgs(sessionDir = "$guestAgentDir/sessions"))
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
        revision: String? = null,
        launch: PiLaunchOptions? = null,
        onStep: (RuntimeProvisioner.Step) -> Unit = {},
    ): Restart = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val resolvedRevision = revision ?: RuntimeProvisioner.packagedRevision(appContext.assets)
            val current = _session.value

            if (current != null && current.state.value == PiEngineSession.EngineState.Busy && !allowInterrupt) {
                return@withLock Restart.RefusedTurnRunning(
                    "有回合正在运行（$reason）。重启会中断模型调用与正在执行的工具，" +
                        "所以本次没有重启，引擎仍在运行并可以继续使用。",
                )
            }

            if (!stampMatches(resolvedRevision)) {
                return@withLock Restart.RefusedNeedsProvisioning(
                    "运行时需要按载荷更新（stamp 与 $resolvedRevision 不一致），这不是一次重启：" +
                        "更新会重解变化的载荷，可能要几分钟。请先走首次启动的 boot() 流程。" +
                        "更新只覆盖内置载荷，不删除工作区、会话、设置或 guest 里你自己装的东西。",
                )
            }

            if (current != null) {
                exitWatcherJob?.cancel()
                exitWatcherJob = null
                val previousLaunch = engineLaunch
                val previousTree = captureEngineTree(previousLaunch)
                runCatching { current.closeAfterSettling() }
                reapEngineTree(previousTree, previousLaunch, "引擎重启（$reason）")
                engineLaunch = null
                _session.value = null
            }

            return@withLock when (val boot = bootLocked(resolvedRevision, workspaceProvider, onStep, launch ?: launchOptions)) {
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
    }

    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val current = _session.value ?: return@withLock false
            exitWatcherJob?.cancel()
            exitWatcherJob = null
            val launch = engineLaunch
            val tree = captureEngineTree(launch)
            runCatching { current.closeAfterSettling() }
            reapEngineTree(tree, launch, "引擎停止")
            engineLaunch = null
            activeEngine = null
            _session.value = null
            true
        }
    }

    fun paths(): PiPaths = paths

    // ------------------------------------------------------------- internals

    /**
     * Copy `assets/guest/scandir-fix.mjs` to [PiPaths.scandirFix], every boot.
     *
     * ## Why every boot rather than once
     *
     * The destination is **inside the volatile tree**: the explicit repair path
     * (`ensureReady(rebuild = true)`) deletes the whole rootfs, and a payload re-extract
     * replaces it. Writing it on every boot costs one small file and removes the entire
     * class of "the module was there last time and is not now" — which would otherwise show
     * up as proroot being mysteriously broken again after a repair, with nothing to read.
     *
     * ## What it returns
     *
     * A sentence for [lastScandirFix], or `null` on success. It is **not** fatal: without the
     * module, proroot behaves exactly as it did before this existed, and proot never looks at
     * it. But the failure must be readable, because "the fix silently did not get installed"
     * and "the fix did not work" are the same screen to the user.
     */
    private fun installScandirFix(revision: String): String? {
        val target = paths.scandirFix()
        val stampFile = File(paths.runtime, ".scandir-fix.stamp")
        val expectedStamp = "${apkStamp()}\n$revision"
        if (target.isFile && stampFile.isFile && runCatching { stampFile.readText().trim() == expectedStamp }.getOrDefault(false)) {
            return null
        }
        return runCatching {
            val bytes = appContext.assets
                .open(SCANDIR_FIX_ASSET, android.content.res.AssetManager.ACCESS_BUFFER)
                .use { it.readBytes() }
            if (!(target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes))) {
                target.parentFile?.mkdirs()
                // Write-then-rename: a half-written module passed to `--import` is a Node startup
                // failure, and a boot that dies during provisioning would otherwise leave one.
                val staging = File(target.parentFile, "${target.name}.partial")
                staging.writeBytes(bytes)
                if (!staging.renameTo(target)) {
                    target.writeBytes(bytes)
                    staging.delete()
                }
            }
            runCatching {
                val tmp = File(stampFile.parentFile, "${stampFile.name}.partial")
                tmp.writeText(expectedStamp + "\n")
                if (!tmp.renameTo(stampFile)) {
                    stampFile.writeText(expectedStamp + "\n")
                    tmp.delete()
                }
            }
            null
        }.getOrElse { error ->
            "scandir 兜底模块未能写入 ${target.absolutePath}：" +
                "${error::class.java.simpleName}: ${error.message ?: "无消息"}"
        }
    }

    /** 把 `assets/guest/sdk-host/` 同步进 `<rootfs>/opt/pi/sdk-host/`；返回 `null` 或一句失败原因。 */
    private fun installSdkHost(revision: String): String? = runCatching {
        val target = File(paths.rootfs, SdkHostLaunch.GUEST_DIR.removePrefix("/"))
        val entry = File(target, "main.mjs")
        val stampFile = File(paths.runtime, ".sdk-host.stamp")
        val expectedStamp = "${apkStamp()}\n$revision"
        if (entry.isFile && stampFile.isFile && runCatching { stampFile.readText().trim() == expectedStamp }.getOrDefault(false)) {
            return@runCatching null
        }
        SdkHostInstaller(AndroidHostAssetSource(appContext.assets)).install(target)
        runCatching {
            val tmp = File(stampFile.parentFile, "${stampFile.name}.partial")
            tmp.writeText(expectedStamp + "\n")
            if (!tmp.renameTo(stampFile)) {
                stampFile.writeText(expectedStamp + "\n")
                tmp.delete()
            }
        }
        null
    }.getOrElse { error ->
        "sdk-host 未能写入 ${SdkHostLaunch.GUEST_DIR}：${error::class.java.simpleName}: ${error.message ?: "无消息"}"
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
        if (next == null) activeEngine = null
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
        // First line only: the stamp's second line is the `audited` marker
        // (`PiPaths.stampRevision`), and comparing the whole text would refuse every
        // restart on a tree that has been verified.
        return paths.stampRevision() == revision
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
    @Volatile
    private var exitWatcherJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var cachedApkStamp: String? = null

    private fun apkStamp(): String {
        cachedApkStamp?.let { return it }
        return runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            "${info.lastUpdateTime}-$versionCode".also { cachedApkStamp = it }
        }.getOrElse { error -> "读不到：${error::class.java.simpleName}" }
    }

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
        GuestWorkspacePath.under(paths.workspaceBase.absolutePath, host.absolutePath)

    companion object {
        /** Log tag for the proroot launch/reap bookkeeping; nothing else in here logs. */
        private const val TAG = "PiEngineHost"

        /** Where the packaged engine lands inside the rootfs. */
        const val ENGINE_GUEST_ROOT = "/opt/pi"

        /**
         * The `scandir(3)` fallback module, as an APK asset.
         *
         * `ProrootCommand.SCANDIR_FIX_GUEST_PATH` is its **guest** spelling; this is where it
         * comes from. Kept beside [ENGINE_GUEST_ROOT] because that is also where it lands —
         * inside the rootfs, never on a bind, so the repair does not depend on the thing it
         * repairs.
         */
        const val SCANDIR_FIX_ASSET = "guest/scandir-fix.mjs"

        /** The npm package name pi ships as. */
        const val PI_PACKAGE = "@earendil-works/pi-coding-agent"

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
