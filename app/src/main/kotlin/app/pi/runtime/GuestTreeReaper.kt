package app.pi.runtime

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * Reaps a guest process tree after the launcher is gone — the replacement for
 * proot's `--kill-on-exit`, which proroot rejects (`docs/proroot-research.md` §4.2).
 *
 * ## What was wrong before
 *
 * Every stop site called `process.destroyForcibly()` on the **direct child only**
 * (`RuntimeSelfCheck`, `GuestCommand`, `DeviceShell`), and on proot that was enough
 * because `--kill-on-exit` made the proot process take its tree with it. proroot's
 * launcher is a long-lived parent of every guest process (§1.3: `libproroot.so →
 * bash → node`), so the same call would leave `bash` and the Node engine running
 * after a stop: one leaked engine per restart, each still holding the session file,
 * the agent directory and a wake-lock-worthy amount of CPU.
 *
 * ## Who is signalled
 *
 * Only the closure of [rootPid]'s descendants in `/proc` — see [GuestProcessTree]
 * for why parent-child is the right relation here (we hold the `Process` handle, so
 * the root is exact; `[DSHA]`'s name-matching selector exists for an app that does
 * not). A pid observed in one snapshot is re-checked in the next by
 * `(pid, starttime)` identity, so a recycled pid is never signalled.
 *
 * ## Signal order
 *
 * **TERM first, then KILL**, and never KILL first:
 *
 *  1. `SIGTERM` to every descendant, deepest first, then the launcher. A SIGTERM is a
 *     request: `bash` runs its `trap`s, Node flushes session files and closes the
 *     RPC pipe, and the guest's own cleanup happens. This is the difference between
 *     stopping the engine and corrupting its session.
 *  2. Wait up to [TERM_TIMEOUT_MS] for the tree to disappear, polling — not a fixed
 *     sleep, because the common case finishes in milliseconds and a stop must not
 *     cost three seconds when it did not have to.
 *  3. `SIGKILL` to whatever is still there, deepest first and then the launcher.
 *  4. Wait up to [KILL_TIMEOUT_MS] and **report** the survivors instead of claiming
 *     success. A pids left behind is a fact the caller can log; a silent claim that
 *     the tree is gone is how the original leak stayed invisible.
 *
 * Depth-first matters in both passes: a parent that dies first reparents its children
 * to init, and the edges this function walks are then gone — the next snapshot could
 * no longer tell those processes apart from unrelated ones.
 */
object GuestTreeReaper {

    private const val TAG = "PiGuestTreeReaper"

    /** How long SIGTERM gets to work before SIGKILL. */
    const val TERM_TIMEOUT_MS: Long = 3_000L

    /** How long SIGKILL gets before the survivors are reported. */
    const val KILL_TIMEOUT_MS: Long = 2_000L

    private const val POLL_MS = 25L

    /** One observation of a pid: what it is, and what makes it *that* process. */
    data class Proc(val pid: Int, val ppid: Int, val startTime: Long)

    data class Report(
        val rootPid: Int,
        /** Descendants that were sent a signal in the TERM pass, deepest first. */
        val termSignalled: List<Int>,
        /** Descendants that were sent a signal in the KILL pass. */
        val killed: List<Int>,
        /** Pids still present after the KILL pass. Reported, never hidden. */
        val survivors: List<Int>,
        /**
         * Non-null when **nothing was signalled** because the pid no longer belongs to
         * the launch that was stopped (a recycled pid). Not a failure — refusing is the
         * correct outcome — but it is worth a log line, because it means the tree that
         * launch owned is not accounted for.
         */
        val skippedReason: String? = null,
    ) {
        val clean: Boolean get() = survivors.isEmpty() && skippedReason == null
    }

