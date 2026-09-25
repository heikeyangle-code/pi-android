package app.pi.runtime

import android.content.Context
import app.pi.PiApplication

/**
 * The app's own standing decision about the container runtime, and the failure
 * counter that outlives a process.
 *
 * ## Why this is not in pi's `settings.json`
 *
 * The settings registry is a transcription of pi's own documents: every row there
 * has a reader inside pi, or is one of the app's *process* knobs that pi is handed
 * on its command line (`app.runtime.offline` and friends). This one is neither — it
 * selects which binary launches the guest, before pi exists — so writing it into a
 * file pi reads would be handing pi a key it has no reader for. It is app-only
 * state, and it is stored the way this app stores app-only state:
 * `SharedPreferences`, exactly like `DeviceCapabilityStore` (the other case of a
 * switch that is not a pi setting, `PiSettingsRegistry.kt:1128-1137`).
 *
 * ## Why it is reachable without a `Context`
 *
 * The guest-launch code that has to read this (`GuestCommand`, the mention scan) is
 * constructed from an [app.pi.packages.AgentLayout], which carries no `Context`, and
 * the call sites that build one are outside this change's scope. So [shared] resolves
 * the application context through [PiApplication] — the same process-wide singleton
 * `PiApplication.instance` that the app already exposes — and returns null before
 * `Application.onCreate` has run. Null means **proot**: the conservative direction,
 * and the only one that cannot start a closed-source runtime by accident.
 *
 * ## The failure counter
 *
 * `docs/proroot-research.md` §7.1: DSH App forces proot after **three consecutive**
 * launch failures and tells the user. The count has to be persisted, because a
 * proroot launch that kills the app process would otherwise reset it — and that is
 * one of the failures it exists to count. It is cleared by any successful launch
 * ([RuntimeSelection.recordProrootSuccess]) and by the user turning the switch on
 * ([setProrootEnabled]), which is the "try again" gesture the settings row promises.
 */
class RuntimePreferences private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The user's switch. **False unless the user turned it on.** */
    val prorootEnabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)

    /** Consecutive proroot launches that failed before doing any work. */
    val prorootFailures: Int get() = prefs.getInt(KEY_FAILURES, 0)

    /**
     * The **second** opt-in runtime's own switch (`docs/bxroot-runtime.md`).
     *
     * Independent of [prorootEnabled] in both directions: the two runtimes answer
     * different probes and have different failure streaks, so one switch cannot be the
     * other's state. Off unless the user turned *this* one on.
     */
    val bxrootEnabled: Boolean get() = prefs.getBoolean(KEY_BXROOT_ENABLED, false)

    /** Consecutive bxroot launches that failed before doing any work. */
    val bxrootFailures: Int get() = prefs.getInt(KEY_BXROOT_FAILURES, 0)

    /** Per-engine readers, so a caller never has to pick a key itself. */
    fun enabled(engine: GuestEngine): Boolean = when (engine) {
        GuestEngine.Bxroot -> bxrootEnabled
        else -> prorootEnabled
    }

    fun failures(engine: GuestEngine): Int = when (engine) {
        GuestEngine.Bxroot -> bxrootFailures
        else -> prorootFailures
    }

    /** Per-engine writers; same reason, and the switch row's own path. */
    fun setEnabled(engine: GuestEngine, enabled: Boolean) = when (engine) {
        GuestEngine.Bxroot -> setBxrootEnabled(enabled)
        else -> setProrootEnabled(enabled)
    }

    fun setFailures(engine: GuestEngine, count: Int) = when (engine) {
        GuestEngine.Bxroot -> setBxrootFailures(count)
        else -> setProrootFailures(count)
    }

    /**
     * Store bxroot's switch. Turning it **on** clears bxroot's own streak, for the same
     * reason [setProrootEnabled] does: the row's text promises that re-enabling is the
     * retry, and a counter that survived it would make the switch a no-op.
     */
    fun setBxrootEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_BXROOT_ENABLED, enabled)
            .putInt(KEY_BXROOT_FAILURES, 0)
            .apply()
    }

    fun setBxrootFailures(count: Int) {
        prefs.edit().putInt(KEY_BXROOT_FAILURES, count).apply()
    }

    /**
     * Store the switch. Turning it **on** clears the failure streak: the row's own
     * text promises that re-enabling is how a forced fallback is undone, and a
     * counter that survived it would make the switch a no-op.
     */
    fun setProrootEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_FAILURES, 0)
            .apply()
    }

    fun setProrootFailures(count: Int) {
        prefs.edit().putInt(KEY_FAILURES, count).apply()
    }

    companion object {
        /** Private to the app; named after the decision, not the binary. */
        const val PREFS_NAME = "pi-runtime-choice"

        /** The switch's key inside [PREFS_NAME]. */
        const val KEY_ENABLED = "proroot.enabled"

        /** bxroot's switch: a second, independent question (`docs/bxroot-runtime.md`). */
        const val KEY_BXROOT_ENABLED = "bxroot.enabled"

        /** bxroot's own consecutive-failure counter. */
        const val KEY_BXROOT_FAILURES = "bxroot.failures"

        /** The consecutive-failure counter's key inside [PREFS_NAME]. */
        const val KEY_FAILURES = "proroot.failures"

        @Volatile
        private var instance: RuntimePreferences? = null

        fun get(context: Context): RuntimePreferences {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: RuntimePreferences(context).also { instance = it }
            }
        }

        /**
         * The process-wide instance, or null when `Application.onCreate` has not run
         * yet (see the class KDoc). Callers treat null as "the switch is off".
         */
        fun shared(): RuntimePreferences? = runCatching { get(PiApplication.context) }.getOrNull()
    }
}
