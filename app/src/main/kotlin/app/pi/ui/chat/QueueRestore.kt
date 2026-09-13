package app.pi.ui.chat

/**
 * pi's cancel/dequeue merge rule, in one place.
 *
 * `restoreQueuedMessagesToEditor` (`modes/interactive/interactive-mode.ts:4387-4406`)
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
 * `interactive-mode.ts:2854`) and the queue row's dequeue (`handleDequeue`, `:4157-4164`,
 * **no** abort). Both must merge identically, and `ChatScreen.kt` imports Compose —
 * which the bare-JVM harness cannot compile — so the rule lives here and is pinned by
 * `QueueRestoreCheck` (`tools/run-app-pure-checks.sh`, `queue-restore`). Android-free:
 * the Kotlin stdlib only.
 */
fun mergeRestoredQueue(restored: List<String>, current: String): String =
    listOf(restored.filter { it.isNotBlank() }.joinToString("\n\n"), current)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
