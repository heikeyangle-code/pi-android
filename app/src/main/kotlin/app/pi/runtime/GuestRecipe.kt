package app.pi.runtime

import java.io.File

/**
 * What the **two** container runtimes have in common: the bind table, the guest's
 * base environment, and the way a guest command is handed to `/bin/bash`.
 *
 * ## Why this is a separate object and not a copy
 *
 * `ProotCommand` and `ProrootCommand` differ in a short, exact list of flags and
 * variables (`docs/pi-android-app-design.md` §2.3.1 is the table). Everything the
 * two recipes *agree* on is in this file, so a change to a bind reaches both
 * runtimes or neither. A second, copy-pasted bind list is the defect this exists
 * to prevent: the two would drift silently, and the symptom would be a guest that
 * behaves differently depending on which engine the user happened to be on —
 * exactly the "A 案 vs B 案" divergence `docs/proroot-research.md` §5.P1-6 warns
 * about, but self-inflicted.
 *
 * Only `java.io.File` is imported, so `tools/run-app-pure-checks.sh` can compile
 * this file into a bare-JVM harness (see the `proroot` harness).
 */
object GuestRecipe {

    /**
     * Bind mounts every launch needs, in both runtimes.
     *
     * The pairs are the whole argument, not just the bind expression: `proot` and
     * `proroot` both spell a bind as two argv entries (`-b`, `host:guest`), and
     * keeping them paired here is what stops one builder from emitting the flag
     * without its value.
     *
     * ## The value that is shared is the *table*, not the spelling (2026-09-19)
     *
     * A bare host path (`-b /proc`) is proot's shorthand for `host == guest`, and that is
     * what this list returns. **proroot v1.2.8 rejects it**: its parser runs
     * `strchr(value, ':')` on the `-b` value and exits with
     * `[proroot] bad bind format (expected host:guest): /dev` before it forks anything.
     *
     * So `ProrootCommand` respells each entry through `ProrootCommand.bindArgument` while
     * proot takes this list verbatim. That asymmetry is deliberate and is the *only* thing
     * the two builders are allowed to disagree about beyond the documented flag table
     * (`docs/pi-android-app-design.md` §2.3.1) — the entries, their order and which flag
     * carries them still come from here, so a bind added once still reaches both runtimes.
     * Guessing the convention at the call site instead would put the same bug back one
     * layer up.
     *
     * Every value also goes through [bindValue], so the **host** side is always spelled the
     * way the guest kernel reports it ([canonicalHost]) — the other half of this list's job,
     * and the fix for the `/data/user/0` vs `/data/data` alias.
     */
    fun binds(paths: PiPaths, storage: File?): List<List<String>> = buildList {
        add(listOf("-b", "/dev"))
        add(listOf("-b", "/dev/urandom:/dev/random"))
        // `/dev/shm` has to be **after** `-b /dev`, because the later bind wins under both
        // runtimes (measured: with `/dev` first and `/dev/urandom:/dev/random` second, the
        // guest's `/dev/random` is urandom's device, major:minor 1:9). Without this entry the
        // guest's `/dev/shm` is whatever the host's `/dev` happens to contain — Android ships
        // none — and everything that uses POSIX shared memory (Chromium/Playwright, some
        // native addons) fails there. The reference implementation binds exactly this:
        // `-b <app cache>/shm:/dev/shm` was in DSH App's live launcher argv on this device.
        add(listOf("-b", "${paths.shm.path}:/dev/shm"))
        add(listOf("-b", "/proc"))
        add(listOf("-b", "/sys"))
        add(listOf("-b", "/system"))
        add(listOf("-b", "/apex"))
        add(listOf("-b", "/proc/self/fd:/dev/fd"))
        if (storage != null) {
            add(listOf("-b", "${storage.path}:/sdcard"))
            add(listOf("-b", "${storage.path}:/storage/emulated/0"))
        }
    }.map { (flag, value) -> listOf(flag, bindValue(value)) }

    /**
     * A writable `/tmp`: Android has none, and a missing `TMPDIR` makes pi's bash
     * tool fail to spill oversized output. A real bind (not an env var) because
     * both runtimes resolve it inside the guest.
     *
     * The host side goes through [bindValue] like every other bind: `<files>/pi/runtime/tmp`
     * is under the app's data directory, so a guest that `cd /tmp` hits the same alias the
     * workspace bind does.
     */
    fun tmpBind(paths: PiPaths): List<String> = listOf("-b", bindValue("${paths.tmp.path}:/tmp"))

    /** `:` — how both runtimes spell a bind (`host:guest`), and what proroot's parser requires. */
    const val BIND_SEPARATOR: String = ":"

    /** `/proc`: synthetic and **process-relative**, so its paths must never be respelled. */
    private const val PROC: String = "/proc"

