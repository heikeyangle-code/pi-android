package app.pi.bridge

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import java.io.File

/**
 * The bytes behind one markdown image link, with the source that produced them.
 *
 * [source] is for diagnostics (the `lastFailure` story) and is never sent anywhere:
 * it exists so a maintainer can tell "the link was a data: URI" apart from "the link
 * was a guest path that did not exist".
 */
internal class GuestImage(
    val bytes: ByteArray,
    val mimeType: String,
    val source: String,
)

/**
 * Turns a markdown image `link` into bytes.
 *
 * ## NOT WIRED YET — A3 does not take effect until this is called
 *
 * Nothing calls into this object, and nothing calls [PiGuestImageTransformer] either.
 * The missing wiring is two lines in `ui/render/**` (that directory's owner, not this
 * file's):
 *
 *  - **P-A** `ui/render/PiMarkdown.kt` — replace
 *    `LocalPiImageTransformer provides NoOpImageTransformerImpl()` with
 *    `LocalPiImageTransformer provides rememberPiGuestImageTransformer()`.
 *  - **P-B** `ui/render/PiMarkdownComponents.kt` — `PiImagePlaceholder` must decide
 *    from the *result* of `transform`, not from the transformer's type: the library
 *    drops the whole node when `transform` returns `null`, so wiring P-A without P-B
 *    would render an unresolvable link as nothing at all (today it shows alt + the
 *    source).
 *
 * Written down because this project has repeatedly shipped "the UI promises a
 * behaviour that was never connected": a reader who finds this file must not assume
 * images already render.
 *
 * ## What a `link` actually is — read from pi, not guessed
 *
 * pi defines **no** image-link semantics at all, and the sources below are why this
 * class has to decide rather than follow:
 *
 *  - pi's terminal markdown renderer has no `image` case. Its `switch` falls to the
 *    `default` branch, which prints the token's `text` — the alt text — and nothing
 *    else (`packages/tui/src/components/markdown.ts:619-627`). A markdown image in pi
 *    is therefore *text*, never a fetch.
 *  - Images on the RPC wire are inline base64 and never a path:
 *    `prompt`/`steer`/`follow_up` carry `images?: ImageContent[]` as
 *    `{type:"image", data:<base64>, mimeType}` (`packages/coding-agent/docs/rpc.md:78`),
 *    and the documented session `Attachment` embeds `content` as base64 with no file
 *    on disk (`docs/rpc.md:1510-1521`). So "pi session attachment" is *not* a location
 *    this channel could read — those bytes travel inside the message and are rendered
 *    by `ui/blocks/ImageGridBlock.kt`, a different surface.
 *  - The one place pi does turn an image into a path is the TUI clipboard paste: it
 *    writes `/tmp/pi-clipboard-<uuid>.<ext>` and inserts that **path as text** into
 *    the editor (`src/modes/interactive/interactive-mode.ts:2934-2946`). That text can
 *    end up quoted in an assistant message, which is the pi-grounded reason a guest
 *    path is worth resolving at all.
 *
 * Everything else is model-authored text: an LLM writing `![chart](/tmp/x.png)` is not
 * following a pi contract. So this resolver accepts the shapes that are *resolvable*
 * and returns `null` — letting the transcript keep its placeholder — for anything
 * else. It never invents a path.
 *
 * ## What it resolves
 *
 *  - `data:` URIs, decoded in place. Self-contained, so no channel is involved.
 *  - `file:` URIs and bare filesystem paths, mapped from the guest's spelling to the
 *    host file that spelling denotes (table below).
 *
 * ## What it deliberately does **not** resolve: `http(s)`
 *
 * A remote URL is refused, not attempted. pi itself never fetches an image, and in a
 * phone app a *silent* request to whatever host the model wrote would leak the user's
 * IP (and the fact that they opened this session) to that host, and would hand the
 * model a probe/tracking primitive with no user-visible action. Rendering the link as
 * text — what the transcript already does for a link it cannot resolve — costs the
 * user nothing and leaks nothing. If a future design wants remote images it belongs
 * behind an explicit user tap, not behind composition.
 *
 * ## Where the bytes come from (guest path to host file)
 *
 * The bridge runs in the app process, and the guest is a proot rootfs of the same
 * app-private tree, so every bind the engine creates is a *host directory* this
 * process can already read. The mappings, each one a `ProotCommand.build` bind:
 *
 *  - `/workspace/<reg>` to `<filesDir>/<reg>` — `PiEngineHost.guestPathFor`
 *    (`engine/PiEngineHost.kt:552-556`) strips the `<filesDir>` prefix and mounts
 *    under `/workspace` (`:286`). The terminal's `PtyLauncher` binds the *same* host
 *    directory one level up, at plain `/workspace`
 *    (`runtime/PtyLauncher.kt:146,264`), so both spellings are probed.
 *  - `/tmp` to `<files>/pi/runtime/tmp` — `runtime/PiRuntime.kt:120`.
 *  - `/root/.pi/agent` to `<files>/pi/.pi/agent` — `PiEngineHost.kt:291`.
 *  - `/sdcard` and `/storage/emulated/0` to the device's shared storage —
 *    `runtime/PiRuntime.kt:92-93`.
 *  - anything else (`/etc`, `/opt`, `/usr`, `/root/...`) to the rootfs:
 *    `--rootfs=<files>/pi/runtime/rootfs` (`runtime/PiRuntime.kt:114`).
 *
 * A host path can also be named directly (`/data/user/0/<pkg>/...`,
 * `/storage/emulated/0/...`), so plain absolute paths are the last probe. The order is
 * not incidental — [GuestPathMapping] documents it as the safety argument, and
 * `app/src/test/kotlin/app/pi/bridge/GuestPathMappingCheck.kt` pins it.
 *
 * ## Why not loopback HTTP
 *
 * The other loopback services in this app (`DeviceBridgeHttp`, the highlight service)
 * exist because the *guest* has to reach the *host* process. Here the bytes move in
 * the opposite direction and never leave the app process, so a socket would add a
 * token, a port and a failure mode to a `File.readBytes()`. No endpoint is added.
 *
 * ## Limits, stated rather than hidden
 *
 * Only the five formats pi's own `read` tool accepts are decoded
 * (jpg/png/gif/webp/bmp — `docs/pi-android-app-design.md:507`; the decoder is
 * `BitmapFactory` in the transformer). An animated GIF renders its first frame. A
 * `data:` URI renders whatever base64 it carries. Shared storage is read through the
 * app's own permissions, so on a scoped-storage device a path there may simply not be
 * readable — that is reported as a failure, never as an empty image. pi's
 * `images.blockImages` setting is **not** consulted, because pi defines it as
 * "prevents all images from being sent to LLM providers"
 * (`core/settings-manager.ts:64`) — an outbound-request switch, not a rendering one.
 */
