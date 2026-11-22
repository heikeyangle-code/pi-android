package app.pi.bridge

import android.content.Context
import android.util.Base64
import app.pi.runtime.PiPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Lifecycle owner of the device bridge.
 *
 * **The single call the app has to make is [start].** Everything else — minting the
 * token, publishing it where the guest can read it, installing the pi extension
 * that talks to it, binding the loopback socket — hangs off that.
 *
 * ### Where the token lives, and why in two places
 *
 * The extension runs *inside* the proot guest, so it needs a file it can open as
 * `/root/...`. Two guest paths can be that file:
 *
 *  - `/root/.pi/device-bridge.json` — a plain file inside the rootfs, which proot
 *    maps 1:1. Always present, and always the *current* token because the bridge
 *    rewrites it on every start.
 *  - `/root/.pi/agent/device-bridge.json` — the host-side agent dir. This one is
 *    bound by the engine (`PiEngineHost.kt:291`, `paths.agentDir to
 *    /root/.pi/agent`), so it is the same host file the app reads and writes.
 *
 * The second path used to be a *fallback* for the case where the engine did not
 * bind the agent dir; that case is gone, but both are still written because the
 * two are only guaranteed to coincide while the bind exists. If the bind is ever
 * dropped, the rootfs copy is the one that keeps working.
 *
 * The extension itself probes a documented list of paths (plus
 * `PI_ANDROID_BRIDGE_FILE`) instead of assuming one.
 *
 * The file is owner-readable only: 0600 inside app-private storage. It never
 * touches `/sdcard`, matching design §23.4.
 */
object DeviceBridgeController {

    /** JSON published for the guest extension. */
    private const val TOKEN_FILE_NAME = "device-bridge.json"

    /** Guest-visible location inside the rootfs. */
    const val GUEST_TOKEN_FILE = "/root/.pi/device-bridge.json"

    @Volatile
    private var server: DeviceBridgeHttpServer? = null

    @Volatile
    private var token: String? = null

    @Volatile
    private var auditLog: DeviceAuditLog? = null

    @Volatile
    private var lastReport = "未启动"

    /**
     * What the previous bridge session left half-done, read from the write-ahead
     * audit trail the moment a new one starts. Published on `/app/health`, so the
     * first tool call after a crash can report "上次执行中异常终止" instead of
     * silently starting from a clean slate.
     */
    @Volatile
    private var lastUnpaired: String? = null

    fun lastUnpairedReport(): String? = lastUnpaired

    fun store(context: Context): DeviceCapabilityStore = DeviceCapabilityStore.get(context)

    fun isRunning(): Boolean = server?.isRunning() == true

    /**
     * The live bearer token. Never logged, and **currently has no caller**: the
     * diagnostics card reports `tokenPresent` instead ([diagnostics]), on purpose —
     * showing the token on screen is how it ends up in a screenshot. Kept as the
     * one accessor a support flow would need, and labelled so nobody assumes the UI
     * already uses it.
     */
    fun currentToken(): String? = token

    fun auditLogPath(): String? = auditLog?.path

    /** Raw JSON lines, for `/app/audit` and the model. */
    fun auditTail(lines: Int): List<String> = auditLog?.tail(lines) ?: emptyList()

    /** One-line-per-request rendering for the diagnostics card. */
    fun auditPrettyTail(lines: Int): List<String> = auditLog?.prettyTail(lines) ?: emptyList()

    /**
     * Start (or restart) the bridge. Idempotent; calling it again mints a new token
     * and republishes it, which is also the recovery path after the rootfs has been
     * re-extracted underneath a running session.
     *
     * @return a short human-readable status, also used by the diagnostics screen.
     */
    @Synchronized
    fun start(context: Context): String {
        val appContext = context.applicationContext
        stop()

        val paths = PiPaths(
            filesDir = appContext.filesDir,
            nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir),
        )
        val store = DeviceCapabilityStore.get(appContext)
        val log = DeviceAuditLog(File(paths.home, "device-bridge-audit.log"))
        auditLog = log
        // Read the trail *before* this session writes into it: a `start` line with
        // no `result` is the fingerprint of a request that killed the process.
        lastUnpaired = log.lastUnpaired()

        // The shell's write boundary is the user's workspace; re-read it on every
        // start so a workspace change is picked up without a rebuild.
        DeviceWorkspace.refresh(appContext)

        val minted = mintToken()
        token = minted

        val installed = installExtensionAssets(appContext)
        val published = publishTokenFile(appContext, paths, minted)

