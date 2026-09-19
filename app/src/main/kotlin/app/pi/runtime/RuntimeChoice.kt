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
 * Which **seccomp configuration** a proroot launch runs under, and the one thing that
 * differs between the two: whether an inline `svc` call has to be translated.
 *
 * ## Why the two are not the same runtime
 *
 * proroot translates paths in two layers, and they do not come from the same machinery:
 * libc calls are intercepted in-process, while an `svc` instruction compiled *into* a
 * binary is covered by the seccomp filter proroot installs. On a device where that filter
 * cannot do its job — and the reference device is one, where the probe recorded `raw/inline
 * svc 调用没有被翻译` while every libc-level read was translated — the documented answer is
 * to run proroot **without** it (`PROROOT_NO_SECCOMP=1`, the variable DSH App runs with on
 * that same phone, and a string the pinned `libproroot.so` carries).
 *
 * So a档 is not a preference and not a fallback: it is the configuration the launch path
 * uses, it changes what proroot can promise, and it therefore has to be visible in the
 * decision (this file), in the gate that refuses proroot ([probeGate]), in the cache that
 * remembers a verdict ([ProrootProbeCache.key]) and in the settings sentence ([describe]).
 *
 * [NoSeccomp] stops treating an untranslated raw `svc` call as a reason to refuse proroot —
 * in that档 it is the documented shape, not a hole that was tolerated — and changes nothing
 * about the two outcomes that stay disqualifying: a raw read that comes back as the
 * **host's** file (libc and raw disagreeing about the same path), and an `rg`/`fd`
 * invocation that does not really work.
 */
enum class ProrootSeccomp(
    /** The token [ProrootProbeCache.key] carries, so the two档 cannot share a verdict. */
    val tag: String,
    /** The value of [ProrootCommand.NO_SECCOMP_ENV]; null when the档 needs no variable. */
    val envValue: String?,
) {
    /** proroot's default: libc calls and inline `svc` instructions both go through it. */
    Seccomp(tag = "seccomp", envValue = null),

    /** `PROROOT_NO_SECCOMP=1`: only libc calls are translated. */
    NoSeccomp(tag = "no-seccomp", envValue = "1"),
    ;

    /**
     * True only in [Seccomp]. An untranslated raw syscall is reported in both档 — it is
     * never hidden — but it refuses proroot only where translation was promised.
     */
    val requiresRawTranslation: Boolean get() = this == Seccomp

    /**
     * The one sentence a user must be able to read: which档 is live **and what it gives
     * up**. Deliberately not "等价于 seccomp 档", because it is not.
     */
    val disclosure: String
        get() = when (this) {
            Seccomp -> "seccomp 档：libc 调用与 inline svc 调用都走翻译"
            NoSeccomp -> "无 seccomp 档：只翻译 libc 调用；直接发 raw syscall 的程序会绕过翻译"
        }
}

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
     * The seccomp档 **production launches proroot under**.
     *
     * One constant, named here rather than inlined in the launcher's environment, because
     * three other things have to agree with it: what the raw probe is allowed to refuse
     * ([probeGate]), which verdict the cache key identifies ([ProrootProbeCache.key]), and
     * what the settings sentence tells the user ([describe]). A no-seccomp behaviour that
     * no surface names is the failure mode this constant exists to make impossible.
     */
    val PROROOT_SECCOMP: ProrootSeccomp = ProrootSeccomp.NoSeccomp

    /**
     * The probe gate's rule — **the whole of it**, pure, so the `proroot` harness executes
     * it instead of a copy of it.
     *
     * Three inputs, and the order they are combined in is the safety property:
     *
     *  1. **A leak refuses proroot in every档.** `rawVetoed` is [ProrootRawProbe.Report.leaked]
     *     — a raw read that returned the *host's* file where libc returned the guest's. It is
     *     the one outcome that is silent everywhere else, and no档 relaxes it.
     *  2. **The `rg`/`fd` real invocation must have worked** (`toolsOk`). It is the gate's
     *     decisive measurement in both档: it is the only one that proves path translation on
     *     a real guest path, and in [ProrootSeccomp.NoSeccomp] it is the only one left that
     *     still can refuse proroot. A probe that could not run is not a pass.
     *  3. **Raw translation is required only where it was promised**
     *     ([ProrootSeccomp.requiresRawTranslation]). In the seccomp档 an untranslated raw
     *     syscall is a hole and refuses proroot; in the no-seccomp档 it is the documented
     *     shape of the档, so [rawTranslated] is reported and not consulted.
     */
    fun probeGate(
        mode: ProrootSeccomp,
        /** [ProrootRawProbe.Report.leaked]: the disqualifying reading, in both档. */
        rawVetoed: Boolean,
        /** [ProrootRawProbe.Report.translated]: consulted only in the seccomp档. */
        rawTranslated: Boolean,
        /** [GuestToolProbe.Report.ok]: required, never inferred. */
        toolsOk: Boolean,
    ): Boolean = when {
        rawVetoed -> false
        !toolsOk -> false
        mode.requiresRawTranslation -> rawTranslated
        else -> true
    }

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

    /**
     * One sentence for the settings row and the diagnostic report — the **evidence-free**
     * form.
     *
     * [EngineFallback.ProbeNotPassed] comes out as "已回退 proot：探针未通过", which is
     * the half-sentence a user cannot act on, because the probe records *which* stage
     * refused. A caller that holds the cached evidence must use
     * [ProrootProbeNarrative.summary], which appends the failing stage and the recorded
     * line to exactly this sentence; this overload stays the form for callers that
     * genuinely have no evidence (the report leaves for the probe: its own notes) and
     * for the harness's "every reason has a sentence" check.
     *
     * [EngineFallback.ProbeNotRun] spells out the three-launch sequence on purpose: the
     * first launch after the switch is turned on is the one that runs the probe **and
     * still uses proot**, and only a later launch can end up on proroot. The old wording
     * ("首次使用时会自动跑一次") described the probe but not what the user sees in
     * between, which is the sentence users got stuck on.
     *
     * The [EngineFallback.None] sentence names the [PROROOT_SECCOMP] in effect **and what
     * that档 gives up**, in the same line: a runtime that quietly traded inline-`svc`
     * translation away would be behaviour the user cannot see, which is the same defect as
     * a row that says "proroot" while every launch falls back to proot. It is one clause,
     * not a manual — the row renders it as one line.
     */
    fun describe(fallback: EngineFallback): String = when (fallback) {
        EngineFallback.None -> "proroot 正在使用（${PROROOT_SECCOMP.disclosure}）"
        EngineFallback.SwitchOff -> "未开启（走 proot）"
        EngineFallback.RuntimeFilesMissing -> "已回退 proot：运行时文件缺失"
        EngineFallback.ProbeNotPassed -> "已回退 proot：探针未通过"
        EngineFallback.ProbeNotRun ->
            "已回退 proot：探针尚未运行——下一次启动 guest 会跑一次，那一次仍用 proot，再下一次才可能接管"

        EngineFallback.FailureStreak -> "已回退 proot：连续 $MAX_CONSECUTIVE_FAILURES 次启动失败"
        EngineFallback.InstallPath -> "安装与维护固定走 proot"
    }
}