internal object GuestImageBytes {

    /**
     * 8 MB. This is a display cap, not pi's provider cap: pi resizes what it sends to
     * a model to 2000x2000 / 4.5 MB base64 (`docs/pi-android-app-design.md:580`), while
     * this path only has to fit on a phone screen. Larger than the cap is refused with
     * a reason instead of being decoded and OOM-ing the transcript.
     */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * Why the last load failed, for diagnostics. `null` after a success. Mirrors
     * `PiHighlightClient.lastFailure` so both byte channels can be reported the same
     * way.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Blocking. Call from `Dispatchers.IO`; returns `null` when there is nothing to decode. */
    fun load(context: Context, rawLink: String): GuestImage? {
        val link = rawLink.trim().removeSurrounding("<", ">").trim()
        if (link.isEmpty()) return fail("图片链接为空")
        if (link.startsWith("http://", ignoreCase = true) ||
            link.startsWith("https://", ignoreCase = true)
        ) {
            // A refusal, not a missing feature: see the class comment. The transcript
            // keeps its alt + source line, which is what pi itself would show.
            return fail("不下载远端图片（$link）：不代替用户向模型写出的主机发起请求")
        }
        return try {
            when {
                link.startsWith("data:", ignoreCase = true) -> fromDataUri(link)
                else -> fromFile(context, link)
            }
        } catch (error: Exception) {
            fail("读取图片失败：${error::class.java.simpleName}: ${error.message}")
        }
    }

