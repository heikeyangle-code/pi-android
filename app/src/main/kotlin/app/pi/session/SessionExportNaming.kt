package app.pi.session

/**
 * The naming and typing rules of `/export`, in pi's spelling.
 *
 * These three rules used to live inside `PiSessionViewModel.exportSession`, where
 * nothing could execute them: the file imports Compose and Android, so the
 * bare-JVM harness cannot compile it. They are pure string arithmetic, and two of
 * them are pi's own rules with a `file:line`, which is exactly the shape this
 * repository pins in `tools/run-app-pure-checks.sh` (registered there in the
 * `sessions` closure).
 *
 * ## The one rule that is a copy, and why it is acceptable
 *
 * pi picks the export's *format* from the argument's extension
 * (`modes/interactive/interactive-mode.ts:6062-6066`): `.jsonl` goes to
 * `exportToJsonl`, anything else — including no argument — to `exportToHtml`. That
 * dispatch is not data pi could hand us over RPC (`export_html` is the only export
 * command, `rpc-types.ts:60`), so it is transcribed here, once, for both writers.
 *
 * `pi-session-` is the package's `APP_NAME` (`config.ts:502`: `pkg.piConfig?.name ||
 * "pi"`), not a second truth: the name is cosmetic, the file's *content* comes from
 * pi, and the app's previous default (`pi-session-<毫秒时间戳>.html`) had the same
 * prefix with a less useful middle. If a repackaged payload ever renames itself, the
 * exported file keeps this prefix — a difference in a file name, nothing else.
 */
object SessionExportNaming {

    /** The suffix pi treats as JSONL (`interactive-mode.ts:6064`). */
    const val JSONL_SUFFIX: String = ".jsonl"

    /** The suffix of the other writer's output. */
    const val HTML_SUFFIX: String = ".html"

    /**
     * pi's `/export` branch (`interactive-mode.ts:6062-6066`).
     *
     * `argument` is what the user typed after `/export`, already trimmed and
     * null/blank when there was none. Extension matching is case-insensitive on
     * purpose: pi compares with `endsWith(".jsonl")` (case-sensitive), which would
     * send `/export Notes.JSONL` to the HTML writer and produce an HTML file named
     * `Notes.JSONL`. Treating the suffix as case-insensitive is the same intent
     * without that surprise, and it is the app's only deliberate deviation here.
     */
    fun isJsonl(argument: String?): Boolean =
        argument != null && argument.endsWith(JSONL_SUFFIX, ignoreCase = true)

    /**
     * The default HTML name: `<APP_NAME>-session-<会话文件 basename>.html`
     * (`core/export-html/index.ts:274-281`).
     *
     * [sessionFile] is pi's guest path of the active session file, or null when
     * `get_state` did not report one — the file itself only appears at the first
     * assistant `message_end` (`session-manager.ts:1029-1052`), so an export taken
     * before then has nothing to borrow a name from. The timestamp fallback keeps the
     * name unique instead of producing a second `pi-session-.html`.
     */
    fun defaultHtmlName(sessionFile: String?, nowMs: Long): String {
        val base = sessionFile
            ?.substringAfterLast('/')
            ?.removeSuffix(JSONL_SUFFIX)
            ?.takeIf { it.isNotBlank() }
            ?: "session-$nowMs"
        return "pi-session-$base$HTML_SUFFIX"
    }

    /**
     * What the delivery channel should call the file.
     *
     * `text/html` for the exporter's output and `text/plain` for the JSONL branch:
     * both files are UTF-8 text, and there is no registered MIME type for JSONL
     * (`application/jsonl` is not in the IANA registry), so `text/plain` is the
     * honest one — a download manager that guesses `application/json` would offer to
     * reformat a file whose every line is a separate JSON document.
     */
    fun mimeTypeFor(name: String): String = when {
        name.endsWith(HTML_SUFFIX, ignoreCase = true) -> "text/html"
        else -> "text/plain"
    }
}
