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

    fun read(): Snapshot = Snapshot(
        piVersion = piVersion(),
        nodeVersion = nodeVersion(),
        runtimeUsage = runtimeUsage(),
        engineStartupMs = PiEngineSession.lastServingMs,
        wakeLockHeld = PiEngineService.isWakeLockHeld(),
        serviceRunning = PiEngineService.isRunning(),
        runtimeUnpacked = paths.rootfs.isDirectory,
        stampRevision = paths.stampRevision(),
        compileCacheUsage = compileCacheUsage(),
    )

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

    private fun runtimeUsage(): String? = runCatching {
        val root = paths.runtime
        if (!root.isDirectory) return null
        val usage = RuntimeUsage.measure(root, ::allocatedStat)
        val caveats = buildList {
            if (usage.unmeasured > 0) {
                add(tr("${usage.unmeasured} 项未计入", "${usage.unmeasured} unmeasured"))
            }
            if (usage.unreadableDirectories > 0) {
                add(tr("${usage.unreadableDirectories} 个目录不可读", "${usage.unreadableDirectories} unreadable dirs"))
            }
        }
        val suffix = if (caveats.isEmpty()) {
            ""
        } else {
            tr("（${caveats.joinToString("；")}）", " (${caveats.joinToString("; ")})")
        }
        formatBytes(usage.bytes) + suffix
    }.getOrNull()

    private fun compileCacheUsage(): String? = runCatching {
        val cacheDir = File(paths.rootfs, COMPILE_CACHE_REL)
        if (!cacheDir.isDirectory) return null
        val usage = RuntimeUsage.measure(cacheDir, ::allocatedStat)
        if (usage.bytes <= 0L) null else formatBytes(usage.bytes)
    }.getOrNull()

    private fun allocatedStat(path: String): RuntimeUsage.Stat? = runCatching {
        val stat = Os.lstat(path)
        RuntimeUsage.Stat(
            allocatedBytes = stat.st_blocks * BYTES_PER_BLOCK,
            isDirectory = OsConstants.S_ISDIR(stat.st_mode),
            isRegularFile = OsConstants.S_ISREG(stat.st_mode),
            isSymlink = OsConstants.S_ISLNK(stat.st_mode),
        )
    }.getOrNull()

    companion object {
        const val COMPILE_CACHE_REL = "tmp/pi-node-compile-cache"
        private const val PI_PACKAGE_DIR = "opt/pi/node_modules/@earendil-works/pi-coding-agent"
        private const val NODE_DIR = "opt/node"
        private const val BYTES_PER_BLOCK = 512L

        private val NODE_MAJOR = Regex("""#define\s+NODE_MAJOR_VERSION\s+(\d+)""")
        private val NODE_MINOR = Regex("""#define\s+NODE_MINOR_VERSION\s+(\d+)""")
        private val NODE_PATCH = Regex("""#define\s+NODE_PATCH_VERSION\s+(\d+)""")

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
            bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024L -> "${bytes / 1024L} KB"
            else -> "$bytes B"
        }
    }
}
