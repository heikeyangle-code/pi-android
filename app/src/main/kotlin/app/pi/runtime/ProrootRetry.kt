package app.pi.runtime

/**
 * What "the user turned the runtime switch on (or off)" must do to persisted state.
 *
 * ## The defect this exists for
 *
 * Two separate pieces of state can outlive a retry, and only one of them used to be
 * cleared:
 *
 *  - the **consecutive-failure counter** — cleared by [RuntimePreferences] when the
 *    switch is written, which is what the settings row's "关掉再打开就是再试一次"
 *    promise relies on;
 *  - the **gate's cached verdict** — keyed by the unpacked runtime revision plus the
 *    digest of the five proroot binaries ([ProrootProbeCache.key]), and **neither of
 *    those changes when the user flips a switch**. So a verdict of *未通过* — including
 *    one reached for a transient reason, e.g. a `/tmp` that could not be written at that
 *    moment — kept proroot disabled **for ever**, and re-opening the switch appeared to
 *    do nothing at all. That is the "switch that cannot do anything" shape this
 *    repository keeps removing, one layer down.
 *
 * ## The rule
 *
 * Turning the switch **on** is a retry: it clears the counter *and* throws the cached
 * verdict away, so the next proroot launch re-runs the gate. Turning it **off** clears
 * only the counter — the verdict is a measurement of the runtime tree rather than of the
 * switch, there is nothing to re-earn on the way out, and discarding it would erase the
 * one thing the cache exists to provide.
 *
 * An ordinary restart re-runs **nothing**: the cached verdict is reused, and that is not
 * affected by this object at all (see [RuntimeSelection.gate]).
 *
 * ## Why the effects are callbacks
 *
 * The transition is the part worth testing, but applying it needs
 * `SharedPreferences` and `java.io.File` — Android and the filesystem. Rather than test a
 * copy of the rule (which would pass while production did something else), the rule is
 * *executed* here on a bare JVM with the two effects injected as callbacks, and
 * [RuntimeSelection.setProrootEnabled] is the single production caller that supplies the
 * real ones. The harness drives [apply] with real lambdas and a real temp file, so
 * "re-enabling reaches the deletion" is proven against the code production runs instead
 * of asserted in prose.
 *
 * Android-free — no imports at all — so it compiles into the `proroot` harness.
 */
object ProrootRetry {

    /** What a switch write must invalidate. */
    data class Plan(
        /** Always: the counter describes consecutive failures of a run that is being retried. */
        val failureCounterReset: Boolean,
        /** Only when the switch went on; see the class KDoc. */
        val probeCacheInvalidated: Boolean,
    )

    /** The plan for a switch write, without touching anything. */
    fun plan(nowEnabled: Boolean): Plan = Plan(
        failureCounterReset = true,
        probeCacheInvalidated = RuntimeChoice.invalidatesProbeCache(nowEnabled),
    )

    /**
     * Apply the plan: [resetFailures] always, [invalidateProbeCache] only when the switch
     * went on. `invalidateProbeCache` returns whether a cached verdict was actually there,
     * which the caller logs; the return value is not interpreted here.
     */
    fun apply(
        nowEnabled: Boolean,
        resetFailures: () -> Unit,
        invalidateProbeCache: () -> Boolean,
    ): Plan {
        val plan = plan(nowEnabled)
        resetFailures()
        if (plan.probeCacheInvalidated) invalidateProbeCache()
        return plan
    }
}
