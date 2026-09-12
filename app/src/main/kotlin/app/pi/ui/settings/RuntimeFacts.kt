package app.pi.ui.settings

import android.app.ActivityManager
import android.content.Context
import app.pi.runtime.PiPaths
import app.pi.service.PiEngineService
import org.json.JSONObject
import java.io.File

/**
 * The four read-only values of 设置 → 运行时, read from whatever actually owns
 * them — or reported as unreadable.
 *
 * ## Why this exists at all
 *
 * Those four rows used to show a `defaultValue` ("未安装" / "未知") that nothing
 * ever wrote, so the screen presented a fixed string in the position where a
 * fact belongs. A wrong value that looks like a reading is worse than no value,
 * which is why every reader here returns `null` rather than a plausible default
 * and the caller renders the reason.
 *
 * ## Where each value comes from, and why that source is the truth
 *
 *  - **pi version** — pi's own version is its package manifest:
 *    `export const VERSION: string = pkg.version || "0.0.0"`
 *    (`packages/coding-agent/src/config.ts:505`), which is what `--version`
 *    prints (`src/cli/args.ts:93`). The app ships that same manifest in the
 *    engine payload (`RuntimeProvisioner.extractEngine` → `<rootfs>/opt/pi`),
 *    so reading it here is the same source, not a second copy that can drift.
 *  - **node version** — the payload is the official tarball, re-compressed
 *    without touching its contents (`tools/fetch-runtime.mjs`), and
 *    `include/node/node_version.h` is what `node -v` is built from. Reading the
 *    header avoids starting a guest process just to print a version.
 *  - **runtime usage** — the real byte count of `<files>/pi/runtime`, walked on
 *    the IO dispatcher by the caller. The volatile tree is the one that grows
 *    (rootfs, package caches), so that is what the row is about.
 *  - **wake lock** — the engine's foreground service is what holds it:
 *    `PiEngineService` acquires a `PARTIAL_WAKE_LOCK` in `onStartCommand` and
 *    releases it in `onDestroy`/`ACTION_STOP`. The lock object itself is private
 *    to the service, so the observable fact is whether that service is running;
 *    the row's description says so instead of implying a direct lock reading.
 */
class RuntimeFacts(
    private val context: Context,
    private val paths: PiPaths,
) {

    data class Snapshot(
        /** `version` from the shipped pi manifest, or null when unreadable. */
        val piVersion: String?,
        /** `v<major>.<minor>.<patch>` from the shipped node payload, or null. */
        val nodeVersion: String?,
        /** Preformatted size of the volatile runtime tree, or null. */
        val runtimeUsage: String?,
        /**
         * The wake lock's own `isHeld` (`PiEngineService.isWakeLockHeld()`), not an
         * inference from the service being alive.
         */
        val wakeLockHeld: Boolean,
        /**
         * Whether the engine service is alive. Used only to explain the one case
         * where the two disagree: the lock has a six-hour cap while the service
         * keeps running.
         */
        val serviceRunning: Boolean,
        /**
         * Whether the runtime tree looks unpacked. The usual reason a read fails
         * is "not unpacked yet", and saying that is more useful than "读取失败".
         */
        val runtimeUnpacked: Boolean,
    )

    fun read(): Snapshot = Snapshot(
        piVersion = piVersion(),
        nodeVersion = nodeVersion(),
        runtimeUsage = runtimeUsage(),
        wakeLockHeld = PiEngineService.isWakeLockHeld(),
        serviceRunning = PiEngineService.isRunning(),
        runtimeUnpacked = paths.rootfs.isDirectory,
    )

    /** `<rootfs>/opt/pi/node_modules/<pi package>/package.json` → `version`. */
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
        var bytes = 0L
        root.walkTopDown().forEach { file -> if (file.isFile) bytes += file.length() }
        formatBytes(bytes)
    }.getOrNull()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

    private companion object {
        /**
         * `RuntimeProvisioner.extractEngine` unpacks the payload into
         * `<rootfs>/opt/pi`, and `tools/fetch-runtime.mjs` installs exactly one
         * package into it. Kept as the same string `PiEngineHost` uses for the
         * guest CLI path, so the two cannot disagree.
         */
        const val PI_PACKAGE_DIR = "opt/pi/node_modules/@earendil-works/pi-coding-agent"

        /** `RuntimeProvisioner.extractNode` renames the archive's top dir to this. */
        const val NODE_DIR = "opt/node"

        val NODE_MAJOR = Regex("""#define\s+NODE_MAJOR_VERSION\s+(\d+)""")
        val NODE_MINOR = Regex("""#define\s+NODE_MINOR_VERSION\s+(\d+)""")
        val NODE_PATCH = Regex("""#define\s+NODE_PATCH_VERSION\s+(\d+)""")
    }
}

/**
 * The four 运行时 rows' display strings, including the reason when a value could
 * not be read.
 *
 * Every key always gets an entry, so a row can never fall through to its
 * registry default in the position where a reading belongs. Null (the first
 * frame, before the IO read lands) yields 未读取 rather than a fake value.
 */
internal fun runtimeOverrides(snapshot: RuntimeFacts.Snapshot?): Map<String, String> {
    if (snapshot == null) {
        return listOf(
            "app.runtime.piVersion",
            "app.runtime.nodeVersion",
            "app.runtime.rootfsUsage",
            "app.runtime.wakeLock",
        ).associateWith { "未读取" }
    }
    val unreadable = if (snapshot.runtimeUnpacked) {
        "取不到：运行时里没有这个文件"
    } else {
        "取不到：运行时尚未解包"
    }
    return mapOf(
        "app.runtime.piVersion" to (snapshot.piVersion ?: unreadable),
        "app.runtime.nodeVersion" to (snapshot.nodeVersion ?: unreadable),
        "app.runtime.rootfsUsage" to (snapshot.runtimeUsage ?: unreadable),
        // The lock itself is private to the service; what is observable is the
        // service, and the row's description says so.
        "app.runtime.wakeLock" to when (snapshot.serviceRunning) {
            true -> "前台服务运行中（唤醒锁随其持有）"
            false -> "前台服务未运行（唤醒锁已释放）"
            null -> "取不到：系统没有返回本应用的服务列表"
        },
    )
}

