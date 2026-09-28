package app.pi.ui

/**
 * Who owns the AppBar's status line — the *busy verb* `PiSessionViewModel.engineLabel`
 * shows while a command is in flight (「读取会话树」, 「切换会话」, 「导入会话」…).
 *
 * ## The defect this exists to remove
 *
 * The line used to be owned by its **label**: `call()` wrote `busy = label` when the
 * command started and cleared it in `finally` only when the state still read that same
 * label. That is sound while every call has a distinct label, and wrong the moment two
 * calls carry the same one — which the app does on purpose, in one place:
 * `refreshTree()`'s coalescing dispatches its follow-up `get_tree` **from inside the
 * previous read's own block `finally`**, i.e. before the previous `call`'s `finally` has
 * run, under the same 「读取会话树」. The first call then sees its own label on the line
 * (the second call put it there), clears it, and the AppBar drops to 「就绪」 in the middle
 * of a read that has not finished; the second call's own clear then finds nothing to clear.
 * One spurious 「读取会话树 → 就绪」 per coalesced pair, produced by the app alone — the
 * exact pair the user reported as "读取会话树和就绪疯狂来回刷".
 *
 * ## The rule
 *
 * Ownership is a **token**, not a label: [claim] returns a new, never-reused one and every
 * release asks whether that token still owns the line. A stale claim's release is therefore
 * a no-op whatever label it carried, and "the newest claim owns the line" is true by
 * construction rather than by a string comparison that two callers can satisfy at once.
 *
 * Pure logic on purpose (no Android, no coroutines, no `UiState`): the mechanism is three
 * counters, and `app/src/test/kotlin/app/pi/ui/EngineStatusLineCheck.kt` pins it without a
 * device — see `tools/run-app-pure-checks.sh`.
 */
internal class EngineStatusLine {

    /** The last token handed out. Monotonic, so a released token can never match again. */
    private var seq = 0L

    /** The token that owns the line, or 0 when nobody does. */
    private var owner = 0L

    /** Take the line and return the token its release needs. */
    fun claim(): Long {
        val token = ++seq
        owner = token
        return token
    }

    /**
     * Give the line back — and report whether [token] still held it.
     *
     * `false` means a newer claim owns the line now (or this token was already released),
     * so the caller must leave `busy` alone: the operation that is still running is not
     * this one.
     */
    fun release(token: Long): Boolean {
        // 0 is the "nobody holds it" sentinel, never a token `claim()` handed out, so a
        // release for it must be refused even when the line is free — otherwise the
        // sentinel would read as "the current owner" exactly when nobody owns the line.
        if (token == 0L || owner != token) return false
        owner = 0L
        return true
    }

    /** Whether the line is currently held. Visible for a caller that needs to ask. */
    val held: Boolean get() = owner != 0L
}
