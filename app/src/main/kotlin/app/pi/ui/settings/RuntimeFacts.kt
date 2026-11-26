package app.pi.ui.settings

import android.system.Os
import android.system.OsConstants
import app.pi.engine.PiEngineSession
import app.pi.i18n.tr
import app.pi.runtime.PiPaths
import app.pi.runtime.RuntimeUsage
import app.pi.service.PiEngineService
import org.json.JSONObject
import java.io.File

/**
 * Pure filesystem/service snapshot of the Linux runtime environment.
 *
 * Read off the main thread (`Dispatchers.IO`) by [RuntimeEngineScreen] and
 * [DiagnosticsReport]. Returns `null` for any value that cannot be read instead
 * of inventing defaults.
 */
class RuntimeFacts(
    private val paths: PiPaths,
) {

    /**
     * Sub-5ms metadata snapshot that performs zero recursive filesystem walks.
     * Used by [RuntimeEngineScreen] to populate the Hero card instantaneously while
     * [StorageBreakdown] computes asynchronously.
     */
    data class FastSnapshot(
        val piVersion: String?,
        val nodeVersion: String?,
        val engineStartupMs: Long?,
        val wakeLockHeld: Boolean,
        val serviceRunning: Boolean,
        val runtimeUnpacked: Boolean,
        val stampRevision: String?,
    )

    /**
     * Single-pass, symlink-safe, hardlink-deduplicated disk breakdown across the runtime tree,
     * durable user data, and safely cleanable caches.
     */
    data class StorageBreakdown(
        val totalBytes: Long,
        /** Linux OS rootfs excluding Node/Pi, durable user data, and caches. */
        val linuxSystemBytes: Long,
        /** Bundled Node.js (`/opt/node`) + Pi SDK & sdk-host (`/opt/pi`). */
        val engineRuntimeBytes: Long,
        /** Durable user data preserved across rebuilds (`/root/.pi/agent`, `/root/projects`, `persist`). */
        val userDataBytes: Long,
        /** Safely cleanable caches (V8 compile cache, apt/npm caches, tmp scratch, APK update cache). */
        val cleanableCacheBytes: Long,
        val compileCacheBytes: Long,
        val pkgCacheBytes: Long,
        val scratchCacheBytes: Long,
        val deviceAvailableBytes: Long,
        val deviceTotalBytes: Long,
        val unmeasured: Int,
        val unreadableDirectories: Int,
    ) {
        val complete: Boolean get() = unmeasured == 0 && unreadableDirectories == 0
    }

    data class CacheCleanResult(
        val freedBytes: Long,
        val removedConfigs: Int,
        val removedProotTmp: Int,
    )

    data class Snapshot(
        /** `version` from the shipped pi manifest, or null when unreadable. */
        val piVersion: String?,
        /** `v<major>.<minor>.<patch>` from the shipped node payload, or null. */
        val nodeVersion: String?,
        /** Preformatted allocated disk usage of `<files>/pi/runtime`, or null. */
        val runtimeUsage: String?,
        /** How long the last engine took to answer its first command, in ms. */
        val engineStartupMs: Long?,
        /** Whether the engine foreground service currently holds a wake lock. */
        val wakeLockHeld: Boolean,
        /** Whether the engine foreground service is alive. */
        val serviceRunning: Boolean,
        /** Whether `<files>/pi/runtime/rootfs` exists. */
        val runtimeUnpacked: Boolean,
        /** Unpacked runtime stamp revision, or null. */
        val stampRevision: String?,
        /** Formatted size of `<rootfs>/tmp/pi-node-compile-cache`, or null if absent/empty. */
        val compileCacheUsage: String?,
    )

    fun readFast(): FastSnapshot = FastSnapshot(
        piVersion = piVersion(),
        nodeVersion = nodeVersion(),
        engineStartupMs = PiEngineSession.lastServingMs,
        wakeLockHeld = PiEngineService.isWakeLockHeld(),
        serviceRunning = PiEngineService.isRunning(),
        runtimeUnpacked = paths.rootfs.isDirectory,
        stampRevision = paths.stampRevision(),
    )

    fun read(): Snapshot {
        val fast = readFast()
        val breakdown = runCatching { measureStorage() }.getOrNull()
        val usageStr = breakdown?.takeIf { fast.runtimeUnpacked || it.totalBytes > 0L }?.let { b ->
            val caveats = buildList {
                if (b.unmeasured > 0) add(tr("${b.unmeasured} 项未计入", "${b.unmeasured} unmeasured"))
                if (b.unreadableDirectories > 0) {
                    add(tr("${b.unreadableDirectories} 个目录不可读", "${b.unreadableDirectories} unreadable dirs"))
                }
            }
            val suffix = if (caveats.isEmpty()) "" else tr("（${caveats.joinToString("；")}）", " (${caveats.joinToString("; ")})")
            formatBytes(b.totalBytes) + suffix
        }
        val compileStr = breakdown?.compileCacheBytes?.takeIf { it > 0L }?.let(::formatBytes)
        return Snapshot(
            piVersion = fast.piVersion,
            nodeVersion = fast.nodeVersion,
            runtimeUsage = usageStr,
            engineStartupMs = fast.engineStartupMs,
            wakeLockHeld = fast.wakeLockHeld,
            serviceRunning = fast.serviceRunning,
            runtimeUnpacked = fast.runtimeUnpacked,
            stampRevision = fast.stampRevision,
            compileCacheUsage = compileStr,
        )
    }

    /**
     * Measure disk allocation in a single hardlink-deduplicated pass across `runtime/`, `persist/`,
     * and optional `<appCacheDir>/updates`. Never follows symlinks.
     */
    fun measureStorage(appCacheDir: File? = null): StorageBreakdown {
        val runtimeRoot = paths.runtime
        val rootfs = paths.rootfs
        val persist = paths.persist
        val updatesCache = appCacheDir?.let { File(it, UPDATES_CACHE_DIR) }

        val rootfsPrefix = normalizePrefix(rootfs)
        val runtimePrefix = normalizePrefix(runtimeRoot)
        val persistPrefix = normalizePrefix(persist)
        val updatesPrefix = updatesCache?.let(::normalizePrefix)

        val roots = buildList {
            if (runtimeRoot.isDirectory) add(runtimeRoot)
            if (persist.isDirectory) add(persist)
            if (updatesCache != null && updatesCache.isDirectory) add(updatesCache)
        }

        val measured = RuntimeUsage.measureCategorized(
            roots = roots,
            stat = ::allocatedStat,
            classify = { file ->
                val p = file.path
                when {
                    updatesPrefix != null && matchesPrefix(p, updatesPrefix) -> CAT_SCRATCH_CACHE
                    matchesPrefix(p, persistPrefix) -> CAT_USER_DATA
                    matchesPrefix(p, rootfsPrefix) -> classifyRootfsRelative(relativeUnder(p, rootfsPrefix))
                    matchesPrefix(p, runtimePrefix) -> classifyRuntimeRelative(relativeUnder(p, runtimePrefix))
                    else -> CAT_LINUX_SYSTEM
                }
            },
        )

        val linuxBytes = measured.byCategory[CAT_LINUX_SYSTEM] ?: 0L
        val engineBytes = measured.byCategory[CAT_ENGINE] ?: 0L
        val userBytes = measured.byCategory[CAT_USER_DATA] ?: 0L
        val compileBytes = measured.byCategory[CAT_COMPILE_CACHE] ?: 0L
        val pkgCacheBytes = measured.byCategory[CAT_PKG_CACHE] ?: 0L
        val scratchCacheBytes = measured.byCategory[CAT_SCRATCH_CACHE] ?: 0L
        val cleanableBytes = compileBytes + pkgCacheBytes + scratchCacheBytes

        val spaceProbe = when {
            paths.home.exists() -> paths.home
            paths.home.parentFile?.exists() == true -> paths.home.parentFile!!
            else -> runtimeRoot
        }
        return StorageBreakdown(
            totalBytes = measured.totalBytes,
            linuxSystemBytes = linuxBytes,
            engineRuntimeBytes = engineBytes,
            userDataBytes = userBytes,
            cleanableCacheBytes = cleanableBytes,
            compileCacheBytes = compileBytes,
            pkgCacheBytes = pkgCacheBytes,
            scratchCacheBytes = scratchCacheBytes,
            deviceAvailableBytes = runCatching { spaceProbe.usableSpace }.getOrDefault(0L),
            deviceTotalBytes = runCatching { spaceProbe.totalSpace }.getOrDefault(0L),
            unmeasured = measured.unmeasured,
            unreadableDirectories = measured.unreadableDirectories,
        )
    }

    /**
     * Safely remove volatile caches without following symlinks and without touching
     * user data (`/root/.pi/agent`, `/root/projects`, `persist`) or installed packages.
     */
    fun clearSafeCaches(
        appCacheDir: File? = null,
        sweepProrootConfigs: () -> Int = { 0 },
    ): CacheCleanResult {
        var freed = 0L

        fun removeTreeNoFollow(dir: File) {
            if (!dir.exists() || isSymlink(dir)) return
            freed += RuntimeUsage.measure(dir, ::allocatedStat).bytes
            deleteNoFollow(dir)
        }

        // 1. V8 compile cache (`<rootfs>/tmp/pi-node-compile-cache`)
        removeTreeNoFollow(File(paths.rootfs, COMPILE_CACHE_REL))

        // 2. apt downloaded `.deb` archives only (leave directory & lock structure intact)
        val aptArchives = File(paths.rootfs, APT_ARCHIVES_REL)
        if (aptArchives.isDirectory && !isSymlink(aptArchives)) {
            aptArchives.listFiles().orEmpty().forEach { entry ->
                if (entry.name.endsWith(".deb") && !isSymlink(entry)) {
                    freed += allocatedStat(entry.path)?.allocatedBytes ?: 0L
                    runCatching { entry.delete() }
                }
            }
        }

        // 3. npm HTTP download cache (`<rootfs>/root/.npm/_cacache`) & pip/generic cache (`<rootfs>/root/.cache`)
        removeTreeNoFollow(File(paths.rootfs, NPM_CACHE_REL))
        removeTreeNoFollow(File(paths.rootfs, ROOT_CACHE_REL))

        // 4. App downloaded update APKs (`<cacheDir>/updates`)
        appCacheDir?.let { removeTreeNoFollow(File(it, UPDATES_CACHE_DIR)) }

        // 5. Stale proot scratch dirs, stale proroot configs, and runtime probe verdict caches
        val prootTmpBefore = if (File(paths.runtime, "tmp").isDirectory) {
            RuntimeUsage.measure(File(paths.runtime, "tmp"), ::allocatedStat).bytes
        } else {
            0L
        }
        val removedProotTmp = runCatching { paths.sweepStaleProotTmp() }.getOrDefault(0)
        if (removedProotTmp > 0 && File(paths.runtime, "tmp").isDirectory) {
            val prootTmpAfter = RuntimeUsage.measure(File(paths.runtime, "tmp"), ::allocatedStat).bytes
            freed += (prootTmpBefore - prootTmpAfter).coerceAtLeast(0L)
        }

        val removedConfigs = runCatching { sweepProrootConfigs() }.getOrDefault(0)
        runCatching { paths.clearProrootProbeCache() }
        runCatching { paths.clearTawcrootProbeCache() }

        return CacheCleanResult(
            freedBytes = freed,
            removedConfigs = removedConfigs,
            removedProotTmp = removedProotTmp,
        )
    }

    private fun classifyRootfsRelative(rel: String): String = when {
        rel.isEmpty() -> CAT_LINUX_SYSTEM
        rel == "opt/node" || rel.startsWith("opt/node/") ||
            rel == "opt/pi" || rel.startsWith("opt/pi/") -> CAT_ENGINE

        rel == "root/.pi" || rel.startsWith("root/.pi/") ||
            rel == "root/projects" || rel.startsWith("root/projects/") ||
            rel == "workspace/pi/workspaces" || rel.startsWith("workspace/pi/workspaces/") -> CAT_USER_DATA

        rel == COMPILE_CACHE_REL || rel.startsWith("$COMPILE_CACHE_REL/") -> CAT_COMPILE_CACHE

        rel == NPM_CACHE_REL || rel.startsWith("$NPM_CACHE_REL/") ||
            rel == ROOT_CACHE_REL || rel.startsWith("$ROOT_CACHE_REL/") ||
            (rel.startsWith("$APT_ARCHIVES_REL/") && rel.endsWith(".deb")) -> CAT_PKG_CACHE

        else -> CAT_LINUX_SYSTEM
    }

    private fun classifyRuntimeRelative(rel: String): String = when {
        rel == "tmp" || rel.startsWith("tmp/") ||
            rel == "proroot-tmp" || rel.startsWith("proroot-tmp/") ||
            rel == ".proroot-probe" || rel == ".tawcroot-probe" -> CAT_SCRATCH_CACHE
        else -> CAT_LINUX_SYSTEM
    }

    private fun normalizePrefix(file: File): String = file.path.trimEnd(File.separatorChar)

    private fun matchesPrefix(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith(prefix + File.separatorChar)

    private fun relativeUnder(path: String, prefix: String): String =
        if (path.length <= prefix.length) "" else path.substring(prefix.length + 1).replace(File.separatorChar, '/')

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun deleteNoFollow(target: File) {
        runCatching {
            val root = target.toPath()
            if (!java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            java.nio.file.Files.walkFileTree(
                root,
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: java.nio.file.attribute.BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.deleteIfExists(file) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        exc: java.io.IOException?,
                    ): java.nio.file.FileVisitResult {
                        runCatching { java.nio.file.Files.deleteIfExists(dir) }
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    private fun piVersion(): String? = runCatching {
        val manifest = File(paths.rootfs, "$PI_PACKAGE_DIR/package.json")
        if (!manifest.isFile) return null
        JSONObject(manifest.readText()).optString("version").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun nodeVersion(): String? = runCatching {
        val header = File(paths.rootfs, "$NODE_DIR/include/node/node_version.h")
        if (!header.isFile) return null
        val text = header.readText()
        val major = NODE_MAJOR.find(text)?.groupValues?.get(1) ?: return null
        val minor = NODE_MINOR.find(text)?.groupValues?.get(1) ?: return null
        val patch = NODE_PATCH.find(text)?.groupValues?.get(1) ?: return null
        "v$major.$minor.$patch"
    }.getOrNull()

    private fun allocatedStat(path: String): RuntimeUsage.Stat? = runCatching {
        val stat = Os.lstat(path)
        RuntimeUsage.Stat(
            allocatedBytes = stat.st_blocks * BYTES_PER_BLOCK,
            isDirectory = OsConstants.S_ISDIR(stat.st_mode),
            isRegularFile = OsConstants.S_ISREG(stat.st_mode),
            isSymlink = OsConstants.S_ISLNK(stat.st_mode),
            device = stat.st_dev,
            inode = stat.st_ino,
            linkCount = stat.st_nlink,
        )
    }.getOrNull()

    companion object {
        const val COMPILE_CACHE_REL = "tmp/pi-node-compile-cache"
        private const val APT_ARCHIVES_REL = "var/cache/apt/archives"
        private const val NPM_CACHE_REL = "root/.npm/_cacache"
        private const val ROOT_CACHE_REL = "root/.cache"
        private const val UPDATES_CACHE_DIR = "updates"
        private const val PI_PACKAGE_DIR = "opt/pi/node_modules/@earendil-works/pi-coding-agent"
        private const val NODE_DIR = "opt/node"
        private const val BYTES_PER_BLOCK = 512L

        private const val CAT_LINUX_SYSTEM = "linux"
        private const val CAT_ENGINE = "engine"
        private const val CAT_USER_DATA = "user"
        private const val CAT_COMPILE_CACHE = "compile_cache"
        private const val CAT_PKG_CACHE = "pkg_cache"
        private const val CAT_SCRATCH_CACHE = "scratch_cache"

        private val NODE_MAJOR = Regex("""#define\s+NODE_MAJOR_VERSION\s+(\d+)""")
        private val NODE_MINOR = Regex("""#define\s+NODE_MINOR_VERSION\s+(\d+)""")
        private val NODE_PATCH = Regex("""#define\s+NODE_PATCH_VERSION\s+(\d+)""")

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024L -> "${bytes / 1024L} KB"
            else -> "$bytes B"
        }
    }
}
