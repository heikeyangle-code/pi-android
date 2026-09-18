package app.pi.ui.chat

/**
 * What one `@` lookup produced, and which of the guest's failure shapes it was.
 *
 * ## The silence this removes
 *
 * `PiMentionSource` used to answer `emptyList()` for three unrelated facts: fd matched
 * nothing, the runtime was not provisioned, and the `fd` run itself failed (a proot launch
 * error, a 10 s timeout, a process that went away). The composer draws nothing for an empty
 * list — which is pi's own behaviour for "no suggestions" (`autocomplete.ts:305`) and the
 * right answer for exactly one of those three. For the other two it is a lie of omission:
 * `@` looks like it found nothing when in truth nothing was ever looked up.
 *
 * The fix is not to change what fd's non-zero exit means — pi treats any non-zero exit as
 * "no candidates" (`autocomplete.ts:196-200`, and `PiMentionSource`'s own KDoc argues the
 * same) — but to separate the two facts the guest can actually report: the command ran and
 * matched nothing, or the command never produced an answer.
 *
 * ## Why the classification is pure
 *
 * `PiMentionSource` holds an Android `Context`, so it cannot be executed on this machine.
 * The decision — which failure is which, and what the user is told — can, and the harness
 * `mentions-unavailable` pins every arm of it, including the one that must stay `null`
 * (a real answer, however empty).
 */
internal sealed interface MentionLookup {
    /**
     * The guest answered. [items] may be empty: fd matched nothing (or is not installed),
     * which is pi's "no suggestions" and is not a failure.
     */
    data class Candidates(val items: List<PiFileMentions.Item>) : MentionLookup

    /**
     * The lookup could not be performed. [sentence] is what the user is told — once per
     * distinct failure, not once per keystroke (the composer asks on a 150 ms debounce).
     */
    data class Unavailable(val sentence: String) : MentionLookup
}

/**
 * The sentence for a lookup that could not run, or **null when the guest gave a real
 * answer** (including "nothing matched").
 *
 * @param runtimeReady `AgentLayout.runtimeReady()` — the runtime this app unpacks on first
 *   launch. False means no `fd` exists to run at all.
 * @param timedOut the run was abandoned at its timeout.
 * @param exitCode the process's exit status, or null when it never started / was killed.
 * @param launchError `GuestCommand.Outcome.launchError` — the runtime itself would not
 *   start (a proot failure).
 * @param stderr the guest's stderr, used only as the detail of a launch failure.
 */
internal fun mentionUnavailableSentence(
    runtimeReady: Boolean,
    timedOut: Boolean,
    exitCode: Int?,
    launchError: String?,
    stderr: String,
): String? = when {
    !runtimeReady -> mentionRuntimeNotReadySentence()

    launchError != null ->
        "@ 提及失败：运行时没能启动（${detailOf(launchError, stderr)}）。"

    timedOut ->
        "@ 提及超时：fd 在 10 秒内没有返回。工作区太大时可能出现，可以缩小目录范围或直接输入路径。"

    exitCode == null ->
        "@ 提及失败：fd 被中断，没有返回任何结果。"

    // fd ran and exited: pi treats *any* non-zero exit as "no candidates"
    // (`autocomplete.ts:196-200`), so this is an answer and not a failure. The composer
    // shows no list for it, which is also what pi does.
    else -> null
}

/**
 * The one failure that is knowable **before** anything runs, and therefore the one whose
 * sentence is not nullable.
 *
 * `AgentLayout.runtimeReady()` is a fact about the install, not about a command, so the
 * caller can return this without asking the classifier and without asserting anything the
 * compiler cannot see. The classifier routes its own `!runtimeReady` arm through the same
 * function, so there is still exactly one wording for it.
 */
internal fun mentionRuntimeNotReadySentence(): String =
    "@ 提及暂时用不了：运行时还没就绪，无法运行 fd。装好或重启引擎后再试。"

/** The launch failure's own words, with stderr as the fallback — one bounded line. */
private fun detailOf(launchError: String, stderr: String): String {
    val text = launchError.ifBlank { stderr }.trim().replace('\n', ' ')
    return if (text.isEmpty()) "原因未知" else text.take(160)
}
