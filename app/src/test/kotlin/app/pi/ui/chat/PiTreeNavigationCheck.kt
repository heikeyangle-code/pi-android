package app.pi.ui.chat

// A bare-JVM harness for the pure half of pi's in-session tree navigation
// (`ui/chat/PiTreeNavigation.kt`). It imports only the Kotlin stdlib and
// kotlinx-serialization-json, so `tools/run-app-pure-checks.sh` can compile it
// next to that file — no android.jar, no Compose.
//
// Why pin these four things: each one is a place where a plausible implementation
// differs from pi in a way a user sees, and none of them can be checked on the
// device path here.
//
//  1. the leaf rule (`agent-session.ts:3265-3277`): a user message and a
//     `custom_message` rewind to the entry's *parent*, everything else lands *on*
//     the entry. Getting this backwards moves the conversation to the wrong turn
//     and there is no error anywhere — pi just obeys;
//  2. the summary question and `branchSummary.skipPrompt`
//     (`interactive-mode.ts:5236-5263`);
//  3. the argument text: JSON, because pi splits the command line on the first
//     space only (`agent-session.ts:1333-1335`) and a user's custom instructions
//     contain spaces, quotes and newlines. An escaping bug here either loses the
//     instructions or corrupts the whole command;
//  4. the branch filter. This is the one that matters most on a phone: after a
//     navigation the session file still contains the abandoned path
//     (`session-manager.ts:1374-1416` never deletes entries), so a physical replay
//     would paint messages that are **not** in the model's context. The app must
//     rebuild `getBranch(leafId)` instead.
//
// What it deliberately does not cover: whether the *extension* parses the JSON the
// same way. `pi-android-bridge/index.ts` does it with `JSON.parse(args)`, so the
// round-trip pinned below (parse the built text back) is the strongest statement
// that can be made from Kotlin.
//
// Run it by hand (the same recipe the registered harnesses use):
//
//   see the command line in docs/pi-surface-audit-tools.md §9 D1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun entry(json: String) = Json.parseToJsonElement(json) as JsonObject

