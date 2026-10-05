package app.pi.ui.chat

/**
 * pi's cancel/dequeue merge rule, in one place.
 *
 * `restoreQueuedMessagesToEditor` (`modes/interactive/interactive-mode.ts:4702-4721`)
 * puts the queued text **and** the text already in the editor back into the editor:
 *
 * ```
 * const combinedText = [queuedText, currentText].filter((t) => t.trim()).join("\n\n");
 * ```
 *
 * Queued messages come first (all of them, `[...steering, ...followUp]`), the text
 * already in the editor follows, and blank halves are dropped — so pressing the
 * action twice does not accumulate blank lines and does not lose the draft.
 *
 * ## Why it is no longer private to `ChatScreen`
 *
 * Two actions use it now: Stop (`restoreQueuedMessagesToEditor({ abort: true })`,
 * `interactive-mode.ts:3052`) and the queue row's dequeue (`handleDequeue`, `:4467-4472`,
 * **no** abort). Both must merge identically, and `ChatScreen.kt` imports Compose —
 * which the bare-JVM harness cannot compile — so the rule lives here and is pinned by
 * `QueueRestoreCheck` (`tools/run-app-pure-checks.sh`, `queue-restore`). Android-free:
 * the Kotlin stdlib only.
 */
fun mergeRestoredQueue(restored: List<String>, current: String): String =
    listOf(restored.filter { it.isNotBlank() }.joinToString("\n\n"), current)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

/**
 * What pi's `clear_queue` handed back, split by whether it can be put back at all.
 *
 * pi's queue is **text only**: `clear_queue` answers with
 * `{steering: string[], followUp: string[]}` (`core/agent-session.ts:2355-2362`), so a
 * message that carried an image and no caption comes back as an empty string — the
 * entry is real, its text is not. [mergeRestoredQueue] then drops it (correctly: there
 * is nothing to write) and the attachment is gone, which is why the caller needs this
 * split to say so instead of losing it silently.
 *
 * Not part of the merge itself: pi's TUI has no attachment concept, so it has no such
 * case, and the merge rule stays a one-to-one transcription of pi's three lines. This
 * is the app's own bookkeeping about a channel pi's terminal never had — the RPC
 * surface accepts `images` on `prompt`/`steer`/`follow_up` (`rpc-types.ts:22-24`).
 */
data class RestoredQueue(val withText: Int, val withoutText: Int) {
    /** Nothing was queued at all — pi's `handleDequeue` answers this with a status. */
    val isEmpty: Boolean get() = withText == 0 && withoutText == 0
}

/** Classify [restored]; see [RestoredQueue]. */
fun summarizeRestoredQueue(restored: List<String>): RestoredQueue =
    RestoredQueue(
        withText = restored.count { it.isNotBlank() },
        withoutText = restored.count { it.isBlank() },
    )
