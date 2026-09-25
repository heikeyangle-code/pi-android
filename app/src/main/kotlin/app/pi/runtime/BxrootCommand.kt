package app.pi.runtime

import java.io.File

/**
 * Builds **bxroot's** command line and environment — the optional third container
 * runtime, opt-in behind its own switch (`app.runtime.bxroot`), independent of
 * [GuestEngine.Proroot]'s switch.
 *
 * ## Why this file is a near-copy of [ProrootCommand]'s two builders
 *
 * bxroot is a **drop-in for proroot's five-piece contract**, not a new architecture:
 * same `launcher + runtime + linker + bridge + stub-loader` layout, same
 * `LD_PRELOAD` path translation, same `-r/-w/-b/-0/--link2symlink` CLI shape. What
 * differs is exactly two things, and both are names:
 *
 *  - **the environment prefix**: `BXROOT_*` instead of `PROROOT_*`;
 *  - **one flag bxroot accepts and proroot rejects**: `--kill-on-exit`.
 *
 * The *logic* — which binds go out, how the workdir is checked, which environment the
 * guest inherits — is deliberately **not copied**: [ProrootCommand.boundPairs],
 * [ProrootCommand.ensureWorkdir] and [ProrootCommand.bindArgument] are called from
 * here, so the two recipes cannot drift on anything that is a property of the shared
 * container model. A second copy of the bind canonicalisation is the defect
 * `GuestRecipe`'s own KDoc exists to prevent, one runtime further out.
 *
 * ## The two spelings that are load-bearing, and why they are the same as proroot's
 *
 *  - `-b host:guest` — proroot v1.2.8 *requires* the colon (`[proroot] bad bind
 *    format (expected host:guest)`), and bxroot's launcher accepts **both**: its
 *    `-b` branch falls back to `add_bind(spec, spec)` when there is no colon
 *    (`src/launcher/launcher.c`, the `strchr(spec, ':') == NULL` path), which is
 *    upstream proot's semantics. So keeping the colon form is the one spelling that
 *    works on both runtimes, and it is why this file needs no bind table of its own.
 *  - `-r` / `-w` — the long spellings `--rootfs=` / `--cwd=` are proot-only; bxroot's
 *    parser accepts `-r`/`--rootfs` and `-w`/`--cwd`/`--pwd`, so the short form stays.
 *
 * ## `--kill-on-exit`: **not passed**, on purpose (2026-09-25)
 *
 * bxroot implements it (proroot rejects it, which is why [GuestTreeReaper] exists at
 * all). Passing it here would hand the guest tree's lifetime to the launcher, and this
 * app's stop path is built the other way round: it signals the launch's pid and reaps
 * the tree itself, from the pid the launch handle resolved. Two owners of one tree is
 * worse than the flag being unused — a reaper that races a launcher-exit sweep is a
 * "sometimes the tree is gone, sometimes not" symptom nothing would attribute. So the
 * capability is recorded here rather than used: switching this app onto it is a
 * separate change with its own verification, and until then bxroot behaves exactly
 * like proroot from [GuestTreeReaper]'s point of view.
 *
 * Android-free (`java.io.File` only) so the bare-JVM `runtime-switch` / `proroot`
 * harnesses can compile it in the library-ownership merge check.
 */
object BxrootCommand {

    /** The launcher this app `execve()`s. Must match `runtime.lock.json` and `PiPaths`. */
    const val LAUNCHER_FILE: String = "libbxroot.so"

    /** Environment names bxroot's launcher reads. One place, so a rename is one edit. */
    const val ENV_TMP_DIR: String = "BXROOT_TMP_DIR"
    const val ENV_LINKER_PATH: String = "BXROOT_LINKER_PATH"
    const val ENV_LIB_PATH: String = "BXROOT_LIB_PATH"
    const val ENV_STUB_LOADER: String = "BXROOT_STUB_LOADER"

    /**
     * The bind separator is the shared one, not a second constant: both runtimes spell
     * a bind as `host:guest` (`GuestRecipe.BIND_SEPARATOR`).
     */
    const val BIND_SEPARATOR: String = GuestRecipe.BIND_SEPARATOR

