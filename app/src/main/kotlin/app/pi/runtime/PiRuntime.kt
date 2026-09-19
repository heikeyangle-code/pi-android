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
    val agentDir: File get() = File(home, ".pi/agent")

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
     *    the copy that answers *before the next provision*: `wipe()` deletes the whole
     *    volatile tree, `installTool` recreates both, and anything that runs in the
     *    window between a wipe and the next successful provision falls through to the
     *    rootfs copy. Keeping it costs two small files.
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
     * [agentDir] is `<files>/pi/.pi/agent` (durable — `RuntimeProvisioner.wipe()`
     * deletes only `<files>/pi/runtime`), while [rootfsAgentBinDir] is inside the
     * volatile tree and is destroyed by every runtime revision bump.
     */
    fun agentBinDir(): File = File(agentDir, "bin")

    /**
     * The rootfs copy of [agentBinDir]. Read [agentBinDir]'s KDoc first: since every
     * launch path binds the agent dir, this copy answers only in the window between a
     * `wipe()` and the next successful provision, and it is the one
     * `RuntimeProvisioner.wipe()` deletes whenever the runtime revision changes.
     */
    fun rootfsAgentBinDir(): File = File(rootfs, "root/.pi/agent/bin")

    /** Volatile: re-extracted whenever the packaged runtime version changes. */
    val runtime: File = File(home, "runtime")

    /** The Ubuntu userland (glibc). */
    val rootfs: File get() = File(runtime, "rootfs")

    /** proot's scratch: loader spills, link2symlink targets. */
    val tmp: File get() = File(runtime, "tmp").also { it.mkdirs() }

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
}

/**
 * Builds proot's command line and environment.
 *
 * The argument set is **our own pinned Termux proot recipe**, documented in
 * `docs/pi-android-app-design.md` §2.3. It is not "what a shipping app does
 * today": it was first read back from a live process, and that process is no
 * longer the definition — this file is. Each flag earns its place:
 *
 *  - `--link2symlink`    the rootfs is on a filesystem where the hardlinks a
 *                        Linux userland normally relies on cannot be created,
 *                        so proot emulates them with symlinks — **and the store
 *                        it keeps those in has to exist**, or the emulation
 *                        reports ENOENT instead of working: [PiPaths.l2s]
 *  - `-0`                present as uid 0 inside; note this is a *fiction* —
 *                        `chown` appears to succeed and does nothing
 *  - `-b /proc -b /sys -b /dev`  things glibc and Node probe at startup
 *  - `-b /system -b /apex`       Android's own runtime bits, read-only
 *  - `/proc/self/fd:/dev/fd`     `/dev/fd` is a procfs symlink that does not
 *                                resolve across the proot boundary
 *  - `--kill-on-exit`    otherwise a killed app leaks a whole process tree
 */
object ProotCommand {

