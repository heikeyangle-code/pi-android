package app.pi.ui.chat

import android.content.Context
import android.util.Base64
import app.pi.bridge.DeviceActionException
import app.pi.bridge.DeviceSystemActions
import app.pi.ui.ExportedSession
import java.io.File

/**
 * How a finished export gets out of the app.
 *
 * ## Why this exists
 *
 * `/export` writes the session into the app's **private** workspace. pi's own
 * export lands in the user's cwd (`core/export-html/index.ts:274-281`) and its TUI
 * reports that path (`interactive-mode.ts:6060-6075`), so the file is reachable on
 * a desktop; inside an Android sandbox there is no such place and no file manager
 * can open the artifact. The export is the deliverable, so the app owes the user a
 * way to take it — and it already owns exactly two of them for its own diagnostic
 * report ([app.pi.ui.settings.DiagnosticsExport]):
 *
 *  - **Download**, through [DeviceSystemActions.export] — MediaStore on API 29+, the
 *    legacy public directory below it, no new permission on modern Android. This is
 *    the app's one writer to a user-visible location.
 *  - **The system share sheet**, through [DeviceSystemActions.share].
 *
 * Deliberately the same two sinks as the diagnostic report: a third storage
 * location, or a `FileProvider` of this app's own, would be a second channel to
 * keep correct for one button.
 *
 * ## Sharing is text, and that has a limit
 *
 * [DeviceSystemActions.share] puts the content in `Intent.EXTRA_TEXT`, which
 * travels through Binder. A session export is not bounded like the diagnostic
 * report is (`DiagnosticsReport` caps itself at 120 000 characters for this very
 * reason): an HTML export of a long conversation is legitimately megabytes. So a
 * too-large file is answered with the one action that still works — save it to
 * Download first — instead of letting `TransactionTooLargeException` surface as a
 * crash or a message about a class the user has never heard of.
 *
 * Both functions return a [Result] rather than showing anything: the screen owns the
 * snackbar. Neither throws, because both are the last step of a successful export and
 * a failure here must not read as "the export failed".
 */
object SessionExportDelivery {

    /**
     * How much text may travel in one share intent.
     *
     * Well under the Binder transaction limit (~1 MiB, and shared with everything
     * else in the call), and above the diagnostic report's 120 000-character cap so
     * the report's own share path never hits this one.
     */
    const val MAX_SHARE_CHARS: Int = 400_000

    /**
     * What to tell the user, and how loudly.
     *
     * A result type rather than a bare sentence: the caller decides the snackbar's
     * tone, and deciding it by matching the sentence's first characters would make
     * the two files depend on each other's wording.
     */
    data class Result(val sentence: String, val warning: Boolean = false)

    /** Save to the public Download folder. */
    fun saveToDownloads(context: Context, exported: ExportedSession): Result = try {
        val file = File(exported.path)
        if (!file.isFile) {
            Result(unreadableSentence(), warning = true)
        } else {
            DeviceSystemActions.export(
                context = context,
                name = exported.name,
                text = null,
                base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
                mimeType = exported.mimeType,
            )
            Result("已保存到 Download/${exported.name}")
        }
    } catch (error: DeviceActionException) {
        Result(
            "保存到 Download 失败：${error.denial.reason}" +
                (error.denial.hint?.let { " $it" } ?: ""),
            warning = true,
        )
    } catch (error: Exception) {
        // No class name and no path: the user can act on "read the file again" and
        // on nothing else this could say.
        Result("保存到 Download 失败：文件读不出来了。请重新导出一份再试。", warning = true)
    }

    /** Open the share sheet with the export. */
    fun share(context: Context, exported: ExportedSession): Result = try {
        val file = File(exported.path)
        when {
            !file.isFile -> Result(unreadableSentence(), warning = true)

            // Bytes here, characters there: `EXTRA_TEXT` carries a String, and a
            // UTF-8 Chinese session costs up to three bytes per character in the
            // transaction. `length()` is the cheap bound and runs before anything is
            // read — 400 000 characters can be at most 1.2 MB of UTF-8, which is
            // already too much for one Binder transaction when the intent carries
            // anything else.
            file.length() > MAX_SHARE_CHARS.toLong() * 3L -> Result(TOO_LARGE, warning = true)

            else -> {
                val text = file.readText()
                if (text.length > MAX_SHARE_CHARS) {
                    Result(TOO_LARGE, warning = true)
                } else {
                    DeviceSystemActions.share(
                        context = context,
                        text = text,
                        subject = exported.name,
                        url = null,
                    )
                    Result("已打开分享")
                }
            }
        }
    } catch (error: DeviceActionException) {
        Result(
            "分享失败：${error.denial.reason}" + (error.denial.hint?.let { " $it" } ?: ""),
            warning = true,
        )
    } catch (error: Exception) {
        Result("分享失败：这份导出读不出来了。请重新导出一份，或改用保存到 Download。", warning = true)
    }

    /**
     * The one action that still works when the text is too big for a share intent:
     * save it to Download, then share it from there. Named once so the two size
     * checks cannot drift apart.
     */
    private const val TOO_LARGE: String =
        "内容较大，系统分享带不动它。请先保存到 Download，再从文件管理器里分享。"

    /** The artifact vanished between the export and the button. */
    private fun unreadableSentence(): String =
        "这份导出已经不在了（可能被清理掉了）。请重新导出一次。"
}
