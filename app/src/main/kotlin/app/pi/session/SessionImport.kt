package app.pi.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The three rules `/import` needs, as pure functions.
 *
 * pi's `/import` is a TUI command (`modes/interactive/interactive-mode.ts:6107-6119`)
 * and RPC has no import command, but `switch_session` takes a **session file path**
 * (`rpc-types.ts:61`) and its handler runs precisely the same pipeline pi's import
 * does — `emitBeforeSwitch` (an extension can veto), `SessionManager.open` (validates
 * the header) and `assertSessionCwdExists` (`rpc-mode.ts:605-611` →
 * `core/agent-session-runtime.ts:197-224`). So the app's job is only what pi's
 * `importFromJsonl` does *before* that call (`agent-session-runtime.ts:361-405`):
 * check the file, pick the destination name, copy it into the session directory.
 *
 * Those three steps are string arithmetic over the file's head, so they live here
 * instead of in the ViewModel — the ViewModel imports Android and Compose and cannot
 * be executed by `tools/run-app-pure-checks.sh`. Registered in that script's
 * `sessions` closure.
 */
object SessionImport {

    /** pi's usage string names this suffix (`interactive-mode.ts:6109`). */
    const val SUFFIX: String = ".jsonl"

    /**
     * How much of the head is inspected before deciding.
     *
     * pi's own discovery budget is 1 MiB (`session-manager.ts:487-489`,
     * `MAX_SESSION_HEADER_SCAN_BYTES`), and the caller reads exactly that much. A
     * session whose header sits beyond it is not rejected here — see
     * [Verdict.Indeterminate].
     */
    const val HEAD_SCAN_CHARS: Int = 1 shl 20

    /**
     * How far the `-1`, `-2` … suffix search goes before giving up.
     *
     * pi loops without a bound (`agent-session-runtime.ts:376-379`); a bound is
     * needed here because the callback cannot be trusted to terminate, and 1000
     * colliding imports of one file name means something else is wrong.
     */
    const val MAX_COPY_SUFFIX: Int = 1000

    /** The fields of the session header this app needs (`session-manager.ts:564-570`). */
    data class Header(val id: String, val cwd: String?)

    /**
     * What the head of the picked file says.
     *
     * [NotASession] is a **certain** rejection: the first parseable line is not a
     * session header, which is exactly the file pi refuses with "Session file is not
     * a valid pi session" — `loadEntriesFromFile` drops its whole entry list when
     * `entries[0]` is not `{type: "session", id: string}` (`session-manager.ts:551-556`)
     * and `_setSessionFile` then throws for a non-empty file with no entries
     * (`:905-908`). Rejecting it before the copy means the session directory does not
     * collect a file nothing can open.
     *
     * [Indeterminate] is everything else — an empty file, only blank/garbage lines,
     * or a header that lives past the scan budget. pi is the authority there:
     * `switch_session` decides. (For an empty file pi *succeeds*: `_setSessionFile`
     * initialises it with a fresh header, `session-manager.ts:904-916`.)
     */
    sealed interface Verdict {
        data class Found(val header: Header) : Verdict
        data object NotASession : Verdict
        data object Indeterminate : Verdict
    }

    /**
     * Read the verdict out of the file's first [HEAD_SCAN_CHARS] characters.
     *
     * The line rules are pi's: blank lines are skipped and malformed lines are
     * skipped (`parseSessionEntryLine`, `session-manager.ts:499-508`), and the
     * **first** line that parses decides — a `type` of `"session"` with a string `id`
     * is the header, anything else means this is not a session file.
     *
     * A truncated final line (the head was cut mid-record) parses as nothing, which
     * is correct: it is skipped like a malformed line, and if it was the only
     * candidate the verdict is [Verdict.Indeterminate] rather than a rejection.
     */
    fun verdictOf(head: String): Verdict {
        for (line in head.lineSequence()) {
            if (line.isBlank()) continue
            // Never throws (`PiJson`'s contract), so a malformed line is simply not
            // a candidate — which is pi's own skip rule (`session-manager.ts:499-508`).
            val entry = app.pi.rpc.PiJson.parseObjectOrNull(line) ?: continue
            val id = entry.string("id")
            return if (entry.string("type") == "session" && id != null) {
                Verdict.Found(Header(id = id, cwd = entry.string("cwd")))
            } else {
                Verdict.NotASession
            }
        }
        return Verdict.Indeterminate
    }

