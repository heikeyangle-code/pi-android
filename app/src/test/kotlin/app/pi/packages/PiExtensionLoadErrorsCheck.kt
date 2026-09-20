package app.pi.packages

// A bare-JVM harness for `PiExtensionLoadErrors`: the one place a *silent* extension
// failure can be made to say something.
//
// Why it is worth pinning: pi discovers every resource kind by walking a directory, and
// every walker swallows its own errors (`loader.ts:739-741` `catch { return [] }`;
// `package-manager.ts:312-359` `catch { // Ignore errors }`). The only half that leaves
// evidence is the module-load failure pi prints to stderr in RPC mode and then exits 1
// on (`main.ts:98-104`, `:900-910`). If this parser stops recognising that line, the
// diagnostic report goes back to saying "nothing here" while extensions are missing —
// which is the exact silent shape the report section exists to end.
//
// Android-free: `PiExtensionLoadErrors.kt` imports only `:rpc`'s `Ansi` and the stdlib.
// Registered in `tools/run-app-pure-checks.sh` as `extension-load-errors`.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** One line exactly as pi's `reportDiagnostics` writes it (`chalk.red` included). */
private fun piLine(path: String, error: String): String =
    "\u001B[31mError: Failed to load extension \"$path\": $error\u001B[39m"

fun main() {
    // ---------------------------------------------------------------- nothing to report
    check("no stderr at all is no failures", PiExtensionLoadErrors.parse(null).count, 0)
    check("blank stderr is no failures", PiExtensionLoadErrors.parse("   \n\n").count, 0)
    // A stderr that *is* present but carries nothing of this shape must say so in a way
    // that cannot be read as "读了但读不到".
    val quiet = PiExtensionLoadErrors.parse("node: warning: something else\n")
    check("unrelated stderr is no failures", quiet.count, 0)
    check("...and the sentence is the single 'none' line", quiet.describe().size, 1)
    check("...which does not claim a count of zero records", quiet.describe()[0].contains("没有"), true)

    // ------------------------------------------------------------- the line pi writes
    val one = PiExtensionLoadErrors.parse(piLine("/root/.pi/agent/extensions/pi-android-bridge/index.ts", "Unexpected token"))
    check("one line is one failure", one.count, 1)
    check("the path is parsed", one.first?.path, "/root/.pi/agent/extensions/pi-android-bridge/index.ts")
    check("the message is parsed", one.first?.message, "Unexpected token")
    val described = one.describe()
    check("the count is in the first line", described[0].contains("1 条"), true)
    check("the path is named", described[0].contains("/root/.pi/agent/extensions/pi-android-bridge/index.ts"), true)
    check("the message gets its own line", described.any { it.trim() == "Unexpected token" }, true)

    // ANSI must not defeat the match: pi colours this with `chalk.red`, and "the pipe is
    // not a TTY so chalk disables colour" is a behaviour, not a contract.
    check("a coloured line is still recognised", PiExtensionLoadErrors.parse("Error: Failed to load extension \"/x.ts\": boom").count, 1)
    check(
        "and the colour codes never reach the message",
        PiExtensionLoadErrors.parse(piLine("/x.ts", "boom")).first?.message?.contains('\u001B'),
        false,
    )

    // ------------------------------------------------------------------- several at once
    val many = PiExtensionLoadErrors.parse(
        listOf(
            piLine("/a/index.ts", "first"),
            "some unrelated warning",
            piLine("/b/index.ts", "second"),
            piLine("/c.js", "third"),
        ).joinToString("\n"),
    )
    check("every line is counted", many.count, 3)
    check("the first is the first one printed", many.first?.path, "/a/index.ts")
    check("order is preserved", many.failures.map { it.path }, listOf("/a/index.ts", "/b/index.ts", "/c.js"))
    // The count is always complete; only the *listing* is capped, and the cap says so.
    val wide = PiExtensionLoadErrors.parse(
        (1..9).joinToString("\n") { piLine("/e$it.ts", "boom$it") },
    )
    check("the count is not capped", wide.count, 9)
    check("the listing is capped", wide.describe().size <= PiExtensionLoadErrors.MAX_LISTED + 2, true)
    check("...and the sentence admits the remainder", wide.describe().any { it.contains("其余 8 条") }, true)

    // ------------------------------------------------------------- malformed / hostile
    check("a line without the closing quote is ignored", PiExtensionLoadErrors.parse("Error: Failed to load extension \"/a.ts: boom").count, 0)
    check("a bare marker with nothing after it is ignored", PiExtensionLoadErrors.parse("Error: Failed to load extension \"").count, 0)
    check("an empty path is still a parseable line", PiExtensionLoadErrors.parse("Error: Failed to load extension \"\": boom").first?.path, "")
    check(
        "a message containing quotes keeps everything after the first closing quote",
        PiExtensionLoadErrors.parse("Error: Failed to load extension \"/a.ts\": expected \"x\"").first?.message,
        "expected \"x\"",
    )
    check("CRLF does not leave a carriage return in the message", PiExtensionLoadErrors.parse(piLine("/a.ts", "boom") + "\r\n").first?.message, "boom")
    check(
        "the marker is found even when pi prefixes something else",
        PiExtensionLoadErrors.parse("[pi] Error: Failed to load extension \"/a.ts\": boom").count,
        1,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
