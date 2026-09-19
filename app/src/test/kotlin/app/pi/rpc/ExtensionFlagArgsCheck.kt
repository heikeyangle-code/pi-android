package app.pi.rpc

// A bare-JVM check of the **extension CLI flag pass-through**, registered in
// `tools/run-app-pure-checks.sh` as `extension-flags` (the registration line is in
// the change report — this file does not edit the script).
//
// Why this check exists: everything here is a rule copied from pi's parser
// (`cli/args.ts:227-241`) or from where the parsed map ends up
// (`main.ts:737` → `core/agent-session-services.ts:8-43`). A rule copied by hand
// rots silently — and the failure mode is the quiet one this repository keeps
// paying for: a user types `--ssh user@host:/path`, the value silently becomes
// `true` or the next flag eats it, and nothing anywhere says so.
//
// Four kinds of assertion:
//
//   1. **the parse table**, one check per line of pi's (`=`, space, bare,
//      "next token starts with -/@", repeat-wins, single-dash, `--`, stray word);
//   2. **the two spellings agree** — `--x v` and `--x=v` must build the same
//      command line, because pi maps both onto one `unknownFlags` entry;
//   3. **argv order at the real call site** — `PiEngineHost.kt` is read as source
//      text (it imports Android), and the assertions pin that `--mode rpc` and
//      `--session-dir` come *before* the suffix, so a user flag can never be
//      mistaken for one of their values;
//   4. **the round trip** — `parse(tokenize(commandLineSuffix()))` reproduces the
//      flags exactly, which is what makes the quoting in the suffix correct rather
//      than merely plausible.

import java.io.File
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

/** `parse` of [text] as the option map pi would hold: name → value (null = `true`). */
private fun mapOf(text: String): Map<String, String?> =
    ExtensionFlagArgs.parse(text).flags.associate { it.name to it.value }

/** The flags a suffix round-trips to, through the same tokeniser. */
private fun roundTrip(options: PiLaunchOptions): List<ExtensionFlagArg> {
    val suffix = options.commandLineSuffix()
    val fromSuffix = ExtensionFlagArgs.parse(suffix.trim()).flags.filter { it.name !in APP_FLAG_NAMES }
    return fromSuffix
}

/** The app's own pre-spawn spellings, which the round trip must ignore. */
private val APP_FLAG_NAMES = setOf("system-prompt", "append-system-prompt", "no-context-files")

