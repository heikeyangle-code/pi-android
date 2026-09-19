package app.pi.runtime

/**
 * Works out which processes belong to a guest tree, so that stopping one launch
 * kills **that launch's** descendants and nothing else.
 *
 * ## Why proroot needs this and proot did not
 *
 * The proot recipe carries `--kill-on-exit`, so killing the proot process takes the
 * whole guest tree with it. proroot rejects that option outright
 * (`docs/proroot-research.md` §4.2), and its launcher is a **long-lived parent** of
 * every guest process (`libproroot.so → bash → node`, §1.3). The app's stop sites
 * only ever called `destroyForcibly()` on the direct child, so on proroot a stop
 * would leave the entire guest tree running — one leaked Node engine per restart,
 * each still holding the session file and the agent dir.
 *
 * ## Why it is parent-child and not "anything that looks like a guest"
 *
 * `[DSHA]`'s `WebProcSel` exists because that app looks for its processes by name
 * and must never match the container launcher itself ("杀到容器启动器 = 环境连 App
 * 一起带走", `docs/proroot-research.md` §5.P1-5). We do not have that problem: we
 * hold the `Process` handle, so the **root pid is known exactly**. What we add is
 * the closure of its descendants, read from `/proc/<pid>/stat` field 4. A pid that
 * is not reachable from our root can never be selected, so another guest's tree, the
 * app process and the device shell are out of reach by construction.
 *
 * ## Order
 *
 * Descendants first, deepest first, root last. Killing the root first is what
 * orphans the rest: a dead parent's children are reparented to init, and the
 * parent-child edges this function depends on are gone by the time the next
 * snapshot is read. Deepest-first also means a shell cannot start a replacement
 * child while we are still walking down from the top.
 *
 * Android-free: it takes a pid → ppid map and returns pids. The caller looks at
 * `/proc` and sends the signals ([GuestTreeReaper]).
 */
object GuestProcessTree {

    /**
     * Every pid reachable from [root] through [parentOf], deepest first.
     *
     * A cycle (which `/proc` cannot really contain, but a recycled pid can *look*
     * like one between two snapshots) cannot make this loop: the result is built as
     * a set and a pid already in it is never expanded again.
     */
    fun descendants(root: Int, parentOf: Map<Int, Int>): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        parentOf.forEach { (pid, parent) ->
            if (pid == root) return@forEach
            children.getOrPut(parent) { mutableListOf() }.add(pid)
        }
        val depth = HashMap<Int, Int>()
        val ordered = mutableListOf<Int>()
        val frontier = ArrayDeque<Int>()
        children[root].orEmpty().forEach { child ->
            depth[child] = 1
            frontier += child
        }
        while (frontier.isNotEmpty()) {
            val pid = frontier.removeFirst()
            ordered += pid
            children[pid].orEmpty().forEach { child ->
                if (depth.containsKey(child)) return@forEach
                depth[child] = (depth[pid] ?: 0) + 1
                frontier += child
            }
        }
        return ordered.sortedWith(compareByDescending<Int> { depth[it] ?: 0 }.thenByDescending { it })
    }

    /** The signal order for one reaping pass: descendants deepest-first, then the root. */
    fun plan(root: Int, parentOf: Map<Int, Int>): List<Int> =
        descendants(root, parentOf) + listOf(root)

    /**
     * Field 4 (`ppid`) of one `/proc/<pid>/stat` line, or null when the line cannot
     * be read that way.
     *
     * The line is `pid (comm) state ppid …` and `comm` may contain **anything**,
     * including spaces and parentheses (`/proc/self/stat` is famously unparseable
     * by naive splitting). Anchoring on the **last** `)` in the line is what makes
     * this correct: `comm` is the only field that can contain a `)`, and the state
     * field directly follows it.
     */
    fun parseStatPpid(statLine: String): Int? {
        val close = statLine.lastIndexOf(')')
        if (close < 0 || close + 2 >= statLine.length) return null
        val fields = statLine.substring(close + 1).trim().split(Regex("\\s+"))
        // fields[0] = state, fields[1] = ppid
        return fields.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Whether a process observed now is still the process we recorded earlier.
     *
     * The whole reason the reaper pairs `(pid, starttime)` is that a pid is not an
     * identity — see [parseStatStartTime]. `expected == null` means "no identity was
     * recorded" (the proot path, and callers that have only a pid), in which case any
     * live observation counts.
     *
     * Pure and separated out because it is the predicate that decides whether signals
     * are sent at all: a wrong `true` kills a recycled pid, and a wrong `false` leaks a
     * tree. The harness pins every combination.
     */
    fun isSameProcess(observedStartTime: Long?, expectedStartTime: Long?): Boolean {
        if (observedStartTime == null) return false
        if (expectedStartTime == null) return true
        return observedStartTime == expectedStartTime
    }

    /**
     * Field 22 (`starttime`, in clock ticks since boot) of the same line, or null.
     *
     * This is what makes signalling a pid safe. A pid that has exited can be
     * **recycled**, and since only processes of our own uid are visible or
     * signalable, the unlucky case is killing one of this app's own processes. Adding
     * the start time to every observation turns the pid into an identity that a
     * recycled number cannot forge, so a stale entry from one snapshot is recognised
     * as stale in the next one instead of being signalled.
     *
     * Offsets: the fields after `)` are `state`, `ppid`, … and `starttime` is the
     * 22nd field of the whole line, i.e. index 19 after the `)` plus one for `state`.
     */
    fun parseStatStartTime(statLine: String): Long? {
        val close = statLine.lastIndexOf(')')
        if (close < 0 || close + 2 >= statLine.length) return null
        val fields = statLine.substring(close + 1).trim().split(Regex("\\s+"))
        return fields.getOrNull(19)?.toLongOrNull()
    }
}
