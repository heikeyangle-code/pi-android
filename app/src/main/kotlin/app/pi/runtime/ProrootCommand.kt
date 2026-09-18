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
 *  - everything else (the bind table, `/bin/bash -c`) is shared with proot
 *    verbatim through [GuestRecipe].
 *
 * Environment: proroot recognises **no** `PROOT_*` variable, so none is set here.
 * In their place the launcher is told where its own components are. DSH App passes
 * the same three (`docs/proroot-research.md` §8.1 is its measured environment), and
 * doing so is the recommendation of §5.P1-4: upstream discovers
 * `libproroot-runtime.so` / `-linker.so` / `-stub-loader.so` by **fixed name in the
 * directory of `/proc/self/exe`**, which happens to be `nativeLibraryDir` today
 * (jniLibs keeps upstream's names — `tools/fetch-runtime.mjs` renames nothing), but
 * a rename would break that discovery silently. Naming them removes the coupling.
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
        GuestRecipe.binds(paths, storage).forEach { argv += it }
        extraBinds.forEach { (host, guest) -> argv += listOf("-b", "$host:$guest") }
        argv += GuestRecipe.tmpBind(paths)
        argv += GuestRecipe.shellArgs(guestCommand)
        return argv
    }

    /**
     * The launcher process's environment.
     *
     * [PiPaths.prorootTmp] **must be a host path**, and it is: the launcher is a
     * host-side process (raw `execve`, proroot's hook not yet installed), so a guest
     * spelling there fails with `[proroot] open config file: No such file or
     * directory` (`docs/proroot-research.md` §4.3, which hit exactly that). The
     * proot recipe already had this right (`PROOT_TMP_DIR` is a `File.absolutePath`),
     * so this is a rename rather than a fix.
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
