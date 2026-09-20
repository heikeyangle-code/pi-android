package app.pi.runtime

import java.io.File

/**
 * Filesystem layout of the runtime.
 *
 * The split matters: `<files>/pi` holds user data and survives an app update,
 * while `<files>/pi/runtime` is a volatile tree that is rebuilt whenever the
 * packaged runtime changes. Mixing the two is how an app update eats a user's
 * sessions (docs/pi-android-app-design.md §19.1).
 */
class PiPaths(private val filesDir: File, private val nativeLibDir: File) {

    /** Everything the user owns: sessions, settings, extensions, credentials. */
    val home: File = File(filesDir, "pi")

    /** pi's `PI_HOME`; `agentDir` below it is `PI_CODING_AGENT_DIR`. */
    val agentDir: File get() = File(home, DurableLayout.AGENT_RELATIVE)

    /**
     * The workspace root: `<files>/pi/workspaces`, one directory per workspace.
     *
     * The spelling is [DurableLayout.WORKSPACES_RELATIVE]'s, and
     * `GuestWorkspacePath.ROOT_RELATIVE` (`pi/workspaces`, relative to the *files*
     * directory) is the other side of the same fact; a bare-JVM harness compares the
     * two, so the workspace cannot end up somewhere else than this accessor says.
     */
    val workspaces: File get() = File(home, DurableLayout.WORKSPACES_RELATIVE)

    /**
     * This app's own durable directory: `<files>/pi/persist`.
     *
     * Durable means "must outlive an update", which is why the boot audit lives here
     * ([BootAudit]) — a file whose whole purpose is to compare a boot before an update
     * with the boot after it cannot live in the volatile tree. Not under
     * `<files>/pi/.pi/agent`: that directory is pi's own home, and this app's
     * bookkeeping does not belong in it.
     */
    val persist: File get() = File(home, DurableLayout.PERSIST_RELATIVE)

    /**
     * Where a guest-resolvable tool binary has to live — in **both** of the two
     * directories this class exposes below.
     *
     * The guest spelling `/usr/local/bin/<tool>` is a symlink to
     * `/root/.pi/agent/bin/<tool>`, and which host directory that guest path lands in
     * depends on whether the launch path binds [agentDir] over `/root/.pi/agent`:
     *
     *  - `PiEngineHost` (the chat engine), `GuestCommand` (package commands) and
     *    `PtyLauncher` (the terminal) **all bind it now**, so on every ordinary launch
     *    the guest resolves it to [agentBinDir] and the rootfs copy at
     *    [rootfsAgentBinDir] is **shadowed**.
     *  - The rootfs copy is therefore no longer "the other launch path's copy". It is
     *    the copy that answers *after a tree deletion and before the next provision*: the
     *    explicit repair path (`ensureReady(rebuild = true)`) deletes the whole volatile
     *    tree, `installTool` recreates both, and anything that runs in that window falls
     *    through to the rootfs copy. Keeping it costs two small files.
     *
     * ## The history, because the shape only makes sense with it
     *
     * `PtyLauncher` used to be the one path that did **not** bind the agent dir, which
     * meant the terminal's `pi` read `<rootfs>/root/.pi/agent` — a different agent dir
     * from the chat page's, with different sessions, settings and credentials. That was
     * survivable while the terminal only existed to show pi's own TUI on request. It
     * stopped being survivable when the terminal became "a shell where the user types
     * `pi`": the shell is now a first-class way to run pi, and running it against a
     * second, volatile agent dir is not a smaller version of the chat page, it is a
     * different install. So the terminal binds it too, and the three paths agree.
     *
     * Installing into only one of the two directories still breaks things, just over a
     * narrower window, and the failure is silent either way: a dangling
     * `/usr/local/bin/fd` is indistinguishable from "fd was never installed" to every
     * caller of it, which is how pi's `find` tool and the `@` mention completion both
     * lose their backend with no error printed anywhere. That is why `installTool`
     * writes both and [ensureToolsVisible] repairs both.
     *
     * The two are genuinely different directories and neither contains the other:
     * [agentDir] is `<files>/pi/.pi/agent` (durable — `RuntimeProvisioner` deletes
     * nothing outside `<files>/pi/runtime`), while [rootfsAgentBinDir] is inside the
     * volatile tree and disappears only with the whole tree, on the explicit repair path.
     */
    fun agentBinDir(): File = File(agentDir, "bin")

