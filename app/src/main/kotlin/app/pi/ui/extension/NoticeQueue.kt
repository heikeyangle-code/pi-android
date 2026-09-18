package app.pi.ui.extension

/**
 * The notice queue's eviction rule: keep at most [max] entries — **newest last, and never
 * drop the head**.
 *
 * ## The defect this exists for
 *
 * `pushNotice` used to append with `(notices + notice).takeLast(MAX_PENDING_NOTICES)`,
 * which drops from the **front**. The host draws one snackbar at a time, oldest first, and
 * consumes an entry only after `showSnackbar` returns (`ExtensionUiHost`), so while a
 * message is on screen it is always the head of this list. Dropping the front therefore
 * dropped *the message the user was reading*, and the host's effect — keyed on the head's
 * sequence — restarted, canceling the in-flight `showSnackbar` and replacing the visible
 * snackbar with the next one. Nine notices inside one snackbar's duration was enough to
 * make a sentence the user was reading disappear mid-word.
 *
 * The fix is one rule, stated where a bare JVM can pin it: an overflowing queue evicts the
 * oldest **pending** entry, and the head is never a candidate. The head is what the host is
 * showing or about to show; the entries behind it have not been read by anyone.
 *
 * Pure and generic (no Compose, no `ExtensionNotice`) so
 * `tools/run-app-pure-checks.sh` → `notice-queue` can exercise it with plain values.
 */
internal fun <T> trimNoticeQueue(entries: List<T>, max: Int): List<T> {
    if (entries.size <= max) return entries
    if (max <= 0) return emptyList()
    // `entries.first()` is the notice on screen; the rest are evicted from their own
    // front, i.e. oldest-pending-first, which is the order the host will show them in.
    return ArrayList<T>(max).apply {
        add(entries.first())
        addAll(entries.takeLast(max - 1))
    }
}
