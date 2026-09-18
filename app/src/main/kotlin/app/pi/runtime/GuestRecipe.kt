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
     */
    fun binds(paths: PiPaths, storage: File?): List<List<String>> = buildList {
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
     * A writable `/tmp`: Android has none, and a missing `TMPDIR` makes pi's bash
     * tool fail to spill oversized output. A real bind (not an env var) because
     * both runtimes resolve it inside the guest.
     */
    fun tmpBind(paths: PiPaths): List<String> = listOf("-b", "${paths.tmp.path}:/tmp")

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