        val router = DeviceBridgeRouter(
            context = appContext,
            port = DeviceBridgeRouter.DEFAULT_PORT,
            tokenFileLabel = GUEST_TOKEN_FILE,
            auditLog = log,
        )
        val http = DeviceBridgeHttpServer(
            port = DeviceBridgeRouter.DEFAULT_PORT,
            token = minted,
            handler = { request -> router.route(request) },
            audit = { event -> log.record(event) },
        )
        return try {
            http.start()
            server = http
            lastReport = "已监听 127.0.0.1:${DeviceBridgeRouter.DEFAULT_PORT}" +
                "（扩展 $installed，token 写入 $published）"
            lastReport
        } catch (error: Exception) {
            lastReport = "启动失败：${error::class.java.simpleName}: ${error.message}"
            lastReport
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        lastReport = "已停止"
    }

    fun statusReport(): String = lastReport

    /** A snapshot for the diagnostics card: no token, only facts. */
    fun diagnostics(context: Context): JSONObject {
        val store = DeviceCapabilityStore.get(context)
        return JSONObject().apply {
            put("running", isRunning())
            put("port", DeviceBridgeRouter.DEFAULT_PORT)
            put("status", lastReport)
            put("tokenFile", GUEST_TOKEN_FILE)
            put("tokenPresent", token != null)
            put("auditLogPath", auditLog?.path ?: "")
            put("accessibilityRunning", DeviceAccessibilityService.isRunning())
            val caps = JSONArray()
            for (state in store.states()) {
                caps.put(
                    JSONObject()
                        .put("id", state.capability.id)
                        .put("enabled", state.enabled)
                        .put("sessionDisabled", !state.enabledForSession)
                        .put("usable", state.usable)
                        .put("reason", state.reason ?: JSONObject.NULL),
                )
            }
            put("capabilities", caps)
        }
    }

    // ------------------------------------------------------------- internals ----

    private fun mintToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Publish the token JSON to every place the guest might look. Returns a short
     * description of what was written, for the status line.
     */
    private fun publishTokenFile(context: Context, paths: PiPaths, token: String): String {
        val usableCaps = runCatching {
            DeviceCapabilityStore.get(context).states().filter { it.usable }.map { it.capability.id }
        }.getOrDefault(emptyList())
        val payload = JSONObject().apply {
            put("service", "pi-android-device-bridge")
            put("version", DeviceBridgeRouter.BRIDGE_VERSION)
            put("port", DeviceBridgeRouter.DEFAULT_PORT)
            put("token", token)
            put("packageName", context.packageName)
            put("createdAt", System.currentTimeMillis())
            put("usableCapabilities", JSONArray(usableCaps))
            put("endpoints", JSONArray(DeviceBridgeRouter.ENDPOINTS))
        }.toString(2)

        val targets = mutableListOf<File>()
        // 1. Host-side agent dir (guest path only if the engine binds it).
        targets.add(File(paths.agentDir, TOKEN_FILE_NAME))
        // 2. Inside the rootfs: always guest-visible at /root/.pi/...
        val rootfsRoot = File(paths.rootfs, "root/.pi")
        if (paths.rootfs.isDirectory) {
            targets.add(File(rootfsRoot, TOKEN_FILE_NAME))
        }

        val written = mutableListOf<String>()
        for (target in targets) {
            if (app.pi.packages.PiConfigFiles.write(target, payload, mode600 = true)) {
                written.add(target.absolutePath)
            }
        }
        if (!targets.any { it.isFile }) {
            // Last resort: keep the token in process memory and say so, rather than
            // pretending the guest can read it.
            lastReport = "无法写入 token 文件（运行时尚未解包）"
        }
        return if (written.isEmpty()) "未写入" else written.joinToString(", ")
    }

