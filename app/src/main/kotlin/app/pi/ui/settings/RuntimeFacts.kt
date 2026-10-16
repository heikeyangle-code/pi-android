package app.pi.ui.settings

import android.system.Os
import android.system.OsConstants
import app.pi.engine.PiEngineSession
import app.pi.runtime.PiPaths
import app.pi.runtime.ProrootProbeNarrative
import app.pi.runtime.RuntimeSelection
import app.pi.runtime.RuntimeUsage
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
 *  - **runtime usage** — the **allocated** size of `<files>/pi/runtime` (`RuntimeUsage`:
 *    `lstat` per entry, `st_blocks * 512`, symlinks never followed), read on the IO
 *    dispatcher by the caller. The volatile tree is the one that grows, so that is what the
 *    row is about; it contains the unpacked rootfs (which in turn contains the agent dir
 *    with `npm` packages and sessions), `tmp/` and the payload bookkeeping. The row's
 *    description names that scope, and the number is meant to be checked against `du` on
 *    that one directory.
 *  - **wake lock** — `PiEngineService.isWakeLockHeld()`, i.e. the lock's own
 *    `isHeld`, published by the service that owns it. Not an inference from the
 *    service being alive, which matters more now than it used to: the lock is held
 *    only while something CPU-bound happens (engine start, a running turn —
 *    `PiEngineLifecyclePolicy.shouldHoldWakeLock`), so a service that is up with the
 *    lock released is the ordinary idle state, not a fault.
 */
