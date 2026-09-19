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
    const val BIND_SEPARATOR = ":"

    /**
     * One bind value in proroot's spelling.
     *
     * proot treats `-b /proc` as `/proc:/proc`; proroot v1.2.8 parses the value with
     * `strchr(value, ':')` and rejects the invocation outright when there is no colon
     * (class KDoc has the addresses and the message). An already-qualified value is
     * passed through untouched so this can never mangle `host:guest` pairs, and the
     * function is deliberately pure and public: the `proroot` harness pins both
     * directions, because the failure it prevents is silent.
     */
    fun bindArgument(value: String): String =
        if (value.contains(BIND_SEPARATOR)) value else "$value$BIND_SEPARATOR$value"

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
        argv += listOf("-r", paths.rootfs.path)
        argv += listOf("-w", cwd)
        // The shared *table*, respelled for proroot: every entry goes out as
        // `-b host:guest`, including the ones the shared recipe spells as a lone host
        // path. Keep the flag from the pair rather than retyping "-b" here, so the
        // shared recipe stays the only place that decides what is bound.
        GuestRecipe.binds(paths, storage).forEach { (flag, value) ->
            argv += listOf(flag, bindArgument(value))
        }
        extraBinds.forEach { (host, guest) -> argv += listOf("-b", "$host:$guest") }
        argv += GuestRecipe.tmpBind(paths)
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
