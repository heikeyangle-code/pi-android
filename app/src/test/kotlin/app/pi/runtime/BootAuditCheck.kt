package app.pi.runtime

// A bare-JVM harness for the durable boot audit (`runtime/BootAudit.kt`,
// `runtime/TreeSnapshot.kt`), compiled and run by `tools/run-app-pure-checks.sh`.
//
// Why a harness and not a device: the audit is the *only* evidence a user can hand us
// about an update that deleted their workspace root, and every way it can lie is silent.
// It can claim a directory held 0 files when it was actually unreadable; it can print a
// count that stopped at a budget without saying so; it can drop old lines without a
// number saying how many; it can fail to record at all on the one boot that mattered.
// None of that is visible from a phone screenshot, and all of it is arithmetic.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

import java.io.File
import java.time.Instant

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** A fresh scratch directory per group, so no check can see another's files. */
fun scratch(tag: String): File =
    File(System.getProperty("java.io.tmpdir"), "pi-boot-audit-$tag-${System.nanoTime()}").also { it.mkdirs() }

fun main() {
    // ------------------------------------------------------------- the upgrade trigger
    // Once per upgrade, never once per boot: "nothing changed" must be false, or the
    // audit becomes a per-boot traversal of the user's directories.
    check("A1 no state means record", BootAudit.shouldRecord(null, "100-1", "abc"), true)
    check("A2 blank state means record", BootAudit.shouldRecord("   ", "100-1", "abc"), true)
    check("A3 the same apk and revision means skip", BootAudit.shouldRecord("apk=100-1\nrevision=abc\n", "100-1", "abc"), false)
    check("A4 a new apk means record", BootAudit.shouldRecord("apk=100-1\nrevision=abc\n", "200-1", "abc"), true)
    check("A5 a new revision means record", BootAudit.shouldRecord("apk=100-1\nrevision=abc\n", "100-1", "def"), true)
    check("A6 an unreadable revision still records", BootAudit.shouldRecord("apk=100-1\nrevision=abc\n", "100-1", ""), true)
    check("A7 state round-trips", BootAudit.parseState(BootAudit.stateText("100-1", "abc")), "100-1" to "abc")
    check("A8 a missing state half is null", BootAudit.parseState("apk=100-1\n"), "100-1" to null)

    // --------------------------------------------------------------- the bounded file
    // 45 boots with a 40-line ceiling: the five oldest are gone, and the header says so
    // rather than letting the reader wonder.
    var text = ""
    for (i in 1..45) text = BootAudit.append(text, "line-$i", maxLines = 40)
    val header = text.lineSequence().first()
    check("B1 the header names the total", "total=45" in header, true)
    check("B2 the header names what was kept", "kept=40" in header, true)
    check("B3 the header names what was dropped", "dropped=5" in header, true)
    check("B4 the header names the limit", "max=40" in header, true)
    check("B5 the newest line is present", "line-45" in text, true)
    check("B6 the oldest dropped line is gone", "line-5" in text, false)
    check("B7 the first kept line is present", "line-6" in text, true)
    val bodyLines = text.lineSequence().filter { it.isNotBlank() && !it.startsWith(BootAudit.HEADER_PREFIX) }.count()
    check("B8 the body holds exactly the limit", bodyLines, 40)

    // A header this build does not recognise (another build wrote the file) must not make
    // the counts lie: existing lines are counted as history.
    val foreign = BootAudit.append("some other build's line\nanother\n", "ours", maxLines = 40)
    check("B9 unknown prior lines are counted as history", "total=3" in foreign, true)
    check("B10 unknown prior lines are kept", "some other build's line" in foreign, true)

    // -------------------------------------------------------- direct children, not a walk
    // The primary evidence: what is directly under the workspace root, and whether the
    // root itself is even there. Three distinct answers that must not impersonate each
    // other: missing / unreadable / empty.
    val root = scratch("root")
    val ws = File(root, "workspaces").also { it.mkdirs() }
    File(ws, "workspace-1").mkdirs()
    File(ws, "notes.md").writeText("hello")
    val wsSnapshot = TreeSnapshot.root(ws)
    check("C1 an existing root says so", wsSnapshot.exists, true)
    check("C2 it counts every direct child", wsSnapshot.childCount, 2)
    check("C3 it records both names", wsSnapshot.children.map { it.name }.sorted(), listOf("notes.md", "workspace-1"))
    check("C4 it records which is a directory", wsSnapshot.children.first { it.name == "workspace-1" }.directory, true)
    check("C5 it records a file's size", wsSnapshot.children.first { it.name == "notes.md" }.bytes, 5L)
    check("C6 it records the root's own mtime", wsSnapshot.modifiedMs == null, false)
    check("C7 an absent root is not `0 children`", TreeSnapshot.root(File(root, "nope")).exists, false)

    val many = File(root, "many").also { it.mkdirs() }
    repeat(5) { File(many, "item-$it").writeText("x") }
    val cappedChildren = TreeSnapshot.root(many, maxChildren = 2)
    check("C8 the child count is complete when the list is capped", cappedChildren.childCount, 5)
    check("C9 the omitted count is stated", cappedChildren.omitted, 3)
    check("C10 the list itself is capped", cappedChildren.children.size, 2)
    check("C11 the summary states the omission", "omitted=3" in TreeSnapshot.rootSummary(cappedChildren), true)
    // An unreadable directory and an empty one are different answers. `listFiles()` on a
    // regular file is null, which is the same shape a permissions failure has.
    check("C12 a non-directory is `not there`, not `empty`", TreeSnapshot.root(File(root, "many/item-0")).exists, false)

    // ----------------------------------------------------------- the budgeted magnitude
    // The walk is allowed to stop early. What it must never do is stop early *quietly*.
    val big = File(root, "big").also { it.mkdirs() }
    repeat(50) { File(big, "f-$it").writeText("12345") }
    val small = TreeSnapshot.walk(big, budgetEntries = 10, budgetMs = 60_000)
    check("D1 the entry budget is a hard cap", small.visited <= 10L, true)
    check("D2 a capped walk says it was capped", small.capped == null, false)
    check("D3 the cap sentence names the budget", small.capped!!.contains("上限"), true)
    check("D4 the summary carries the cap", "截断：" in TreeSnapshot.walkSummary(small), true)
    val full = TreeSnapshot.walk(big, budgetEntries = 10_000, budgetMs = 60_000)
    check("D5 an uncapped walk counts every file", full.files, 50L)
    check("D6 an uncapped walk sums the bytes", full.bytes, 250L)
    check("D7 an uncapped walk says it was not capped", full.capped, null)
    check("D8 the summary says `未截断`", "未截断" in TreeSnapshot.walkSummary(full), true)
    // A zero time budget is the deterministic form of "slow device": it must cap, and say so.
    val timedOut = TreeSnapshot.walk(big, budgetEntries = 10_000, budgetMs = 0L)
    check("D9 the time budget caps", timedOut.capped == null, false)
    check("D10 the time-cap sentence names the budget", timedOut.capped!!.contains("ms 上限"), true)
    check("D11 a missing directory is stated, not counted as zero", TreeSnapshot.walk(File(root, "nope")).capped, "目录不存在")

    // ------------------------------------------------------------------------ the line
    val line = BootAudit.entry(
        timestamp = Instant.parse("2026-06-01T12:00:00Z"),
        apk = "1000-7",
        revision = "abcdef0123456789",
        payloads = "ubuntu-base:1111111111111111,node:2222222222222222",
        reextracted = "none",
        workspace = wsSnapshot,
        workspaceWalk = full,
        agent = wsSnapshot,
        agentWalk = full,
    )
    check("E1 the line is one line", line.contains("\n"), false)
    check("E2 the line carries the time", "t=2026-06-01T12:00:00Z" in line, true)
    check("E3 the line carries the epoch", "epochMs=" in line, true)
    check("E4 the line carries the apk", "apk=1000-7" in line, true)
    check("E5 the line carries the revision", "rev=abcdef0123456789" in line, true)
    check("E6 the line carries the per-payload digests", "payloads=ubuntu-base:1111111111111111" in line, true)
    check("E7 the line carries what was re-extracted", "reextract=none" in line, true)
    check("E8 the line carries the workspace root", "ws=path=" in line, true)
    check("E9 the line carries the agent dir", "agent=path=" in line, true)
    check("E10 the line carries the workspace's children", "notes.md:file:5:" in line, true)
    check("E11 the line carries the magnitude", "wswalk=visited=" in line, true)
    check("E12 a read failure is a word, not a zero", "读不到" in BootAudit.entry(
        timestamp = Instant.EPOCH, apk = "", revision = "", payloads = "", reextracted = "",
        workspace = TreeSnapshot.root(File(root, "nope")),
        workspaceWalk = TreeSnapshot.walk(File(root, "nope")),
        agent = TreeSnapshot.root(File(root, "nope")),
        agentWalk = TreeSnapshot.walk(File(root, "nope")),
    ), true)

    // -------------------------------------------------------------- record, once per upgrade
    // The end-to-end shape that answers the user's question: an upgrade records the
    // workspace root's children *before* they disappear and the same listing *after*.
    val persist = File(root, "persist")
    val agent = File(root, "agent").also { it.mkdirs() }
    val first = BootAudit.recordIfUpgraded(persist, "1000-7", "rev-1", "p:1", "none", ws, agent)
    check("F1 the first run records", first, null)
    val auditPath = BootAudit.file(persist)
    check("F2 the audit file exists", auditPath.isFile, true)
    val afterFirst = auditPath.readText()
    check("F3 it recorded the workspace's children", "notes.md:file:5:" in afterFirst, true)
    val second = BootAudit.recordIfUpgraded(persist, "1000-7", "rev-1", "p:1", "none", ws, agent)
    check("F4 a second boot with nothing changed does not add a line", second, null)
    check("F5 and does not grow the file", auditPath.readText().count { it == '\n' }, afterFirst.count { it == '\n' })

    // Now the upgrade itself: same workspace path, children gone (the user's report), new apk.
    File(ws, "notes.md").delete()
    File(ws, "workspace-1").delete()
    val third = BootAudit.recordIfUpgraded(persist, "2000-8", "rev-2", "p:2", "git.tgz", ws, agent)
    check("F6 the upgrade records", third, null)
    val afterUpgrade = auditPath.readText()
    check("F7 the `before` line still shows the child", "notes.md:file:5:" in afterUpgrade, true)
    check("F8 the `after` line shows an empty root", afterUpgrade.lineSequence().last { it.isNotBlank() }.contains("children=0"), true)
    check("F9 the after line names what was re-extracted", "reextract=git.tgz" in afterUpgrade, true)
    check("F10 the header counts both lines", "total=2" in afterUpgrade, true)

    // The reader must never turn "too big to read fully" into "this is all there is".
    val huge = File(root, "huge.log")
    val hugeText = "x".repeat(5000) + "\nlast-line\n"
    huge.writeText(hugeText)
    val tail = BootAudit.readTail(huge, maxChars = 100)
    check("G1 a truncated read says so", tail != null && tail.contains("读取截断"), true)
    // The sentence must name the file's real byte count and the real budget, so the
    // expected size is taken from the text that was actually written rather than from a
    // literal that could drift from it.
    check(
        "G2 it names the size and the budget",
        tail != null && tail.contains(hugeText.toByteArray().size.toString()) && tail.contains("100"),
        true,
    )
    check("G3 it keeps the newest content", tail != null && tail.contains("last-line"), true)
    check("G4 a missing file is null, not empty", BootAudit.readTail(File(root, "nothing-here"), 100), null)

    root.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
