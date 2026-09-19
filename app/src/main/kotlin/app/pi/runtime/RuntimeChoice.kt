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
 * ## What was believed, and what the evidence says (2026-09-19)
 *
 * proroot translates paths in two layers: libc calls are intercepted in-process, and an
 * `svc` instruction compiled *into* a binary is covered by the seccomp `RET_TRAP` filter
 * proroot installs (`PROROOT_SIGSYS_LOG_HOST_PATH`, upstream v1.2.4's "narrow path/exec
 * seccomp fallback filters"). The reference device recorded
 * `raw/inline svc 调用没有被翻译` while every libc-level read was translated, which read
 * as "this kernel's seccomp cannot do the job" — and the planned answer was to run
 * proroot without that layer via `PROROOT_NO_SECCOMP=1`, a string the pinned
 * `libproroot.so` carries and a variable DSH App's *children* carry.
 *
 * Three measurements overturned that reading, and they are why [PROROOT_SECCOMP] is
 * [Seccomp] rather than [NoSeccomp]:
 *
 *  1. **The variable has no reader in v1.2.8.** Of the five pinned binaries
 *     (byte-identical to the published v1.2.8 assets, sha256 `a4e74d75…` /
 *     `8c47a0a7…` / `51a0ec5b…` / `1c5bc953…` / `06c6624d…`), only `libproroot.so`
 *     contains the name at all — and there it appears exactly twice: a `getenv` inside
 *     the `PROROOT_VERBOSE` line `[proroot] launcher: env PROROOT_NO_SECCOMP=%s`
 *     (`0x90b4`), and `setenv("PROROOT_NO_SECCOMP","1",1)` on the unconditional path
 *     that prepares the **child** environment (`0xa088`). `libproroot-runtime.so`,
 *     `-linker.so`, `-bridge.so` and `-stub-loader.so` do not mention it. Upstream's
 *     README environment table (five variables: `VERBOSE`, `GUEST_EXE`, `TMP_DIR`,
 *     `STUB_LOADER`, `LOG_APPEND`) and every release note from v1.2.2 to v1.2.8 omit
 *     it. So the launcher **labels every guest child `no-seccomp`** and nothing acts
 *     on the label: the value is not a configuration, it is a comment.
 *  2. **DSHA — the reference implementation — does not set it.** Its live launcher
 *     (`/proc/<pid>/environ` on this device) exports exactly `PROROOT_TMP_DIR`,
 *     `PROROOT_LIB_PATH`, `PROROOT_LINKER_PATH`, `PROROOT_STUB_LOADER`. What earlier
 *     measurement saw in a *child* was the launcher's own `setenv`, not DSHA's input.
 *  3. **Raw translation works on this device, in that state.** Running
 *     [ProrootRawProbe]'s own Perl probe inside a working DSHA guest here — with
 *     `PROROOT_NO_SECCOMP=1` in its environment — returned
 *     `guestpath=translated hostpath=unreachable passwd=translated`, and
 *     `/tmp/proroot-sigsys-last.txt` refreshed with `SIGSYS trapped syscall=439`
 *     (arm64 `faccessat2`) at the same moment, i.e. the seccomp trap layer is *active*.
 *
 * The device's own failure had a different cause entirely and is fixed in
 * [ProrootCommand.bindArgument]. So [Seccomp] is the configuration production uses: the
 * launcher's default, where both layers are promised **and delivered**.
 *
 * ## Why the alternative is still here
 *
 * [NoSeccomp] is not reachable by setting anything in v1.2.8, but it is not deleted: it
 * is the honest description of the configuration the plan originally called for, the
 * gate's rule is genuinely different under it, and the cache key must be able to keep
 * the two apart the day a launcher honours the variable. Keeping it named is what makes
 * "this档 needs no raw translation" a decision with a name instead of a condition
 * buried in a parser; **shipping it as production would have been a claim the device
 * measurement contradicts.**
 */
enum class ProrootSeccomp(
    /** The token [ProrootProbeCache.key] carries, so the two档 cannot share a verdict. */
    val tag: String,
    /** The value of [ProrootCommand.NO_SECCOMP_ENV]; null when the档 needs no variable. */
    val envValue: String?,
) {
    /**
     * proroot's default and the configuration this app ships: libc calls *and* inline
     * `svc` instructions are translated, the latter through the launcher's seccomp
     * `RET_TRAP` filter (`libproroot.so` sets the diagnostic variable for its children,
     * so this is also the state [NoSeccomp] would describe — see the class KDoc).
     */
    Seccomp(tag = "seccomp", envValue = null),

    /**
     * `PROROOT_NO_SECCOMP=1`: only libc calls are translated. **Not reachable in
     * v1.2.8** — nothing reads the variable — so this is a specification, not a
     * setting the app can select.
     */
    NoSeccomp(tag = "no-seccomp", envValue = "1"),
    ;

    /**
     * True only in [Seccomp]. An untranslated raw syscall is reported in both档 — it is
     * never hidden — but it refuses proroot only where translation was promised.
     */
    val requiresRawTranslation: Boolean get() = this == Seccomp

    /** The two-character-wide label a one-line sentence can carry. */
    val shortLabel: String
        get() = when (this) {
            Seccomp -> "默认档"
            NoSeccomp -> "无 seccomp 档"
        }

    /**
     * The one sentence a user must be able to read: which档 is live **and what it gives
     * up**. Deliberately not "等价于 seccomp 档", because it is not.
     */
    val disclosure: String
        get() = when (this) {
            Seccomp -> "默认档（seccomp 兜底）：libc 调用与 inline svc 调用都走翻译"
            NoSeccomp -> "无 seccomp 档（v1.2.8 不可选，未启用）：只翻译 libc 调用；" +
                "直接发 raw syscall 的程序会绕过翻译"
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
     *
     * It is [ProrootSeccomp.Seccomp] — the launcher's default — and the enum's KDoc is the
     * evidence that the alternative is not a reachable configuration in v1.2.8. This is a
     * **stricter** setting than the plan's `NoSeccomp`: under it an untranslated raw
     * syscall still refuses proroot.
     */
    val PROROOT_SECCOMP: ProrootSeccomp = ProrootSeccomp.Seccomp

    /**
     * The probe gate's rule — **the whole of it**, pure, so the `proroot` harness executes
     * it instead of a copy of it.
     *
     * Four inputs, and the order they are combined in is the safety property:
     *
     *  1. **A leak refuses proroot in every档.** `rawVetoed` is [ProrootRawProbe.Report.leaked]
     *     — a raw read that returned the *host's* file where libc returned the guest's. It is
     *     the one outcome that is silent everywhere else, and no档 relaxes it.
     *  2. **The `rg`/`fd` real invocation must have worked** (`toolsOk`). It is the gate's
     *     decisive measurement in both档: it is the only one that proves path translation on
     *     a real guest path, and in [ProrootSeccomp.NoSeccomp] it is the only one left that
     *     still can refuse proroot. A probe that could not run is not a pass.
     *  3. **The engine's own class of binary must really run** (`execOk`,
     *     [ProrootExecProbe]). Node is a dynamically linked glibc ELF, and on 2026-09-19 the
     *     other two measurements passed while the engine exited 126 — so this input is what
     *     makes the verdict an answer about the process the app cannot do without. It is
     *     required in **every**档 and it is **all-or-nothing**: a runtime that cannot start
     *     the engine is refused entirely rather than used for the launch paths that happen to
     *     work. The only mixture this app allows is the pre-existing `allowProroot = false`
     *     install/maintenance line (`RuntimeSelection`), which is a division of labour and
     *     not a second verdict.
     *  4. **Raw translation is required only where it was promised**
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
        /** [ProrootExecProbe.Report.ok]: required, never inferred. See the KDoc. */
        execOk: Boolean,
    ): Boolean = when {
        rawVetoed -> false
        !toolsOk -> false
        !execOk -> false
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
     * ## 这一行答的是「在跑哪个」，不是「开关在哪一档」
     *
     * 读它的那一行标题是「运行时（实际生效）」（`app.runtime.prorootStatus`），所以每个值
     * 都**先写实际在跑的那个运行时**（`proot` / `proroot`），原因才跟在括号里。旧的一组句子
     * 是反过来的：关掉时它写「未开启（走 proot）」——把开关的状态当成了运行时的状态，于是
     * 一个问「在跑哪个」的行回答「没打开」，用户直接问了出来（「关掉 Pro Root 为什么要写着
     * 未开启」）。原因仍然逐条写明，因为它们各自指向一件不同的事，而这一行又紧贴在开关下面：
     * 探针没过（哪一档由 [ProrootProbeNarrative.summary] 补上）、运行时文件缺失、连续失败、
     * 装机路径不参与 proroot。
     *
     * [EngineFallback.ProbeNotPassed] 的这句是**半句话**：用户没法据它行动，因为探针会记下
     * *哪一阶段* 拒绝了。手里有缓存证据的调用者必须用 [ProrootProbeNarrative.summary]，它
     * 把失败的阶段与探针原话接在这句后面；这个重载留给真没有证据的调用者（报告的备注）与
     * harness 的「每个理由都有一句话」检查。
     *
     * [EngineFallback.None] 那句仍然写清生效的 [PROROOT_SECCOMP] **以及这个档放弃了什么**：
     * 一个悄悄把 inline-`svc` 翻译换掉的运行时是用户看不见的行为，与「行上写 proroot、每次
     * 启动却回退」是同一类缺陷。它是一句话，不是说明书——行只画一行。
     *
     * ## 为什么 [EngineFallback.ProbeNotRun] 不再讲「下一次 / 再下一次」
     *
     * 那段三趟车的说明描述的是旧行为：拨开开关**只**写下偏好，探针要等下一次启动 guest 才
     * 跑，那一次仍走 proot，再下一次才可能接管。现在拨开开关会当场跑探针、通过了当场重启引擎
     * （`ui/settings/RuntimeSwitchAction` 与 `PiSessionViewModel.onSettingWritten`），所以
     * 「下一次启动」既不是用户看到的事，也和开关那一行的文案互相矛盾。这一档剩下的含义只有
     * 一个：**此刻没有这个 revision 的探针结论**——正在测（那时状态行显示「正在测…」，读不到
     * 这句）、或上一次探测没能留下结论。它就说这一件事，不猜下一步。
     */
    fun describe(fallback: EngineFallback): String = when (fallback) {
        EngineFallback.None -> "proroot（${PROROOT_SECCOMP.disclosure}）"
        EngineFallback.SwitchOff -> "proot"
        EngineFallback.RuntimeFilesMissing -> "proot（运行时文件缺失）"
        EngineFallback.ProbeNotPassed -> "proot（探针未通过）"
        EngineFallback.ProbeNotRun -> "proot（探针尚未运行）"
        EngineFallback.FailureStreak ->
            "proot（连续 $MAX_CONSECUTIVE_FAILURES 次启动失败，重新打开开关可清零重试）"

        EngineFallback.InstallPath -> "proot（装机与维护路径不参与 proroot）"
    }
}
