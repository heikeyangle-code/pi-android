package app.pi.ui.chat

// 裸 JVM harness：钉住 `ui/chat/PiTreeNavigation.kt` 里会话树「跳转」的纯逻辑——
// 摘要问题与 `branchSummary.skipPrompt`、跳转后「叶子有没有动」的判定，
// 以及「只重建活动分支」的过滤（跳转后会话文件仍含被放弃的路径，物理重放会画出模型上下文里没有的消息）。
// 只依赖 Kotlin 标准库与 kotlinx-serialization-json，`tools/run-app-pure-checks.sh` 直接编译运行。

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