    /**
     * argv for one guest command.
     *
     * Same shape as [ProrootCommand.build]; see the class KDoc for the two flags whose
     * spelling is shared and for why `--kill-on-exit` is absent.
     */
    fun build(
        paths: PiPaths,
        guestCommand: String,
        cwd: String,
        storage: File?,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> {
        // The same single funnel as the proroot recipe: the bind table is computed
        // once and both consumed by the workdir check and emitted as argv.
        val binds = ProrootCommand.boundPairs(paths, storage, extraBinds)
        // `-w` is resolved by the launcher inside the rootfs `-r` names, so a directory
        // that does not really exist there makes the launch exit 126. bxroot's launcher
        // `chdir`s the same way, so the check is the same check.
        ProrootCommand.ensureWorkdir(paths.rootfs, cwd, binds)
        val argv = mutableListOf<String>()
        argv += paths.bxrootLauncher().absolutePath
        // A guest `link()` in an app-private tree is EACCES at the SELinux layer, not at
        // the filesystem layer (bxroot's `docs/DSHA-适配说明.md` §「app 私有目录禁 link(2)」).
        // Both runtimes therefore take `--link2symlink` unconditionally.
        argv += "--link2symlink"
        argv += "-0"
        argv += listOf("-r", paths.rootfs.path)
        argv += listOf("-w", cwd)
        binds.forEach { (host, guest) -> argv += listOf("-b", "$host$BIND_SEPARATOR$guest") }
        argv += GuestRecipe.shellArgs(guestCommand)
        return argv
    }

    /**
     * The launcher process's environment: the four `BXROOT_*` variables the
     * DSHA/BXROOT contract names, plus the guest's shared base environment.
     *
     * `BXROOT_TMP_DIR` **must be a host path** — the launcher is a host-side process
     * (raw `execve`, the runtime hook not yet installed), which is the same constraint
     * `PROOT_TMP_DIR`/`PROROOT_TMP_DIR` already carry. [PiPaths.bxrootTmp] is one.
     *
     * `BXROOT_ROOTFS` / `BXROOT_GUEST_EXE` / `BXROOT_WORKDIR` / `BXROOT_FAKEROOT` /
     * `BXROOT_LINK2SYMLINK` are deliberately **not** set: bxroot's adapter contract
     * (`docs/DSHA-适配说明.md`) makes the launcher derive them from argv, and setting
     * both sides makes "argv wins" unprovable.
     *
     * The `scandir(3)` `NODE_OPTIONS` injection [ProrootCommand.environment] carries is
     * **absent here on purpose**: that workaround exists for proroot's `scandir` hook
     * returning `ENOENT` on rewritten bind paths (`docs/proroot-scandir-defect.md`), and
     * bxroot's own path-translation notes do not record that defect. Injecting it
     * anyway would put a module load in front of every node process for a bug that is
     * not being worked around.
     */
    fun environment(paths: PiPaths, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put(ENV_TMP_DIR, paths.bxrootTmp.path)
        // Explicit component paths, exactly as the proroot recipe does: discovery through
        // `/proc/self/exe` would depend on how the APK's native-library extractor laid
        // the five files out, and a rename would then fail silently as "component
        // missing" instead of pointing at this line.
        put(ENV_LINKER_PATH, paths.bxrootLinker().absolutePath)
        put(ENV_LIB_PATH, paths.bxrootRuntimeHook().absolutePath)
        put(ENV_STUB_LOADER, paths.bxrootStubLoader().absolutePath)
        putAll(GuestRecipe.environment(paths))
        putAll(extra)
    }

    /**
     * The path `BXROOT_LOG_APPEND` should point at **when tracing is turned on to
     * diagnose something**.
     *
     * Placed beside [ProrootCommand.prorootTraceLog] and for the same reasons: under
     * `<files>`, outside the volatile tree, so `RuntimeProvisioner.wipe()` does not
     * delete a trace that has not been read yet. Nothing in the production path sets
     * the variable — bxroot documents it as a diagnosis switch, and an environment that
     * differs from the reference implementation's on a timing-sensitive path is the
     * mistake `docs/proroot-research.md` §4.3 records for the other runtime.
     */
    fun bxrootTraceLog(paths: PiPaths): File =
        File(paths.home.parentFile ?: paths.home, "bxroot-trace.log")
}