    /**
     * The other guest spellings of procfs descriptors — the same class as [PROC], and the
     * reason the exclusion is a **set** rather than one `startsWith`.
     *
     * `/dev/fd` is a procfs symlink (to `/proc/self/fd`), and `/dev/stdin` / `/dev/stdout` /
     * `/dev/stderr` are symlinks into `/proc/self/fd/{0,1,2}`. `File(…).canonicalPath`
     * resolves *those* to `/proc/<the app's pid>/fd/…`: a bind of the wrong directory, and —
     * worse — **a different string in every process**, so two launches would disagree about
     * the bind table. The shared table spells the descriptor bind as
     * `/proc/self/fd:/dev/fd` (host side under [PROC], already excluded); these four are the
     * *alias* spellings a future entry could plausibly use, and they are excluded here so the
     * rule holds for the whole class instead of for the one instance we happen to ship today.
     */
    private val PROC_ALIASED_HOST_PATHS: Set<String> =
        setOf("/dev/fd", "/dev/stdin", "/dev/stdout", "/dev/stderr")

    /**
     * The real resolver: the same resolution the kernel performs.
     *
     * Kept as a named value (rather than inlined into [canonicalHost]) so the bare-JVM harness
     * can install a *counting* delegate — `installCanonicalResolverForTest` — and assert the
     * memo's call count without re-implementing the rule.
     */
    private val defaultCanonicalResolver: (String) -> String = { path ->
        runCatching { File(path).canonicalPath }.getOrDefault(path)
    }

    @Volatile
    private var canonicalResolver: (String) -> String = defaultCanonicalResolver

    /**
     * Process-lifetime memo for [canonicalHost] — the hot-path half of this rule.
     *
     * Why it is safe to cache for the life of the process: the canonical spelling of a path
     * depends only on the symlink structure *along* that path, and every path this object is
     * asked about is either a system mount point (`/dev`, `/proc`, `/sys`, `/system`, `/apex`,
     * `/storage/emulated/0`) or an app-owned directory that the app itself creates before it is
     * bound (`PiPaths.tmp`, `PiPaths.shm`, `PiPaths.agentDir`, the workspace). None of those is
     * re-linked while the app process lives; shipping a different layout means an install,
     * which replaces the process.
     *
     * Why it must exist: without it every guest spawn pays one `canonicalPath` per bind —
     * an engine turn spawns several guests, and each `canonicalPath` is a chain of `readlink`/
     * `stat` syscalls. With it, the cost is one resolution per distinct path per process and
     * **zero** on every subsequent spawn.
     */
    private val canonicalHosts = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Install a counting resolver for the harness; see [defaultCanonicalResolver]. */
    internal fun installCanonicalResolverForTest(resolver: (String) -> String) {
        canonicalResolver = resolver
        canonicalHosts.clear()
    }

    /** Put the real resolver (and an empty memo) back after a harness measured the cache. */
    internal fun resetCanonicalResolverForTest() {
        canonicalResolver = defaultCanonicalResolver
        canonicalHosts.clear()
    }

    /** How many distinct hosts the memo currently holds; the harness asserts this. */
    internal fun canonicalHostCacheSizeForTest(): Int = canonicalHosts.size