fun main() {
    // ------------------------------------------------------- 1. the leaf rule
    // `agent-session.ts:3265-3277`.
    check(
        "a user message rewinds to its parent",
        landingFor(entry("""{"type":"message","message":{"role":"user"}}""")),
        NavigateLanding.BeforeEntry,
    )
    check(
        "an assistant message lands on itself",
        landingFor(entry("""{"type":"message","message":{"role":"assistant"}}""")),
        NavigateLanding.AtEntry,
    )
    check(
        "a tool result lands on itself",
        landingFor(entry("""{"type":"message","message":{"role":"toolResult"}}""")),
        NavigateLanding.AtEntry,
    )
    check(
        "a custom message rewinds to its parent",
        landingFor(entry("""{"type":"custom_message","customType":"x","content":"y"}""")),
        NavigateLanding.BeforeEntry,
    )
    // A branch_summary is what a previous navigation wrote; pi leaves the leaf on it
    // (`session-manager.ts:1395-1416` → `:1061`), so it must NOT be treated as a
    // rewind — otherwise a second navigation would skip over the summary.
    check(
        "a branch summary lands on itself",
        landingFor(entry("""{"type":"branch_summary","summary":"s"}""")),
        NavigateLanding.AtEntry,
    )
    check(
        "a compaction lands on itself",
        landingFor(entry("""{"type":"compaction","summary":"s"}""")),
        NavigateLanding.AtEntry,
    )
    check("a label lands on itself", landingFor(entry("""{"type":"label"}""")), NavigateLanding.AtEntry)
    check("a model change lands on itself", landingFor(entry("""{"type":"model_change"}""")), NavigateLanding.AtEntry)
    check("a missing type lands on itself", landingFor(entry("""{"id":"a"}""")), NavigateLanding.AtEntry)
    // pi tests `entry.message.role === "user"` only under `type === "message"`, so a
    // bare role without the wrapper is not a user message.
    check(
        "a user role without the message wrapper is not a user message",
        landingFor(entry("""{"type":"message","role":"user"}""")),
        NavigateLanding.AtEntry,
    )

    // ------------------------------------------- 2. the summary question
    // `interactive-mode.ts:5236`: the whole prompt is skipped when the setting is on.
    check("the question is asked by default", summaryPromptShown(false), true)
    check("skipPrompt suppresses the question", summaryPromptShown(true), false)
    check("no summary means no summary", wantsSummary(BranchSummaryChoice.NoSummary, false), false)
    check("summarize means summarize", wantsSummary(BranchSummaryChoice.Summarize, false), true)
    check("custom prompt means summarize", wantsSummary(BranchSummaryChoice.SummarizeWithPrompt, false), true)
    // skipPrompt means "always default to no summary" (`interactive-mode.ts:5235-5236`),
    // whatever the user would have picked.
    check("skipPrompt forces no summary", wantsSummary(BranchSummaryChoice.Summarize, true), false)
    check(
        "skipPrompt forces no summary for the custom form too",
        wantsSummary(BranchSummaryChoice.SummarizeWithPrompt, true),
        false,
    )
    check("only the custom form needs instructions", needsCustomInstructions(BranchSummaryChoice.Summarize), false)
    check(
        "the custom form needs instructions",
        needsCustomInstructions(BranchSummaryChoice.SummarizeWithPrompt),
        true,
    )

    // ------------------------------------------------- 3. the argument text
    check(
        "the minimal argument text omits every optional field",
        navigateCommandArgs("ab12cd34", summarize = false),
        """{"targetId":"ab12cd34","summarize":false}""",
    )
    check(
        "summarize is a real boolean",
        navigateCommandArgs("ab12cd34", summarize = true),
        """{"targetId":"ab12cd34","summarize":true}""",
    )
    check(
        "blank instructions are omitted, not sent as an empty string",
        navigateCommandArgs("ab12cd34", summarize = true, customInstructions = "   "),
        """{"targetId":"ab12cd34","summarize":true}""",
    )
    check(
        "a blank label is omitted",
        navigateCommandArgs("ab12cd34", summarize = false, label = ""),
        """{"targetId":"ab12cd34","summarize":false}""",
    )
    // `undefined` is not `false` for `replaceInstructions` (`agent-session.ts:3217-3219`),
    // so an explicit false has to survive as a field.
    check(
        "an explicit false replaceInstructions is kept",
        navigateCommandArgs("ab12cd34", summarize = true, replaceInstructions = false),
        """{"targetId":"ab12cd34","summarize":true,"replaceInstructions":false}""",
    )
    check(
        "every option together",
        navigateCommandArgs(
            "ab12cd34",
            summarize = true,
            customInstructions = "focus on the schema",
            replaceInstructions = true,
            label = "before v2",
        ),
        """{"targetId":"ab12cd34","summarize":true,"customInstructions":"focus on the schema","replaceInstructions":true,"label":"before v2"}""",
    )
    // The escaping that matters: instructions are free text a user typed.
    val nasty = "keep \"quotes\", a \\backslash,\na newline and a\ttab"
    val built = navigateCommandArgs("ab12cd34", summarize = true, customInstructions = nasty)
    check("no raw newline survives into the argument text", built.contains('\n'), false)
    check("no raw tab survives into the argument text", built.contains('\t'), false)
    check("the quotes are escaped", built.contains("keep \\\"quotes\\\""), true)
    val parsed = Json.parseToJsonElement(built) as JsonObject
    check(
        "the instruction text survives a round trip",
        parsed["customInstructions"]!!.jsonPrimitive.content,
        nasty,
    )
    check(
        "the entry id survives a round trip",
        parsed["targetId"]!!.jsonPrimitive.content,
        "ab12cd34",
    )
    // A control character JSON forbids raw.
    check(
        "a control character is escaped",
        navigateCommandArgs("ab", summarize = false, customInstructions = "a\u0001b"),
        """{"targetId":"ab","summarize":false,"customInstructions":"a\u0001b"}""",
    )
    // The command line is one token plus its args, split on the FIRST space only
    // (`agent-session.ts:1333-1335`), so the args must not start with a space.
    check("the argument text starts with a brace", built.startsWith("{"), true)
    // ------------------------------------------------------ 4. the outcome
    check("a refusal wins over a moved leaf", navigateOutcome(null, "x", refused = true), NavigateOutcome.Refused)
    check("a refusal with no reading", navigateOutcome(null, null, refused = true), NavigateOutcome.Refused)
    check("the same leaf twice is no move", navigateOutcome("a", "a", refused = false), NavigateOutcome.NoMove)
    check("null to null is no move", navigateOutcome(null, null, refused = false), NavigateOutcome.NoMove)
    // Null is pi's leaf before the first entry and after `resetLeaf()`
    // (`session-manager.ts:1386-1388`), which is what navigating to the FIRST user
    // message produces — a real move, not missing data.
    check("null to a leaf is a move", navigateOutcome(null, "u1", refused = false), NavigateOutcome.Moved)
    check("a leaf to null is a move", navigateOutcome("u1", null, refused = false), NavigateOutcome.Moved)
    check("a different leaf is a move", navigateOutcome("a", "b", refused = false), NavigateOutcome.Moved)

    // ------------------------------------------------- 5. the branch filter
    val root = entry("""{"id":"r","parentId":null,"type":"message"}""")
    val u1 = entry("""{"id":"u1","parentId":"r","type":"message"}""")
    val a1 = entry("""{"id":"a1","parentId":"u1","type":"message"}""")
    val u2 = entry("""{"id":"u2","parentId":"a1","type":"message"}""")
    val a2 = entry("""{"id":"a2","parentId":"u2","type":"message"}""")
    val summary = entry("""{"id":"s1","parentId":"r","type":"branch_summary"}""")
    val file = listOf(root, u1, a1, u2, a2, summary)

    check("a linear branch is root-first", activeBranch(listOf(root, u1, a1), "a1").map { it["id"].toString() }, listOf("\"r\"", "\"u1\"", "\"a1\""))
    // The exact "half a screen of stale data" case: the file's physical tail is
    // `a2` (the abandoned leaf) but the active leaf is the summary `s1`, so the
    // abandoned path must not appear.
    check(
        "the abandoned path is not in the rebuilt branch",
        activeBranch(file, "s1").map { it["id"].toString() },
        listOf("\"r\"", "\"s1\""),
    )
    check(
        "the abandoned branch's own leaf still resolves to its own path",
        activeBranch(file, "a2").map { it["id"].toString() },
        listOf("\"r\"", "\"u1\"", "\"a1\"", "\"u2\"", "\"a2\""),
    )
    check("a null leaf is an empty branch", activeBranch(file, null), emptyList<Any>())
    check("an unknown leaf is an empty branch", activeBranch(file, "nope"), emptyList<Any>())
    check("an empty file with a leaf is empty", activeBranch(emptyList(), "a1"), emptyList<Any>())
    // Navigating to the first user message resets the leaf; the rebuilt branch is
    // then whatever the new root is, and a file that only holds the old path must
    // not be shown at all.
    check("a summary that roots the file is the whole branch", activeBranch(listOf(summary), "s1").size, 1)

    // A malformed file whose parentId chain cycles must terminate rather than hang.
    val cycleA = entry("""{"id":"x","parentId":"y"}""")
    val cycleB = entry("""{"id":"y","parentId":"x"}""")
    check("a cycle terminates", activeBranch(listOf(cycleA, cycleB), "x").size, 2)
    // A self-parent is the same hazard.
    val selfParent = entry("""{"id":"z","parentId":"z"}""")
    check("a self-parent terminates", activeBranch(listOf(selfParent), "z").size, 1)
    // A dangling parent ends the walk but keeps what it found.
    val dangling = entry("""{"id":"d","parentId":"missing"}""")
    check("a dangling parent keeps the row", activeBranch(listOf(dangling), "d").size, 1)

    // The generic form, so the rule is pinned without JSON at all.
    check(
        "the generic form walks the same way",
        branchFromRootToLeaf(
            items = listOf("r", "u1", "a1", "s1"),
            leafId = "s1",
            idOf = { it },
            parentOf = { if (it == "r") null else if (it == "u1" || it == "s1") "r" else "u1" },
        ),
        listOf("r", "s1"),
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