    /**
     * Read `/proc` into `pid → Proc` for the processes this app may signal.
     *
     * A process that disappears between `listFiles` and the read is skipped: that is
     * the normal race, not an error.
     */
    fun snapshot(): Map<Int, Proc> {
        val entries = File("/proc").listFiles() ?: return emptyMap()
        val result = HashMap<Int, Proc>()
        entries.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(entry, "stat").readText() }.getOrNull() ?: return@forEach
            val ppid = GuestProcessTree.parseStatPpid(stat) ?: return@forEach
            val start = GuestProcessTree.parseStatStartTime(stat) ?: return@forEach
            result[pid] = Proc(pid, ppid, start)
        }
        return result
    }

    /** True when [pid] still exists **and** is still that exact process. */
    private fun alive(pid: Int, startTime: Long): Boolean {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
        return GuestProcessTree.isSameProcess(GuestProcessTree.parseStatStartTime(stat), startTime)
    }

    private fun signal(pid: Int, signal: Int) {
        runCatching { Os.kill(pid, signal) }
    }

    /**
     * Freeze the tree that [rootPid] owns **right now**, in signal order.
     *
     * This is separated from [reap] because the parent-child edges do not survive the
     * thing that stops a guest. `PiEngineSession.close()` and every `destroyForcibly()`
     * kill the launcher, and a dead parent's children are reparented to init — after
     * which no amount of walking `/proc` can tell them apart from anybody else's
     * processes. So the call sites capture **first**, stop the direct child second, and
     * signal the captured set third; a reaper that took its own snapshot after the kill
     * would find an empty tree and silently leak exactly the processes it exists for.
     *
     * @param expectedStartTime the `starttime` recorded when the launch was resolved
     *        ([ProrootLaunchHandle.launcherStartTime]). Null means "no identity was
     *        recorded" and accepts any live process at that pid.
     * @return null when the pid is gone or is not that process — i.e. when signalling it
     *         would risk somebody else's tree.
     */
    fun capture(rootPid: Int, expectedStartTime: Long? = null): Snapshot? {
        if (rootPid <= 0) return null
        val procs = snapshot()
        val root = procs[rootPid]
        if (!GuestProcessTree.isSameProcess(root?.startTime, expectedStartTime)) return null
        val order = GuestProcessTree.plan(rootPid, procs.mapValues { it.value.ppid })
        return Snapshot(
            rootPid = rootPid,
            ordered = order.mapNotNull { pid -> procs[pid]?.let { pid to it.startTime } },
        )
    }

    /** A captured tree: pids with the start times that identify them, deepest first. */
    class Snapshot internal constructor(
        val rootPid: Int,
        internal val ordered: List<Pair<Int, Long>>,
    ) {
        /** How many processes (including the launcher) were in the tree. */
        val size: Int get() = ordered.size
    }

    /**
     * Stop a captured tree: **TERM first, then KILL**, deepest first, launcher last.
     *
     * Safe for a tree whose launcher is already gone — the captured descendants are
     * matched by `(pid, starttime)`, so a recycled pid is skipped rather than signalled.
     * Survivors are reported instead of assumed away.
     */
    fun reap(
        snapshot: Snapshot,
        termTimeoutMs: Long = TERM_TIMEOUT_MS,
        killTimeoutMs: Long = KILL_TIMEOUT_MS,
    ): Report {
        if (snapshot.ordered.isEmpty()) {
            return Report(snapshot.rootPid, emptyList(), emptyList(), emptyList())
        }
        val termSignalled = mutableListOf<Int>()
        snapshot.ordered.forEach { (pid, startTime) ->
            if (!alive(pid, startTime)) return@forEach
            signal(pid, OsConstants.SIGTERM)
            termSignalled += pid
        }
        awaitGone(snapshot, termTimeoutMs)

        val killed = mutableListOf<Int>()
        snapshot.ordered.forEach { (pid, startTime) ->
            if (!alive(pid, startTime)) return@forEach
            signal(pid, OsConstants.SIGKILL)
            killed += pid
        }
        val survivors = awaitGone(snapshot, killTimeoutMs)
        return Report(snapshot.rootPid, termSignalled, killed, survivors)
    }

    /**
     * [capture] + [reap] in one call, for the paths that stop nothing in between.
     *
     * The batch timeout in [app.pi.packages.GuestCommand] uses `capture` and `reap`
     * separately instead (capture → kill the direct child → reap), so that its readers
     * are released immediately while the tree is still accounted for.
     */
    fun reap(
        rootPid: Int,
        expectedStartTime: Long? = null,
        termTimeoutMs: Long = TERM_TIMEOUT_MS,
        killTimeoutMs: Long = KILL_TIMEOUT_MS,
    ): Report {
        val captured = capture(rootPid, expectedStartTime)
            ?: return Report(
                rootPid = rootPid,
                termSignalled = emptyList(),
                killed = emptyList(),
                survivors = emptyList(),
                skippedReason = "pid $rootPid 不是本次启动的进程（已不存在或已被回收），未发送任何信号",
            )
        return reap(captured, termTimeoutMs, killTimeoutMs)
    }

    /**
     * [reap] on a daemon thread, for callers that are **on the main thread**.
     *
     * Two of the stop paths are UI teardown: the terminal's `close()` runs in a Compose
     * `DisposableEffect`'s `onDispose`, and the engine's restart is driven from a
     * `rememberCoroutineScope` coroutine. `reap` deliberately waits (TERM, then KILL)
     * for up to five seconds, so blocking either of them is an ANR. **The capture must
     * already have happened** on the calling thread — it is the cheap part, and it is
     * the part that has to run before the launcher dies.
     */
    fun reapInBackground(snapshot: Snapshot, onDone: (Report) -> Unit = {}): Thread {
        val thread = Thread({ onDone(reap(snapshot)) }, "pi-proroot-reap")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * The **probe-timeout** half of the same rule: capture the tree now (the launcher is
     * still alive, which is the only moment its children are reachable) and reap it in the
     * background.
     *
     * `ProrootProbe` hands the launcher's identity here instead of calling [capture] itself,
     * because that file has to stay Android-free for the bare-JVM `proroot` harness and *this*
     * object is the one that signals pids with `android.system.Os.kill`. That keeps one
     * implementation: the probe path cannot drift from the three production stop sites.
     *
     * Called from the probe's own (background) thread at the moment a stage times out, before
     * it kills the direct child. Both "nothing to reap" cases are **said**, not assumed away.
     *
     * @param launcherPid null when the launch never identified itself (no `.proroot-config`
     *        table appeared), so there is nothing to capture.
     * @param expectedStartTime the `starttime` recorded when the pid was resolved, so a
     *        recycled pid is rejected instead of signalled.
     */
    fun reapTimeoutedProbe(launcherPid: Int?, expectedStartTime: Long?): Unit {
        if (launcherPid == null) {
            Log.w(TAG, "proroot 探针超时，但拿不到 launcher pid：没有可回收的 guest 树")
            return
        }
        val snapshot = runCatching { capture(launcherPid, expectedStartTime) }.getOrNull()
        if (snapshot == null) {
            Log.w(TAG, "proroot 探针超时，但捕获不到 guest 进程树：launcher pid 已不存在或已被回收")
            return
        }
        runCatching {
            reapInBackground(snapshot) { report ->
                if (!report.clean) {
                    Log.w(TAG, "proroot 探针超时后仍有 guest 进程存活：${report.survivors.joinToString()}")
                }
            }
        }
    }

    /**
     * Poll until every process in [observed] is gone (or changed identity), and
     * return what is left when [timeoutMs] runs out.
     */
    private fun awaitGone(snapshot: Snapshot, timeoutMs: Long): List<Int> {
        if (snapshot.ordered.isEmpty()) return emptyList()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val left = snapshot.ordered.filter { (pid, startTime) -> alive(pid, startTime) }.map { it.first }
            if (left.isEmpty() || SystemClock.elapsedRealtime() >= deadline) return left
            runCatching { Thread.sleep(POLL_MS) }
        }
    }
}
