package app.pi.runtime

import java.io.File

/**
 * Builds **proroot's** command line and environment.
 *
 * The authoritative map from our proot recipe to proroot's is
 * `docs/pi-android-app-design.md` §2.3.1, whose evidence is
 * `docs/proroot-research.md` §4.2 (four proot-only options are *rejected*, not
 * silently ignored) and §4.3 (the argv/env table). Read that table before changing
 * anything here; each line below names the entry it implements.
 *
 * What changes, and why each change is not cosmetic:
 *
 *  - `--link2symlink`  **kept**. Removing it is not an option: a guest `link()`
 *    then fails `EACCES` on this filesystem (§5.P1-3). Under proroot the flag
 *    yields a **real** hard link (same inode, write-through) rather than proot's
 *    `.l2s` symlink emulation — the store this app's [PiPaths.l2s] exists for
 *    stays on disk and stays unbound, because only proot reads it.
 *  - `-b <l2s>:<l2s>`  **dropped**. proroot anchors its link handling at
 *    `<rootfs>/.l2s` itself and DSH App ships no such bind (§4.3). The directory
 *    is still created by [PiPaths.l2s] so switching back to proot works.
 *  - `-L`  **dropped**. proroot has no such option and does not need one: guest
 *    absolute symlinks resolve under it (measured, §7.3) — the option exists for
 *    proot, which resolves them as host paths.
 *  - `--kill-on-exit`  **dropped**. proroot rejects it, so the tree is reaped by
 *    the app instead ([GuestTreeReaper], wired at every stop site). This is the
 *    one place where dropping a flag moves work into our code, and it is the
 *    reason that reaper exists rather than being a nicety.
 *  - `--rootfs=` / `--cwd=`  **respell** as `-r` / `-w` (§4.2 shows the long
 *    spellings print `unknown option`).
 *  - `-0`  unchanged: proroot fakes uid 0 the same way, and `chown` "succeeds"
 *    without doing anything in both (§5.P2-3).
 *  - the **bind values** are respelled: proot accepts `-b <path>` (host == guest) and
 *    that is what [GuestRecipe.binds] emits, but proroot's launcher requires the
 *    `host:guest` form and *rejects the whole invocation* without it. See
 *    [bindArgument]; this is the one place where "shared bind table" could not mean
 *    "shared spelling".
 *  - the **host side of every bind is canonicalised** ([GuestRecipe.canonicalHost]).
 *    That is not a proroot-only concern — it is the `/data/user/0` vs `/data/data` alias,
 *    which both recipes carried and which broke the guest's cwd and the extension
 *    directory under proroot. It lives in [GuestRecipe] so the two runtimes cannot
 *    diverge; [bindArgument] is simply the proroot-side entry to it.
 *  - everything else (`/bin/bash -c`, the bind *table*) is shared with proot
 *    verbatim through [GuestRecipe].
 *
 * ## The `-b` spelling is load-bearing, and it was wrong (2026-09-19)
 *
 * `GuestRecipe.binds` returns the proot spelling (`-b /proc`, `-b /dev`, …) because
 * proot accepts a bare host path as host==guest. proroot v1.2.8 does **not**: its
 * argument parser runs `strchr(value, ':')` on the `-b` value and, when it comes back
 * null, prints
 *
 * ```
 * [proroot] bad bind format (expected host:guest): /dev
 * Usage: libproroot.so [-r rootfs] [-0] [--link2symlink] …
 * ```
 *
 * to **stderr** and exits — before forking, before writing its `.proroot-config-<pid>`
 * table, before running any guest command. Evidence:
 *
 *  - **binary**: `libproroot.so` v1.2.8 (sha256 `a4e74d75…`, byte-identical to the
 *    published release asset) at `0x7904` calls `strchr(value, ':')` and at `0x7908`
 *    branches to the address loading `"[proroot] bad bind format (expected host:guest)"`
 *    when it returns null;
 *  - **reference implementation**: DSH App's live launcher command line on this device
 *    spells *every* bind with a colon (`-b /dev:/dev`, `-b /proc:/proc`, …) — it never
 *    relies on the bare form, even though its README-documented option table lists
 *    `-b <host>` as valid.
 *
 * The consequence of the old spelling was not a degraded proroot: it was **no proroot
 * at all**. The launcher's stdout/stderr went through [ProrootRawProbe.parse], which
 * only recognises marker lines, so the probe saw an empty run and reported it as
 * "raw/inline svc 调用没有被翻译" — a translation verdict for a process that never
 * reached translation. That misreading is why the settings row could not explain
 * itself; the launcher's own sentence is now carried as evidence
 * ([ProrootRawProbe.launcherLines]).
 *
 * ## `-w` 指的目录必须在 **rootfs 里**存在（2026-09-19 实测；引擎 126 的根因）
 *
 * 这是同一类失败的第二个，形状和 `-b` 那次很像，但更隐蔽：proroot v1.2.8 的 `-w`
 *（guest 工作目录）**不看绑定表**。启动器在 fork 之前对 `<rootfs>/<cwd>` 做 `chdir`，
 * 目录不存在就打印一行、让子进程以 **126** 退出；而 126 在别处正好是壳层的"命令能看见
 * 但不能执行"，所以这条被读成了"node 不能 exec"（[ProrootExecProbe] 的 KDoc 记了完整
 * 证据与结案）。
 *
 * 实测（本开发容器本身就是 proroot 客户机；载荷见 `runtime.lock.json`，rootfs 是钉住的
 * ubuntu-base 24.04.3 + 同一个 node 24.19.0。bionic 的 `libproroot.so` 在客户机里不能
 * 直接 exec——proroot 自己的解释器解不了 bionic 的依赖——所以用 `qemu-aarch64-static`
 * 驱动钉住的那份 launcher，argv/env 与本文件产出的完全一致）：
 *
 * ```
 * libproroot.so --link2symlink -0 -r <rootfs> -w /workspace/pi/workspaces/workspace-1 \
 *   -b /dev:/dev … -b <files>/pi/workspaces/workspace-1:/workspace/pi/workspaces/workspace-1 \
 *   -b <rootfs>/tmp:/tmp /bin/bash -c '…'
 * # → [proroot] chdir workdir failed: /workspace/pi/workspaces/workspace-1: No such file or directory
 * # → [proroot] child exited with code 126
 * ```
 *
 * 同一条命令，只在 rootfs 里补上那个目录，就变成 `cwd=/workspace/pi/workspaces/workspace-1`，
 * 且 `/usr/bin/env true`、`/opt/node/bin/node --version`（v24.19.0）、`perl` 全部退出 0。
 * 两个名字不同、内容不同的标记文件证明**落地的是绑定那一侧**（`-b` 的 host 目录），
 * rootfs 里那个目录只是让 `chdir` 有东西可进的替身。
 *
 * 所以 [build] 在拼 argv 之前调 [ensureWorkdir]：只有当 `cwd` 落在**某条绑定之下、且那条
 * 绑定的 host 目录真的存在**时才在 rootfs 里补目录。故意不无条件 `mkdirs()`——否则
 * "忘了传绑定"会从"启动器大声失败"退化成"pi 在一个空的 rootfs 目录里安静工作"，
 * 那正是本项目禁止的"读出来像是另一回事"。
 *
 * 三个探针阶段（`-w /`）与终端（`-w /root`）不受影响：前者就是 rootfs 本身，后者是
 * ubuntu-base 自带的目录。装机路径只建了 `<rootfs>/workspace`
 *（`RuntimeProvisioner.kt:687`），而引擎与装包命令的 cwd 是
 * `<rootfs>/workspace/pi/workspaces/<名>`（`GuestWorkspacePath.under`、`PiEngineHost.kt:437`）
 * ——差的就是这一层，所以探针全绿、引擎 126。
 *
 * Environment: proroot recognises **no** `PROOT_*` variable, so none is set here.
 * In their place the launcher is told where its own components are. This is
 * **byte-for-byte the set DSH App exports** — measured from the live launcher's
 * `/proc/<pid>/environ` on this device, which holds exactly `PROROOT_TMP_DIR`,
 * `PROROOT_LIB_PATH`, `PROROOT_LINKER_PATH` and `PROROOT_STUB_LOADER`, and nothing
 * else proroot-shaped. Naming them is §5.P1-4's recommendation: upstream discovers
 * `libproroot-runtime.so` / `-linker.so` / `-bridge.so` / `-stub-loader.so` by
 * **fixed name in the directory of `/proc/self/exe`**, which happens to be
 * `nativeLibraryDir` today (jniLibs keeps upstream's names — `tools/fetch-runtime.mjs`
 * renames nothing), but a rename would break that discovery silently.
 *
 * ## The two variables we deliberately do **not** set
 *
 * `PROROOT_NO_SECCOMP` and `PROROOT_TRAMPOLINE_PATH` are both *missing* from the
 * reference implementation's environment, and both omissions are correct:
 *
 *  - **`PROROOT_TRAMPOLINE_PATH`** — the launcher resolves `libproroot-bridge.so`
 *    itself, from the same directory (the string `libproroot-bridge.so` is in the
 *    launcher, and it `setenv`s the resolved path for its children). DSHA does not set
 *    it either.
 *  - **`PROROOT_NO_SECCOMP`** ([NO_SECCOMP_ENV]) — measured to have **no reader** in
 *    v1.2.8; see [RuntimeChoice.ProrootSeccomp] for the disassembly, the release-note
 *    search and the live-device proof. Setting it would change our environment and
 *    nothing else, so it is not set, and `RuntimeChoice.PROROOT_SECCOMP` is the
 *    launcher's default configuration.
 *
 * `LD_LIBRARY_PATH` is deliberately **not** set: it exists in the proot recipe only
 * so the dynamic loader can find the `libtalloc.so.2` alias proot needs, and
 * keeping it would inject host paths into a guest that has no use for them (§4.3).
 *
 * `--static-loader` is not passed explicitly. Its default is adaptive — enabled
 * when the stub loader is present — and we ship it and name it in
 * `PROROOT_STUB_LOADER`, so the layer that keeps static/static-pie binaries
 * (`rg`/`fd`) and `execve` routing inside the translation is on (§5.P0-2 warns
 * that turning it off is how those binaries stop being covered).
 *
 * Android-free by construction — `java.io.File` and the Kotlin stdlib — so the
 * `proroot` harness in `tools/run-app-pure-checks.sh` builds the argv and the
 * environment on a bare JVM and pins the §2.3.1 mapping table by value.
 */
