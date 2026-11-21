package app.pi.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * **The** decision point: one object that answers "which runtime launches this guest
 * command, and why" for every entry point, and that owns the probe gate, the failure
 * streak and the proroot scratch directory.
 *
 * ## What it is not
 *
 * It is not a second recipe. The argv and the environment come from
 * [GuestCommandLine] → [ProotCommand]/[ProrootCommand], which share [GuestRecipe];
 * this class only *chooses* between them. A caller that wanted to spell a flag here
 * would be adding the drift the shared recipe exists to prevent.
 *
 * ## Which callers get which answer
 *
 * The division of labour is DSH App's (`docs/proroot-research.md` §7.1, §9.2 item 3)
 * and the reason a closed-source runtime is shippable at all: **everything on the
 * daily interactive path runs proroot when it is available** — the pi engine process
 * itself (Node and pi, the largest single cost and the one the user waits for), the
 * terminal's PTY, the commands pi executes as tools, and package installs — while
 * exactly two things stay on proot:
 *
 *  - **install / first run** — the rootfs, tools, Node, pnpm, the harness and the
 *    guard. It runs once, must not be the thing that breaks a fresh install, and
 *    gains nothing from a faster runtime;
 *  - **maintenance** — the self-check and the runtime probes, which are the
 *    measurements that decide whether proroot is allowed to run at all. A gate that
 *    ran through the thing it is gating would be circular.
 *
 * That is expressed by `allowProroot = false` at those call sites, not by a hidden
 * rule here, so the split is visible in the diff of every launch path.
 *
 * ## The three-layer fallback
 *
 * 1. **Files missing** — [EngineFallback.RuntimeFilesMissing]; nothing is probed,
 *    and the reason names the missing files.
 * 2. **A launch fails** — [recordProrootFailure] is called by the launch sites that
 *    can see an immediate failure, and the *caller* retries once with proot
 *    (`GuestCommand.run` is the one that does this today; the engine's plan is
 *    validated by the gate before it is used).
 * 3. **Three consecutive failures** — [EngineFallback.FailureStreak] forces proot
 *    until the user turns the switch on again, which clears the counter.
 *
 * Plus the layer DSH App does not need and we do: **the probe gate**. A switch that
 * is on but whose runtime cannot translate a guest path is worse than no switch,
 * because the failure is silent (§5.P0-2), so the switch means "use proroot if it
 * provably works here".
 *
 * ## Stopping a guest
 *
 * proroot rejects `--kill-on-exit` (§4.2), so the tree is reaped by
 * [GuestTreeReaper] at every stop site (the launch's own pid comes from
 * [ProrootLaunchHandle]), and this class owns the other half of the cleanup:
 * [sweepProrootConfigs] removes `.proroot-config-*` tables whose owner is gone, and
 * each stop site deletes its **own** table through its handle rather than waiting for
 * the next launch to notice it.
 */