    /** 0600: the proot guest runs as this app's uid, so owner-only is enough. */
    private fun restrictToOwner(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.setExecutable(false, false)
        }
    }

    /**
     * Copy the shipped extension next to pi's other extensions, in both the host
     * agent dir and the rootfs copy. Without this the tools simply do not exist for
     * the agent, so it is part of "start", not a separate chore for the app.
     *
     * The stamp is a **content fingerprint** of the asset tree ([assetFingerprint]),
     * not a hand-bumped constant. `ASSET_VERSION` remains only as the fallback for
     * the case where the package's assets cannot be read at all, which is strictly
     * the old behaviour rather than a new failure.
     *
     * @return a short description, e.g. `已安装` / `已是最新` / `失败: …`
     */
    fun installExtensionAssets(context: Context): String {
        val paths = PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
        // App 的 SDK 引擎（host.bundle.mjs）已将唯一的 Android 扩展 `pi-android-bridge` 预编译为官方
        // `builtin:pi-android-bridge` 工厂，零 jiti / 零 Babel 冷启动；不再往磁盘拷 .ts 副本，也不再改写
        // 终端 `/opt/pi/bin/pi` 包装脚本。这里只做一次性的旧版升级清理（并按 APK stamp 缓存零 I/O 跳过）。
        val legacyRoots = linkedMapOf<String, File>().apply {
            val agentExt = File(paths.agentDir, "extensions")
            put(runCatching { agentExt.canonicalPath }.getOrDefault(agentExt.absolutePath), agentExt)
            if (paths.rootfs.isDirectory) {
                val rootfsExt = File(paths.rootfs, "root/.pi/agent/extensions")
                put(runCatching { rootfsExt.canonicalPath }.getOrDefault(rootfsExt.absolutePath), rootfsExt)
            }
        }.values.toList()
        val legacyCliExtRoot = if (paths.rootfs.isDirectory) {
            File(paths.rootfs, "opt/pi/android-extensions")
        } else {
            null
        }
        val legacyEntries = listOf(
            "pi-android-bridge",
            "pi-android-permission-gate.ts",
            "pi-highlight",
            "README.md",
            ".pi-android-assets",
            ".pi-android-apk-stamp",
        )
        val apkMarker = File(paths.agentDir, ".pi-android-apk-stamp")
        return try {
            val apkStamp = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
                "${info.lastUpdateTime}-$code"
            }.getOrNull()
            val legacyClean = legacyRoots.all { root ->
                !root.isDirectory || !File(root, "pi-android-bridge").exists() &&
                    !File(root, "pi-android-permission-gate.ts").exists() &&
                    !File(root, "pi-highlight").exists()
            } && (legacyCliExtRoot == null || !legacyCliExtRoot.exists())
            if (apkStamp != null && legacyClean && apkMarker.isFile &&
                runCatching { apkMarker.readText().trim() == apkStamp }.getOrDefault(false)
            ) {
                return "已是最新（builtin:pi-android-bridge 预编译内置，APK 缓存命中）"
            }
            for (root in legacyRoots) {
                if (!root.isDirectory) continue
                for (name in legacyEntries) {
                    val f = File(root, name)
                    if (f.exists()) f.deleteRecursively()
                }
            }
            if (legacyCliExtRoot != null && legacyCliExtRoot.exists()) {
                legacyCliExtRoot.deleteRecursively()
            }
            restoreVanillaCliPiIfPatched(paths)
            if (apkStamp != null) {
                runCatching {
                    apkMarker.parentFile?.mkdirs()
                    apkMarker.writeText(apkStamp)
                }
            }
            "已清理旧版扩展副本（builtin:pi-android-bridge 预编译内置）"
        } catch (error: Exception) {
            "清理旧版扩展失败: ${error::class.java.simpleName}: ${error.message}"
        }
    }

    /**
     * 升级自旧版 APK 时，若 `<rootfs>/opt/pi/bin/pi` 曾被旧版写入过 `/opt/pi/android-extensions`
     * 补丁，自动恢复为官方原版直跑 `cli.js` 的 2 行脚本，不保留任何终端私有补丁。
     */
    private fun restoreVanillaCliPiIfPatched(paths: PiPaths) {
        runCatching {
            val wrapper = File(paths.rootfs, "opt/pi/bin/pi")
            if (!wrapper.isFile) return
            val existing = runCatching { wrapper.readText() }.getOrNull() ?: return
            if (!existing.contains("opt/pi/android-extensions")) return
            val hostEngineRoot = File(paths.rootfs, "opt/pi/node_modules/@earendil-works/pi-coding-agent")
            if (!File(hostEngineRoot, "dist").isDirectory) return
            val guestEngineRoot = "/opt/pi/node_modules/@earendil-works/pi-coding-agent"
            val guestCli = if (File(hostEngineRoot, "dist/bundle/cli.js").isFile) {
                "$guestEngineRoot/dist/bundle/cli.js"
            } else {
                "$guestEngineRoot/dist/cli.js"
            }
            wrapper.writeText(buildVanillaCliPiScript(guestCli))
            wrapper.setExecutable(true, false)
        }
    }

    internal fun buildVanillaCliPiScript(guestCli: String): String =
        "#!/bin/bash
exec /opt/node/bin/node $guestCli "\$@"
"
}