    /**
     * The name the copy takes in the session directory
     * (`agent-session-runtime.ts:371-379`).
     *
     * pi takes the source's basename, and when that name is taken it inserts `-1`,
     * `-2` … **before the extension** (`path.parse` + `name-suffix ext`), which is
     * what makes the copies sort next to each other in the session list. [taken] is a
     * predicate rather than a directory so this stays testable without a filesystem.
     *
     * @return null when every candidate within [MAX_COPY_SUFFIX] is taken. The caller
     *         must refuse the import then — overwriting an existing session file with
     *         `File.writeBytes` would destroy a conversation, which is why pi uses
     *         `COPYFILE_EXCL` (`agent-session-runtime.ts:387`) and why there is no
     *         "just replace it" fallback here.
     */
    fun destinationName(sourceName: String, taken: (String) -> Boolean): String? {
        if (sourceName.isBlank()) return null
        if (!taken(sourceName)) return sourceName
        // Node's `path.parse`: a leading dot is part of the name, not an extension
        // separator (`.bashrc` → name `.bashrc`, ext ``).
        val dot = sourceName.lastIndexOf('.')
        val name = if (dot > 0) sourceName.substring(0, dot) else sourceName
        val ext = if (dot > 0) sourceName.substring(dot) else ""
        for (suffix in 1..MAX_COPY_SUFFIX) {
            val candidate = "$name-$suffix$ext"
            if (!taken(candidate)) return candidate
        }
        return null
    }

    /** The picked file has no `.jsonl` name: pi's own usage says `<path.jsonl>`. */
    fun wrongSuffixSentence(name: String): String =
        "「$name」不是会话文件。请选择扩展名为 .jsonl 的会话记录（本应用导出的就是这种文件）。"

    /** The document could not be opened or copied. */
    fun unreadableSentence(): String =
        "读不到所选文件：它可能已被删除，或这个来源不允许本应用读取。请重新选择一次，或先把文件复制到本机再导入。"

    /** Every candidate name was taken. */
    fun noFreeNameSentence(name: String): String =
        "会话目录里已经有太多同名文件，「$name」放不进去。请先把旧的导入文件改名或删除，再试一次。"

    /**
     * One sentence for a failed `switch_session`, with a next step.
     *
     * pi's raw error text is deliberately **not** echoed: two of the three shapes it
     * takes carry a filesystem path (`Stored session working directory does not
     * exist: /…`, `Session file is not a valid pi session: /…`, `session-cwd.ts:35-40`,
     * `session-manager.ts:908`) and user-visible copy in this app never shows an
     * internal directory. The classification is on pi's own wording, so a reworded
     * pi version degrades to the generic sentence instead of printing a path.
     *
     * The first shape is the one the app cannot paper over: pi's TUI offers
     * "continue in current cwd" there (`interactive-mode.ts:2545` →
     * `formatMissingSessionCwdPrompt`), and RPC's `switch_session` takes no
     * `cwdOverride` (`rpc-types.ts:61`) — so the honest answer is that this session
     * cannot be continued here, not a silent fallback into another directory.
     */
    fun failureSentence(reason: String?): String {
        val text = reason.orEmpty()
        return when {
            text.contains("working directory does not exist", ignoreCase = true) ->
                "这份会话记录来自另一个工作目录，这台设备上没有那个目录，本应用不能在别的目录里继续它。" +
                    "请改从本机导出的会话导入。"

            text.contains("not a valid", ignoreCase = true) ->
                "所选文件不是本应用的会话记录：它没有可识别的会话头。" +
                    "请选一个由本应用导出的 .jsonl 文件。"

            else ->
                "导入没有成功：这份会话记录打不开。" +
                    "请确认它是本应用导出的 .jsonl 文件，再试一次。"
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