object ProrootCommand {

    /**
     * The variable DSH App's children carry and the launcher's own code writes into
     * them. **Never set by this app** — see the class KDoc and
     * [RuntimeChoice.ProrootSeccomp]. Named here so the decision has an identifier
     * instead of surviving as an absence, and so a harness can assert that a launch's
     * environment does not contain it.
     */
    const val NO_SECCOMP_ENV = "PROROOT_NO_SECCOMP"

    /** `host:guest` — the only `-b` value shape proroot v1.2.8 accepts. */
    const val BIND_SEPARATOR: String = GuestRecipe.BIND_SEPARATOR

    /**
     * One bind value in proroot's spelling.
     *
     * Two jobs, and the second one is the reason both sides cannot be written by hand here:
     *
     *  1. **qualify** — proot treats `-b /proc` as `/proc:/proc`; proroot v1.2.8 parses the
     *     value with `strchr(value, ':')` and rejects the invocation outright when there is
     *     no colon (class KDoc has the addresses and the message). A bare value means
     *     `host == guest`, so the expansion is `canonicalHost(v):v`: the **host** side is
     *     respelled, the path the shorthand named stays byte-identical on the guest side, and
     *     the equality the shorthand asserts survives as "both name the same directory" —
     *     which canonicalising the guest side too would quietly break by moving the mount point;
     *  2. **canonicalise the host side** ([GuestRecipe.bindValue]) — the table has to spell a
     *     host directory the way the guest kernel reports it, or the runtime's reverse map
     *     (a string prefix match) misses and relative paths stop resolving
     *     ([GuestRecipe.canonicalHost] has the live measurement). An already-qualified value
     *     keeps its **guest** side exactly as written; only the host side moves.
     *
     * Deliberately pure and public: the `proroot` harness pins both directions, because the
     * failure it prevents is silent.
     */
    fun bindArgument(value: String): String {
        if (value.contains(BIND_SEPARATOR)) return GuestRecipe.bindValue(value)
        // A bare value is proot's `host == guest` shorthand. Expanding it must not move the
        // **guest** mount point: only the host side is respelled, so the path the shorthand
        // named stays byte-identical where the guest sees it. (Spelling *both* sides with the
        // canonical path — the earlier rule — would silently mount a bare `/data/user/0/…` at
        // `/data/data/…`: today's table has no such entry, but the rule has to hold for the
        // whole class, and this is the only place it is decided.)
        return "${GuestRecipe.canonicalHost(value)}$BIND_SEPARATOR$value"
    }

