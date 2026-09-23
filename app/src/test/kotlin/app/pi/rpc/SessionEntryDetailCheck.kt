package app.pi.rpc

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

// A bare-JVM harness for the session tree's text for **unmodelled** entry types.
// Registered in `tools/run-app-pure-checks.sh` as `session-entry-detail`.
//
// Why it exists: `SessionEntries.kt` keeps an entry type a newer pi introduced as
// `SessionEntry.Unknown` with its `raw` object (data preserved, on purpose), and the tree
// screen used to render that as `raw.toString()` — a wall of JSON. `SessionEntrySummary`
// replaced it with pi's own sentence for `context_edit` (`tree-selector.js:486`) and a
// `key=value` field list for anything else, and this file pins both:
//
//   * the omit/replace decision, including the two shapes of "no replacement" (`null` vs
//     the key being absent) — the branch pi's own code spells `entry.replacement === null`;
//   * that the metadata keys are gone from the dump (a row that repeats `id`/`timestamp`
//     buries the field the reader has not seen);
//   * that a nested value is named by shape, not printed (`<object>`, `<array(2)>`), so a
//     future entry type cannot turn a 160dp block into a JSON viewer;
//   * that `isSettingsEntry` covers exactly the unmodelled half of pi's
//     settings/bookkeeping set, which is what keeps `context_edit` out of the default
//     filter.
//
// The negative half matters as much as the positive: a *completely unknown* type must render
// as a field list (never as raw JSON, never as an empty string), because the next pi release
// is what this code is for.
//
// Android-free: kotlinx.serialization and the Kotlin stdlib, compiled together with
// `:rpc`'s `PiJson`/`SessionEntries` closure.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** One raw entry as pi writes it: metadata plus the type's own fields. */
private fun raw(vararg pairs: Pair<String, Any?>): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("type", "placeholder")
    put("id", "e1")
    put("parentId", "e0")
    put("timestamp", "2026-09-23T00:00:00.000Z")
    for ((key, value) in pairs) {
        when (value) {
            null -> put(key, JsonNull)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is kotlinx.serialization.json.JsonElement -> put(key, value)
            else -> error("unsupported harness value for $key")
        }
    }
}

fun main() {
    // ---------------------------------------------------------------- context_edit
    val omitExplicit = SessionEntrySummary.contextEditLine(
        raw("type" to "context_edit", "targetId" to "msg-42", "replacement" to null),
    )
    check(
        "context_edit: an explicit null replacement reads as omit and names the target",
        omitExplicit,
        "context omit: msg-42（收回了该条目对模型上下文的贡献）",
    )

    // pi always writes the key, but a hand-written or older record may leave it out; the
    // two are the same statement and must not render differently.
    val omitAbsent = SessionEntrySummary.contextEditLine(raw("type" to "context_edit", "targetId" to "msg-42"))
    check("context_edit: an absent replacement reads the same as null", omitAbsent, omitExplicit)

    val replace = SessionEntrySummary.contextEditLine(
        raw(
            "type" to "context_edit",
            "targetId" to "msg-7",
            "replacement" to buildJsonObject { put("content", "rewritten") },
        ),
    )
    check("context_edit: a replacement reads as replace", replace, "context replace: msg-7（改写了该条目的上下文内容）")

    check(
        "context_edit: a missing targetId still renders one readable line",
        SessionEntrySummary.contextEditLine(raw("type" to "context_edit", "replacement" to null)),
        "context omit: (未给出 targetId)（收回了该条目对模型上下文的贡献）",
    )

    // `unknownDetail` is what the screen calls, so the dispatch itself is asserted too.
    check(
        "unknownDetail routes context_edit to pi's sentence",
        SessionEntrySummary.unknownDetail("context_edit", raw("type" to "context_edit", "targetId" to "x")),
        SessionEntrySummary.contextEditLine(raw("type" to "context_edit", "targetId" to "x")),
    )

    // ---------------------------------------------------------------- generic body
    val future = SessionEntrySummary.unknownBody(
        raw(
            "type" to "some_future_entry",
            "note" to "hello",
            "count" to 3,
            "flag" to true,
            "payload" to buildJsonObject { put("nested", 1) },
            "ids" to kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("a")) },
        ),
    )
    check(
        "a future entry renders one key=value per line, in pi's order",
        future,
        "note=hello\ncount=3\nflag=true\npayload=<object>\nids=<array(1)>",
    )
    check("the metadata keys are not repeated", future.contains("id="), false)
    check("the timestamp is not repeated", future.contains("timestamp="), false)
    check("the type is not repeated as a field", future.contains("type=some_future_entry"), false)
    check("a nested object is named, not printed", future.contains("nested"), false)
    check("never raw JSON", future.startsWith("{"), false)

    // The property this whole file exists for: *any* unknown type is readable.
    val blank = SessionEntrySummary.unknownBody(raw("type" to "another_future_entry"))
    check(
        "an unknown type with no extra fields still says something",
        blank,
        "（another_future_entry 条目没有其它字段）",
    )
    check("an empty body is never returned", blank.isNotEmpty(), true)

    // A value with newlines must not break the one-line-per-field promise, and a very long
    // value must not push the whole block off a 160dp pane.
    val messy = SessionEntrySummary.unknownBody(
        raw("type" to "future", "text" to "first\nsecond", "long" to "x".repeat(200)),
    )
    check("a multi-line value stays on one line", messy.lines().size, 2)
    check("newlines become spaces", messy.contains("first second"), true)
    check("a long value is cut at 80 characters", messy.lines()[1].length, "long=".length + 80)

    // ---------------------------------------------------------------- the filter half
    check("context_edit is a settings entry (hidden by the default filter)", SessionEntrySummary.isSettingsEntry("context_edit"), true)
    check("a type nobody knows is NOT silently hidden", SessionEntrySummary.isSettingsEntry("some_future_entry"), false)
    check("a null type is not a settings entry", SessionEntrySummary.isSettingsEntry(null), false)
    // The modelled half is matched by class in the screen's filter, so this set must not
    // grow to include them: a second, weaker path for the same statement is how the two
    // drift apart.
    check(
        "the set holds only the unmodelled remainder",
        SessionEntrySummary.SETTINGS_ENTRY_TYPES,
        setOf("context_edit"),
    )

    if (failures > 0) {
        println("session-entry-detail: FAILED ($failures)")
        exitProcess(1)
    }
    println("session-entry-detail: OK — unmodelled entries render as readable text")
}