    /**
     * The rootfs copy of [agentBinDir]. Read [agentBinDir]'s KDoc first: since every
     * launch path binds the agent dir, this copy answers only in the window between a
     * tree deletion (the explicit rebuild) and the next successful provision.
     */
    fun rootfsAgentBinDir(): File = File(rootfs, "root/.pi/agent/bin")

    /** Volatile: the tree a payload change may rewrite, and only the repair path deletes. */
    val runtime: File = File(home, "runtime")

    /**
     * Where this class refuses to be built: a durable directory inside [runtime].
     *
     * The user's report is that an update deletes the workspace root, and the only
     * structural way that can happen is a durable directory — the workspace root, pi's
     * agent dir, or this app's persist dir — being spelled *inside* the volatile tree
     * that provisioning rebuilds. So the invariant is asserted where the paths are
     * built, not left to a comment: with the layout above it can never fire, and if a
     * later change moves one of them under `runtime/` the app fails loudly with the
     * path and the relative offset instead of deleting the user's files on the next
     * update. The same sentence also appears in the diagnostic report's path section,
     * so a build that somehow shipped without this assertion still tells the user.
     */
    init {
        val violations = DurableLayout.violations(home, runtime)
        if (violations.isNotEmpty()) {
            throw IllegalStateException(
                "耐久目录落在易失树内，升级会删掉用户数据：\n" + violations.joinToString("\n"),
            )
        }
    }

    /**
     * The one place per-payload state lives: `<files>/pi/runtime/.payloads`.
     *
     * Inside the volatile tree on purpose. Each payload gets a `<name>.digest` (the
     * digest of the bytes it was extracted from) and a `<name>.list` (every non-directory
     * path it owns, relative to [runtime]) beside it, so the next provision can tell
     * "this payload did not change, touch nothing" from "this payload changed, extract it
     * over the tree and prune only what the old list owned". Deleting the whole tree —
     * the explicit repair path — takes this state with it, which is exactly right: a
     * rebuilt tree has no previous owner to prune against.
     */
    fun payloadStateDir(): File = File(runtime, ".payloads")

    /** The Ubuntu userland (glibc). */
    val rootfs: File get() = File(runtime, "rootfs")

    /** proot's scratch: loader spills, link2symlink targets. */
    val tmp: File get() = File(runtime, "tmp").also { it.mkdirs() }

    /**
     * The guest's `/dev/shm`, as a host directory this app owns.
     *
     * It exists because the shared bind table has to name a **host** directory that is
     * writable: Android's own `/dev` ships no `shm`, and `-b /dev` (which both runtimes
     * apply) would otherwise leave the guest without one — POSIX shared memory
     * (`shm_open`) then fails for whatever needs it (Chromium/Playwright, some native
     * addons). The reference implementation binds `<app cache>/shm:/dev/shm`; this is the
     * same bind with a directory inside the volatile tree, so `wipe()` cleans it and no
     * stale segment outlives the runtime tree.
     *
     * Created by the getter, like [tmp]: [GuestRecipe.binds] reads it on every launch and
     * neither runtime binds a host path that does not exist.
     */
    val shm: File get() = File(runtime, "shm").also { it.mkdirs() }

    /**
     * The store proot's `--link2symlink` keeps its intermediates in, and it **must
     * exist before proot starts**.
     *
     * This is not decoration. `link2symlink.c`'s `move_and_symlink_path` — the
     * handler that turns a guest `link()` into a symlink — opens this directory and
     * returns its failure as the guest's error:
     *
     *     status = open_l2s_directory();
     *     if (status < 0)
     *         return status;          // -ENOENT when the directory is not there
     *
     * `open_l2s_directory` is a plain `open(.., O_DIRECTORY | O_NOFOLLOW)`; proot
     * never creates the directory itself. So a `PROOT_L2S_DIR` that does not exist
     * makes **every** hard link inside the guest fail with `ENOENT`, which reads as
     * "No such file or directory" about two files that are both demonstrably there.
     *
     * On this app that is not a corner case: it is why `apt-get install` cannot
     * upgrade a package. dpkg makes a backup link next to every file it replaces
     * (`./usr/lib/.../afalg.so` in the report that found this), and with hard links
     * broken the run dies at the first already-installed file — `libssl3t64` in that
     * report — even though the download and the permissions were fine.
     *
     * It lives **inside the rootfs** because [build] binds it into the guest at its
     * own absolute path: the intermediates are absolute host paths recorded in
     * symlink targets, so the guest has to be able to resolve them. It is recreated
     * by this getter, so a revision bump that wipes the rootfs cannot leave the next
     * launch without it.
     */
    val l2s: File get() = File(rootfs, ".l2s").also { it.mkdirs() }