    /**
     * `-w` 的目录在 rootfs 里是什么状态，或者**为什么我们没有动它**。
     *
     * 七种读法对应七件事，不能互相冒充（本项目对"读出来的东西"的硬要求）：
     * [NO_BIND] 是"这次启动没有任何绑定覆盖 `cwd`，所以按纪律不建"——不是失败，是拒绝替
     * 调用方兜底；[HOST_MISSING] 是"绑定在，但它 host 那一侧不存在"，那通常意味着调用方
     * 忘了 `mkdirs()` 工作区，启动器会大声失败，我们**不**把它变成空目录。
     */
    enum class WorkdirState {
        /** rootfs 里已经有了。 */
        PRESENT,

        /** 刚刚补上。 */
        CREATED,

        /** 没有绑定覆盖 `cwd`：不建（见类 KDoc 的纪律）。 */
        NO_BIND,

        /** 有绑定覆盖，但那条绑定的 host 目录不在：不建。 */
        HOST_MISSING,

        /** rootfs 还没解包：不建（proroot 本来也起不来）。 */
        NO_ROOTFS,

        /** `cwd` 不是可用的绝对 guest 路径（相对、空、含 `..`）。 */
        UNUSABLE_PATH,

        /** rootfs 在，`mkdirs()` 还是失败（权限/同名文件）。 */
        FAILED,
    }

