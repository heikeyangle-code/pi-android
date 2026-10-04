package app.pi.ui.chat

import android.content.Context
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
 * export lands in the user's cwd (`core/export-html/index.ts:191-196`) and its TUI
 * reports that path (`interactive-mode.ts:5380-5388`), so the file is reachable on
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
 * ## 两份：保存要把两份都写出去
 *
 * [ExportedSession] 是 HTML 与会话文件本身（逐字节，能导回来），所以 [saveToDownloads] 逐份写；
 * 分享面板只带得动一个文本块，所以 [share] 分享 HTML，会话文件那份走「保存到 Download」。
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

    /**
     * Save every artifact of [exported] to the public Download folder.
     *
     * 走 `export(sourceFile = …)` 而不是把文件编码成 base64 再交给它：一份导出的会话可以有
     * 几 MB，而 `readBytes()` → base64 字符串（≈4/3 倍）→ `export` 里再 decode 回来，
     * 是同一条数据的**三份**同时驻留（峰值 ≈ 3.3 倍文件大小）；`sourceFile` 让 `export`
     * 自己读一次，只剩一份。读不出来时由 `export` 自己抛出的 `DeviceActionException` 说原因
     * （文件不在了、没权限、空间不够），这里不再另写一句去猜。
     */
    fun saveToDownloads(context: Context, exported: ExportedSession): Result = try {
        for (file in exported.files) {
            DeviceSystemActions.export(
                context = context,
                name = file.name,
                text = null,
                base64 = null,
                mimeType = file.mimeType,
                sourceFile = File(file.path),
            )
        }
        Result("已保存到 Download：${exported.files.joinToString("、") { it.name }}")
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

    /**
     * Open the share sheet with the **HTML** (the human-readable half).
     *
     * 分享通道只带一个文本块，所以这里只发 HTML；要导回来的那份用「保存到 Download」取。
     */
    fun share(context: Context, exported: ExportedSession): Result = try {
        val html = exported.files.firstOrNull { it.mimeType == HTML_MIME } ?: exported.files.first()
        val file = File(html.path)
        when {
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
                        subject = html.name,
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

    private const val HTML_MIME: String = "text/html"

    /**
     * The one action that still works when the text is too big for a share intent:
     * save it to Download, then share it from there. Named once so the two size
     * checks cannot drift apart.
     */
    private const val TOO_LARGE: String =
        "内容较大，系统分享带不动它。请先保存到 Download，再从文件管理器里分享。"
}