    /** Holds the `libtalloc.so.2` alias the dynamic linker insists on. */
    val lib: File get() = File(runtime, "lib").also { it.mkdirs() }

    /** Where the app unpacks its own copy of the engine. */
    val engines: File get() = File(home, "engines")

    val nativeLib: File get() = nativeLibDir

    /** The one location Android lets us execute from. */
    fun prootBinary(): File = File(nativeLibDir, "libproot.so")

    fun prootLoader(): File = File(nativeLibDir, "libprootloader.so")

    // ------------------------------------------------------------------ proroot
    // The five proroot binaries, spelled exactly as `tools/fetch-runtime.mjs`
    // installs them and `runtime.lock.json` pins them. The names are upstream's and
    // are load-bearing in two independent ways (`docs/proroot-research.md` §5.P1-4):
    // Android's native-library extractor only unpacks `lib*.so`, and proroot's own
    // component discovery looks for these names in the directory of
    // `/proc/self/exe`. `ProrootCommand` also passes them explicitly, so a rename
    // would be caught here rather than silently disabling a component.
    //
    // None of these is a reason to touch `fetch-runtime.mjs` or the lock file: they
    // are the *reader* of that contract, and `RuntimeChoice.REQUIRED_FILES` is the
    // same list in the same order the gate checks it.

    /** The proroot launcher — the one file Android `execve()`s. */
    fun prorootLauncher(): File = File(nativeLibDir, "libproroot.so")

    /** The in-process hook (path translation, fake id0, `/proc` synthesis). */
    fun prorootRuntimeHook(): File = File(nativeLibDir, "libproroot-runtime.so")

    /** The clean-room ELF interpreter guest binaries are linked against. */
    fun prorootLinker(): File = File(nativeLibDir, "libproroot-linker.so")

    /** The entry trampoline a guest child re-enters through. */
    fun prorootBridge(): File = File(nativeLibDir, "libproroot-bridge.so")

    /** Static/static-pie and `execve` routing — what keeps `rg`/`fd` translated. */
    fun prorootStubLoader(): File = File(nativeLibDir, "libproroot-stub-loader.so")

    /** The five above, in the order [RuntimeChoice.REQUIRED_FILES] names them. */
    fun prorootComponents(): List<File> = listOf(
        prorootLauncher(),
        prorootRuntimeHook(),
        prorootLinker(),
        prorootBridge(),
        prorootStubLoader(),
    )

    /**
     * The proroot components missing from `nativeLibraryDir`, by file name.
     *
     * "The files exist" is not the same question as "proroot works" — that is what
     * the probe gate answers — but it is the cheapest of the three conditions and the
     * only one that is a property of the APK, so it is answered separately
     * (`RuntimeChoice` fallback ①).
     *
     * **Answered at most once per process.** The five files live in
     * `nativeLibraryDir`, which is extracted from the *installed* APK: within one
     * process those bytes cannot appear or disappear, and shipping a different set
     * means an update, which kills the process. So this is a property of the running
     * install rather than of a launch, and re-`stat`ing five paths on every guest
     * start — the engine, the terminal, every command pi runs — buys nothing. Keyed by
     * the directory path so two installs (or a test fixture) cannot share an entry,
     * exactly as [ProrootProbe.digestOf] is.
     *
     * Callers that must not touch proroot at all while the switch is off (`plan` /
     * `status`) do not call this at all; this cache is what stops the *enabled* path
     * from paying for it per launch.
     */
    fun missingProrootComponents(): List<String> {
        val key = nativeLibDir.path
        missingComponentsCache?.let { if (it.first == key) return it.second }
        val components = prorootComponents()
        val missing = RuntimeChoice.REQUIRED_FILES.filterIndexed { index, _ -> !components[index].isFile }
        missingComponentsCache = key to missing
        return missing
    }

    /**
     * proroot's scratch: the `PROROOT_TMP_DIR` the launcher writes its
     * `.proroot-config-<pid>` tables into.
     *
     * Volatile, like [tmp]: those config files are meaningless after a wipe, and
     * `RuntimeProvisioner.wipe()` deleting the whole tree is the one cleanup that
     * cannot leave a stale one behind. It is a **host** path — the launcher is a
     * host-side process before proroot's hook is installed, so a guest spelling here
     * fails with `[proroot] open config file: No such file or directory`
     * (`docs/proroot-research.md` §4.3).
     */
    fun prorootTmpDir(): File = File(runtime, "proroot-tmp")

