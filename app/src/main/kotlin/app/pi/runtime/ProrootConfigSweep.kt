package app.pi.runtime

/**
 * Decides which `.proroot-config-*` files to delete, and nothing else.
 *
 * ## What the files are
 *
 * Every proroot launch writes one fixed-size (274,736 B) read-only config file to
 * `PROROOT_TMP_DIR` and hands its path to the guest as `PROROOT_CFG_FD`. proroot
 * never removes them: `docs/proroot-research.md` §1.4 measured **11** on the
 * reference device, the oldest from six days earlier. They are pure garbage once
 * the process that owns them is gone — and a genuine hazard while it is not.
 *
 * ## The rule, and why it is not "age"
 *
 * The primary judgement is **liveness, not age**: a config file belongs to
 * `<pid>`, and it may be deleted precisely when `/proc/<pid>` no longer exists.
 * Age is wrong in both directions — an engine that has been running for an hour
 * has an old config file that is load-bearing, and a process that died a second
 * ago has a fresh one that is garbage. There is also no timer here: the sweep runs
 * immediately before a proroot launch, which is the only moment a new config file
 * can appear and therefore the only moment worth looking.
 *
 * The engine, the terminal and a package install can all be alive at once
 * (`docs/proroot-research.md` §1.3: `libproroot.so` is a long-lived parent), so the
 * alive set is genuinely multi-valued and a "delete everything but mine" rule would
 * break a running guest.
 *
 * ## The cap
 *
 * Liveness alone cannot bound the directory: a phone that reboots frequently, or a
 * container whose pids are recycled quickly, could accumulate files faster than it
 * removes them. So after the dead ones are gone, if the survivors still exceed
 * [DEFAULT_LIMIT], the **oldest by mtime** are deleted until the cap is met and the
 * caller logs it. The one file never touched is the launch in progress, which the
 * caller passes in `keepNames` — deleting a peer's config file is survivable
 * (the launcher holds its own mapping), but deleting the one this launch just
 * created is not, and that is the difference between the two rules.
 *
 * Android-free and side-effect-free: it is handed names, pids and mtimes and returns
 * names to unlink. The `proroot` harness drives the tricky shapes — a live pid, a
 * dead pid, an unparseable name, an exactly-at-cap directory, a directory over the
 * cap with a keep entry inside the oldest set.
 */
object ProrootConfigSweep {

    /** The launcher's naming scheme: `.proroot-config-<launcher pid>` (§1.4). */
    const val PREFIX = ".proroot-config-"

    /**
     * Backstop for the directory, not a policy about any single file. 32 is ~9 MB
     * of config files, i.e. a few orders of magnitude more than the number of
     * simultaneous proroot launchers this app can have (engine, terminal, package
     * command, mention scan) while still being a bound.
     */
    const val DEFAULT_LIMIT = 32

    /** One directory entry, already stat'ed by the caller. */
    data class Entry(
        val name: String,
        /** The pid parsed out of the name, or null when the name does not parse. */
        val pid: Int?,
        /** `lastModified()` of the file; used only by the cap, never by the liveness rule. */
        val modifiedMs: Long,
    )

    /** What to unlink, and enough counts to log one honest sentence. */
    data class Plan(
        val delete: List<String>,
        /** Files whose owner is gone. */
        val dead: Int,
        /** Live-or-unjudgeable files dropped only to satisfy the cap. */
        val overCap: Int,
    ) {
        val isEmpty: Boolean get() = delete.isEmpty()
    }

    /** The file a launch with `pid` owns. */
    fun name(pid: Int): String = "$PREFIX$pid"

    /** The pid in a config file's name, or null when it is not one of ours. */
    fun pidOf(name: String): Int? {
        if (!name.startsWith(PREFIX)) return null
        val rest = name.removePrefix(PREFIX)
        if (rest.isEmpty() || rest.any { !it.isDigit() }) return null
        return rest.toIntOrNull()
    }

    /**
     * @param alivePids pids whose `/proc/<pid>` exists — read **at sweep time**.
     * @param keepNames files this launch owns; never deleted by the cap.
     */
    fun plan(
        entries: List<Entry>,
        alivePids: Set<Int>,
        keepNames: Set<String> = emptySet(),
        limit: Int = DEFAULT_LIMIT,
    ): Plan {
        val dead = mutableListOf<String>()
        val survivors = mutableListOf<Entry>()
        entries.forEach { entry ->
            val owner = entry.pid
            if (owner != null && owner !in alivePids) dead += entry.name else survivors += entry
        }

        // A name that does not parse is not evidence of death: it is a file this
        // rule cannot judge, so it survives the liveness pass. It can still be cut
        // by the cap, which is the backstop for exactly this kind of leftover.
        val overCap = mutableListOf<String>()
        if (survivors.size > limit) {
            val deletable = survivors
                .filter { it.name !in keepNames }
                .sortedWith(compareBy({ it.modifiedMs }, { it.name }))
            var remaining = survivors.size
            for (entry in deletable) {
                if (remaining <= limit) break
                overCap += entry.name
                remaining--
            }
        }
        return Plan(delete = dead + overCap, dead = dead.size, overCap = overCap.size)
    }

    /** The one log line the cap emits, or null when nothing was cut. */
    fun capNote(plan: Plan, limit: Int = DEFAULT_LIMIT): String? =
        if (plan.overCap == 0) {
            null
        } else {
            "proroot 配置目录超过 $limit 份，按修改时间从旧到新删除了 ${plan.overCap} 份" +
                "（其中 ${plan.dead} 份的属主进程已不存在）"
        }
}
