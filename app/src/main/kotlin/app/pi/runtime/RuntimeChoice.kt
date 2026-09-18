package app.pi.runtime

/**
 * Which container runtime launches a guest process.
 *
 * `Proot` is the app's pinned Termux proot recipe and the **only** one that is ever
 * used by the install/maintenance path. `Proroot` is the opt-in, closed-source,
 * faster runtime (`docs/proroot-research.md`).
 */
enum class GuestEngine {
    Proot,
    Proroot,
}

/**
 * Why the decision came out as [GuestEngine.Proot].
 *
 * A reason is always produced, including the ordinary "the switch is off" case:
 * the settings row and the diagnostic report both render it, and "unavailable"
 * without a reason is the shape this repository keeps having to fix (a value that
 * looks like a reading but is a default).
 */
enum class EngineFallback {
    /** proroot is in use. */
    None,

    /** The user has not turned it on (the default, and the only default). */
    SwitchOff,

    /** One of the five `.so` files is missing from `nativeLibraryDir`. */
    RuntimeFilesMissing,

    /** The probe gate has not passed at this runtime revision + binary digest. */
    ProbeNotPassed,

    /** The gate has not run yet at this revision; the first real launch will run it. */
    ProbeNotRun,

    /** Three consecutive proroot launch failures: forced back to proot. */
    FailureStreak,

    /**
     * This call site is on the install/maintenance path, which is **always** proot
     * regardless of the switch. Not a fallback: a division of labour
     * (`docs/proroot-research.md` §7.1 — "不参与装机路径（解压、安装六步一律用
     * proot），只影响「执行命令」这一层", and §9.2 item 3).
     */
    InstallPath,
}

/** The decision, plus the reason when it is proot. */
data class EngineDecision(
    val engine: GuestEngine,
    val fallback: EngineFallback,
)

/**
 * The pure half of the runtime selection: **four booleans in, one engine out**.
 *
 * This is separated from the code that reads files, runs the guest and persists the
 * failure counter ([RuntimeSelection]) on purpose. It is the part that decides
 * whether a closed-source component is used, it has no device to test it on until
 * it is shipped, and it is cheap to pin exhaustively in a bare-JVM harness — which
 * is what `tools/run-app-pure-checks.sh`'s `proroot` harness does.
 *
 * The order of the conditions is load-bearing and is not the order of the enum:
 * the switch is checked first (so a user who never opted in can never reach
 * proroot), then the two availability gates, then the failure budget. Every one of
 * them has to hold for proroot; any one of them puts the app back on proot.
 *
 * ## Where the three-layer fallback comes from
 *
 * `docs/proroot-research.md` §7.1 quotes DSH App's own notice: proroot defaults on
 * there, does not participate in the install path, falls back to proot when the
 * runtime files are missing, and **forces proot after three consecutive launch
 * failures while telling the user**. That is what makes shipping a closed-source
 * runtime acceptable to its author: "最坏情况是这一层退回 proot，不会导致环境不可用".
 * We copy the machinery and keep the user's own decision — **never default on**,
 * because this is a general-purpose app and DSH App is the author's own.
 */
object RuntimeChoice {

    /**
     * Consecutive proroot launch failures after which proroot is abandoned until
     * the user toggles the switch again. Three, from DSH App's contract (§7.1).
     */
    const val MAX_CONSECUTIVE_FAILURES: Int = 3

    /** The five proroot binaries, by the names `jniLibs` must ship (`runtime.lock.json`). */
    val REQUIRED_FILES: List<String> = listOf(
        "libproroot.so",
        "libproroot-runtime.so",
        "libproroot-linker.so",
        "libproroot-bridge.so",
        "libproroot-stub-loader.so",
    )