    // --------------------------------------------------------------- sources ----

    /** `data:image/png;base64,...` — self-contained, so no channel is involved. */
    private fun fromDataUri(link: String): GuestImage? {
        val comma = link.indexOf(',')
        if (comma < 0) return fail("data: URI 缺少逗号分隔符")
        val header = link.substring("data:".length, comma)
        val payload = link.substring(comma + 1)
        val base64 = header.split(';').any { it.trim().equals("base64", ignoreCase = true) }
        val bytes = if (base64) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
        if (bytes.isEmpty()) return fail("data: URI 解出 0 字节")
        if (bytes.size > MAX_BYTES) return fail("data: URI 超过 ${MAX_BYTES / 1024 / 1024} MB 上限")
        val declared = header.substringBefore(';').trim()
        return GuestImage(bytes, mimeOf(bytes, declared, null), "data: URI（${bytes.size} 字节）")
    }

    private fun fromFile(context: Context, link: String): GuestImage? {
        val path = when {
            link.startsWith("file:", ignoreCase = true) -> fileUriToPath(link)
                ?: return fail("不支持的 file: URI：$link")

            else -> link
        }
        for (candidate in hostCandidates(context, path)) {
            if (!candidate.isFile) continue
            if (candidate.length() > MAX_BYTES) {
                return fail("图片文件 ${candidate.name} 超过 ${MAX_BYTES / 1024 / 1024} MB 上限")
            }
            val bytes = candidate.readBytes()
            if (bytes.isEmpty()) return fail("图片文件 ${candidate.name} 是空文件")
            return GuestImage(bytes, mimeOf(bytes, "", candidate.name), candidate.absolutePath)
        }
        return fail("找不到图片文件：$path（已尝试 guest 到 host 的全部绑定映射）")
    }

    // ------------------------------------------------------- guest to host ----

    /**
     * Every host file a guest-or-host spelling could mean, in priority order.
     *
     * The rules and their reasoning live in [GuestPathMapping]; this function only
     * supplies the current install's roots and the two spellings of the link.
     */
    private fun hostCandidates(context: Context, path: String): List<File> {
        val paths = PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
        val roots = GuestPathRoots(
            filesDir = context.filesDir.absolutePath,
            rootfs = paths.rootfs.absolutePath,
            tmp = paths.tmp.absolutePath,
            agentDir = paths.agentDir.absolutePath,
            workspaceHost = runCatching { PtyLauncher.workspaceHost(context).absolutePath }.getOrNull(),
            storage = Environment.getExternalStorageDirectory().absolutePath,
        )
        // Percent-escapes survive the markdown parser, so both spellings are tried.
        val spellings = listOf(path, runCatching { Uri.decode(path) }.getOrDefault(path)).distinct()
        return GuestPathMapping.candidates(spellings, roots).map { File(it) }
    }

    /** `file:///a/b`, `file:/a/b` and `file://localhost/a/b`; a remote authority is refused. */
    private fun fileUriToPath(link: String): String? {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "file") return null
        val authority = uri.authority
        if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
        return uri.path ?: return null
    }

    // -------------------------------------------------------------- helpers ----

    /**
     * Magic-byte sniffing first, then whatever the source declared, then the
     * extension. pi's accepted set is jpg/png/gif/webp/bmp
     * (`docs/pi-android-app-design.md:507`), so those are the signatures here.
     */
    private fun mimeOf(bytes: ByteArray, declared: String, fileName: String?): String {
        sniff(bytes)?.let { return it }
        declared.trim().takeIf { it.startsWith("image/") }?.let { return it }
        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/*"
        }
    }

    private fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"

        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"

        bytes.size >= 6 &&
            String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"

        bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"

        else -> null
    }

    private fun fail(reason: String): GuestImage? {
        lastFailure = reason
        return null
    }
}