    /**
     * 覆盖 [cwd] 的那条绑定（guest 侧等于 `cwd`，或 `cwd` 在它之下），没有就 null。
     *
     * 只看 guest 侧、不碰文件系统，所以 harness 能在裸 JVM 上钉住这条判据。`cwd` 恰好是
     * 绑定目标本身是最常见的情形：引擎的 `-w` 与它自己的 workspace 绑定就是同一个字符串
     *（`PiEngineHost.kt:499`、`GuestWorkspacePath.under`）。
     */
    fun bindingCovering(cwd: String, binds: List<Pair<String, String>>): Pair<String, String>? {
        val clean = cwd.trimEnd('/').ifEmpty { "/" }
        return binds.firstOrNull { (_, guestValue) ->
            val guest = guestValue.trimEnd('/').ifEmpty { "/" }
            guest == "/" || clean == guest || clean.startsWith("$guest/")
        }
    }

    /**
     * guest 绝对路径在 rootfs 里的落点；不是可用的绝对 guest 路径时 null。
     *
     * `-w /` 就是 rootfs 自己。任何一段是空、`.`、`..` 的路径都拒绝：这个函数的结果会被
     * `mkdirs()`，而 `..` 能让它落到 rootfs 外面去。
     */
    fun workdirUnder(rootfs: File, cwd: String): File? {
        if (!cwd.startsWith("/")) return null
        val relative = cwd.trim('/')
        if (relative.isEmpty()) return rootfs
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(rootfs, relative)
    }

