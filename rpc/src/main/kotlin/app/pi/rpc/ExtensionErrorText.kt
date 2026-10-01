package app.pi.rpc

/**
 * pi's prefix for a path that names no file (`core/source-info.ts:15`).
 *
 * Built-in extensions and, since 0.99.0, built-in tools are identified by
 * `builtin:<name>` rather than by a file, so the value can arrive in
 * `extension_error`'s `extensionPath` and in `get_commands`' `sourceInfo.path`
 * (where `source` reads `"builtin"`, not the older `"inline"`).
 */
private const val BUILTIN_PATH_PREFIX = "builtin:"

/**
 * Attribution for `extension_error`, the one thing that event carries and the
 * transcript/notice had no way to say.
 *
 * pi emits it from its error listener when an extension handler throws
 * (`rpc-mode.ts:348-350`, fed by `runner.ts:851-882`):
 *
 * ```json
 * {"type":"extension_error","extensionPath":"…","event":"tool_call","error":"…"}
 * ```
 *
 * `extensionPath` is the extension's **entry file** — either `<name>.ts` or
 * `<name>/index.ts`, the two layouts pi's loader discovers — and this app may not
 * put a file path in user-visible copy. So [extensionErrorName] reduces it to the
 * last segment that identifies the extension at all, and
 * [extensionErrorHeadline] turns that into the sentence both surfaces show. The
 * alternative, naming nothing, is what made a broken extension indistinguishable
 * from one that chose to do nothing.
 *
 * `event` (the pi hook that threw) is deliberately **not** shown: pi's own UI does
 * not show it either (`interactive-mode.ts:2827-2828` prints
 * `Extension "<path>" error: <error>`), and a hook name is an extension API
 * identifier rather than something a user can act on. It stays on
 * [PiEvent.ExtensionError.event] for callers that want it.
 */
fun extensionErrorHeadline(extensionPath: String?): String {
    val name = extensionErrorName(extensionPath)
    return if (name == null) "扩展出错" else "扩展出错：$name"
}

/**
 * The identifying segment of pi's `extensionPath`, or `null` when pi sent none.
 *
 * Three rules, the first two because a path can name no file at all:
 *
 *  - a **synthetic** path is reduced to the name inside it. pi uses two shapes and
 *    neither is a file (`core/source-info.ts:15-27`): `builtin:<name>` — its own
 *    built-in extensions, and since 0.99.0 its built-in tools too — and
 *    `<source:name>`, the older angle-bracket form, whose source segment is dropped
 *    by the same rule that makes pi call it synthetic. Without this rule the prefix
 *    reaches user-visible copy, which is exactly what this file promises never
 *    happens: 「扩展出错：builtin:mcp」;
 *  - the entry file's own name is used as-is, matching how the app already names
 *    extensions elsewhere (the TUI-only list shows `file.name`);
 *  - an entry file literally named `index.*` is replaced by its containing
 *    directory, because `index.ts` says nothing about which extension failed.
 *
 * The result is a name, never a path: no separator survives.
 */
fun extensionErrorName(extensionPath: String?): String? {
    val trimmed = extensionPath?.trim()?.trimEnd('/') ?: return null
    if (trimmed.isEmpty()) return null
    // A synthetic path is answered here and **only** here: falling through to the
    // file rules would hand back the prefix itself when the name is empty
    // (`builtin:`), which is the leak this rule exists to prevent.
    if (isSyntheticExtensionPath(trimmed)) return syntheticExtensionName(trimmed)
    val file = trimmed.substringAfterLast('/').substringAfterLast('\\')
    if (file.isEmpty()) return null
    if (file.substringBeforeLast('.', file) == "index") {
        val parent = trimmed
            .substringBeforeLast('/', "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
        if (parent.isNotEmpty()) return parent
    }
    return file
}

/**
 * Whether [path] names no file, by pi's own test (`core/source-info.ts:26-28`):
 * the `builtin:` prefix, or an angle-bracket path. Both shapes reach this file only
 * through `extension_error`.
 */
private fun isSyntheticExtensionPath(path: String): Boolean =
    path.startsWith(BUILTIN_PATH_PREFIX) || path.startsWith("<")

/**
 * The name inside a synthetic path, or `null` when it carries none.
 *
 * Both shapes carry the name after the source, so one split covers them: `builtin:`
 * is a prefix, and the angle-bracket form puts its source before the first colon
 * (`<inline:mcp>` — pi's own `getSyntheticPathSource` splits on that same colon).
 * `null` rather than an empty string, so the caller shows the generic sentence
 * instead of a label-less colon.
 */
private fun syntheticExtensionName(path: String): String? {
    val name = when {
        path.startsWith(BUILTIN_PATH_PREFIX) -> path.removePrefix(BUILTIN_PATH_PREFIX)
        path.startsWith("<") && path.endsWith(">") -> path.substring(1, path.length - 1)
        else -> return null
    }
    // A bare `<name>` is its own name; `<source:name>` keeps the part after the colon.
    return name.substringAfterLast(':').takeIf { it.isNotEmpty() }
}