class RuntimeSelection(
    /** The runtime tree and the `nativeLibraryDir` this decision is about. */
    val paths: PiPaths,
    /** Null when the app process is not up yet — read as "the switch is off". */
    private val prefs: RuntimePreferences?,
) {

    /** 用户选的运行时（三选一）。 */
    val selectedEngine: GuestEngine get() = prefs?.selectedEngine ?: GuestEngine.Proot

    /**
     * 选运行时。重新选一个非 proot 的引擎 = 清零失败计数 + 删掉**那个引擎**的探针缓存，
     * 下一次启动 guest 时重跑门禁——「再试一次」这个动作就是重新选它。
     */
    fun setSelectedEngine(engine: GuestEngine) {
        prefs?.setSelectedEngine(engine)
        when (engine) {
            GuestEngine.Proroot -> runCatching { paths.clearProrootProbeCache() }
            GuestEngine.Tawcroot -> runCatching { paths.clearTawcrootProbeCache() }
            GuestEngine.Proot -> Unit
        }
        if (engine != GuestEngine.Proot) Log.i(TAG, "选择了 ${engine.name}：失败计数已清零，探针缓存已删除，下次启动重跑门禁")
    }

    /** 某个运行时缺的组件文件；proot 随包内置，不查。 */
    private fun missingFor(engine: GuestEngine): List<String> = when (engine) {
        GuestEngine.Proot -> emptyList()
        GuestEngine.Proroot -> paths.missingProrootComponents()
        GuestEngine.Tawcroot -> paths.missingTawcrootComponents()
    }

    /** Guards against two main-thread callers starting the gate at once. */
    private val probeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** What a launch should use, and everything the caller needs to explain it. */
    data class Plan(
        val engine: GuestEngine,
        val fallback: EngineFallback,
        val argv: List<String>,
        val environment: Map<String, String>,
        /** Sentences for the diagnostic report, in order. Never empty. */
        val notes: List<String>,
        /** The gate verdict this plan used, when the gate was consulted. */
        val probe: ProrootProbe.Verdict? = null,
        /**
         * This launch's identity token, non-null exactly when [engine] is proroot.
         *
         * Handed to [ProrootLaunchHandle.arm] by the launch site so the pid resolved
         * afterwards is provably **this** launch's, not a concurrent one's.
         */
        val launchToken: String? = null,
        /** 用户**选**的运行时（门禁没过时和 [engine] 不同）。 */
        val selectedEngine: GuestEngine = GuestEngine.Proot,
    ) {
        val usingProroot: Boolean get() = engine == GuestEngine.Proroot

        /** 本次用的是非 proot 的实验性运行时：失败要计数、第一次失败要用 proot 重试。 */
        val usingExperimental: Boolean get() = engine != GuestEngine.Proot

        /**
         * 这次启动的「句柄」：用来找 launcher pid，超时 / 停止时回收整棵 guest 树。proroot 靠它的
         * config 表，tawcroot 靠环境里的 launch token + 父进程；proot 有 `--kill-on-exit`，不需要。
         */
        fun armHandle(paths: PiPaths): ProrootLaunchHandle? = when (engine) {
            GuestEngine.Proroot -> ProrootLaunchHandle.arm(paths.prorootTmpDir(), launchToken)
            GuestEngine.Tawcroot -> launchToken?.let { ProrootLaunchHandle.armByToken(it) }
            GuestEngine.Proot -> null
        }

        /**
         * One line for the settings row.
         *
         * Goes through [ProrootProbeNarrative] so a refusal names the stage that refused
         * instead of stopping at "探针未通过": [probe] is this plan's own verdict when the
         * gate was consulted, and every other fallback needs no evidence. Pure string
         * work over data already in hand — see the narrative's KDoc.
         */
        val summary: String
            get() = ProrootProbeNarrative.summary(fallback, probe?.detail.orEmpty(), engine = engine)
    }

    /** The effective state, for the settings row and the diagnostic report. */
    data class Status(
        val enabled: Boolean,
        val failures: Int,
        val missingComponents: List<String>,
        /** Cached gate verdict; null when the gate has never run at this revision. */
        val probePassed: Boolean?,
        val probeDetail: List<String>,
        val engine: GuestEngine,
        val fallback: EngineFallback,
        /**
         * The seccomp档 a proroot launch would run under, and the one every cached verdict
         * was earned under (the cache key pins it — [ProrootProbeCache.key]). Carried on
         * the status so the settings row, the group summary and the report can all name
         * the configuration in force **and what it gives up** from one value, and so the
         * row's sentence can say which档 refused.
         */
        val mode: ProrootSeccomp = RuntimeChoice.PROROOT_SECCOMP,
        val selected: GuestEngine = GuestEngine.Proot,
    ) {
        /**
         * The sentence the settings row, the settings-home summary and the report's
         * 实际生效 line all read.
         *
         * [ProrootProbeNarrative.summary] appends the failing stage and its recorded line
         * when the gate refused, so "看不出是哪一阶段失败" cannot happen unless the cache
         * holds no evidence — and then the sentence says that instead of guessing. Pure,
         * over fields this object already holds: reading it runs no probe and hashes
         * nothing. Filling [probeDetail] is the only expensive part, and the caller does
         * that once per epoch on `Dispatchers.IO` (`PiSettingsStack`).
         */
        val summary: String
            // `engine` must travel with it: with `EngineFallback.None` the sentence is
            // whatever runtime is *actually running*, and defaulting it to proroot made
            // this row say "proroot" while tawcroot was in effect.
            get() = ProrootProbeNarrative.summary(fallback, probeDetail, mode, engine)
    }

    fun plan(
        guestCommand: String,
        cwd: String,
        storage: File?,
        extraBinds: List<Pair<String, String>> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        /**
         * `false` on the install/maintenance paths — see the class KDoc. Those call
         * sites pass it explicitly so the boundary is visible where it is drawn.
         */
        allowProroot: Boolean = true,
    ): Plan {
        val selected = if (allowProroot) selectedEngine else GuestEngine.Proot
        val enabled = selected != GuestEngine.Proot
        // **Nothing proroot-shaped is touched while the switch is off** — not the five
        // `stat`s, not the config-table listing. The decision below cannot use either
        // of them in that case (`RuntimeChoice.decide` returns `SwitchOff` on its first
        // condition), so computing them would be work whose result is provably
        // discarded, on a path that runs on every guest start. The user asked for
        // exactly this: 「如果没打开，根本没必要检查」.
        val missing = missingFor(selected)
        val filesPresent = missing.isEmpty()
        val failures = prefs?.prorootFailures ?: 0

        // The gate is consulted only when it could change the answer: a switch that
        // is off must never run a proroot process, and a streak that has already
        // exhausted proroot must not be given another chance by a probe.
        var probe: ProrootProbe.Verdict? = null
        val probePassed = if (enabled && filesPresent && !RuntimeChoice.exhausted(failures)) {
            probe = gate(storage, selected)
            probe.passed
        } else {
            false
        }

        val decision = if (!allowProroot) {
            EngineDecision(GuestEngine.Proot, EngineFallback.InstallPath)
        } else {
            RuntimeChoice.decideFor(selected, filesPresent, probePassed, failures)
        }

        // Only while proroot can actually launch. The sweep is the cleanup proroot's own
        // config tables get, and the only moment one can appear is a proroot launch —
        // so off means there is nothing new to collect. Tables already on disk from an
        // earlier enabled period are collected by the next enabled launch (and a runtime
        // revision change wipes the whole tree anyway), which is why gating this on the
        // switch loses no cleanup. It is also a no-op when the directory does not exist,
        // because it looks through [PiPaths.prorootTmpDir] and creates nothing.
        if (selected == GuestEngine.Proroot) sweepProrootConfigs()

        // A per-launch identity, carried in the launcher's environment and read back
        // from `/proc/<pid>/environ` when the launch's pid is resolved. The pid has to
        // be exact — it is what gets signalled on stop — and "the config table that
        // appeared since I armed" is ambiguous when two launches overlap
        // ([ProrootLaunchHandle] carries the argument). Proot plans get no token: they
        // are never reaped by pid, and a guest environment should not grow a variable
        // nothing reads.
        val token = if (decision.engine != GuestEngine.Proot) UUID.randomUUID().toString() else null
        val environment = GuestCommandLine.environment(
            paths = paths,
            engine = decision.engine,
            extra = if (token == null) extraEnv else extraEnv + (ProrootLaunchHandle.TOKEN_ENV to token),
        )

        return Plan(
            engine = decision.engine,
            fallback = decision.fallback,
            argv = GuestCommandLine.build(
                paths = paths,
                engine = decision.engine,
                guestCommand = guestCommand,
                cwd = cwd,
                storage = storage,
                extraBinds = extraBinds,
            ),
            environment = environment,
            notes = notesFor(decision, selected, missing, probe, failures),
            probe = probe,
            launchToken = token,
            selectedEngine = selected,
        )
    }

    /**
     * The effective state, from cached facts only. **Runs no probe.**
     *
     * The returned [Status] carries the whole recorded evidence ([Status.probeDetail]),
     * not a summary of it: the settings row renders the per-phase lines under itself
     * (bounded, and saying so when it truncates), the report prints them in full, and
     * both read [Status.summary] for the one-line reason. That is a property of the
     * *value*, not a licence to call this more often — the five `stat`s plus the digest
     * are why [PiSettingsStack] calls it once per epoch on `Dispatchers.IO` and hands
     * the finished strings down, and why the row's `read(key)` never reaches here.
     */
    fun status(): Status = statusFor(selectedEngine)

    /**
     * 某个运行时**现在**的状态——设置页把三个选项各问一遍，每个选项下面写它自己的「可用 / 缺组件 /
     * 探针没过 / 失败 N 次」，用户选之前就知道选了会怎样。只读缓存，不跑探针。
     */
    fun statusFor(candidate: GuestEngine): Status {
        val enabled = candidate != GuestEngine.Proot
        // 失败计数属于「当前选中的那个」：没选中的引擎没有在跑，没有失败可数。
        val failures = if (candidate == selectedEngine) prefs?.prorootFailures ?: 0 else 0
        // Same rule as [plan]: a switch that is off is answered by `SwitchOff` before
        // anything else is consulted, so the five `stat`s (and the digest behind them)
        // would be work whose result is discarded. The settings row and the diagnostic
        // report call this, and both are opened by users who never enabled proroot.
        val missing = missingFor(candidate)
        val cached = if (enabled && missing.isEmpty()) {
            runCatching {
                when (candidate) {
                    GuestEngine.Proroot -> ProrootProbe.cached(paths, revision(), ProrootProbe.digestOf(paths))
                    GuestEngine.Tawcroot -> TawcrootProbe.cached(paths, revision(), TawcrootProbe.digestOf(paths))
                    GuestEngine.Proot -> null
                }
            }.getOrNull()
        } else {
            null
        }
        val decision = when {
            !enabled -> EngineDecision(GuestEngine.Proot, EngineFallback.SwitchOff)
            missing.isNotEmpty() -> EngineDecision(GuestEngine.Proot, EngineFallback.RuntimeFilesMissing)
            RuntimeChoice.exhausted(failures) -> EngineDecision(GuestEngine.Proot, EngineFallback.FailureStreak)
            cached == null -> EngineDecision(GuestEngine.Proot, EngineFallback.ProbeNotRun)
            !cached.passed -> EngineDecision(GuestEngine.Proot, EngineFallback.ProbeNotPassed)
            else -> EngineDecision(candidate, EngineFallback.None)
        }
        return Status(
            selected = candidate,
            enabled = enabled,
            failures = failures,
            missingComponents = missing,
            probePassed = cached?.passed,
            probeDetail = cached?.detail.orEmpty(),
            engine = decision.engine,
            fallback = decision.fallback,
        )
    }

    /**
     * A proroot launch that failed **before doing any work**, so the streak advances.
     *
     * Only immediate, attributable failures belong here: a guest command that ran and
     * exited non-zero is a result, not a runtime failure, and counting it would
     * abandon proroot because a user's `npm install` failed.
     */
    fun recordEngineFailure(reason: String) = recordProrootFailure(reason)

    fun recordEngineSuccess() = recordProrootSuccess()

    fun recordProrootFailure(reason: String) {
        val store = prefs ?: return
        val next = RuntimeChoice.afterFailure(store.prorootFailures)
        store.setProrootFailures(next)
        Log.w(TAG, "${selectedEngine.name} 启动失败（$next/${RuntimeChoice.MAX_CONSECUTIVE_FAILURES}）：$reason")
        if (RuntimeChoice.exhausted(next)) {
            // The "tell the user" half of DSH App's contract. The reachable surfaces
            // are the settings row (its summary reads `status()`), the diagnostic
            // report, and this log line: there is no toast channel inside the app
            // process, and inventing one would need the chat/session layer this
            // change deliberately does not touch.
            Log.w(TAG, "${selectedEngine.name} 已连续失败 $next 次，强制回退 proot；用户重新选择它可清零重试")
        }
    }

    /** A proroot launch that worked. Clears the streak — it is *consecutive*. */
    fun recordProrootSuccess() {
        val store = prefs ?: return
        if (store.prorootFailures != 0) store.setProrootFailures(0)
    }

    /**
     * Delete the config tables of launches whose process is gone, and cut the
     * directory back to [ProrootConfigSweep.DEFAULT_LIMIT] if it is still over.
     *
     * Runs **before a guest launch** (the moment a new table appears) and never on a
     * timer. [keepPid] is the launch in progress when the caller knows it; the liveness
     * rule alone already protects a live pid, and [keepPid] additionally protects it
     * from the cap.
     *
     * Uses [PiPaths.prorootTmpDir], not [PiPaths.prorootTmp]: looking must not create
     * the directory, or every proot-only user would grow an empty `proroot-tmp`.
     */
    fun sweepProrootConfigs(keepPid: Int? = null): ProrootConfigSweep.Plan {
        val directory = paths.prorootTmpDir()
        val files = runCatching { directory.listFiles() }.getOrNull().orEmpty()
        val entries = files
            .filter { it.name.startsWith(ProrootConfigSweep.PREFIX) }
            .map { file ->
                ProrootConfigSweep.Entry(
                    name = file.name,
                    pid = ProrootConfigSweep.pidOf(file.name),
                    modifiedMs = file.lastModified(),
                )
            }
        if (entries.isEmpty()) return ProrootConfigSweep.Plan(emptyList(), 0, 0)

        val alive = entries.mapNotNull { it.pid }
            .filter { pid -> File("/proc/$pid").exists() }
            .toSet()
        val keep = keepPid?.let { setOf(ProrootConfigSweep.name(it)) }.orEmpty()
        val plan = ProrootConfigSweep.plan(entries = entries, alivePids = alive, keepNames = keep)
        plan.delete.forEach { name -> runCatching { File(directory, name).delete() } }
        ProrootConfigSweep.capNote(plan)?.let { Log.i(TAG, it) }
        if (plan.dead > 0) {
            Log.i(TAG, "清理了 ${plan.dead} 份属主进程已不存在的 .proroot-config 文件")
        }
        return plan
    }

    /**
     * Run or read the gate for the current revision + binary digest.
     *
     * ## Why the main thread is special-cased
     *
     * The gate is three real proroot invocations (a raw-syscall probe, an `rg`/`fd` probe and
     * a dynamic-binary probe), bounded by `ProrootProbe.RAW_TIMEOUT_MS` +
     * `GuestToolProbe.TIMEOUT_MS` + `ProrootProbe.EXEC_TIMEOUT_MS` — up to ~60 s on a
     * device where something is wrong. Most callers are already on a background
     * dispatcher (the engine boots on `Dispatchers.IO`, the package commands run in
     * coroutines), but **the terminal does not**: `TerminalPane` starts its bridge from
     * a `LaunchedEffect`, i.e. on the main thread. Blocking there for a minute is an ANR,
     * and an ANR is a worse outcome than starting this one launch on proot.
     *
     * So on the main thread, with no cached verdict, the gate is **started in the
     * background** and this launch falls back to proot with a note saying the probe has
     * not run yet. The next launch — the engine's, a few hundred ms later on any normal
     * boot — finds the cached verdict. Nothing is skipped and nothing is decided on a
     * guess: the gate still has to pass before proroot is ever used.
     */
    private fun gate(storage: File?, engine: GuestEngine): ProrootProbe.Verdict {
        val revision = revision()
        val digest = runCatching { digestOf(engine) }.getOrDefault("")
        cachedVerdict(engine, revision, digest)?.let { return it }
        if (digest.isEmpty()) {
            return ProrootProbe.Verdict(
                passed = false,
                key = "unknown",
                detail = listOf("✗ 探针未运行：读不到 ${engine.name} 二进制"),
                cached = false,
            )
        }
        // 终端页从主线程启动 guest，探针要几秒到几十秒：主线程上只起后台线程、本次先回 proot，
        // 下一次启动用结论。两个引擎共用这一条规则（一次只跑一个探针）。
        if (onMainThread()) {
            if (probeInFlight.compareAndSet(false, true)) {
                val key = keyOf(engine, revision, digest)
                Thread({
                    try {
                        recordGateVerdict(runProbe(engine, storage, revision, digest), key, engine)
                    } finally {
                        probeInFlight.set(false)
                    }
                }, "pi-${engine.name.lowercase()}-gate").apply { isDaemon = true }.start()
            }
            return ProrootProbe.Verdict(
                passed = false,
                key = keyOf(engine, revision, digest),
                detail = listOf(
                    "✗ 探针尚未运行：首次使用需要在后台线程上跑一次（终端页从主线程启动）",
                    "  已在后台开始测，下一次启动 guest 时使用结论",
                ),
                cached = false,
            )
        }
        val verdict = runProbe(engine, storage, revision, digest)
        recordGateVerdict(verdict, verdict.key, engine)
        return verdict
    }

    private fun digestOf(engine: GuestEngine): String = when (engine) {
        GuestEngine.Proroot -> ProrootProbe.digestOf(paths)
        GuestEngine.Tawcroot -> TawcrootProbe.digestOf(paths)
        GuestEngine.Proot -> ""
    }

    private fun keyOf(engine: GuestEngine, revision: String, digest: String): String = when (engine) {
        GuestEngine.Tawcroot -> TawcrootProbe.key(revision, digest)
        else -> ProrootProbe.key(revision, digest)
    }

    private fun cachedVerdict(engine: GuestEngine, revision: String, digest: String): ProrootProbe.Verdict? =
        when (engine) {
            GuestEngine.Proroot -> ProrootProbe.cached(paths, revision, digest)
            GuestEngine.Tawcroot -> TawcrootProbe.cached(paths, revision, digest)
            GuestEngine.Proot -> null
        }

    private fun runProbe(engine: GuestEngine, storage: File?, revision: String, digest: String): ProrootProbe.Verdict =
        when (engine) {
            GuestEngine.Tawcroot -> TawcrootProbe.run(paths, storage, revision, digest)
            else -> ProrootProbe.run(paths, storage, revision, digest, onTimeoutTree = GuestTreeReaper::reapTimeoutedProbe)
        }

    private fun recordGateVerdict(verdict: ProrootProbe.Verdict, key: String, engine: GuestEngine) {
        if (verdict.passed) recordProrootSuccess()
        Log.i(TAG, "${engine.name} 探针（$key）：${if (verdict.passed) "通过" else "未通过"}")
        verdict.detail.forEach { Log.i(TAG, "  $it") }
    }

    /**
     * Whether this call is on the process's main thread. `Looper` is Android-only and
     * this class already is (it reads the app's preferences).
     */
    private fun onMainThread(): Boolean =
        runCatching { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }
            .getOrDefault(false)

    /** The unpacked revision the gate and its cache are keyed by. */
    private fun revision(): String = paths.stampRevision().orEmpty().ifEmpty { "unstamped" }

    private fun notesFor(
        decision: EngineDecision,
        selected: GuestEngine,
        missing: List<String>,
        probe: ProrootProbe.Verdict?,
        failures: Int,
    ): List<String> = buildList {
        add(
            when (decision.engine) {
                GuestEngine.Proroot -> "proroot（实验性）：本次 guest 进程使用 proroot"
                GuestEngine.Tawcroot -> "tawcroot（实验性）：本次 guest 进程使用 tawcroot"
                GuestEngine.Proot -> "本次 guest 进程使用 proot"
            },
        )
        // The 档 goes out with the two notes where it is part of the answer: a launch that
        // is about to use proroot has to say what it promised, and a refusal by the gate has
        // to say which promise was in force when it was refused. Not on every proot launch —
        // the switch being off is already the whole reason there, and a per-command line
        // about a runtime nobody selected is noise. One line, the same string the settings
        // row shows, so the log and the UI cannot disagree about it.
        if (decision.engine == GuestEngine.Proroot || (selected == GuestEngine.Proroot && decision.fallback == EngineFallback.ProbeNotPassed)) {
            add("proroot 档：${RuntimeChoice.PROROOT_SECCOMP.tag}（${RuntimeChoice.PROROOT_SECCOMP.disclosure}）")
        }
        if (decision.fallback != EngineFallback.None) add(RuntimeChoice.describe(decision.fallback, selected))
        when (decision.fallback) {
            EngineFallback.RuntimeFilesMissing ->
                add("nativeLibraryDir 缺少：${missing.joinToString("、")}（${selected.name}）")

            EngineFallback.ProbeNotPassed -> {
                // The verdict's own lines, and nothing else. This branch is reached only
                // when the gate was actually consulted — the failure budget is checked
                // before it ([RuntimeChoice.decide]) — so there is never a verdict to
                // invent here; a sentence claiming one would be a reading nobody took.
                probe?.detail?.forEach { add(it) }
            }

            EngineFallback.FailureStreak ->
                add("失败计数 $failures/${RuntimeChoice.MAX_CONSECUTIVE_FAILURES}；在设置里重新选择该运行时可清零重试")

            else -> Unit
        }
    }

    companion object {
        private const val TAG = "PiRuntimeSelection"

        /** For call sites that hold a `Context` (the engine, the terminal, settings). */
        fun of(context: Context, paths: PiPaths): RuntimeSelection =
            RuntimeSelection(paths, RuntimePreferences.get(context))

        /**
         * For call sites that only have [PiPaths] (the package commands and the `@`
         * mention scan). Falls back to "the switch is off" before the application
         * process exists — see [RuntimePreferences.shared].
         */
        fun of(paths: PiPaths): RuntimeSelection = RuntimeSelection(paths, RuntimePreferences.shared())
    }
}
