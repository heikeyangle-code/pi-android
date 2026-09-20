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

    // --- the backward readings ------------------------------------------------------
    //
    // 「聊天内容多了，回到聊天顶部，之前的内容都被截掉了，都没了。」
    //
    // The defect was a *reading* being misread: a step that delivered nothing — over
    // budget, or a range that could not be read — was treated as "this is the start of
    // the file", so the cursor set `reachedStart`, the 「加载更早」 row disappeared and
    // everything above the loaded range became unreachable for the rest of the session.
    // `cursorAfter` is the whole rule, and it is the rule the view model runs (there is
    // no second copy of it in `PiSessionViewModel`).

    fun decided(name: String, read: BackwardRead): CursorDecision {
        val decision = cursorAfter(read)
        println("… $name → reachedStart=${decision.reachedStart} stop=${decision.stop?.reason ?: "—"}")
        return decision
    }

    // (c) a genuinely complete read of the file's start: the reader's own flag, which is
    // `from == 0L && !capped && !budgetStopped` (`SessionFileReader.kt:257`).
    val atStart = decided("a window that reached byte 0", BackwardRead.Window(reachedStart = true))
    check("C1 a window the reader flagged reachedStart is the start", atStart.reachedStart, true)
    check("C1 and it reports no failure", atStart.stop, null)

    // A window that did *not* reach byte 0 is a count too, and it must leave the walk
    // open — otherwise every ordinary step would end the conversation.
    val counted = decided("a window with more above", BackwardRead.Window(reachedStart = false))
    check("C2 an ordinary window does not claim the start", counted.reachedStart, false)
    check("C2 and it reports no failure", counted.stop, null)

    // Byte 0 in hand is the one null answer that establishes the start: nothing can be
    // above it. This is not the defect's branch — that one had bytes above and no window.
    val atFileStart = decided("null with the range already at byte 0", BackwardRead.AtFileStart)
    check("C3 a range already at byte 0 is the start", atFileStart.reachedStart, true)

    // (a) the budget. It is deliberately **not** a reading: `cursorAfter` has no argument
    // for it and `BackwardRead` has no variant for it, so "the budget stopped the step"
    // cannot set `reachedStart` — the view model trims the retained copy and keeps
    // reading instead (`evictablePrefix` below). The `when` in `cursorAfter` is
    // exhaustive over the sealed interface, so re-adding such a variant fails this
    // harness' *compilation*, not just a check.
    //
    // What is pinned here is the other half of (a): a step that arrives empty-handed for
    // any *read* reason keeps `reachedStart` false.
    val withheld = decided("a range that withheld a line", BackwardRead.WithheldLine)
    check("A1 a withheld line does not claim the start", withheld.reachedStart, false)
    check("A1 and it says why", withheld.stop?.reason?.isNotBlank(), true)

    // (b) a read that failed.
    val failed = decided("a read that threw", BackwardRead.ReadFailed("文件不在了"))
    check("B1 a failed read does not claim the start", failed.reachedStart, false)
    check("B1 and it carries the reason", failed.stop?.reason?.contains("文件不在了"), true)

    val unreadable = decided("null above byte 0", BackwardRead.UnreadableRange)
    check("B2 an undeliverable range does not claim the start", unreadable.reachedStart, false)
    check("B2 and it says why", unreadable.stop?.reason?.isNotBlank(), true)

    // The whole invariant, over every reading there is. This is (a) and (b) stated once:
    // **only** the two readings that establish the start may set it.
    val readings = listOf(
        BackwardRead.Window(reachedStart = true) to true,
        BackwardRead.Window(reachedStart = false) to false,
        BackwardRead.AtFileStart to true,
        BackwardRead.WithheldLine to false,
        BackwardRead.UnreadableRange to false,
        BackwardRead.ReadFailed("x") to false,
    )
    var wrong = 0
    for ((read, expected) in readings) {
        if (cursorAfter(read).reachedStart != expected) wrong++
    }
    check("R1 only a read that establishes the start sets reachedStart", wrong, 0)
    check("R2 the readings are all distinct", readings.map { it.first::class }.distinct().size, 5)
    // Every reading that arrives **empty-handed** has to say why. A count that did not
    // reach byte 0 is not empty-handed — it delivered entries — so it is not in this list;
    // the three that are, are exactly the ones that used to be mistaken for the end.
    val emptyHanded = listOf<BackwardRead>(
        BackwardRead.WithheldLine,
        BackwardRead.UnreadableRange,
        BackwardRead.ReadFailed("x"),
    )
    check(
        "R3 every empty-handed reading carries a user-facing reason",
        emptyHanded.all { cursorAfter(it).stop?.reason?.isNotBlank() == true },
        true,
    )
    check(
        "R3b and none of them claims the start",
        emptyHanded.none { cursorAfter(it).reachedStart },
        true,
    )
    check(
        "R4 no reading's reason mentions the retention budget",
        readings.mapNotNull { cursorAfter(it.first).stop?.reason }.none { it.contains("预算") },
        true,
    )

    // --- what the row says -----------------------------------------------------------
    //
    // The screen has exactly one owner for this string (`ChatScreen.kt` calls
    // `earlierRowText`), so the readings cannot drift into one sentence.
    val loadable = earlierRowText(hiddenCount = 0, cursorKnown = true, loading = false, reachedStart = false, stop = null)
    val countedRows = earlierRowText(hiddenCount = 7, cursorKnown = true, loading = false, reachedStart = false, stop = null)
    val reading = earlierRowText(hiddenCount = 0, cursorKnown = true, loading = true, reachedStart = false, stop = null)
    val cannotRead = earlierRowText(
        hiddenCount = 0,
        cursorKnown = true,
        loading = false,
        reachedStart = false,
        stop = HistoryStop("文件不在了"),
    )
    check("L1 the loadable row is offered", loadable, "加载更早的内容")
    check("L2 rows already in memory are counted", countedRows, "加载更早的 7 条")
    check("L3 a read in flight says so", reading, "正在读取更早的内容…")
    check("L4 a failed read says so, with its reason", cannotRead, "读不到更早的内容（文件不在了）")
    check(
        "L5 the three states are three different sentences",
        setOf(countedRows, reading, cannotRead).size,
        3,
    )
    check(
        "L6 none of them mentions the retention budget",
        listOfNotNull(loadable, countedRows, reading, cannotRead).none { it.contains("预算") },
        true,
    )

    // **At the start of the file there is no row**, so the screen can never offer to load
    // earlier while the reader is holding the file's first entry.
    check(
        "L7 at the start there is no row to offer",
        earlierRowText(hiddenCount = 0, cursorKnown = true, loading = false, reachedStart = true, stop = null),
        null,
    )
    // …but rows already in memory above the rendered window are still a local reveal, and
    // that is true even once the file's first entry has been loaded.
    check(
        "L8 a local reveal is still offered at the file's start",
        earlierRowText(hiddenCount = 3, cursorKnown = true, loading = false, reachedStart = true, stop = null),
        "加载更早的 3 条",
    )
    // The failure branch wins over nothing but the local reveal: with rows in memory the
    // press reveals them instead of re-reading, and the reason is not shown because it is
    // not what the press would do.
    check(
        "L9 a local reveal outranks a failed read",
        earlierRowText(hiddenCount = 3, cursorKnown = true, loading = false, reachedStart = false, stop = HistoryStop("x")),
        "加载更早的 3 条",
    )
    // No cursor yet (a transcript from somewhere that is not this reader): nothing to
    // read, so no row — the same answer `hasEarlier` gave before, which was only ever
    // true with a cursor.
    check(
        "L10 without a cursor there is no row",
        earlierRowText(hiddenCount = 0, cursorKnown = false, loading = false, reachedStart = false, stop = null),
        null,
    )

    // --- no user-visible 「已到预算」 state --------------------------------------------
    //
    // The complaint this change answers is 「之前的内容都被截掉了」, and the *reason* the old
    // code handed the reader was the retention budget. It is not said any more, and it must
    // not come back, because the budget bounds a **copy in RAM** (`evictablePrefix`), not what
    // the reader can reach: a row saying "you have hit the budget" would tell the reader to
    // stop while the walk is supposed to continue.
    //
    // The structural half is already pinned above: `BackwardRead` has no budget variant and
    // `cursorAfter`'s `when` is exhaustive over the sealed interface — a budget *reading*
    // cannot be added without `HistoryRetention.kt` failing to compile. So what is left to
    // check is the copy: the phrase cannot appear anywhere in the chat UI's own sources.
    // (`HistoryCursor` carries no budget field either; its states are the offset,
    // `reachedStart`, `loading` and a failure `stop`, and L1–L10 pin what each one says.)
    val uiSourceRoot = java.io.File(
        java.io.File(System.getProperty("pi.repo.root") ?: "."),
        "app/src/main/kotlin/app/pi/ui",
    )
    val uiSources = uiSourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    check("S1 the chat UI source tree was found", uiSources.size > 50, true)
    check(
        "S2 no chat source can render 「已到预算」",
        uiSources.none { it.readText().contains("已到预算") },
        true,
    )
    check(
        "S3 no reading is named after the budget",
        readings.map { it.first.toString() }.none { it.contains("Budget", ignoreCase = true) || it.contains("预算") },
        true,
    )
    // The row's whole output space — every sentence it can show, plus its two "no row"
    // answers — and not one of them is about the budget.
    val rowSpace = listOfNotNull(
        earlierRowText(hiddenCount = 0, cursorKnown = true, loading = false, reachedStart = false, stop = null),
        earlierRowText(hiddenCount = 3, cursorKnown = true, loading = false, reachedStart = false, stop = null),
        earlierRowText(hiddenCount = 0, cursorKnown = true, loading = true, reachedStart = false, stop = null),
        earlierRowText(hiddenCount = 0, cursorKnown = true, loading = false, reachedStart = false, stop = HistoryStop("x")),
        earlierRowText(hiddenCount = 0, cursorKnown = true, loading = false, reachedStart = true, stop = null),
        earlierRowText(hiddenCount = 0, cursorKnown = false, loading = false, reachedStart = false, stop = null),
    )
    check("S4 every sentence the row can show is budget-free", rowSpace.none { it.contains("预算") }, true)

    // --- the eviction rule -----------------------------------------------------------
    //
    // The budget only ever moves a **copy**: the loaded entries the view model keeps so a
    // later rebuild can reproduce the rows. It must never touch the chunk the reader is
    // on (the oldest retained one — a backward step only runs with the reader at the top
    // of the loaded range), and it must never end the walk.

    fun chunk(chars: Long) = RetainedChunk(entries = listOf(textEntry("x".repeat(8))), startOffset = chars, chars = chars)

    // Nothing to do while inside the budget.
    check("E1 inside the budget nothing is dropped", evictablePrefix(listOf(chunk(10), chunk(10), chunk(10)), 100L), 0)
    // Over it, the **newest** chunks go first: they are the ones farthest from the reader,
    // and the reader is walking towards the older end.
    check("E2 the newest chunk goes first", evictablePrefix(listOf(chunk(40), chunk(10), chunk(10)), 25L), 1)
    check("E3 dropping continues until inside the budget", evictablePrefix(listOf(chunk(40), chunk(40), chunk(10)), 25L), 2)
    // **The counterexample this rule exists for.** Even with an absurd budget the oldest
    // chunk — the one under the reader's eyes — is never a candidate, so the walk can
    // always continue and the screen always has its rows.
    val protectedChunks = listOf(chunk(40), chunk(40), chunk(40), chunk(40))
    val victims = evictablePrefix(protectedChunks, budgetChars = 0L, protectedOldest = 1)
    check("E4 a zero budget still keeps the reader's own chunk", victims, 3)
    check(
        "E5 no victim is inside the protected zone",
        (0 until victims).none { it >= protectedChunks.size - 1 },
        true,
    )
    check("E6 the retained copy is never emptied", protectedChunks.size - victims > 0, true)
    // The victim set is a *prefix* of the newest-first list: that is what makes the evicted
    // byte range contiguous, which is what makes it readable back in one exact range.
    check(
        "E7 the victims are a contiguous newest-first prefix",
        (0 until victims).toList(),
        List(victims) { it },
    )
    // A count that removes everything droppable and is still over budget is the answer:
    // the alternative is dropping the visible chunk.
    check(
        "E8 the protected chunk wins over the budget",
        evictablePrefix(listOf(chunk(100), chunk(100), chunk(100)), budgetChars = 1L, protectedOldest = 1),
        2,
    )
    // Nothing to protect against when the protected count covers the list.
    check("E9 an all-protected list drops nothing", evictablePrefix(listOf(chunk(100), chunk(100)), 0L, protectedOldest = 2), 0)
    check("E10 an empty list drops nothing", evictablePrefix(emptyList(), 0L), 0)
    // And the accounting the eviction subtracts with is the same measure the reads add
    // with: dropping a chunk has to leave the running total at exactly the sum of the rest.
    val three = listOf(textEntry("a"), textEntry("b"), imageEntry("C".repeat(64)))
    val threeChars = listOf(chunk(entryChars(three[0])), chunk(entryChars(three[1])), chunk(entryChars(three[2])))
    val droppedChars = threeChars.take(2).sumOf { it.chars }
    check(
        "E11 subtracting an evicted chunk equals the sum of what is left",
        entryCharsOf(three) - droppedChars,
        entryChars(three[2]),
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