    /**
     * 保证 [cwd] 在 [rootfs] 里有一个**目录**可进，返回它当时的状态（见 [WorkdirState]）。
     *
     * 为什么必须是"有绑定覆盖"才动手，类 KDoc 有一整节；一句话：无条件的 `mkdirs()` 会把
     * "忘了传绑定"从一个响亮的启动失败变成一次安静的、空工作区里的运行。
     *
     * 幂等，且对 proot 完全无意义（只有 [ProrootCommand.build] 调它）。
     */
    fun ensureWorkdir(
        rootfs: File,
        cwd: String,
        binds: List<Pair<String, String>>,
    ): WorkdirState {
        val binding = bindingCovering(cwd, binds) ?: return WorkdirState.NO_BIND
        if (!File(binding.first).isDirectory) return WorkdirState.HOST_MISSING
        if (!rootfs.isDirectory) return WorkdirState.NO_ROOTFS
        val dir = workdirUnder(rootfs, cwd) ?: return WorkdirState.UNUSABLE_PATH
        if (dir.isDirectory) return WorkdirState.PRESENT
        return if (dir.mkdirs() || dir.isDirectory) WorkdirState.CREATED else WorkdirState.FAILED
    }

    /**
     * 本次启动的全部 `(host, guest)` 绑定对：共享绑定表 + 调用方的额外绑定 + `/tmp`。
     *
     * 与 [build] 拼进去的 argv 同源（同一个 [GuestRecipe.binds]、同一个 [GuestRecipe.tmpBind]，
     * 额外绑定同样过 [bindArgument]），所以"argv 里绑了什么"与"判断 `-w` 是否在绑定之下时看的是
     * 什么"不可能分叉——这正是 [ensureWorkdir] 那条纪律能站住的前提，也是 [WorkdirState.HOST_MISSING]
     * 检查的 host 目录与 argv 里那一个**逐字相同**的原因（别名拼写会让"存在"与"能被反向映射"
     * 变成两个不同的问题）。没有冒号的值按 proot 的 host == guest 读。
     */
    fun boundPairs(
        paths: PiPaths,
        storage: File?,
        extraBinds: List<Pair<String, String>>,
    ): List<Pair<String, String>> =
        GuestRecipe.binds(paths, storage).map { (_, value) -> asPair(value) } +
            extraBinds.map { (host, guest) -> asPair(bindArgument("$host:$guest")) } +
            listOf(asPair(GuestRecipe.tmpBind(paths)[1]))

    /** `host:guest` → pair；没有冒号时按 host == guest 读（proot 的简写）。 */
    private fun asPair(value: String): Pair<String, String> =
        if (value.contains(BIND_SEPARATOR)) {
            value.substringBefore(BIND_SEPARATOR) to value.substringAfter(BIND_SEPARATOR)
        } else {
            value to value
        }