class RuntimeFacts(
    private val paths: PiPaths,
) {

    data class Snapshot(
        /** `version` from the shipped pi manifest, or null when unreadable. */
        val piVersion: String?,
        /** `v<major>.<minor>.<patch>` from the shipped node payload, or null. */
        val nodeVersion: String?,
        /**
         * Preformatted **allocated** size of the volatile runtime tree (`<files>/pi/runtime`,
         * symlinks not followed), or null. When a part of the tree could not be measured the
         * sentence carries that caveat — the total is never presented as complete.
         */
        val runtimeUsage: String?,
        /**
         * How long the last engine took to answer its first command, in ms, or null
         * when none has been measured yet.
         *
         * This is the wait between "the engine process exists" and "the engine can
         * act on a message", which is where a first message goes when the user types
         * it immediately after opening the app (`PiEngineSession.probeServing`).
         */
        val engineStartupMs: Long?,
        /**
         * The wake lock's own `isHeld` (`PiEngineService.isWakeLockHeld()`), not an
         * inference from the service being alive.
         */
        val wakeLockHeld: Boolean,
        /**
         * Whether the engine service is alive. Used only to explain the case where
         * the two disagree — the ordinary one being "the service is up and nothing
         * needs the CPU right now".
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
        engineStartupMs = PiEngineSession.lastServingMs,
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
        val usage = RuntimeUsage.measure(root, ::allocatedStat)
        val caveats = buildList {
            if (usage.unmeasured > 0) add("${usage.unmeasured} 项取不到分配块，未计入")
            if (usage.unreadableDirectories > 0) add("${usage.unreadableDirectories} 个目录读不出")
        }
        val suffix = if (caveats.isEmpty()) "" else "（${caveats.joinToString("；")}）"
        formatBytes(usage.bytes) + suffix
    }.getOrNull()

    /**
     * `lstat` (the link itself) plus `st_blocks * 512` — the disk usage of the entry, not its
     * apparent size. `java.nio.file`'s `PosixFileAttributes` carries no block count on
     * Android, which is why this goes through [android.system.Os].
     *
     * Null when the entry cannot be measured. [RuntimeUsage] then counts it as unmeasured and
     * the row says so rather than presenting an incomplete total as the whole number.
     */
    private fun allocatedStat(path: String): RuntimeUsage.Stat? = runCatching {
        val stat = Os.lstat(path)
        RuntimeUsage.Stat(
            allocatedBytes = stat.st_blocks * BYTES_PER_BLOCK,
            isDirectory = OsConstants.S_ISDIR(stat.st_mode),
            isRegularFile = OsConstants.S_ISREG(stat.st_mode),
            isSymlink = OsConstants.S_ISLNK(stat.st_mode),
        )
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

        /** `st_blocks` is in 512-byte units (POSIX `DEV_BSIZE`), not in the filesystem's block size. */
        const val BYTES_PER_BLOCK = 512L

        val NODE_MAJOR = Regex("""#define\s+NODE_MAJOR_VERSION\s+(\d+)""")
        val NODE_MINOR = Regex("""#define\s+NODE_MINOR_VERSION\s+(\d+)""")
        val NODE_PATCH = Regex("""#define\s+NODE_PATCH_VERSION\s+(\d+)""")
    }
}

/**
 * The 运行时 rows' display strings, including the reason when a value could
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
            "app.runtime.engineStartup",
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
        // Measured, not estimated: `PiEngineSession.probeServing` times the span
        // between starting pi and pi's first answer. Rounded to whole seconds
        // because that is the resolution the wait is felt at, and because a
        // milliseconds figure would imply a precision this deliberately variable
        // measurement does not have.
        "app.runtime.engineStartup" to (
            snapshot.engineStartupMs?.let { ms ->
                if (ms < 1000) "$ms 毫秒" else "${(ms + 500) / 1000} 秒"
            } ?: "尚未启动过引擎"
            ),
        // The lock's own state, read through `PiEngineService.isWakeLockHeld()` — the
        // lock's `isHeld`, not an inference from the service being alive. Since the
        // lock is taken per busy period (`PiEngineLifecyclePolicy.shouldHoldWakeLock`,
        // applied by `PiEngineService.reportWork`), "service up, lock released" is the
        // **normal idle state**. The old wording here ("锁已达 6 小时上限") was an
        // inference that the policy change made wrong: it told the user their
        // protection had just expired while the app was simply doing nothing.
        "app.runtime.wakeLock" to when {
            snapshot.wakeLockHeld -> "持有中"
            snapshot.serviceRunning -> "未持有（当前空闲）"
            else -> "未持有"
        },
    )
}

/**
 * The **detail block** under a read-only runtime row, keyed the same way
 * [runtimeOverrides] is.
 *
 * ## Why it is a second map and not part of the value
 *
 * A row's value is one ellipsized line (`PiSettingRow` renders it with
 * `maxLines = 1`), which is right for a sentence and wrong for evidence: the proroot
 * gate records four to six lines, one per measured phase, and the whole point of this
 * block is that they are readable **without exporting the diagnostic report**. The
 * block is rendered under the row by `SettingsGroupScreen`, from the string handed in
 * here.
 *
 * ## Why it cannot become per-frame work
 *
 * The only input is a `RuntimeSelection.Status`, which the caller obtained from
 * `status()` — the one call that stats five `.so` files and hashes them. This function
 * is pure string work over fields already in that object: it opens no file, starts no
 * process and touches no Android API (`ProrootProbeNarrative` is a bare-JVM-tested
 * object). `PiSettingsStack` computes both strings in the **same** `LaunchedEffect`
 * pass, on `Dispatchers.IO`, keyed by the settings epoch and the switch epoch, and
 * `AppOnlySettingsStore` still never reaches the probe from `read(key)` — so the count
 * of probe-cache reads and `.so` hashes is exactly what it was before this block
 * existed. A row rendering this map only formats a string it was given.
 *
 * ## Honesty
 *
 * Empty means "nothing to add" (the switch is off, the files are missing, proroot is in
 * use) and the row renders no block. A missing probe verdict is **not** empty: it
 * becomes the narrative's "尚未运行 / 读不到逐阶段记录" line, because a block that
 * silently disappears where a reading is expected is how the original defect read.
 */
internal fun runtimeDetailOverrides(status: RuntimeSelection.Status?): Map<String, String> {
    if (status == null) return emptyMap()
    val text = ProrootProbeNarrative.boundedDetailText(
        fallback = status.fallback,
        probePassed = status.probePassed,
        probeDetail = status.probeDetail,
    )
    return if (text.isBlank()) emptyMap() else mapOf(AppOnlySettingsStore.KEY_STATUS to text)
}

