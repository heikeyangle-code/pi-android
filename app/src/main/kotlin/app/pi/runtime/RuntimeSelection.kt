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

    /** The user's standing choice, as the settings row reads it. */
    val prorootEnabled: Boolean get() = prefs?.prorootEnabled ?: false

    /**
     * Write the switch, keeping everything that means "try again" together.
     *
     * The preference itself clears the consecutive-failure counter
     * ([RuntimePreferences.setProrootEnabled]). This method adds the other half: when the
     * switch goes **on**, the gate's cached verdict is deleted, because that verdict is
     * keyed by the runtime revision and the binary digest — neither of which a switch
     * changes — so a cached *failure* would otherwise survive the retry and proroot would
     * stay disabled for good. [RuntimeChoice.invalidatesProbeCache] carries the full
     * case and the reason the *off* direction does not need it.
     *
     * The deletion has to happen here rather than in the preference: this is the layer
     * that holds both the pref store and [PiPaths], and the point of the split is that
     * reading the switch (a high-frequency call — it is read on every guest launch) never
     * touches the filesystem.
     */
    fun setProrootEnabled(enabled: Boolean) {
        var removed = false
        // The transition itself lives in [ProrootRetry] so that the rule — counter always,
        // cached verdict only when the switch goes on — is executed by the harness on a
        // bare JVM with these two effects injected, rather than re-implemented there.
        ProrootRetry.apply(
            nowEnabled = enabled,
            resetFailures = { prefs?.setProrootEnabled(enabled) },
            invalidateProbeCache = {
                removed = runCatching { paths.clearProrootProbeCache() }.getOrDefault(false)
                removed
            },
        )
        if (enabled) {
            Log.i(TAG, if (removed) "开关重新打开：已删除探针缓存，下次启动 guest 时重跑门禁" else "开关重新打开：没有探针缓存需要删除")
        }
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
    ) {
        val usingProroot: Boolean get() = engine == GuestEngine.Proroot

        /**
         * One line for the settings row.
         *
         * Goes through [ProrootProbeNarrative] so a refusal names the stage that refused
         * instead of stopping at "探针未通过": [probe] is this plan's own verdict when the
         * gate was consulted, and every other fallback needs no evidence. Pure string
         * work over data already in hand — see the narrative's KDoc.
         */
        val summary: String
            get() = ProrootProbeNarrative.summary(fallback, probe?.detail.orEmpty())
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
            get() = ProrootProbeNarrative.summary(fallback, probeDetail, mode)
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
        // ---- the second opt-in engine, asked first (2026-09-25) --------------------
        // The order is a decision: with **both** switches on the user has asked for two
        // runtimes, and bxroot is the one whose gate is cheapest to abandon — if it does
        // not pass, the proroot path below runs exactly as it did before this engine
        // existed. Nothing here is consulted while bxroot's own switch is off, which is
        // the same "if it is not on, there is nothing to check" rule the proroot branch
        // states for itself.
        val bxrootEnabled = allowProroot && (prefs?.bxrootEnabled ?: false)
        val bxrootMissing = if (bxrootEnabled) paths.missingBxrootComponents() else emptyList()
        val bxrootFailures = prefs?.bxrootFailures ?: 0
        var bxrootProbe: ProrootProbe.Verdict? = null
        val bxrootProbePassed =
            if (bxrootEnabled && bxrootMissing.isEmpty() && !RuntimeChoice.exhausted(bxrootFailures)) {
                bxrootProbe = gate(storage, GuestEngine.Bxroot)
                bxrootProbe.passed
            } else {
                false
            }
        val bxrootDecision = if (!allowProroot) {
            EngineDecision(GuestEngine.Proot, EngineFallback.InstallPath)
        } else {
            RuntimeChoice.decideBxroot(
                enabled = bxrootEnabled,
                filesPresent = bxrootMissing.isEmpty(),
                probePassed = bxrootProbePassed,
                consecutiveFailures = bxrootFailures,
            )
        }
        val bxrootWins = bxrootDecision.engine == GuestEngine.Bxroot

        val enabled = !bxrootWins && allowProroot && (prefs?.prorootEnabled ?: false)
        // **Nothing proroot-shaped is touched while the switch is off** — not the five
        // `stat`s, not the config-table listing. The decision below cannot use either
        // of them in that case (`RuntimeChoice.decide` returns `SwitchOff` on its first
        // condition), so computing them would be work whose result is provably
        // discarded, on a path that runs on every guest start. The user asked for
        // exactly this: 「如果没打开，根本没必要检查」.
        val missing = if (enabled) paths.missingProrootComponents() else emptyList()
        val filesPresent = missing.isEmpty()
        val failures = prefs?.prorootFailures ?: 0

        // The gate is consulted only when it could change the answer: a switch that
        // is off must never run a proroot process, and a streak that has already
        // exhausted proroot must not be given another chance by a probe.
        var probe: ProrootProbe.Verdict? = null
        val probePassed = if (enabled && filesPresent && !RuntimeChoice.exhausted(failures)) {
            probe = gate(storage)
            probe.passed
        } else {
            false
        }

        val decision = when {
            bxrootWins -> bxrootDecision
            !allowProroot -> EngineDecision(GuestEngine.Proot, EngineFallback.InstallPath)
            else -> RuntimeChoice.decide(enabled, filesPresent, probePassed, failures)
        }
        // The evidence the plan carries is the **winning** engine's: a summary that mixed
        // bxroot's probe lines with proroot's missing-file list would describe neither.
        val reportedMissing = if (bxrootWins) bxrootMissing else missing
        val reportedProbe = if (bxrootWins) bxrootProbe else probe
        val reportedFailures = if (bxrootWins) bxrootFailures else failures

        // Only while proroot can actually launch. The sweep is the cleanup proroot's own
        // config tables get, and the only moment one can appear is a proroot launch —
        // so off means there is nothing new to collect. Tables already on disk from an
        // earlier enabled period are collected by the next enabled launch (and a runtime
        // revision change wipes the whole tree anyway), which is why gating this on the
        // switch loses no cleanup. It is also a no-op when the directory does not exist,
        // because it looks through [PiPaths.prorootTmpDir] and creates nothing.
        if (enabled) sweepProrootConfigs()

        // A per-launch identity, carried in the launcher's environment and read back
        // from `/proc/<pid>/environ` when the launch's pid is resolved. The pid has to
        // be exact — it is what gets signalled on stop — and "the config table that
        // appeared since I armed" is ambiguous when two launches overlap
        // ([ProrootLaunchHandle] carries the argument). Proot plans get no token: they
        // are never reaped by pid, and a guest environment should not grow a variable
        // nothing reads.
        val token = if (decision.engine.usesOptInPlumbing) UUID.randomUUID().toString() else null
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
            notes = notesFor(decision, reportedMissing, reportedProbe, reportedFailures),
            probe = probe,
            launchToken = token,
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
    fun status(): Status {
        // bxroot first, mirroring plan(): a status line that said "proot" while every
        // launch ran bxroot would be the same defect class this file's reasons exist for.
        val bxrootEnabled = prefs?.bxrootEnabled ?: false
        val bxrootFailures = prefs?.bxrootFailures ?: 0
        val bxrootMissing = if (bxrootEnabled) paths.missingBxrootComponents() else emptyList()
        val bxrootCached = if (bxrootEnabled && bxrootMissing.isEmpty()) {
            runCatching {
                ProrootProbe.cached(
                    paths,
                    revision(),
                    ProrootProbe.digestOf(paths, GuestEngine.Bxroot),
                    GuestEngine.Bxroot,
                )
            }.getOrNull()
        } else {
            null
        }
        val bxrootDecision = when {
            !bxrootEnabled -> EngineDecision(GuestEngine.Proot, EngineFallback.BxrootSwitchOff)
            bxrootMissing.isNotEmpty() -> EngineDecision(GuestEngine.Proot, EngineFallback.RuntimeFilesMissing)
            RuntimeChoice.exhausted(bxrootFailures) ->
                EngineDecision(GuestEngine.Proot, EngineFallback.BxrootFailureStreak)
            bxrootCached == null -> EngineDecision(GuestEngine.Proot, EngineFallback.ProbeNotRun)
            !bxrootCached.passed -> EngineDecision(GuestEngine.Proot, EngineFallback.BxrootProbeNotPassed)
            else -> EngineDecision(GuestEngine.Bxroot, EngineFallback.BxrootActive)
        }
        if (bxrootDecision.engine == GuestEngine.Bxroot) {
            return Status(
                enabled = bxrootEnabled,
                failures = bxrootFailures,
                missingComponents = bxrootMissing,
                probePassed = bxrootCached?.passed,
                probeDetail = bxrootCached?.detail.orEmpty(),
                engine = GuestEngine.Bxroot,
                fallback = EngineFallback.BxrootActive,
            )
        }

        val enabled = prefs?.prorootEnabled ?: false
        val failures = prefs?.prorootFailures ?: 0
        // Same rule as [plan]: a switch that is off is answered by `SwitchOff` before
        // anything else is consulted, so the five `stat`s (and the digest behind them)
        // would be work whose result is discarded. The settings row and the diagnostic
        // report call this, and both are opened by users who never enabled proroot.
        val missing = if (enabled) paths.missingProrootComponents() else emptyList()
        val cached = if (enabled && missing.isEmpty()) {
            runCatching { ProrootProbe.cached(paths, revision(), ProrootProbe.digestOf(paths)) }.getOrNull()
        } else {
            null
        }
        val decision = when {
            !enabled -> EngineDecision(GuestEngine.Proot, EngineFallback.SwitchOff)
            missing.isNotEmpty() -> EngineDecision(GuestEngine.Proot, EngineFallback.RuntimeFilesMissing)
            RuntimeChoice.exhausted(failures) -> EngineDecision(GuestEngine.Proot, EngineFallback.FailureStreak)
            cached == null -> EngineDecision(GuestEngine.Proot, EngineFallback.ProbeNotRun)
            !cached.passed -> EngineDecision(GuestEngine.Proot, EngineFallback.ProbeNotPassed)
            else -> EngineDecision(GuestEngine.Proroot, EngineFallback.None)
        }
        return Status(
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
    fun recordProrootFailure(reason: String) = recordFailure(GuestEngine.Proroot, reason)

    /**
     * A launch of [engine] that failed **before producing a result**, counted against that
     * engine's own streak.
     *
     * Per engine because the switches are independent: three bxroot failures must not spend
     * the proroot switch's credit, and vice versa. The sentence keeps the engine's name so a
     * log reader can tell the two counters apart without knowing which switch was on.
     */
    fun recordFailure(engine: GuestEngine, reason: String) {
        val store = prefs ?: return
        if (!engine.usesOptInPlumbing) return
        val next = RuntimeChoice.afterFailure(store.failures(engine))
        store.setFailures(engine, next)
        Log.w(TAG, "${engine.name.lowercase()} 启动失败（$next/${RuntimeChoice.MAX_CONSECUTIVE_FAILURES}）：$reason")
        if (RuntimeChoice.exhausted(next)) {
            // The "tell the user" half of DSH App's contract. The reachable surfaces
            // are the settings row (its summary reads `status()`), the diagnostic
            // report, and this log line: there is no toast channel inside the app
            // process, and inventing one would need the chat/session layer this
            // change deliberately does not touch.
            Log.w(
                TAG,
                "${engine.name.lowercase()} 已连续失败 $next 次，强制回退 proot；用户重新打开开关可清零重试",
            )
        }
    }

    /** A proroot launch that worked. Clears the streak — it is *consecutive*. */
    fun recordProrootSuccess() = recordSuccess(GuestEngine.Proroot)

    /** An opt-in launch that worked: clears **that engine's** streak, and only its own. */
    fun recordSuccess(engine: GuestEngine) {
        val store = prefs ?: return
        if (!engine.usesOptInPlumbing) return
        if (store.failures(engine) != 0) store.setFailures(engine, 0)
    }

    /**
     * [setProrootEnabled] for the second opt-in engine: write the switch and, when it goes
     * **on**, delete bxroot's own cached verdict so the retry is a real retry.
     *
     * Deliberately not `setProrootEnabled(engine == Proroot && enabled)`: touching the other
     * engine's switch here would make one row's write change the other row's state, which is
     * the "switch says one thing, engine does another" shape both of these switches exist
     * to avoid.
     */
    fun setBxrootEnabled(enabled: Boolean) {
        var removed = false
        ProrootRetry.apply(
            nowEnabled = enabled,
            resetFailures = { prefs?.setBxrootEnabled(enabled) },
            invalidateProbeCache = {
                removed = runCatching { paths.clearBxrootProbeCache() }.getOrDefault(false)
                removed
            },
        )
        if (enabled) {
            Log.i(TAG, if (removed) "bxroot 开关重新打开：已删除探针缓存" else "bxroot 开关重新打开：没有探针缓存需要删除")
        }
    }

    /** Write the switch of [engine]; the settings row and the switch action both use this. */
    fun setEnabled(engine: GuestEngine, enabled: Boolean) = when (engine) {
        GuestEngine.Bxroot -> setBxrootEnabled(enabled)
        else -> setProrootEnabled(enabled)
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
    private fun gate(storage: File?, engine: GuestEngine = GuestEngine.Proroot): ProrootProbe.Verdict {
        val revision = revision()
        val digest = runCatching { ProrootProbe.digestOf(paths, engine) }.getOrDefault("")
        ProrootProbe.cached(paths, revision, digest, engine)?.let { return it }
        // A missing digest means we could not even read the binaries; do not cache a
        // verdict that was never measured.
        if (digest.isEmpty()) {
            return ProrootProbe.Verdict(
                passed = false,
                key = "unknown",
                detail = listOf("✗ 探针未运行：读不到 ${engine.name.lowercase()} 二进制"),
                cached = false,
            )
        }
        if (onMainThread()) {
            if (probeInFlight.compareAndSet(false, true)) {
                val key = ProrootProbe.key(revision, digest)
                Thread({
                    try {
                        val verdict = ProrootProbe.run(
                            paths,
                            storage,
                            revision,
                            digest,
                            // The probe hands the launcher's identity back at a stage timeout,
                            // before it kills the direct child, so this — the Android side —
                            // can capture and reap the tree. The probe file itself stays
                            // Android-free; see `ProrootProbe.run`'s KDoc.
                            onTimeoutTree = GuestTreeReaper::reapTimeoutedProbe,
                            engine = engine,
                        )
                        recordGateVerdict(verdict, key)
                    } finally {
                        probeInFlight.set(false)
                    }
                }, "pi-" + engine.name.lowercase() + "-gate").apply { isDaemon = true }.start()
            }
            return ProrootProbe.Verdict(
                passed = false,
                key = ProrootProbe.key(revision, digest),
                detail = listOf(
                    "✗ 探针尚未运行：首次使用需要在后台线程上跑一次（终端页从主线程启动）",
                    "  已在后台开始测，下一次启动 guest 时使用结论",
                ),
                cached = false,
            )
        }
        val verdict = ProrootProbe.run(
            paths,
            storage,
            revision,
            digest,
            // Same seam as the background call above: the reaper is Android and lives in this
            // class's package, the probe is not, and there is no default that could silently
            // skip the reaping.
            onTimeoutTree = GuestTreeReaper::reapTimeoutedProbe,
            engine = engine,
        )
        recordGateVerdict(verdict, verdict.key)
        return verdict
    }

    /** Log and account for a gate verdict, from either thread. */
    private fun recordGateVerdict(verdict: ProrootProbe.Verdict, key: String) {
        if (verdict.passed) recordProrootSuccess()
        Log.i(TAG, "proroot 探针（$key）：${if (verdict.passed) "通过" else "未通过"}")
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
    private fun revision(): String = runCatching {
        paths.stampFile().readText().trim()
    }.getOrNull().orEmpty().ifEmpty { "unstamped" }

    private fun notesFor(
        decision: EngineDecision,
        missing: List<String>,
        probe: ProrootProbe.Verdict?,
        failures: Int,
    ): List<String> = buildList {
        add(decision.engine.let { engine ->
            if (engine == GuestEngine.Proroot) "proroot（实验性）：本次 guest 进程使用 proroot" else "本次 guest 进程使用 proot"
        })
        // The 档 goes out with the two notes where it is part of the answer: a launch that
        // is about to use proroot has to say what it promised, and a refusal by the gate has
        // to say which promise was in force when it was refused. Not on every proot launch —
        // the switch being off is already the whole reason there, and a per-command line
        // about a runtime nobody selected is noise. One line, the same string the settings
        // row shows, so the log and the UI cannot disagree about it.
        if (decision.engine == GuestEngine.Proroot || decision.fallback == EngineFallback.ProbeNotPassed) {
            add("proroot 档：${RuntimeChoice.PROROOT_SECCOMP.tag}（${RuntimeChoice.PROROOT_SECCOMP.disclosure}）")
        }
        if (decision.fallback != EngineFallback.None) add(RuntimeChoice.describe(decision.fallback))
        when (decision.fallback) {
            EngineFallback.RuntimeFilesMissing ->
                add("nativeLibraryDir 缺少：${missing.joinToString("、")}")

            EngineFallback.ProbeNotPassed -> {
                probe?.detail?.forEach { add(it) }
                if (probe == null) add("探针的缓存结论是未通过")
            }

            EngineFallback.FailureStreak ->
                add("失败计数 $failures/${RuntimeChoice.MAX_CONSECUTIVE_FAILURES}；重新打开开关可清零重试")

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
