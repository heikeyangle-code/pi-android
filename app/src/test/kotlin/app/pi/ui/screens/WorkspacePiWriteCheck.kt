package app.pi.ui.screens

// A bare-JVM harness for the workspace tree's pi-file write rule. Registered in
// `tools/run-app-pure-checks.sh` as `workspace-pi-write`.
//
// Why this is worth pinning: the files under `<workspace>/.pi/` are read by pi, and they had
// two writers — the workspace tree's whole-file overwrite and the 「Pi 文件」screen's locked,
// atomic, validated one. This file checks the rules that decide which save takes which path,
// that the validation is the *same* judgement the other screen makes (it is literally the same
// function, `checkPiFileWrite`), and the two refusals that keep a JSON document pi parses from
// being created or renamed into place unchecked.
//
// The decision reads the **real file** first and the display path only as a fallback, and that
// order is itself an assertion here: on one of the viewer's open routes the display path is
// pi's own absolute/guest spelling (`/workspace/...`), which a path-only rule read as "not a
// `.pi` file" and sent back to the unprotected overwrite. Canonical paths also make `..` and
// symlinks unable to escape the decision, which two of the cases below exercise on a real
// filesystem.

import java.io.File
import java.nio.file.Files

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun checkTrue(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

/** A path that is deliberately **not** under any `.pi`, so each case tests one rule at a time. */
private val outside = File("/tmp/pi-android-harness/workspace/notes.txt")

/** The display path one of the viewer's open routes hands in: pi's own spelling. */
private const val GUEST_PI_PATH = "/workspace/.pi/settings.json"

fun main() {
    // --- which paths are pi's files (display-relative form) --------------------------
    check("a root .pi document is protected", WorkspacePiWrite.isPiDocumentPath(".pi/settings.json", outside), true)
    check("a nested .pi document is protected", WorkspacePiWrite.isPiDocumentPath(".pi/themes/dark.json", outside), true)
    check("the path is relativised to the .pi root", WorkspacePiWrite.piRelative(".pi/settings.json"), "settings.json")
    check("and so is a nested one", WorkspacePiWrite.piRelative(".pi/themes/dark.json"), "themes/dark.json")
    check("backslashes are separators too", WorkspacePiWrite.piRelative(".pi\\settings.json"), "settings.json")

    check("an ordinary workspace file is not", WorkspacePiWrite.isPiDocumentPath("src/Main.kt", outside), false)
    check("a file merely named .pi is not a document path", WorkspacePiWrite.isPiDocumentPath(".pi", outside), false)
    check("the directory itself has no document path", WorkspacePiWrite.piRelative(".pi"), null)
    check("a trailing slash is the directory", WorkspacePiWrite.piRelative(".pi/"), null)
    check("a deeper .pi is not this workspace's project dir", WorkspacePiWrite.piRelative("src/.pi/settings.json"), null)
    check("a file called .piconfig is not a directory", WorkspacePiWrite.isPiDocumentPath(".piconfig/x.json", outside), false)
    check("a .. segment cannot make a path a .pi one", WorkspacePiWrite.isPiDocumentPath("../.pi/x.json", outside), false)

    // The case rule, both halves. A case-insensitive volume spells the same directory `.PI`,
    // so that segment is matched leniently; `piFilesDocFor` matches the document name
    // exactly, so `SETTINGS.JSON` is not the file pi reads and gets no content check.
    check("an upper-case .PI segment is still pi's directory", WorkspacePiWrite.isPiDocumentPath(".PI/settings.json", outside), true)
    check("but the document name is matched exactly", WorkspacePiWrite.piRelative(".PI/settings.json"), "settings.json")
    checkTrue(
        "an upper-case document name gets no content check",
        WorkspacePiWrite.problem(".PI/SETTINGS.JSON", outside, "not json at all") == null,
    )

    // --- the real file decides, and the display path cannot fool it ------------------
    //
    // This is the shape that used to leak: the viewer hands in pi's guest/absolute spelling
    // while `file` is the real host file, so the string rule alone said "not under .pi".
    val realPiFile = File("/tmp/pi-android-harness/workspace/.pi/settings.json")
    check("the file's own path is what is relativised", WorkspacePiWrite.piRelativeFromFile(realPiFile), "settings.json")
    check("the guest display path no longer hides a real pi file", WorkspacePiWrite.isPiDocumentPath(GUEST_PI_PATH, realPiFile), true)
    checkTrue(
        "and a bad value is refused through it",
        WorkspacePiWrite.problem(GUEST_PI_PATH, realPiFile, """{"httpIdleTimeoutMs": null}""") != null,
    )
    check(
        "the same file with an ordinary display path is still protected",
        WorkspacePiWrite.isPiDocumentPath("settings.json", realPiFile),
        true,
    )

    // The converse: a display path must not be able to *invent* protection for an ordinary
    // file either — the file decides the relativisation, so the two are read consistently.
    check("the file wins for relativisation when both are given", WorkspacePiWrite.piRelativeFor(".pi/x.json", outside), "x.json")
    check("no .pi ancestor, no file rule", WorkspacePiWrite.piRelativeFromFile(outside), null)

    // Nested `.pi` directories: the innermost one is the nearest pi root.
    check("the innermost .pi ancestor wins", WorkspacePiWrite.piRelativeFromFile(File("/tmp/w/a/.pi/b/.pi/c.json")), "c.json")
    check("an ancestor walk stops at the root", WorkspacePiWrite.piDirOf(File("/settings.json")), null)
    check(
        "a .pi directory is found for a file that does not exist yet",
        WorkspacePiWrite.piDirOf(File("/tmp/w/.pi/themes/new.json"))?.name,
        ".pi",
    )

    // `..` is resolved by the canonical path, in both directions.
    check(".. out of .pi is not a pi file", WorkspacePiWrite.piRelativeFromFile(File("/tmp/w/.pi/../notes.txt")), null)
    check(".. into .pi is still a pi file", WorkspacePiWrite.piRelativeFromFile(File("/tmp/w/src/../.pi/settings.json")), "settings.json")

    // --- on a real filesystem: symlinks and `..` ------------------------------------
    val temp = runCatching { Files.createTempDirectory("wpw").toFile() }.getOrNull()
    if (temp == null) {
        println("SKIP the real-filesystem cases (no temp directory available)")
    } else {
        val ws = File(temp, "ws").apply { mkdirs() }
        val piDir = File(ws, ".pi").apply { mkdirs() }
        val real = File(piDir, "settings.json").apply { writeText("{}") }
        val elsewhere = File(ws, "notes.txt").apply { writeText("hi") }
        check("a real file under .pi is protected", WorkspacePiWrite.isPiDocumentPath("", real), true)
        check("its real relative path is used", WorkspacePiWrite.piRelativeFromFile(real), "settings.json")
        check("a real file outside .pi is not", WorkspacePiWrite.piRelativeFromFile(elsewhere), null)
        check("a real .. path out of .pi is not protected", WorkspacePiWrite.piRelativeFromFile(File(piDir, "../notes.txt")), null)

        // A symlink from outside the workspace into `.pi`: the canonical path follows it, so
        // the save is still recognised as a pi file rather than escaping the rule.
        val link = File(temp, "link-to-settings.json")
        if (runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess) {
            check("a symlink into .pi is still recognised", WorkspacePiWrite.piRelativeFromFile(link), "settings.json")
        } else {
            println("SKIP the symlink case (this filesystem does not allow creating one)")
        }
        // A symlink spelled inside `.pi` but pointing at an ordinary file. Both spellings are
        // judged: the lexical one is what pi reads by name, so the save stays routed (and a
        // link *called* `settings.json` therefore still gets the settings check, which is the
        // hole the canonical-only rule had).
        val outLink = File(piDir, "escape.txt")
        if (runCatching { Files.createSymbolicLink(outLink.toPath(), elsewhere.toPath()) }.isSuccess) {
            check("a symlink out of .pi is still judged by the name pi reads", WorkspacePiWrite.piRelativeFromFile(outLink), "escape.txt")
        }
        val dangerousLink = File(piDir, "linked-settings.json")
        if (runCatching { Files.createSymbolicLink(dangerousLink.toPath(), elsewhere.toPath()) }.isSuccess) {
            check(
                "and a link called *.json under .pi is still a document",
                WorkspacePiWrite.piRelativeFromFile(dangerousLink),
                "linked-settings.json",
            )
            checkTrue(
                "so its content is checked",
                WorkspacePiWrite.problem("", dangerousLink, "{ not json") != null,
            )
        }
        temp.deleteRecursively()
    }

    // --- the content check, which is the other screen's own judgement ----------------
    check("a valid settings.json may be saved", WorkspacePiWrite.problem(".pi/settings.json", outside, "{}"), null)
    check(
        "a valid settings.json with values may be saved",
        WorkspacePiWrite.problem(".pi/settings.json", outside, """{"httpIdleTimeoutMs": 30000}"""),
        null,
    )

    val nullTimeout = WorkspacePiWrite.problem(".pi/settings.json", outside, """{"httpIdleTimeoutMs": null}""")
    checkTrue("a null timeout is refused", nullTimeout != null, "$nullTimeout")
    checkTrue("and the reason names the key", nullTimeout?.contains("httpIdleTimeoutMs") == true, "$nullTimeout")

    checkTrue(
        "a negative reserveTokens is refused",
        WorkspacePiWrite.problem(".pi/settings.json", outside, """{"compaction": {"reserveTokens": -1}}""") != null,
    )

    val brokenJson = WorkspacePiWrite.problem(".pi/settings.json", outside, "{ not json")
    checkTrue("broken JSON is refused", brokenJson != null, "$brokenJson")
    checkTrue("and the reason says so", brokenJson?.contains("JSON") == true, "$brokenJson")

    check(
        "a models.json without a bad provider is accepted",
        WorkspacePiWrite.problem(".pi/models.json", outside, """{"providers": {}}"""),
        null,
    )
    checkTrue(
        "a models.json whose model has no id is refused",
        WorkspacePiWrite.problem(".pi/models.json", outside, """{"providers":{"p":{"models":[{}]}}}""") != null,
    )

    // Files pi does *not* parse as a document are not content-checked, and that is the
    // deliberate half: a session file is line-delimited JSON that pi appends to, and a
    // markdown context file is prose. They still get the lock and the atomic replace.
    check("a session file is not content-checked", WorkspacePiWrite.problem(".pi/sessions/s.jsonl", outside, "anything at all"), null)
    check("a context markdown file is not content-checked", WorkspacePiWrite.problem(".pi/AGENTS.md", outside, "prose"), null)
    check("an ordinary workspace file is never refused", WorkspacePiWrite.problem("notes.txt", outside, "{ not json"), null)

    // --- creating or renaming a JSON document into place -----------------------------
    //
    // A new file is empty and a rename moves bytes nothing has checked, so neither can pass
    // the content check a *save* runs. Both are refused instead of guessed at.
    check("a new .pi JSON document is refused", WorkspacePiWrite.isPiJsonTarget(File("/tmp/w/.pi/settings.json")), true)
    check("a new .pi theme document is refused", WorkspacePiWrite.isPiJsonTarget(File("/tmp/w/.pi/themes/mine.json")), true)
    check("a nested .pi is refused too (over-approximation, the safe side)", WorkspacePiWrite.isPiJsonTarget(File("/tmp/w/src/.pi/x.json")), true)
    check("a .pi file that is not JSON is allowed", WorkspacePiWrite.isPiJsonTarget(File("/tmp/w/.pi/AGENTS.md")), false)
    check("a .jsonl under .pi is allowed (it is not a JSON document)", WorkspacePiWrite.isPiJsonTarget(File("/tmp/w/.pi/sessions/s.jsonl")), false)
    check("a .json outside .pi is allowed", WorkspacePiWrite.isPiJsonTarget(File("/tmp/w/src/x.json")), false)
    check("the document name is matched exactly", WorkspacePiWrite.isPiJsonTarget(File("/tmp/w/.pi/SETTINGS.JSON")), false)

    val refusal = WorkspacePiWrite.creationRefusalSentence()
    checkTrue("the refusal explains itself", refusal.contains(".pi") && refusal.isNotBlank())
    checkTrue("and it names where such a file can be edited", refusal.contains("Pi 文件"))
    checkTrue(
        "and it does not promise a new-file action there",
        !refusal.contains("屏新建"),
        refusal,
    )

    // --- owner-only writes ----------------------------------------------------------
    check("auth.json keeps pi's 0600", WorkspacePiWrite.restrictToOwner(".pi/auth.json", outside), true)
    check("settings.json is not restricted", WorkspacePiWrite.restrictToOwner(".pi/settings.json", outside), false)
    check("nothing outside .pi is restricted", WorkspacePiWrite.restrictToOwner("auth.json", outside), false)
    check("a nested auth.json is not pi's credential file", WorkspacePiWrite.restrictToOwner(".pi/nested/auth.json", outside), false)

    // --- the stale-write check ------------------------------------------------------
    //
    // The other half of "pi is writing this file too": the lock keeps the check and the write
    // atomic, and this decides whether there is anything to refuse. A null baseline means the
    // viewer never read the file, which is not a reason to block a save.
    check("no baseline does not block a save", WorkspacePiWrite.stampChanged(null, "10:100"), false)
    check("an unchanged stamp passes", WorkspacePiWrite.stampChanged("10:100", "10:100"), false)
    check("a changed mtime is noticed", WorkspacePiWrite.stampChanged("10:100", "10:101"), true)
    check("a changed size is noticed", WorkspacePiWrite.stampChanged("10:100", "11:100"), true)
    checkTrue(
        "and the refusal tells the user how to recover",
        WorkspacePiWrite.staleStampSentence().contains("重新打开") &&
            WorkspacePiWrite.staleStampSentence().isNotBlank(),
    )

    // --- the property, stated directly ---------------------------------------------
    //
    // Every path that is not under a `.pi` must be untouched by this object: no refusal, no
    // restriction. A false positive here would make an ordinary document unsavable, which is a
    // worse failure than the one this file exists to fix.
    val ordinary = listOf("a.txt", "src/main.kt", "docs/.pi.md", "pi/settings.json", "sub/.pi/x.json", ".git/config")
    for (path in ordinary) {
        if (WorkspacePiWrite.problem(path, outside, "{ broken") != null || WorkspacePiWrite.restrictToOwner(path, outside)) {
            failures++
            println("FAIL an ordinary path was touched: $path")
        }
    }
    println("PASS ordinary paths are never refused and never restricted (${ordinary.size} checked)")

    // And the converse: every `.pi` path is routed — by the file when there is one, by the
    // display path otherwise — so none of them can fall back to the unprotected overwrite.
    val piFiles = listOf(".pi/settings.json", ".pi/models.json", ".pi/auth.json", ".pi/themes/a.json", ".pi/sessions/x.jsonl")
    for (path in piFiles) {
        if (!WorkspacePiWrite.isPiDocumentPath(path, outside)) {
            failures++
            println("FAIL a pi file would not be routed: $path")
        }
    }
    println("PASS every .pi path is routed through pi's write path (${piFiles.size} checked)")

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
