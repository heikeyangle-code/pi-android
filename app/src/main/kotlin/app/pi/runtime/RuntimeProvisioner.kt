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
                    return@runCatching
                }
                val steps = buildList {
                    add("校验内置载荷")
                    add("准备存储")
                    add("解压 Ubuntu 用户态")
                    add("解压 Node 运行时")
                    add("安装 rg / fd")
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
        // "failed to unpack ubuntu-base.tar.gz: runtime/ubuntu-base.tar.gz". That
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
     * A device reported `failed to unpack ubuntu-base.tar.gz: runtime/ubuntu-base.tar.gz`
     * on an APK that is 127,195,427 bytes — and the five archives alone account for
     * ~115 MB of that, so the payload is demonstrably in the package. Two things were
     * wrong with the old code as a diagnostic: it used one access mode, and when that
     * mode failed it said nothing about the state of the APK, so a missing asset, a
     * differently-placed asset and a broken install were indistinguishable.
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
     * on it a moment later, after the wipe). `pi-engine.tar.gz` is reported but not
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
     * ## What this class deliberately does NOT install: git (`docs/known-gaps.md` K2)
     *
     * pi's package manager supports four source kinds — npm, git, an explicit URL
     * and a path — and the git one shells out to a `git` binary:
     * `installGit` runs `git clone <repo> <targetDir>` then `git checkout <ref>`
     * (`packages/coding-agent/src/core/package-manager.ts:1850`, `:1852`), and
     * `updateGit` uses `git fetch` / `rev-parse` / `reset --hard`
     * (`:1932-1956`). Each call is a bare command name spawned with the process
     * environment (`:2604-2611`), so git must be **on PATH inside the guest** —
     * and it is not there:
     *
     *  - `runtime.lock.json` has no git artifact (proot, libtalloc,
     *    libandroidShmem, ubuntuBase, node, ripgrep, fd);
     *  - the pinned `ubuntu-base-24.04.3-base-arm64.tar.gz` contains no `git`
     *    and, checked by listing the cached tarball, no `/etc/ssl` and no CA
     *    bundle at all — nothing for git's HTTPS transport to validate against
     *    (Node is unaffected because it carries its own CA store);
     *  - this class only links node/npm/npx ([extractNode]) and rg/fd
     *    ([installTool]).
     *
     * `git:` is therefore **unavailable**, and the app must not offer it. Shipping
     * git was weighed and rejected. The cost is measured, not guessed, in an
     * Ubuntu 24.04.3 aarch64 userland matching the pinned base: `git` plus
     * `/usr/lib/git-core` gzip to **7.3 MiB**, and of the 31 shared libraries
     * `git-remote-http` resolves, the 15 the base does not already ship (the
     * GnuTLS flavour of libcurl, libnghttp2, libssh, libldap, libkrb5, libsasl2,
     * libbrotlidec, …) add **1.6 MiB** gzipped — call it **9 MiB** of extra
     * compressed payload, roughly 20 MiB unpacked, plus a CA bundle. That alone
     * is not the reason; it is affordable next to the engine. The reasons are:
     *
     *  - the whole thing is **unverifiable without a device**. There is no emulator
     *    and no Gradle here, so a git payload would ship as an untested path on
     *    top of an already unverified runtime, and a half-working install path is
     *    worse app behaviour than an honestly absent one;
     *  - the `git@host:path` and `ssh://` forms pi also advertises
     *    (`packages/coding-agent/README.md:417-422`, resolved to those URLs at
     *    `src/utils/git.ts:172-199`) would still need an ssh client and
     *    credentials, so even a working git would answer only part of the source
     *    grammar the UI would then be advertising;
     *  - it is a **hand-built partial Debian userland**: the payload is a pinned
     *    set of `.deb`s (git plus its library closure) that never receives the
     *    Ubuntu security updates libgnutls/libcurl/libssh get, unlike the current
     *    self-contained artifacts (static musl rg/fd, Node's official build);
     *  - whether git is *reliable* under proot is **uncertain** — no measurement
     *    was possible here (proot, hardlink shims and `--link2symlink` all sit
     *    under every file git writes). The npm path, which most packages use, is
     *    unaffected by all of this.
     *
     * If that decision is ever reversed, the shape is: a git artifact in
     * `runtime.lock.json`, a build-time repack to `.tar.gz` in
     * `tools/fetch-runtime.mjs` (the Debian payload is an `ar` archive the app's
     * [TarExtractor] cannot read — the same reason Node is re-compressed from
     * `.tar.xz` there today), an unpack plus a `/usr/local/bin/git` symlink in
     * [installTool]'s shape (that is the path `ProotCommand.environment` puts on
     * PATH), and a CA bundle.
     */
    private fun installTool(archive: String, binaryName: String) {
        val staging = File(paths.runtime, "${archive.removeSuffix(".tar.gz")}-stage")
        staging.deleteRecursively()
        extractAsset(archive, staging)
        val binary = staging.walkTopDown()
            .filter { it.isFile && it.name == binaryName }
            .maxByOrNull { it.length() }
            ?: throw ProvisioningException("$binaryName not found in $archive")

        val dir = File(paths.rootfs, "root/.pi/agent/bin")
        dir.mkdirs()
        val dest = File(dir, binaryName)
        binary.copyTo(dest, overwrite = true)
        dest.setExecutable(true, false)
        guestSymlink("/usr/local/bin/$binaryName", "/root/.pi/agent/bin/$binaryName")
        staging.deleteRecursively()
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
         * Bump **by hand** when the packaged payload changes, so an existing
         * install re-unpacks. The value is written to `<runtime>/.stamp`
         * ([writeStamp]) and compared on every boot ([isStampCurrent]) and on
         * every restart (`PiEngineHost.stampMatches`).
         *
         * **Nothing verifies this number.** `tools/fetch-runtime.mjs` writes no
         * revision, sidecar or manifest of any kind — an earlier version of this
         * comment claimed it did, and that claim was simply wrong (checked against
         * the whole script). So a changed pin in `runtime.lock.json`, or a bumped
         * `PI_VERSION`, leaves the app happily using the tree it extracted from the
         * *previous* payload: the second install looks finished and silently runs
         * the old engine. If that ever bites, the fix is a payload-derived revision
         * (a digest of the assembled archives, published as an asset and read
         * before the stamp check) — a design change, not a one-line edit, because
         * `PiEngineHost.stampMatches` compares the stamp against this constant.
         */
        const val RUNTIME_REVISION = "1"

        private const val UBUNTU_BASE = "ubuntu-base.tar.gz"
        private const val NODE_ARCHIVE = "node.tar.gz"
        private const val ENGINE_ARCHIVE = "pi-engine.tar.gz"
        private const val RIPGREP_ARCHIVE = "ripgrep.tar.gz"
        private const val FD_ARCHIVE = "fd.tar.gz"

        /**
         * Every payload archive `tools/fetch-runtime.mjs` writes into
         * `app/src/main/assets/runtime/`, in the order the steps consume them.
         *
         * `required = false` only for the engine, mirroring [extractEngine]: the
         * audit must report it, but a package without it is still a package this
         * class can provision from, so it is not a reason to refuse to start.
         *
         * The list is the single place the five names are written down; the
         * companion constants above are what the extracting steps themselves use.
         */
        private val PAYLOADS = listOf(
            Payload(UBUNTU_BASE, required = true),
            Payload(NODE_ARCHIVE, required = true),
            Payload(RIPGREP_ARCHIVE, required = true),
            Payload(FD_ARCHIVE, required = true),
            Payload(ENGINE_ARCHIVE, required = false),
        )

        /**
         * Why the payloads exist and what their absence means. Appended to every
         * payload failure so the on-screen message carries the explanation with
         * it, instead of requiring a lookup in this file.
         *
         * [PAYLOADS] is the load-bearing list, and the suffix list in
         * app/build.gradle.kts (`gz`, plus `xz`/`tar` for a future repack) is what
         * keeps all five openable: a compressed asset is the one
         * `AssetManager.openFd` cannot open.
         */
        private const val PAYLOAD_HINT =
            "The five payload archives are generated at build time by tools/fetch-runtime.mjs " +
                "(app/src/main/assets/runtime/ is not in git); an APK built without that step has " +
                "assets/dexopt and nothing else."
    }
}
