package app.pi.ui.render.rich

/**
 * The **pure URL model** behind the lightweight link card (`§十一.3` of
 * `docs/plans/rich-rendering-upgrade.md`).
 *
 * The Compose card lives in `PiLinkCardView.kt`; this file has no Compose import
 * so it can be compiled by the bare-JVM toolchain like the other `rich` models.
 *
 * ## The rule this file exists to enforce
 *
 * `§十一.3` is explicit: "链接卡片只能展示 URL 本身可以确定的信息，不得伪造标题、
 * 摘要和图片。" So:
 *
 * - **no network request** — this parser never opens a socket, resolves DNS or
 *   fetches anything; it stops at [PiLinkInfo];
 * - **no Open Graph** — there is no title, description, site name or preview
 *   image anywhere in the model, because none of them can be derived from a URL;
 * - **no invented labels** — the card's type (`PDF`, `图片`, `代码`, …) comes from
 *   the path's file extension, and the host/path are the URL's own text. An
 *   unknown extension is [LinkKind.Other], not a guess;
 * - **credentials are not displayed** — a `user:password@host` URL is parsed, but
 *   the userinfo never reaches [PiLinkInfo.host] or the card. Only the host, the
 *   non-default port and the path are shown.
 *
 * Only `http` and `https` produce a card. Other schemes (`mailto:`, `tel:`,
 * `file:`, `javascript:`, `data:`, …) are [LinkCardFailure.UnsupportedScheme] and
 * the caller keeps the plain link it already renders — the card must never make a
 * dangerous or unknown scheme look like a rich, trustworthy destination.
 *
 * ## What is not validated
 *
 * The URL is not checked for reachability, certificate validity or content type.
 * An extension is a naming fact, not proof of the response's `Content-Type`; that
 * is exactly why it is presented as a type label and not as a claim about the
 * page. Malformed URLs (spaces, a missing host, an invalid port, a relative path)
 * are [LinkCardFailure.NotUrl] and fall back to the original text.
 */

/** The URL-only type shown on the card. The label is the visible string. */
enum class LinkKind(val label: String) {
    WebPage("网页"),
    Image("图片"),
    Document("文档"),
    Archive("压缩包"),
    Code("代码"),
    Video("视频"),
    Audio("音频"),
    Other("链接"),
}

/**
 * Everything the card is allowed to know.
 *
 * All fields are literal facts of the URL. [path] is the raw path (percent
 * escapes are kept as written) and is empty when the URL has none;
 * [pathForDisplay] turns that into `/` and marks an omitted query/fragment with
 * `?…`/`#…` rather than dropping the information silently.
 */
data class PiLinkInfo(
    /** The URL as given (trimmed of surrounding whitespace). */
    val url: String,
    /** Lower-cased scheme: `http` or `https`. */
    val scheme: String,
    /** Host as written (IPv6 keeps its brackets); userinfo is never part of it. */
    val host: String,
    /** Non-default port; `null` for 80/443 and for no port. */
    val port: Int?,
    /** Raw path, e.g. `/docs/page.html`; empty when absent. */
    val path: String,
    /** Whether a non-empty query string was present (its text is never shown). */
    val hasQuery: Boolean,
    /** Whether a non-empty fragment was present (its text is never shown). */
    val hasFragment: Boolean,
    /** The URL-only type. */
    val kind: LinkKind,
    /** The lower-cased extension of the last path segment, when it is plausible. */
    val fileExtension: String?,
) {
    /** `host` plus a non-default port. */
    val hostForDisplay: String get() = if (port == null) host else "$host:$port"

    /** Path with `/` for "none", plus `?…`/`#…` when the URL carries them. */
    val pathForDisplay: String
        get() = buildString {
            append(path.ifEmpty { "/" })
            if (hasQuery) append("?…")
            if (hasFragment) append("#…")
        }
}

/** Why a string did not become a link card. */
enum class LinkCardFailure {
    Empty,
    TooLong,
    NotUrl,

    /** A syntactically valid URL of a scheme this card does not render. */
    UnsupportedScheme,
}

/** The result of [PiLinkCard.parse]. */
sealed interface LinkCardResult {
    data class Info(val info: PiLinkInfo) : LinkCardResult

    data class Failure(val reason: LinkCardFailure) : LinkCardResult
}

/**
 * URL → [PiLinkInfo], with no network and no guessing.
 *
 * The parser is deliberately a small hand-written scanner rather than
 * `java.net.URI`: the app only needs the authority and the path, and URI's
 * authority parsing rejects several host shapes (IDN, IPv6 variants) that a user
 * may legitimately paste. The trade-off is that anything ambiguous is refused
 * ([LinkCardFailure.NotUrl]) instead of being normalized into a claim.
 */
object PiLinkCard {
    /** A card is never drawn for a URL longer than this; the plain link stays. */
    const val MAX_URL_CHARS: Int = 8_192