    /**
     * The **host** side of a bind, respelled the way the guest kernel reports that
     * directory.
     *
     * ## Why this is not cosmetic (2026-09-20, measured on this device)
     *
     * `Context.getFilesDir()` hands out the *symlinked* spelling of the app's data
     * directory — `/data/user/0/<pkg>/files` — while the kernel's own path for the very
     * same directory is `/data/data/<pkg>/files` (`/data/user/0` is a symlink to
     * `/data/data`). Both runtimes turn host paths into guest paths by **string prefix
     * matching against the bind table**, so a bind whose host side is spelled one way
     * cannot match a path the kernel reports the other way.
     *
     * That is not hypothetical. The guest's *initial working directory* is the bound host
     * directory (proroot's `-w` resolves inside the rootfs and the child ends up on the
     * bind's host side — `ProrootCommand`'s KDoc has the measurement), so `getcwd()`
     * returns a host path spelled `/data/data/…` while the table says `/data/user/0/…`,
     * and everything resolved relative to the cwd then fails. Reproduced inside a live
     * proroot guest on this device, whose launcher argv contained
     * `-b /data/user/0/com.dsh.client/cache/shm:/dev/shm`:
     *
     * ```
     * $ cd /dev/shm && /bin/pwd
     * /data/data/com.dsh.client/cache/shm     # the host spelling: the reverse map missed
     * $ ls .
     * ls: cannot access '.': No such file or directory
     *
     * $ cd /sdcard && /bin/pwd                 # control: the table spells this one
     * /sdcard                                  # the way the kernel does
     * ```
     *
     * `canonicalPath` performs the same resolution the kernel does, so canonicalising the
     * host side makes the two spellings equal **by construction** instead of by luck. It
     * is a no-op for every path that is already canonical (`/dev`, `/proc`, `/sys`,
     * `/storage/emulated/0`, …), and it never touches the guest side.
     *
     * ## The `/proc` exception, and why it is load-bearing
     *
     * `/proc` is synthetic and **process-relative**: `/proc/self` resolves to *this*
     * process, so `canonicalPath` would turn the table's `/proc/self/fd:/dev/fd` entry
     * into `/proc/<the app's pid>/fd` — a bind of the wrong directory, and a *different*
     * string in every process. The entry must stay `/proc/self/fd` precisely so the guest
     * resolves it inside the guest's own proc. So the whole `/proc` subtree is returned as
     * written — and so are its **alias** spellings (`/dev/stdin|stdout|stderr|fd`), which
     * is the same failure one indirection away ([PROC_ALIASED_HOST_PATHS]).
     *
     * An unresolvable path also falls back to the path as written (the precedent is
     * `PiSettingsFileStore.kt:345`): a bind the caller asked for must still be emitted, and
     * that is exactly the case where the launcher's own error is the better report.
     *
     * ## The boundaries, all of them, in one place
     *
     * This is the **only** place a bind's host spelling is decided, so every case has to be
     * answered here rather than at a call site (ordering is the function body's ①②③④):
     *
     * | input | result | why |
     * |---|---|---|
     * | `""` / blank | as written | nothing to resolve; asking the filesystem would be a syscall for no answer |
     * | relative (`rel/x`) | as written | `File("rel/x").canonicalPath` resolves against the **JVM's `user.dir`**, inventing a bind source nobody asked for; the launcher's own error is better |
     * | `/proc`, `/proc/…` | as written | synthetic and process-relative |
     * | `/dev/fd`, `/dev/stdin`, `/dev/stdout`, `/dev/stderr` | as written | procfs descriptor aliases — same class |
     * | absolute, real | `canonicalPath`, **memoised** | what the kernel will report, so the runtimes' prefix map matches |
     * | absolute, missing | `canonicalPath` of what exists + the rest | `realpath` semantics; still emitted |
     * | multi-user `/data/user/<N>` | unchanged for N≠0 (no symlink), `/data/data/…` for N=0 | that is what the kernel does |
     * | `/storage/emulated/0` | unchanged | already the kernel's spelling — measured |
     *
     * The guest side is **never** touched by any of this.
     *
     * ## Cost
     *
     * One resolution per distinct host **per process** ([canonicalHosts]); every later spawn
     * is a map lookup. The bare-JVM harness pins the call count.
     */
    fun canonicalHost(host: String): String {
        // ① 空 / 空白：没有可解析的东西，返回原值（不要去问文件系统）。
        if (host.isBlank()) return host
        // ② 相对路径：`File("x").canonicalPath` 会拿 **JVM 的 user.dir** 拼出一个宿主绝对
        //    路径 —— 一个调用方从没要过的绑来源。启动器的报错比一个编出来的路径好。
        if (!host.startsWith("/")) return host
        // ③ `/proc` 子树与 procfs 的别名拼写：合成的、**相对进程**的，不能被解析
        //    （见 PROC_ALIASED_HOST_PATHS）。
        if (isProcessRelative(host)) return host
        // ④ 其余的按内核的方式解析一次，并在进程内记住（见 canonicalHosts）。
        return canonicalHosts.computeIfAbsent(host) { canonicalResolver(it) }
    }

    /** True when [host] is `/proc`, something under it, or a procfs descriptor alias. */
    private fun isProcessRelative(host: String): Boolean =
        host == PROC || host.startsWith("$PROC/") || host in PROC_ALIASED_HOST_PATHS

    /**
     * One `-b` value with its **host** side canonicalised ([canonicalHost]).
     *
     * A value **without** a colon is a bare host path — proot's shorthand for
     * `host == guest` — and is returned **unchanged**: there is no separate host side to
     * respell, and rewriting it would move the guest's mount point with it. (`ProrootCommand`,
     * which cannot use the shorthand, expands it itself as `canonicalHost(v):v`: only the
     * host side moves, so the guest mount point the shorthand named stays byte-identical.)
     *
     * This is the **only** place a bind's spelling is decided. Every bind both runtimes emit
     * goes through it — the shared table ([binds]), `/tmp` ([tmpBind]) and the callers' extra
     * binds (`PiEngineHost`, `PtyLauncher`, `AgentLayout`, rendered by
     * `ProotCommand`/`ProrootCommand`) — which is what keeps the two runtimes' tables from
     * drifting apart, the thing this object exists for.
     */
    fun bindValue(value: String): String {
        val separator = value.indexOf(BIND_SEPARATOR)
        if (separator < 0) return value
        val host = value.substring(0, separator)
        return "${canonicalHost(host)}$BIND_SEPARATOR${value.substring(separator + 1)}"
    }

