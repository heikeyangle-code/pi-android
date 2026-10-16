package app.pi.engine

// A bare-JVM harness for the engine's exit translator
// (`engine/EngineExitCause.kt`), compiled and run by
// `tools/run-app-pure-checks.sh`.
//
// Why a harness: this object is the *only* thing that turns the captured stderr into
// something a person can act on, and every rule in it is a claim about what pi
// prints. The stderr strings below are copied verbatim from runs (pi 0.85.1, node
// 24.19.0, recorded in `docs/engine-exit-review.md` §2); the pinned baseline
// re-measured them, and **1.1.0, 1.2.0 and 1.3.0 behave identically** (an
// unregistered flag: exit 1, stdout empty, `Error: Unknown option: --bogus-flag`).
// The strings are unchanged in the pinned engine's source too —
// `core/extensions/loader.ts:578`, `cli/args.ts:242`, `main.ts:645`,
// `modes/rpc/rpc-mode.ts:476`. If pi's wording ever changes, the rule silently
// stops matching and the failure screen quietly falls back to "引擎异常退出" — which
// is precisely the state this object was written to end. Pinning the strings here
// makes that change show up as a failure in CI instead.
//
// The other half of the contract is what it must NOT say: an unattributable exit, an
// empty stderr, or text that merely mentions a word out of context must not be
// dressed up as a diagnosis. Roughly half the checks below are that half.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun checkTrue(name: String, actual: Boolean) = check(name, actual, true)
fun checkFalse(name: String, actual: Boolean) = check(name, actual, false)

