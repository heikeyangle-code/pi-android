package app.pi.rpc

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
 * Two rules, both because a bare entry file name can identify nothing:
 *
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