    /**
     * The same directory, created on demand.
     *
     * The pair exists because the two uses differ: proroot's launcher needs the
     * directory to exist ([prorootTmp], handed to it as `PROROOT_TMP_DIR`), while the
     * sweep that cleans up after it must be able to **look** without creating anything
     * ([prorootTmpDir]) — otherwise every proot-only user would grow an empty
     * `proroot-tmp` on their first guest launch.
     */
    val prorootTmp: File get() = prorootTmpDir().also { it.mkdirs() }

    /**
     * Records the proroot probe gate's verdict for one revision + binary digest
     * ([ProrootProbeCache]). Inside the volatile tree on purpose: it describes this
     * unpacked rootfs and these payloads, and a verdict that outlived them would be
     * a pass for a tree that no longer exists.
     */
    fun prorootProbeCache(): File = File(runtime, ".proroot-probe")

    /**
     * Throw the cached verdict away so the next proroot launch re-runs the gate.
     *
     * Called when the user turns the switch on — see
     * [RuntimeChoice.invalidatesProbeCache] for the defect that makes this necessary
     * (a cached *failure* whose key cannot change would otherwise disable proroot
     * permanently, and re-opening the switch would appear to do nothing).
     *
     * It lives here, next to [prorootProbeCache], because the file this deletes and the
     * file the gate reads and writes must be **the same file**; that is the kind of
     * agreement that drifts when it is spelled twice. Returns whether a file was there —
     * "nothing to invalidate" is the ordinary case and is not an error.
     */
    fun clearProrootProbeCache(): Boolean = prorootProbeCache().delete()

    /**
     * The **engine-failure autopsy** of the last proroot engine launch
     * ([ProrootProbe.autopsy]), or a missing file when there has not been one.
     *
     * It lives next to [prorootProbeCache] and inside the same volatile tree for the same
     * reason: it is a statement about *this* unpacked rootfs, this Node and these proroot
     * bytes, so it must not outlive them — a forensic block from an older tree would send
     * the next reader after a defect that is already gone.
     *
     * A file rather than a field of the live session because the failure outlives the
     * session: `PiEngineHost.publish(null)` drops the engine that produced it, and the
     * diagnostic report is assembled later, from `Context` + [PiPaths] alone. Writing it
     * here is also what makes the report's own reader (`DiagnosticsReport`) independent of
     * whoever happens to hold the engine — it reads the same file the settings row can.
     *
     * Written **only after a launch failure**; a normal start neither creates nor reads it.
     */
    fun prorootEngineForensics(): File = File(runtime, ".proroot-engine-forensics")

    /**
     * proot links against `libtalloc.so.2`, but jniLibs only lets files named
     * `lib*.so` through. The real file ships as `libtalloc.so`; this alias is
     * what satisfies the SONAME.
     */
    fun prepareLibraryAliases() {
        val real = File(nativeLibDir, "libtalloc.so")
        if (!real.exists()) return
        val alias = File(lib, "libtalloc.so.2")
        if (java.nio.file.Files.isSymbolicLink(alias.toPath()) || alias.exists()) alias.delete()
        runCatching {
            java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
        }
    }

    /** Marker recording which packaged runtime revision is unpacked. */
    fun stampFile(): File = File(runtime, ".stamp")

    /**
     * Marker recording which unpacked revision passed [RuntimeSelfCheck].
     *
     * A separate file from [stampFile], on purpose: that one means "this revision
     * is unpacked" and is written by the provisioner, this one means "a guest
     * binary has actually been executed at this revision" and is written by the
     * check. Sharing one file would make either statement imply the other. It lives
     * inside the runtime tree, so `RuntimeProvisioner.wipe()` deletes it together
     * with what it describes.
     */
    fun selfCheckStamp(): File = File(runtime, ".selfcheck")

    companion object {
        /**
         * [missingProrootComponents]'s answer, per `nativeLibraryDir`.
         *
         * Process-wide rather than per-instance because `PiPaths` is built fresh in a
         * dozen places (`PiEngineHost`, `PtyLauncher`, the settings stack, the bridge,
         * …) and a per-instance memo would never hit. The value is a property of the
         * installed APK, so there is nothing to invalidate: see the function's KDoc.
         * `@Volatile` because the engine, the terminal and the package commands can
         * ask from different threads; a lost race recomputes the same answer.
         */
        @Volatile
        private var missingComponentsCache: Pair<String, List<String>>? = null
    }
}