fun main() {
    val root = System.getProperty("pi.repo.root")?.let(::File)
        ?: File(System.getProperty("user.dir") ?: ".")

    // --------------------------------------------------------------- the table
    check(
        "an `=` value is taken verbatim: --plan=true",
        mapOf("--plan=true") == mapOf("plan" to "true"),
        "cli/args.ts:219-222",
    )
    check(
        "only the first `=` splits: --a=b=c",
        mapOf("--a=b=c") == mapOf("a" to "b=c"),
        "cli/args.ts:219-220 — arg.indexOf(\"=\")",
    )
    check(
        "an empty value survives: --a=",
        mapOf("--a=") == mapOf("a" to ""),
        "the app must be able to express the empty string pi's `=` form accepts",
    )
    check(
        "the `=` form is not re-examined: --a=-x",
        mapOf("--a=-x") == mapOf("a" to "-x"),
        "cli/args.ts:219-222 takes the value unconditionally; the `-`/`@` guard is only in the space form",
    )
    check(
        "a following plain token is the value and is consumed: --ssh user@host:/path",
        mapOf("--ssh user@host:/path") == mapOf("ssh" to "user@host:/path"),
        "cli/args.ts:224-231",
    )
    check(
        "the value may start with @: --a user@host (the token does not start with @)",
        mapOf("--a user@host") == mapOf("a" to "user@host"),
        "the guard is `next.startsWith(\"@\")`, so `user@host` is a value",
    )
    check(
        "a bare flag is true: --plan",
        mapOf("--plan") == mapOf("plan" to null),
        "cli/args.ts:232 — unknownFlags.set(name, true)",
    )
    check(
        "a following option is not eaten: --plan --verbose",
        mapOf("--plan --verbose") == mapOf("plan" to null, "verbose" to null),
        "cli/args.ts:225 — !next.startsWith(\"-\")",
    )
    check(
        "a repeated flag keeps the last value and one entry",
        ExtensionFlagArgs.parse("--a 1 --a 2").flags == listOf(ExtensionFlagArg("a", "2")),
        "unknownFlags is a Map: later set() wins (cli/args.ts:221/:227)",
    )
    check(
        "the first position is kept for a repeated flag",
        ExtensionFlagArgs.parse("--a 1 --b 2 --a 3").flags.map { it.name } == listOf("a", "b"),
        "a Map keeps insertion order, so the surviving entry stays where it first appeared",
    )
    check(
        "quoted values stay one token: --msg \"a b\"",
        mapOf("--msg \"a b\"") == mapOf("msg" to "a b"),
        "the string is tokenised first, because pi's rules are defined over argv tokens",
    )
    check(
        "single quotes do the same: --msg 'a b'",
        mapOf("--msg 'a b'") == mapOf("msg" to "a b"),
    )
    check(
        "several flags in one line keep their order",
        ExtensionFlagArgs.parse("--a 1 --b --c=3").flags ==
            listOf(ExtensionFlagArg("a", "1"), ExtensionFlagArg("b", null), ExtensionFlagArg("c", "3")),
    )
    check("blank text parses to nothing", ExtensionFlagArgs.parse("   \n  ").ok &&
        ExtensionFlagArgs.parse(null).flags.isEmpty())

    // ------------------------------------------------------- the app's refusals
    val single = ExtensionFlagArgs.parse("--ok 1 -x")
    check(
        "a single-dash option is refused, not passed",
        !single.ok && single.reason == Refusal.SingleDashOption,
        "pi: an error diagnostic (cli/args.ts:234-235) that exits the process (main.ts:473-481)",
    )
    val atFile = ExtensionFlagArgs.parse("--plan @notes.md")
    check(
        "an @file token is refused",
        !atFile.ok && atFile.reason == Refusal.AtFileArgument,
        "pi: \"@file arguments are not supported in RPC mode\" + exit 1 (main.ts:506-509)",
    )
    val endOfOptions = ExtensionFlagArgs.parse("--plan -- --x")
    check(
        "`--` is refused",
        !endOfOptions.ok && endOfOptions.reason == Refusal.EndOfOptions,
        "pi: everything after -- is positional (cli/args.ts:23-31), not an extension flag",
    )
    val positional = ExtensionFlagArgs.parse("hello")
    check(
        "a stray word is refused",
        !positional.ok && positional.reason == Refusal.Positional,
        "pi: a bare word is a positional message (cli/args.ts:237-238); the row is for flags",
    )
    check(
        "a refused text emits no flags at all",
        ExtensionFlagArgs.parse("--ok 1 -x").flags.isEmpty() &&
            ExtensionFlagArgs.parse("--ok 1 -x").argvTokens().isEmpty(),
        "nothing is passed when anything is refused: pi would exit on the whole command line",
    )
    check(
        "the refusal is a sentence in the user's language, naming the token",
        ExtensionFlagArgs.parse("-x").refusal?.contains("-x") == true &&
            ExtensionFlagArgs.parse("--plan @f").refusal?.contains("@f") == true,
    )
    check(
        "no registration check: an unknown flag name is accepted",
        ExtensionFlagArgs.parse("--no-extension-registers-this 7").ok,
        "whether an extension registered it is knowable only inside pi " +
            "(core/agent-session-services.ts:13-19); a whitelist here would be a guess",
    )

    // ----------------------------------------------- the two spellings agree
    val spaceForm = PiLaunchOptions.fromSettingValues(null, null, null, null, null, "--ssh user@host:/p")
    val equalsForm = PiLaunchOptions.fromSettingValues(null, null, null, null, null, "--ssh=user@host:/p")
    check(
        "`--x v` and `--x=v` build the same command line",
        spaceForm.commandLineSuffix() == equalsForm.commandLineSuffix(),
        "space=${spaceForm.commandLineSuffix()} equals=${equalsForm.commandLineSuffix()}",
    )
    check(
        "a bare flag and `=true` both reach pi as a boolean-ish flag",
        spaceForm.commandLineSuffix().isNotEmpty() && mapOf("--plan=true")["plan"] == "true",
    )

    // ------------------------------------------------------------- the suffix
    val everything = PiLaunchOptions.fromSettingValues(
        offline = true,
        cacheRetention = "long",
        systemPrompt = "base",
        appendSystemPrompt = "extra",
        noContextFiles = true,
        extensionArgs = "--plan --ssh user@host:/path",
    )
    val suffix = everything.commandLineSuffix()
    check(
        "the app's own flags come first, the extension flags last",
        suffix.indexOf("--system-prompt") < suffix.indexOf("--append-system-prompt") &&
            suffix.indexOf("--append-system-prompt") < suffix.indexOf("--no-context-files") &&
            suffix.indexOf("--no-context-files") < suffix.indexOf("--plan") &&
            suffix.indexOf("--plan") < suffix.indexOf("--ssh"),
        "got $suffix",
    )
    check(
        "an ordinary flag keeps its spelling (no gratuitous quoting)",
        suffix.contains(" --plan") && !suffix.contains("'--plan'"),
        "got $suffix",
    )
    check(
        "a value the shell would act on is quoted",
        PiLaunchOptions.fromSettingValues(null, null, null, null, null, "--msg 'a b'")
            .commandLineSuffix().contains("'a b'"),
    )
    check(
        "a value with a quote in it is quoted safely",
        PiLaunchOptions.fromSettingValues(null, null, null, null, null, "--msg \"it's\"")
            .commandLineSuffix().contains("'it'\\''s'"),
    )

    // --------------------------------------------------------- the round trip
    check(
        "round trip: --plan --ssh user@host:/path",
        roundTrip(everything) == listOf(ExtensionFlagArg("plan", null), ExtensionFlagArg("ssh", "user@host:/path")),
        "suffix=$suffix → ${roundTrip(everything)}",
    )
    val tricky = listOf(
        "--a 1 --b --c=3",
        "--msg \"a b\" --empty= --eq=a=b --at=@x",
        "--中文 值 --tab \"a\tb\"",
        "--plan",
    )
    for (text in tricky) {
        val options = PiLaunchOptions.fromSettingValues(null, null, null, null, null, text)
        check(
            "round trip through bash-quoting: $text",
            roundTrip(options) == ExtensionFlagArgs.parse(text).flags,
            "suffix=${options.commandLineSuffix()}",
        )
    }

    // ------------------------------------------------- argv order at the launch
    val hostFile = File(root, "app/src/main/kotlin/app/pi/engine/PiEngineHost.kt")
    check("the engine host is readable at $hostFile", hostFile.isFile)
    if (hostFile.isFile) {
        val host = hostFile.readText()
        val modeAt = host.indexOf("--mode rpc")
        val sessionDirAt = host.indexOf("--session-dir ")
        val suffixAt = host.indexOf("launch.commandLineSuffix()")
        check(
            "the engine host passes --mode rpc before the session dir",
            modeAt >= 0 && sessionDirAt > modeAt,
            "PiEngineHost must name both, in that order",
        )
        check(
            "the extension suffix is appended after --session-dir (so no value is eaten)",
            suffixAt > sessionDirAt && sessionDirAt >= 0,
            "mode=$modeAt sessionDir=$sessionDirAt suffix=$suffixAt — the user's flags must come " +
                "after every app flag that has a value, or a user token could become our value",
        )
    }

    if (failures > 0) {
        println("\nharness: FAILED ($failures)")
        exitProcess(1)
    }
    println("\nharness: OK")
}
