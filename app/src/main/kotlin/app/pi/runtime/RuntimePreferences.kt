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

    /** 用户选的运行时（三选一）。proot 是默认，也是任何一关不过时的回退。 */
    val selectedEngine: GuestEngine
        get() {
            val stored = prefs.getString(KEY_ENGINE, null)
                ?.let { name -> GuestEngine.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            return stored ?: GuestEngine.Proot
        }

    /** Consecutive proroot launches that failed before doing any work. */
    val prorootFailures: Int get() = prefs.getInt(KEY_FAILURES, 0)

    /** 选运行时；失败计数清零（换了引擎，旧引擎攒的失败不算新引擎的）。 */
    fun setSelectedEngine(engine: GuestEngine) {
        prefs.edit()
            .putString(KEY_ENGINE, engine.name)
            .putInt(KEY_FAILURES, 0)
            .apply()
    }

    fun setProrootFailures(count: Int) {
        prefs.edit().putInt(KEY_FAILURES, count).apply()
    }

    companion object {
        /** Private to the app; named after the decision, not the binary. */
        const val PREFS_NAME = "pi-runtime-choice"

        /** 三选一的引擎键，值是 [GuestEngine.name]。 */
        const val KEY_ENGINE = "runtime.engine"

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
