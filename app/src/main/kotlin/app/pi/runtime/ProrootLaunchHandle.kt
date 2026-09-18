package app.pi.runtime

import java.io.File

/**
 * Finds the pid of a proroot launch, and owns that launch's config table.
 *
 * ## Why this is not `Process.pid()`
 *
 * It would be the obvious answer, and it is not available: `java.lang.Process.pid()`
 * does not resolve against this project's compile classpath (`tools/typecheck.sh`
 * compiles with `-no-jdk` against `android.jar`, where `Process` has no public `pid`,
 * and the Gradle build agrees). The direct child's pid is therefore not something the
 * app can ask the platform for.
 *
 * proroot hands us a better identifier anyway: the launcher writes its config table as
 * `PROROOT_TMP_DIR/.proroot-config-<launcher pid>` (`docs/proroot-research.md` §1.4,
 * measured). **The file name is the pid** — precise, per-launch, and produced by the
 * thing being identified rather than inferred from a snapshot that may race.
 *
 * ## Why the name alone is not enough, and what the token adds
 *
 * "The only new file that appeared since I armed" is a claim about *time*, and two
 * launches can overlap: the engine boots while a terminal opens, or the `@` completion
 * runs a scan while a package install starts. In that window each handle sees **both**
 * new tables and could take the other launch's pid — which, because the pid is what
 * gets signalled on stop, would mean a terminal close reaping the running engine's
 * tree. Guessing (`candidate.max()`) is not good enough when the consequence is
 * killing a live guest.
 *
 * So each proroot plan also carries a **per-launch token** in the launcher's
 * environment (`PI_LAUNCH_TOKEN`, added by [RuntimeSelection]), and a candidate pid
 * counts only if `/proc/<pid>/environ` contains it. The environment of a process is
 * readable by its owner and is set before it runs, so the match is exact and
 * independent of timing; `/proc/<pid>/environ` was already the documented way the
 * reference implementation's launcher environment is inspected
 * (`docs/proroot-research.md` §8.1). The token reaches the guest too, which is
 * harmless — nothing reads it but this class.
 *
 * The resolved pid is also paired with the process's `starttime`
 * ([launcherStartTime]) so that a stop minutes later cannot signal a **recycled** pid
 * ([GuestTreeReaper] refuses on a mismatch).
 *
 * That matters most for the two jobs the pid is needed for:
 *
 *  - **reaping the tree** ([GuestTreeReaper]) — killing "all descendants of a pid we
 *    guessed" is how one guest kills another;
 *  - **deleting this launch's config table** on stop (requirement 7's third rule).
 *
 * Both the sweep and this handle agree on the name because both read
 * [ProrootConfigSweep.PREFIX]; there is no second spelling of the scheme.
 *
 * ## When it fails
 *
 * If no new table appears within [DEFAULT_RESOLVE_TIMEOUT_MS], [resolveLauncherPid]
 * returns null and the caller logs it. Nothing is guessed: a launch whose pid is
 * unknown is a launch whose tree is not reaped, and the sweep will collect the
 * leftover table on the next launch. Guessing here would be strictly worse than
 * leaving one tree to leak on a device where proroot's own bookkeeping did not work.
 *
 * Android-free (`java.io.File`, `System.nanoTime`, `Thread.sleep`), so the `proroot`
 * harness can drive the resolve/delete logic with real files.
 */
