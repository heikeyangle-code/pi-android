package app.pi.bridge

import android.content.Context
import android.util.Base64
import app.pi.runtime.PiPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
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

    /** Assets shipped in the APK that make up the extension. */
    private const val ASSET_ROOT = "pi-extensions"

    /**
     * **No longer the install gate.** The stamp written next to the installed
     * extensions is a content fingerprint of the asset tree ([assetFingerprint]), so
     * editing anything under `assets/pi-extensions/` no longer needs a version bump
     * and can no longer be forgotten.
     *
     * This constant is the fallback for the one case a fingerprint cannot cover:
     * when the asset tree cannot be read at all, the marker becomes `v<this value>`
     * and the installer behaves exactly as it did before the fingerprint existed
     * (compare, copy, stamp) rather than claiming everything is current.
     *
     * It still records what each shipped generation contained, which is why the
     * history stays: "1" shipped the device bridge; "2" adds `pi-highlight/`; "3"
     * adds the session-scoped approvals, the workspace-relative shell policy and the
     * SAF file tools to the device extension; "4" adds the `device-reload` command
     * (re-scan extensions/skills/prompts without restarting the engine).
     *
     * The manual gate this replaced is `docs/known-gaps.md` B12 / E6 — a silent
     * failure whose symptom was "some feature just does not work", which sends
     * whoever debugs it to ports, tokens and networks instead of the installer.
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

    /**
     * The live bearer token. Never logged, and **currently has no caller**: the
     * diagnostics card reports `tokenPresent` instead ([diagnostics]), on purpose —
     * showing the token on screen is how it ends up in a screenshot. Kept as the
     * one accessor a support flow would need, and labelled so nobody assumes the UI
     * already uses it.
     */
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
        val roots = mutableListOf(File(paths.agentDir, "extensions"))
        if (paths.rootfs.isDirectory) {
            roots.add(File(paths.rootfs, "root/.pi/agent/extensions"))
        }
        return try {
            val fingerprint = assetFingerprint(context)
            val marker = fingerprint ?: "v$ASSET_VERSION"
            var copied = 0
            var skipped = 0
            var bytes = 0L
            for (root in roots) {
                root.mkdirs()
                val stamp = File(root, ".pi-android-assets")
                if (stamp.isFile && stamp.readText().trim() == marker) {
                    skipped++
                    continue
                }
                bytes += copyAssetTree(context, ASSET_ROOT, root)
                stamp.writeText(marker)
                copied++
            }
            val how = if (fingerprint == null) {
                "标记 $marker（资产内容读不出来，退回手工版本号）"
            } else {
                "标记 ${marker.take(15)}…（内容指纹）"
            }
            when {
                copied > 0 -> "已安装到 $copied 个位置，$bytes 字节，$how"
                skipped > 0 -> "已是最新（$skipped 个位置，$how）"
                else -> "未安装"
            }
        } catch (error: Exception) {
            // Name what the package actually contains: a FileNotFoundException from
            // `assets.open` cannot distinguish "asset missing" from "asset packaging
            // is broken", and the reader has no other way to tell.
            "失败: ${error::class.java.simpleName}: ${error.message}；包内实际内容：${describeAssets(context)}"
        }
    }

    /**
     * Recursive copy of an asset subtree; `AssetManager.list` is the only API.
     *
     * One limitation cannot be worked around with this API: `list` returns an empty
     * array for an empty *directory* exactly as it does for a *file*, so an empty
     * directory inside `assets/pi-extensions/` would be opened as a file and throw.
     * None exists today; the failure message names the asset path if one appears.
     *
     * @return the number of bytes copied, so `start`'s status line can be checked
     *   against reality instead of trusted.
     */
    private fun copyAssetTree(context: Context, assetPath: String, target: File): Long {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            return context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.mkdirs()
        var total = 0L
        for (child in children) {
            total += copyAssetTree(context, "$assetPath/$child", File(target, child))
        }
        return total
    }

    /**
     * A SHA-256 over the whole asset tree, as `sha256:<hex>`: every file's asset path
     * and its bytes, in sorted path order.
     *
     * This is the fix for the manual `ASSET_VERSION` gate in `docs/known-gaps.md`
     * B12 / E6. That gate failed silently in the worst way — a device that had run an
     * older build kept the old extension tree forever, and the symptom was "some
     * feature just does not work", which sends the reader to ports, tokens and
     * networks instead of the installer.
     *
     * `null` means the asset tree could not be read at all (the same condition that
     * makes `copyAssetTree` throw); the caller then falls back to the constant, so a
     * broken package produces today's behaviour and never a permanently empty
     * extension directory.
     */
    private fun assetFingerprint(context: Context): String? = runCatching {
        val names = ArrayList<String>()
        collectAssetFiles(context, ASSET_ROOT, names)
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in names.sorted()) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(context.assets.open(name).use { it.readBytes() })
            digest.update(0.toByte())
        }
        "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    /** Every file (leaf) under [assetPath], by asset path. */
    private fun collectAssetFiles(context: Context, assetPath: String, into: MutableList<String>) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            into.add(assetPath)
            return
        }
        for (child in children) collectAssetFiles(context, "$assetPath/$child", into)
    }

    /** What the APK's asset tree really contains, for a failure message. */
    private fun describeAssets(context: Context): String = runCatching {
        val top = context.assets.list(ASSET_ROOT).orEmpty().toList()
        if (top.isNotEmpty()) {
            return@runCatching "assets/$ASSET_ROOT 下有 ${top.size} 项：${top.joinToString("、")}"
        }
        val root = context.assets.list("").orEmpty().toList()
        "assets/$ASSET_ROOT 列不出来；assets 根目录下有 ${root.size} 项：" +
            (if (root.isEmpty()) "（空）" else root.joinToString("、"))
    }.getOrElse { "列目录本身失败：${it::class.java.simpleName}: ${it.message}" }

    /**
     * Where the shipped extensions land inside the guest.
     *
     * Derived from the asset tree rather than hardcoded, because the previous literal
     * list named only `pi-android-bridge/index.ts` and
     * `pi-android-permission-gate.ts`: it had been stale since `pi-highlight/` was
     * added (the very thing `ASSET_VERSION` history "2" records). Nothing calls this
     * yet — it is here because it is the only place that answers "where did the
     * extension go?" in guest terms.
     */
    fun guestExtensionPaths(context: Context): List<String> =
        runCatching { context.assets.list(ASSET_ROOT).orEmpty().toList() }
            .getOrDefault(emptyList())
            .map { name -> "$GUEST_EXTENSIONS_DIR/$name" }

    /** The guest directory the engine binds to `paths.agentDir/extensions`. */
    private const val GUEST_EXTENSIONS_DIR = "/root/.pi/agent/extensions"
}