/**
 * Builds proot's command line and environment: the **fallback** runtime, and the
 * only runtime the install/maintenance paths ever use.
 *
 * The argument set is **our own pinned Termux proot recipe**, documented in
 * `docs/pi-android-app-design.md` §2.3. It is not "what a shipping app does
 * today": it was first read back from DSH App's process while that app still ran
 * Termux proot, and DSH App runs proroot now — its environment has no `PROOT_*`
 * variable left. So this file, not that process, is the definition. The opt-in
 * second runtime lives in [ProrootCommand], and §2.3.1 is the item-by-item table
 * between the two; what they share is [GuestRecipe]. Each flag earns its place:
 *
 *  - `--link2symlink`    the rootfs is on a filesystem where the hardlinks a
 *                        Linux userland normally relies on cannot be created,
 *                        so proot emulates them with symlinks — **and the store
 *                        it keeps those in has to exist**, or the emulation
 *                        reports ENOENT instead of working: [PiPaths.l2s]
 *  - `-0`                present as uid 0 inside; note this is a *fiction* —
 *                        `chown` appears to succeed and does nothing
 *  - `-b /proc -b /sys -b /dev`  things glibc and Node probe at startup (shared)
 *  - `-b /system -b /apex`       Android's own runtime bits, read-only (shared)
 *  - `/proc/self/fd:/dev/fd`     `/dev/fd` is a procfs symlink that does not
 *                                resolve across the proot boundary (shared)
 *  - `--kill-on-exit`    otherwise a killed app leaks a whole process tree. This
 *                        one is **proot-only**; proroot rejects it, and on proroot
 *                        the tree is reaped by [GuestTreeReaper] instead.
 *
 * The four `-b` pairs and the shell tail come from [GuestRecipe] rather than being
 * spelled here, so `ProrootCommand` cannot drift away from this list.
 */
object ProotCommand {

