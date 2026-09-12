package app.pi.runtime

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * Unpacks the bundled runtime on first launch, and again whenever the packaged
 * runtime revision changes.
 *
 * Two trees, deliberately separated (docs/pi-android-app-design.md §19.1):
 *  - `<files>/pi` holds user data — sessions, settings, extensions, credentials —
 *    and must survive an app update untouched.
 *  - `<files>/pi/runtime` is volatile and is rebuilt wholesale when [revision]
 *    changes. Anything a user cares about living here would be destroyed by an
 *    update, so nothing they care about is allowed to live here.
 *
 * The work is a sequence of named steps so the first launch can show honest
 * progress instead of a spinner: unpacking a rootfs takes tens of seconds on a
 * phone and an unexplained wait reads as a hang.
 */
class RuntimeProvisioner(
    private val paths: PiPaths,
    private val assets: AssetManager,
) {

    data class Step(val label: String, val index: Int, val total: Int) {
        val fraction: Float get() = index.toFloat() / total.toFloat()
    }

    class ProvisioningException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** One payload archive the assembler is expected to place in `assets/runtime/`. */
    private data class Payload(val name: String, val required: Boolean)

    /** What the pre-flight audit found for one payload. */
    private data class PayloadInfo(
        val asset: String,
        val required: Boolean,
        val bytes: Long,
        val via: String?,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null && bytes > 0L
    }

    /**
     * The pre-flight audit's result as rendered for the screen, one payload per
     * line. Written by [auditPayloads]; read by [payloadReportBlock] when a later
     * step fails, so a failure after the audit still carries the audit with it.
     */
    private var payloadReport: String = ""

    /**
     * @param revision any string that changes when the packaged payload changes
     *        (the app's versionCode plus a payload hash). Recorded in a stamp
     *        file; a mismatch triggers a clean re-unpack.
     */
    suspend fun ensureReady(revision: String, onStep: (Step) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (isStampCurrent(revision) && paths.rootfs.isDirectory) {
                    paths.prepareLibraryAliases()
                    // The two tools live in *two* directories, and one of them is
                    // outside the stamped tree ([PiPaths.agentBinDir]). A boot that
                    // does not re-unpack is therefore still the only thing that can
                    // repair a tool the agent-dir migration moved out of the rootfs —
                    // see [ensureToolsVisible]. Cheap: two file-existence probes.
                    ensureToolsVisible()
                    return@runCatching
                }
                val steps = buildList {
                    add("校验内置载荷")
                    add("准备存储")
                    add("解压 Ubuntu 用户态")
                    add("解压 Node 运行时")
                    add("安装 rg / fd")
                    add("安装 git")
                    add("配置 DNS 与目录")
                    add("解压 pi 引擎")
                    add("完成")
                }.let { it }

                var index = 0
                fun next(label: String = steps[index]) = onStep(Step(label, index, steps.size))

                // Pre-flight, and deliberately *before* wipe(): the audit is the
                // step that can prove the APK's payload is unusable, and a package
                // that cannot be read must not destroy a runtime that works.
                // `auditPayloads` throws only for the four payloads the unpack
                // needs; see its KDoc for why the engine is reported but not
                // required. Its file-and-size list goes on screen in the step
                // label so the next device failure is self-explanatory.
                next()
                val payloads = auditPayloads()
                onStep(Step(auditLabel(payloads), index, steps.size))
                index++

                next(); wipe()
                index++

                next(); extractAsset(UBUNTU_BASE, paths.rootfs)
                index++

                next(); extractNode()
                index++

                next(); installTool(RIPGREP_ARCHIVE, "rg"); installTool(FD_ARCHIVE, "fd")
                index++

                next(); installGit()
                index++

                next(); configureGuest()
                index++

                next(); extractEngine()
                index++

                next(); paths.prepareLibraryAliases(); writeStamp(revision)
                onStep(Step(steps.last(), steps.size, steps.size))
            }
        }

    // ------------------------------------------------------------------- steps

    private fun wipe() {
        // Only the volatile tree, never `paths.home`.
        paths.runtime.deleteRecursively()
        paths.runtime.mkdirs()
        paths.tmp.mkdirs()
        paths.lib.mkdirs()
    }

    private fun extractAsset(assetName: String, into: File) {
        val archive = "runtime/$assetName"
        // `AssetManager.open` throws a bare-path `FileNotFoundException` when the
        // asset is not in the APK — but an unpack that dies half way (disk full, a
        // truncated archive, a rejected symlink) can carry a bare path in its
        // message too, and the old single catch turned both into
        // "failed to unpack ubuntu-base.tgz: runtime/ubuntu-base.tgz". That
        // ambiguity is indistinguishable from a device failure and cost a round
        // trip; [openPayload] now names the access layer that failed and the
        // pre-flight audit is appended here, so even a half-way failure arrives
        // with the contents of the package attached.
        val stream = openPayload(archive)
        try {
            stream.use { TarExtractor(into).extractGzip(it) }
        } catch (e: IOException) {
            throw ProvisioningException("failed to unpack $assetName: ${e.message}${payloadReportBlock()}", e)
        }
    }

    /**
     * Open a payload archive from the APK, and if that fails, say what the APK
     * actually contains and which access layer failed.
     *
     * A device reported `failed to unpack ubuntu-base.tgz: runtime/ubuntu-base.tgz`
     * — this class's own message, naming the asset it could not open — on an APK
     * that is 127,195,427 bytes, while the payload archives alone account for
     * ~115 MB of it, so the payload was demonstrably in the package. Two things were
     * wrong with the old code as a diagnostic: it used one access mode, and when that
     * mode failed it said nothing about the state of the APK, so a missing asset, a
     * differently-placed asset and a broken install were indistinguishable.
     *
     * There was in fact a third thing wrong, and it was the cause: the asset was
     * there under a *different name*, because AAPT2 gunzips and renames any asset
     * whose name ends in `.gz`. That is why this method lists the directory it was
     * told to read — the listing is what named the real problem. See
     * [PAYLOAD_SUFFIX].
     *
     * Three layers now, tried in order, each failure recorded separately:
     *
     *  1. `open(ACCESS_STREAMING)` — the normal path; inflates the entry itself.
     *  2. `open(ACCESS_BUFFER)` — some install paths have been observed to serve
     *     one mode and not the other.
     *  3. `openFd()` + `AssetFileDescriptor.createInputStream()` — the classic way
     *     to read an asset, and for a *stored* entry it is a plain file read with
     *     no inflate step. It only works on stored entries (a compressed one
     *     throws "can not be opened as a file descriptor; it is probably
     *     compressed"), which is exactly what `noCompress += "gz"`
     *     in app/build.gradle.kts now guarantees for these suffixes: layer 3 and
     *     that build setting are one fix, not two.
     *
     * If all three fail, the message names each layer's failure (exception class
     * and message, so `FileNotFoundException` and a truncated-entry `IOException`
     * are not the same text) and lists `assets/runtime/` and the asset root as the
     * APK actually serves them.
     */
    private fun openPayload(archive: String): InputStream {
        val failures = mutableListOf<String>()
        var last: IOException? = null

        try {
            return assets.open(archive, AssetManager.ACCESS_STREAMING)
        } catch (e: IOException) {
            last = e
            failures += "1 open(ACCESS_STREAMING) failed: ${describe(e)}"
        }

        try {
            return assets.open(archive, AssetManager.ACCESS_BUFFER)
        } catch (e: IOException) {
            last = e
            failures += "2 open(ACCESS_BUFFER) failed: ${describe(e)}"
        }

        try {
            val fd = assets.openFd(archive)
            // `createInputStream()` hands back a stream over the descriptor. The
            // wrapper closes the AssetFileDescriptor as well as the stream, and
            // makes closing twice (or close-after-close) harmless: a leaked
            // descriptor would be one more way to make a *later* payload fail.
            return object : FilterInputStream(fd.createInputStream()) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        super.close()
                    } finally {
                        runCatching { fd.close() }
                    }
                }
            }
        } catch (e: IOException) {
            last = e
            failures += "3 openFd() failed: ${describe(e)}"
        }

        throw ProvisioningException(
            buildString {
                append("packaged asset unreadable: $archive could not be opened from this APK.\n")
                failures.forEach { append("  ").append(it).append("\n") }
                append("assets/runtime/ contains: ").append(listAssets("runtime")).append("\n")
                append("assets/ root contains: ").append(listAssets("")).append("\n")
                append(payloadReportBlock())
                append(PAYLOAD_HINT)
            },
            last,
        )
    }

    // ------------------------------------------------------------- payload audit

    /**
     * Confirm, before anything is unpacked, that each payload archive is present
     * in the APK *and readable*, and record its size.
     *
     * This is the screen-visible half of the fix for the device report above.
     * Waiting for the unpack to fail tells the user almost nothing: the wipe has
     * already run, and only one asset is named. Auditing all five first turns the
     * next device attempt into a list of what the APK actually contains — file
     * names and sizes, in the step label, and again in any later failure message
     * through [payloadReportBlock].
     *
     * The audit runs before [wipe] on purpose: a package that cannot be read must
     * not destroy a runtime that already works.
     *
     * A missing or unreadable *required* payload throws here (the unpack would fail
     * on it a moment later, after the wipe). `pi-engine.tgz` is reported but not
     * required, mirroring [extractEngine], which treats a package without the
     * engine as provisionable and leaves installing it for later.
     */
    private fun auditPayloads(): List<PayloadInfo> {
        val audited = PAYLOADS.map { inspectPayload(it) }
        payloadReport = audited.joinToString("\n") { it.line() }

        val missing = audited.filter { it.required && !it.ok }
        if (missing.isNotEmpty()) {
            throw ProvisioningException(
                buildString {
                    append("bundled runtime payload unusable: ")
                    append(missing.joinToString(", ") { it.asset })
                    append("\n")
                    append(payloadReport).append("\n")
                    missing.forEach { info ->
                        append("\nreading ").append(info.asset).append(" failed:\n")
                        append(info.error ?: "(no detail)").append("\n")
                    }
                    append("\nassets/runtime/ contains: ").append(listAssets("runtime"))
                    append("\nassets/ root contains: ").append(listAssets(""))
                    append("\n").append(PAYLOAD_HINT)
                },
            )
        }
        return audited
    }

    private fun inspectPayload(payload: Payload): PayloadInfo {
        val asset = "runtime/${payload.name}"
        return try {
            val (bytes, via) = payloadSize(asset)
            PayloadInfo(asset, payload.required, bytes, via, null)
        } catch (e: IOException) {
            PayloadInfo(asset, payload.required, 0L, null, describe(e))
        }
    }

    /**
     * Size of one payload in bytes, plus which access path produced the number.
     *
     * `AssetManager.openFd` is tried first: for a *stored* (uncompressed) ZIP
     * entry the length comes straight out of the APK directory without moving a
     * byte, and the `androidResources { noCompress += ... }` block in
     * app/build.gradle.kts is what stores these suffixes. On an APK where AAPT2
     * deflated them, `openFd` throws its "probably compressed"
     * `FileNotFoundException` and the length is counted off the stream instead —
     * through [openPayload], the same chain the unpacker uses, so an unreadable
     * payload is reported with the same per-layer detail and the same directory
     * listings.
     */
    private fun payloadSize(asset: String): Pair<Long, String> {
        val viaFd = runCatching { assets.openFd(asset).use { it.length } }.getOrNull()
        if (viaFd != null && viaFd > 0L) return viaFd to "openFd"
        val counted = openPayload(asset).use { countBytes(it) }
        return counted to "stream"
    }

    private fun countBytes(stream: InputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return total
            total += read.toLong()
        }
    }

    /** One line per payload for the step label and for [payloadReport]. */
    private fun PayloadInfo.line(): String = buildString {
        append(asset).append("  ")
        if (ok) {
            append(formatBytes(bytes))
            if (via != null) append(" via ").append(via)
        } else {
            append("UNREADABLE")
            if (error != null) append(" (").append(error.lineSequence().first()).append(")")
        }
        if (!required) append(" [optional]")
    }

    /** The same audit as one compact, on-screen line of names and sizes. */
    private fun auditLabel(audited: List<PayloadInfo>): String =
        "校验内置载荷：" + audited.joinToString(" · ") { info ->
            val name = info.asset.removePrefix("runtime/")
            if (info.ok) "$name ${formatBytes(info.bytes)}" else "$name 缺失/不可读"
        }

    /**
     * The audit, for a failure that happens *after* it — an unpack that dies
     * half way, or an `open()` that fails during extraction rather than during the
     * audit. Empty when no audit ran in this provisioning attempt.
     */
    private fun payloadReportBlock(): String =
        if (payloadReport.isBlank()) "" else "\npayload audit:\n$payloadReport\n"

    private fun listAssets(dir: String): String =
        runCatching { assets.list(dir)?.sorted()?.joinToString(", ") }
            .getOrNull()
            ?.ifBlank { "(empty)" }
            ?: "(unlistable)"

    private fun describe(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message ?: "(no message)"}"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> {
            val tenths = (bytes / 1048576.0 * 10).roundToInt()
            "${tenths / 10}.${tenths % 10} MiB"
        }
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }

    /**
     * Node ships as `.tar.xz` upstream and Java cannot decode xz, so
     * `tools/fetch-runtime.mjs` re-packs it as gzip at build time. The archive
     * contains a single top-level `node-vX-linux-arm64/` directory.
     */
    private fun extractNode() {
        val staging = File(paths.runtime, "node-stage")
        staging.deleteRecursively()
        extractAsset(NODE_ARCHIVE, staging)
        val top = staging.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw ProvisioningException("node archive had no top-level directory")
        val target = File(paths.rootfs, "opt/node")
        target.parentFile?.mkdirs()
        target.deleteRecursively()
        if (!top.renameTo(target)) {
            top.copyRecursively(target, overwrite = true)
            top.deleteRecursively()
        }
        staging.deleteRecursively()
        // Expose it on PATH the way the guest expects.
        guestSymlink("/usr/local/bin/node", "/opt/node/bin/node")
        guestSymlink("/usr/local/bin/npm", "/opt/node/bin/npm")
        guestSymlink("/usr/local/bin/npx", "/opt/node/bin/npx")
    }

    /**
     * ripgrep and fd are hard dependencies of pi's `grep` and `find` tools.
     *
     * pi will try to download them itself, but it **refuses** when
     * `process.platform === "android"` (`utils/tools-manager.ts`). Inside proot
     * the platform is `linux`, so pi would happily fetch them — but the first
     * launch may not have a network, and a tool that silently fails on a plane
     * is worse than one that ships. Both binaries are static musl builds, so
     * they run in the guest regardless of libc.
     *
     * ## git: installed, and exactly how far it reaches (`docs/known-gaps.md` K2)
     *
     * pi's package manager supports four source kinds — npm, git, an explicit URL
     * and a path — and the git one shells out to a bare `git`:
     * `installGit` runs `git clone <repo> <targetDir>` then `git checkout <ref>`
     * (`packages/coding-agent/src/core/package-manager.ts:1850`, `:1852`), and
     * `updateGit` uses `git fetch` / `rev-parse` / `reset --hard`
     * (`:1932-1956`). Each call is spawned with the process environment
     * (`:2604-2611`), so git has to be on the guest's PATH. [installGit] puts it
     * there, which is also what lets the model itself run `git status`, `git diff`,
     * `git log` and `git commit` instead of only being able to read `.git/HEAD`.
     *
     * ### What works
     *
     *  - **`https://` remotes.** `git clone`, `fetch`, `pull` and `push` against
     *    GitHub and friends: the payload carries `libcurl-gnutls.so.4`, its full
     *    transitive library closure, and a CA bundle at
     *    [CA_BUNDLE][ProotCommand.GUEST_CA_BUNDLE], and [ProotCommand.environment]
     *    points `GIT_SSL_CAINFO`/`SSL_CERT_FILE` at that file — necessary because
     *    the pinned base ships no `/etc/ssl` at all, so there is nothing else for
     *    a TLS peer to be validated against. Node is unaffected either way: it
     *    carries its own CA store.
     *  - **Everything local.** init, status, diff, log, commit, add, branch,
     *    checkout, merge, rebase, stash and the rest of the builtins; one real
     *    binary serves them all, and `git-remote-http` serves the HTTPS transport.
     *
     * ### What still does NOT work — a boundary, not a bug
     *
     *  - **`git@host:path` and `ssh://…`.** These are the other half of the source
     *    grammar pi advertises (`packages/coding-agent/README.md:417-422`, resolved
     *    at `src/utils/git.ts:172-199`). They need an **ssh client plus a key or an
     *    agent**, and none of that is shipped: `git-remote-http` links `libssh.so.4`
     *    — which is libcurl's `sftp://` scheme, not git's ssh transport — and there
     *    is no `ssh` binary, no `~/.ssh`, no agent forwarding and no way to answer a
     *    host-key or passphrase prompt. An ssh source therefore fails with
     *    "cannot run ssh: No such file or directory", which is the honest outcome.
     *    The UI should keep saying so rather than implying ssh works.
     *  - **`git commit` needs an identity** before it will do anything:
     *    `user.name` and `user.email` are unset, and the payload does not invent
     *    them — a commit attributed to a made-up author is worse than a clear
     *    "Please tell me who you are". One `git config --global user.email …` fixes
     *    it.
     *  - **Subcommands Ubuntu ships in *other* packages**: `git svn` (`git-svn`),
     *    `git send-email` (`git-email`), `git gui`/`gitk` (`git-gui`, `gitk`),
     *    `git web--browse`'s browsers, and `git instaweb` (no web server). The
     *    payload is the `git` package alone, so those are absent rather than
     *    broken.
     *
     * ### Why the payload looks the way it does
     *
     * It is a pinned set of Ubuntu `.deb`s in `runtime.lock.json` — git plus the 16
     * libraries the base does not ship — re-assembled into a `.tgz` by
     * `tools/fetch-runtime.mjs` (the Debian payload is an `ar` archive the app's
     * [TarExtractor] cannot read, the same reason Node is re-compressed from
     * `.tar.xz` there). Two consequences worth knowing:
     *
     *  - the closure is **computed, not guessed** — 33 sonames reached, 18 already
     *    in the base, 16 new, 0 unresolved — and the build re-checks it, so an
     *    upstream bump that moves a soname fails the build instead of the phone;
     *  - these libraries **do not receive Ubuntu security updates** the way a
     *    desktop install does. Refreshing them means bumping the pins, which is
     *    also why the UI must not promise more than the pins deliver.
     *
     * Reliability under proot is the one thing this cannot settle from here:
     * proot, its hardlink shim and `--link2symlink` sit under every file git
     * writes, and no device measurement of `git commit`/`gc` has been taken yet.
     * The npm source path, which most packages use, is unaffected by all of this.
     */
    private fun installTool(archive: String, binaryName: String) {
        val staging = File(paths.runtime, "${archive.removeSuffix(PAYLOAD_SUFFIX)}-stage")
        staging.deleteRecursively()
        extractAsset(archive, staging)
        val binary = staging.walkTopDown()
            .filter { it.isFile && it.name == binaryName }
            .maxByOrNull { it.length() }
            ?: throw ProvisioningException("$binaryName not found in $archive")

        // Both copies, deliberately — a tool installed into only one of them is
        // invisible to one of the three things that launch a guest process. Read
        // [PiPaths.agentBinDir] before moving either line: the short version is that
        // the engine and package commands bind the durable dir over the guest's
        // `/root/.pi/agent` and the terminal does not, so `agentBinDir` is the copy
        // with the bind and `rootfsAgentBinDir` is the copy without it.
        for (dir in listOf(paths.agentBinDir(), paths.rootfsAgentBinDir())) {
            dir.mkdirs()
            val dest = File(dir, binaryName)
            binary.copyTo(dest, overwrite = true)
            dest.setExecutable(true, false)
        }
        staging.deleteRecursively()
        publishTool(binaryName)
    }

    /**
     * Unpack the git payload (see [installTool]'s KDoc for what it contains and how
     * far it reaches).
     *
     * One line, because `git.tgz` is assembled at build time as a tree that already
     * has the guest's own shape — `usr/bin/git`, `usr/lib/git-core/…`,
     * `usr/lib/aarch64-linux-gnu/…`, `etc/ssl/certs/ca-certificates.crt`. So unlike
     * rg and fd there is no binary to hunt for and nothing to relocate: extracting
     * it over the rootfs *is* the install. It also needs no `/usr/local/bin` symlink
     * and no second copy in the agent dir, because `/usr/bin` is already on the PATH
     * [ProotCommand.environment] sets and no bind shadows `/usr` — the trap that
     * [PiPaths.agentBinDir] documents for `/root/.pi/agent` simply does not apply
     * here.
     *
     * The CA bundle rides along in the same archive, and
     * [ProotCommand.environment] is what makes git use it.
     */
    private fun installGit() {
        extractAsset(GIT_ARCHIVE, paths.rootfs)
    }

    /**
     * Re-assert the `/usr/local/bin/<tool>` symlink and make sure the file it points at
     * exists in **both** directories a launch path can resolve it through.
     *
     * ## Why this is not just [installTool] again
     *
     * `PiPaths.agentBinDir` explains the two directories. Two separate things can empty
     * one of them after provisioning has already run, and neither re-runs `installTool`,
     * because `ensureReady` returns early while the stamp is current:
     *
     *  - `PiEngineHost.migrateGuestAgentDir` runs **before** provisioning on every boot
     *    (`PiEngineHost.kt:223`, then `:227`) and *moves* the children of the rootfs
     *    agent dir into the durable one. It skips an entry whose target already exists,
     *    so a complete install is left alone — but an install that predates this fix
     *    has no `agentBinDir` yet, and there its `bin/` is renamed away, taking the
     *    rootfs copy with it. That is the terminal's copy, and only this function puts
     *    it back.
     *  - `wipe()` deletes `rootfsAgentBinDir` wholesale on a revision bump. `installTool`
     *    recreates it on that same boot, so this is the belt to that pair of braces.
     *
     * Copying from whichever copy survived keeps the two in step without needing the
     * asset, which is what makes this callable from the early-return path where nothing
     * has been unpacked yet. `overwrite = false` is the point: this repairs a missing
     * file and must never race a fresh extraction into downgrading one.
     */
    private fun ensureToolsVisible() {
        TOOL_BINARIES.forEach { publishTool(it) }
    }

    /**
     * One tool's guest-visible spelling: `/usr/local/bin/<name>` pointing at the guest
     * path `/root/.pi/agent/bin/<name>`, plus the guarantee that both host directories
     * that guest path can mean actually hold the file.
     *
     * The symlink **must** name the guest path, not a host path — see [guestSymlink].
     */
    private fun publishTool(binaryName: String) {
        val copies = listOf(File(paths.agentBinDir(), binaryName), File(paths.rootfsAgentBinDir(), binaryName))
        val source = copies.firstOrNull { it.isFile } ?: return
        copies.forEach { target ->
            if (!target.isFile) {
                target.parentFile?.mkdirs()
                runCatching { source.copyTo(target, overwrite = false) }
            }
            if (target.isFile) target.setExecutable(true, false)
        }
        guestSymlink("$GUEST_LOCAL_BIN/$binaryName", "$GUEST_AGENT_BIN/$binaryName")
    }

    /**
     * Guest configuration that Android cannot supply.
     *
     * DNS is the important one: glibc inside proot cannot see Android's
     * per-network resolver, so without a static `resolv.conf` every name lookup
     * fails while the network is otherwise fine — the single most confusing
     * failure mode of a proot'd userland (docs/pi-android-app-design.md §13).
     */
    private fun configureGuest() {
        val etc = File(paths.rootfs, "etc").also { it.mkdirs() }
        File(etc, "resolv.conf").writeText(
            """
            # Written by pi-android. glibc in a proot cannot reach Android's
            # per-network resolver, so nameservers are pinned here.
            nameserver 223.5.5.5
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            """.trimIndent() + "\n",
        )
        File(etc, "hosts").writeText(
            """
            127.0.0.1   localhost
            ::1         localhost ip6-localhost
            """.trimIndent() + "\n",
        )

        // pi's home inside the guest: the same layout a desktop install has, so
        // settings, skills, extensions and themes are interchangeable.
        File(paths.rootfs, "root/.pi/agent").mkdirs()
        File(paths.rootfs, "workspace").mkdirs()
    }

    private fun extractEngine() {
        // Optional: the engine may be installed on first run instead of shipped.
        val target = File(paths.rootfs, "opt/pi")
        if (!assetExists("runtime/$ENGINE_ARCHIVE")) {
            target.mkdirs()
            return
        }
        extractAsset(ENGINE_ARCHIVE, target)
        // Convenience launcher: `pi` on PATH inside the guest.
        val cli = File(target, "node_modules/@earendil-works/pi-coding-agent/dist/cli.js")
        if (cli.isFile) {
            val bin = File(paths.rootfs, "opt/pi/bin").also { it.mkdirs() }
            val wrapper = File(bin, "pi")
            wrapper.writeText(
                "#!/bin/bash\nexec /opt/node/bin/node ${cli.path} \"\$@\"\n",
            )
            wrapper.setExecutable(true, false)
            guestSymlink("/usr/local/bin/pi", "/opt/pi/bin/pi")
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Create a symlink **inside the rootfs**, pointing at a **guest** path.
     *
     * The distinction is not cosmetic. proot translates paths on the way in, so a
     * link whose target is a host path (`/data/user/0/...`) is meaningless to the
     * guest: nothing resolves it, and the failure looks like a missing binary.
     * Only symlinks that Android itself follows — the `libtalloc.so.2` alias in
     * [PiPaths.lib], which lives outside the rootfs — may name host paths.
     */
    private fun guestSymlink(guestLink: String, guestTarget: String) {
        val link = File(paths.rootfs, guestLink.removePrefix("/"))
        link.parentFile?.mkdirs()
        if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Path.of(guestTarget)) }
    }

    private fun assetExists(name: String): Boolean = runCatching {
        assets.open(name).close()
        true
    }.getOrDefault(false)

    private fun stampFile() = paths.stampFile()

    private fun isStampCurrent(revision: String): Boolean {
        val stamp = stampFile()
        return stamp.isFile && stamp.readText().trim() == revision
    }

    private fun writeStamp(revision: String) {
        stampFile().writeText(revision + "\n")
    }

    companion object {
        /**
         * The fallback revision, used only when [packagedRevision] cannot read the
         * assembler's digest.
         *
         * This used to be the whole mechanism, and the comment above it said so: the
         * number was bumped **by hand** and nothing verified it. That arrangement
         * fails in the direction that costs the most. Change a payload and forget to
         * bump it, and every device keeps the tree it already unpacked — the APK
         * installs, the app looks fine, and it is running the previous Node and the
         * previous pi. The build produced nothing, and nothing on the device
         * disagrees with anything else, so nobody finds out. The reverse mistake
         * (bumping it when nothing changed) deletes everything the user installed
         * inside the guest for no reason at all.
         *
         * So the number is derived now: [packagedRevision] reads a digest of the
         * payload bytes that `tools/fetch-runtime.mjs` writes at build time, and a
         * device that unpacked different bytes re-unpacks on its own. Nothing here
         * needs a human to remember anything. Editing this constant therefore has no
         * effect on a real build — which is the point — and it exists for the one
         * build with no assembler behind it (a bare `:app:assembleRelease` over a
         * checkout where `fetch-runtime.mjs` never ran), where the payloads are
         * absent too and provisioning fails on the payload audit before this value is
         * ever compared against a stamp.
         *
         * ## What a revision change still costs
         *
         * `wipe()` deletes the whole runtime tree, which holds the extracted guest —
         * so **everything the user installed inside it goes**: apt and pip packages,
         * `npm -g` packages, anything dropped into `/usr/local/bin`, edits to
         * `/etc/hosts`, and all of `/root` except the bind-mounted `.pi/agent`. That
         * is now automatic rather than forgettable, which makes it *more* important to
         * know, not less: a payload change is a decision to delete the user's guest
         * environment. Session history, credentials, settings, extensions and the
         * workspace are outside the wiped tree and survive. See
         * `docs/known-gaps.md`; keeping user-installed packages across a wipe is a
         * provisioning-path change nobody has made, deliberately.
         */
        const val RUNTIME_REVISION = "2"

        /**
         * Where [packagedRevision] reads the digest from.
         *
         * Beside `assets/runtime/`, not inside it: CI asserts that every entry under
         * that directory is one of the payloads, byte for byte, and a manifest is not
         * a payload. `tools/fetch-runtime.mjs` writes this path; the APK step in
         * `.github/workflows/ci.yml` asserts it survived packaging with a 16-character
         * value, because a rename on one side only would leave every device on the
         * fallback and silently restore the bug this replaced.
         */
        const val REVISION_ASSET = "runtime-revision.txt"

        /** The digest length `tools/fetch-runtime.mjs` writes, in hex characters. */
        private const val REVISION_LENGTH = 16

        /**
         * The revision the packaged payloads carry — a digest of their bytes, or
         * [RUNTIME_REVISION] when the asset is not there.
         *
         * Read from the APK rather than compiled in so it cannot go stale. The read is
         * a few dozen bytes, once per boot and once per restart, which is why it is
         * done here instead of being cached in a field: there is nothing to gain and a
         * stale cache is one more way to miss a payload change.
         */
        fun packagedRevision(assets: AssetManager): String =
            runCatching {
                assets.open(REVISION_ASSET, AssetManager.ACCESS_BUFFER).use {
                    it.readBytes().decodeToString().trim()
                }
            }.getOrNull()
                ?.takeIf { value ->
                    value.length == REVISION_LENGTH && value.all { it.isDigit() || it in 'a'..'f' }
                }
                ?: RUNTIME_REVISION

        /**
         * The suffix every payload archive in `assets/runtime/` carries.
         *
         * **It must not end in `.gz`.** The Android Gradle Plugin gunzips an asset
         * whose *file extension is* `gz` while it merges assets —
         * `com.android.ide.common.resources.AssetItem` decides it with
         * `Files.getFileExtension(name).toLowerCase(Locale.US).equals("gz")` and then
         * renames with `Files.getNameWithoutExtension`, which removes only the final
         * `.gz`. So the assembler writing `ubuntu-base.tar.gz` (28.5 MiB) put
         * `ubuntu-base.tar` (106 MB) in the APK, this class still asked for
         * `runtime/ubuntu-base.tar.gz`, and `AssetManager.open()` answered with its
         * bare-path `FileNotFoundException` — the runtime never provisioned once, on
         * any build, and the device's own report said so:
         *
         *   packaged asset unreadable: runtime/ubuntu-base.tar.gz
         *   assets/runtime/ contains: fd.tar, node.tar, pi-engine.tar, ripgrep.tar, ubuntu-base.tar
         *
         * It is **not** AAPT2: aapt2 2.20-14304508, run directly against a directory
         * laid out like this one, ships `ubuntu-base.tar.gz` and `pi-engine.tgz`
         * under their own names. The rename is one stage earlier and therefore
         * outlives any change to the aapt2 command line. `.tgz` is the same gzip
         * bytes under an extension AGP leaves alone
         * (`Files.getFileExtension("ubuntu-base.tgz")` is `tgz`).
         *
         * The matching half of the fix is `androidResources { noCompress }` in
         * app/build.gradle.kts, which must name this suffix — and the full account,
         * including the commands that established it, is on `PAYLOAD_SUFFIX` in
         * tools/fetch-runtime.mjs.
         *
         * Nothing is recompressed by this: [TarExtractor.extractGzip] reads all six
         * payloads exactly as before.
         */
        private const val PAYLOAD_SUFFIX = ".tgz"

        private const val UBUNTU_BASE = "ubuntu-base$PAYLOAD_SUFFIX"
        private const val NODE_ARCHIVE = "node$PAYLOAD_SUFFIX"
        private const val ENGINE_ARCHIVE = "pi-engine$PAYLOAD_SUFFIX"
        private const val RIPGREP_ARCHIVE = "ripgrep$PAYLOAD_SUFFIX"
        private const val FD_ARCHIVE = "fd$PAYLOAD_SUFFIX"
        private const val GIT_ARCHIVE = "git$PAYLOAD_SUFFIX"

        /**
         * The one guest directory a launcher that does **not** bind the agent dir
         * resolves `/root/.pi/agent` through — see [PiPaths.agentBinDir].
         */
        private const val GUEST_AGENT_BIN = "/root/.pi/agent/bin"

        /** The guest directory `ProotCommand.environment` puts on PATH (PiRuntime.kt). */
        private const val GUEST_LOCAL_BIN = "/usr/local/bin"

        /**
         * The tools [publishTool] keeps visible, named where both the install step and
         * the repair step ([ensureToolsVisible]) read the same list, so a third tool
         * cannot be added to one and forgotten by the other.
         *
         * git is deliberately **not** here. It needs a whole `/usr/lib/git-core` tree
         * and its libraries, and none of that belongs under `/root/.pi/agent`: it goes
         * to the ordinary Debian locations in the rootfs (`/usr/bin/git`,
         * `/usr/lib/git-core`), which are on PATH and are not shadowed by any bind — so
         * it is visible to all three launch paths with no agent-dir involvement at all.
         * See [installGit].
         */
        private val TOOL_BINARIES = listOf("rg", "fd")

        /**
         * Every payload archive `tools/fetch-runtime.mjs` writes into
         * `app/src/main/assets/runtime/`, in the order the steps consume them.
         *
         * `required = false` only for the engine, mirroring [extractEngine]: the
         * audit must report it, but a package without it is still a package this
         * class can provision from, so it is not a reason to refuse to start.
         *
         * The list is the single place the six names are written down; the
         * companion constants above are what the extracting steps themselves use.
         */
        private val PAYLOADS = listOf(
            Payload(UBUNTU_BASE, required = true),
            Payload(NODE_ARCHIVE, required = true),
            Payload(RIPGREP_ARCHIVE, required = true),
            Payload(FD_ARCHIVE, required = true),
            Payload(GIT_ARCHIVE, required = true),
            Payload(ENGINE_ARCHIVE, required = false),
        )

        /**
         * Why the payloads exist and what their absence means. Appended to every
         * payload failure so the on-screen message carries the explanation with
         * it, instead of requiring a lookup in this file.
         *
         * [PAYLOADS] is the load-bearing list, and the suffix list in
         * app/build.gradle.kts (`tgz`, plus `xz`/`tar` for a future repack) is what
         * keeps all six openable: a compressed asset is the one
         * `AssetManager.openFd` cannot open. That list must name the suffix the
         * assets actually use — see [PAYLOAD_SUFFIX] for what happens otherwise.
         */
        private const val PAYLOAD_HINT =
            "The payload archives are generated at build time by tools/fetch-runtime.mjs " +
                "(app/src/main/assets/runtime/ is not in git); an APK built without that step has " +
                "assets/dexopt and nothing else."
    }
}
