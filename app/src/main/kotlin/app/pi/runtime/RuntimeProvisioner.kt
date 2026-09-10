package app.pi.runtime

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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

                next(); wipe()
                index++

                next(); extractAsset(UBUNTU_BASE, paths.rootfs)
                index++

                next(); extractNode()
                index++

                next(); installTool("ripgrep", "rg"); installTool("fd", "fd")
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
        try {
            assets.open(archive).use { stream ->
                TarExtractor(into).extractGzip(stream)
            }
        } catch (e: IOException) {
            throw ProvisioningException("failed to unpack $assetName: ${e.message}", e)
        }
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
     */
    private fun installTool(archiveBase: String, binaryName: String) {
        val staging = File(paths.runtime, "$archiveBase-stage")
        staging.deleteRecursively()
        extractAsset("$archiveBase.tar.gz", staging)
        val binary = staging.walkTopDown()
            .filter { it.isFile && it.name == binaryName }
            .maxByOrNull { it.length() }
            ?: throw ProvisioningException("$binaryName not found in $archiveBase archive")

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
         * Bump when the packaged payload changes so existing installs re-unpack.
         * The tool that assembles the runtime writes the same value into the
         * sidecar manifest.
         */
        const val RUNTIME_REVISION = "1"

        private const val UBUNTU_BASE = "ubuntu-base.tar.gz"
        private const val NODE_ARCHIVE = "node.tar.gz"
        private const val ENGINE_ARCHIVE = "pi-engine.tar.gz"
    }
}
