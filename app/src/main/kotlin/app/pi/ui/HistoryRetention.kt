package app.pi.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How the retained-history budget counts one session entry.
 *
 * ## Why this is a file of its own
 *
 * `PiSessionViewModel` is an `AndroidViewModel`, so nothing in it can be executed on
 * this machine (`tools/typecheck.sh` has no Android runtime). The measured cost of this
 * function is the reason the view model changed at all — it serialises an entry to take
 * the length of the result — so the *arithmetic* the cap depends on lives here, where
 * `tools/run-app-pure-checks.sh` → `history-retention` can pin it on a bare JVM.
 *
 * ## What the number is
 *
 * **It is the serialised length.** Measuring an entry means rendering it back to JSON
 * and taking that string's length, because the question the cap answers is "how much
 * would holding this entry cost" — and an inline image's base64 is the single biggest
 * thing an entry carries, whether or not its own text field is the thing that holds it.
 * The constant term covers the ids and envelope of a small entry, which the transcript
 * row also holds.
 *
 * ## Why the caller must stay off the frame thread
 *
 * The measurement is expensive: rendering one 6 MiB base64 entry costs ~135 ms and a
 * 4000-entry text window ~460 ms on a desktop JVM (measured), and a phone is slower. All
 * three call sites used to sum it on the frame thread — `viewModelScope` is
 * `Dispatchers.Main.immediate` — so opening a session, and every later "load earlier"
 * (which re-summed the whole retained set), blocked the UI for hundreds of milliseconds
 * to seconds. A cheaper measure would have to re-implement kotlinx's JSON escaping and
 * would silently change what the budget means, so the cost is paid on
 * `Dispatchers.IO` instead.
 */
internal fun entryChars(entry: JsonObject): Long {
    val type = (entry["type"] as? JsonPrimitive)?.content
    val message = entry["message"] as? JsonObject
    if (type == "message" && message != null) {
        return message.toString().length.toLong() + ENTRY_ENVELOPE_CHARS
    }
    return entry.toString().length.toLong() + ENTRY_ENVELOPE_CHARS
}

/**
 * [entryChars] over a window.
 *
 * **This is additive by construction**, and that is the property the view model relies
 * on when a batch arrives: `expandEarlierHistory` used to re-sum the whole retained list
 * on every step (quadratic in how far the reader had scrolled); it now adds the newly
 * read window's own count, which is only the same number because
 * `entryCharsOf(a) + entryCharsOf(b) == entryCharsOf(a + b)`. The harness pins exactly
 * that rather than trusting it.
 *
 * A loop rather than `sumOf`: the sum is over thousands of elements and this avoids one
 * boxed accumulator step per element.
 */
internal fun entryCharsOf(entries: List<JsonObject>): Long {
    var total = 0L
    for (entry in entries) total += entryChars(entry)
    return total
}

/**
 * Characters added to every entry's serialised length.
 *
 * The envelope the transcript row holds besides the payload the two branches above
 * measure: the entry's own `id`, `type` and `timestamp`, plus the row's own fields.
 * A constant rather than a measurement because it is an allowance, not a reading.
 */
private const val ENTRY_ENVELOPE_CHARS = 64L