    /**
     * How a guest command is handed to the guest's shell — `/bin/bash -c <cmd>`.
     *
     * `-c`, not `-lc`. A login shell sources `/etc/profile` and every file it
     * pulls in, which measured ~1.5 s per guest start on this device — and there
     * is nothing for it to set up: [environment] already hands the guest `PATH`,
     * `HOME`, `TMPDIR`, `TERM` and `LANG` explicitly, and the pinned ubuntu-base
     * ships no `/etc/profile.d` payload this app depends on. Anything that *did*
     * need a login shell would be a dependency on a file the runtime tree can be
     * re-extracted without — exactly the kind of "works until the next wipe" that
     * `PiRuntime` avoids.
     */
    fun shellArgs(guestCommand: String): List<String> = listOf("/bin/bash", "-c", guestCommand)

    /**
     * The guest environment **both** runtimes give: shell basics, the TLS trust
     * store, and the one variable that belongs to the recipe rather than to a
     * runtime.
     *
     * The runtime-specific entries are added by the two builders on top of this
     * map — `PROOT_LOADER`/`PROOT_TMP_DIR`/`PROOT_L2S_DIR`/`LD_LIBRARY_PATH` in
     * `ProotCommand`, `PROROOT_*` in `ProrootCommand` — so no launch path can
     * inherit one runtime's variable while running the other, and a guest entry
     * added here reaches all of them.
     */
    fun environment(paths: PiPaths): Map<String, String> = buildMap {
        // ---- NARB_DISABLE_NATIVE_CACHE -------------------------------------
        // Why it is here, and why it is not conditional on the runtime:
        //
        // The guest runs Node, and Node's native-addon build cache publishes a
        // cached artefact with `link()` + `unlink()`. Under `--link2symlink` —
        // which **both** recipes must always pass, because without it every guest
        // `link()` fails with EACCES on this filesystem (`PiRuntime`'s KDoc, and
        // docs/proroot-research.md §5.P1-3, which also shows proroot granting a
        // *real* hard link where proot emulates one) — that first publication can
        // dangle. Keeping the cache off costs one rebuild per native extension and
        // removes a failure that is silent on the Node side.
        //
        // It belongs to the *recipe*, not to proot: the flag it compensates for is
        // required under both mechanisms. Setting it here rather than in the
        // guest's `/etc/profile.d` is deliberate: this map is what every
        // non-interactive path (`bash -c`) gets, and a profile script would only
        // reach the ones that source it.
        //
        // Source: docs/proroot-research.md §10.3, which records it from DSH App's
        // AGENTS.md ("其默认 link+unlink 缓存会在 --link2symlink 下首次悬空").
        // The device shell (`bridge/DeviceShell.kt`) is deliberately **not** a
        // caller: it runs `/system/bin/sh` as the app's own uid and clears its
        // environment on purpose (`DeviceShell.kt:57-63`), so no guest Node runs
        // there and the variable would be meaningless host clutter.
        put("NARB_DISABLE_NATIVE_CACHE", "1")
        put("HOME", "/root")
        put("TMPDIR", "/tmp")
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        put("PATH", "/opt/pi/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        // The trust store, named explicitly because nothing else names it. The
        // pinned ubuntu-base ships no `/etc/ssl` at all; the git payload installs
        // [GUEST_CA_BUNDLE] and these two variables are what make anything read it.
        //
        // They are not redundant with each other or with the default:
        //  - `GIT_SSL_CAINFO` is git's own override, passed to libcurl as
        //    CAINFO, so git's HTTPS transport loads this one file directly;
        //  - `SSL_CERT_FILE` is the OpenSSL/GnuTLS convention, so the guest's other
        //    TLS consumers (python, wget, anything not carrying its own store) work
        //    too — Node does not need it, because Node ships its own CA store.
        //
        // The explicit path is load-bearing rather than belt-and-braces: libcurl's
        // compiled-in default here is the *directory* `/etc/ssl/certs`, which it
        // reads in `c_rehash` form (`<hash>.0` symlinks). The payload ships the
        // concatenated bundle and no hashed links, so a lookup through the
        // directory default would find nothing and every `https://` clone would
        // fail certificate verification.
        put("GIT_SSL_CAINFO", GUEST_CA_BUNDLE)
        put("SSL_CERT_FILE", GUEST_CA_BUNDLE)
    }

    /**
     * The CA bundle the git payload installs (`RuntimeProvisioner.installGit`
     * extracts it from inside `git.tgz`, generated at build time from the pinned
     * `ca-certificates` deb).
     */
    const val GUEST_CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"
}