class ProrootLaunchHandle private constructor(
    private val directory: File,
    private val before: Set<String>,
    /** The per-launch token this handle's candidates must carry; see the class KDoc. */
    private val launchToken: String?,
) {

    /** The pid of the launch this handle was armed for, once resolved. */
    var launcherPid: Int? = null
        private set

    /**
     * `/proc/<pid>/stat`'s `starttime` for [launcherPid], read at resolve time.
     *
     * The pid alone is not a stable identity: it can be recycled between the launch and
     * the stop. Pairing it with the start time is what makes signalling safe minutes
     * later, and it is the same `(pid, starttime)` identity the reaper's own snapshots
     * use.
     */
    var launcherStartTime: Long? = null
        private set

    /**
     * Wait for this launch's config table and read its pid out of the name.
     *
     * The launcher creates the table before it execs the guest command, so on a
     * working launch this returns on the first or second poll. The timeout is a bound
     * on a broken launcher, not a typical wait.
     */
    fun resolveLauncherPid(timeoutMs: Long = DEFAULT_RESOLVE_TIMEOUT_MS): Int? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            // Only a pid that *is* this launch can be selected: see the class KDoc for
            // why "the only new table" is not enough when two launches overlap.
            val candidate = listNames()
                .filter { it !in before }
                .mapNotNull { ProrootConfigSweep.pidOf(it) }
                .firstOrNull { carriesLaunchToken(it) }
            if (candidate != null) {
                launcherPid = candidate
                launcherStartTime = readStartTime(candidate)
                return candidate
            }
            if (System.nanoTime() >= deadline) return null
            runCatching { Thread.sleep(POLL_MS) }
        }
    }

    /**
     * Whether `/proc/<pid>/environ` identifies [pid] as **this** launch.
     *
     * With no token armed (the non-proroot path, and the directory-level test in the
     * harness) every candidate counts, which is the old time-based behaviour; with one,
     * only an exact match does. An unreadable environ is **not** a match: falling back
     * to "probably mine" is exactly the guess that can reap another guest.
     */
    private fun carriesLaunchToken(pid: Int): Boolean {
        val token = launchToken ?: return true
        val environ = runCatching { File("/proc/$pid/environ").readBytes() }.getOrNull() ?: return false
        return environHasToken(String(environ, Charsets.ISO_8859_1), token)
    }

    private fun readStartTime(pid: Int): Long? = runCatching {
        File("/proc/$pid/stat").readText().let { GuestProcessTree.parseStatStartTime(it) }
    }.getOrNull()

    /** The table this launch wrote, or null when the pid was never resolved. */
    fun configFile(): File? = launcherPid?.let { File(directory, ProrootConfigSweep.name(it)) }

    /** Delete this launch's table. Safe when the pid was never resolved. */
    fun deleteConfig() {
        runCatching { configFile()?.delete() }
    }

    private fun listNames(): Set<String> = runCatching {
        directory.listFiles()
            ?.filter { it.name.startsWith(ProrootConfigSweep.PREFIX) }
            ?.map { it.name }
            ?.toSet()
    }.getOrNull().orEmpty()

    companion object {
        /** How long a launch gets to produce its table. */
        const val DEFAULT_RESOLVE_TIMEOUT_MS: Long = 1_500L

        private const val POLL_MS = 10L

        /**
         * Record the directory's current contents **before** the process starts.
         *
         * @param directory the runtime's `prorootTmp` ([PiPaths.prorootTmp]).
         */
        /**
         * The environment variable a launch's token is carried in.
         *
         * Named in our own namespace on purpose: it is set on the **launcher** process
         * (which is where it is read from) and inherited by the guest, where nothing
         * reads it.
         */
        const val TOKEN_ENV = "PI_LAUNCH_TOKEN"

        /**
         * Whether a `/proc/<pid>/environ` dump contains `[TOKEN_ENV]=[token]`.
         *
         * Pure, so the harness pins it: the string is NUL-separated, a prefix must not
         * match (`X=PI_LAUNCH_TOKEN=abc` is a different variable), and a token that is
         * a prefix of another must not match either.
         */
        fun environHasToken(environ: String, token: String): Boolean =
            environ.split('\u0000').any { it == "$TOKEN_ENV=$token" }

        fun arm(directory: File, launchToken: String? = null): ProrootLaunchHandle = ProrootLaunchHandle(
            launchToken = launchToken,
            directory = directory,
            before = runCatching {
                directory.listFiles()
                    ?.filter { it.name.startsWith(ProrootConfigSweep.PREFIX) }
                    ?.map { it.name }
                    ?.toSet()
            }.getOrNull().orEmpty(),
        )
    }
}
