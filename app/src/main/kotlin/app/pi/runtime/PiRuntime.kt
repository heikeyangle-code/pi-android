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

    /** Volatile: re-extracted whenever the packaged runtime version changes. */
    val runtime: File = File(home, "runtime")

    /** The Ubuntu userland (glibc). */
    val rootfs: File get() = File(runtime, "rootfs")

    /** proot's scratch: loader spills, link2symlink targets. */
    val tmp: File get() = File(runtime, "tmp").also { it.mkdirs() }

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
}

/**
 * Builds proot's command line and environment.
 *
 * The argument set is not invented: it is the recipe a shipping Android app in
 * this exact environment uses, read back from a live process
 * (docs/pi-android-app-design.md §2.3). Each flag earns its place:
 *
 *  - `--link2symlink`    the rootfs is on a filesystem where the hardlinks a
 *                        Linux userland normally relies on cannot be created,
 *                        so proot emulates them with symlinks
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
     * @param guestCommand passed to `bash -lc` **inside** the rootfs, so it may
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
        argv += listOf("-b", "${paths.rootfs.path}/.l2s:${paths.rootfs.path}/.l2s")
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
        argv += "-lc"
        argv += guestCommand
        return argv
    }

    /**
     * proot's own environment. These four variables are load-bearing:
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
        put("PROOT_L2S_DIR", "${paths.rootfs.path}/.l2s")
        put("LD_LIBRARY_PATH", "${paths.lib.path}:${paths.nativeLib.path}")
        put("HOME", "/root")
        put("TMPDIR", "/tmp")
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        put("PATH", "/opt/pi/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        putAll(extra)
    }
}