    /**
     * @param enabled the user's switch. **Off by default and never on by itself.**
     * @param filesPresent all five [REQUIRED_FILES] are in `nativeLibraryDir`
     *        (`[DSHA]` fallback ①).
     * @param probePassed the gate of `docs/proroot-research.md` §9.6 step 1 passed
     *        at this runtime revision and binary digest.
     * @param consecutiveFailures persisted count of proroot launches that failed
     *        before doing anything (`[DSHA]` fallback ②).
     */
    fun decide(
        enabled: Boolean,
        filesPresent: Boolean,
        probePassed: Boolean,
        consecutiveFailures: Int,
    ): EngineDecision = when {
        !enabled -> EngineDecision(GuestEngine.Proot, EngineFallback.SwitchOff)
        !filesPresent -> EngineDecision(GuestEngine.Proot, EngineFallback.RuntimeFilesMissing)
        !probePassed -> EngineDecision(GuestEngine.Proot, EngineFallback.ProbeNotPassed)
        consecutiveFailures >= MAX_CONSECUTIVE_FAILURES ->
            EngineDecision(GuestEngine.Proot, EngineFallback.FailureStreak)

        else -> EngineDecision(GuestEngine.Proroot, EngineFallback.None)
    }

    /** The counter after a proroot launch that failed before producing a result. */
    fun afterFailure(consecutiveFailures: Int): Int = consecutiveFailures + 1

    /**
     * The counter after a proroot launch that worked. Any success clears the streak:
     * the contract is *consecutive* failures, and a runtime that just served a
     * command is demonstrably usable.
     */
    fun afterSuccess(): Int = 0

    /** True when [consecutiveFailures] alone is enough to refuse proroot. */
    fun exhausted(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= MAX_CONSECUTIVE_FAILURES

    /**
     * Whether this switch write must throw away the gate's **cached** verdict.
     *
     * ## The defect this exists for
     *
     * The verdict is cached because it is expensive (two real guest probes) and keyed by
     * the unpacked revision plus the digest of the five binaries — nothing else. So a
     * verdict of *"未通过"* outlives the reason it was reached: a probe that failed once
     * for a transient cause (a `/tmp` that could not be written at that moment, a device
     * under memory pressure, a launcher that lost a race) would keep proroot disabled
     * **forever**, and the settings row's own promise — "关掉再打开就是再试一次" — would
     * silently do nothing, because the key had not changed. That is the same "a switch
     * that cannot do anything" shape this repository keeps removing, one layer down.
     *
     * So the rule is: **turning the switch on is a retry**, and it invalidates the cached
     * verdict, exactly as it already clears the consecutive-failure counter
     * ([RuntimePreferences.setProrootEnabled]).
     *
     * Turning it *off* does not invalidate anything. The verdict is a measurement of the
     * runtime tree, not of the switch, and there is then nothing to re-earn — discarding
     * it on the way out would only make the next on-transition's behaviour harder to
     * reason about (it would always be a first run, which is precisely what the cache
     * exists to avoid).
     *
     * Pure, so the harness pins both directions — and, just as importantly, pins that
     * the cache **key** is insensitive to the switch: without this invalidation the key
     * would happily serve the stale failure, which is how the defect stayed invisible.
     */
    fun invalidatesProbeCache(nowEnabled: Boolean): Boolean = nowEnabled

    /** One sentence for the settings row and the diagnostic report. */
    fun describe(fallback: EngineFallback): String = when (fallback) {
        EngineFallback.None -> "proroot 正在使用"
        EngineFallback.SwitchOff -> "未开启（走 proot）"
        EngineFallback.RuntimeFilesMissing -> "已回退 proot：运行时文件缺失"
        EngineFallback.ProbeNotPassed -> "已回退 proot：探针未通过"
        EngineFallback.ProbeNotRun -> "已回退 proot：探针尚未运行（首次使用时会自动跑一次）"
        EngineFallback.FailureStreak -> "已回退 proot：连续 $MAX_CONSECUTIVE_FAILURES 次启动失败"
        EngineFallback.InstallPath -> "安装与维护固定走 proot"
    }
}