fun main() {
    // ------------------------------------------------------------ extension load
    // The device's most likely cause: a shipped extension tree that was not copied
    // completely, or a user-installed extension whose import is missing.
    val extensionStderr = """
        Error: Failed to load extension "/root/.pi/agent/extensions/broken.ts": Failed to load extension: ParseError: Unexpected token
        /root/.pi/agent/extensions/broken.ts:1:41
        Hint: Start without extensions using "pi -ne".
    """.trimIndent()
    val extensionSummary = EngineExitCause.summary(1, extensionStderr)
    checkTrue("A1 an extension load failure is attributed", extensionSummary != null)
    checkTrue("A2 and the sentence names the extension as the cause", extensionSummary?.contains("扩展") == true)
    checkFalse("A3 the sentence leaks a file path", extensionSummary?.contains("/root") == true)

    // The missing-module spelling, which is what §M9's shape looked like.
    val missingModule = "Error: Failed to load extension \"/x/needs.ts\": Failed to load extension: Cannot find module 'highlight.js'"
    checkTrue("A4 a missing module is attributed too", EngineExitCause.summary(1, missingModule) != null)

    // -------------------------------------------------------------- unknown flag
    // What an unregistered flag does — measured on the pinned engine: exit 1, stdout
    // empty, this line on stderr. Since the app forwards `app.extensions.args`, the
    // row is the source the user can actually check, so the sentence has two branches
    // and both are pinned here.
    val unknownOption = "Error: Unknown option: --bogus-flag"
    checkTrue("B1 an unknown option is attributed", EngineExitCause.summary(1, unknownOption) != null)
    // Not the load-failure sentence ("有一个扩展没能加载"). The row's *name* may appear —
    // it is the fix — so the assertion is about the claim, not about the word 扩展.
    checkFalse(
        "B2 and does not blame a failed extension load",
        EngineExitCause.summary(1, unknownOption)?.contains("没能加载") == true,
    )
    // No row content (or unreadable): the common source is still named, the
    // payload-mismatch answer and its fix stay.
    val unknownOptionRowEmpty = EngineExitCause.summary(1, unknownOption)
    checkTrue(
        "B3 an empty row still names the row as the common source",
        unknownOptionRowEmpty?.contains("扩展启动参数") == true,
    )
    checkTrue(
        "B4 and keeps the payload-mismatch answer",
        unknownOptionRowEmpty?.contains("运行时载荷") == true,
    )
    checkTrue("B5 and its reinstall advice", unknownOptionRowEmpty?.contains("重装 App") == true)
    // A blank string is "no content", exactly as `ExtensionArgsStore.read()` treats it.
    check(
        "B6 a blank row is the empty-row branch",
        EngineExitCause.summary(1, unknownOption, "   "),
        unknownOptionRowEmpty,
    )
    // The row holds text: the sentence points at it, drops the payload claim, and says
    // the value is not a reinstall away.
    val unknownOptionRowFilled = EngineExitCause.summary(1, unknownOption, "--plan")
    checkTrue(
        "B7 a filled row is named as the likely source",
        unknownOptionRowFilled?.contains("扩展启动参数") == true,
    )
    checkFalse(
        "B8 and is not blamed on the payload",
        unknownOptionRowFilled?.contains("运行时载荷") == true,
    )
    checkTrue(
        "B9 and says a reinstall does not clear the row",
        unknownOptionRowFilled?.contains("重装 App") == true &&
            unknownOptionRowFilled?.contains("不会") == true,
    )
    checkTrue(
        "B10 and the fix is editing or clearing that row",
        unknownOptionRowFilled?.contains("清空") == true,
    )
    checkFalse(
        "B11 the row's own text is never quoted back",
        unknownOptionRowFilled?.contains("--plan") == true,
    )
    // It is an input to one rule only: an extension load failure stays the same
    // sentence whatever the row says.
    check(
        "B12 the row does not change another rule",
        EngineExitCause.summary(1, extensionStderr, "--plan"),
        extensionSummary,
    )

    // ------------------------------------------------------- argument-shape bugs
    // Not reachable today (the app passes neither), which is exactly why a match has
    // to be recognisable: it would mean argument building regressed.
    checkTrue(
        "C1 @file in rpc mode is attributed",
        EngineExitCause.summary(1, "Error: @file arguments are not supported in RPC mode") != null,
    )
    checkTrue(
        "C2 an unknown model is attributed",
        EngineExitCause.summary(1, "Error: Model \"bogus/bogus\" not found. Use --list-models to see available models.") != null,
    )
    // The word "not found" belongs to another failure and must not be read as one of
    // ours: without the "Model" half this would fire on a missing session file.
    val missingSession = EngineExitCause.summary(1, "Error: No session found matching 'x'")
    checkFalse("C3 a bare 'not found' is not an argument-shape verdict", missingSession?.contains("启动参数") == true)
    checkFalse("C4 and it does not name a model", missingSession?.contains("模型") == true)

    // ------------------------------------------------------------------ crashes
    checkTrue(
        "D1 an out-of-memory crash is attributed",
        EngineExitCause.summary(134, "FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory") != null,
    )
    checkTrue(
        "D2 a proot failure is attributed",
        EngineExitCause.summary(1, "proot error: can't initialize the loader") != null,
    )

    // --------------------------------------------------------- when it says nothing
    // An unattributable exit with nothing on stderr has to stay unattributable; the
    // caller's generic sentence is the honest answer.
    check("E1 no stderr and no exit code says nothing", EngineExitCause.summary(null, null), null)
    check("E2 a blank stderr and no exit code says nothing", EngineExitCause.summary(null, "   \n  "), null)
    // Exit code 1 with an empty stderr is still a real, explainable outcome - but it
    // must not be dressed up as a cause. The sentence says the engine wrote nothing
    // and names the two things that leave no trace.
    checkFalse("E3 an empty stderr is not a cause claim", EngineExitCause.summary(1, "")?.contains("扩展") == true)
    check("E4 a clean exit is not a failure", EngineExitCause.summary(0, ""), null)
    // Exit code 1 with no output is its own answer, and it must be the honest one:
    // the two things that leave no trace are named.
    val silent = EngineExitCause.summary(1, "")
    checkTrue("E5 a silent exit code 1 is explained without inventing a cause", silent?.contains("1") == true)
    // A signal-ish code must not be described as "engine exited with code 1".
    val killed = EngineExitCause.summary(134, "")
    checkTrue("E6 a signal exit is described as a kill", killed?.contains("终止") == true)

    // -------------------------------------------------------------- the evidence
    // The failure screen shows the engine's own line under the App's sentence, so
    // the line has to be the *matching* one and it has to be bounded.
    val long = "x".repeat(10_000)
    val evidence = EngineExitCause.evidence("Warning: something\n$unknownOption\n$long")
    check("F1 the evidence is the matching line", evidence, unknownOption)
    checkTrue("F2 the evidence is bounded", (evidence?.length ?: 0) <= EngineExitCause.EVIDENCE_MAX_CHARS + 1)
    check("F3 no match means no evidence", EngineExitCause.evidence("Warning: something else"), null)
    check("F4 blank stderr means no evidence", EngineExitCause.evidence(null), null)

    // The composed detail: the sentence first, then the engine's words, and the
    // whole thing is null when there is nothing to say.
    val detail = EngineExitCause.detail(1, extensionStderr)
    checkTrue("G1 the detail carries the sentence", detail?.startsWith(extensionSummary.orEmpty()) == true)
    checkTrue("G2 the detail carries the engine's line", detail?.contains("Failed to load extension") == true)
    check("G3 nothing to say means no detail", EngineExitCause.detail(null, ""), null)
    // `detail` is the one the production call site uses, so the row has to survive the
    // forwarding to `summary`.
    checkTrue(
        "G4 the detail forwards the row",
        EngineExitCause.detail(1, unknownOption, "--plan")?.contains("扩展启动参数") == true,
    )

    // The caller has to be able to call this with the raw capture the engine keeps,
    // including its trailing newline and blank lines.
    checkTrue(
        "H1 a trailing newline does not defeat the match",
        EngineExitCause.summary(1, unknownOption + "\n") != null,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
