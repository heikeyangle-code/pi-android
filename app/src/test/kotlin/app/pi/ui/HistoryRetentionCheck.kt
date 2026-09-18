package app.pi.ui

// A bare-JVM harness for the retained-history accounting. Registered in
// `tools/run-app-pure-checks.sh` as `history-retention`.
//
// Why this is worth pinning: `PiSessionViewModel` decides whether to keep reading
// older history from a running character total (`HISTORY_RETAINED_CHARS`), and that
// total is now accumulated *incrementally* (each window's own count is added) instead
// of re-summed over everything retained. Incremental accounting is correct only while
// `entryCharsOf` is additive, so the additivity is asserted here rather than assumed —
// a re-sum that silently disagreed with the sum would either stop the reader early
// (history the user cannot reach) or let the retention cap be exceeded (the OOM this
// budget exists to prevent).
//
// The second thing pinned is what the number *is*: the **serialised** length of an
// entry, not the length of its own text field. The cap is a budget for holding the
// entry, and an inline image's base64 lives in the entry's message, so a measurement
// that skimped on it would let a session of images past the cap.

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun textEntry(text: String): JsonObject = buildJsonObject {
    put("type", "message")
    put("id", "id-1")
    put("timestamp", "2024-01-01T00:00:00.000Z")
    putJsonObject("message") {
        put("role", "assistant")
        putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        }
    }
}

private fun imageEntry(base64: String): JsonObject = buildJsonObject {
    put("type", "message")
    put("id", "id-2")
    putJsonObject("message") {
        put("role", "user")
        putJsonArray("content") {
            add(buildJsonObject { put("type", "image"); put("data", base64) })
        }
    }
}

fun main() {
    // --- what the number is ---------------------------------------------------------
    //
    // The measurement is `message.toString().length + 64`, so a message entry's count is
    // always larger than its own text plus the envelope: the JSON it is measured as
    // carries the wrapper as well.
    val entry = textEntry("hello")
    check("a message entry is measured as its JSON, not as its text", entryChars(entry) > 5L + 64L, true)
    check(
        "the envelope is the constant, and the payload is the JSON",
        entryChars(entry),
        entry["message"]!!.toString().length.toLong() + 64L,
    )

    // An image's payload is the biggest thing an entry can carry and it must be counted:
    // a measurement that skipped it would let a window of images past the retention cap.
    val base64 = "A".repeat(4096)
    check("an inline image's base64 is counted", entryChars(imageEntry(base64)) > 4096L, true)

    // A non-`message` entry takes the other branch: the whole entry, serialised.
    val custom = buildJsonObject { put("type", "custom"); put("customType", "note"); put("data", "x".repeat(128)) }
    check("a non-message entry is the whole entry, serialised", entryChars(custom), custom.toString().length.toLong() + 64L)

    // --- the property the incremental accumulator relies on -------------------------
    //
    // `expandEarlierHistory` adds the newly read window's count to the running total
    // instead of re-summing the whole retained list. That is the same number only
    // because the measure is additive over a concatenation; if it ever stops being (a
    // per-entry overlap, a shared envelope, a prefix rule), the cap silently diverges.
    val older = listOf(textEntry("a".repeat(64)), imageEntry("B".repeat(512)))
    val newer = listOf(textEntry("b".repeat(32)), imageEntry("C".repeat(256)), textEntry(""))
    check(
        "incremental equals the full sum",
        entryCharsOf(older) + entryCharsOf(newer),
        entryCharsOf(older + newer),
    )
    check(
        "the increment does not depend on which half is first",
        entryCharsOf(older) + entryCharsOf(newer),
        entryCharsOf(newer) + entryCharsOf(older),
    )

    // The empty cases, because both halves of the reader can produce an empty window and
    // the view model assigns the result straight into the running total.
    check("an empty window adds nothing", entryCharsOf(emptyList()), 0L)
    check("one entry is its own window", entryCharsOf(listOf(entry)), entryChars(entry))

    // A whole-session replay hands every entry at once; the sum must not overflow or
    // truncate on the way (it is a `Long`, and the cap it feeds is 64 MiB).
    val many = List(500) { textEntry("x".repeat(1000)) }
    check("500 entries are summed, not truncated", entryCharsOf(many) > 500L * 1000L, true)

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