    private val SCHEME = Regex("""^[a-z][a-z0-9+.-]*$""")

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "ico", "svg")
    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "md", "epub",
    )
    private val ARCHIVE_EXTENSIONS = setOf("zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "zst")
    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rs", "go", "c", "h", "cc", "cpp",
        "hpp", "json", "xml", "yaml", "yml", "toml", "sh", "bash", "zsh", "gradle", "sql",
    )
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "mkv", "avi", "m3u8")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "m4a", "flac", "opus")

    /** Parse [url]; see the file KDoc for what is and is not allowed. */
    fun parse(url: String): LinkCardResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return LinkCardResult.Failure(LinkCardFailure.Empty)
        if (trimmed.length > MAX_URL_CHARS) return LinkCardResult.Failure(LinkCardFailure.TooLong)
        // A raw space or control character means the text is not a URL; percent-
        // encoding is the only way a space may appear in one.
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return LinkCardResult.Failure(LinkCardFailure.NotUrl)
        }

        val schemeEnd = trimmed.indexOf(':')
        if (schemeEnd <= 0) return LinkCardResult.Failure(LinkCardFailure.NotUrl)
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (!SCHEME.matches(scheme)) return LinkCardResult.Failure(LinkCardFailure.NotUrl)
        if (scheme != "http" && scheme != "https") {
            return LinkCardResult.Failure(LinkCardFailure.UnsupportedScheme)
        }

        val afterScheme = trimmed.substring(schemeEnd + 1)
        if (!afterScheme.startsWith("//")) return LinkCardResult.Failure(LinkCardFailure.NotUrl)
        var rest = afterScheme.substring(2)
        if (rest.isEmpty()) return LinkCardResult.Failure(LinkCardFailure.NotUrl)

        val fragment = splitFirst(rest, '#').also { rest = it.first }.second
        val query = splitFirst(rest, '?').also { rest = it.first }.second

        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""
        if (authority.isEmpty()) return LinkCardResult.Failure(LinkCardFailure.NotUrl)

        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return LinkCardResult.Failure(LinkCardFailure.NotUrl)

        val host: String
        val rawPort: Int?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return LinkCardResult.Failure(LinkCardFailure.NotUrl)
            host = hostPort.substring(0, close + 1)
            val afterHost = hostPort.substring(close + 1)
            rawPort = when {
                afterHost.isEmpty() -> null
                !afterHost.startsWith(":") -> return LinkCardResult.Failure(LinkCardFailure.NotUrl)
                else -> afterHost.substring(1).toIntOrNull()
                    ?: return LinkCardResult.Failure(LinkCardFailure.NotUrl)
            }
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0) {
                // A bare IPv6 address (more than one colon) is ambiguous without
                // brackets; refuse rather than normalize it.
                if (hostPort.indexOf(':') != colon) return LinkCardResult.Failure(LinkCardFailure.NotUrl)
                host = hostPort.substring(0, colon)
                rawPort = hostPort.substring(colon + 1).toIntOrNull()
                    ?: return LinkCardResult.Failure(LinkCardFailure.NotUrl)
            } else {
                host = hostPort
                rawPort = null
            }
        }

        if (host.isEmpty()) return LinkCardResult.Failure(LinkCardFailure.NotUrl)
        if (host.any { it == '/' || it == '@' || it == '?' || it == '#' }) {
            return LinkCardResult.Failure(LinkCardFailure.NotUrl)
        }
        if (rawPort != null && (rawPort < 1 || rawPort > 65_535)) {
            return LinkCardResult.Failure(LinkCardFailure.NotUrl)
        }

        // A userinfo `@` may not leak into the displayed authority; it is already
        // removed above (`substringAfterLast('@')`).

        val defaultPort = (scheme == "http" && rawPort == 80) || (scheme == "https" && rawPort == 443)
        val port = if (defaultPort) null else rawPort

        val lastSegment = path.substringAfterLast('/')
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isLetterOrDigit) }

        return LinkCardResult.Info(
            PiLinkInfo(
                url = trimmed,
                scheme = scheme,
                host = host,
                port = port,
                path = path,
                hasQuery = query.isNotEmpty(),
                hasFragment = fragment.isNotEmpty(),
                kind = kindOf(lastSegment, extension),
                fileExtension = extension,
            ),
        )
    }

    /** `name` labels the type of the file the path names; no network, no content type. */
    private fun kindOf(lastSegment: String, extension: String?): LinkKind = when {
        extension == null -> if (lastSegment.contains('.')) LinkKind.Other else LinkKind.WebPage
        extension in IMAGE_EXTENSIONS -> LinkKind.Image
        extension in DOCUMENT_EXTENSIONS -> LinkKind.Document
        extension in ARCHIVE_EXTENSIONS -> LinkKind.Archive
        extension in CODE_EXTENSIONS -> LinkKind.Code
        extension in VIDEO_EXTENSIONS -> LinkKind.Video
        extension in AUDIO_EXTENSIONS -> LinkKind.Audio
        extension == "html" || extension == "htm" -> LinkKind.WebPage
        else -> LinkKind.Other
    }
}

/** Split at the first [separator], returning `(before, after)`; after is empty when absent. */
private fun splitFirst(text: String, separator: Char): Pair<String, String> {
    val index = text.indexOf(separator)
    return if (index < 0) text to "" else text.substring(0, index) to text.substring(index + 1)
}
