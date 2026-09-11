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
 * `/root/...`. Two guest paths can be that file, depending on how the engine's bind
 * mounts are arranged (the parent owns `PiEngineHost`, which today does not bind
 * `PI_CODING_AGENT_DIR`):
 *
 *  - `/root/.pi/device-bridge.json` — a plain file inside the rootfs, which proot
 *    maps 1:1. Always present, and always the *current* token because the bridge
 *    rewrites it on every start.
 *  - `/root/.pi/agent/device-bridge.json` — the host-side agent dir, which becomes
 *    guest-visible only if the engine binds it.
 *
 * Writing both means the extension works either way, and the extension itself
 * probes a documented list of paths (plus `PI_ANDROID_BRIDGE_FILE`) instead of
 * assuming one.
 *
 * The file is owner-readable only: 0600 inside app-private storage. It never
 * touches `/sdcard`, matching design §23.4.
 */
object DeviceBridgeController {

    /** JSON published for the guest extension. */
    private const val TOKEN_FILE_NAME = "device-bridge.json"

    /** Guest-visible location inside the rootfs. */
    const val GUEST_TOKEN_FILE = "/root/.pi/device-bridge.json"

    /** Assets shipped in the APK that make up the extension. */
    private const val ASSET_ROOT = "pi-extensions"

    /**
     * Bumped whenever anything under `assets/pi-extensions/` changes, to force
     * re-installation into the guest.
     *
     * This is a manual gate and it fails silently: the stamp is compared before
     * copying, so a device that already ran an older build keeps the old
     * extension tree and never sees the new one. The symptom is not an error —
     * it is a feature that "does not work", which sends whoever debugs it off to
     * check ports, tokens and networks instead of the installer.
     *
     * So: **every change to `pi-extensions/ 目录` must bump this string.**
     * History: "1" shipped the device bridge; "2" adds `pi-highlight/`; "3" adds the
     * session-scoped approvals, the workspace-relative shell policy and the SAF file
     * tools to the device extension; "4" adds the `device-reload` command (re-scan
     * extensions/skills/prompts without restarting the engine).
     *
     * A content-derived fingerprint (hashing the asset tree's names and sizes)
     * would remove the human step entirely and is the better long-term design —
     * recorded in docs/known-gaps.md rather than done here, because the file is
     * not the one being worked on right now.
     */
    const val ASSET_VERSION = "4"

    @Volatile
    private var server: DeviceBridgeHttpServer? = null

    @Volatile
    private var token: String? = null

    @Volatile
    private var auditLog: DeviceAuditLog? = null

    @Volatile
    private var lastReport = "未启动"

    fun store(context: Context): DeviceCapabilityStore = DeviceCapabilityStore.get(context)

    fun isRunning(): Boolean = server?.isRunning() == true

    /** The live bearer token, for the diagnostics card. Never logged. */
    fun currentToken(): String? = token

    fun auditLogPath(): String? = auditLog?.path

    fun auditTail(lines: Int): List<String> = auditLog?.tail(lines) ?: emptyList()

    /**
     * Start (or restart) the bridge. Idempotent; calling it again mints a new token
     * and republishes it, which is also the recovery path after the rootfs has been
     * re-extracted underneath a running session.
     *
     * @return a short human-readable status, also used by the diagnostics screen.
     */
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
        val payload = JSONObject().apply {
            put("service", "pi-android-device-bridge")
            put("version", DeviceBridgeRouter.BRIDGE_VERSION)
            put("port", DeviceBridgeRouter.DEFAULT_PORT)
            put("token", token)
            put("packageName", context.packageName)
            put("createdAt", System.currentTimeMillis())
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
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(payload)
                restrictToOwner(target)
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
     * @return a short description, e.g. `已安装` / `已是最新` / `失败: …`
     */
    fun installExtensionAssets(context: Context): String {
        val paths = PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
        val roots = mutableListOf(File(paths.agentDir, "extensions"))
        if (paths.rootfs.isDirectory) {
            roots.add(File(paths.rootfs, "root/.pi/agent/extensions"))
        }
        return try {
            var copied = 0
            var skipped = 0
            for (root in roots) {
                root.mkdirs()
                val stamp = File(root, ".pi-android-assets")
                if (stamp.isFile && stamp.readText().trim() == ASSET_VERSION) {
                    skipped++
                    continue
                }
                copyAssetTree(context, ASSET_ROOT, root)
                stamp.writeText(ASSET_VERSION)
                copied++
            }
            when {
                copied > 0 -> "已安装到 $copied 个位置"
                skipped > 0 -> "已是最新（$skipped 个位置）"
                else -> "未安装"
            }
        } catch (error: Exception) {
            "失败: ${error::class.java.simpleName}: ${error.message}"
        }
    }

    /** Recursive copy of an asset subtree; `AssetManager.list` is the only API. */
    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetTree(context, "$assetPath/$child", File(target, child))
        }
    }

    /** Where the given extension file ends up inside the guest. */
    fun guestExtensionPaths(): List<String> = listOf(
        "/root/.pi/agent/extensions/pi-android-bridge/index.ts",
        "/root/.pi/agent/extensions/pi-android-permission-gate.ts",
    )
}