    /**
     * @param guestCommand passed to `bash -c` **inside** the rootfs, so it may
     *        use guest paths (`/opt/pi/node`, `/workspace`, …).
     */
    fun build(
        paths: PiPaths,
        guestCommand: String,
        cwd: String,
        storage: File?,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> {
        val argv = mutableListOf<String>()
        argv += paths.prootBinary().absolutePath
        argv += "--link2symlink"
        // Bound at its own absolute path, and the source is the same `l2s` getter
        // that creates it: the intermediates are host paths recorded in symlink
        // targets, so the guest must be able to resolve them there. See [PiPaths.l2s]
        // for what happens when this directory is missing - every hard link in the
        // guest fails, and dpkg stops being able to upgrade anything.
        //
        // proroot needs none of this: it anchors its own link handling at
        // `<rootfs>/.l2s` and is not told about it (`docs/proroot-research.md`
        // §4.3). That is why this pair is not in [GuestRecipe].
        //
        // **Deliberately not run through [GuestRecipe.bindValue].** Both sides of this pair
        // are the *same* string on purpose — it is the one bind that is addressed by its host
        // spelling from inside the guest, and `PROOT_L2S_DIR` below names that same spelling —
        // so canonicalising one side would split the pair rather than fix it. It is also not
        // an app-data path in the sense the alias bug is about: the guest reaches it through
        // the rootfs prefix, which the runtime strips structurally (measured: a cwd under the
        // rootfs translates correctly even when `-r` is spelled `/data/user/0/...`).
        argv += listOf("-b", "${paths.l2s.path}:${paths.l2s.path}")
        // `-L` keeps the guest's own absolute symlinks meaningful. Also proot-only:
        // proroot resolves them itself (§7.3).
        argv += listOf("-L", "--kill-on-exit", "-0")
        argv += "--rootfs=${paths.rootfs.path}"
        argv += "--cwd=$cwd"
        GuestRecipe.binds(paths, storage).forEach { argv += it }
        // The caller's binds (the workspace, the agent dir) carry the app's data-directory
        // path, which `Context.getFilesDir()` spells `/data/user/0/...` while the guest
        // kernel spells it `/data/data/...`. proot's reverse mapping is the same string
        // prefix match proroot's is, so both runtimes get the host side respelled by the one
        // shared rule rather than only the runtime whose failure happened to be measured
        // (`GuestRecipe.canonicalHost` has the measurement and the `/proc` exception).
        extraBinds.forEach { (host, guest) -> argv += listOf("-b", GuestRecipe.bindValue("$host:$guest")) }
        argv += GuestRecipe.tmpBind(paths)
        argv += GuestRecipe.shellArgs(guestCommand)
        return argv
    }

    /**
     * The guest process's environment. This is the **only** place a proot guest env
     * is built: every launch path goes through it, which is what keeps the engine,
     * the terminal, the package commands and the self-check from handing the
     * guest four different environments (`PtyLauncher`'s KDoc records what
     * happened the one time a path filtered part of this map out).
     *
     * The runtime-independent half (`HOME`, `PATH`, the CA bundle,
     * `NARB_DISABLE_NATIVE_CACHE`) comes from [GuestRecipe.environment]. The
     * load-bearing proot-specific entries are:
     *
     *  - `PROOT_LOADER` — without it proot writes its loader into a temp dir it
     *    can no longer execute from (Android 10+ W^X). Pointing at the
     *    nativeLibraryDir copy is what makes this work on modern Android.
     *  - `PROOT_TMP_DIR` — where `--link2symlink` puts its shims.
     *  - `PROOT_L2S_DIR` — link2symlink state, kept inside the rootfs.
     *  - `LD_LIBRARY_PATH` — so `libtalloc.so.2` resolves to the alias. Deliberately
     *    absent from [ProrootCommand]: proroot does not link against talloc, and
     *    keeping it there would inject host paths into a guest with no use for them.
     */
    fun environment(paths: PiPaths, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("PROOT_LOADER", paths.prootLoader().absolutePath)
        put("PROOT_TMP_DIR", paths.tmp.path)
        put("PROOT_L2S_DIR", paths.l2s.path)
        put("LD_LIBRARY_PATH", "${paths.lib.path}:${paths.nativeLib.path}")
        putAll(GuestRecipe.environment(paths))
        putAll(extra)
    }

    /**
     * The CA bundle the git payload installs and [environment] points at
     * (`RuntimeProvisioner.installGit` extracts it from inside `git.tgz`, generated
     * at build time from the pinned `ca-certificates` deb).
     *
     * Forwarded from [GuestRecipe.GUEST_CA_BUNDLE], which is the single definition
     * both runtimes read; this alias stays because callers and harnesses already
     * spell it here, and a second literal is exactly what would drift.
     */
    const val GUEST_CA_BUNDLE = GuestRecipe.GUEST_CA_BUNDLE
}

/**
 * Replace a small text file's contents atomically, for the runtime's stamp files.
 *
 * The files this is used for ([PiPaths.stampFile], [PiPaths.selfCheckStamp], and the
 * per-payload `.digest`/`.list` files under `PiPaths.payloadStateDir`) are all read back
 * and compared, and all were written with `writeText`, which truncates first. A process
 * killed between the truncate and the write therefore leaves a **short** value, and a
 * short stamp is read as "this revision is not unpacked": the next boot re-reads and
 * re-compares every payload's state (and, for a short *payload* digest, re-extracts a
 * payload that was fine — or, for a short list, makes the next prune miss entries), and
 * `PiEngineHost.restart` refuses to restart at all. The same pattern is already the
 * house rule for `settings.json` and `auth.json` (`PiConfigFiles.write`,
 * `PiSettingsFileStore.writeDocument`); this is the runtime's copy of it, kept here
 * because the writers live in this package and a third spelling of it is exactly
 * what would drift.
 *
 * `Files.move` with `REPLACE_EXISTING` is an atomic rename within the same
 * directory, which is the same volume by construction (the temp file is created
 * beside the target). No `fsync`: these stamps describe work that has already been
 * flushed to the same filesystem, and the failure this prevents is a *truncated*
 * stamp, not a lost one.
 *
 * @return true when the target now holds [text].
 */
internal fun writeStampAtomically(target: File, text: String): Boolean = runCatching {
    target.parentFile?.mkdirs()
    // Suffixed with the wall clock rather than a pid: `android.os.Process` would make
    // this file Android-dependent (the bare-JVM checks compile it) and
    // `java.lang.ProcessHandle` is not on every API level this app supports. Two
    // writers of the same target would have to start within a nanosecond to collide.
    val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
    temp.writeText(text)
    java.nio.file.Files.move(
        temp.toPath(),
        target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    )
    temp.delete()
    true
}.getOrDefault(false)