    /** Bind mounts every launch needs. */
    private fun baseBinds(paths: PiPaths, storage: File?): List<List<String>> = buildList {
        add(listOf("-b", "/dev"))
        add(listOf("-b", "/dev/urandom:/dev/random"))
        add(listOf("-b", "/proc"))
        add(listOf("-b", "/sys"))
        add(listOf("-b", "/system"))
        add(listOf("-b", "/apex"))
        add(listOf("-b", "/proc/self/fd:/dev/fd"))
        if (storage != null) {
            add(listOf("-b", "${storage.path}:/sdcard"))
            add(listOf("-b", "${storage.path}:/storage/emulated/0"))
        }
    }

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
        argv += listOf("-b", "${paths.l2s.path}:${paths.l2s.path}")
        // `-L` keeps the guest's own absolute symlinks meaningful.
        argv += listOf("-L", "--kill-on-exit", "-0")
        argv += "--rootfs=${paths.rootfs.path}"
        argv += "--cwd=$cwd"
        baseBinds(paths, storage).forEach { argv += it }
        extraBinds.forEach { (host, guest) -> argv += listOf("-b", "$host:$guest") }
        // A writable /tmp: Android has none, and a missing TMPDIR makes pi's
        // bash tool fail to spill oversized output.
        argv += listOf("-b", "${paths.tmp.path}:/tmp")
        argv += "/bin/bash"
        // `-c`, not `-lc`. A login shell sources `/etc/profile` and every file it
        // pulls in, which measured ~1.5 s per guest start on this device — and there
        // is nothing for it to set up: [environment] already hands the guest
        // `PATH`, `HOME`, `TMPDIR`, `TERM`, `LANG` and `LD_LIBRARY_PATH`
        // explicitly, and the pinned ubuntu-base ships no `/etc/profile.d` payload
        // this app depends on. Anything that *did* need a login shell would be a
        // dependency on a file the runtime tree can be re-extracted without —
        // exactly the kind of "works until the next wipe" that this file avoids.
        argv += "-c"
        argv += guestCommand
        return argv
    }

    /**
     * The guest process's environment. This is the **only** place a guest env is
     * built: every launch path goes through it, which is what keeps the engine, the
     * terminal, the package commands and the self-check from handing the guest four
     * different environments (`PtyLauncher`'s KDoc records what happened the one
     * time a path filtered part of this map out).
     *
     * The load-bearing proot-specific entries are:
     *
     *  - `PROOT_LOADER` — without it proot writes its loader into a temp dir it
     *    can no longer execute from (Android 10+ W^X). Pointing at the
     *    nativeLibraryDir copy is what makes this work on modern Android.
     *  - `PROOT_TMP_DIR` — where `--link2symlink` puts its shims.
     *  - `PROOT_L2S_DIR` — link2symlink state, kept inside the rootfs.
     *  - `LD_LIBRARY_PATH` — so `libtalloc.so.2` resolves to the alias.
     */
    fun environment(paths: PiPaths, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("PROOT_LOADER", paths.prootLoader().absolutePath)
        put("PROOT_TMP_DIR", paths.tmp.path)
        put("PROOT_L2S_DIR", paths.l2s.path)
        put("LD_LIBRARY_PATH", "${paths.lib.path}:${paths.nativeLib.path}")
        // The guest runs Node, and Node's native-addon build cache publishes a cached
        // artefact with `link()` + `unlink()`. Under `--link2symlink` — which this
        // recipe must always pass, because without it every guest `link()` fails with
        // EACCES on this filesystem — that first publication can dangle. The failure is
        // silent on the Node side, so the cache is turned off here instead of being
        // left for a device to discover; the cost is one rebuild per native extension.
        // It belongs in this map and not in the guest's `/etc/profile.d`, because a
        // non-interactive `bash -c` never sources a profile script.
        //
        // Source: docs/proroot-research.md §10.3, recorded from DSH App's AGENTS.md
        // ("其默认 link+unlink 缓存会在 --link2symlink 下首次悬空").
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
        putAll(extra)
    }

    /**
     * The CA bundle the git payload installs and [environment] points at
     * (`RuntimeProvisioner.installGit` extracts it from inside `git.tgz`, generated
     * at build time from the pinned `ca-certificates` deb).
     */
    const val GUEST_CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"
}

/**
 * Replace a small text file's contents atomically, for the runtime's stamp files.
 *
 * The two files this is used for ([PiPaths.stampFile], [PiPaths.selfCheckStamp]) are
 * both read back with "does the text equal the revision?" and both are written with
 * `writeText`, which truncates first. A process killed between the truncate and the
 * write therefore leaves a **short** stamp, and a short stamp is read as "this
 * revision is not unpacked": the next boot re-unpacks the whole runtime (tens of
 * seconds, and on a revision change a destructive `wipe()`), and
 * `PiEngineHost.restart` refuses to restart at all. The same pattern is already the
 * house rule for `settings.json` and `auth.json` (`PiConfigFiles.write`,
 * `PiSettingsFileStore.writeDocument`); this is the runtime's copy of it, kept here
 * because both writers live in this package and a third spelling of it is exactly
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