    /**
     * @param guestCommand passed to `bash -c` **inside** the rootfs, so it may use
     *        guest paths (`/opt/pi/node`, `/workspace`, …).
     */
    fun build(
        paths: PiPaths,
        guestCommand: String,
        cwd: String,
        storage: File?,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> {
        // 2026-09-19：proroot 的 `-w` 只按 rootfs 解析（绑定表不算数），所以 `-w` 指的目录
        // 必须在 rootfs 里真的存在，否则启动器 `chdir` 失败、子进程 126（类 KDoc 有实测
        // 原文）。**这里是唯一的漏斗**：引擎、装包命令、终端、三个探针阶段和失败取证都
        // 经过 [GuestCommandLine] → 本函数，而失败取证恰恰绕过了 `RuntimeSelection`。
        //
        // 绑定对**只算一次**：同一个 [boundPairs] 结果既喂 `ensureWorkdir` 的判断，又是下面
        // argv 的来源（harness 钉住这两个集合相等）。先前是先把绑定表算一遍给 `ensureWorkdir`、
        // 再遍历一次 `GuestRecipe.binds` + `extraBinds` 拼 argv —— 同一批 `canonicalHost`
        // 每次 spawn 算两遍，而 `canonicalPath` 是 syscall。
        val binds = boundPairs(paths, storage, extraBinds)
        ensureWorkdir(paths.rootfs, cwd, binds)
        val argv = mutableListOf<String>()
        argv += paths.prorootLauncher().absolutePath
        // Kept from the proot recipe: without it a guest `link()` is EACCES
        // (`docs/proroot-research.md` §5.P1-3). proroot grants a real hard link
        // where proot emulates one, so this flag means "allow the guest to link at
        // all" under both runtimes, not "emulate it".
        argv += "--link2symlink"
        argv += "-0"
        // `-r` / `-w`: the long spellings `--rootfs=` / `--cwd=` are proot-only and
        // make proroot exit with its usage line (`docs/proroot-research.md` §4.2).
        //
        // `-r` stays in `PiPaths.rootfs`'s own spelling **on purpose**: proroot resolves the
        // rootfs prefix itself, and a live measurement inside a proroot guest showed every
        // rootfs path (`/etc`, `/root`, `/tmp`, `/dev/fd`) reported back correctly even
        // though the launcher had been given `-r /data/user/0/…`. Canonicalising it here would
        // be churn with no defect behind it.
        argv += listOf("-r", paths.rootfs.path)
        argv += listOf("-w", cwd)
        // The bind set, respelled for proroot: every entry goes out as `-b host:guest`,
        // including the ones the shared recipe spells as a lone host path. `-b` is the flag
        // for the whole shared table by construction (`GuestRecipe.binds`/`tmpBind` each pair
        // their own flag with their value, and the harness pins that every one of them is
        // `-b`), which is what lets this line reuse [boundPairs] instead of walking the shared
        // table a second time — the duplication that made every spawn canonicalise its hosts
        // twice.
        binds.forEach { (host, guest) -> argv += listOf("-b", "$host$BIND_SEPARATOR$guest") }
        argv += GuestRecipe.shellArgs(guestCommand)
        return argv
    }

    /**
     * The launcher process's environment: **exactly** the four `PROROOT_*` variables DSH
     * App exports, and no others (class KDoc is the measurement).
     *
     * [PiPaths.prorootTmp] **must be a host path**, and it is: the launcher is a
     * host-side process (raw `execve`, proroot's hook not yet installed), so a guest
     * spelling there fails with `[proroot] open config file: No such file or
     * directory` (`docs/proroot-research.md` §4.3, which hit exactly that). The
     * proot recipe already had this right (`PROOT_TMP_DIR` is a `File.absolutePath`),
     * so this is a rename rather than a fix.
     *
     * There is deliberately **no** `PROROOT_NO_SECCOMP` here. It is not an omission:
     * v1.2.8's five binaries contain the name only in the launcher, which writes `1`
     * into every guest child itself, and no component reads it — so exporting it
     * changes nothing except making our environment differ from the reference
     * implementation's. `RuntimeChoice.ProrootSeccomp` carries the full evidence.
     */
    fun environment(paths: PiPaths, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("PROROOT_TMP_DIR", paths.prorootTmp.path)
        // Explicit component paths: see the class KDoc for why this does not rely
        // on `/proc/self/exe` discovery.
        put("PROROOT_LINKER_PATH", paths.prorootLinker().absolutePath)
        put("PROROOT_LIB_PATH", paths.prorootRuntimeHook().absolutePath)
        put("PROROOT_STUB_LOADER", paths.prorootStubLoader().absolutePath)
        putAll(GuestRecipe.environment(paths))
        putAll(extra)
    }
}
